import * as SecureStore from "expo-secure-store";

const KEY = "memepulse.pumpportal.api-key";

export async function loadPumpPortalKey() {
  return (await SecureStore.getItemAsync(KEY)) ?? "";
}

export async function savePumpPortalKey(value: string) {
  const normalized = value.trim();
  if (normalized) await SecureStore.setItemAsync(KEY, normalized);
  else await SecureStore.deleteItemAsync(KEY);
}
