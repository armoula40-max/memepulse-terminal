import AsyncStorage from "@react-native-async-storage/async-storage";
import * as Notifications from "expo-notifications";
import { classifyTokenMovement, extractPublicSolanaAddress, isSolanaAddress, type WalletActivityKind } from "./wallet-tracker-core";
export { classifyTokenMovement, extractPublicSolanaAddress, isSolanaAddress } from "./wallet-tracker-core";
export type { WalletActivityKind } from "./wallet-tracker-core";

export const SOLANA_RPC_URL = "https://api.mainnet-beta.solana.com";
const STORAGE_KEY = "memepulse.wallet-tracker.v1";
const MAX_ACTIVITIES = 100;

export type WalletMonitorStatus = "CONNECTED" | "RECONNECTING" | "PAUSED" | "ERROR";
export type TrackedWallet = { id: string; address: string; name: string; enabled: boolean; status: WalletMonitorStatus; lastActivityAt: string | null; lastSyncedAt: string | null; error: string | null };
export type WalletActivity = { id: string; walletId: string; walletName: string; signature: string; mint: string | null; symbol: string | null; name: string | null; kind: WalletActivityKind; status: "PENDING" | "CONFIRMED" | "FINALIZED" | "UNKNOWN"; observedAt: string; confirmedAt: string | null; solDelta: number | null; tokenDelta: number | null; usdValue: number | null; note: string };
export type WalletTrackerState = { wallets: TrackedWallet[]; activities: WalletActivity[]; pendingAlerts: boolean; buyAlerts: boolean };

const emptyState: WalletTrackerState = { wallets: [], activities: [], pendingAlerts: false, buyAlerts: true };

export function photonTokenUrl(mint: string | null): string | null { return mint && isSolanaAddress(mint) ? `https://photon-sol.tinyastro.io/en/lp/${encodeURIComponent(mint)}` : null; }

export async function loadWalletTracker(): Promise<WalletTrackerState> { try { const raw = await AsyncStorage.getItem(STORAGE_KEY); return raw ? { ...emptyState, ...JSON.parse(raw) } : emptyState; } catch { return emptyState; } }
export async function saveWalletTracker(state: WalletTrackerState): Promise<void> { await AsyncStorage.setItem(STORAGE_KEY, JSON.stringify(state)); }

async function rpc(method: string, params: unknown[]): Promise<any> {
  const response = await fetch(SOLANA_RPC_URL, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ jsonrpc: "2.0", id: Date.now(), method, params }), signal: AbortSignal.timeout(12_000) });
  if (!response.ok) throw new Error(`RPC_${response.status}`);
  const payload = await response.json() as any;
  if (payload.error) throw new Error(String(payload.error.message ?? "RPC_ERROR"));
  return payload.result;
}

function tokenBalanceMap(rows: any[] | null | undefined, walletAddress: string): Map<string, number> {
  const result = new Map<string, number>();
  for (const row of rows ?? []) if (!row.owner || row.owner === walletAddress) result.set(String(row.mint), (result.get(String(row.mint)) ?? 0) + Number(row.uiTokenAmount?.uiAmount ?? 0));
  return result;
}

export function parseWalletTransaction(args: { signature: string; wallet: TrackedWallet; transaction: any; confirmationStatus: string | null }): WalletActivity[] {
  const tx = args.transaction;
  const meta = tx?.meta;
  if (!meta) return [{ id: `${args.wallet.id}:${args.signature}:unknown`, walletId: args.wallet.id, walletName: args.wallet.name, signature: args.signature, mint: null, symbol: null, name: null, kind: "UNKNOWN", status: "UNKNOWN", observedAt: new Date().toISOString(), confirmedAt: null, solDelta: null, tokenDelta: null, usdValue: null, note: "Transaction metadata unavailable; no buy claim made." }];
  const keys = tx?.transaction?.message?.accountKeys ?? [];
  const walletIndex = keys.findIndex((key: any) => (typeof key === "string" ? key : key.pubkey) === args.wallet.address);
  const solDelta = walletIndex >= 0 && Array.isArray(meta.preBalances) && Array.isArray(meta.postBalances) ? (Number(meta.postBalances[walletIndex]) - Number(meta.preBalances[walletIndex])) / 1e9 : null;
  const before = tokenBalanceMap(meta.preTokenBalances, args.wallet.address);
  const after = tokenBalanceMap(meta.postTokenBalances, args.wallet.address);
  const mints = new Set([...before.keys(), ...after.keys()]);
  const status: WalletActivity["status"] = args.confirmationStatus === "finalized" ? "FINALIZED" : args.confirmationStatus === "confirmed" ? "CONFIRMED" : args.confirmationStatus === "processed" ? "PENDING" : "UNKNOWN";
  const timestamp = tx.blockTime ? new Date(Number(tx.blockTime) * 1000).toISOString() : new Date().toISOString();
  const activities: WalletActivity[] = [];
  for (const mint of mints) {
    const tokenDelta = (after.get(mint) ?? 0) - (before.get(mint) ?? 0);
    if (Math.abs(tokenDelta) < 1e-12) continue;
    const confirmed = status === "CONFIRMED" || status === "FINALIZED";
    const kind = classifyTokenMovement({ tokenDelta, solDelta, confirmed, hasSwapInstruction: false });
    activities.push({ id: `${args.wallet.id}:${args.signature}:${mint}`, walletId: args.wallet.id, walletName: args.wallet.name, signature: args.signature, mint, symbol: null, name: null, kind, status, observedAt: timestamp, confirmedAt: confirmed ? timestamp : null, solDelta, tokenDelta, usdValue: null, note: kind.startsWith("BUY") ? "Positive token balance and negative SOL movement observed; price is not inferred." : "Classified from public pre/post token and SOL balances." });
  }
  return activities.length ? activities : [{ id: `${args.wallet.id}:${args.signature}:other`, walletId: args.wallet.id, walletName: args.wallet.name, signature: args.signature, mint: null, symbol: null, name: null, kind: "OTHER", status, observedAt: timestamp, confirmedAt: status === "CONFIRMED" || status === "FINALIZED" ? timestamp : null, solDelta, tokenDelta: 0, usdValue: null, note: "No token balance movement attributable to this wallet." }];
}

export async function pollWallet(wallet: TrackedWallet, knownSignatures: Set<string>): Promise<{ activities: WalletActivity[]; syncedAt: string }> {
  const rows = await rpc("getSignaturesForAddress", [wallet.address, { limit: 25 }]) as Array<{ signature: string; confirmationStatus?: string | null; blockTime?: number | null }>;
  const fresh = rows.filter((row) => !knownSignatures.has(row.signature)).slice(0, 10);
  const activities: WalletActivity[] = [];
  for (const row of fresh.reverse()) {
    try {
      const transaction = await rpc("getTransaction", [row.signature, { encoding: "jsonParsed", commitment: "confirmed", maxSupportedTransactionVersion: 0 }]);
      activities.push(...parseWalletTransaction({ signature: row.signature, wallet, transaction, confirmationStatus: row.confirmationStatus ?? null }));
    } catch {
      activities.push({ id: `${wallet.id}:${row.signature}:pending`, walletId: wallet.id, walletName: wallet.name, signature: row.signature, mint: null, symbol: null, name: null, kind: "UNKNOWN", status: "PENDING", observedAt: new Date().toISOString(), confirmedAt: null, solDelta: null, tokenDelta: null, usdValue: null, note: "Transaction fetch pending or unavailable; retrying without a buy claim." });
    }
  }
  return { activities, syncedAt: new Date().toISOString() };
}

export async function notifyWalletBuy(activity: WalletActivity): Promise<string | null> {
  if (!activity.kind.startsWith("BUY")) return null;
  return Notifications.scheduleNotificationAsync({ content: { title: "Tracked Trader Bought", body: `${activity.walletName} · ${activity.mint ? `token ${activity.mint.slice(0, 6)}…` : "token metadata pending"} · ${activity.status}`, data: { address: activity.mint, signature: activity.signature }, sound: "shopify_catch.wav" }, trigger: null });
}

export function mergeActivities(existing: WalletActivity[], incoming: WalletActivity[]): WalletActivity[] { return Array.from(new Map([...existing, ...incoming].map((item) => [item.id, item])).values()).sort((a, b) => new Date(b.observedAt).getTime() - new Date(a.observedAt).getTime()).slice(0, MAX_ACTIVITIES); }
