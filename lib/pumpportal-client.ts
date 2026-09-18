import { useEffect, useState } from "react";

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

  useEffect(() => {
    const apiKey = process.env.EXPO_PUBLIC_PUMPPORTAL_API_KEY;
    setConfigured(Boolean(apiKey));
    if (!apiKey || typeof WebSocket === "undefined") return;

    const socket = new WebSocket(
      `wss://pumpportal.fun/api/data?api-key=${encodeURIComponent(apiKey)}`,
    );
    socket.onopen = () => {
      setConnected(true);
      socket.send(JSON.stringify({ method: "subscribeNewToken" }));
    };
    socket.onmessage = (message) => {
      try {
        const item = JSON.parse(String(message.data));
        if (!item.mint || item.txType !== "create") return;
        const event: DirectPumpToken = {
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
        };
        setEvents((current) => [event, ...current].slice(0, 500));
      } catch {
        // Ignore malformed provider messages.
      }
    };
    socket.onerror = () => setConnected(false);
    socket.onclose = () => setConnected(false);
    return () => socket.close();
  }, []);

  return { events, connected, configured };
}
