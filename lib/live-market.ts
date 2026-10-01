import AsyncStorage from "@react-native-async-storage/async-storage";
import { classifySignalOutcome, isStrictEarlyFlow, type SignalSnapshot, type SignalOutcome } from "./signal-outcomes";

export type LiveMarketItem = {
  address: string;
  symbol: string;
  name: string;
  priceUsd: number | null;
  marketCapUsd: number;
  liquidityUsd: number;
  volume1hUsd: number;
  change1hPct: number;
  change24hPct: number;
  buys1h: number;
  sells1h: number;
  pairCreatedAt: string | null;
  capturedAt: string;
  source: string;
  signal: "EARLY_FLOW" | "MOMENTUM" | "X10_CANDIDATE" | "X100_CANDIDATE" | "RISK";
  peakChangePct: number;
  firstSignalAt: string | null;
  firstSignalPriceUsd: number | null;
  snapshots: SignalSnapshot[];
  outcome: SignalOutcome;
  currentMultiple: number | null;
  maxMultiple: number | null;
  drawdownPct: number | null;
};

const KEY = "memepulse.live-market.v1";
const MAX_ITEMS = 50;

export async function loadLiveMarket(): Promise<LiveMarketItem[]> {
  try { return JSON.parse((await AsyncStorage.getItem(KEY)) ?? "[]") as LiveMarketItem[]; } catch { return []; }
}

export type LiveMarketTokenInput = Pick<LiveMarketItem, "address" | "symbol" | "name" | "priceUsd" | "marketCapUsd" | "liquidityUsd" | "volume1hUsd" | "change1hPct" | "change24hPct" | "buys1h" | "sells1h" | "pairCreatedAt" | "source">;

export async function captureLiveMarket(tokens: LiveMarketTokenInput[]) {
  const current = await loadLiveMarket();
  const byAddress = new Map(current.map((item) => [item.address, item]));
  for (const token of tokens) {
    const ageMs = token.pairCreatedAt ? Date.now() - new Date(token.pairCreatedAt).getTime() : -1;
    if (token.priceUsd === null || token.priceUsd <= 0 || token.marketCapUsd <= 0 || token.liquidityUsd <= 0 || token.volume1hUsd <= 0 || ageMs < 0 || ageMs > 5 * 60_000) continue;
    const previous = byAddress.get(token.address);
    const capturedAt = new Date().toISOString();
    const ageMinutes = token.pairCreatedAt ? Math.max(0, (Date.now() - new Date(token.pairCreatedAt).getTime()) / 60_000) : 99999;
    const strictEarly = isStrictEarlyFlow({ ageMinutes, liquidityUsd: token.liquidityUsd, volume1hUsd: token.volume1hUsd, change1hPct: token.change1hPct, buys1h: token.buys1h, sells1h: token.sells1h, priceUsd: token.priceUsd, marketCapUsd: token.marketCapUsd });
    const change = token.change24hPct;
    const signal: LiveMarketItem["signal"] = change >= 10_000 ? "X100_CANDIDATE" : change >= 900 ? "X10_CANDIDATE" : token.liquidityUsd < 5_000 || token.change1hPct < -25 ? "RISK" : strictEarly ? "EARLY_FLOW" : "MOMENTUM";
    const snapshot: SignalSnapshot = { capturedAt, priceUsd: token.priceUsd, marketCapUsd: token.marketCapUsd, liquidityUsd: token.liquidityUsd, change1hPct: token.change1hPct, change24hPct: token.change24hPct, buys1h: token.buys1h, sells1h: token.sells1h };
    const snapshots = [...(previous?.snapshots ?? []), snapshot].slice(-240);
    // Legacy records did not contain snapshots. Start their measurement at the first
    // observation after this version; never fabricate historical performance.
    const trackable = signal !== "RISK";
    const firstSignalAt = previous?.firstSignalAt ?? (trackable ? capturedAt : null);
    const firstSignalPriceUsd = previous?.firstSignalPriceUsd ?? (trackable ? token.priceUsd : null);
    const first = firstSignalAt ? snapshots.find((point) => point.capturedAt === firstSignalAt) : undefined;
    const outcome = classifySignalOutcome(first, snapshots);
    byAddress.set(token.address, { ...token, capturedAt, signal, peakChangePct: Math.max(previous?.peakChangePct ?? 0, change), firstSignalAt, firstSignalPriceUsd, snapshots, outcome: outcome.outcome, currentMultiple: outcome.multiple, maxMultiple: outcome.maxMultiple, drawdownPct: outcome.drawdownPct });
  }
  const next = Array.from(byAddress.values()).sort((a, b) => new Date(b.capturedAt).getTime() - new Date(a.capturedAt).getTime()).slice(0, MAX_ITEMS);
  await AsyncStorage.setItem(KEY, JSON.stringify(next));
  return next;
}

export async function clearLiveMarket() { await AsyncStorage.removeItem(KEY); }

export function buildLiveMarketReport(items: LiveMarketItem[]) {
  const counts = items.reduce<Record<string, number>>((acc, item) => { acc[item.signal] = (acc[item.signal] ?? 0) + 1; return acc; }, {});
  const outcomes = items.reduce<Record<string, number>>((acc, item) => { acc[item.outcome] = (acc[item.outcome] ?? 0) + 1; return acc; }, {});
  const lines = ["MemePulse Live Market Report", `generated=${new Date().toISOString()}`, `items=${items.length}`, `early_flow=${counts.EARLY_FLOW ?? 0}`, `momentum=${counts.MOMENTUM ?? 0}`, `x10_candidates=${counts.X10_CANDIDATE ?? 0}`, `x100_candidates=${counts.X100_CANDIDATE ?? 0}`, `risk=${counts.RISK ?? 0}`, `post_signal_x10=${outcomes.POST_SIGNAL_X10 ?? 0}`, `post_signal_x100=${outcomes.POST_SIGNAL_X100 ?? 0}`, `already_pumped_x10=${outcomes.ALREADY_PUMPED_X10 ?? 0}`, `failed_after_signal=${outcomes.FAILED_AFTER_SIGNAL ?? 0}`, "", "address,symbol,signal,outcome,price_usd,first_signal_price_usd,current_multiple,max_multiple,drawdown_pct,market_cap_usd,liquidity_usd,volume_1h_usd,change_1h_pct,change_24h_pct,buys_1h,sells_1h,peak_change_pct,captured_at"];
  for (const item of items) lines.push([item.address, item.symbol, item.signal, item.outcome, item.priceUsd ?? "", item.firstSignalPriceUsd ?? "", item.currentMultiple ?? "", item.maxMultiple ?? "", item.drawdownPct ?? "", item.marketCapUsd, item.liquidityUsd, item.volume1hUsd, item.change1hPct, item.change24hPct, item.buys1h, item.sells1h, item.peakChangePct, item.capturedAt].join(","));
  return lines.join("\n");
}
