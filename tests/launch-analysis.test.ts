import { describe, expect, it } from "vitest";
import { analyzeLaunch } from "../lib/launch-analysis";

describe("launch analysis", () => {
  it("recognizes a meaningful first buy", () => {
    const result = analyzeLaunch({ txType: "buy", solAmount: 2, marketCapSol: 12, initialBuy: 20 });
    expect(result.verdict).toBe("PROMISING");
    expect(result.reasons).toContain("first observed flow is a buy");
  });

  it("flags a first sell at a tiny market cap", () => {
    const result = analyzeLaunch({ txType: "sell", solAmount: 0.2, marketCapSol: 2, initialBuy: null });
    expect(result.verdict).toBe("DANGER");
  });
});
