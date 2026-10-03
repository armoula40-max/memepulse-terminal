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
  observedAt?: string;
};

export type ProAssessment = {
  score: number;
  decision: "ENTER" | "WATCH" | "REJECT";
  reasons: string[];
  warnings: string[];
  gates: { data: boolean; liquidity: boolean; chase: boolean; flow: boolean; age: boolean };
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
const MIN_LIQUIDITY_USD = 2_000;
const MIN_VOLUME_1H_USD = 5_000;
const MAX_FIRST_TRANCHE_PCT = 0.025;

export function assessProToken(token: ProTokenInput, cashUsd = 10_000): ProAssessment {
  const price = token.priceUsd ?? 0;
  const ageHours = token.pairCreatedAt ? Math.max(0, (Date.now() - new Date(token.pairCreatedAt).getTime()) / 3_600_000) : null;
  const data = price > 0 && token.marketCapUsd > 0 && token.liquidityUsd > 0 && token.volume1hUsd > 0 && Boolean(token.pairCreatedAt);
  const liquidity = token.liquidityUsd >= MIN_LIQUIDITY_USD && token.liquidityUsd >= 20 * 100;
  const chase = token.change24hPct <= MAX_DAILY_MOVE && token.change1hPct <= 15;
  const buySellRatio = token.sells1h > 0 ? token.buys1h / token.sells1h : token.buys1h > 0 ? 2 : 0;
  const flow = buySellRatio >= 1.05 && token.volume1hUsd >= MIN_VOLUME_1H_USD;
  const age = ageHours !== null && ageHours >= 72;
  const reasons: string[] = [];
  const warnings: string[] = [];
  let score = 0;

  if (token.liquidityUsd >= 20_000) { score += 20; reasons.push("سيولة مناسبة للتنفيذ الورقي"); }
  else if (token.liquidityUsd >= MIN_LIQUIDITY_USD) { score += 10; reasons.push("سيولة موجودة لكنها محدودة"); warnings.push("السيولة أقل من المستوى الاحترافي الكامل"); }
  else warnings.push("السيولة منخفضة");
  if (token.volume1hUsd >= 50_000) { score += 15; reasons.push("حجم تداول نشط"); }
  else if (token.volume1hUsd >= MIN_VOLUME_1H_USD) { score += 8; reasons.push("حجم تداول قابل للمراقبة"); }
  else warnings.push("الحجم الحديث ضعيف");
  if (buySellRatio >= 1.5) { score += 15; reasons.push("ضغط شراء أعلى من البيع"); }
  else if (buySellRatio >= 1.05) { score += 9; reasons.push("تدفق شراء متوازن إلى إيجابي"); }
  else warnings.push("ضغط البيع ليس مؤيدًا");
  if (token.change1hPct >= 0 && token.change1hPct <= 15) { score += 10; reasons.push("زخم قصير غير ممتد"); }
  else if (token.change1hPct > 15) warnings.push("احتمال مطاردة ارتفاع قصير");
  if (token.change24hPct >= 0 && token.change24hPct <= MAX_DAILY_MOVE) { score += 10; reasons.push("أداء يومي غير مطارد"); }
  else if (token.change24hPct > MAX_DAILY_MOVE) warnings.push("تغير 24 ساعة يتجاوز حد المطاردة");
  if (token.marketCapUsd >= 20_000) { score += 10; reasons.push("قيمة سوقية قابلة للمقارنة"); }
  if (data) score += 5; else warnings.push("بيانات السعر أو التاريخ ناقصة");
  if (age) { score += 5; reasons.push("مرّت نافذة المراقبة الأولية"); }
  else warnings.push("عملة حديثة: مراقبة فقط قبل اكتمال التاريخ");

  const gates = { data, liquidity, chase, flow, age };
  if (!age) warnings.push("Pro لا ينفذ دخولًا آليًا قبل 72 ساعة من إنشاء الزوج");
  if (!chase) warnings.push("السعر ممتد؛ انتظر تصحيحًا أو إعادة اختبار");
  const safe = data && liquidity && chase && flow;
  const decision = safe && age && score >= 75 ? "ENTER" : safe && score >= 65 ? "WATCH" : "REJECT";
  const entryPriceUsd = price;
  const invalidationPriceUsd = price * 0.9;
  const targetOnePriceUsd = price * 1.15;
  const targetTwoPriceUsd = price * 1.25;
  const riskPerUnit = entryPriceUsd - invalidationPriceUsd;
  const riskReward = riskPerUnit > 0 ? (targetTwoPriceUsd - entryPriceUsd) / riskPerUnit : 0;
  const suggestedNotionalUsd = Math.max(25, Math.min(cashUsd * 0.05, token.liquidityUsd / 20));
  const firstTrancheUsd = Math.max(10, Math.min(suggestedNotionalUsd * 0.5, cashUsd * MAX_FIRST_TRANCHE_PCT));

  return { score: Math.round(Math.min(100, score)), decision, reasons, warnings, gates, entryPriceUsd, invalidationPriceUsd, targetOnePriceUsd, targetTwoPriceUsd, suggestedNotionalUsd, firstTrancheUsd, ageHours, riskReward: Number(riskReward.toFixed(2)) };
}

export function formatAgeHours(ageHours: number | null) {
  if (ageHours === null || !Number.isFinite(ageHours)) return "UNKNOWN";
  if (ageHours < 24) return `${Math.floor(ageHours)}h`;
  return `${Math.floor(ageHours / 24)}d ${Math.floor(ageHours % 24)}h`;
}
