import { describe, expect, it } from "vitest";
import { DEFAULT_AUTO_PAPER_CONFIG, runAutoPaperCycle } from "../lib/paper-auto";
import { createPaperAccount, executePaperOrder } from "../lib/paper-ledger";
import type { ProAssessment } from "../lib/pro-strategy";

const assessment = (decision: ProAssessment["decision"] = "ENTER"): ProAssessment => ({ score: 80, decision, venue: "RAYDIUM", reasons: [], warnings: [], gates: { data: true, venue: true, liquidity: true, chase: true, flow: true, age: true, migration: true }, entryPriceUsd: 1, invalidationPriceUsd: 0.9, targetOnePriceUsd: 1.15, targetTwoPriceUsd: 1.25, suggestedNotionalUsd: 100, firstTrancheUsd: 50, ageHours: 0.05, riskReward: 2.5, flowPressure: 75, tradeVelocity: 70, liquidityEfficiency: 40, shortHorizonBias: 60 });

const candidate = (priceUsd: number, decision: ProAssessment["decision"] = "ENTER") => ({ address: "ray-token", symbol: "RAY", priceUsd, liquidityUsd: 100_000, assessment: assessment(decision) });

describe("auto paper trading", () => {
  it("opens only an ENTER candidate within the configured exposure", () => {
    const result = runAutoPaperCycle(createPaperAccount(), [candidate(1)], { ...DEFAULT_AUTO_PAPER_CONFIG, enabled: true }, Date.now());
    expect(result.actions).toHaveLength(1);
    expect(result.actions[0]?.side).toBe("BUY");
    expect(result.account.positions).toHaveLength(1);
    expect(result.account.positions[0]?.openedAt).toBeTruthy();
  });
  it("closes a position at the stop loss", () => {
    const opened = executePaperOrder(createPaperAccount(), { side: "BUY", address: "ray-token", symbol: "RAY", priceUsd: 1, liquidityUsd: 100_000, notionalUsd: 100 }).account;
    const result = runAutoPaperCycle(opened, [candidate(0.89, "WATCH")], { ...DEFAULT_AUTO_PAPER_CONFIG, enabled: true }, Date.now());
    expect(result.actions[0]?.reason).toContain("AUTO_STOP_LOSS");
    expect(result.account.positions).toHaveLength(0);
  });
  it("does nothing when disabled", () => {
    const result = runAutoPaperCycle(createPaperAccount(), [candidate(1)], DEFAULT_AUTO_PAPER_CONFIG);
    expect(result.actions).toHaveLength(0);
    expect(result.account.positions).toHaveLength(0);
  });
});
