import AsyncStorage from "@react-native-async-storage/async-storage";
import {
  DEFAULT_ALGORITHM_SETTINGS,
  type AlgorithmSettings,
  type FilterMode,
} from "./token-rules";
const KEY = "memepulse.preferences.v1";
export type Preferences = {
  alertMomentum: boolean;
  alertLiquidity: boolean;
  alertRisk: boolean;
  compactCards: boolean;
  walletProvider?: "phantom" | "solflare";
  walletAddress?: string;
  demoMode?: boolean;
  soundAlerts?: boolean;
  filterMode: FilterMode;
  algorithm: AlgorithmSettings;
};
export const DEFAULT_PREFERENCES: Preferences = {
  alertMomentum: true,
  alertLiquidity: true,
  alertRisk: false,
  compactCards: false,
  walletProvider: "phantom",
  walletAddress: "",
  demoMode: true,
  soundAlerts: true,
  filterMode: "strict",
  algorithm: DEFAULT_ALGORITHM_SETTINGS,
};
export async function loadPreferences(): Promise<Preferences> {
  try {
    const raw = await AsyncStorage.getItem(KEY);
    if (!raw) return DEFAULT_PREFERENCES;
    const parsed = JSON.parse(raw);
    return {
      ...DEFAULT_PREFERENCES,
      ...parsed,
      algorithm: { ...DEFAULT_ALGORITHM_SETTINGS, ...(parsed.algorithm ?? {}) },
    };
  } catch {
    return DEFAULT_PREFERENCES;
  }
}
export async function savePreferences(value: Preferences) {
  await AsyncStorage.setItem(KEY, JSON.stringify(value));
}
