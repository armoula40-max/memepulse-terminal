import { describe, expect, it } from "vitest";
import { scoreIntelligence } from "../lib/intelligence-score";

const base = { ageMinutes: 8, priceUsd: 0.001, marketCapUsd: 25_000, liquidityUsd: 15_000, volume1hUsd: 4_000, volume24hUsd: 20_000, change1hPct: 18, change24hPct: 18, buys1h: 80, sells1h: 30, holders: 120, topHolderPct: 8 };

describe("intelligence score", () => {
  it("recognizes early buy flow without calling it guaranteed", () => {
    const result = scoreIntelligence(base);
    expect(result.score).toBeGreaterThan(50);
    expect(result.reasons).toContain("persistent_buy_side_dominance");
  });
  it("rejects incomplete or dangerous liquidity data", () => {
    const result = scoreIntelligence({ ...base, priceUsd: null, liquidityUsd: 0, holders: 0, ageMinutes: 240 });
    expect(result.verdict).toBe("REJECT");
    expect(result.flags).toContain("incomplete_market_data");
    expect(result.flags).toContain("no_holders_after_3h");
  });
  it("penalizes vertical spikes and concentrated holders", () => {
    const result = scoreIntelligence({ ...base, change1hPct: 500, topHolderPct: 40 });
    expect(result.flags).toContain("possible_vertical_spike");
    expect(result.flags).toContain("holder_concentration");
  });
});
