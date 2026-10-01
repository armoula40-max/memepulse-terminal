import { describe, expect, it } from "vitest";
import { evaluateLaunchFilter } from "../lib/launch-filters";

describe("launch stage filters", () => {
  it("accepts early flow above the minimum new-pair volume", () => {
    const result = evaluateLaunchFilter({ ageMinutes: 6, volumeUsd: 75, marketCapUsd: 4_000, liquidityUsd: 1_500, buys: 4, sells: 1, holders: 12, migrated: false, globalFeesSol: null });
    expect(result.stage).toBe("NEW_PAIR");
    expect(result.eligible).toBe(true);
  });
  it("rejects an old token with no holders", () => {
    const result = evaluateLaunchFilter({ ageMinutes: 240, volumeUsd: 30_000, marketCapUsd: 40_000, liquidityUsd: 15_000, buys: 20, sells: 4, holders: 0, migrated: true, globalFeesSol: 1 });
    expect(result.eligible).toBe(false);
    expect(result.warnings).toContain("no_holders_after_3h");
  });
});
