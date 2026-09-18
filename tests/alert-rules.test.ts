import { describe, expect, it } from "vitest";
import { getAlertSignals } from "../lib/alert-rules";

const token = {
  address: "mint-1",
  symbol: "TEST",
  change1hPct: 12,
  volume1hUsd: 40_000,
  liquidityUsd: 120_000,
};

describe("alert rules", () => {
  it("only emits enabled rules", () => {
    expect(getAlertSignals(token, { momentum: true, liquidity: false, risk: false }).map((signal) => signal.rule)).toEqual(["momentum"]);
  });

  it("detects thin liquidity and sharp declines for risk alerts", () => {
    const signals = getAlertSignals({ ...token, liquidityUsd: 10_000, change1hPct: -14 }, { momentum: false, liquidity: false, risk: true });
    expect(signals).toHaveLength(1);
    expect(signals[0].rule).toBe("risk");
    expect(signals[0].body).toContain("Thin liquidity");
  });

  it("does not alert on ordinary movement", () => {
    expect(getAlertSignals({ ...token, change1hPct: 2, liquidityUsd: 50_000 }, { momentum: true, liquidity: true, risk: true })).toEqual([]);
  });
});
