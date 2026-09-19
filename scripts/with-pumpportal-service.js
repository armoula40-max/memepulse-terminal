const { withDangerousMod, withPlugins } = require("@expo/config-plugins");
const fs = require("fs");
const path = require("path");

const PACKAGE = "com.app.meme.pulse";
const JAVA_DIR = "android/app/src/main/java/com/app/meme/pulse";
const SERVICE = `package ${PACKAGE};

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.annotation.Nullable;
import com.facebook.react.bridge.*;
import okhttp3.*;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

public class PumpPortalForegroundService extends Service {
  static final String ACTION_START = "${PACKAGE}.START_PUMPPORTAL";
  static final String ACTION_STOP = "${PACKAGE}.STOP_PUMPPORTAL";
  static final String CHANNEL = "pumpportal_monitor";
  static final int NOTIFICATION_ID = 7311;
  OkHttpClient client;
  WebSocket socket;
  String apiKey = "";
  java.util.HashSet<String> seen = new java.util.HashSet<>();
  boolean autoBuy = false;
  double autoBuyUsd = 100.0;
  String autoBuyMode = "strict";

  @Override public void onCreate() {
    super.onCreate();
    createChannel();
    client = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build();
    android.content.SharedPreferences paper = getSharedPreferences("paper_auto_buy", MODE_PRIVATE);
    autoBuy = paper.getBoolean("enabled", false);
    autoBuyUsd = Double.longBitsToDouble(paper.getLong("amount", Double.doubleToLongBits(100.0)));
    autoBuyMode = paper.getString("mode", "strict");
  }
  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
    if (intent != null && intent.hasExtra("apiKey")) apiKey = intent.getStringExtra("apiKey");
    startForeground(NOTIFICATION_ID, persistent("MemePulse is monitoring PumpPortal"));
    if (apiKey != null && !apiKey.isEmpty() && socket == null) connect();
    return START_STICKY;
  }
  void connect() {
    Request request = new Request.Builder().url("wss://pumpportal.fun/api/data?api-key=" + okhttp3.HttpUrl.parse("https://x/?k=" + apiKey).queryParameter("k")).build();
    socket = client.newWebSocket(request, new WebSocketListener() {
      @Override public void onOpen(WebSocket ws, Response response) { ws.send("{\\"method\\":\\"subscribeNewToken\\"}"); }
      @Override public void onMessage(WebSocket ws, String text) { handle(text); }
      @Override public void onFailure(WebSocket ws, Throwable t, Response response) { socket = null; scheduleReconnect(); }
      @Override public void onClosed(WebSocket ws, int code, String reason) { socket = null; scheduleReconnect(); }
    });
  }
  void scheduleReconnect() { new Handler(Looper.getMainLooper()).postDelayed(() -> { if (!isStopped() && socket == null) connect(); }, 5000); }
  void handle(String text) {
    try {
      JSONObject item = new JSONObject(text);
      if (!item.has("mint") || (item.has("txType") && !"create".equals(item.optString("txType")))) return;
      String mint = item.optString("mint");
      if (seen.contains(mint)) return;
      seen.add(mint);
      if (seen.size() > 500) seen.clear();
      String symbol = item.optString("symbol", "UNKNOWN");
      String name = item.optString("name", "New token");
      notifyToken(symbol, name, mint);
      if (autoBuy) evaluatePaperAutoBuy(mint, symbol);
    } catch (Exception ignored) {}
  }
  void notifyToken(String symbol, String name, String mint) {
    NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
    Notification notification = new Notification.Builder(this, CHANNEL)
      .setSmallIcon(android.R.drawable.ic_popup_sync)
      .setContentTitle("NEW PUMPPORTAL TOKEN · $" + symbol)
      .setContentText(name + " · " + mint.substring(0, Math.min(6, mint.length())) + "…")
      .setStyle(new Notification.BigTextStyle().bigText("New token received from PumpPortal. Open MemePulse to run the selected filter mode."))
      .setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE).setPriority(Notification.PRIORITY_HIGH).build();
    manager.notify((int)(System.currentTimeMillis() % 1000000), notification);
  }
  void evaluatePaperAutoBuy(String mint, String symbol) {
    Request request = new Request.Builder().url("https://api.dexscreener.com/latest/dex/tokens/" + mint).build();
    client.newCall(request).enqueue(new Callback() {
      public void onFailure(Call call, java.io.IOException e) {}
      public void onResponse(Call call, Response response) throws java.io.IOException {
        if (!response.isSuccessful() || response.body() == null) return;
        try {
          JSONObject root = new JSONObject(response.body().string());
          org.json.JSONArray pairs = root.optJSONArray("pairs");
          if (pairs == null || pairs.length() == 0) return;
          JSONObject pair = pairs.getJSONObject(0);
          JSONObject liq = pair.optJSONObject("liquidity");
          JSONObject vol = pair.optJSONObject("volume");
          JSONObject change = pair.optJSONObject("priceChange");
          JSONObject tx = pair.optJSONObject("txns");
          JSONObject h1 = tx == null ? null : tx.optJSONObject("h1");
          double price = pair.optDouble("priceUsd", 0);
          double liquidity = liq == null ? 0 : liq.optDouble("usd", 0);
          double volume = vol == null ? 0 : vol.optDouble("h1", 0);
          double mc = pair.optDouble("marketCap", pair.optDouble("fdv", 0));
          double momentum = change == null ? 0 : change.optDouble("h1", 0);
          double buys = h1 == null ? 0 : h1.optDouble("buys", 0);
          double sells = h1 == null ? 0 : h1.optDouble("sells", 0);
          double ratio = buys + sells > 0 ? buys * 100.0 / (buys + sells) : 0;
          double minLiq = "early".equals(autoBuyMode) ? 3000 : ("balanced".equals(autoBuyMode) ? 7500 : 10000);
          double minMc = "early".equals(autoBuyMode) ? 8000 : ("balanced".equals(autoBuyMode) ? 15000 : 20000);
          double minVol = "early".equals(autoBuyMode) ? 1000 : ("balanced".equals(autoBuyMode) ? 3000 : 5000);
          double minBuy = "early".equals(autoBuyMode) ? 52 : ("balanced".equals(autoBuyMode) ? 54 : 55);
          if (price <= 0 || liquidity < minLiq || mc < minMc || volume < minVol || ratio < minBuy || momentum < ("early".equals(autoBuyMode) ? 3 : ("balanced".equals(autoBuyMode) ? 2 : 0))) return;
          savePaperOrder(mint, symbol, price, ratio, liquidity, mc);
        } catch (Exception ignored) {}
      }
    });
  }
  void savePaperOrder(String mint, String symbol, double price, double ratio, double liquidity, double mc) {
    android.content.SharedPreferences p = getSharedPreferences("paper_auto_buy", MODE_PRIVATE);
    try {
      org.json.JSONArray orders = new org.json.JSONArray(p.getString("orders", "[]"));
      for (int i = 0; i < orders.length(); i++) if (mint.equals(orders.getJSONObject(i).optString("address"))) return;
      JSONObject order = new JSONObject(); order.put("id", "auto-" + System.currentTimeMillis()); order.put("address", mint); order.put("symbol", symbol); order.put("side", "BUY"); order.put("notionalUsd", autoBuyUsd); order.put("fillPriceUsd", price); order.put("qty", autoBuyUsd / price); order.put("feeUsd", autoBuyUsd * 0.003); order.put("createdAt", new java.util.Date().toString()); order.put("status", "FILLED"); order.put("mode", autoBuyMode); order.put("buyRatio", ratio); order.put("liquidityUsd", liquidity); order.put("marketCapUsd", mc);
      orders.put(order); p.edit().putString("orders", orders.toString()).apply();
      notifyAutoBuy(symbol);
    } catch (Exception ignored) {}
  }
  void notifyAutoBuy(String symbol) { NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE); Notification n = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("PAPER AUTO-BUY · $" + symbol).setContentText("Simulated order · $" + String.format(java.util.Locale.US, "%.2f", autoBuyUsd) + " · " + autoBuyMode).setAutoCancel(true).setPriority(Notification.PRIORITY_HIGH).build(); manager.notify((int)(System.currentTimeMillis() % 1000000), n); }
  Notification persistent(String text) { return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("MemePulse background monitor").setContentText(text).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build(); }
  void createChannel() { if (Build.VERSION.SDK_INT >= 26) { NotificationChannel c = new NotificationChannel(CHANNEL, "PumpPortal monitoring", NotificationManager.IMPORTANCE_HIGH); c.setDescription("Instant new-token notifications"); ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c); } }
  @Override public void onDestroy() { if (socket != null) socket.close(1000, "service stopped"); if (client != null) client.dispatcher().executorService().shutdown(); super.onDestroy(); }
  @Nullable @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
`;
const PACKAGE_MODULE = `package ${PACKAGE};
import android.content.*;
import com.facebook.react.*;
import com.facebook.react.bridge.*;
public class PumpPortalServiceModule extends ReactContextBaseJavaModule {
  PumpPortalServiceModule(ReactApplicationContext c) { super(c); }
  @Override public String getName() { return "PumpPortalService"; }
  @ReactMethod public void start(String apiKey) { Intent i = new Intent(getReactApplicationContext(), PumpPortalForegroundService.class); i.setAction(PumpPortalForegroundService.ACTION_START); i.putExtra("apiKey", apiKey); if (android.os.Build.VERSION.SDK_INT >= 26) getReactApplicationContext().startForegroundService(i); else getReactApplicationContext().startService(i); }
  @ReactMethod public void stop() { getReactApplicationContext().stopService(new Intent(getReactApplicationContext(), PumpPortalForegroundService.class)); }
  @ReactMethod public void configureAutoBuy(boolean enabled, double amount, String mode) { getReactApplicationContext().getSharedPreferences("paper_auto_buy", 0).edit().putBoolean("enabled", enabled).putLong("amount", Double.doubleToLongBits(Math.max(1.0, Math.min(amount, 1000.0)))).putString("mode", mode == null ? "strict" : mode).apply(); }
  @ReactMethod public void getAutoBuyOrders(Promise promise) { promise.resolve(getReactApplicationContext().getSharedPreferences("paper_auto_buy", 0).getString("orders", "[]")); }
}
`;
const PACKAGE_SOURCE = `package ${PACKAGE};
import com.facebook.react.*;
import com.facebook.react.bridge.*;
import java.util.*;
public class PumpPortalServicePackage implements ReactPackage {
  public List<NativeModule> createNativeModules(ReactApplicationContext c) { return Arrays.<NativeModule>asList(new PumpPortalServiceModule(c)); }
  public List<ViewManager> createViewManagers(ReactApplicationContext c) { return Collections.emptyList(); }
}
`;
const RECEIVER = `package ${PACKAGE};
import android.content.*;
public class PumpPortalBootReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent i) { if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) { /* service restarts when the app next supplies the locally stored key */ } }
}
`;
function writeFile(config, { modRequest }) {
  const root = modRequest.platformProjectRoot;
  const dir = path.join(root, JAVA_DIR);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, "PumpPortalForegroundService.java"), SERVICE);
  fs.writeFileSync(
    path.join(dir, "PumpPortalServiceModule.java"),
    PACKAGE_MODULE,
  );
  fs.writeFileSync(
    path.join(dir, "PumpPortalServicePackage.java"),
    PACKAGE_SOURCE,
  );
  fs.writeFileSync(path.join(dir, "PumpPortalBootReceiver.java"), RECEIVER);
  const gradle = path.join(root, "app/build.gradle");
  if (fs.existsSync(gradle)) {
    let text = fs.readFileSync(gradle, "utf8");
    if (!text.includes("com.squareup.okhttp3:okhttp"))
      text = text.replace(
        "dependencies {",
        'dependencies {\n    implementation "com.squareup.okhttp3:okhttp:4.12.0"',
      );
    fs.writeFileSync(gradle, text);
  }
  const app = path.join(root, "app/src/main/AndroidManifest.xml");
  if (fs.existsSync(app)) {
    let text = fs.readFileSync(app, "utf8");
    const service = `<service android:name=".PumpPortalForegroundService" android:exported="false" android:foregroundServiceType="dataSync" />`;
    if (!text.includes("PumpPortalForegroundService"))
      text = text.replace("</application>", `    ${service}\n  </application>`);
    if (!text.includes("FOREGROUND_SERVICE"))
      text = text
        .replace("<manifest ", "<manifest ")
        .replace(
          ">",
          '>\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />\n',
          1,
        );
    fs.writeFileSync(app, text);
  }
  const main = path.join(dir, "MainApplication.kt");
  if (fs.existsSync(main)) {
    let text = fs.readFileSync(main, "utf8");
    if (!text.includes("PumpPortalServicePackage")) {
      text = text.replace(
        /(package [^\n]+\n)/,
        `$1\nimport ${PACKAGE}.PumpPortalServicePackage\n`,
      );
      text = text.replace(
        "PackageList(this).packages.apply {",
        "PackageList(this).packages.apply {\n          add(PumpPortalServicePackage())",
      );
      fs.writeFileSync(main, text);
    }
  }
  return config;
}
module.exports = function withPumpPortalService(config) {
  return withPlugins(config, [
    [withDangerousMod, { platform: "android", mod: writeFile }],
  ]);
};
