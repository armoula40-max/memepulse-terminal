import { describe, expect, it } from "vitest";
import { DEFAULT_PREFERENCES } from "../lib/preferences";
describe("preferences", () => { it("ships with the requested algorithm defaults", () => { expect(DEFAULT_PREFERENCES.algorithm).toMatchObject({ enabled: true, minLiquidityUsd: 10_000, minHolders10m: 75, minHolders30m: 150, maxTop10PctNew: 30, maxTop10Pct30m: 25, minMarketCapUsd: 20_000, minVolume1hUsd: 5_000, minBuyRatioPct: 55, requireMintDisabled: true, requireFreezeDisabled: true }); }); });
