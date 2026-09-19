import { NativeModules, Platform } from "react-native";

const service = NativeModules.PumpPortalService as
  | {
      start: (apiKey: string) => void;
      stop: () => void;
      configureAutoBuy: (
        enabled: boolean,
        amount: number,
        mode: string,
      ) => void;
      getAutoBuyOrders: () => Promise<string>;
    }
  | undefined;

export function startPumpPortalBackground(apiKey: string) {
  if (Platform.OS === "android" && apiKey.trim() && service)
    service.start(apiKey.trim());
}

export function stopPumpPortalBackground() {
  if (Platform.OS === "android" && service) service.stop();
}

export function configurePaperAutoBuy(
  enabled: boolean,
  amount: number,
  mode: string,
) {
  if (Platform.OS === "android" && service?.configureAutoBuy)
    service.configureAutoBuy(enabled, amount, mode);
}

export async function loadPaperAutoBuyOrders(): Promise<unknown[]> {
  if (Platform.OS !== "android" || !service?.getAutoBuyOrders) return [];
  try {
    return JSON.parse(await service.getAutoBuyOrders()) as unknown[];
  } catch {
    return [];
  }
}

export const pumpPortalBackgroundAvailable =
  Platform.OS === "android" && Boolean(service);
