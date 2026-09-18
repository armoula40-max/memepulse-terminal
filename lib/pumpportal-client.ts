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

  return {
    events,
    connected,
    configured,
    error,
    reload: () => setReload((value) => value + 1),
  };
}
