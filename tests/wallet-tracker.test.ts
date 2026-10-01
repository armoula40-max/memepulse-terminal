import { describe, expect, it } from "vitest";
import { classifyTokenMovement, extractPublicSolanaAddress } from "../lib/wallet-tracker-core";

const address = "7YWHMfk9bB7qvJw4QfN7K3Q9pK8aL6sR2tV5xC1mD8e";

describe("wallet tracker safety helpers", () => {
  it("accepts a public address and extracts it from a Photon portfolio URL", () => {
    expect(extractPublicSolanaAddress(address)).toBe(address);
    expect(extractPublicSolanaAddress(`https://photon-sol.tinyastro.io/en/p/${address}`)).toBe(address);
    expect(extractPublicSolanaAddress("not-a-wallet")).toBeNull();
    expect(extractPublicSolanaAddress("https://example.com/" + address)).toBeNull();
  });

  it("classifies a confirmed buy only when tokens increase and SOL decreases", () => {
    expect(classifyTokenMovement({ tokenDelta: 12, solDelta: -0.4, confirmed: true })).toBe("BUY_CONFIRMED");
    expect(classifyTokenMovement({ tokenDelta: 12, solDelta: 0, confirmed: true })).toBe("TRANSFER_IN");
    expect(classifyTokenMovement({ tokenDelta: -12, solDelta: 0.4, confirmed: true })).toBe("SELL_CONFIRMED");
    expect(classifyTokenMovement({ tokenDelta: 12, solDelta: -0.4, confirmed: false })).toBe("BUY_PENDING");
  });
});
