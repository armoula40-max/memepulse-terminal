import { describe, expect, it } from "vitest";
import { classifySignalOutcome, isStrictEarlyFlow } from "../lib/signal-outcomes";

const point = (minutes: number, priceUsd: number, change1hPct = 5) => ({ capturedAt: new Date(Date.now() - minutes * 60_000).toISOString(), priceUsd, marketCapUsd: 20_000, liquidityUsd: 12_000, change1hPct, change24hPct: 5, buys1h: 20, sells1h: 10 });

describe("signal outcomes", () => {
  it("counts x10 only after the signal price, not from an unrelated ATH", () => {
    const first = point(40, 1);
    const result = classifySignalOutcome(first, [first, { ...point(30, 2), capturedAt: new Date(Date.now() - 30 * 60_000).toISOString() }, { ...point(5, 10), capturedAt: new Date(Date.now() - 5 * 60_000).toISOString() }]);
    expect(result.outcome).toBe("POST_SIGNAL_X10");
    expect(result.maxMultiple).toBe(10);
  });
  it("marks a token FAILED_AFTER_SIGNAL after a sustained collapse", () => {
    const first = point(60, 1);
    const current = { ...point(1, 0.4), capturedAt: new Date(Date.now() - 1 * 60_000).toISOString() };
    const result = classifySignalOutcome(first, [first, current]);
    expect(result.outcome).toBe("FAILED_AFTER_SIGNAL");
    expect(result.multiple).toBe(0.4);
  });
  it("marks a token failed when market cap collapses even if price data is noisy", () => {
    const first = point(60, 1);
    const current = { ...point(1, 0.9), marketCapUsd: 3_000, capturedAt: new Date(Date.now() - 1 * 60_000).toISOString() };
    expect(classifySignalOutcome(first, [first, current]).outcome).toBe("FAILED_AFTER_SIGNAL");
  });
  it("requires multiple safety and flow conditions for strict early flow", () => {
    expect(isStrictEarlyFlow({ ageMinutes: 8, liquidityUsd: 15_000, volume1hUsd: 1_000, change1hPct: 20, buys1h: 20, sells1h: 10, priceUsd: 0.001, marketCapUsd: 25_000 })).toBe(true);
    expect(isStrictEarlyFlow({ ageMinutes: 8, liquidityUsd: 1_000, volume1hUsd: 20_000, change1hPct: 500, buys1h: 500, sells1h: 20, priceUsd: 0.001, marketCapUsd: 25_000 })).toBe(false);
  });
});
