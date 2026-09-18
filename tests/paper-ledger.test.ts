import { describe, expect, it } from "vitest";
import { createPaperAccount, executePaperOrder } from "../lib/paper-ledger";

describe("paper ledger", () => {
  it("supports a buy followed by a sell with balance and position checks", () => {
    const initial = createPaperAccount(1_000);
    const bought = executePaperOrder(initial, { side: "BUY", address: "mint-1", symbol: "TEST", priceUsd: 1, liquidityUsd: 100_000, notionalUsd: 200 });
    expect(bought.account.cashUsd).toBeLessThan(800);
    expect(bought.account.positions[0].quantity).toBeGreaterThan(0);
    const sold = executePaperOrder(bought.account, { side: "SELL", address: "mint-1", symbol: "TEST", priceUsd: 1.2, liquidityUsd: 100_000, notionalUsd: 50 });
    expect(sold.account.positions[0].quantity).toBeLessThan(bought.account.positions[0].quantity);
    expect(sold.account.trades).toHaveLength(2);
  });

  it("rejects a sell without a position", () => {
    expect(() => executePaperOrder(createPaperAccount(), { side: "SELL", address: "mint-1", symbol: "TEST", priceUsd: 1, liquidityUsd: 100_000, notionalUsd: 10 })).toThrow("Insufficient");
  });
});
