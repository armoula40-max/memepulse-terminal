import { useEffect, useMemo, useRef, useState } from "react";
import * as Notifications from "expo-notifications";
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  View,
  Platform,
} from "react-native";
import { MaterialIcons } from "@expo/vector-icons";
import { ScreenContainer } from "@/components/screen-container";
import { trpc } from "@/lib/trpc";
import {
  DEFAULT_PREFERENCES,
  loadPreferences,
  type Preferences,
} from "@/lib/preferences";
import { evaluateToken } from "@/lib/token-rules";

const C = {
  bg: "#07100F",
  surface: "#0D1B18",
  border: "#1C3A33",
  text: "#F2F8F5",
  muted: "#88A69A",
  mint: "#76F2B6",
  mintSoft: "#143E30",
  amber: "#F8C36A",
  red: "#FF7B80",
  blue: "#8BB8FF",
};
Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowAlert: true,
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: true,
    shouldSetBadge: true,
  }),
});

const SIGNAL_CHANNEL_ID = "memepulse-signals";
async function configureNotifications() {
  if (Platform.OS === "android") {
    await Notifications.setNotificationChannelAsync(SIGNAL_CHANNEL_ID, {
      name: "MemePulse Signals",
      importance: Notifications.AndroidImportance.MAX,
      sound: "memepulse-signal.wav",
      vibrationPattern: [0, 250, 120, 250],
      lockscreenVisibility: Notifications.AndroidNotificationVisibility.PUBLIC,
    });
  }
  const permissions = await Notifications.getPermissionsAsync();
  if (!permissions.granted) await Notifications.requestPermissionsAsync();
}

type AlertItem = {
  id: string;
  title: string;
  detail: string;
  time: string;
  kind: "buy" | "info" | "risk";
  icon: keyof typeof MaterialIcons.glyphMap;
};

export default function AlertsScreen() {
  const [enabled, setEnabled] = useState({
    momentum: true,
    liquidity: true,
    risk: false,
  });
  const [prefs, setPrefs] = useState<Preferences>(DEFAULT_PREFERENCES);
  const [readIds, setReadIds] = useState<string[]>([]);
  const market = trpc.market.latest.useQuery(undefined, {
    staleTime: 25_000,
    refetchInterval: 30_000,
  });
  const newFeed = trpc.market.newTokens.useQuery(undefined, {
    staleTime: 10_000,
    refetchInterval: 30_000,
  });
  const notifiedBuySignals = useRef<Set<string>>(new Set());
  const notifiedEvents = useRef<string>("");
  useEffect(() => {
    void loadPreferences().then(setPrefs);
    void configureNotifications();
  }, []);

  const buySignals = useMemo(
    () =>
      (market.data?.tokens ?? [])
        .filter(
          (token) =>
            prefs.algorithm.enabled &&
            evaluateToken(token, prefs.algorithm).eligible,
        )
        .slice(0, 10),
    [market.data, prefs.algorithm],
  );
  const inAppAlerts = useMemo<AlertItem[]>(() => {
    const items: AlertItem[] = [];
    buySignals.forEach((token) =>
      items.push({
        id: `buy-${token.address}`,
        title: `BUY SIGNAL · $${token.symbol}`,
        detail: "All configured screening rules passed · research signal only",
        time: new Date(token.observedAt).toLocaleTimeString(),
        kind: "buy",
        icon: "bolt",
      }),
    );
    (newFeed.data?.events ?? []).slice(0, 8).forEach((event) =>
      items.push({
        id: `event-${event.mint}`,
        title: `New token · $${event.symbol}`,
        detail: `${event.name} · review risk and on-chain safety`,
        time: new Date(event.createdAt).toLocaleTimeString(),
        kind: "info",
        icon: "fiber-new",
      }),
    );
    (market.data?.tokens ?? [])
      .filter((token) => token.liquidityUsd < 25_000 || token.change1hPct < -25)
      .slice(0, 5)
      .forEach((token) =>
        items.push({
          id: `risk-${token.address}`,
          title: `Risk watch · $${token.symbol}`,
          detail: `${token.liquidityUsd < 25_000 ? "Thin liquidity" : "Sharp decline"} · inspect before acting`,
          time: new Date(token.observedAt).toLocaleTimeString(),
          kind: "risk",
          icon: "warning",
        }),
      );
    return items;
  }, [buySignals, newFeed.data, market.data]);
  const unread = inAppAlerts.filter(
    (item) => !readIds.includes(item.id),
  ).length;

  useEffect(() => {
    for (const token of buySignals) {
      if (notifiedBuySignals.current.has(token.address)) continue;
      notifiedBuySignals.current.add(token.address);
      void Notifications.scheduleNotificationAsync({
        content: {
          title: `BUY SIGNAL · $${token.symbol}`,
          body: "All configured rules passed. Open MemePulse to review evidence.",
          sound: "memepulse-signal.wav",
        },
        trigger: {
          type: Notifications.SchedulableTriggerInputTypes.TIME_INTERVAL,
          seconds: 1,
          repeats: false,
          channelId: SIGNAL_CHANNEL_ID,
        },
      });
    }
    const event = newFeed.data?.events?.[0];
    if (
      event &&
      notifiedEvents.current &&
      notifiedEvents.current !== event.createdAt
    ) {
      void Notifications.scheduleNotificationAsync({
        content: {
          title: `New token · $${event.symbol}`,
          body: "A new creation event is available in the in-app notification center.",
          sound: "memepulse-signal.wav",
        },
        trigger: {
          type: Notifications.SchedulableTriggerInputTypes.TIME_INTERVAL,
          seconds: 1,
          repeats: false,
          channelId: SIGNAL_CHANNEL_ID,
        },
      });
    }
    if (event) notifiedEvents.current = event.createdAt;
  }, [buySignals, newFeed.data?.events]);

  const set = (key: keyof typeof enabled) =>
    setEnabled((old) => ({ ...old, [key]: !old[key] }));
  return (
    <ScreenContainer className="px-5" containerClassName="bg-background">
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.header}>
          <View>
            <Text style={styles.kicker}>MONITORING CENTER</Text>
            <Text style={styles.title}>Notifications</Text>
            <Text style={styles.subtitle}>
              Instant Buy Signals are separated from normal in-app activity.
            </Text>
          </View>
          {unread > 0 ? (
            <View style={styles.badge}>
              <Text style={styles.badgeText}>{unread}</Text>
            </View>
          ) : null}
        </View>
        <View style={styles.summary}>
          <View style={styles.summaryIcon}>
            <MaterialIcons
              name="notifications-active"
              size={22}
              color={C.mint}
            />
          </View>
          <View style={{ flex: 1 }}>
            <Text style={styles.summaryTitle}>
              {unread
                ? `${unread} unread notification${unread === 1 ? "" : "s"}`
                : "All notifications read"}
            </Text>
            <Text style={styles.summaryText}>
              {market.data
                ? `Observed ${new Date(market.data.observedAt).toLocaleTimeString()}`
                : "Waiting for public data"}
            </Text>
          </View>
          {unread > 0 ? (
            <Pressable
              onPress={() => setReadIds(inAppAlerts.map((item) => item.id))}
              style={styles.readButton}
            >
              <Text style={styles.readButtonText}>MARK READ</Text>
            </Pressable>
          ) : (
            <View style={styles.greenDot} />
          )}
        </View>
        <Text style={styles.section}>INSTANT BUY SIGNALS</Text>
        {buySignals.length ? (
          buySignals.map((item) => (
            <Signal
              key={item.address}
              icon="bolt"
              title={`BUY SIGNAL · $${item.symbol}`}
              time={new Date(item.observedAt).toLocaleTimeString()}
              detail="All configured rules passed · review evidence before any independent action"
              color={C.mint}
              unread={!readIds.includes(`buy-${item.address}`)}
            />
          ))
        ) : (
          <View style={styles.empty}>
            <MaterialIcons name="bolt" size={20} color={C.muted} />
            <Text style={styles.footerText}>
              No complete Buy Signal right now. Missing on-chain data remains
              pending.
            </Text>
          </View>
        )}
        <Text style={styles.section}>IN-APP NOTIFICATION CENTER</Text>
        {inAppAlerts.filter((item) => item.kind !== "buy").length ? (
          inAppAlerts
            .filter((item) => item.kind !== "buy")
            .map((item) => (
              <Signal
                key={item.id}
                icon={item.icon}
                title={item.title}
                time={item.time}
                detail={item.detail}
                color={item.kind === "risk" ? C.amber : C.blue}
                unread={!readIds.includes(item.id)}
              />
            ))
        ) : (
          <Text style={styles.footerText}>
            No other notifications are available.
          </Text>
        )}
        <Text style={styles.section}>ALERT RULES</Text>
        <Rule
          icon="trending-up"
          title="Momentum breakout"
          description="Enable local momentum notifications"
          value={enabled.momentum}
          onChange={() => set("momentum")}
          color={C.mint}
        />
        <Rule
          icon="water-drop"
          title="Liquidity expansion"
          description="Enable liquidity activity notifications"
          value={enabled.liquidity}
          onChange={() => set("liquidity")}
          color={C.blue}
        />
        <Rule
          icon="warning"
          title="Risk escalation"
          description="Enable thin liquidity and decline warnings"
          value={enabled.risk}
          onChange={() => set("risk")}
          color={C.amber}
        />
        <View style={styles.footer}>
          <MaterialIcons name="lock" size={15} color={C.muted} />
          <Text style={styles.footerText}>
            Buy Signals are informational only and never submit transactions or
            connect a wallet.
          </Text>
        </View>
      </ScrollView>
    </ScreenContainer>
  );
}
function Rule({
  icon,
  title,
  description,
  value,
  onChange,
  color,
}: {
  icon: keyof typeof MaterialIcons.glyphMap;
  title: string;
  description: string;
  value: boolean;
  onChange: () => void;
  color: string;
}) {
  return (
    <View style={styles.rule}>
      <View style={[styles.ruleIcon, { backgroundColor: `${color}18` }]}>
        <MaterialIcons name={icon} size={18} color={color} />
      </View>
      <View style={styles.ruleCopy}>
        <Text style={styles.ruleTitle}>{title}</Text>
        <Text style={styles.ruleDescription}>{description}</Text>
      </View>
      <Switch
        value={value}
        onValueChange={onChange}
        trackColor={{ false: "#1A3029", true: "#2C7459" }}
        thumbColor={value ? C.mint : C.muted}
      />
    </View>
  );
}
function Signal({
  icon,
  title,
  time,
  detail,
  color,
  unread,
}: {
  icon: keyof typeof MaterialIcons.glyphMap;
  title: string;
  time: string;
  detail: string;
  color: string;
  unread?: boolean;
}) {
  return (
    <View style={[styles.signal, unread && styles.signalUnread]}>
      <MaterialIcons name={icon} size={16} color={color} />
      <View style={{ flex: 1 }}>
        <View style={styles.signalTitleRow}>
          <Text style={styles.signalTitle}>{title}</Text>
          {unread ? <View style={styles.unreadDot} /> : null}
        </View>
        <Text style={styles.signalDetail}>{detail}</Text>
      </View>
      <Text style={styles.signalTime}>{time}</Text>
    </View>
  );
}
const styles = StyleSheet.create({
  content: { paddingTop: 16, paddingBottom: 32, gap: 12 },
  header: {
    flexDirection: "row",
    alignItems: "flex-start",
    justifyContent: "space-between",
  },
  kicker: {
    color: C.mint,
    fontSize: 10,
    fontWeight: "800",
    letterSpacing: 1.3,
  },
  title: { color: C.text, fontSize: 28, fontWeight: "800", marginTop: 3 },
  subtitle: { color: C.muted, fontSize: 11, marginBottom: 7, maxWidth: 300 },
  badge: {
    minWidth: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: C.red,
    alignItems: "center",
    justifyContent: "center",
  },
  badgeText: { color: C.text, fontSize: 12, fontWeight: "900" },
  summary: {
    flexDirection: "row",
    alignItems: "center",
    gap: 11,
    backgroundColor: C.surface,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 16,
    padding: 14,
  },
  summaryIcon: {
    width: 42,
    height: 42,
    borderRadius: 14,
    backgroundColor: C.mintSoft,
    alignItems: "center",
    justifyContent: "center",
  },
  summaryTitle: { color: C.text, fontSize: 13, fontWeight: "800" },
  summaryText: { color: C.muted, fontSize: 10, marginTop: 4 },
  greenDot: { width: 8, height: 8, borderRadius: 4, backgroundColor: C.mint },
  readButton: {
    borderWidth: 1,
    borderColor: C.mint,
    borderRadius: 8,
    paddingHorizontal: 8,
    paddingVertical: 7,
  },
  readButtonText: { color: C.mint, fontSize: 8, fontWeight: "900" },
  section: {
    color: C.muted,
    fontSize: 9,
    fontWeight: "900",
    letterSpacing: 1.3,
    marginTop: 9,
  },
  rule: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
    backgroundColor: C.surface,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 15,
    padding: 12,
  },
  ruleIcon: {
    width: 35,
    height: 35,
    borderRadius: 11,
    alignItems: "center",
    justifyContent: "center",
  },
  ruleCopy: { flex: 1 },
  ruleTitle: { color: C.text, fontSize: 12, fontWeight: "800" },
  ruleDescription: {
    color: C.muted,
    fontSize: 10,
    lineHeight: 14,
    marginTop: 3,
  },
  signal: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
    backgroundColor: C.surface,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 14,
    padding: 12,
  },
  signalUnread: { borderColor: "#2C7459", backgroundColor: "#10261F" },
  signalTitleRow: { flexDirection: "row", alignItems: "center", gap: 6 },
  signalTitle: { color: C.text, fontSize: 11, fontWeight: "800" },
  signalDetail: { color: C.muted, fontSize: 10, marginTop: 3 },
  signalTime: { color: C.muted, fontSize: 9 },
  unreadDot: { width: 6, height: 6, borderRadius: 3, backgroundColor: C.mint },
  empty: {
    flexDirection: "row",
    alignItems: "center",
    gap: 9,
    backgroundColor: C.surface,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 14,
    padding: 12,
  },
  footer: {
    flexDirection: "row",
    gap: 8,
    borderTopWidth: 1,
    borderTopColor: C.border,
    paddingTop: 14,
    marginTop: 7,
  },
  footerText: { color: C.muted, fontSize: 10, flex: 1, lineHeight: 15 },
});
