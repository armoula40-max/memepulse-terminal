export type SignalSnapshot = { capturedAt: string; priceUsd: number | null; marketCapUsd: number; liquidityUsd: number; change1hPct: number; change24hPct: number; buys1h: number; sells1h: number };
export type SignalOutcome = "PENDING" | "NO_CONFIRMATION" | "POST_SIGNAL_X2" | "POST_SIGNAL_X5" | "POST_SIGNAL_X10" | "POST_SIGNAL_X100" | "ALREADY_PUMPED_X10" | "ALREADY_PUMPED_X100" | "FAILED_AFTER_SIGNAL";

export function classifySignalOutcome(first: SignalSnapshot | undefined, snapshots: SignalSnapshot[], now = Date.now()): { outcome: SignalOutcome; multiple: number | null; maxMultiple: number | null; drawdownPct: number | null } {
  if (!first || first.priceUsd === null || first.priceUsd <= 0) return { outcome: "PENDING", multiple: null, maxMultiple: null, drawdownPct: null };
  const valid = snapshots.filter((point) => point.priceUsd !== null && point.priceUsd > 0).sort((a, b) => new Date(a.capturedAt).getTime() - new Date(b.capturedAt).getTime());
  const firstPrice = first.priceUsd;
  const maxMultiple = valid.length ? Math.max(...valid.map((point) => (point.priceUsd as number) / firstPrice)) : 1;
  const current = valid.at(-1)?.priceUsd ?? firstPrice;
  const multiple = current / firstPrice;
  const ageMinutes = (now - new Date(first.capturedAt).getTime()) / 60_000;
  const drawdownPct = maxMultiple > 1 ? Number(((1 - multiple / maxMultiple) * 100).toFixed(1)) : null;
  if (first.change24hPct >= 10_000) return { outcome: "ALREADY_PUMPED_X100", multiple: Number(multiple.toFixed(3)), maxMultiple: Number(maxMultiple.toFixed(3)), drawdownPct };
  if (first.change24hPct >= 900) return { outcome: "ALREADY_PUMPED_X10", multiple: Number(multiple.toFixed(3)), maxMultiple: Number(maxMultiple.toFixed(3)), drawdownPct };
  if (ageMinutes < 1) return { outcome: "PENDING", multiple: Number(multiple.toFixed(3)), maxMultiple: Number(maxMultiple.toFixed(3)), drawdownPct };
  if (maxMultiple >= 100 && ageMinutes < 10 && first.change1hPct >= 900) return { outcome: "ALREADY_PUMPED_X100", multiple: Number(multiple.toFixed(3)), maxMultiple: Number(maxMultiple.toFixed(3)), drawdownPct };
  if (maxMultiple >= 10 && ageMinutes < 10 && (first.change1hPct >= 900 || first.marketCapUsd >= 100_000)) return { outcome: "ALREADY_PUMPED_X10", multiple: Number(multiple.toFixed(3)), maxMultiple: Number(maxMultiple.toFixed(3)), drawdownPct };
  if (maxMultiple >= 100) return { outcome: "POST_SIGNAL_X100", multiple: Number(multiple.toFixed(3)), maxMultiple: Number(maxMultiple.toFixed(3)), drawdownPct };
  if (maxMultiple >= 10) return { outcome: "POST_SIGNAL_X10", multiple: Number(multiple.toFixed(3)), maxMultiple: Number(maxMultiple.toFixed(3)), drawdownPct };
  if (maxMultiple >= 5) return { outcome: "POST_SIGNAL_X5", multiple: Number(multiple.toFixed(3)), maxMultiple: Number(maxMultiple.toFixed(3)), drawdownPct };
  if (maxMultiple >= 2) return { outcome: "POST_SIGNAL_X2", multiple: Number(multiple.toFixed(3)), maxMultiple: Number(maxMultiple.toFixed(3)), drawdownPct };
  const currentMarketCap = valid.at(-1)?.marketCapUsd ?? first.marketCapUsd;
  const marketCapCollapse = first.marketCapUsd > 0 && currentMarketCap / first.marketCapUsd <= 0.2;
  if (ageMinutes >= 30 && (multiple <= 0.8 || (drawdownPct ?? 0) >= 40 || marketCapCollapse)) return { outcome: "FAILED_AFTER_SIGNAL", multiple: Number(multiple.toFixed(3)), maxMultiple: Number(maxMultiple.toFixed(3)), drawdownPct };
  return { outcome: ageMinutes >= 30 ? "NO_CONFIRMATION" : "PENDING", multiple: Number(multiple.toFixed(3)), maxMultiple: Number(maxMultiple.toFixed(3)), drawdownPct };
}

export function isStrictEarlyFlow(input: { ageMinutes: number; liquidityUsd: number; volume1hUsd: number; change1hPct: number; buys1h: number; sells1h: number; priceUsd: number | null; marketCapUsd: number }): boolean {
  const total = input.buys1h + input.sells1h;
  const buyRatio = total > 0 ? input.buys1h / total : 0;
  const turnover = input.liquidityUsd > 0 ? input.volume1hUsd / input.liquidityUsd : Infinity;
  return input.ageMinutes <= 30 && input.priceUsd !== null && input.marketCapUsd > 0 && input.liquidityUsd >= 10_000 && input.volume1hUsd >= 50 && input.change1hPct > -10 && input.change1hPct < 180 && buyRatio >= 0.58 && input.buys1h >= 5 && turnover >= 0.02 && turnover <= 8;
}
