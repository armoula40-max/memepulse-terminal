import { describe, expect, it } from "vitest";
import { DEFAULT_ALGORITHM_SETTINGS, evaluateToken } from "../lib/token-rules";

const baseToken = {
  address: "11111111111111111111111111111111", symbol: "TEST", name: "Test", priceUsd: 1,
  liquidityUsd: 20_000, volume24hUsd: 10_000, volume1hUsd: 5_000, change1hPct: 2, change24hPct: 4,
  buys1h: 60, sells1h: 40, pairUrl: "", pairAddress: "", pairCreatedAt: new Date().toISOString(),
  marketCapUsd: 20_000, dexId: "test", source: "dexscreener" as const, observedAt: new Date().toISOString(),
};

describe("new-token algorithm", () => {
  it("keeps a candidate pending when required on-chain facts are unavailable", () => {
    const result = evaluateToken(baseToken, DEFAULT_ALGORITHM_SETTINGS);
    expect(result.eligible).toBe(false);
    expect(result.pending).toEqual(expect.arrayContaining(["Mint authority", "Freeze authority"]));
  });

  it("rejects candidates below configured market thresholds", () => {
    const result = evaluateToken({ ...baseToken, liquidityUsd: 9_999 }, DEFAULT_ALGORITHM_SETTINGS);
    expect(result.eligible).toBe(false);
    expect(result.failures).toContain("Liquidity < $10,000");
  });
});
