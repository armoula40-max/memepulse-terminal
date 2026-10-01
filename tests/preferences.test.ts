import { describe, expect, it } from "vitest";
import { DEFAULT_PREFERENCES } from "../lib/preferences";
describe("preferences", () => { it("ships with safe defaults", () => { expect(DEFAULT_PREFERENCES).toEqual({ alertMomentum: true, alertLiquidity: true, alertRisk: false, compactCards: false, walletProvider: "phantom", walletAddress: "", demoMode: true, soundAlerts: true }); }); });
