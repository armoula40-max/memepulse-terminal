import * as SecureStore from "expo-secure-store";

const PUMPPORTAL_KEY = "memepulse.pumpportal.api-key";
export async function loadPumpPortalKey() { return SecureStore.getItemAsync(PUMPPORTAL_KEY); }
export async function savePumpPortalKey(value: string) { const key = value.trim(); if (key) await SecureStore.setItemAsync(PUMPPORTAL_KEY, key); else await SecureStore.deleteItemAsync(PUMPPORTAL_KEY); }
