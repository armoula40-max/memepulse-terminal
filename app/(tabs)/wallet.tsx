import { useEffect, useMemo, useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { MaterialIcons } from "@expo/vector-icons";
import { useFocusEffect } from "expo-router";
import { ScreenContainer } from "@/components/screen-container";
import { trpc } from "@/lib/trpc";
import { executePaperOrder, loadPaperAccount, savePaperAccount, type PaperAccount } from "@/lib/paper-ledger";
import { assessProToken, formatAgeHours, type ProTokenInput } from "@/lib/pro-strategy";
import { openTokenLink } from "@/lib/token-links";

const C = { bg: "#07111F", surface: "#0D1B2A", border: "#1D3852", text: "#F4F8FC", muted: "#8FA6BC", mint: "#23E6A0", amber: "#F6C667", red: "#FF7180", blue: "#59D6FF" };

export default function ProScreen() {
  const market = trpc.market.latest.useQuery(undefined, { staleTime: 5_000, refetchInterval: 15_000 });
  const [account, setAccount] = useState<PaperAccount>(() => ({ cashUsd: 10_000, positions: [], realizedPnlUsd: 0, trades: [] }));
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState("");
  useEffect(() => { void loadPaperAccount().then(setAccount); }, []);
  useFocusEffect(() => { let active = true; void loadPaperAccount().then((saved) => { if (active) setAccount(saved); }); return () => { active = false; }; });

  const assessments = useMemo(() => (market.data?.tokens ?? []).map((token) => {
    const input: ProTokenInput = { address: token.address, symbol: token.symbol, name: token.name, priceUsd: token.priceUsd, marketCapUsd: token.marketCapUsd, liquidityUsd: token.liquidityUsd, volume1hUsd: token.volume1hUsd, volume24hUsd: token.volume24hUsd, change1hPct: token.change1hPct, change24hPct: token.change24hPct, buys1h: token.buys1h, sells1h: token.sells1h, pairCreatedAt: token.pairCreatedAt, observedAt: token.observedAt };
    return { token, assessment: assessProToken(input, account.cashUsd) };
  }).sort((a, b) => b.assessment.score - a.assessment.score).slice(0, 30), [market.data?.tokens, account.cashUsd]);
  const eligible = assessments.filter((item) => item.assessment.decision === "ENTER").length;

  const enterPaper = async (token: ProTokenInput, notionalUsd: number) => {
    setBusy(token.address); setMessage("");
    try {
      const latest = await loadPaperAccount();
      const result = executePaperOrder(latest, { side: "BUY", address: token.address, symbol: token.symbol, priceUsd: token.priceUsd ?? 0, liquidityUsd: token.liquidityUsd, notionalUsd });
      await savePaperAccount(result.account);
      setAccount(result.account);
      setMessage(`تم فتح ${token.symbol} في Paper Trading · الشريحة الأولى $${notionalUsd.toFixed(2)}`);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "تعذر فتح الصفقة الورقية");
    } finally { setBusy(""); }
  };

  return <ScreenContainer className="px-5" containerClassName="bg-background"><ScrollView contentContainerStyle={styles.content}>
    <Text style={styles.kicker}>RESEARCH-DRIVEN PAPER DESK</Text>
    <View style={styles.titleRow}><View><Text style={styles.title}>Pro</Text><Text style={styles.subtitle}>بوابات أمان + تأكيد متعدد المصادر + دخول ورقي متدرج</Text></View><View style={styles.badge}><MaterialIcons name="verified" size={15} color={C.mint} /><Text style={styles.badgeText}>NO LEVERAGE</Text></View></View>
    <View style={styles.notice}><MaterialIcons name="info-outline" size={17} color={C.amber} /><Text style={styles.noticeText}>هذه خوارزمية بحث ومحاكاة، وليست توصية استثمارية. لا توجد صفقات حقيقية أو ضمان للربح.</Text></View>
    <View style={styles.metrics}><Metric label="CASH" value={`$${account.cashUsd.toFixed(0)}`} /><Metric label="ELIGIBLE" value={String(eligible)} /><Metric label="WATCHLIST" value={String(assessments.length)} /></View>
    <View style={styles.method}><Text style={styles.sectionTitle}>PRO METHOD</Text><Text style={styles.methodText}>يُمنع شراء عملة ممتدة، أو ذات بيانات ناقصة، أو سيولة ضعيفة. الدخول المؤهل يستخدم أول شريحة فقط، مع إبطال عند -10% وأهداف 1.15x و1.25x. لا يوجد توسيط للخسارة.</Text></View>
    {market.error ? <Text style={styles.error}>Market data unavailable: {market.error.message}</Text> : null}
    {message ? <Text style={styles.message}>{message}</Text> : null}
    <Text style={styles.section}>RANKED OPPORTUNITIES</Text>
    {assessments.map(({ token, assessment }) => <View key={token.address} style={styles.card}>
      <Pressable onPress={() => void openTokenLink(token.address, "photon")} style={styles.cardTop}><View style={{ flex: 1 }}><Text style={styles.symbol}>${token.symbol}</Text><Text style={styles.name}>{token.name}</Text><Text style={styles.meta}>{token.pairCreatedAt ? `Age ${formatAgeHours(assessment.ageHours)}` : "Age UNKNOWN"} · ${Math.round(token.marketCapUsd).toLocaleString()} MC</Text></View><View style={styles.scoreBox}><Text style={styles.score}>{assessment.score}</Text><Text style={styles.scoreLabel}>{assessment.decision}</Text></View></Pressable>
      <View style={styles.stats}><Stat label="Price" value={formatPrice(token.priceUsd)} /><Stat label="Liquidity" value={`$${Math.round(token.liquidityUsd).toLocaleString()}`} /><Stat label="1H" value={`${token.change1hPct.toFixed(1)}%`} /><Stat label="Buy/Sell" value={`${token.buys1h}/${token.sells1h}`} /></View>
      <Text style={styles.reason}>{assessment.reasons.slice(0, 3).join(" · ") || "لا توجد أسباب كافية للدخول"}</Text>
      {assessment.warnings.length ? <Text style={styles.warning}>{assessment.warnings.slice(0, 2).join(" · ")}</Text> : null}
      <View style={styles.levels}><Text style={styles.level}>Invalidation {formatPrice(assessment.invalidationPriceUsd)}</Text><Text style={styles.level}>T1 {formatPrice(assessment.targetOnePriceUsd)}</Text><Text style={styles.level}>T2 {formatPrice(assessment.targetTwoPriceUsd)}</Text></View>
      <View style={styles.actions}><Pressable onPress={() => void openTokenLink(token.address, "photon")} style={styles.marketButton}><Text style={styles.marketText}>PHOTON</Text></Pressable><Pressable disabled={assessment.decision === "REJECT" || busy === token.address} onPress={() => void enterPaper(token, assessment.firstTrancheUsd)} style={[styles.enterButton, assessment.decision !== "ENTER" && styles.disabled]}><Text style={styles.enterText}>{busy === token.address ? "OPENING…" : assessment.decision === "ENTER" ? `PAPER ENTER $${assessment.firstTrancheUsd.toFixed(0)}` : assessment.decision === "WATCH" ? `MANUAL PAPER $${assessment.firstTrancheUsd.toFixed(0)}` : "REJECTED"}</Text></Pressable></View>
    </View>)}
    {!assessments.length ? <Text style={styles.empty}>لا توجد بيانات سوق حالية. ستظهر هنا عند وصول بيانات DexScreener.</Text> : null}
  </ScrollView></ScreenContainer>;
}
function Metric({ label, value }: { label: string; value: string }) { return <View style={styles.metric}><Text style={styles.metricLabel}>{label}</Text><Text style={styles.metricValue}>{value}</Text></View>; }
function Stat({ label, value }: { label: string; value: string }) { return <View style={styles.stat}><Text style={styles.statLabel}>{label}</Text><Text style={styles.statValue}>{value}</Text></View>; }
function formatPrice(value: number | null) { if (value === null || !Number.isFinite(value)) return "—"; return value >= 0.01 ? `$${value.toFixed(4)}` : `$${value.toExponential(2)}`; }
const styles = StyleSheet.create({ content: { paddingTop: 16, paddingBottom: 32, gap: 12 }, kicker: { color: C.mint, fontSize: 10, fontWeight: "900", letterSpacing: 1.3 }, titleRow: { flexDirection: "row", justifyContent: "space-between", alignItems: "flex-start" }, title: { color: C.text, fontSize: 30, fontWeight: "900" }, subtitle: { color: C.muted, fontSize: 11, marginTop: 3 }, badge: { flexDirection: "row", alignItems: "center", gap: 5, borderColor: C.border, borderWidth: 1, borderRadius: 10, paddingHorizontal: 8, paddingVertical: 6 }, badgeText: { color: C.mint, fontSize: 8, fontWeight: "900" }, notice: { flexDirection: "row", gap: 8, backgroundColor: "#211B0E", borderColor: "#5A421D", borderWidth: 1, borderRadius: 13, padding: 11 }, noticeText: { flex: 1, color: C.amber, fontSize: 10, lineHeight: 15 }, metrics: { flexDirection: "row", gap: 8 }, metric: { flex: 1, backgroundColor: C.surface, borderColor: C.border, borderWidth: 1, borderRadius: 12, padding: 10 }, metricLabel: { color: C.muted, fontSize: 8, fontWeight: "900" }, metricValue: { color: C.mint, fontSize: 18, fontWeight: "900", marginTop: 4 }, method: { backgroundColor: "#102039", borderColor: "#24466F", borderWidth: 1, borderRadius: 14, padding: 12 }, sectionTitle: { color: C.blue, fontSize: 10, fontWeight: "900", letterSpacing: 1 }, methodText: { color: C.muted, fontSize: 10, lineHeight: 15, marginTop: 5 }, error: { color: C.red, fontSize: 10, fontWeight: "800" }, message: { color: C.mint, fontSize: 10, fontWeight: "800" }, section: { color: C.muted, fontSize: 9, fontWeight: "900", letterSpacing: 1.3, marginTop: 5 }, card: { backgroundColor: C.surface, borderColor: C.border, borderWidth: 1, borderRadius: 15, padding: 12, gap: 9 }, cardTop: { flexDirection: "row", alignItems: "flex-start" }, symbol: { color: C.text, fontSize: 16, fontWeight: "900" }, name: { color: C.muted, fontSize: 10, marginTop: 2 }, meta: { color: C.muted, fontSize: 9, marginTop: 5 }, scoreBox: { alignItems: "center", borderWidth: 1, borderColor: C.mint, borderRadius: 10, paddingHorizontal: 9, paddingVertical: 5 }, score: { color: C.mint, fontSize: 17, fontWeight: "900" }, scoreLabel: { color: C.mint, fontSize: 7, fontWeight: "900", marginTop: 1 }, stats: { flexDirection: "row", gap: 6 }, stat: { flex: 1, borderTopWidth: 1, borderTopColor: C.border, paddingTop: 6 }, statLabel: { color: C.muted, fontSize: 8 }, statValue: { color: C.text, fontSize: 9, fontWeight: "800", marginTop: 2 }, reason: { color: C.mint, fontSize: 9, lineHeight: 14 }, warning: { color: C.amber, fontSize: 9, lineHeight: 14 }, levels: { flexDirection: "row", justifyContent: "space-between" }, level: { color: C.muted, fontSize: 8 }, actions: { flexDirection: "row", gap: 8 }, marketButton: { borderWidth: 1, borderColor: C.blue, borderRadius: 8, paddingHorizontal: 11, paddingVertical: 8 }, marketText: { color: C.blue, fontSize: 9, fontWeight: "900" }, enterButton: { flex: 1, alignItems: "center", justifyContent: "center", backgroundColor: C.mint, borderRadius: 8, paddingHorizontal: 8, paddingVertical: 8 }, enterText: { color: C.bg, fontSize: 9, fontWeight: "900" }, disabled: { opacity: 0.45, backgroundColor: C.border }, empty: { color: C.muted, textAlign: "center", padding: 24 } });
