import WebSocket from "ws";
import { ENV } from "./_core/env";

export type NewTokenEvent = { mint: string; symbol: string; name: string; uri: string; creator: string; marketCapSol: number | null; initialBuy: number | null; createdAt: string; source: "pumpportal" };
export type FirstTradeEvent = { mint: string; txType: "buy" | "sell"; solAmount: number | null; tokenAmount: number | null; marketCapSol: number | null; trader: string | null; createdAt: string; source: "pumpportal" };
const events: NewTokenEvent[] = [];
const firstTrades: FirstTradeEvent[] = [];
const trackedMints = new Set<string>();
let socket: WebSocket | null = null;
let connected = false;
export function getNewTokenEvents() { return events.slice(0, 250); }
export function getFirstTradeEvents() { return firstTrades.slice(0, 250); }
export function getNewTokenStreamStatus() { return { configured: Boolean(ENV.pumpPortalApiKey), connected, source: "PumpPortal subscribeNewToken + subscribeTokenTrade", trackedTokens: trackedMints.size, note: ENV.pumpPortalApiKey ? "Real-time creation and first-trade monitoring is configured." : "Add PUMPPORTAL_API_KEY to receive real-time creation and first-trade events; public Dexscreener profiles remain the fallback." }; }
function trackToken(mint: string) { if (!socket || trackedMints.has(mint) || trackedMints.size >= 500) return; trackedMints.add(mint); socket.send(JSON.stringify({ method: "subscribeTokenTrade", keys: [mint] })); }
export function startNewTokenStream() {
  if (!ENV.pumpPortalApiKey || socket) return;
  const uri = `wss://pumpportal.fun/api/data?api-key=${encodeURIComponent(ENV.pumpPortalApiKey)}`;
  socket = new WebSocket(uri);
  socket.on("open", () => { connected = true; socket?.send(JSON.stringify({ method: "subscribeNewToken" })); });
  socket.on("message", (raw) => {
    try {
      const item = JSON.parse(String(raw));
      if (item.txType === "create" && item.mint) {
        const mint = String(item.mint);
        events.unshift({ mint, symbol: String(item.symbol ?? "UNKNOWN"), name: String(item.name ?? "Unnamed token"), uri: String(item.uri ?? ""), creator: String(item.traderPublicKey ?? ""), marketCapSol: typeof item.marketCapSol === "number" ? item.marketCapSol : null, initialBuy: typeof item.initialBuy === "number" ? item.initialBuy : null, createdAt: new Date().toISOString(), source: "pumpportal" });
        if (events.length > 250) events.length = 250;
        trackToken(mint);
      }
      if ((item.txType === "buy" || item.txType === "sell") && item.mint) {
        const mint = String(item.mint);
        if (!firstTrades.some((trade) => trade.mint === mint)) {
          firstTrades.unshift({ mint, txType: item.txType, solAmount: typeof item.solAmount === "number" ? item.solAmount : null, tokenAmount: typeof item.tokenAmount === "number" ? item.tokenAmount : null, marketCapSol: typeof item.marketCapSol === "number" ? item.marketCapSol : null, trader: item.traderPublicKey ? String(item.traderPublicKey) : null, createdAt: new Date().toISOString(), source: "pumpportal" });
          if (firstTrades.length > 250) firstTrades.length = 250;
        }
      }
    } catch { /* ignore malformed stream messages */ }
  });
  const reconnect = () => { connected = false; socket = null; trackedMints.clear(); setTimeout(startNewTokenStream, 5_000); };
  socket.on("close", reconnect);
  socket.on("error", reconnect);
}
