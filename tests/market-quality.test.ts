import { describe, expect, it } from "vitest";
import { evaluateMarketQuality } from "../lib/market-quality";

describe("market quality filter", () => {
  const passing = { liquidityUsd: 10_000, marketCapUsd: 20_000, holders: 150, top10HolderPct: 25 };

  it("accepts a token at the exact configured thresholds", () => {
    expect(evaluateMarketQuality(passing)).toMatchObject({ eligible: true, status: "PASS" });
  });

  it("rejects tokens below liquidity, holders, or market cap thresholds", () => {
    expect(evaluateMarketQuality({ ...passing, liquidityUsd: 9_999 }).reasons).toContain("liquidity_below_10k");
    expect(evaluateMarketQuality({ ...passing, holders: 149 }).reasons).toContain("holders_below_150");
    expect(evaluateMarketQuality({ ...passing, marketCapUsd: 19_999 }).reasons).toContain("market_cap_below_20k");
  });

  it("rejects excessive top-ten concentration", () => {
    expect(evaluateMarketQuality({ ...passing, top10HolderPct: 25.01 }).reasons).toContain("top10_above_25pct");
  });

  it("does not pass when holder data is unavailable", () => {
    expect(evaluateMarketQuality({ ...passing, holders: null, top10HolderPct: null })).toMatchObject({ eligible: false, status: "UNKNOWN" });
  });
});
