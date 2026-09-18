import type { MarketToken } from "@/server/market-data";

export type UpsideLabel = "x10 candidate" | "x100 speculative" | "watch";
export type UpsideSignal = {
  score: number;
  label: UpsideLabel;
  reasons: string[];
};

export function upsideSignal(token: MarketToken): UpsideSignal {
  const reasons: string[] = [];
  let score = 0;
  const buyRatio =
    token.buys1h + token.sells1h > 0
      ? (token.buys1h / (token.buys1h + token.sells1h)) * 100
      : 0;
  if (token.liquidityUsd >= 10_000) {
    score += 20;
    reasons.push("liquidity floor passed");
  }
  if (token.volume1hUsd >= 5_000) {
    score += 15;
    reasons.push("active 1h volume");
  }
  if (buyRatio >= 55) {
    score += 20;
    reasons.push("buy pressure");
  }
  if (token.change1hPct >= 2) {
    score += 15;
    reasons.push("positive momentum");
  }
  if (token.change1hPct >= 10) {
    score += 10;
    reasons.push("accelerating momentum");
  }
  if (token.marketCapUsd > 0 && token.marketCapUsd <= 250_000) {
    score += 20;
    reasons.push("early market-cap profile");
  }
  if (
    token.liquidityUsd > 0 &&
    token.marketCapUsd > 0 &&
    token.liquidityUsd / token.marketCapUsd >= 0.08
  ) {
    score += 10;
    reasons.push("liquidity supports attention");
  }
  const label: UpsideLabel =
    score >= 85 ? "x100 speculative" : score >= 65 ? "x10 candidate" : "watch";
  return { score: Math.min(100, score), label, reasons };
}
