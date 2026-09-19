import { NativeModules, Platform } from "react-native";

const service = NativeModules.PumpPortalService as
  | { start: (apiKey: string) => void; stop: () => void }
  | undefined;

export function startPumpPortalBackground(apiKey: string) {
  if (Platform.OS === "android" && apiKey.trim() && service)
    service.start(apiKey.trim());
}

export function stopPumpPortalBackground() {
  if (Platform.OS === "android" && service) service.stop();
}

export const pumpPortalBackgroundAvailable =
  Platform.OS === "android" && Boolean(service);
