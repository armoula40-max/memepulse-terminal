import { useMemo, useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { MaterialIcons } from "@expo/vector-icons";
import { ScreenContainer } from "@/components/screen-container";
import { trpc } from "@/lib/trpc";
import { opportunityScore } from "@/lib/meme-pulse";
const P = {
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
export default function HomeScreen() {
  const [watching, setWatching] = useState<string[]>([]);
  const [scanRunning, setScanRunning] = useState(true);
  const [minLiquidity, setMinLiquidity] = useState(10_000);
  const [minVolume, setMinVolume] = useState(5_000);
  const [flowFilter, setFlowFilter] = useState<
    "all" | "positive" | "buy-heavy"
  >("all");
  const [momentumFilter, setMomentumFilter] = useState<
    "all" | "positive" | "strong"
  >("all");
  const [riskFilter, setRiskFilter] = useState<
    "all" | "guarded" | "watch" | "danger"
  >("all");
  const [signalFilter, setSignalFilter] = useState<
    "all" | "guarded" | "watch" | "danger"
  >("all");
  const [sortBy, setSortBy] = useState<"score" | "volume">("score");
  const market = trpc.market.latest.useQuery(undefined, {
    staleTime: 25_000,
    refetchInterval: scanRunning ? 30_000 : false,
    enabled: scanRunning,
  });
  const radar = useMemo(
    () =>
      (market.data?.tokens ?? [])
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
        }))
        .filter((coin) => {
          const buyPct =
            coin.buys1h + coin.sells1h > 0
              ? (coin.buys1h / (coin.buys1h + coin.sells1h)) * 100
              : 0;
          const risk =
            coin.liquidityUsd < 25_000
              ? "danger"
              : coin.liquidityUsd < 75_000
                ? "watch"
                : "guarded";
          const signal =
            coin.score >= 70
              ? "guarded"
              : coin.score >= 45
                ? "watch"
                : "danger";
          return (
            coin.liquidityUsd >= minLiquidity &&
            coin.volume1hUsd >= minVolume &&
            (momentumFilter === "all" ||
              (momentumFilter === "positive" && coin.change1hPct > 0) ||
              (momentumFilter === "strong" && coin.change1hPct >= 10)) &&
            (riskFilter === "all" || riskFilter === risk) &&
            (signalFilter === "all" || signalFilter === signal) &&
            (flowFilter === "all" ||
              (flowFilter === "positive" && coin.change1hPct > 0) ||
              (flowFilter === "buy-heavy" && buyPct >= 55))
          );
        })
        .sort((a, b) =>
          sortBy === "volume"
            ? b.volume1hUsd - a.volume1hUsd
            : b.score - a.score,
        )
        .slice(0, 5),
    [
      market.data,
      minLiquidity,
      minVolume,
      flowFilter,
      momentumFilter,
      riskFilter,
      signalFilter,
      sortBy,
    ],
  );
  const marketCap = radar.reduce(
    (sum, item) => sum + (item.liquidityUsd || 0),
    0,
  );
  const volume = radar.reduce((sum, item) => sum + item.volume24hUsd, 0);
  const topScore = radar[0]?.score ?? null;
  const money = (v: number) =>
    v >= 1_000_000
      ? `$${(v / 1_000_000).toFixed(1)}M`
      : v >= 1_000
        ? `$${(v / 1_000).toFixed(1)}K`
        : `$${v.toFixed(0)}`;
  return (
    <ScreenContainer className="px-5" containerClassName="bg-background">
      <ScrollView
        showsVerticalScrollIndicator={false}
        contentContainerStyle={styles.content}
      >
        <View style={styles.topbar}>
          <View>
            <View style={styles.brandRow}>
              <View style={styles.brandMark}>
                <MaterialIcons
                  name="candlestick-chart"
                  size={18}
                  color={P.bg}
                />
              </View>
              <Text style={styles.brand}>MEMEPULSE</Text>
            </View>
            <Text style={styles.eyebrow}>
              PRO TERMINAL / SOLANA INTELLIGENCE
            </Text>
          </View>
          <View style={styles.livePill}>
            <View
              style={[
                styles.liveDot,
                { backgroundColor: market.isFetching ? P.amber : P.mint },
              ]}
            />
            <Text
              style={[
                styles.liveText,
                { color: market.isFetching ? P.amber : P.mint },
              ]}
            >
              {market.isFetching ? "SYNC" : "LIVE"}
            </Text>
          </View>
        </View>
        <View style={styles.hero}>
          <View style={styles.heroCopy}>
            <Text style={styles.heroKicker}>MARKET PULSE</Text>
            <Text style={styles.heroTitle}>Find signal before the crowd.</Text>
            <Text style={styles.heroBody}>
              Read-only discovery, cost-aware simulation and auditable research
              metrics.
            </Text>
          </View>
          <View style={styles.scoreOrb}>
            <Text style={styles.orbScore}>{topScore ?? "—"}</Text>
            <Text style={styles.orbLabel}>PULSE</Text>
          </View>
        </View>
        <View style={styles.notice}>
          <MaterialIcons name="shield" size={16} color={P.mint} />
          <Text style={styles.noticeText}>
            {market.error
              ? `Public feed unavailable: ${market.error.message ?? "network error"}`
              : `Simulation only. Public feed: ${market.data?.source ?? "waiting"}. No wallet keys or signing.`}
          </Text>
        </View>
        <View style={styles.metricGrid}>
          <Metric
            label="LIQUIDITY / RADAR"
            value={market.data ? money(marketCap) : "—"}
            delta={market.data ? "live snapshot" : "not available"}
            icon="account-balance"
          />
          <Metric
            label="VOLUME / RADAR"
            value={market.data ? money(volume) : "—"}
            delta={market.data ? "live snapshot" : "not available"}
            icon="bar-chart"
          />
          <Metric
            label="WATCHLIST"
            value={String(watching.length).padStart(2, "0")}
            delta="local only"
            icon="star-border"
          />
          <Metric
            label="RISK MODE"
            value="GUARDED"
            delta="active"
            icon="security"
          />
        </View>
        <View style={styles.sectionHeader}>
          <View>
            <Text style={styles.sectionTitle}>Signal engine</Text>
            <Text style={styles.sectionMeta}>
              {market.data
                ? `Observed ${new Date(market.data.observedAt).toLocaleTimeString()}`
                : "Waiting for public snapshot"}
            </Text>
          </View>
          <Pressable
            onPress={() => setScanRunning((value) => !value)}
            style={({ pressed }) => [
              styles.controlButton,
              pressed && styles.pressed,
            ]}
          >
            <MaterialIcons
              name={scanRunning ? "pause" : "play-arrow"}
              size={15}
              color={P.mint}
            />
            <Text style={styles.controlText}>
              {scanRunning ? "SCANNING" : "PAUSED"}
            </Text>
          </Pressable>
        </View>
        <View style={styles.engineCard}>
          <View style={styles.engineTop}>
            <View>
              <Text style={styles.engineLabel}>TOP OPPORTUNITY INDEX</Text>
              <Text style={styles.engineValue}>
                {topScore ?? "—"} <Text style={styles.engineOutOf}>/ 100</Text>
              </Text>
            </View>
            <View style={styles.engineBadge}>
              <Text style={styles.engineBadgeText}>
                {topScore === null
                  ? "WAIT"
                  : topScore >= 70
                    ? "GUARDED"
                    : "WATCH"}
              </Text>
            </View>
          </View>
          <View style={styles.progressTrack}>
            <View
              style={[
                styles.progressFill,
                { width: `${topScore ?? 0}%` as `${number}%` },
              ]}
            />
          </View>
          <View style={styles.engineFooter}>
            <Text style={styles.engineMuted}>
              Liquidity + momentum + activity
            </Text>
            <Text style={styles.engineMuted}>
              {market.isFetching ? "syncing" : "read-only"}
            </Text>
          </View>
        </View>
        <View style={styles.sectionHeader}>
          <View>
            <Text style={styles.sectionTitle}>Momentum radar</Text>
            <Text style={styles.sectionMeta}>
              Filtered public Solana pairs · {radar.length} shown
            </Text>
          </View>
          <MaterialIcons name="tune" size={20} color={P.muted} />
        </View>
        <View style={styles.filterPanel}>
          <Text style={styles.filterTitle}>ADVANCED FILTERS</Text>
          <View style={styles.filterLine}>
            <Text style={styles.filterLabel}>Liquidity</Text>
            {[
              [10_000, "$10K+"],
              [25_000, "$25K+"],
              [50_000, "$50K+"],
            ].map(([value, label]) => (
              <Pressable
                key={String(value)}
                onPress={() => setMinLiquidity(Number(value))}
                style={[
                  styles.filterChip,
                  minLiquidity === value && styles.filterChipActive,
                ]}
              >
                <Text
                  style={[
                    styles.filterChipText,
                    minLiquidity === value && styles.filterChipTextActive,
                  ]}
                >
                  {label}
                </Text>
              </Pressable>
            ))}
          </View>
          <View style={styles.filterLine}>
            <Text style={styles.filterLabel}>1h volume</Text>
            {[
              [5_000, "$5K+"],
              [25_000, "$25K+"],
              [100_000, "$100K+"],
            ].map(([value, label]) => (
              <Pressable
                key={String(value)}
                onPress={() => setMinVolume(Number(value))}
                style={[
                  styles.filterChip,
                  minVolume === value && styles.filterChipActive,
                ]}
              >
                <Text
                  style={[
                    styles.filterChipText,
                    minVolume === value && styles.filterChipTextActive,
                  ]}
                >
                  {label}
                </Text>
              </Pressable>
            ))}
          </View>
          <View style={styles.filterLine}>
            <Text style={styles.filterLabel}>Flow</Text>
            {[
              ["all", "All"],
              ["positive", "Positive"],
              ["buy-heavy", "Buy ≥55%"],
            ].map(([value, label]) => (
              <Pressable
                key={value}
                onPress={() =>
                  setFlowFilter(value as "all" | "positive" | "buy-heavy")
                }
                style={[
                  styles.filterChip,
                  flowFilter === value && styles.filterChipActive,
                ]}
              >
                <Text
                  style={[
                    styles.filterChipText,
                    flowFilter === value && styles.filterChipTextActive,
                  ]}
                >
                  {label}
                </Text>
              </Pressable>
            ))}
          </View>
          <View style={styles.filterLine}>
            <Text style={styles.filterLabel}>Momentum</Text>
            {[
              ["all", "All"],
              ["positive", "Positive"],
              ["strong", "≥10%"],
            ].map(([value, label]) => (
              <Pressable
                key={value}
                onPress={() =>
                  setMomentumFilter(value as "all" | "positive" | "strong")
                }
                style={[
                  styles.filterChip,
                  momentumFilter === value && styles.filterChipActive,
                ]}
              >
                <Text
                  style={[
                    styles.filterChipText,
                    momentumFilter === value && styles.filterChipTextActive,
                  ]}
                >
                  {label}
                </Text>
              </Pressable>
            ))}
          </View>
          <View style={styles.filterLine}>
            <Text style={styles.filterLabel}>Risk</Text>
            {[
              ["all", "All"],
              ["guarded", "Guarded"],
              ["watch", "Watch"],
              ["danger", "Danger"],
            ].map(([value, label]) => (
              <Pressable
                key={value}
                onPress={() =>
                  setRiskFilter(value as "all" | "guarded" | "watch" | "danger")
                }
                style={[
                  styles.filterChip,
                  riskFilter === value && styles.filterChipActive,
                ]}
              >
                <Text
                  style={[
                    styles.filterChipText,
                    riskFilter === value && styles.filterChipTextActive,
                  ]}
                >
                  {label}
                </Text>
              </Pressable>
            ))}
          </View>
          <View style={styles.filterLine}>
            <Text style={styles.filterLabel}>Analysis</Text>
            {[
              ["all", "All"],
              ["guarded", "Positive"],
              ["watch", "Watch"],
              ["danger", "Danger"],
            ].map(([value, label]) => (
              <Pressable
                key={value}
                onPress={() =>
                  setSignalFilter(
                    value as "all" | "guarded" | "watch" | "danger",
                  )
                }
                style={[
                  styles.filterChip,
                  signalFilter === value && styles.filterChipActive,
                ]}
              >
                <Text
                  style={[
                    styles.filterChipText,
                    signalFilter === value && styles.filterChipTextActive,
                  ]}
                >
                  {label}
                </Text>
              </Pressable>
            ))}
          </View>
          <View style={styles.filterLine}>
            <Text style={styles.filterLabel}>Sort</Text>
            {[
              ["score", "Score"],
              ["volume", "Volume"],
            ].map(([value, label]) => (
              <Pressable
                key={value}
                onPress={() => setSortBy(value as "score" | "volume")}
                style={[
                  styles.filterChip,
                  sortBy === value && styles.filterChipActive,
                ]}
              >
                <Text
                  style={[
                    styles.filterChipText,
                    sortBy === value && styles.filterChipTextActive,
                  ]}
                >
                  {label}
                </Text>
              </Pressable>
            ))}
          </View>
        </View>
        <View style={styles.radarCard}>
          {radar.length ? (
            radar.map((item, index) => {
              const watched = watching.includes(item.address);
              return (
                <View
                  key={item.address}
                  style={[
                    styles.radarRow,
                    index < radar.length - 1 && styles.rowDivider,
                  ]}
                >
                  <View style={styles.tokenAvatar}>
                    <Text style={styles.avatarText}>
                      {item.symbol.slice(0, 1)}
                    </Text>
                  </View>
                  <View style={styles.tokenInfo}>
                    <View style={styles.tokenLine}>
                      <Text style={styles.tokenSymbol}>${item.symbol}</Text>
                      <Text style={styles.tokenTag}>
                        {item.dexId.toUpperCase()}
                      </Text>
                    </View>
                    <Text style={styles.tokenName}>{item.name}</Text>
                  </View>
                  <View style={styles.tokenStats}>
                    <Text
                      style={[
                        styles.tokenMove,
                        { color: item.change1hPct >= 0 ? P.mint : P.red },
                      ]}
                    >
                      {item.change1hPct >= 0 ? "+" : ""}
                      {item.change1hPct.toFixed(1)}%
                    </Text>
                    <Text style={styles.tokenScore}>score {item.score}</Text>
                  </View>
                  <Pressable
                    onPress={() =>
                      setWatching((current) =>
                        watched
                          ? current.filter((id) => id !== item.address)
                          : [...current, item.address],
                      )
                    }
                    style={styles.starButton}
                  >
                    <MaterialIcons
                      name={watched ? "star" : "star-border"}
                      size={21}
                      color={watched ? P.amber : P.muted}
                    />
                  </Pressable>
                </View>
              );
            })
          ) : (
            <View style={styles.empty}>
              <Text style={styles.emptyText}>
                {market.isLoading
                  ? "Loading public candidates..."
                  : "No live candidates available."}
              </Text>
            </View>
          )}
        </View>
        <View style={styles.footerCard}>
          <MaterialIcons name="info-outline" size={16} color={P.muted} />
          <Text style={styles.footerText}>
            Model outputs are hypothetical research signals. Verify contract,
            liquidity, holder concentration and sellability independently.
          </Text>
        </View>
      </ScrollView>
    </ScreenContainer>
  );
}
function Metric({
  label,
  value,
  delta,
  icon,
}: {
  label: string;
  value: string;
  delta: string;
  icon: keyof typeof MaterialIcons.glyphMap;
}) {
  return (
    <View style={styles.metric}>
      <MaterialIcons name={icon} size={16} color={P.mint} />
      <Text style={styles.metricLabel}>{label}</Text>
      <Text style={styles.metricValue}>{value}</Text>
      <Text style={styles.metricDelta}>{delta}</Text>
    </View>
  );
}
const styles = StyleSheet.create({
  content: { paddingTop: 16, paddingBottom: 32, gap: 16 },
  topbar: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "flex-start",
  },
  brandRow: { flexDirection: "row", alignItems: "center", gap: 8 },
  brandMark: {
    width: 28,
    height: 28,
    borderRadius: 9,
    backgroundColor: P.mint,
    alignItems: "center",
    justifyContent: "center",
  },
  brand: { color: P.text, fontSize: 16, fontWeight: "800", letterSpacing: 1.5 },
  eyebrow: {
    color: P.muted,
    fontSize: 9,
    fontWeight: "700",
    letterSpacing: 1.2,
    marginTop: 5,
  },
  livePill: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    borderColor: P.border,
    borderWidth: 1,
    paddingHorizontal: 9,
    paddingVertical: 6,
    borderRadius: 20,
  },
  liveDot: { width: 6, height: 6, borderRadius: 3 },
  liveText: { fontSize: 9, fontWeight: "800", letterSpacing: 0.8 },
  hero: {
    backgroundColor: P.surface,
    borderRadius: 22,
    borderWidth: 1,
    borderColor: P.border,
    padding: 18,
    flexDirection: "row",
    alignItems: "center",
    minHeight: 152,
  },
  heroCopy: { flex: 1, paddingRight: 12 },
  heroKicker: {
    color: P.mint,
    fontSize: 10,
    fontWeight: "800",
    letterSpacing: 1.4,
    marginBottom: 9,
  },
  heroTitle: {
    color: P.text,
    fontSize: 25,
    lineHeight: 29,
    fontWeight: "800",
    letterSpacing: -0.8,
  },
  heroBody: { color: P.muted, fontSize: 12, lineHeight: 18, marginTop: 9 },
  scoreOrb: {
    width: 90,
    height: 90,
    borderRadius: 45,
    borderWidth: 1,
    borderColor: P.mint,
    backgroundColor: P.mintSoft,
    alignItems: "center",
    justifyContent: "center",
  },
  orbScore: { color: P.mint, fontSize: 30, fontWeight: "800" },
  orbLabel: {
    color: P.muted,
    fontSize: 9,
    fontWeight: "800",
    letterSpacing: 1.3,
    marginTop: -2,
  },
  notice: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    paddingHorizontal: 13,
    paddingVertical: 11,
    backgroundColor: "#0B251D",
    borderRadius: 12,
    borderWidth: 1,
    borderColor: "#164D3B",
  },
  noticeText: { color: "#A4DCC3", flex: 1, fontSize: 11, lineHeight: 16 },
  metricGrid: { flexDirection: "row", flexWrap: "wrap", gap: 9 },
  metric: {
    width: "48.5%",
    minHeight: 92,
    backgroundColor: P.surface,
    borderRadius: 15,
    borderWidth: 1,
    borderColor: P.border,
    padding: 12,
  },
  metricLabel: {
    color: P.muted,
    fontSize: 9,
    fontWeight: "800",
    letterSpacing: 0.6,
    marginTop: 9,
  },
  metricValue: { color: P.text, fontSize: 19, fontWeight: "800", marginTop: 5 },
  metricDelta: { color: P.mint, fontSize: 10, fontWeight: "700", marginTop: 3 },
  sectionHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginTop: 4,
  },
  sectionTitle: { color: P.text, fontSize: 16, fontWeight: "800" },
  sectionMeta: { color: P.muted, fontSize: 10, marginTop: 3 },
  filterPanel: {
    backgroundColor: P.surface,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: P.border,
    padding: 11,
    gap: 8,
  },
  filterTitle: {
    color: P.muted,
    fontSize: 9,
    fontWeight: "900",
    letterSpacing: 1.1,
  },
  filterLine: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    flexWrap: "wrap",
  },
  filterLabel: { color: P.muted, fontSize: 9, width: 58 },
  filterChip: {
    borderWidth: 1,
    borderColor: P.border,
    borderRadius: 8,
    paddingHorizontal: 8,
    paddingVertical: 6,
  },
  filterChipActive: { backgroundColor: P.mint, borderColor: P.mint },
  filterChipText: { color: P.muted, fontSize: 9, fontWeight: "800" },
  filterChipTextActive: { color: P.bg },
  controlButton: {
    flexDirection: "row",
    alignItems: "center",
    gap: 5,
    borderWidth: 1,
    borderColor: P.border,
    borderRadius: 9,
    paddingHorizontal: 9,
    paddingVertical: 7,
  },
  controlText: {
    color: P.mint,
    fontSize: 9,
    fontWeight: "800",
    letterSpacing: 0.6,
  },
  pressed: { opacity: 0.7, transform: [{ scale: 0.98 }] },
  engineCard: {
    backgroundColor: P.surface,
    borderColor: P.border,
    borderWidth: 1,
    borderRadius: 17,
    padding: 16,
  },
  engineTop: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "flex-start",
  },
  engineLabel: {
    color: P.muted,
    fontSize: 9,
    fontWeight: "800",
    letterSpacing: 1.2,
  },
  engineValue: { color: P.text, fontSize: 31, fontWeight: "800", marginTop: 5 },
  engineOutOf: { color: P.muted, fontSize: 15, fontWeight: "600" },
  engineBadge: {
    backgroundColor: "#392F17",
    borderRadius: 8,
    paddingHorizontal: 9,
    paddingVertical: 6,
  },
  engineBadgeText: {
    color: P.amber,
    fontSize: 9,
    fontWeight: "800",
    letterSpacing: 0.8,
  },
  progressTrack: {
    height: 8,
    backgroundColor: "#183029",
    borderRadius: 8,
    marginTop: 15,
    overflow: "hidden",
  },
  progressFill: { height: 8, backgroundColor: P.mint, borderRadius: 8 },
  engineFooter: {
    flexDirection: "row",
    justifyContent: "space-between",
    marginTop: 10,
  },
  engineMuted: { color: P.muted, fontSize: 9 },
  radarCard: {
    backgroundColor: P.surface,
    borderRadius: 17,
    borderWidth: 1,
    borderColor: P.border,
    paddingHorizontal: 13,
  },
  radarRow: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: 13,
    gap: 10,
  },
  rowDivider: { borderBottomWidth: 1, borderBottomColor: P.border },
  tokenAvatar: {
    width: 36,
    height: 36,
    borderRadius: 12,
    alignItems: "center",
    justifyContent: "center",
    backgroundColor: P.mintSoft,
  },
  avatarText: { color: P.mint, fontSize: 16, fontWeight: "900" },
  tokenInfo: { flex: 1 },
  tokenLine: { flexDirection: "row", alignItems: "center", gap: 7 },
  tokenSymbol: { color: P.text, fontWeight: "800", fontSize: 13 },
  tokenTag: {
    color: P.blue,
    fontSize: 8,
    fontWeight: "800",
    letterSpacing: 0.5,
  },
  tokenName: { color: P.muted, fontSize: 10, marginTop: 4 },
  tokenStats: { alignItems: "flex-end" },
  tokenMove: { fontSize: 12, fontWeight: "800" },
  tokenScore: { color: P.muted, fontSize: 9, marginTop: 3 },
  starButton: { padding: 4 },
  empty: { paddingVertical: 22, alignItems: "center" },
  emptyText: { color: P.muted, fontSize: 11 },
  footerCard: {
    flexDirection: "row",
    gap: 8,
    borderTopWidth: 1,
    borderTopColor: P.border,
    paddingTop: 14,
  },
  footerText: { color: P.muted, fontSize: 10, lineHeight: 15, flex: 1 },
});
