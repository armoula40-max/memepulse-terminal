export type MarketQualityInput = {
  liquidityUsd: number;
  marketCapUsd: number;
  holders: number | null;
  top10HolderPct: number | null;
};

export type MarketQualityResult = {
  eligible: boolean;
  status: "PASS" | "REJECT" | "UNKNOWN";
  reasons: string[];
};

export const DEFAULT_MARKET_QUALITY_FILTER = {
  minLiquidityUsd: 10_000,
  minHolders: 150,
  maxTop10HolderPct: 25,
  minMarketCapUsd: 20_000,
} as const;

export function evaluateMarketQuality(
  input: MarketQualityInput,
  filter = DEFAULT_MARKET_QUALITY_FILTER,
): MarketQualityResult {
  const reasons: string[] = [];
  let unknown = false;

  if (!Number.isFinite(input.liquidityUsd) || input.liquidityUsd < filter.minLiquidityUsd) reasons.push("liquidity_below_10k");
  if (!Number.isFinite(input.marketCapUsd) || input.marketCapUsd < filter.minMarketCapUsd) reasons.push("market_cap_below_20k");
  if (input.holders === null || !Number.isFinite(input.holders)) unknown = true;
  else if (input.holders < filter.minHolders) reasons.push("holders_below_150");
  if (input.top10HolderPct === null || !Number.isFinite(input.top10HolderPct)) unknown = true;
  else if (input.top10HolderPct > filter.maxTop10HolderPct) reasons.push("top10_above_25pct");

  if (reasons.length > 0) return { eligible: false, status: "REJECT", reasons };
  if (unknown) return { eligible: false, status: "UNKNOWN", reasons: ["holder_data_unavailable"] };
  return { eligible: true, status: "PASS", reasons: ["passes_market_quality_filter"] };
}
