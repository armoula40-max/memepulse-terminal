export type IntelligenceInput = {
  ageMinutes: number;
  priceUsd: number | null;
  marketCapUsd: number;
  liquidityUsd: number;
  volume1hUsd: number;
  volume24hUsd: number;
  change1hPct: number;
  change24hPct: number;
  buys1h: number;
  sells1h: number;
  holders: number | null;
  topHolderPct: number | null;
  mintAuthorityActive?: boolean | null;
  freezeAuthorityActive?: boolean | null;
};

export type IntelligenceScore = {
  score: number;
  riskScore: number;
  confidence: "LOW" | "MEDIUM" | "HIGH";
  verdict: "WATCH" | "EARLY_ENTRY_CANDIDATE" | "MOMENTUM_CANDIDATE" | "REJECT";
  components: { flow: number; liquidity: number; momentum: number; structure: number; safety: number };
  flags: string[];
  reasons: string[];
};

const clamp = (value: number, min = 0, max = 100) => Math.max(min, Math.min(max, value));

export function scoreIntelligence(input: IntelligenceInput): IntelligenceScore {
  const flags: string[] = [];
  const reasons: string[] = [];
  const totalTrades = input.buys1h + input.sells1h;
  const buyRatio = totalTrades ? input.buys1h / totalTrades : 0;
  const sellRatio = totalTrades ? input.sells1h / totalTrades : 0;
  const volumeBaseline = input.volume24hUsd > 0 ? input.volume24hUsd / 24 : 0;
  const acceleration = volumeBaseline > 0 ? input.volume1hUsd / volumeBaseline : 0;
  const turnover = input.liquidityUsd > 0 ? input.volume1hUsd / input.liquidityUsd : 0;

  let flow = clamp(buyRatio * 65 + Math.min(25, Math.log10(Math.max(1, totalTrades)) * 8));
  let liquidity = clamp(input.liquidityUsd >= 30_000 ? 90 : input.liquidityUsd >= 10_000 ? 70 : input.liquidityUsd >= 5_000 ? 45 : 10);
  let momentum = clamp(input.change1hPct > 0 ? 45 + Math.min(40, input.change1hPct / 4) : 45 + Math.max(-40, input.change1hPct / 3));
  let structure = clamp(50 + Math.min(35, Math.max(-35, (acceleration - 1) * 18)) + (turnover >= 0.05 && turnover <= 8 ? 10 : -15));
  let safety = 70;

  if (input.ageMinutes <= 10 && input.volume1hUsd >= 50) reasons.push("fresh_pair_with_minimum_activity");
  if (buyRatio >= 0.58 && input.buys1h >= 3) reasons.push("persistent_buy_side_dominance");
  if (acceleration >= 2) reasons.push("volume_acceleration_above_baseline");
  if (input.change1hPct > 0 && input.change1hPct < 180) reasons.push("positive_momentum_without_extreme_spike");
  if (input.liquidityUsd >= 10_000) reasons.push("liquidity_supports_manual_exit");

  if (input.priceUsd === null || input.marketCapUsd <= 0) { flags.push("incomplete_market_data"); safety -= 30; }
  if (input.liquidityUsd < 5_000) { flags.push("thin_liquidity"); safety -= 45; }
  if (input.liquidityUsd > 0 && input.marketCapUsd / input.liquidityUsd > 100) { flags.push("market_cap_liquidity_mismatch"); structure -= 25; }
  if (sellRatio >= 0.58 && input.sells1h >= 3) { flags.push("sell_side_dominance"); flow -= 35; }
  if (input.change1hPct < -25) { flags.push("sharp_decline"); momentum -= 35; }
  if (input.change1hPct > 250) { flags.push("possible_vertical_spike"); momentum -= 20; structure -= 20; }
  if (input.topHolderPct !== null && input.topHolderPct > 25) { flags.push("holder_concentration"); safety -= 30; }
  if (input.holders === 0 && input.ageMinutes > 180) { flags.push("no_holders_after_3h"); safety = 0; }
  if (input.mintAuthorityActive === true) { flags.push("mint_authority_active"); safety -= 25; }
  if (input.freezeAuthorityActive === true) { flags.push("freeze_authority_active"); safety -= 35; }

  const riskScore = Math.round(clamp(100 - (safety * 0.55 + liquidity * 0.25 + structure * 0.2)));
  const score = Math.round(clamp(flow * 0.3 + liquidity * 0.2 + momentum * 0.2 + structure * 0.15 + safety * 0.15));
  const hardReject = flags.some((flag) => ["no_holders_after_3h", "freeze_authority_active", "thin_liquidity"].includes(flag));
  const verdict = hardReject || riskScore >= 65 ? "REJECT" : score >= 78 && input.ageMinutes <= 30 ? "EARLY_ENTRY_CANDIDATE" : score >= 72 && momentum >= 65 ? "MOMENTUM_CANDIDATE" : "WATCH";
  const confidence = totalTrades >= 100 && input.volume1hUsd > 10_000 ? "HIGH" : totalTrades >= 20 ? "MEDIUM" : "LOW";
  return { score, riskScore, confidence, verdict, components: { flow: Math.round(clamp(flow)), liquidity: Math.round(clamp(liquidity)), momentum: Math.round(clamp(momentum)), structure: Math.round(clamp(structure)), safety: Math.round(clamp(safety)) }, flags, reasons };
}
