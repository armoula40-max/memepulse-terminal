import { describe, expect, it } from "vitest";
import { upsideSignal } from "../lib/upside-sniper";
import type { MarketToken } from "../server/market-data";

const token = (overrides: Partial<MarketToken> = {}): MarketToken => ({
  address: "mint",
  symbol: "TEST",
  name: "Test",
  priceUsd: 0.001,
  liquidityUsd: 20_000,
  volume24hUsd: 50_000,
  volume1hUsd: 10_000,
  change1hPct: 12,
  change24hPct: 20,
  buys1h: 80,
  sells1h: 20,
  pairUrl: "",
  pairAddress: "pair",
  pairCreatedAt: new Date().toISOString(),
  marketCapUsd: 100_000,
  dexId: "pumpfun",
  source: "dexscreener",
  observedAt: new Date().toISOString(),
  ...overrides,
});

describe("upside sniper heuristic", () => {
  it("labels a strong early profile as speculative x100", () => {
    expect(upsideSignal(token()).label).toBe("x100 speculative");
  });
  it("does not label weak market data as an upside candidate", () => {
    expect(
      upsideSignal(
        token({
          liquidityUsd: 100,
          volume1hUsd: 1,
          change1hPct: -4,
          buys1h: 1,
          sells1h: 99,
        }),
      ).label,
    ).toBe("watch");
  });
});
