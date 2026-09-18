import { useEffect, useMemo, useState } from "react";
import {
  FlatList,
  Modal,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import { MaterialIcons } from "@expo/vector-icons";
import { ScreenContainer } from "@/components/screen-container";
import { trpc } from "@/lib/trpc";
import { opportunityScore } from "@/lib/meme-pulse";
import {
  DEFAULT_PREFERENCES,
  loadPreferences,
  type Preferences,
} from "@/lib/preferences";
import { evaluateToken } from "@/lib/token-rules";
import { useDirectPumpPortal } from "@/lib/pumpportal-client";
import { upsideSignal } from "@/lib/upside-sniper";
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
export default function ScannerScreen() {
  const directPump = useDirectPumpPortal();
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState("All");
  const [watch, setWatch] = useState<string[]>([]);
  const [prefs, setPrefs] = useState<Preferences>(DEFAULT_PREFERENCES);
  const [selectedAddress, setSelectedAddress] = useState<string | null>(null);
  useEffect(() => {
    loadPreferences().then(setPrefs);
  }, []);
  const market = trpc.market.latest.useQuery(undefined, {
    staleTime: 5_000,
    refetchInterval: 15_000,
  });
  const newFeed = trpc.market.newTokens.useQuery(undefined, {
    staleTime: 2_000,
    refetchInterval: 5_000,
  });
  const risk = trpc.market.risk.useQuery(
    { address: selectedAddress ?? "" },
    { enabled: Boolean(selectedAddress), staleTime: 30_000 },
  );
  const forecast = trpc.market.forecast.useQuery(
    { address: selectedAddress ?? "" },
    { enabled: Boolean(selectedAddress), staleTime: 30_000 },
  );
  const safety = trpc.market.safety.useQuery(
    { address: selectedAddress ?? "" },
    { enabled: Boolean(selectedAddress), staleTime: 30_000 },
  );
  const strategy = trpc.market.strategy.useQuery(
    { address: selectedAddress ?? "" },
    { enabled: Boolean(selectedAddress), staleTime: 30_000 },
  );
  const ohlcv = trpc.market.ohlcv.useQuery(
    { address: selectedAddress ?? "" },
    { enabled: Boolean(selectedAddress), staleTime: 30_000 },
  );
  const data = useMemo(
    () =>
      Array.from(
        new Map(
          [...(market.data?.tokens ?? []), ...directPump.tokens].map(
            (token) => [token.address, token],
          ),
        ).values(),
      )
        .map((coin) => ({
          ...coin,
          score: opportunityScore({
            liquidityUsd: coin.liquidityUsd,
            volume1hUsd: coin.volume1hUsd,
            momentumPct: coin.change1hPct,
            risk:
              coin.liquidityUsd < 25_000
                ? "EXTREME"
                : coin.liquidityUsd < 75_000
                  ? "HIGH"
                  : "MED",
          }),
          algorithm: evaluateToken(coin, prefs.algorithm),
          upside: upsideSignal(coin),
        }))
        .filter((coin) => {
          const isRisk = coin.liquidityUsd < 25_000;
          const isNew = Boolean(
            coin.pairCreatedAt &&
            Date.now() - new Date(coin.pairCreatedAt).getTime() <=
              24 * 60 * 60 * 1000,
          );
          return (
            (!prefs.algorithm.enabled || coin.algorithm.eligible) &&
            (filter === "All" ||
              (filter === "Risk" && isRisk) ||
              (filter === "New" && isNew) ||
              (filter === "Sniper" && coin.upside.label !== "watch")) &&
            `${coin.symbol} ${coin.name}`
              .toLowerCase()
              .includes(query.toLowerCase())
          );
        })
        .sort((a, b) => b.score - a.score),
    [market.data, directPump.tokens, filter, query, prefs.algorithm],
  );
  const selected = data.find((item) => item.address === selectedAddress);
  const directEligibleEvents = directPump.events.filter((event) =>
    data.some((token) => token.address === event.mint),
  );
  return (
    <ScreenContainer className="px-5" containerClassName="bg-background">
      <View style={styles.header}>
        <View>
          <Text style={styles.kicker}>LIVE READ-ONLY FEED</Text>
          <Text style={styles.title}>Scanner</Text>
          <Text style={styles.subtitle}>
            Tap a token for risk report and evidence. New listings refresh every
            5–15 seconds.
          </Text>
        </View>
        <View style={styles.mode}>
          <View
            style={[
              styles.dot,
              { backgroundColor: market.isFetching ? C.amber : C.mint },
            ]}
          />
          <Text
            style={[
              styles.modeText,
              { color: market.isFetching ? C.amber : C.mint },
            ]}
          >
            {market.isFetching ? "SYNC" : "LIVE"}
          </Text>
        </View>
      </View>
      <View style={styles.search}>
        <MaterialIcons name="search" size={18} color={C.muted} />
        <TextInput
          value={query}
          onChangeText={setQuery}
          placeholder="Search symbol or name"
          placeholderTextColor={C.muted}
          style={styles.input}
        />
      </View>
      <View style={styles.filters}>
        <View style={styles.algorithmPill}>
          <Text style={styles.algorithmPillText}>
            {prefs.algorithm.enabled ? "RULES ON" : "RULES OFF"}
          </Text>
        </View>
        {["All", "New", "Risk", "Sniper"].map((item) => (
          <Pressable
            key={item}
            onPress={() => setFilter(item)}
            style={[styles.filter, filter === item && styles.filterActive]}
          >
            <Text
              style={[
                styles.filterText,
                filter === item && styles.filterTextActive,
              ]}
            >
              {item}
            </Text>
          </Pressable>
        ))}
      </View>
      <View style={styles.banner}>
        <MaterialIcons name="cloud-download" size={17} color={C.mint} />
        <View style={styles.bannerCopy}>
          <Text style={styles.bannerTitle}>
            {directPump.connected
              ? "PumpPortal direct stream + Dexscreener"
              : newFeed.data?.stream.connected
                ? "PumpPortal server stream + Dexscreener"
                : (market.data?.source ?? "Dexscreener public API")}
          </Text>
          <Text style={styles.bannerText}>
            {directPump.configured
              ? `Direct ${directEligibleEvents.length} eligible · ${directPump.events.length} received · `
              : ""}
            {newFeed.data
              ? `${newFeed.data.events.length} server events · ${newFeed.data.tokens.length} pairs · updated ${safeTime(newFeed.data.observedAt)}`
              : market.data
                ? `Observed ${safeTime(market.data.observedAt)}`
                : market.error
                  ? "Feed unavailable — retrying safely"
                  : "Loading public market snapshot..."}
          </Text>
          {directPump.error ? (
            <Text style={styles.directError}>{directPump.error}</Text>
          ) : null}
          {newFeed.data?.events?.length ? (
            <Text style={styles.liveHint}>
              ● LIVE CREATION MONITOR · newest event{" "}
              {new Date(newFeed.data.events[0].createdAt).toLocaleTimeString()}
            </Text>
          ) : null}
        </View>
        <MaterialIcons name="lock" size={15} color={C.muted} />
      </View>
      {directEligibleEvents.length > 0 ? (
        <View style={styles.directPanel}>
          <Text style={styles.directPanelTitle}>
            PUMPPORTAL DIRECT · {directEligibleEvents.length} ELIGIBLE TOKENS
          </Text>
          {directEligibleEvents.slice(0, 8).map((event) => (
            <View
              key={`${event.mint}-${event.createdAt}`}
              style={styles.directRow}
            >
              <View style={styles.directIcon}>
                <MaterialIcons name="bolt" size={15} color={C.mint} />
              </View>
              <View style={{ flex: 1 }}>
                <Text style={styles.directSymbol}>${event.symbol}</Text>
                <Text style={styles.directName} numberOfLines={1}>
                  {event.name} · {event.mint.slice(0, 5)}…{event.mint.slice(-5)}
                </Text>
              </View>
              <View style={styles.directNumbers}>
                <Text style={styles.directNumber}>
                  {event.marketCapSol !== null
                    ? `${event.marketCapSol.toFixed(1)} SOL`
                    : "—"}
                </Text>
                <Text style={styles.directAge}>
                  {safeTime(event.createdAt)}
                </Text>
              </View>
            </View>
          ))}
        </View>
      ) : null}
      {market.error ? (
        <View style={styles.empty}>
          <MaterialIcons name="cloud-off" size={28} color={C.red} />
          <Text style={styles.emptyText}>
            The public feed is unavailable right now.
          </Text>
          <Text style={styles.emptyHint}>
            No fabricated market numbers are shown.
          </Text>
          <Pressable onPress={() => market.refetch()} style={styles.retry}>
            <Text style={styles.retryText}>RETRY</Text>
          </Pressable>
        </View>
      ) : (
        <FlatList
          data={data}
          keyExtractor={(item) => item.address}
          showsVerticalScrollIndicator={false}
          contentContainerStyle={styles.list}
          renderItem={({ item }) => {
            const level =
              item.liquidityUsd < 25_000
                ? "EXTREME"
                : item.liquidityUsd < 75_000
                  ? "HIGH"
                  : "MED";
            const watched = watch.includes(item.address);
            return (
              <Pressable
                onPress={() => setSelectedAddress(item.address)}
                style={({ pressed }) => [
                  styles.card,
                  pressed && styles.pressed,
                ]}
              >
                <View style={styles.cardTop}>
                  <View
                    style={[
                      styles.avatar,
                      {
                        backgroundColor:
                          level === "EXTREME"
                            ? "#3B2022"
                            : level === "HIGH"
                              ? "#3A2D17"
                              : C.mintSoft,
                      },
                    ]}
                  >
                    <Text
                      style={{
                        color:
                          level === "EXTREME"
                            ? C.red
                            : level === "HIGH"
                              ? C.amber
                              : C.mint,
                        fontWeight: "900",
                      }}
                    >
                      {item.symbol.slice(0, 1)}
                    </Text>
                  </View>
                  <View style={styles.main}>
                    <View style={styles.nameRow}>
                      <Text style={styles.symbol}>${item.symbol}</Text>
                      <Text
                        style={[
                          styles.risk,
                          {
                            color:
                              level === "EXTREME"
                                ? C.red
                                : level === "HIGH"
                                  ? C.amber
                                  : C.mint,
                          },
                        ]}
                      >
                        {level} LIQUIDITY
                      </Text>
                    </View>
                    <Text style={styles.name}>{item.name}</Text>
                    {item.upside.label !== "watch" ? (
                      <Text
                        style={[
                          styles.upsideBadge,
                          {
                            color:
                              item.upside.label === "x100 speculative"
                                ? C.amber
                                : C.mint,
                          },
                        ]}
                      >
                        {item.upside.label.toUpperCase()} · {item.upside.score}
                        /100
                      </Text>
                    ) : null}
                  </View>
                  <Pressable
                    onPress={() =>
                      setWatch((old) =>
                        watched
                          ? old.filter((x) => x !== item.address)
                          : [...old, item.address],
                      )
                    }
                    style={styles.star}
                  >
                    <MaterialIcons
                      name={watched ? "star" : "star-border"}
                      size={22}
                      color={watched ? C.amber : C.muted}
                    />
                  </Pressable>
                </View>
                <View style={styles.scoreLine}>
                  <Text style={styles.scoreLabel}>PULSE SCORE</Text>
                  <Text style={styles.score}>{item.score}</Text>
                  <View style={styles.miniTrack}>
                    <View
                      style={[
                        styles.miniFill,
                        {
                          width: `${item.score}%` as `${number}%`,
                          backgroundColor: item.score > 70 ? C.mint : C.amber,
                        },
                      ]}
                    />
                  </View>
                  <Text
                    style={[
                      styles.change,
                      { color: item.change1hPct >= 0 ? C.mint : C.red },
                    ]}
                  >
                    {item.change1hPct >= 0 ? "+" : ""}
                    {item.change1hPct.toFixed(1)}%
                  </Text>
                </View>
                <View style={styles.stats}>
                  <Stat label="PRICE" value={formatPrice(item.priceUsd)} />
                  <Stat label="LIQUIDITY" value={money(item.liquidityUsd)} />
                  <Stat label="VOL / 1H" value={money(item.volume1hUsd)} />
                  <Stat
                    label="BUYS / SELLS"
                    value={`${item.buys1h} / ${item.sells1h}`}
                  />
                </View>
                <Text style={styles.tapHint}>
                  Tap for evidence and risk report
                </Text>
              </Pressable>
            );
          }}
          ListEmptyComponent={
            <View style={styles.empty}>
              <MaterialIcons name="manage-search" size={28} color={C.muted} />
              <Text style={styles.emptyText}>
                {market.isLoading
                  ? "Loading live candidates..."
                  : "No public candidates match this filter."}
              </Text>
            </View>
          }
        />
      )}
      <Modal
        visible={Boolean(selected)}
        transparent
        animationType="slide"
        onRequestClose={() => setSelectedAddress(null)}
      >
        <View style={styles.modalBackdrop}>
          <View style={styles.modal}>
            <View style={styles.modalHeader}>
              <View>
                <Text style={styles.kicker}>RISK EVIDENCE</Text>
                <Text style={styles.modalTitle}>
                  ${selected?.symbol ?? "TOKEN"}
                </Text>
              </View>
              <Pressable onPress={() => setSelectedAddress(null)}>
                <MaterialIcons name="close" size={23} color={C.muted} />
              </Pressable>
            </View>
            {risk.isLoading ? (
              <Text style={styles.modalText}>Loading provider report...</Text>
            ) : (
              <>
                <View style={styles.riskHero}>
                  <Text style={styles.riskLevel}>
                    {risk.data?.level ?? "UNKNOWN"}
                  </Text>
                  <Text style={styles.modalText}>
                    Provider score: {risk.data?.score ?? "—"}
                  </Text>
                </View>
                <Row
                  label="Price"
                  value={formatPrice(selected?.priceUsd ?? null)}
                />
                <Row
                  label="Liquidity"
                  value={money(
                    risk.data?.liquidityUsd ?? selected?.liquidityUsd ?? 0,
                  )}
                />
                <Row
                  label="Buy / sell ratio"
                  value={risk.data?.buySellRatio?.toString() ?? "—"}
                />
                <Text style={styles.sectionLabel}>PRICE ACTION</Text>
                <View style={styles.chart}>
                  {(ohlcv.data?.points ?? [])
                    .slice(0, 24)
                    .reverse()
                    .map((point, index) => (
                      <View
                        key={`${point.timestamp}-${index}`}
                        style={[
                          styles.bar,
                          {
                            height: Math.max(
                              8,
                              Math.min(
                                72,
                                (Math.abs(point.close - point.open) /
                                  Math.max(point.open, 1e-12)) *
                                  500,
                              ),
                            ),
                            backgroundColor:
                              point.close >= point.open ? C.mint : C.red,
                          },
                        ]}
                      />
                    ))}
                </View>
                <Text style={styles.sectionLabel}>ON-CHAIN SAFETY</Text>
                <Row
                  label="Mint authority"
                  value={
                    safety.isLoading
                      ? "checking"
                      : safety.data?.mintAuthority
                        ? "ACTIVE"
                        : "not returned"
                  }
                />
                <Row
                  label="Freeze authority"
                  value={
                    safety.isLoading
                      ? "checking"
                      : safety.data?.freezeAuthority
                        ? "ACTIVE"
                        : "not returned"
                  }
                />
                <Row
                  label="Top holder"
                  value={
                    safety.data?.topHolderPercent !== null &&
                    safety.data?.topHolderPercent !== undefined
                      ? `${safety.data.topHolderPercent}%`
                      : "—"
                  }
                />
                <View style={styles.forecast}>
                  <Text style={styles.sectionLabel}>VIDEO STRATEGY FILTER</Text>
                  <Text style={styles.forecastTitle}>
                    {strategy.data?.eligible ? "ELIGIBLE" : "NOT YET ELIGIBLE"}{" "}
                    · {strategy.data?.score ?? "—"}/100
                  </Text>
                  <Text style={styles.note}>
                    {strategy.data?.reasons?.join(" · ") ||
                      "Waiting for enough observed history."}
                  </Text>
                </View>
                <Text style={styles.sectionLabel}>FLAGS</Text>
                {(risk.data?.flags ?? ["No report available"]).map((flag) => (
                  <Text key={flag} style={styles.flag}>
                    • {flag}
                  </Text>
                ))}
                <View style={styles.forecast}>
                  <Text style={styles.sectionLabel}>
                    1H PROBABILISTIC SIGNAL
                  </Text>
                  <Text style={styles.forecastTitle}>
                    {forecast.data?.direction ?? "UNKNOWN"} ·{" "}
                    {forecast.data
                      ? `${forecast.data.probabilityUp}% up / ${forecast.data.probabilityDown}% down`
                      : "waiting"}
                  </Text>
                  <Text style={styles.note}>
                    {forecast.data?.warning ??
                      "Prediction unavailable until a current snapshot is loaded."}
                  </Text>
                </View>
                <Text style={styles.note}>
                  {risk.data?.note ?? "No current report available."}
                </Text>
              </>
            )}
            <Pressable
              onPress={() => setSelectedAddress(null)}
              style={styles.closeButton}
            >
              <Text style={styles.closeText}>CLOSE</Text>
            </Pressable>
          </View>
        </View>
      </Modal>
    </ScreenContainer>
  );
}
function formatPrice(value: number | null) {
  if (value === null || !Number.isFinite(value)) return "—";
  if (value >= 1) return `$${value.toFixed(2)}`;
  if (value >= 0.01) return `$${value.toFixed(4)}`;
  return `$${value.toExponential(3)}`;
}
function safeTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleTimeString();
}
function money(value: number) {
  return value >= 1_000_000
    ? `$${(value / 1_000_000).toFixed(1)}M`
    : value >= 1_000
      ? `$${(value / 1_000).toFixed(1)}K`
      : `$${value.toFixed(0)}`;
}
function Stat({ label, value }: { label: string; value: string }) {
  return (
    <View>
      <Text style={styles.statLabel}>{label}</Text>
      <Text style={styles.statValue}>{value}</Text>
    </View>
  );
}
function Row({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      <Text style={styles.rowValue}>{value}</Text>
    </View>
  );
}
const styles = StyleSheet.create({
  header: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "flex-start",
    paddingTop: 16,
    paddingBottom: 16,
  },
  kicker: {
    color: C.mint,
    fontSize: 10,
    fontWeight: "800",
    letterSpacing: 1.3,
  },
  title: { color: C.text, fontSize: 28, fontWeight: "800", marginTop: 4 },
  subtitle: { color: C.muted, fontSize: 11, marginTop: 4 },
  mode: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    paddingHorizontal: 9,
    paddingVertical: 6,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 12,
  },
  dot: { width: 6, height: 6, borderRadius: 3 },
  modeText: { fontSize: 9, fontWeight: "800" },
  search: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 13,
    paddingHorizontal: 12,
    backgroundColor: C.surface,
  },
  input: { flex: 1, color: C.text, height: 42, fontSize: 12 },
  filters: {
    flexDirection: "row",
    gap: 8,
    marginVertical: 12,
    alignItems: "center",
  },
  algorithmPill: {
    marginLeft: "auto",
    borderRadius: 10,
    paddingHorizontal: 9,
    paddingVertical: 8,
    backgroundColor: "#0B251D",
    borderWidth: 1,
    borderColor: "#164D3B",
  },
  algorithmPillText: { color: C.mint, fontSize: 9, fontWeight: "900" },
  filter: {
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 10,
    paddingHorizontal: 15,
    paddingVertical: 8,
  },
  filterActive: { backgroundColor: C.mint, borderColor: C.mint },
  filterText: { color: C.muted, fontSize: 11, fontWeight: "700" },
  filterTextActive: { color: C.bg },
  banner: {
    flexDirection: "row",
    alignItems: "center",
    gap: 9,
    backgroundColor: "#0B251D",
    borderWidth: 1,
    borderColor: "#164D3B",
    borderRadius: 14,
    padding: 12,
    marginBottom: 12,
  },
  bannerCopy: { flex: 1 },
  bannerTitle: { color: C.text, fontSize: 11, fontWeight: "800" },
  bannerText: { color: C.muted, fontSize: 10, marginTop: 3 },
  liveHint: { color: C.mint, fontSize: 9, marginTop: 4, fontWeight: "800" },
  directError: { color: C.red, fontSize: 9, marginTop: 4 },
  directPanel: {
    backgroundColor: C.surface,
    borderWidth: 1,
    borderColor: "#164D3B",
    borderRadius: 15,
    padding: 12,
    marginBottom: 12,
    gap: 8,
  },
  directPanelTitle: {
    color: C.mint,
    fontSize: 9,
    fontWeight: "900",
    letterSpacing: 1,
  },
  directRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 9,
    borderTopWidth: 1,
    borderTopColor: C.border,
    paddingTop: 8,
  },
  directIcon: {
    width: 28,
    height: 28,
    borderRadius: 8,
    backgroundColor: C.mintSoft,
    alignItems: "center",
    justifyContent: "center",
  },
  directSymbol: { color: C.text, fontSize: 11, fontWeight: "900" },
  directName: { color: C.muted, fontSize: 9, marginTop: 2 },
  directNumbers: { alignItems: "flex-end" },
  directNumber: { color: C.amber, fontSize: 9, fontWeight: "800" },
  directAge: { color: C.muted, fontSize: 8, marginTop: 2 },
  list: { gap: 11, paddingBottom: 28 },
  card: {
    backgroundColor: C.surface,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 17,
    padding: 13,
  },
  pressed: { opacity: 0.8, transform: [{ scale: 0.99 }] },
  cardTop: { flexDirection: "row", alignItems: "center" },
  avatar: {
    width: 38,
    height: 38,
    borderRadius: 13,
    alignItems: "center",
    justifyContent: "center",
  },
  main: { flex: 1, marginLeft: 10 },
  nameRow: { flexDirection: "row", alignItems: "center", gap: 7 },
  symbol: { color: C.text, fontSize: 14, fontWeight: "800" },
  risk: { fontSize: 8, fontWeight: "900", letterSpacing: 0.5 },
  name: { color: C.muted, fontSize: 10, marginTop: 3 },
  upsideBadge: {
    fontSize: 8,
    fontWeight: "900",
    marginTop: 4,
    letterSpacing: 0.4,
  },
  star: { padding: 4 },
  scoreLine: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    marginTop: 15,
  },
  scoreLabel: { color: C.muted, fontSize: 8, fontWeight: "800" },
  score: { color: C.text, fontWeight: "800", fontSize: 12 },
  miniTrack: {
    flex: 1,
    height: 5,
    borderRadius: 5,
    backgroundColor: "#19302A",
    overflow: "hidden",
  },
  miniFill: { height: 5, borderRadius: 5 },
  change: { fontSize: 11, fontWeight: "800" },
  stats: {
    flexDirection: "row",
    justifyContent: "space-between",
    borderTopWidth: 1,
    borderTopColor: C.border,
    marginTop: 13,
    paddingTop: 11,
  },
  statLabel: {
    color: C.muted,
    fontSize: 8,
    fontWeight: "800",
    letterSpacing: 0.4,
  },
  statValue: { color: C.text, fontSize: 10, fontWeight: "700", marginTop: 4 },
  tapHint: { color: C.blue, fontSize: 9, marginTop: 12 },
  empty: { alignItems: "center", paddingTop: 60, gap: 9 },
  emptyText: { color: C.muted, fontSize: 12, textAlign: "center" },
  emptyHint: { color: C.muted, fontSize: 10 },
  retry: {
    backgroundColor: C.mint,
    borderRadius: 9,
    paddingHorizontal: 14,
    paddingVertical: 8,
  },
  retryText: { color: C.bg, fontSize: 10, fontWeight: "900" },
  modalBackdrop: {
    flex: 1,
    justifyContent: "flex-end",
    backgroundColor: "#00000099",
  },
  modal: {
    backgroundColor: C.surface,
    borderTopLeftRadius: 22,
    borderTopRightRadius: 22,
    padding: 20,
    borderTopWidth: 1,
    borderColor: C.border,
  },
  modalHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "flex-start",
    marginBottom: 16,
  },
  modalTitle: { color: C.text, fontSize: 24, fontWeight: "800", marginTop: 4 },
  riskHero: {
    backgroundColor: "#211B0E",
    borderColor: "#5A421D",
    borderWidth: 1,
    borderRadius: 14,
    padding: 14,
    marginBottom: 10,
  },
  riskLevel: { color: C.amber, fontSize: 20, fontWeight: "900" },
  modalText: { color: C.muted, fontSize: 11, lineHeight: 17 },
  row: {
    flexDirection: "row",
    justifyContent: "space-between",
    borderTopWidth: 1,
    borderTopColor: C.border,
    paddingVertical: 10,
  },
  rowLabel: { color: C.muted, fontSize: 10 },
  rowValue: { color: C.text, fontSize: 10, fontWeight: "800" },
  sectionLabel: {
    color: C.muted,
    fontSize: 9,
    fontWeight: "900",
    letterSpacing: 1.2,
    marginTop: 9,
    marginBottom: 6,
  },
  flag: { color: C.red, fontSize: 11, lineHeight: 18 },
  note: { color: C.muted, fontSize: 10, lineHeight: 15, marginTop: 10 },
  forecast: {
    backgroundColor: "#0B251D",
    borderColor: "#164D3B",
    borderWidth: 1,
    borderRadius: 12,
    padding: 11,
    marginTop: 12,
  },
  forecastTitle: {
    color: C.mint,
    fontSize: 15,
    fontWeight: "900",
    marginTop: 3,
  },
  chart: {
    height: 78,
    flexDirection: "row",
    alignItems: "flex-end",
    gap: 3,
    borderBottomWidth: 1,
    borderBottomColor: C.border,
    paddingHorizontal: 4,
    marginBottom: 6,
  },
  bar: { flex: 1, minWidth: 2, borderRadius: 2 },
  closeButton: {
    alignItems: "center",
    backgroundColor: C.mint,
    borderRadius: 11,
    paddingVertical: 12,
    marginTop: 16,
  },
  closeText: { color: C.bg, fontWeight: "900", fontSize: 11 },
});
