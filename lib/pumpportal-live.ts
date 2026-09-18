import * as Notifications from "expo-notifications";
import AsyncStorage from "@react-native-async-storage/async-storage";
import { loadPumpPortalKey } from "./local-secrets";

let socket: WebSocket | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let running = false;
let lastKey = "";
const TOKENS_KEY = "memepulse.pumpportal.tokens.v1";
const MAX_PUMPPORTAL_TOKENS = 500;

export type PumpPortalTokenSeed = {
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
  source: string;
  initialBuy: number;
  marketCapSol: number;
  capturedAt: string;
};

export async function loadPumpPortalTokens(): Promise<PumpPortalTokenSeed[]> {
  try { return JSON.parse((await AsyncStorage.getItem(TOKENS_KEY)) ?? "[]") as PumpPortalTokenSeed[]; } catch { return []; }
}

async function rememberPumpPortalToken(item: any) {
  const address = typeof item?.mint === "string" ? item.mint : "";
  if (!address) return;
  const current = await loadPumpPortalTokens();
  const capturedAt = new Date().toISOString();
  const token: PumpPortalTokenSeed = {
    address, symbol: String(item.symbol ?? "TOKEN").slice(0, 16), name: String(item.name ?? item.symbol ?? "PumpPortal token").slice(0, 48),
    priceUsd: null, marketCapUsd: 0, liquidityUsd: 0, volume1hUsd: 0, change1hPct: 0, change24hPct: 0, buys1h: 0, sells1h: 0,
    pairCreatedAt: capturedAt, source: "pumpportal", initialBuy: Number(item.initialBuy ?? 0), marketCapSol: Number(item.marketCapSol ?? 0), capturedAt,
  };
  const next = [token, ...current.filter((entry) => entry.address !== address)].slice(0, MAX_PUMPPORTAL_TOKENS);
  await AsyncStorage.setItem(TOKENS_KEY, JSON.stringify(next));
}

function notify(item: any) {
  const mint = typeof item?.mint === "string" ? item.mint : "";
  if (!mint || item.txType !== "create") return;
  const initialBuy = Number(item.initialBuy ?? 0);
  const marketCapSol = Number(item.marketCapSol ?? 0);
  void rememberPumpPortalToken(item);
  void Notifications.scheduleNotificationAsync({ content: { title: `New launch: $${String(item.symbol ?? "TOKEN")}`, body: `Early flow detected · initial buy ${initialBuy || "—"} · market cap ${marketCapSol ? `${marketCapSol.toFixed(2)} SOL` : "—"}`, data: { address: mint }, sound: "shopify_catch.wav" }, trigger: { type: Notifications.SchedulableTriggerInputTypes.TIME_INTERVAL, seconds: 1, repeats: false, channelId: "meme-catch-v2" } });
}

async function connect() {
  if (!running || socket) return;
  const key = await loadPumpPortalKey();
  if (!key) return;
  lastKey = key;
  const NativeWebSocket = WebSocket;
  socket = new NativeWebSocket(`wss://pumpportal.fun/api/data?api-key=${encodeURIComponent(key)}`);
  socket.onopen = () => socket?.send(JSON.stringify({ method: "subscribeNewToken" }));
  socket.onmessage = (event) => { try { notify(JSON.parse(String(event.data))); } catch {} };
  const reconnect = () => { socket = null; if (running && !reconnectTimer) { reconnectTimer = setTimeout(() => { reconnectTimer = null; void connect(); }, 3_000); } };
  socket.onerror = reconnect;
  socket.onclose = reconnect;
}

export async function startPumpPortalLiveStream() { running = true; await connect(); return Boolean(lastKey); }
export function stopPumpPortalLiveStream() { running = false; if (reconnectTimer) clearTimeout(reconnectTimer); reconnectTimer = null; socket?.close(); socket = null; }
