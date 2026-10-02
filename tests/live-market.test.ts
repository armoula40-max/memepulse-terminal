import { beforeAll, describe, expect, it, vi } from "vitest";
import type { LiveMarketItem } from "../lib/live-market";

let buildLiveMarketReport: typeof import("../lib/live-market").buildLiveMarketReport;

beforeAll(async () => {
  vi.stubGlobal("__DEV__", false);
  ({ buildLiveMarketReport } = await import("../lib/live-market"));
});

describe("Live Market historical report", () => {
  it("reports pre-signal pump separately from observed post-signal ATH", () => {
    const item = { address: "token", symbol: "TEST", name: "Test", priceUsd: 0.5, marketCapUsd: 1000, liquidityUsd: 1000, volume1hUsd: 1000, change1hPct: 10, change24hPct: 20, buys1h: 10, sells1h: 5, pairCreatedAt: null, capturedAt: "2026-10-02T00:00:00.000Z", source: "test", signal: "MOMENTUM", peakChangePct: 20, firstSignalAt: "2026-10-01T23:00:00.000Z", firstSignalPriceUsd: 0.25, snapshots: [], outcome: "PENDING", currentMultiple: 2, maxMultiple: 4, drawdownPct: 10, observedAthPriceUsd: 1, observedAthAt: "2026-10-01T23:30:00.000Z", preSignalPump24hPct: 3500, preSignalPumpMultiple: 36, currentVsSignalPct: 100 } as LiveMarketItem;
    const report = buildLiveMarketReport([item]);
    expect(report).toContain("historical_method=observed_local_snapshots_plus_provider_reported_24h_at_first_signal");
    expect(report).toContain("pre_signal_reported_24h_pump_pct");
    expect(report).toContain("3500");
    expect(report).toContain("observed_ath_multiple");
  });
});
