import { useEffect, useState } from "react";
import {
  Linking,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from "react-native";
import { MaterialIcons } from "@expo/vector-icons";
import { ScreenContainer } from "@/components/screen-container";
import {
  DEFAULT_PREFERENCES,
  loadPreferences,
  savePreferences,
  type Preferences,
} from "@/lib/preferences";
import {
  type AlgorithmSettings,
  type FilterMode,
  settingsForMode,
  VCS_ALGORITHM_SETTINGS,
} from "@/lib/token-rules";
import { loadPumpPortalKey, savePumpPortalKey } from "@/lib/pumpportal-key";
import {
  startPumpPortalBackground,
  stopPumpPortalBackground,
} from "@/lib/pumpportal-background";
const C = {
  bg: "#07100F",
  surface: "#0D1B18",
  border: "#1C3A33",
  text: "#F2F8F5",
  muted: "#88A69A",
  mint: "#76F2B6",
  mintSoft: "#143E30",
  amber: "#F8C36A",
  red: "#FF7B80",
  blue: "#8BB8FF",
};
const algorithmFields: Array<{
  key: keyof AlgorithmSettings;
  label: string;
  suffix: string;
}> = [
  { key: "minLiquidityUsd", label: "Minimum liquidity", suffix: "$" },
  {
    key: "minHolders10m",
    label: "Minimum holders at 10 min",
    suffix: "holders",
  },
  {
    key: "minHolders30m",
    label: "Minimum holders at 30 min",
    suffix: "holders",
  },
  { key: "maxTop10PctNew", label: "Maximum top 10 at launch", suffix: "%" },
  { key: "maxTop10Pct30m", label: "Maximum top 10 at 30 min", suffix: "%" },
  { key: "minMarketCapUsd", label: "Minimum market cap", suffix: "$" },
  { key: "minVolume1hUsd", label: "Minimum 1h volume", suffix: "$" },
  { key: "minBuyRatioPct", label: "Minimum buy ratio", suffix: "%" },
  { key: "minMomentumPct", label: "Minimum 1h momentum", suffix: "%" },
];
export default function SettingsScreen() {
  const [paper, setPaper] = useState(true);
  const [biometric, setBiometric] = useState(false);
  const [prefs, setPrefs] = useState<Preferences>(DEFAULT_PREFERENCES);
  const [pumpPortalKey, setPumpPortalKey] = useState("");
  const [pumpPortalSaved, setPumpPortalSaved] = useState(false);
  useEffect(() => {
    loadPreferences().then(setPrefs);
    loadPumpPortalKey().then((value) => setPumpPortalKey(value));
  }, []);
  const update = (next: Preferences) => {
    setPrefs(next);
    void savePreferences(next);
  };
  const updateAlgorithm = (key: keyof AlgorithmSettings, raw: string) => {
    const numeric = Number(raw.replace(/[^0-9.]/g, ""));
    if (!Number.isFinite(numeric)) return;
    update({ ...prefs, algorithm: { ...prefs.algorithm, [key]: numeric } });
  };
  const setFilterMode = (filterMode: FilterMode) => {
    update({ ...prefs, filterMode, algorithm: settingsForMode(filterMode) });
  };
  return (
    <ScreenContainer className="px-5" containerClassName="bg-background">
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.kicker}>CONTROL PLANE</Text>
        <Text style={styles.title}>Settings</Text>
        <Text style={styles.subtitle}>
          Tune the terminal without exposing sensitive keys.
        </Text>
        <View style={styles.security}>
          <View style={styles.securityIcon}>
            <MaterialIcons name="security" size={22} color={C.mint} />
          </View>
          <View style={{ flex: 1 }}>
            <Text style={styles.securityTitle}>Security posture: guarded</Text>
            <Text style={styles.securityText}>
              Private keys are not requested, stored or transmitted.
            </Text>
          </View>
          <MaterialIcons name="check-circle" size={18} color={C.mint} />
        </View>
        <Text style={styles.section}>DIRECT PUMPPORTAL CONNECTION</Text>
        <View style={styles.walletCard}>
          <View style={styles.walletHeader}>
            <MaterialIcons name="sync" size={20} color={C.mint} />
            <View style={{ flex: 1 }}>
              <Text style={styles.settingTitle}>PumpPortal API key</Text>
              <Text style={styles.settingDescription}>
                Stored only on this phone. The Scanner connects directly without
                the local server.
              </Text>
            </View>
          </View>
          <TextInput
            value={pumpPortalKey}
            onChangeText={(value) => {
              setPumpPortalKey(value);
              setPumpPortalSaved(false);
            }}
            placeholder="Paste PumpPortal API key"
            placeholderTextColor={C.muted}
            autoCapitalize="none"
            autoCorrect={false}
            secureTextEntry
            style={styles.walletInput}
          />
          <View style={styles.walletActions}>
            <Pressable
              onPress={() => {
                void savePumpPortalKey(pumpPortalKey).then(() => {
                  if (pumpPortalKey.trim())
                    startPumpPortalBackground(pumpPortalKey);
                  else stopPumpPortalBackground();
                  setPumpPortalSaved(true);
                });
              }}
              style={styles.walletButton}
            >
              <Text style={styles.walletButtonText}>SAVE ON THIS PHONE</Text>
            </Pressable>
            <Text style={styles.walletStatus}>
              {pumpPortalSaved
                ? "KEY SAVED LOCALLY"
                : pumpPortalKey
                  ? "UNSAVED KEY"
                  : "NOT CONFIGURED"}
            </Text>
          </View>
          <Text style={styles.keyWarning}>
            This key is not sent to MemePulse servers. PumpPortal can still
            identify usage from a direct app connection.
          </Text>
        </View>
        <Text style={styles.section}>NEW-TOKEN ALGORITHM</Text>
        <View style={styles.algorithmCard}>
          <View style={styles.algorithmHeader}>
            <View style={{ flex: 1 }}>
              <Text style={styles.settingTitle}>Screening rules</Text>
              <Text style={styles.settingDescription}>
                Saved locally and applied to the Scanner. Unknown on-chain
                fields stay pending.
              </Text>
            </View>
            <Switch
              value={prefs.algorithm.enabled}
              onValueChange={(enabled) =>
                update({ ...prefs, algorithm: { ...prefs.algorithm, enabled } })
              }
              trackColor={{ false: "#1A3029", true: "#2C7459" }}
              thumbColor={prefs.algorithm.enabled ? C.mint : C.muted}
            />
          </View>
          <Pressable
            onPress={() =>
              update({ ...prefs, algorithm: { ...VCS_ALGORITHM_SETTINGS } })
            }
            style={styles.vcsButton}
          >
            <MaterialIcons name="bolt" size={16} color={C.bg} />
            <View style={{ flex: 1 }}>
              <Text style={styles.vcsButtonTitle}>LOAD VCS PRESET</Text>
              <Text style={styles.vcsButtonText}>
                Viral Coin Sniping · early momentum + quality filters
              </Text>
            </View>
            <MaterialIcons name="chevron-right" size={18} color={C.bg} />
          </Pressable>
          <Text style={styles.modeLabel}>FILTER MODE</Text>
          <View style={styles.modeRow}>
            {(
              [
                ["strict", "Strict", "Confirmed only"],
                ["early", "Early Sniper", "Fast candidates"],
                ["balanced", "Balanced", "Middle ground"],
              ] as Array<[FilterMode, string, string]>
            ).map(([mode, title, detail]) => (
              <Pressable
                key={mode}
                onPress={() => setFilterMode(mode)}
                style={[
                  styles.modeButton,
                  prefs.filterMode === mode && styles.modeButtonActive,
                ]}
              >
                <Text
                  style={[
                    styles.modeButtonTitle,
                    prefs.filterMode === mode && styles.modeButtonTitleActive,
                  ]}
                >
                  {title}
                </Text>
                <Text
                  style={[
                    styles.modeButtonText,
                    prefs.filterMode === mode && styles.modeButtonTextActive,
                  ]}
                >
                  {detail}
                </Text>
              </Pressable>
            ))}
          </View>
          <Text style={styles.modeDescription}>
            {prefs.filterMode === "strict"
              ? "Only fully verified candidates pass the Scanner filter."
              : prefs.filterMode === "early"
                ? "Lower early thresholds surface candidates sooner; not a confirmed buy signal."
                : "A middle setting for earlier discovery with stronger confirmation."}
          </Text>
          {algorithmFields.map((field) => (
            <View style={styles.ruleRow} key={field.key}>
              <Text style={styles.ruleLabel}>{field.label}</Text>
              <View style={styles.ruleInputWrap}>
                <Text style={styles.ruleSuffix}>{field.suffix}</Text>
                <TextInput
                  value={String(prefs.algorithm[field.key])}
                  onChangeText={(value) => updateAlgorithm(field.key, value)}
                  keyboardType="decimal-pad"
                  selectTextOnFocus
                  style={styles.ruleInput}
                />
              </View>
            </View>
          ))}
          <ToggleRow
            label="Require mint authority disabled"
            value={prefs.algorithm.requireMintDisabled}
            onChange={(value) =>
              update({
                ...prefs,
                algorithm: { ...prefs.algorithm, requireMintDisabled: value },
              })
            }
          />
          <ToggleRow
            label="Require freeze authority disabled"
            value={prefs.algorithm.requireFreezeDisabled}
            onChange={(value) =>
              update({
                ...prefs,
                algorithm: { ...prefs.algorithm, requireFreezeDisabled: value },
              })
            }
          />
        </View>
        <Text style={styles.section}>WALLET CONNECTION</Text>
        <View style={styles.walletCard}>
          <View style={styles.walletHeader}>
            <MaterialIcons
              name="account-balance-wallet"
              size={20}
              color={C.mint}
            />
            <View style={{ flex: 1 }}>
              <Text style={styles.settingTitle}>Watch-only wallet</Text>
              <Text style={styles.settingDescription}>
                Stores only a public address for portfolio viewing. No private
                keys or signing.
              </Text>
            </View>
          </View>
          <View style={styles.walletProviders}>
            <Pressable
              onPress={() => update({ ...prefs, walletProvider: "phantom" })}
              style={[
                styles.provider,
                prefs.walletProvider === "phantom" && styles.providerActive,
              ]}
            >
              <Text
                style={[
                  styles.providerText,
                  prefs.walletProvider === "phantom" &&
                    styles.providerTextActive,
                ]}
              >
                Phantom
              </Text>
            </Pressable>
            <Pressable
              onPress={() => update({ ...prefs, walletProvider: "solflare" })}
              style={[
                styles.provider,
                prefs.walletProvider === "solflare" && styles.providerActive,
              ]}
            >
              <Text
                style={[
                  styles.providerText,
                  prefs.walletProvider === "solflare" &&
                    styles.providerTextActive,
                ]}
              >
                Solflare
              </Text>
            </Pressable>
          </View>
          <TextInput
            value={prefs.walletAddress ?? ""}
            onChangeText={(walletAddress) =>
              update({ ...prefs, walletAddress })
            }
            placeholder="Paste public Solana address"
            placeholderTextColor={C.muted}
            autoCapitalize="none"
            autoCorrect={false}
            style={styles.walletInput}
          />
          <View style={styles.walletActions}>
            <Pressable
              onPress={() => {
                const appUrl =
                  prefs.walletProvider === "solflare"
                    ? "https://solflare.com"
                    : "https://phantom.app";
                void Linking.openURL(appUrl);
              }}
              style={styles.walletButton}
            >
              <Text style={styles.walletButtonText}>OPEN WALLET APP</Text>
            </Pressable>
            <Text style={styles.walletStatus}>
              {prefs.walletAddress
                ? "ADDRESS SAVED LOCALLY"
                : "NO ADDRESS SAVED"}
            </Text>
          </View>
        </View>
        <Text style={styles.section}>TRADING MODE</Text>
        <Setting
          icon="science"
          title="Paper trading only"
          description="Simulated orders and ledger; no live execution"
          value={paper}
          onChange={setPaper}
          tint={C.mint}
          locked
        />
        <Setting
          icon="fingerprint"
          title="Biometric gate"
          description="Require device authentication before opening the app"
          value={biometric}
          onChange={setBiometric}
          tint={C.blue}
        />
        <Text style={styles.section}>ALERT PREFERENCES</Text>
        <Setting
          icon="trending-up"
          title="Momentum alerts"
          description="Enable local rule evaluation"
          value={prefs.alertMomentum}
          onChange={(value) => update({ ...prefs, alertMomentum: value })}
          tint={C.mint}
        />
        <Setting
          icon="water-drop"
          title="Liquidity alerts"
          description="Enable local rule evaluation"
          value={prefs.alertLiquidity}
          onChange={(value) => update({ ...prefs, alertLiquidity: value })}
          tint={C.blue}
        />
        <Setting
          icon="warning"
          title="Risk alerts"
          description="Enable local rule evaluation"
          value={prefs.alertRisk}
          onChange={(value) => update({ ...prefs, alertRisk: value })}
          tint={C.amber}
        />
        <Text style={styles.section}>DISPLAY</Text>
        <Setting
          icon="view-agenda"
          title="Compact cards"
          description="Show more candidates per screen"
          value={prefs.compactCards}
          onChange={(value) => update({ ...prefs, compactCards: value })}
          tint={C.amber}
        />
        <Text style={styles.section}>DATA & PRODUCT</Text>
        <LinkRow
          icon="storage"
          title="Data sources"
          detail="Public market data · timestamps required"
        />
        <LinkRow
          icon="tune"
          title="Risk profile"
          detail="Guarded · 10% max simulated position"
        />
        <LinkRow
          icon="help-outline"
          title="About MemePulse"
          detail="Version 0.2 · research terminal"
        />
        <View style={styles.disclaimer}>
          <Text style={styles.disclaimerTitle}>Important</Text>
          <Text style={styles.disclaimerText}>
            Meme coins are highly speculative and can lose most or all of their
            value. MemePulse provides research tools and simulations, not
            financial advice or guaranteed predictions.
          </Text>
        </View>
      </ScrollView>
    </ScreenContainer>
  );
}
function ToggleRow({
  label,
  value,
  onChange,
}: {
  label: string;
  value: boolean;
  onChange: (value: boolean) => void;
}) {
  return (
    <View style={styles.toggleRow}>
      <Text style={styles.settingTitle}>{label}</Text>
      <Switch
        value={value}
        onValueChange={onChange}
        trackColor={{ false: "#1A3029", true: "#2C7459" }}
        thumbColor={value ? C.mint : C.muted}
      />
    </View>
  );
}
function Setting({
  icon,
  title,
  description,
  value,
  onChange,
  tint,
  locked,
}: {
  icon: keyof typeof MaterialIcons.glyphMap;
  title: string;
  description: string;
  value: boolean;
  onChange: (value: boolean) => void;
  tint: string;
  locked?: boolean;
}) {
  return (
    <View style={styles.setting}>
      <View style={[styles.settingIcon, { backgroundColor: `${tint}18` }]}>
        <MaterialIcons name={icon} size={18} color={tint} />
      </View>
      <View style={styles.settingCopy}>
        <Text style={styles.settingTitle}>
          {title}
          {locked ? "  ·  LOCKED" : ""}
        </Text>
        <Text style={styles.settingDescription}>{description}</Text>
      </View>
      <Switch
        value={value}
        onValueChange={onChange}
        disabled={locked}
        trackColor={{ false: "#1A3029", true: "#2C7459" }}
        thumbColor={value ? C.mint : C.muted}
      />
    </View>
  );
}
function LinkRow({
  icon,
  title,
  detail,
}: {
  icon: keyof typeof MaterialIcons.glyphMap;
  title: string;
  detail: string;
}) {
  return (
    <Pressable
      style={({ pressed }) => [styles.linkRow, pressed && { opacity: 0.7 }]}
    >
      <View style={styles.linkIcon}>
        <MaterialIcons name={icon} size={18} color={C.muted} />
      </View>
      <View style={{ flex: 1 }}>
        <Text style={styles.settingTitle}>{title}</Text>
        <Text style={styles.settingDescription}>{detail}</Text>
      </View>
      <MaterialIcons name="chevron-right" size={18} color={C.muted} />
    </Pressable>
  );
}
const styles = StyleSheet.create({
  content: { paddingTop: 16, paddingBottom: 32, gap: 12 },
  algorithmCard: {
    backgroundColor: C.surface,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 15,
    padding: 12,
    gap: 9,
  },
  algorithmHeader: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
    paddingBottom: 5,
  },
  modeLabel: {
    color: C.muted,
    fontSize: 9,
    fontWeight: "900",
    letterSpacing: 1,
    marginTop: 3,
  },
  modeRow: { flexDirection: "row", gap: 6 },
  modeButton: {
    flex: 1,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 9,
    padding: 8,
    minHeight: 56,
  },
  modeButtonActive: { backgroundColor: C.mint, borderColor: C.mint },
  modeButtonTitle: { color: C.text, fontSize: 9, fontWeight: "900" },
  modeButtonTitleActive: { color: C.bg },
  modeButtonText: { color: C.muted, fontSize: 8, marginTop: 4 },
  modeButtonTextActive: { color: "#184C38" },
  modeDescription: { color: C.muted, fontSize: 9, lineHeight: 13 },
  vcsButton: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    backgroundColor: C.mint,
    borderRadius: 10,
    padding: 10,
  },
  vcsButtonTitle: { color: C.bg, fontSize: 10, fontWeight: "900" },
  vcsButtonText: { color: "#184C38", fontSize: 9, marginTop: 2 },
  ruleRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    borderTopWidth: 1,
    borderTopColor: C.border,
    paddingTop: 9,
  },
  ruleLabel: { color: C.muted, fontSize: 10, flex: 1 },
  ruleInputWrap: {
    flexDirection: "row",
    alignItems: "center",
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 8,
    minWidth: 100,
    height: 34,
    paddingHorizontal: 8,
  },
  ruleSuffix: { color: C.muted, fontSize: 9, marginRight: 4 },
  ruleInput: {
    color: C.text,
    fontSize: 11,
    fontWeight: "800",
    flex: 1,
    textAlign: "right",
  },
  toggleRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    borderTopWidth: 1,
    borderTopColor: C.border,
    paddingTop: 7,
  },
  walletCard: {
    backgroundColor: C.surface,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 15,
    padding: 12,
    gap: 10,
  },
  walletHeader: { flexDirection: "row", alignItems: "center", gap: 10 },
  walletProviders: { flexDirection: "row", gap: 8 },
  provider: {
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 9,
    paddingHorizontal: 12,
    paddingVertical: 7,
  },
  providerActive: { backgroundColor: C.mint, borderColor: C.mint },
  providerText: { color: C.muted, fontSize: 10, fontWeight: "800" },
  providerTextActive: { color: C.bg },
  walletInput: {
    color: C.text,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 9,
    paddingHorizontal: 10,
    height: 40,
    fontSize: 11,
  },
  walletActions: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: 8,
  },
  walletButton: {
    backgroundColor: C.mint,
    borderRadius: 9,
    paddingHorizontal: 10,
    paddingVertical: 8,
  },
  walletButtonText: { color: C.bg, fontSize: 9, fontWeight: "900" },
  walletStatus: {
    color: C.mint,
    fontSize: 8,
    fontWeight: "800",
    flex: 1,
    textAlign: "right",
  },
  keyWarning: { color: C.amber, fontSize: 9, lineHeight: 14 },
  kicker: {
    color: C.mint,
    fontSize: 10,
    fontWeight: "800",
    letterSpacing: 1.3,
  },
  title: { color: C.text, fontSize: 28, fontWeight: "800", marginTop: 3 },
  subtitle: { color: C.muted, fontSize: 11, marginBottom: 7 },
  security: {
    flexDirection: "row",
    alignItems: "center",
    gap: 11,
    backgroundColor: "#0B251D",
    borderWidth: 1,
    borderColor: "#164D3B",
    borderRadius: 16,
    padding: 14,
  },
  securityIcon: {
    width: 42,
    height: 42,
    borderRadius: 14,
    backgroundColor: C.mintSoft,
    alignItems: "center",
    justifyContent: "center",
  },
  securityTitle: { color: C.text, fontSize: 12, fontWeight: "800" },
  securityText: { color: C.muted, fontSize: 10, marginTop: 4 },
  section: {
    color: C.muted,
    fontSize: 9,
    fontWeight: "900",
    letterSpacing: 1.3,
    marginTop: 9,
  },
  setting: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
    backgroundColor: C.surface,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 15,
    padding: 12,
  },
  settingIcon: {
    width: 35,
    height: 35,
    borderRadius: 11,
    alignItems: "center",
    justifyContent: "center",
  },
  settingCopy: { flex: 1 },
  settingTitle: { color: C.text, fontSize: 12, fontWeight: "800" },
  settingDescription: {
    color: C.muted,
    fontSize: 10,
    lineHeight: 14,
    marginTop: 3,
  },
  linkRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
    backgroundColor: C.surface,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 15,
    padding: 12,
  },
  linkIcon: {
    width: 35,
    height: 35,
    borderRadius: 11,
    backgroundColor: "#142521",
    alignItems: "center",
    justifyContent: "center",
  },
  disclaimer: {
    backgroundColor: "#211B0E",
    borderWidth: 1,
    borderColor: "#5A421D",
    borderRadius: 15,
    padding: 14,
    marginTop: 6,
  },
  disclaimerTitle: { color: C.amber, fontSize: 11, fontWeight: "900" },
  disclaimerText: {
    color: "#C7AE83",
    fontSize: 10,
    lineHeight: 16,
    marginTop: 6,
  },
});
