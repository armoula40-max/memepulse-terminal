import AsyncStorage from "@react-native-async-storage/async-storage";
import { simulateFill, type SimulatedFill } from "./simulation";

const ACCOUNT_KEY = "memepulse.paper-account.v2";
export type PaperPosition = { address: string; symbol: string; quantity: number; averagePriceUsd: number; investedUsd: number };
export type PaperAccount = { cashUsd: number; positions: PaperPosition[]; realizedPnlUsd: number; trades: PaperTrade[] };
export type PaperTrade = SimulatedFill & { address: string; symbol: string; quantity: number; timestamp: string };
export type PaperOrderInput = { side: "BUY" | "SELL"; address: string; symbol: string; priceUsd: number; liquidityUsd: number; notionalUsd: number };

export function createPaperAccount(initialCashUsd = 10_000): PaperAccount { return { cashUsd: initialCashUsd, positions: [], realizedPnlUsd: 0, trades: [] }; }
export async function loadPaperAccount(): Promise<PaperAccount> { try { const raw = await AsyncStorage.getItem(ACCOUNT_KEY); return raw ? JSON.parse(raw) as PaperAccount : createPaperAccount(); } catch { return createPaperAccount(); } }
export async function savePaperAccount(account: PaperAccount) { await AsyncStorage.setItem(ACCOUNT_KEY, JSON.stringify(account)); }
export function accountEquity(account: PaperAccount, prices: Record<string, number>): number { return account.cashUsd + account.positions.reduce((total, position) => total + position.quantity * (prices[position.address] ?? position.averagePriceUsd), 0); }
export function positionMark(position: PaperPosition, currentPriceUsd: number) { const positionValueUsd = position.quantity * currentPriceUsd; const profitUsd = positionValueUsd - position.investedUsd; const profitPct = position.investedUsd > 0 ? profitUsd / position.investedUsd * 100 : 0; return { positionValueUsd, profitUsd, profitPct }; }

export function executePaperOrder(account: PaperAccount, input: PaperOrderInput): { account: PaperAccount; trade: PaperTrade } {
  const requestedUsd = Number.isFinite(input.notionalUsd) ? Math.max(0, input.notionalUsd) : 0;
  if (!input.address || !Number.isFinite(input.priceUsd) || input.priceUsd <= 0 || requestedUsd <= 0) throw new Error("Enter a valid amount and select a live token.");
  const existing = account.positions.find((position) => position.address === input.address);
  const positionValue = (existing?.quantity ?? 0) * input.priceUsd;
  if (input.side === "BUY" && requestedUsd + requestedUsd * 0.0125 > account.cashUsd) throw new Error(`Insufficient demo cash. Available: $${account.cashUsd.toFixed(2)}.`);
  if (input.side === "SELL" && requestedUsd > positionValue) throw new Error(`Insufficient ${input.symbol} position. Available: $${positionValue.toFixed(2)}.`);
  const fill = simulateFill({ side: input.side, notionalUsd: requestedUsd, priceUsd: input.priceUsd, liquidityUsd: input.liquidityUsd });
  const quantity = requestedUsd / fill.estimatedPriceUsd;
  const next: PaperAccount = { ...account, positions: account.positions.map((position) => ({ ...position })), trades: [...account.trades] };
  const nextPosition = next.positions.find((position) => position.address === input.address);
  if (input.side === "BUY") {
    const totalCost = requestedUsd + fill.feeUsd;
    next.cashUsd -= totalCost;
    if (nextPosition) { nextPosition.quantity += quantity; nextPosition.investedUsd += totalCost; nextPosition.averagePriceUsd = nextPosition.investedUsd / nextPosition.quantity; }
    else next.positions.push({ address: input.address, symbol: input.symbol, quantity, averagePriceUsd: fill.estimatedPriceUsd, investedUsd: totalCost });
  } else {
    const soldQuantity = Math.min(quantity, nextPosition?.quantity ?? 0); const proceeds = soldQuantity * fill.estimatedPriceUsd;
    next.cashUsd += proceeds - fill.feeUsd;
    if (nextPosition) { const costBasis = nextPosition.averagePriceUsd * soldQuantity; nextPosition.quantity -= soldQuantity; nextPosition.investedUsd -= costBasis; next.realizedPnlUsd += proceeds - fill.feeUsd - costBasis; }
    next.positions = next.positions.filter((position) => position.quantity > 1e-12);
  }
  const trade: PaperTrade = { ...fill, address: input.address, symbol: input.symbol, quantity, timestamp: new Date().toISOString() };
  next.trades.push(trade); return { account: next, trade };
}
