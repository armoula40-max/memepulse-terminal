import AsyncStorage from "@react-native-async-storage/async-storage";
import * as BackgroundTask from "expo-background-task";
import * as Notifications from "expo-notifications";
import * as TaskManager from "expo-task-manager";
import { loadPumpPortalKey } from "./local-secrets";

export const LOCAL_MARKET_TASK = "memepulse-local-market-monitor";
const SEEN_KEY = "memepulse.local-monitor.seen.v1";
void Notifications.setNotificationChannelAsync("meme-catch-v2", { name: "MemePulse Catch Signals", importance: Notifications.AndroidImportance.MAX, sound: "shopify_catch.wav", vibrationPattern: [0, 180, 80, 280] });

const PROFILE_URLS = ["https://api.dexscreener.com/token-profiles/latest/v1", "https://api.dexscreener.com/token-boosts/latest/v1"];

type Profile = { chainId?: string; tokenAddress?: string; description?: string; url?: string };
type Pair = { chainId?: string; baseToken?: { address?: string; symbol?: string; name?: string }; priceUsd?: string; liquidity?: { usd?: number }; volume?: { h1?: number }; priceChange?: { h1?: number }; txns?: { h1?: { buys?: number; sells?: number } }; url?: string };


async function inspectPumpPortal(key: string) {
  return new Promise<number>((resolve) => {
    const WebSocketCtor = (globalThis as unknown as { WebSocket?: new (url: string) => any }).WebSocket;
    if (!WebSocketCtor) { resolve(0); return; }
    const socket = new WebSocketCtor(`wss://pumpportal.fun/api/data?api-key=${encodeURIComponent(key)}`);
    let count = 0;
    const timer = setTimeout(() => { try { socket.close(); } catch {} resolve(count); }, 7_000);
    socket.onopen = () => socket.send(JSON.stringify({ method: "subscribeNewToken" }));
    socket.onmessage = async (message: { data?: string }) => {
      try {
        const item = JSON.parse(String(message.data ?? "{}"));
        if (item.txType !== "create" || !item.mint) return;
        count += 1;
        await Notifications.scheduleNotificationAsync({ content: { title: `PumpPortal catch: $${item.symbol ?? "TOKEN"}`, body: `New Solana token · initial buy ${item.initialBuy ?? "—"} · tap to inspect`, data: { address: String(item.mint) }, sound: "shopify_catch.wav" }, trigger: { type: Notifications.SchedulableTriggerInputTypes.TIME_INTERVAL, seconds: 1, repeats: false, channelId: "meme-catch-v2" } });
      } catch {}
    };
    socket.onerror = () => { clearTimeout(timer); resolve(count); };
  });
}

async function inspectMarket() {
  const pumpPortalKey = await loadPumpPortalKey();
  if (pumpPortalKey) return inspectPumpPortal(pumpPortalKey);
  const lists = await Promise.all(PROFILE_URLS.map(async (url) => { const response = await fetch(url); return response.ok ? await response.json() as Profile[] : []; }));
  const addresses = Array.from(new Set(lists.flat().filter((item) => item.chainId === "solana" && item.tokenAddress).map((item) => item.tokenAddress as string))).slice(0, 80);
  if (!addresses.length) return 0;
  const response = await fetch(`https://api.dexscreener.com/tokens/v1/solana/${addresses.join(",")}`);
  if (!response.ok) return 0;
  const pairs = await response.json() as Pair[];
  const seen = new Set(JSON.parse((await AsyncStorage.getItem(SEEN_KEY)) ?? "[]") as string[]);
  let notifications = 0;
  for (const pair of pairs) {
    if (pair.chainId !== "solana" || !pair.baseToken?.address) continue;
    const address = pair.baseToken.address;
    const buys = pair.txns?.h1?.buys ?? 0;
    const sells = pair.txns?.h1?.sells ?? 0;
    const liquidity = pair.liquidity?.usd ?? 0;
    const change = pair.priceChange?.h1 ?? 0;
    const score = (liquidity >= 20_000 ? 30 : 0) + (buys > sells ? 25 : 0) + (change > 0 ? 20 : 0) + ((pair.volume?.h1 ?? 0) > 10_000 ? 25 : 0);
    const key = `${address}:${Math.round(score / 10)}:${Math.floor(Date.now() / 900_000)}`;
    if (score < 55 || seen.has(key)) continue;
    seen.add(key);
    notifications += 1;
    await Notifications.scheduleNotificationAsync({ content: { title: `MemePulse catch: $${pair.baseToken.symbol ?? "TOKEN"}`, body: `Score ${score}/100 · price $${pair.priceUsd ?? "—"} · liquidity $${Math.round(liquidity).toLocaleString()}`, data: { address }, sound: "shopify_catch.wav" }, trigger: { type: Notifications.SchedulableTriggerInputTypes.TIME_INTERVAL, seconds: 1, repeats: false, channelId: "meme-catch-v2" } });
  }
  await AsyncStorage.setItem(SEEN_KEY, JSON.stringify(Array.from(seen).slice(-500)));
  return notifications;
}

TaskManager.defineTask(LOCAL_MARKET_TASK, async () => { try { await inspectMarket(); return BackgroundTask.BackgroundTaskResult.Success; } catch { return BackgroundTask.BackgroundTaskResult.Failed; } });

export async function enableLocalMarketMonitoring() {
  const status = await BackgroundTask.getStatusAsync();
  if (status !== BackgroundTask.BackgroundTaskStatus.Available) throw new Error("Android background monitoring is restricted on this device.");
  await Notifications.requestPermissionsAsync();
  const registered = await TaskManager.isTaskRegisteredAsync(LOCAL_MARKET_TASK);
  if (!registered) await BackgroundTask.registerTaskAsync(LOCAL_MARKET_TASK, { minimumInterval: 15 });
  return true;
}

export async function disableLocalMarketMonitoring() { if (await TaskManager.isTaskRegisteredAsync(LOCAL_MARKET_TASK)) await BackgroundTask.unregisterTaskAsync(LOCAL_MARKET_TASK); }
export async function runLocalMarketCheckNow() { return inspectMarket(); }
