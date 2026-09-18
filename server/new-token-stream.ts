import WebSocket from "ws";
import { ENV } from "./_core/env";
export type NewTokenEvent = {
  mint: string;
  symbol: string;
  name: string;
  uri: string;
  creator: string;
  marketCapSol: number | null;
  initialBuy: number | null;
  createdAt: string;
  source: "pumpportal";
};
const events: NewTokenEvent[] = [];
let socket: WebSocket | null = null;
let connected = false;
export function getNewTokenEvents() {
  return events.slice(0, 500);
}
export function getNewTokenMints(limit = 500) {
  return events.slice(0, limit).map((event) => event.mint);
}
export function getNewTokenStreamStatus() {
  return {
    configured: Boolean(ENV.pumpPortalApiKey),
    connected,
    source: "PumpPortal subscribeNewToken",
    note: ENV.pumpPortalApiKey
      ? "Real-time token creation stream is configured."
      : "Add PUMPPORTAL_API_KEY to receive real-time creation events; public Dexscreener recent profiles remain the fallback.",
  };
}
export function startNewTokenStream() {
  if (!ENV.pumpPortalApiKey || socket) return;
  const uri = `wss://pumpportal.fun/api/data?api-key=${encodeURIComponent(ENV.pumpPortalApiKey)}`;
  socket = new WebSocket(uri);
  socket.on("open", () => {
    connected = true;
    socket?.send(JSON.stringify({ method: "subscribeNewToken" }));
  });
  socket.on("message", (raw) => {
    try {
      const item = JSON.parse(String(raw));
      if (!item.mint || item.txType !== "create") return;
      events.unshift({
        mint: String(item.mint),
        symbol: String(item.symbol ?? "UNKNOWN"),
        name: String(item.name ?? "Unnamed token"),
        uri: String(item.uri ?? ""),
        creator: String(item.traderPublicKey ?? ""),
        marketCapSol:
          typeof item.marketCapSol === "number" ? item.marketCapSol : null,
        initialBuy:
          typeof item.initialBuy === "number" ? item.initialBuy : null,
        createdAt: new Date().toISOString(),
        source: "pumpportal",
      });
      if (events.length > 500) events.length = 500;
    } catch {
      /* ignore malformed stream messages */
    }
  });
  const reconnect = () => {
    connected = false;
    socket = null;
    setTimeout(startNewTokenStream, 5_000);
  };
  socket.on("close", reconnect);
  socket.on("error", reconnect);
}
