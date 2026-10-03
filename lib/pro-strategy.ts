export type ProVenue = "PUMP_FUN_CURVE" | "RAYDIUM" | "PUMPSWAP" | "OTHER";

export type ProTokenInput = {
  address: string;
  symbol: string;
  name: string;
  priceUsd: number | null;
  marketCapUsd: number;
  liquidityUsd: number;
  volume1hUsd: number;
  volume24hUsd?: number;
  change1hPct: number;
  change24hPct: number;
  buys1h: number;
  sells1h: number;
  pairCreatedAt: string | null;
  venue?: ProVenue;
  observedAt?: string;
};

export type ProAssessment = {
  score: number;
  decision: "ENTER" | "WATCH" | "REJECT";
  venue: ProVenue;
  reasons: string[];
  warnings: string[];
  gates: { data: boolean; venue: boolean; liquidity: boolean; chase: boolean; flow: boolean; age: boolean; migration: boolean };
  entryPriceUsd: number;
  invalidationPriceUsd: number;
  targetOnePriceUsd: number;
  targetTwoPriceUsd: number;
  suggestedNotionalUsd: number;
  firstTrancheUsd: number;
  ageHours: number | null;
  riskReward: number;
};

const MAX_DAILY_MOVE = 25;
const MIN_CURVE_LIQUIDITY_USD = 5_000;
const MIN_AMM_LIQUIDITY_USD = 50_000;
const MIN_VOLUME_1H_USD = 5_000;
const MAX_FIRST_TRANCHE_PCT = 0.025;
const RAYDIUM_CUTOFF = Date.parse("2025-03-20T00:00:00.000Z");

export function classifyProVenue(dexId: string | undefined, address: string, pairCreatedAt: string | null): ProVenue {
  const dex = String(dexId ?? "").toLowerCase();
  if (dex.includes("raydium")) return "RAYDIUM";
  if (dex.includes("pump_swap") || dex.includes("pumpswap")) return "PUMPSWAP";
  if (dex.includes("pump.fun") || dex.includes("pumpfun") || address.endsWith("pump")) return "PUMP_FUN_CURVE";
  // A token launched before the PumpSwap cutover can only be called Raydium when the pair reports it.
  if (pairCreatedAt && Date.parse(pairCreatedAt) < RAYDIUM_CUTOFF && dex === "raydium") return "RAYDIUM";
  return "OTHER";
}

export function venueLabel(venue: ProVenue) {
  return venue === "PUMP_FUN_CURVE" ? "PUMP.FUN CURVE" : venue === "RAYDIUM" ? "RAYDIUM AMM" : venue === "PUMPSWAP" ? "PUMPSWAP AMM" : "OTHER DEX";
}

export function assessProToken(token: ProTokenInput, cashUsd = 10_000): ProAssessment {
  const price = token.priceUsd ?? 0;
  const venue = token.venue ?? "OTHER";
  const ageHours = token.pairCreatedAt ? Math.max(0, (Date.now() - new Date(token.pairCreatedAt).getTime()) / 3_600_000) : null;
  const data = price > 0 && token.marketCapUsd > 0 && token.liquidityUsd > 0 && token.volume1hUsd > 0 && Boolean(token.pairCreatedAt);
  const venueGate = venue !== "OTHER";
  const amm = venue === "RAYDIUM" || venue === "PUMPSWAP";
  const liquidityFloor = amm ? MIN_AMM_LIQUIDITY_USD : MIN_CURVE_LIQUIDITY_USD;
  const liquidity = token.liquidityUsd >= liquidityFloor && token.liquidityUsd >= 20 * 100;
  const chase = token.change24hPct <= MAX_DAILY_MOVE && token.change1hPct <= 15;
  const buySellRatio = token.sells1h > 0 ? token.buys1h / token.sells1h : token.buys1h > 0 ? 2 : 0;
  const flow = buySellRatio >= 1.05 && token.volume1hUsd >= MIN_VOLUME_1H_USD && token.buys1h >= 10;
  const age = ageHours !== null && ageHours >= 0.5;
  // DexScreener alone cannot prove a Pump.fun curve completion/migration signature.
  const migration = venue === "PUMP_FUN_CURVE" ? false : venue === "RAYDIUM" || venue === "PUMPSWAP";
  const reasons: string[] = [];
  const warnings: string[] = [];
  let score = 0;

  if (venue === "PUMP_FUN_CURVE") { score += 10; reasons.push("مصدر Pump.fun/curve ظاهر"); warnings.push("حالة المنحنى والترحيل تحتاج تأكيد on-chain"); }
  else if (venue === "RAYDIUM") { score += 15; reasons.push("تجمع Raydium مؤكد من المصدر"); }
  else if (venue === "PUMPSWAP") { score += 15; reasons.push("تجمع PumpSwap الحديث ظاهر"); }
  else warnings.push("المنصة ليست Pump.fun أو Raydium أو PumpSwap");
  if (token.liquidityUsd >= liquidityFloor * 4) { score += 20; reasons.push(amm ? "عمق AMM مناسب للمحاكاة" : "سيولة curve قابلة للمراقبة"); }
  else if (token.liquidityUsd >= liquidityFloor) { score += 10; warnings.push(amm ? "عمق AMM أقل من المستوى الكامل" : "سيولة curve محدودة"); }
  else warnings.push(`السيولة أقل من حد ${amm ? "$50k AMM" : "$5k curve"}`);
  if (token.volume1hUsd >= 50_000) { score += 15; reasons.push("حجم تداول نشط على المنصة"); }
  else if (token.volume1hUsd >= MIN_VOLUME_1H_USD) { score += 8; reasons.push("حجم تداول قابل للمراقبة"); }
  else warnings.push("الحجم الحديث ضعيف");
  if (buySellRatio >= 1.5) { score += 15; reasons.push("صافي تدفق شراء قوي"); }
  else if (buySellRatio >= 1.05) { score += 9; reasons.push("تدفق شراء موجب"); }
  else warnings.push("تدفق البيع أعلى أو غير مؤكد");
  if (token.buys1h >= 25) { score += 10; reasons.push("عدد عمليات شراء كافٍ نسبيًا"); }
  else if (token.buys1h >= 10) score += 5;
  if (token.change1hPct >= 0 && token.change1hPct <= 15) { score += 10; reasons.push("زخم قصير غير مطارد"); }
  else if (token.change1hPct > 15) warnings.push("احتمال مطاردة ارتفاع قصير");
  if (token.change24hPct >= 0 && token.change24hPct <= MAX_DAILY_MOVE) { score += 5; reasons.push("الأداء اليومي داخل نطاق غير مطارد"); }
  else if (token.change24hPct > MAX_DAILY_MOVE) warnings.push("تغير 24 ساعة يتجاوز حد المطاردة");
  if (data) score += 5; else warnings.push("بيانات السعر أو وقت الزوج ناقصة");
  if (age) reasons.push("نافذة التحقق الزمنية مكتملة"); else warnings.push("انتظر 30 دقيقة على الأقل قبل تقييم الدخول");
  if (!migration) warnings.push("لا يوجد تأكيد migration signature؛ لا دخول آلي من curve");

  const gates = { data, venue: venueGate, liquidity, chase, flow, age, migration };
  const safe = data && venueGate && liquidity && chase && flow && migration;
  const decision = safe && age && score >= 75 ? "ENTER" : safe && score >= 65 ? "WATCH" : "REJECT";
  const entryPriceUsd = price;
  const invalidationPriceUsd = price * (amm ? 0.9 : 0.85);
  const targetOnePriceUsd = price * 1.15;
  const targetTwoPriceUsd = price * 1.25;
  const riskPerUnit = entryPriceUsd - invalidationPriceUsd;
  const riskReward = riskPerUnit > 0 ? (targetTwoPriceUsd - entryPriceUsd) / riskPerUnit : 0;
  const maxByLiquidity = token.liquidityUsd / 20;
  const suggestedNotionalUsd = Math.max(25, Math.min(cashUsd * 0.05, maxByLiquidity));
  const firstTrancheUsd = Math.max(10, Math.min(suggestedNotionalUsd * 0.4, cashUsd * MAX_FIRST_TRANCHE_PCT));

  return { score: Math.round(Math.min(100, score)), decision, venue, reasons, warnings, gates, entryPriceUsd, invalidationPriceUsd, targetOnePriceUsd, targetTwoPriceUsd, suggestedNotionalUsd, firstTrancheUsd, ageHours, riskReward: Number(riskReward.toFixed(2)) };
}

export function formatAgeHours(ageHours: number | null) {
  if (ageHours === null || !Number.isFinite(ageHours)) return "UNKNOWN";
  if (ageHours < 24) return `${Math.floor(ageHours)}h`;
  return `${Math.floor(ageHours / 24)}d ${Math.floor(ageHours % 24)}h`;
}
