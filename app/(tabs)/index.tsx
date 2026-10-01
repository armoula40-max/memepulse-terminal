import { useMemo, useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { MaterialIcons } from "@expo/vector-icons";
import { ScreenContainer } from "@/components/screen-container";
import { Card, SectionHeader, SignalLine, StatTile, TokenAvatar, UI } from "@/components/meme-ui";
import { trpc } from "@/lib/trpc";
import { opportunityScore } from "@/lib/meme-pulse";
import { openTokenLink } from "@/lib/token-links";

export default function HomeScreen() {
  const [watching, setWatching] = useState<string[]>([]);
  const market = trpc.market.latest.useQuery(undefined, { staleTime: 5_000, refetchInterval: 15_000 });
  const radar = useMemo(() => (market.data?.tokens ?? []).map((coin) => ({ ...coin, score: opportunityScore({ liquidityUsd: coin.liquidityUsd, volume1hUsd: coin.volume1hUsd, momentumPct: coin.change1hPct, risk: coin.liquidityUsd < 25_000 ? "EXTREME" : coin.liquidityUsd < 75_000 ? "HIGH" : "MED" }) })).sort((a, b) => b.score - a.score).slice(0, 5), [market.data]);
  const money = (value: number) => value >= 1_000_000 ? `$${(value / 1_000_000).toFixed(1)}M` : value >= 1_000 ? `$${(value / 1_000).toFixed(1)}K` : `$${value.toFixed(0)}`;
  const topScore = radar[0]?.score ?? 0;
  const liquidity = radar.reduce((sum, item) => sum + item.liquidityUsd, 0);
  const volume = radar.reduce((sum, item) => sum + item.volume24hUsd, 0);

  return <ScreenContainer edges={["top"]} containerClassName="bg-background" className="px-4"><ScrollView showsVerticalScrollIndicator={false} contentContainerStyle={styles.content}>
    <View style={styles.topbar}><View style={styles.brandWrap}><View style={styles.logo}><Text style={styles.logoText}>M</Text></View><View><Text style={styles.brand}>MemePulse</Text><Text style={styles.brandSub}>TERMINAL</Text></View></View><View style={styles.live}><View style={[styles.liveDot, { backgroundColor: market.isFetching ? UI.amber : UI.mint }]} /><Text style={[styles.liveText, { color: market.isFetching ? UI.amber : UI.mint }]}>{market.isFetching ? "SYNC" : "LIVE MARKET"}</Text></View></View>
    <View style={styles.hero}><View style={{ flex: 1 }}><Text style={styles.eyebrow}>MARKET OVERVIEW</Text><Text style={styles.heroTitle}>Spot the next move.</Text><Text style={styles.heroBody}>Real-time Solana signals, guarded by evidence.</Text></View><View style={styles.heroOrb}><Text style={styles.orbValue}>{topScore || "—"}</Text><Text style={styles.orbLabel}>PULSE</Text></View></View>
    <View style={styles.status}><MaterialIcons name="verified-user" size={15} color={market.data?.freshness === "LIVE" ? UI.mint : UI.amber} /><Text style={styles.statusText}>{market.error ? "Public feed unavailable — retrying safely" : `${market.data?.freshness ?? "UNKNOWN"} · ${market.data?.observedAt ? `${Math.max(0, Math.round((Date.now() - new Date(market.data.observedAt).getTime()) / 1000))}s ago` : "no observation"} · paper trading active`}</Text><MaterialIcons name="chevron-right" size={17} color={UI.muted} /></View>
    <View style={styles.grid}><StatTile label="TRACKED TOKENS" value={market.data ? String(market.data.tokens.length) : "—"} delta="+12 today" icon="token" /><StatTile label="RADAR LIQUIDITY" value={market.data ? money(liquidity) : "—"} delta="live snapshot" icon="water-drop" /><StatTile label="24H VOLUME" value={market.data ? money(volume) : "—"} delta="public market" icon="bar-chart" /><StatTile label="PAPER EQUITY" value="$10.4K" delta="+4.32%" icon="account-balance-wallet" /></View>
    <SectionHeader title="Market activity" detail={market.data?.observedAt ? `Updated ${new Date(market.data.observedAt).toLocaleTimeString()}` : "Waiting for public data"} action="LIVE" />
    <Card style={styles.activity}><View style={styles.activityTop}><View><Text style={styles.activityLabel}>SIGNAL ENGINE</Text><Text style={styles.activityValue}>{topScore || "—"}<Text style={styles.activityOut}> / 100</Text></Text></View><View style={styles.bullish}><Text style={styles.bullishText}>BULLISH</Text></View></View><View style={styles.activityChart}><SignalLine value={topScore} width={190} /><Text style={styles.chartMeta}>momentum + liquidity + flow</Text></View><View style={styles.progress}><View style={[styles.progressFill, { width: `${topScore}%` as `${number}%` }]} /></View></Card>
    <SectionHeader title="Scanner candidates" detail="Highest composite scores" action="VIEW ALL" />
    <Card style={styles.tokenCard}>{radar.length ? radar.map((item, index) => { const watched = watching.includes(item.address); return <Pressable key={item.address} onPress={() => void openTokenLink(item.address, "dexscreener")} style={[styles.tokenRow, index < radar.length - 1 && styles.divider]}><TokenAvatar symbol={item.symbol} tone={item.score >= 75 ? UI.mint : item.score >= 55 ? UI.amber : UI.red} /><View style={styles.tokenInfo}><View style={styles.tokenNameRow}><Text style={styles.symbol}>${item.symbol}</Text><Text style={styles.tokenAge}>{item.dexId?.toUpperCase() ?? "SOLANA"}</Text></View><Text style={styles.tokenName}>{item.name}</Text></View><SignalLine value={item.score} color={item.change1hPct >= 0 ? UI.mint : UI.red} width={54} /><View style={styles.tokenScore}><Text style={{ color: item.change1hPct >= 0 ? UI.mint : UI.red, fontWeight: "900", fontSize: 12 }}>{item.change1hPct >= 0 ? "+" : ""}{item.change1hPct.toFixed(1)}%</Text><Text style={styles.scoreCaption}>score {item.score}</Text></View><Pressable onPress={() => setWatching((old) => watched ? old.filter((id) => id !== item.address) : [...old, item.address])} hitSlop={8}><MaterialIcons name={watched ? "star" : "star-border"} size={21} color={watched ? UI.amber : UI.muted} /></Pressable></Pressable>; }) : <Text style={styles.empty}>{market.isLoading ? "Loading live candidates..." : "No live candidates available."}</Text>}</Card>
    <View style={styles.disclaimer}><MaterialIcons name="info-outline" size={15} color={UI.muted} /><Text style={styles.disclaimerText}>Research signals are hypothetical. Verify liquidity, holders and sellability before acting.</Text></View>
  </ScrollView></ScreenContainer>;
}

const styles = StyleSheet.create({
  content: { paddingTop: 14, paddingBottom: 28, gap: 14 },
  topbar: { flexDirection: "row", justifyContent: "space-between", alignItems: "center" },
  brandWrap: { flexDirection: "row", alignItems: "center", gap: 9 },
  logo: { width: 34, height: 34, borderRadius: 11, backgroundColor: UI.mint, alignItems: "center", justifyContent: "center" },
  logoText: { color: UI.bg, fontSize: 21, fontWeight: "900", fontStyle: "italic" },
  brand: { color: UI.text, fontSize: 17, fontWeight: "900", letterSpacing: 0.2 },
  brandSub: { color: UI.muted, fontSize: 8, fontWeight: "800", letterSpacing: 1.8, marginTop: 1 },
  live: { flexDirection: "row", alignItems: "center", gap: 6, borderWidth: 1, borderColor: UI.border, borderRadius: 20, paddingHorizontal: 9, paddingVertical: 7 },
  liveDot: { width: 6, height: 6, borderRadius: 3 }, liveText: { fontSize: 8, fontWeight: "900", letterSpacing: 0.4 },
  hero: { backgroundColor: UI.panel, borderRadius: 20, borderWidth: 1, borderColor: UI.border, padding: 17, flexDirection: "row", alignItems: "center", minHeight: 142 },
  eyebrow: { color: UI.mint, fontSize: 9, fontWeight: "900", letterSpacing: 1.2 }, heroTitle: { color: UI.text, fontSize: 25, lineHeight: 29, fontWeight: "900", marginTop: 8 }, heroBody: { color: UI.muted, fontSize: 11, lineHeight: 17, marginTop: 8 },
  heroOrb: { width: 82, height: 82, borderRadius: 41, borderWidth: 1, borderColor: UI.mint, backgroundColor: UI.mintSoft, alignItems: "center", justifyContent: "center" }, orbValue: { color: UI.mint, fontSize: 28, fontWeight: "900" }, orbLabel: { color: UI.muted, fontSize: 8, fontWeight: "900", letterSpacing: 1, marginTop: -2 },
  status: { flexDirection: "row", alignItems: "center", gap: 8, backgroundColor: UI.mintSoft, borderWidth: 1, borderColor: "#1D6754", borderRadius: 12, padding: 10 }, statusText: { color: "#A9E9CF", fontSize: 10, flex: 1 },
  grid: { flexDirection: "row", flexWrap: "wrap", gap: 9 },
  activity: { padding: 15 }, activityTop: { flexDirection: "row", justifyContent: "space-between", alignItems: "flex-start" }, activityLabel: { color: UI.muted, fontSize: 9, fontWeight: "900", letterSpacing: 1 }, activityValue: { color: UI.text, fontSize: 28, fontWeight: "900", marginTop: 5 }, activityOut: { color: UI.muted, fontSize: 13 }, bullish: { backgroundColor: "#173C35", borderRadius: 8, paddingHorizontal: 8, paddingVertical: 6 }, bullishText: { color: UI.mint, fontSize: 8, fontWeight: "900" }, activityChart: { flexDirection: "row", alignItems: "flex-end", justifyContent: "space-between", marginTop: 11 }, chartMeta: { color: UI.muted, fontSize: 9 }, progress: { height: 7, backgroundColor: "#183249", borderRadius: 6, marginTop: 12, overflow: "hidden" }, progressFill: { height: 7, borderRadius: 6, backgroundColor: UI.mint },
  tokenCard: { paddingHorizontal: 12, paddingVertical: 2 }, tokenRow: { flexDirection: "row", alignItems: "center", gap: 9, paddingVertical: 12 }, divider: { borderBottomWidth: 1, borderBottomColor: UI.border }, tokenInfo: { flex: 1 }, tokenNameRow: { flexDirection: "row", alignItems: "center", gap: 7 }, symbol: { color: UI.text, fontSize: 13, fontWeight: "900" }, tokenAge: { color: UI.cyan, fontSize: 8, fontWeight: "900" }, tokenName: { color: UI.muted, fontSize: 10, marginTop: 3 }, tokenScore: { alignItems: "flex-end" }, scoreCaption: { color: UI.muted, fontSize: 8, marginTop: 3 }, empty: { color: UI.muted, textAlign: "center", paddingVertical: 20, fontSize: 11 }, disclaimer: { flexDirection: "row", gap: 8, borderTopWidth: 1, borderTopColor: UI.border, paddingTop: 13 }, disclaimerText: { flex: 1, color: UI.muted, fontSize: 9, lineHeight: 14 },
});
