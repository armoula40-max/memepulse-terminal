import { useEffect, useState } from "react";
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

export function useDirectPumpPortal() {
  const [events, setEvents] = useState<DirectPumpToken[]>([]);
  const [connected, setConnected] = useState(false);
  const [configured, setConfigured] = useState(false);
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
      if (!apiKey || typeof WebSocket === "undefined") return;
      socket = new WebSocket(
        `wss://pumpportal.fun/api/data?api-key=${encodeURIComponent(apiKey)}`,
      );
      socket.onopen = () => {
        setConnected(true);
        socket?.send(JSON.stringify({ method: "subscribeNewToken" }));
      };
      socket.onmessage = (message) => {
        try {
          const item = JSON.parse(String(message.data));
          if (!item.mint || item.txType !== "create") return;
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
      socket.onerror = () => setConnected(false);
      socket.onclose = () => setConnected(false);
    });
    return () => {
      cancelled = true;
      socket?.close();
      setConnected(false);
    };
  }, [reload]);

  return {
    events,
    connected,
    configured,
    reload: () => setReload((value) => value + 1),
  };
}
