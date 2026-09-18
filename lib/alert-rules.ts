export type AlertRuleState = {
  momentum: boolean;
  liquidity: boolean;
  risk: boolean;
};

export type AlertToken = {
  address: string;
  symbol: string;
  change1hPct: number;
  volume1hUsd: number;
  liquidityUsd: number;
};

export type AlertSignal = {
  rule: keyof AlertRuleState;
  title: string;
  body: string;
};

export function getAlertSignals(token: AlertToken, enabled: AlertRuleState): AlertSignal[] {
  const signals: AlertSignal[] = [];
  const volumeToLiquidity = token.volume1hUsd / Math.max(token.liquidityUsd, 1);

  if (enabled.momentum && token.change1hPct >= 8 && volumeToLiquidity >= 0.25) {
    signals.push({
      rule: "momentum",
      title: `Momentum breakout: $${token.symbol}`,
      body: `1H change +${token.change1hPct.toFixed(1)}% with elevated activity. Review risk before acting.`,
    });
  }

  if (enabled.liquidity && token.liquidityUsd >= 100_000) {
    signals.push({
      rule: "liquidity",
      title: `Liquidity expansion: $${token.symbol}`,
      body: `Observed liquidity is $${Math.round(token.liquidityUsd).toLocaleString()}.`,
    });
  }

  if (enabled.risk && (token.liquidityUsd < 25_000 || token.change1hPct <= -12)) {
    signals.push({
      rule: "risk",
      title: `Risk escalation: $${token.symbol}`,
      body: token.liquidityUsd < 25_000 ? "Thin liquidity detected. Verify sellability independently." : "Sharp 1H decline detected. Review the full risk report.",
    });
  }

  return signals;
}
