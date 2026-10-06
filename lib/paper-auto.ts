import AsyncStorage from "@react-native-async-storage/async-storage";
import { executePaperOrder, type PaperAccount, type PaperPosition } from "./paper-ledger";
import type { ProAssessment } from "./pro-strategy";

const CONFIG_KEY = "memepulse.paper-auto.v1";
export type AutoPaperConfig = {
  enabled: boolean;
  maxOpenPositions: number;
  maxPositionPct: number;
  stopLossPct: number;
  takeProfitPct: number;
  maxHoldMinutes: number;
};
export type AutoPaperCandidate = { address: string; symbol: string; priceUsd: number | null; liquidityUsd: number; assessment: ProAssessment };
export type AutoPaperAction = { side: "BUY" | "SELL"; symbol: string; reason: string; notionalUsd: number };
export const DEFAULT_AUTO_PAPER_CONFIG: AutoPaperConfig = { enabled: false, maxOpenPositions: 3, maxPositionPct: 0.025, stopLossPct: 0.10, takeProfitPct: 0.15, maxHoldMinutes: 20 };

export async function loadAutoPaperConfig(): Promise<AutoPaperConfig> {
  try { return { ...DEFAULT_AUTO_PAPER_CONFIG, ...(JSON.parse((await AsyncStorage.getItem(CONFIG_KEY)) ?? "null") ?? {}) }; } catch { return DEFAULT_AUTO_PAPER_CONFIG; }
}
export async function saveAutoPaperConfig(config: AutoPaperConfig) { await AsyncStorage.setItem(CONFIG_KEY, JSON.stringify(config)); }

export function runAutoPaperCycle(account: PaperAccount, candidates: AutoPaperCandidate[], config: AutoPaperConfig, now = Date.now()): { account: PaperAccount; actions: AutoPaperAction[] } {
  if (!config.enabled) return { account, actions: [] };
  let next = account;
  const actions: AutoPaperAction[] = [];
  const byAddress = new Map(candidates.map((candidate) => [candidate.address, candidate]));

  for (const position of account.positions) {
    const candidate = byAddress.get(position.address);
    const price = candidate?.priceUsd ?? null;
    if (!candidate || price === null || price <= 0) continue;
    const returnPct = position.averagePriceUsd > 0 ? (price - position.averagePriceUsd) / position.averagePriceUsd : 0;
    const heldMinutes = position.openedAt ? (now - Date.parse(position.openedAt)) / 60_000 : 0;
    const reason = returnPct <= -config.stopLossPct ? `AUTO_STOP_LOSS ${(returnPct * 100).toFixed(1)}%` : returnPct >= config.takeProfitPct ? `AUTO_TAKE_PROFIT ${(returnPct * 100).toFixed(1)}%` : heldMinutes >= config.maxHoldMinutes ? "AUTO_TIME_EXIT" : "";
    if (!reason) continue;
    const result = executePaperOrder(next, { side: "SELL", address: position.address, symbol: position.symbol, priceUsd: price, liquidityUsd: candidate.liquidityUsd, notionalUsd: position.quantity * price, reason });
    next = result.account;
    actions.push({ side: "SELL", symbol: position.symbol, reason, notionalUsd: result.trade.requestedUsd });
  }

  const openAddresses = new Set(next.positions.map((position) => position.address));
  for (const candidate of candidates) {
    if (next.positions.length >= config.maxOpenPositions || openAddresses.has(candidate.address)) break;
    if (candidate.assessment.decision !== "ENTER" || candidate.priceUsd === null || candidate.priceUsd <= 0) continue;
    const notionalUsd = Math.min(candidate.assessment.firstTrancheUsd, next.cashUsd * config.maxPositionPct);
    if (notionalUsd < 10) continue;
    const result = executePaperOrder(next, { side: "BUY", address: candidate.address, symbol: candidate.symbol, priceUsd: candidate.priceUsd, liquidityUsd: candidate.liquidityUsd, notionalUsd, reason: "AUTO_ENTRY_PRO_ENTER" });
    next = result.account;
    openAddresses.add(candidate.address);
    actions.push({ side: "BUY", symbol: candidate.symbol, reason: "AUTO_ENTRY_PRO_ENTER", notionalUsd: result.trade.requestedUsd });
  }
  return { account: next, actions };
}

export function autoExitReason(position: PaperPosition, priceUsd: number, config: AutoPaperConfig, now = Date.now()) {
  const returnPct = position.averagePriceUsd > 0 ? (priceUsd - position.averagePriceUsd) / position.averagePriceUsd : 0;
  const heldMinutes = position.openedAt ? (now - Date.parse(position.openedAt)) / 60_000 : 0;
  if (returnPct <= -config.stopLossPct) return `AUTO_STOP_LOSS ${(returnPct * 100).toFixed(1)}%`;
  if (returnPct >= config.takeProfitPct) return `AUTO_TAKE_PROFIT ${(returnPct * 100).toFixed(1)}%`;
  if (heldMinutes >= config.maxHoldMinutes) return "AUTO_TIME_EXIT";
  return null;
}
