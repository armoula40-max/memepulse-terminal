import { useEffect, useState } from "react";
import type { MarketToken } from "@/server/market-data";
import { loadPumpPortalKey } from "@/lib/pumpportal-key";

export type DirectPumpToken = {
  mint: string;
  symbol: string;
  name: string;
  uri: string;
  creator: string;
  marketCapSol: number | null;
  initialBuy: number | null;
  createdAt: string;
};

const DEX = "https://api.dexscreener.com/latest/dex/tokens/";
function number(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : 0;
}
async function hydrateEvents(events: DirectPumpToken[]) {
  const results = await Promise.all(
    events.slice(0, 40).map(async (event): Promise<MarketToken | null> => {
      try {
        const response = await fetch(`${DEX}${encodeURIComponent(event.mint)}`);
        if (!response.ok) return null;
        const payload = await response.json();
        const pair = (payload.pairs ?? []).find(
          (item: any) => item.chainId === "solana",
        );
        if (!pair) return null;
        const txns = pair.txns?.h1 ?? {};
        const volume = pair.volume ?? {};
        const change = pair.priceChange ?? {};
        return {
          address: event.mint,
          symbol: String(pair.baseToken?.symbol ?? event.symbol).slice(0, 16),
          name: String(pair.baseToken?.name ?? event.name).slice(0, 48),
          priceUsd: pair.priceUsd == null ? null : Number(pair.priceUsd),
          liquidityUsd: number(pair.liquidity?.usd),
          volume24hUsd: number(volume.h24),
          volume1hUsd: number(volume.h1),
          change1hPct: number(change.h1),
          change24hPct: number(change.h24),
          buys1h: number(txns.buys),
          sells1h: number(txns.sells),
          pairUrl: String(
            pair.url ?? `https://dexscreener.com/solana/${event.mint}`,
          ),
          pairAddress: String(pair.pairAddress ?? event.mint),
          pairCreatedAt: pair.pairCreatedAt
            ? new Date(pair.pairCreatedAt).toISOString()
            : event.createdAt,
          marketCapUsd: number(pair.marketCap ?? pair.fdv),
          dexId: String(pair.dexId ?? "pumpfun"),
          source: "dexscreener",
          observedAt: new Date().toISOString(),
          holders: null,
          top10Pct: null,
          mintAuthority: null,
          freezeAuthority: null,
        };
      } catch {
        return null;
      }
    }),
  );
  const hydrated = results.filter((item): item is MarketToken => item !== null);
  const hydratedAddresses = new Set(hydrated.map((item) => item.address));
  const pending = events
    .filter((event) => !hydratedAddresses.has(event.mint))
    .map(
      (event): MarketToken => ({
        address: event.mint,
        symbol: event.symbol,
        name: event.name,
        priceUsd: null,
        liquidityUsd: 0,
        volume24hUsd: 0,
        volume1hUsd: 0,
        change1hPct: 0,
        change24hPct: 0,
        buys1h: 0,
        sells1h: 0,
        pairUrl: `https://dexscreener.com/solana/${event.mint}`,
        pairAddress: event.mint,
        pairCreatedAt: event.createdAt,
        marketCapUsd: 0,
        dexId: "pumpportal-pending",
        source: "dexscreener",
        observedAt: new Date().toISOString(),
        holders: null,
        top10Pct: null,
        mintAuthority: null,
        freezeAuthority: null,
      }),
    );
  return [...hydrated, ...pending];
}

export function useDirectPumpPortal() {
  const [events, setEvents] = useState<DirectPumpToken[]>([]);
  const [tokens, setTokens] = useState<MarketToken[]>([]);
  const [connected, setConnected] = useState(false);
  const [configured, setConfigured] = useState(false);
  const [error, setError] = useState("");
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let previous = "";
    const timer = setInterval(() => {
      void loadPumpPortalKey().then((value) => {
        if (value !== previous) {
          previous = value;
          setReload((current) => current + 1);
        }
      });
    }, 2_000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    let socket: WebSocket | null = null;
    let cancelled = false;
    void loadPumpPortalKey().then((apiKey) => {
      if (cancelled) return;
      setConfigured(Boolean(apiKey));
      if (!apiKey) {
        setError("API key is not saved on this phone.");
        return;
      }
      if (typeof WebSocket === "undefined") {
        setError("WebSocket is not available on this device.");
        return;
      }
      socket = new WebSocket(
        `wss://pumpportal.fun/api/data?api-key=${encodeURIComponent(apiKey)}`,
      );
      socket.onopen = () => {
        setConnected(true);
        setError("");
        socket?.send(JSON.stringify({ method: "subscribeNewToken" }));
      };
      socket.onmessage = (message) => {
        try {
          const item = JSON.parse(String(message.data));
          if (item.errors || item.error) {
            setError(String(item.errors ?? item.error));
            return;
          }
          if (!item.mint || (item.txType && item.txType !== "create")) return;
          setEvents((current) =>
            [
              {
                mint: String(item.mint),
                symbol: String(item.symbol ?? "UNKNOWN"),
                name: String(item.name ?? "Unnamed token"),
                uri: String(item.uri ?? ""),
                creator: String(item.traderPublicKey ?? ""),
                marketCapSol:
                  typeof item.marketCapSol === "number"
                    ? item.marketCapSol
                    : null,
                initialBuy:
                  typeof item.initialBuy === "number" ? item.initialBuy : null,
                createdAt: new Date().toISOString(),
              },
              ...current,
            ].slice(0, 500),
          );
        } catch {
          // Ignore malformed provider messages.
        }
      };
      socket.onerror = () => {
        setConnected(false);
        setError("PumpPortal WebSocket connection failed.");
      };
      socket.onclose = () => {
        setConnected(false);
        setError("PumpPortal WebSocket closed. Check the key and network.");
      };
    });
    return () => {
      cancelled = true;
      socket?.close();
      setConnected(false);
    };
  }, [reload]);

  useEffect(() => {
    if (!events.length) return;
    let cancelled = false;
    const refresh = () => {
      void hydrateEvents(events).then((items) => {
        if (!cancelled) setTokens(items);
      });
    };
    refresh();
    const timer = setInterval(refresh, 15_000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [events]);

  return {
    events,
    tokens,
    connected,
    configured,
    error,
    reload: () => setReload((value) => value + 1),
  };
}
