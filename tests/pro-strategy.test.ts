import { describe, expect, it } from "vitest";
import { assessProToken, classifyProVenue, PRO_MAX_AGE_MINUTES } from "../lib/pro-strategy";

const base = {
  address: "4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9",
  symbol: "TEST",
  name: "Test Token",
  priceUsd: 1,
  marketCapUsd: 100_000,
  liquidityUsd: 50_000,
  volume1hUsd: 100_000,
  volume24hUsd: 500_000,
  change1hPct: 5,
  change24hPct: 12,
  buys1h: 120,
  sells1h: 80,
  pairCreatedAt: new Date(Date.now() - 5 * 60_000).toISOString(),
  venue: "PUMPSWAP" as const,
};

describe("Pro strategy", () => {
  it("requires age and safety gates before allowing entry", () => {
    const result = assessProToken(base, 10_000);
    expect(result.decision).toBe("ENTER");
    expect(result.gates.data).toBe(true);
    expect(result.gates.liquidity).toBe(true);
    expect(result.firstTrancheUsd).toBeLessThanOrEqual(250);
    expect(result.targetTwoPriceUsd).toBe(1.25);
  });

  it("keeps a new token on watch even when its market metrics are strong", () => {
    const result = assessProToken({ ...base, pairCreatedAt: new Date(Date.now() - 10 * 60_000).toISOString() }, 10_000);
    expect(result.decision).toBe("WATCH");
    expect(result.gates.age).toBe(false);
    expect(result.warnings.some((warning) => warning.includes(String(PRO_MAX_AGE_MINUTES)))).toBe(true);
  });

  it("rejects launches older than the Pro new-token window", () => {
    const result = assessProToken({ ...base, pairCreatedAt: new Date(Date.now() - (PRO_MAX_AGE_MINUTES + 1) * 60_000).toISOString() }, 10_000);
    expect(result.decision).toBe("WATCH");
    expect(result.gates.age).toBe(false);
    expect(result.warnings.some((warning) => warning.includes("0–7"))).toBe(true);
  });

  it("rejects a chased move and weak flow", () => {
    const result = assessProToken({ ...base, change1hPct: 42, change24hPct: 180, buys1h: 20, sells1h: 100 }, 10_000);
    expect(result.decision).toBe("REJECT");
    expect(result.gates.chase).toBe(false);
    expect(result.gates.flow).toBe(false);
  });

  it("rejects an unrelated DEX instead of treating it as a Solana meme venue", () => {
    const result = assessProToken({ ...base, venue: "OTHER" }, 10_000);
    expect(result.decision).toBe("REJECT");
    expect(result.gates.venue).toBe(false);
  });

  it("classifies current and historical venue labels explicitly", () => {
    expect(classifyProVenue("raydium", base.address, base.pairCreatedAt)).toBe("RAYDIUM");
    expect(classifyProVenue("pumpswap", base.address, base.pairCreatedAt)).toBe("PUMPSWAP");
    expect(classifyProVenue("unknown", `${base.address.slice(0, -4)}pump`, base.pairCreatedAt)).toBe("PUMP_FUN_CURVE");
  });
});
