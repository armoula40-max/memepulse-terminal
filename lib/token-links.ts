import { Linking, Platform } from "react-native";

export type TokenLinkTarget = "pump.fun" | "phantom" | "solflare" | "dexscreener" | "photon";

export function tokenWebUrl(address: string, target: TokenLinkTarget = "photon"): string {
  if (target === "phantom") return `https://phantom.app/ul/browse/${encodeURIComponent(`https://pump.fun/coin/${address}`)}?ref=memepulse-terminal`;
  if (target === "solflare") return `https://solflare.com/portfolio/${encodeURIComponent(address)}`;
  if (target === "dexscreener") return `https://dexscreener.com/solana/${encodeURIComponent(address)}`;
  if (target === "photon") return `https://photon-sol.tinyastro.io/en/lp/${encodeURIComponent(address)}`;
  return `https://pump.fun/coin/${encodeURIComponent(address)}`;
}

export async function openTokenLink(address: string, target: TokenLinkTarget = "photon"): Promise<boolean> {
  const url = tokenWebUrl(address, target);
  try {
    if (await Linking.canOpenURL(url)) {
      await Linking.openURL(url);
      return true;
    }
    if (Platform.OS !== "web" && target !== "dexscreener") {
      await Linking.openURL(tokenWebUrl(address, "dexscreener"));
      return true;
    }
  } catch {
    return false;
  }
  return false;
}

export function formatTokenAge(pairCreatedAt: string | null): string {
  if (!pairCreatedAt) return "AGE UNKNOWN";
  const ageMs = Date.now() - new Date(pairCreatedAt).getTime();
  if (!Number.isFinite(ageMs) || ageMs < 0) return "AGE UNKNOWN";
  const created = new Date(pairCreatedAt);
  const date = created.toLocaleString();
  const minutes = Math.floor(ageMs / 60_000);
  const age = minutes < 60 ? `${minutes}m old` : minutes < 1_440 ? `${Math.floor(minutes / 60)}h old` : `${Math.floor(minutes / 1_440)}d old`;
  return `${age} · ${date}`;
}
