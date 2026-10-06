import { useEffect, useMemo, useState } from "react";
import { Platform, Pressable, ScrollView, StyleSheet, Switch, Text, View } from "react-native";
import { MaterialIcons } from "@expo/vector-icons";
import * as Notifications from "expo-notifications";
import { useFocusEffect } from "expo-router";
import { ScreenContainer } from "@/components/screen-container";
import { trpc } from "@/lib/trpc";
import { executePaperOrder, loadPaperAccount, savePaperAccount, type PaperAccount } from "@/lib/paper-ledger";
import { loadPumpPortalTokens, startPumpPortalLiveStream, stopPumpPortalLiveStream } from "@/lib/pumpportal-live";
import { enableLocalMarketMonitoring } from "@/lib/local-background-monitor";
import { DEFAULT_AUTO_PAPER_CONFIG, loadAutoPaperConfig, runAutoPaperCycle, saveAutoPaperConfig, type AutoPaperConfig } from "@/lib/paper-auto";
import { assessProToken, classifyProVenue, formatAgeHours, PRO_MAX_AGE_MINUTES, venueLabel, type ProTokenInput } from "@/lib/pro-strategy";
import { openTokenLink } from "@/lib/token-links";

const C = { bg: "#07111F", surface: "#0D1B2A", border: "#1D3852", text: "#F4F8FC", muted: "#8FA6BC", mint: "#23E6A0", amber: "#F6C667", red: "#FF7180", blue: "#59D6FF" };

export default function ProScreen() {
  const newFeed = trpc.market.newTokens.useQuery(undefined, { staleTime: 2_000, refetchInterval: 5_000 });
  const [account, setAccount] = useState<PaperAccount>(() => ({ cashUsd: 10_000, positions: [], realizedPnlUsd: 0, trades: [] }));
  const [localTokens, setLocalTokens] = useState<Awaited<ReturnType<typeof loadPumpPortalTokens>>>([]);
  const [localStreamReady, setLocalStreamReady] = useState(false);
  const [notificationsReady, setNotificationsReady] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [autoConfig, setAutoConfig] = useState<AutoPaperConfig>(DEFAULT_AUTO_PAPER_CONFIG);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState("");
  useEffect(() => { void loadPaperAccount().then(setAccount); }, []);
  useEffect(() => { void loadAutoPaperConfig().then(setAutoConfig); }, []);
  useEffect(() => {
    let active = true;
    void startPumpPortalLiveStream().then((ready) => { if (active) setLocalStreamReady(ready); });
    void loadPumpPortalTokens().then((tokens) => { if (active) setLocalTokens(tokens); });
    const timer = setInterval(() => { void loadPumpPortalTokens().then((tokens) => { if (active) setLocalTokens(tokens); }); }, 5_000);
    return () => { active = false; clearInterval(timer); stopPumpPortalLiveStream(); };
  }, []);
  useEffect(() => {
    if (Platform.OS === "web") return;
    void Notifications.requestPermissionsAsync().then((permission) => setNotificationsReady(permission.granted));
    void enableLocalMarketMonitoring().then(() => setNotificationsReady(true)).catch(() => setNotificationsReady(false));
  }, []);
  useFocusEffect(() => { let active = true; void loadPaperAccount().then((saved) => { if (active) setAccount(saved); }); return () => { active = false; }; });
  const feedTokens = useMemo(() => {
    const serverTokens = newFeed.data?.tokens ?? [];
    const serverAddresses = new Set(serverTokens.map((token) => token.address));
    const localMarketTokens = localTokens.filter((token) => !serverAddresses.has(token.address)).map((token) => ({ ...token, volume24hUsd: token.volume1hUsd, pairUrl: "", pairAddress: "", dexId: "pump.fun", source: "dexscreener" as const, observedAt: token.capturedAt }));
    return [...serverTokens, ...localMarketTokens];
  }, [localTokens, newFeed.data?.tokens]);

  const assessments = useMemo(() => feedTokens.map((token) => {
    const input: ProTokenInput = { address: token.address, symbol: token.symbol, name: token.name, priceUsd: token.priceUsd, marketCapUsd: token.marketCapUsd, liquidityUsd: token.liquidityUsd, volume1hUsd: token.volume1hUsd, volume24hUsd: token.volume24hUsd, change1hPct: token.change1hPct, change24hPct: token.change24hPct, buys1h: token.buys1h, sells1h: token.sells1h, pairCreatedAt: token.pairCreatedAt, venue: classifyProVenue(token.dexId, token.address, token.pairCreatedAt), observedAt: token.observedAt };
    return { token, assessment: assessProToken(input, account.cashUsd) };
  }).filter(({ assessment }) => assessment.ageHours !== null && assessment.ageHours <= PRO_MAX_AGE_MINUTES / 60).sort((a, b) => {
    const ageDelta = (a.assessment.ageHours ?? Number.POSITIVE_INFINITY) - (b.assessment.ageHours ?? Number.POSITIVE_INFINITY);
    return ageDelta || b.assessment.score - a.assessment.score;
  }).slice(0, 30), [feedTokens, account.cashUsd]);
  const eligible = assessments.filter((item) => item.assessment.decision === "ENTER").length;
  const eventEvidence = useMemo(() => {
    const now = Date.now();
    const captured = assessments.map(({ token }) => getCapturedAt(token)).filter(Boolean) as string[];
    const ages = captured.map((value) => Math.max(0, now - Date.parse(value)) / 60_000).filter(Number.isFinite);
    const enrichment = assessments.map(({ token }) => getEnrichmentLatency(token)).filter((value): value is number => value !== null);
    return { captured: captured.length, live: captured.filter((value) => now - Date.parse(value) <= 30_000).length, avgAge: ages.length ? ages.reduce((sum, value) => sum + value, 0) / ages.length : null, avgEnrichment: enrichment.length ? enrichment.reduce((sum, value) => sum + value, 0) / enrichment.length : null };
  }, [assessments]);
  useEffect(() => {
    if (!autoConfig.enabled || !assessments.length) return;
    let active = true;
    const cycle = async () => {
      const latest = await loadPaperAccount();
      const result = runAutoPaperCycle(latest, assessments.map(({ token, assessment }) => ({ address: token.address, symbol: token.symbol, priceUsd: token.priceUsd, liquidityUsd: token.liquidityUsd, assessment })), autoConfig);
      if (!active || !result.actions.length) return;
      await savePaperAccount(result.account);
      setAccount(result.account);
      setMessage(`AUTO PAPER · ${result.actions.map((action) => `${action.side} ${action.symbol}`).join(" · ")}`);
    };
    void cycle();
    const timer = setInterval(() => { void cycle(); }, 5_000);
    return () => { active = false; clearInterval(timer); };
  }, [assessments, autoConfig]);

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

  const refreshNow = async () => {
    if (refreshing) return;
    setRefreshing(true); setMessage("Refreshing PumpPortal feed…");
    try {
      stopPumpPortalLiveStream();
      const ready = await startPumpPortalLiveStream();
      const [tokens] = await Promise.all([loadPumpPortalTokens(), newFeed.refetch()]);
      setLocalStreamReady(ready); setLocalTokens(tokens);
      setMessage(`Feed refreshed · ${tokens.length} local PumpPortal tokens`);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "Refresh failed");
    } finally { setRefreshing(false); }
  };
  const toggleAutoPaper = (enabled: boolean) => { const next = { ...autoConfig, enabled }; setAutoConfig(next); void saveAutoPaperConfig(next); };

  return <ScreenContainer className="px-5" containerClassName="bg-background"><ScrollView contentContainerStyle={styles.content}>
    <Text style={styles.kicker}>NEW LAUNCH PAPER DESK · 0–{PRO_MAX_AGE_MINUTES} MIN</Text>
    <View style={styles.titleRow}><View><Text style={styles.title}>Pro</Text><Text style={styles.subtitle}>بوابات أمان + تأكيد متعدد المصادر + دخول ورقي متدرج</Text></View><View style={styles.badge}><MaterialIcons name="verified" size={15} color={C.mint} /><Text style={styles.badgeText}>NO LEVERAGE</Text></View></View>
    <View style={styles.notice}><MaterialIcons name="bolt" size={17} color={C.amber} /><Text style={styles.noticeText}>محرك Pro يراقب إطلاقات PumpPortal الجديدة، يرتبها، ويفتح Paper Trading لاختبار الدخول والسيولة وإدارة المخاطر. التداول الحقيقي معطل عمدًا في هذا الإصدار.</Text></View>
    <View style={styles.metrics}><Metric label="CASH" value={`$${account.cashUsd.toFixed(0)}`} /><Metric label="ELIGIBLE" value={String(eligible)} /><Metric label="WATCHLIST" value={String(assessments.length)} /></View>
    <View style={styles.autoCard}><View style={styles.autoCopy}><Text style={styles.autoTitle}>AUTO PAPER TRADING</Text><Text style={styles.autoText}>{autoConfig.enabled ? "يفتح ويغلق صفقات محاكاة فقط كل 5 ثوانٍ أثناء تشغيل Pro." : "متوقف افتراضيًا. يستخدم ENTER فقط، حد خسارة 10%، هدف 15%، و3 مراكز كحد أقصى."}</Text><Text style={styles.autoHint}>Android background monitoring is best-effort; the OS cannot be forced to keep the app alive.</Text></View><Switch value={autoConfig.enabled} onValueChange={toggleAutoPaper} trackColor={{ false: "#1A3029", true: "#2C7459" }} thumbColor={autoConfig.enabled ? C.mint : C.muted} /></View>
    <View style={styles.method}><Text style={styles.sectionTitle}>SOLANA NEW-LAUNCH METHOD</Text><Text style={styles.methodText}>هذه الصفحة تعزل الأزواج الجديدة فقط خلال أول {PRO_MAX_AGE_MINUTES} دقائق من وقت إنشاء الزوج. تبدأ المراقبة من Pump.fun curve، ثم لا تُقبل إلا بعد ظهور Raydium أو PumpSwap. لا توجد صفقات حقيقية، ولا ندّعي أن DexScreener وحده يثبت حرق السيولة أو إلغاء mint أو غياب bundle.</Text></View>
    <View style={styles.evidence}><View style={styles.evidenceHeader}><MaterialIcons name="timeline" size={16} color={C.blue} /><Text style={styles.sectionTitle}>EVENT EVIDENCE · HOT PATH</Text></View><Text style={styles.evidenceText}>التقاط الحدث أولًا، ثم إثراء السوق خارج المسار الحرج. الأوقات أدناه هي أوقات الرصد الفعلية وليست وقت طلب HTTP.</Text><View style={styles.evidenceStats}><Stat label="CAPTURED" value={String(eventEvidence.captured)} /><Stat label="LIVE ≤30S" value={String(eventEvidence.live)} /><Stat label="AVG AGE" value={eventEvidence.avgAge === null ? "—" : `${eventEvidence.avgAge.toFixed(1)}m`} /><Stat label="ENRICH" value={eventEvidence.avgEnrichment === null ? "—" : `${Math.round(eventEvidence.avgEnrichment)}ms`} /></View></View>
    <View style={styles.refreshRow}><Text style={styles.stream}>{newFeed.data?.stream.connected ? "PUMPPORTAL SERVER · CONNECTED" : localStreamReady ? "PUMPPORTAL LOCAL KEY · READY" : newFeed.data?.stream.configured ? "PUMPPORTAL SERVER · RECONNECTING" : "PUMPPORTAL SERVER · NOT CONFIGURED"}{notificationsReady ? " · PRO ALERTS ON" : " · ALERT PERMISSION REQUIRED"}</Text><Pressable onPress={() => void refreshNow()} disabled={refreshing} style={[styles.refreshButton, refreshing && styles.disabled]}><MaterialIcons name="refresh" size={15} color={C.bg} /><Text style={styles.refreshText}>{refreshing ? "REFRESHING" : "REFRESH"}</Text></Pressable></View>
    {newFeed.error ? <Text style={styles.error}>PumpPortal feed unavailable: {newFeed.error.message}</Text> : null}
    {message ? <Text style={styles.message}>{message}</Text> : null}
    <Text style={styles.section}>NEW LAUNCHES · {PRO_MAX_AGE_MINUTES} MINUTE WINDOW</Text>
    {assessments.map(({ token, assessment }) => <View key={token.address} style={styles.card}>
      <Pressable onPress={() => void openTokenLink(token.address, "photon")} style={styles.cardTop}><View style={{ flex: 1 }}><Text style={styles.symbol}>${token.symbol}</Text><Text style={styles.name}>{token.name}</Text><Text style={styles.meta}>{venueLabel(assessment.venue)} · {token.pairCreatedAt ? `Age ${formatAgeHours(assessment.ageHours)}` : "Age UNKNOWN"} · ${Math.round(token.marketCapUsd).toLocaleString()} MC</Text><Text style={styles.evidenceLine}>{freshnessLabel(getCapturedAt(token))} · CAPTURED {formatTimestamp(getCapturedAt(token))} · ENRICH {formatLatency(getEnrichmentLatency(token))}</Text></View><View style={styles.scoreBox}><Text style={styles.score}>{assessment.score}</Text><Text style={styles.scoreLabel}>{assessment.decision}</Text></View></Pressable>
      <View style={styles.stats}><Stat label="Price" value={formatPrice(token.priceUsd)} /><Stat label="Liquidity" value={`$${Math.round(token.liquidityUsd).toLocaleString()}`} /><Stat label="FLOW" value={`${assessment.flowPressure}/100`} /><Stat label="VELOCITY" value={`${assessment.tradeVelocity}/100`} /></View><Text style={styles.behaviorLine}>FLOW PRESSURE {assessment.flowPressure} · LIQ EFF {assessment.liquidityEfficiency}% · SHORT HORIZON {assessment.shortHorizonBias}</Text>
      <Text style={styles.reason}>{assessment.reasons.slice(0, 3).join(" · ") || "لا توجد أسباب كافية للدخول"}</Text>
      {assessment.warnings.length ? <Text style={styles.warning}>{assessment.warnings.slice(0, 2).join(" · ")}</Text> : null}
      <View style={styles.levels}><Text style={styles.level}>Invalidation {formatPrice(assessment.invalidationPriceUsd)}</Text><Text style={styles.level}>T1 {formatPrice(assessment.targetOnePriceUsd)}</Text><Text style={styles.level}>T2 {formatPrice(assessment.targetTwoPriceUsd)}</Text></View>
      <View style={styles.actions}><Pressable onPress={() => void openTokenLink(token.address, "photon")} style={styles.marketButton}><Text style={styles.marketText}>PHOTON</Text></Pressable><Pressable disabled={assessment.decision === "REJECT" || busy === token.address} onPress={() => void enterPaper(token, assessment.firstTrancheUsd)} style={[styles.enterButton, assessment.decision !== "ENTER" && styles.disabled]}><Text style={styles.enterText}>{busy === token.address ? "OPENING…" : assessment.decision === "ENTER" ? `PAPER ENTER $${assessment.firstTrancheUsd.toFixed(0)}` : assessment.decision === "WATCH" ? `MANUAL PAPER $${assessment.firstTrancheUsd.toFixed(0)}` : "REJECTED"}</Text></Pressable></View>
    </View>)}
    {!assessments.length ? <Text style={styles.empty}>{newFeed.isLoading ? "جاري استقبال إطلاقات PumpPortal الجديدة…" : "لا توجد عملات جديدة مؤهلة ضمن نافذة 0–7 دقائق حاليًا. العملات الأقدم تُستبعد عمدًا من صفحة Pro."}</Text> : null}
  </ScrollView></ScreenContainer>;
}
function Metric({ label, value }: { label: string; value: string }) { return <View style={styles.metric}><Text style={styles.metricLabel}>{label}</Text><Text style={styles.metricValue}>{value}</Text></View>; }
function Stat({ label, value }: { label: string; value: string }) { return <View style={styles.stat}><Text style={styles.statLabel}>{label}</Text><Text style={styles.statValue}>{value}</Text></View>; }
function formatPrice(value: number | null) { if (value === null || !Number.isFinite(value)) return "—"; return value >= 0.01 ? `$${value.toFixed(4)}` : `$${value.toExponential(2)}`; }
function getCapturedAt(token: unknown) { const item = token as { capturedAt?: string; observedAt?: string }; return item.capturedAt ?? item.observedAt ?? null; }
function getEnrichmentLatency(token: unknown) { const item = token as { enrichmentLatencyMs?: number; enrichedAt?: string; capturedAt?: string }; if (typeof item.enrichmentLatencyMs === "number") return item.enrichmentLatencyMs; if (item.enrichedAt && item.capturedAt) { const value = Date.parse(item.enrichedAt) - Date.parse(item.capturedAt); return Number.isFinite(value) ? Math.max(0, value) : null; } return null; }
function formatTimestamp(value: string | null) { return value ? new Date(value).toLocaleTimeString() : "UNKNOWN"; }
function formatLatency(value: number | null) { return value === null ? "UNKNOWN" : `${Math.round(value)}ms`; }
function freshnessLabel(value: string | null) { if (!value) return "UNKNOWN"; const age = Date.now() - Date.parse(value); if (!Number.isFinite(age) || age < 0) return "UNKNOWN"; return age <= 30_000 ? "LIVE" : age <= 120_000 ? "DELAYED" : "STALE"; }
  const styles = StyleSheet.create({ content: { paddingTop: 16, paddingBottom: 32, gap: 12 }, kicker: { color: C.mint, fontSize: 10, fontWeight: "900", letterSpacing: 1.3 }, titleRow: { flexDirection: "row", justifyContent: "space-between", alignItems: "flex-start" }, title: { color: C.text, fontSize: 30, fontWeight: "900" }, subtitle: { color: C.muted, fontSize: 11, marginTop: 3 }, badge: { flexDirection: "row", alignItems: "center", gap: 5, borderColor: C.border, borderWidth: 1, borderRadius: 10, paddingHorizontal: 8, paddingVertical: 6 }, badgeText: { color: C.mint, fontSize: 8, fontWeight: "900" }, notice: { flexDirection: "row", gap: 8, backgroundColor: "#211B0E", borderColor: "#5A421D", borderWidth: 1, borderRadius: 13, padding: 11 }, noticeText: { flex: 1, color: C.amber, fontSize: 10, lineHeight: 15 }, metrics: { flexDirection: "row", gap: 8 }, metric: { flex: 1, backgroundColor: C.surface, borderColor: C.border, borderWidth: 1, borderRadius: 12, padding: 10 }, metricLabel: { color: C.muted, fontSize: 8, fontWeight: "900" }, metricValue: { color: C.mint, fontSize: 18, fontWeight: "900", marginTop: 4 }, autoCard: { flexDirection: "row", alignItems: "center", gap: 10, backgroundColor: "#2A2412", borderColor: "#66511F", borderWidth: 1, borderRadius: 14, padding: 12 }, autoCopy: { flex: 1 }, autoTitle: { color: C.amber, fontSize: 10, fontWeight: "900", letterSpacing: 1 }, autoText: { color: C.muted, fontSize: 9, lineHeight: 14, marginTop: 4 }, autoHint: { color: C.amber, fontSize: 8, lineHeight: 12, marginTop: 4 }, method: { backgroundColor: "#102039", borderColor: "#24466F", borderWidth: 1, borderRadius: 14, padding: 12 }, sectionTitle: { color: C.blue, fontSize: 10, fontWeight: "900", letterSpacing: 1 }, methodText: { color: C.muted, fontSize: 10, lineHeight: 15, marginTop: 5 }, evidence: { backgroundColor: "#0B251D", borderColor: "#164D3B", borderWidth: 1, borderRadius: 14, padding: 12, gap: 7 }, evidenceHeader: { flexDirection: "row", alignItems: "center", gap: 7 }, evidenceText: { color: C.muted, fontSize: 9, lineHeight: 14 }, evidenceStats: { flexDirection: "row", gap: 5 }, evidenceLine: { color: C.blue, fontSize: 8, fontWeight: "800", marginTop: 4 }, behaviorLine: { color: C.blue, fontSize: 8, fontWeight: "800" }, refreshRow: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 8 }, stream: { flex: 1, color: C.mint, fontSize: 9, fontWeight: "900", letterSpacing: 0.7 }, refreshButton: { flexDirection: "row", alignItems: "center", gap: 4, backgroundColor: C.mint, borderRadius: 8, paddingHorizontal: 9, paddingVertical: 7 }, refreshText: { color: C.bg, fontSize: 8, fontWeight: "900" }, error: { color: C.red, fontSize: 10, fontWeight: "800" }, message: { color: C.mint, fontSize: 10, fontWeight: "800" }, section: { color: C.muted, fontSize: 9, fontWeight: "900", letterSpacing: 1.3, marginTop: 5 }, card: { backgroundColor: C.surface, borderColor: C.border, borderWidth: 1, borderRadius: 15, padding: 12, gap: 9 }, cardTop: { flexDirection: "row", alignItems: "flex-start" }, symbol: { color: C.text, fontSize: 16, fontWeight: "900" }, name: { color: C.muted, fontSize: 10, marginTop: 2 }, meta: { color: C.muted, fontSize: 9, marginTop: 5 }, scoreBox: { alignItems: "center", borderWidth: 1, borderColor: C.mint, borderRadius: 10, paddingHorizontal: 9, paddingVertical: 5 }, score: { color: C.mint, fontSize: 17, fontWeight: "900" }, scoreLabel: { color: C.mint, fontSize: 7, fontWeight: "900", marginTop: 1 }, stats: { flexDirection: "row", gap: 6 }, stat: { flex: 1, borderTopWidth: 1, borderTopColor: C.border, paddingTop: 6 }, statLabel: { color: C.muted, fontSize: 8 }, statValue: { color: C.text, fontSize: 9, fontWeight: "800", marginTop: 2 }, reason: { color: C.mint, fontSize: 9, lineHeight: 14 }, warning: { color: C.amber, fontSize: 9, lineHeight: 14 }, levels: { flexDirection: "row", justifyContent: "space-between" }, level: { color: C.muted, fontSize: 8 }, actions: { flexDirection: "row", gap: 8 }, marketButton: { borderWidth: 1, borderColor: C.blue, borderRadius: 8, paddingHorizontal: 11, paddingVertical: 8 }, marketText: { color: C.blue, fontSize: 9, fontWeight: "900" }, enterButton: { flex: 1, alignItems: "center", justifyContent: "center", backgroundColor: C.mint, borderRadius: 8, paddingHorizontal: 8, paddingVertical: 8 }, enterText: { color: C.bg, fontSize: 9, fontWeight: "900" }, disabled: { opacity: 0.45, backgroundColor: C.border }, empty: { color: C.muted, textAlign: "center", padding: 24 } });
