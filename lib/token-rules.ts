import type { MarketToken } from "../server/market-data";

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
  requireMintDisabled: true,
  requireFreezeDisabled: true,
};

export type AlgorithmResult = { eligible: boolean; pending: string[]; failures: string[] };

export function evaluateToken(token: MarketToken, settings: AlgorithmSettings): AlgorithmResult {
  const failures: string[] = [];
  const pending: string[] = [];
  const ageMinutes = token.pairCreatedAt ? Math.max(0, (Date.now() - new Date(token.pairCreatedAt).getTime()) / 60_000) : null;
  const buyRatio = token.buys1h + token.sells1h > 0 ? token.buys1h / (token.buys1h + token.sells1h) * 100 : null;
  if (token.liquidityUsd < settings.minLiquidityUsd) failures.push(`Liquidity < $${settings.minLiquidityUsd.toLocaleString()}`);
  if (token.marketCapUsd < settings.minMarketCapUsd) failures.push(`MC < $${settings.minMarketCapUsd.toLocaleString()}`);
  if (token.volume1hUsd < settings.minVolume1hUsd) failures.push(`1h volume < $${settings.minVolume1hUsd.toLocaleString()}`);
  if (buyRatio !== null && buyRatio < settings.minBuyRatioPct) failures.push(`Buy ratio < ${settings.minBuyRatioPct}%`);
  if (buyRatio === null) pending.push("Buy ratio");
  if (ageMinutes !== null && ageMinutes <= 10) {
    if (token.holders === null || token.holders === undefined) pending.push(`Holders ≥ ${settings.minHolders10m} at 10m`);
    else if (token.holders < settings.minHolders10m) failures.push(`Holders < ${settings.minHolders10m} at 10m`);
    if (token.top10Pct === null || token.top10Pct === undefined) pending.push(`Top 10 ≤ ${settings.maxTop10PctNew}% at launch`);
    else if (token.top10Pct > settings.maxTop10PctNew) failures.push(`Top 10 > ${settings.maxTop10PctNew}%`);
  } else if (ageMinutes !== null && ageMinutes >= 30) {
    if (token.holders === null || token.holders === undefined) pending.push(`Holders ≥ ${settings.minHolders30m} at 30m`);
    else if (token.holders < settings.minHolders30m) failures.push(`Holders < ${settings.minHolders30m} at 30m`);
    if (token.top10Pct === null || token.top10Pct === undefined) pending.push(`Top 10 ≤ ${settings.maxTop10Pct30m}% at 30m`);
    else if (token.top10Pct > settings.maxTop10Pct30m) failures.push(`Top 10 > ${settings.maxTop10Pct30m}%`);
  }
  if (settings.requireMintDisabled && token.mintAuthority !== null && token.mintAuthority !== undefined && token.mintAuthority !== false) failures.push("Mint authority active");
  else if (settings.requireMintDisabled && (token.mintAuthority === null || token.mintAuthority === undefined)) pending.push("Mint authority");
  if (settings.requireFreezeDisabled && token.freezeAuthority !== null && token.freezeAuthority !== undefined && token.freezeAuthority !== false) failures.push("Freeze authority active");
  else if (settings.requireFreezeDisabled && (token.freezeAuthority === null || token.freezeAuthority === undefined)) pending.push("Freeze authority");
  const uniquePending = Array.from(new Set(pending));
  return { eligible: failures.length === 0 && uniquePending.length === 0, pending: uniquePending, failures };
}

export function algorithmSummary(settings: AlgorithmSettings) {
  return `${settings.enabled ? "ON" : "OFF"} · $${settings.minLiquidityUsd / 1000}K liquidity · ${settings.minBuyRatioPct}% buys`;
}
