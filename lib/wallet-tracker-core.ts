const ADDRESS_RE = /^[1-9A-HJ-NP-Za-km-z]{32,44}$/;

export type WalletActivityKind = "BUY_CONFIRMED" | "BUY_PENDING" | "SELL_CONFIRMED" | "SELL_PENDING" | "TRANSFER_IN" | "TRANSFER_OUT" | "LIQUIDITY_OPERATION" | "OTHER" | "UNKNOWN";

export function isSolanaAddress(value: string): boolean { return ADDRESS_RE.test(value.trim()); }
export function extractPublicSolanaAddress(input: string): string | null {
  const trimmed = input.trim();
  if (isSolanaAddress(trimmed)) return trimmed;
  try {
    const url = new URL(trimmed);
    if (!/(^|\.)photon-sol\.tinyastro\.io$/i.test(url.hostname)) return null;
    return url.pathname.split("/").filter(Boolean).reverse().find((part) => isSolanaAddress(part)) ?? null;
  } catch { return null; }
}
export function classifyTokenMovement(input: { tokenDelta: number; solDelta: number | null; confirmed: boolean; hasSwapInstruction?: boolean }): WalletActivityKind {
  if (!Number.isFinite(input.tokenDelta)) return "UNKNOWN";
  if (input.tokenDelta > 0) {
    if (input.solDelta !== null && input.solDelta < -0.000001) return input.confirmed ? "BUY_CONFIRMED" : "BUY_PENDING";
    return "TRANSFER_IN";
  }
  if (input.tokenDelta < 0) {
    if (input.solDelta !== null && input.solDelta > 0.000001) return input.confirmed ? "SELL_CONFIRMED" : "SELL_PENDING";
    return "TRANSFER_OUT";
  }
  return input.hasSwapInstruction ? "LIQUIDITY_OPERATION" : "OTHER";
}
