import { Linking, Platform } from "react-native";

export type TokenLinkTarget = "pump.fun" | "phantom" | "solflare" | "dexscreener";

export function tokenWebUrl(address: string, target: TokenLinkTarget = "dexscreener"): string {
  if (target === "phantom") return `https://phantom.app/ul/browse/${encodeURIComponent(`https://pump.fun/coin/${address}`)}?ref=memepulse-terminal`;
  if (target === "solflare") return `https://solflare.com/portfolio/${encodeURIComponent(address)}`;
  if (target === "dexscreener") return `https://dexscreener.com/solana/${encodeURIComponent(address)}`;
  return `https://pump.fun/coin/${encodeURIComponent(address)}`;
}

export async function openTokenLink(address: string, target: TokenLinkTarget = "dexscreener"): Promise<boolean> {
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
