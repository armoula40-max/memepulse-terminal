import * as Notifications from "expo-notifications";
import AsyncStorage from "@react-native-async-storage/async-storage";
import { loadPumpPortalKey } from "./local-secrets";

let socket: WebSocket | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let running = false;
let lastKey = "";
let pumping = false;
const pending = new Map<string, any>();
const inFlight = new Set<string>();
const lastAttempt = new Map<string, number>();
const TOKENS_KEY = "memepulse.pumpportal.tokens.v1";
const MAX_QUALIFIED_TOKENS = 120;
const MAX_PENDING_TOKENS = 80;
const MAX_TOKEN_AGE_MS = 5 * 60_000;
const ENRICH_CONCURRENCY = 4;
const RETRY_AFTER_MS = 15_000;
const DEX_TOKEN_URL = "https://api.dexscreener.com/latest/dex/tokens/";

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

type Pair = {
  chainId?: string;
  baseToken?: { address?: string; symbol?: string; name?: string };
  priceUsd?: string;
  marketCap?: number;
  fdv?: number;
  liquidity?: { usd?: number };
  volume?: { h1?: number; h24?: number };
  priceChange?: { h1?: number; h24?: number };
  txns?: { h1?: { buys?: number; sells?: number } };
  pairCreatedAt?: number;
  pairAddress?: string;
};

export async function loadPumpPortalTokens(): Promise<PumpPortalTokenSeed[]> {
  try {
    return JSON.parse((await AsyncStorage.getItem(TOKENS_KEY)) ?? "[]") as PumpPortalTokenSeed[];
  } catch {
    return [];
  }
}

function complete(pair: Pair, seed: any): PumpPortalTokenSeed | null {
  const priceUsd = Number(pair.priceUsd ?? 0);
  const marketCapUsd = Number(pair.marketCap ?? pair.fdv ?? 0);
  const liquidityUsd = Number(pair.liquidity?.usd ?? 0);
  const volume1hUsd = Number(pair.volume?.h1 ?? 0);
  const pairCreatedAt = pair.pairCreatedAt ? new Date(pair.pairCreatedAt).toISOString() : seed.createdAt;
  const age = Date.now() - new Date(pairCreatedAt).getTime();
  const buys1h = Number(pair.txns?.h1?.buys ?? 0);
  const sells1h = Number(pair.txns?.h1?.sells ?? 0);
  if (pair.chainId !== "solana" || !pair.baseToken?.address || !pair.pairAddress || !Number.isFinite(priceUsd) || priceUsd <= 0 || marketCapUsd <= 0 || liquidityUsd <= 0 || volume1hUsd <= 0 || buys1h <= sells1h || age < 0 || age > MAX_TOKEN_AGE_MS) return null;
  return {
    address: pair.baseToken.address,
    symbol: String(pair.baseToken.symbol ?? seed.symbol ?? "TOKEN").slice(0, 16),
    name: String(pair.baseToken.name ?? seed.name ?? "PumpPortal token").slice(0, 48),
    priceUsd,
    marketCapUsd,
    liquidityUsd,
    volume1hUsd,
    change1hPct: Number(pair.priceChange?.h1 ?? 0),
    change24hPct: Number(pair.priceChange?.h24 ?? 0),
    buys1h,
    sells1h,
    pairCreatedAt,
    source: "pumpportal+market",
    initialBuy: Number(seed.initialBuy ?? 0),
    marketCapSol: Number(seed.marketCapSol ?? 0),
    capturedAt: seed.receivedAt,
  };
}

async function saveQualified(token: PumpPortalTokenSeed) {
  const current = await loadPumpPortalTokens();
  const next = [token, ...current.filter((entry) => entry.address !== token.address)].slice(0, MAX_QUALIFIED_TOKENS);
  await AsyncStorage.setItem(TOKENS_KEY, JSON.stringify(next));
}

function notifyQualified(token: PumpPortalTokenSeed) {
  void Notifications.scheduleNotificationAsync({
    content: { title: `Qualified launch: $${token.symbol}`, body: `MC $${Math.round(token.marketCapUsd).toLocaleString()} · volume $${Math.round(token.volume1hUsd).toLocaleString()} · buys ${token.buys1h} / sells ${token.sells1h}`, data: { address: token.address }, sound: "shopify_catch.wav" },
    trigger: { type: Notifications.SchedulableTriggerInputTypes.TIME_INTERVAL, seconds: 1, repeats: false, channelId: "meme-catch-v2" },
  });
}

async function enrich(seed: any) {
  const address = String(seed.mint);
  try {
    const response = await fetch(`${DEX_TOKEN_URL}${encodeURIComponent(address)}`, { headers: { Accept: "application/json" }, signal: AbortSignal.timeout(6_000) });
    if (!response.ok) return false;
    const payload = await response.json() as { pairs?: Pair[] };
    const pair = (payload.pairs ?? []).find((item) => item.chainId === "solana" && item.baseToken?.address === address) ?? payload.pairs?.find((item) => item.chainId === "solana");
    const token = pair ? complete(pair, seed) : null;
    if (!token) return false;
    await saveQualified(token);
    notifyQualified(token);
    return true;
  } catch {
    return false;
  }
}

async function pumpQueue() {
  if (pumping) return;
  pumping = true;
  try {
    while (running && inFlight.size < ENRICH_CONCURRENCY) {
      const now = Date.now();
      for (const [address, seed] of pending) {
        const age = now - seed.receivedAtMs;
        if (age > MAX_TOKEN_AGE_MS) pending.delete(address);
      }
      const next = Array.from(pending.entries()).find(([address]) => !inFlight.has(address) && Date.now() - (lastAttempt.get(address) ?? 0) >= RETRY_AFTER_MS);
      if (!next) break;
      const [address, seed] = next;
      pending.delete(address);
      inFlight.add(address);
      lastAttempt.set(address, Date.now());
      void enrich(seed).then((qualified) => {
        if (!qualified && Date.now() - seed.receivedAtMs < MAX_TOKEN_AGE_MS && running) pending.set(address, seed);
      }).finally(() => { inFlight.delete(address); void pumpQueue(); });
    }
  } finally {
    pumping = false;
  }
}

function enqueue(item: any) {
  const address = typeof item?.mint === "string" ? item.mint : "";
  if (!address || item.txType !== "create") return;
  const receivedAt = new Date().toISOString();
  pending.delete(address);
  pending.set(address, { ...item, receivedAt, receivedAtMs: Date.now() });
  while (pending.size > MAX_PENDING_TOKENS) pending.delete(pending.keys().next().value as string);
  void pumpQueue();
}

async function connect() {
  if (!running || socket) return;
  const key = await loadPumpPortalKey();
  if (!key) return;
  lastKey = key;
  socket = new WebSocket(`wss://pumpportal.fun/api/data?api-key=${encodeURIComponent(key)}`);
  socket.onopen = () => socket?.send(JSON.stringify({ method: "subscribeNewToken" }));
  socket.onmessage = (event) => { try { enqueue(JSON.parse(String(event.data))); } catch {} };
  const reconnect = () => { socket = null; if (running && !reconnectTimer) reconnectTimer = setTimeout(() => { reconnectTimer = null; void connect(); }, 3_000); };
  socket.onerror = reconnect;
  socket.onclose = reconnect;
}

export async function startPumpPortalLiveStream() { running = true; await connect(); return Boolean(lastKey); }
export function stopPumpPortalLiveStream() { running = false; pending.clear(); inFlight.clear(); if (reconnectTimer) clearTimeout(reconnectTimer); reconnectTimer = null; socket?.close(); socket = null; }
