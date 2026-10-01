export type LaunchFilterInput = {
  ageMinutes: number;
  volumeUsd: number;
  marketCapUsd: number;
  liquidityUsd: number;
  buys: number;
  sells: number;
  holders: number | null;
  migrated: boolean;
  globalFeesSol: number | null;
};

export type LaunchFilterResult = { eligible: boolean; stage: "NEW_PAIR" | "FINAL_STRETCH" | "MIGRATED" | "REJECTED"; score: number; reasons: string[]; warnings: string[] };

export function evaluateLaunchFilter(input: LaunchFilterInput): LaunchFilterResult {
  const reasons: string[] = [];
  const warnings: string[] = [];
  const earlyFlow = input.buys >= 2 && input.buys >= input.sells;
  const hasHolders = input.holders === null || input.holders > 0;
  if (input.holders === 0 && input.ageMinutes > 180) warnings.push("no_holders_after_3h");
  if (input.globalFeesSol !== null && input.globalFeesSol < 0.5) warnings.push("low_global_fees");

  let stage: LaunchFilterResult["stage"] = "REJECTED";
  let score = 0;
  if (input.migrated && input.marketCapUsd >= 30_000 && input.liquidityUsd >= 10_000 && hasHolders) {
    stage = "MIGRATED"; score = 80; reasons.push("migrated_market_cap_above_30k", "liquidity_above_10k");
  } else if (input.marketCapUsd >= 10_000 && input.liquidityUsd >= 5_000 && earlyFlow && hasHolders) {
    stage = "FINAL_STRETCH"; score = 65; reasons.push("market_cap_above_10k", "early_buy_flow");
  } else if (input.ageMinutes <= 10 && input.volumeUsd >= 50 && input.liquidityUsd >= 1_000 && earlyFlow) {
    stage = "NEW_PAIR"; score = 55; reasons.push("new_pair_under_10m", "volume_above_50", "early_buy_flow");
  }
  if (input.globalFeesSol !== null && input.globalFeesSol >= 0.5) { score += 5; reasons.push("global_fees_above_0_5_sol"); }
  if (warnings.length) score -= warnings.length * 10;
  return { eligible: stage !== "REJECTED" && !warnings.includes("no_holders_after_3h"), stage, score: Math.max(0, Math.min(100, score)), reasons, warnings };
}
