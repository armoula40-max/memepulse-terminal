import type { MarketToken } from "../server/market-data";

export type FilterMode = "strict" | "early" | "balanced";

export type AlgorithmSettings = {
  enabled: boolean;
  minLiquidityUsd: number;
  minHolders10m: number;
  minHolders30m: number;
  maxTop10PctNew: number;
  maxTop10Pct30m: number;
  minMarketCapUsd: number;
  minVolume1hUsd: number;
  minBuyRatioPct: number;
  minMomentumPct: number;
  requireMintDisabled: boolean;
  requireFreezeDisabled: boolean;
};

export const DEFAULT_ALGORITHM_SETTINGS: AlgorithmSettings = {
  enabled: true,
  minLiquidityUsd: 10_000,
  minHolders10m: 75,
  minHolders30m: 150,
  maxTop10PctNew: 30,
  maxTop10Pct30m: 25,
  minMarketCapUsd: 20_000,
  minVolume1hUsd: 5_000,
  minBuyRatioPct: 55,
  minMomentumPct: 0,
  requireMintDisabled: true,
  requireFreezeDisabled: true,
};

export const EARLY_SNIPER_ALGORITHM_SETTINGS: AlgorithmSettings = {
  ...DEFAULT_ALGORITHM_SETTINGS,
  minLiquidityUsd: 3_000,
  minHolders10m: 20,
  minHolders30m: 75,
  maxTop10PctNew: 40,
  maxTop10Pct30m: 35,
  minMarketCapUsd: 8_000,
  minVolume1hUsd: 1_000,
  minBuyRatioPct: 52,
  minMomentumPct: 3,
};

export const BALANCED_ALGORITHM_SETTINGS: AlgorithmSettings = {
  ...DEFAULT_ALGORITHM_SETTINGS,
  minLiquidityUsd: 7_500,
  minHolders10m: 50,
  minHolders30m: 110,
  maxTop10PctNew: 35,
  maxTop10Pct30m: 30,
  minMarketCapUsd: 15_000,
  minVolume1hUsd: 3_000,
  minBuyRatioPct: 54,
  minMomentumPct: 2,
};

export const VCS_ALGORITHM_SETTINGS: AlgorithmSettings = {
  ...DEFAULT_ALGORITHM_SETTINGS,
  minLiquidityUsd: 10_000,
  minMarketCapUsd: 20_000,
  minVolume1hUsd: 5_000,
  minBuyRatioPct: 55,
  minMomentumPct: 2,
};

export function settingsForMode(mode: FilterMode): AlgorithmSettings {
  if (mode === "early") return { ...EARLY_SNIPER_ALGORITHM_SETTINGS };
  if (mode === "balanced") return { ...BALANCED_ALGORITHM_SETTINGS };
  return { ...DEFAULT_ALGORITHM_SETTINGS };
}

export type AlgorithmResult = {
  eligible: boolean;
  pending: string[];
  failures: string[];
};

export function evaluateToken(
  token: MarketToken,
  settings: AlgorithmSettings,
): AlgorithmResult {
  const failures: string[] = [];
  const pending: string[] = [];
  const ageMinutes = token.pairCreatedAt
    ? Math.max(
        0,
        (Date.now() - new Date(token.pairCreatedAt).getTime()) / 60_000,
      )
    : null;
  const buyRatio =
    token.buys1h + token.sells1h > 0
      ? (token.buys1h / (token.buys1h + token.sells1h)) * 100
      : null;
  if (token.liquidityUsd < settings.minLiquidityUsd)
    failures.push(`Liquidity < $${settings.minLiquidityUsd.toLocaleString()}`);
  if (token.marketCapUsd < settings.minMarketCapUsd)
    failures.push(`MC < $${settings.minMarketCapUsd.toLocaleString()}`);
  if (token.volume1hUsd < settings.minVolume1hUsd)
    failures.push(`1h volume < $${settings.minVolume1hUsd.toLocaleString()}`);
  if (token.change1hPct < settings.minMomentumPct)
    failures.push(`Momentum < ${settings.minMomentumPct}%`);
  if (buyRatio !== null && buyRatio < settings.minBuyRatioPct)
    failures.push(`Buy ratio < ${settings.minBuyRatioPct}%`);
  if (buyRatio === null) pending.push("Buy ratio");
  if (ageMinutes !== null && ageMinutes <= 10) {
    if (token.holders == null)
      pending.push(`Holders ≥ ${settings.minHolders10m} at 10m`);
    else if (token.holders < settings.minHolders10m)
      failures.push(`Holders < ${settings.minHolders10m} at 10m`);
    if (token.top10Pct == null)
      pending.push(`Top 10 ≤ ${settings.maxTop10PctNew}% at launch`);
    else if (token.top10Pct > settings.maxTop10PctNew)
      failures.push(`Top 10 > ${settings.maxTop10PctNew}%`);
  } else if (ageMinutes !== null && ageMinutes >= 30) {
    if (token.holders == null)
      pending.push(`Holders ≥ ${settings.minHolders30m} at 30m`);
    else if (token.holders < settings.minHolders30m)
      failures.push(`Holders < ${settings.minHolders30m} at 30m`);
    if (token.top10Pct == null)
      pending.push(`Top 10 ≤ ${settings.maxTop10Pct30m}% at 30m`);
    else if (token.top10Pct > settings.maxTop10Pct30m)
      failures.push(`Top 10 > ${settings.maxTop10Pct30m}%`);
  }
  if (settings.requireMintDisabled && token.mintAuthority == null)
    pending.push("Mint authority");
  else if (settings.requireMintDisabled && token.mintAuthority !== false)
    failures.push("Mint authority active");
  if (settings.requireFreezeDisabled && token.freezeAuthority == null)
    pending.push("Freeze authority");
  else if (settings.requireFreezeDisabled && token.freezeAuthority !== false)
    failures.push("Freeze authority active");
  const uniquePending = Array.from(new Set(pending));
  return {
    eligible: failures.length === 0 && uniquePending.length === 0,
    pending: uniquePending,
    failures,
  };
}

export function algorithmSummary(settings: AlgorithmSettings) {
  return `${settings.enabled ? "ON" : "OFF"} · $${settings.minLiquidityUsd / 1000}K liquidity · ${settings.minBuyRatioPct}% buys`;
}
