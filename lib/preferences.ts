import AsyncStorage from "@react-native-async-storage/async-storage";
const KEY = "memepulse.preferences.v1";
export type Preferences = { alertMomentum: boolean; alertLiquidity: boolean; alertRisk: boolean; compactCards: boolean; walletProvider?: "phantom" | "solflare"; walletAddress?: string; demoMode?: boolean; soundAlerts?: boolean };
export const DEFAULT_PREFERENCES: Preferences = { alertMomentum: true, alertLiquidity: true, alertRisk: false, compactCards: false, walletProvider: "phantom", walletAddress: "", demoMode: true, soundAlerts: true };
export async function loadPreferences(): Promise<Preferences> { try { const raw = await AsyncStorage.getItem(KEY); return raw ? { ...DEFAULT_PREFERENCES, ...JSON.parse(raw) } : DEFAULT_PREFERENCES; } catch { return DEFAULT_PREFERENCES; } }
export async function savePreferences(value: Preferences) { await AsyncStorage.setItem(KEY, JSON.stringify(value)); }
