import { useEffect, useMemo, useState } from "react";
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import { MaterialIcons } from "@expo/vector-icons";
import { ScreenContainer } from "@/components/screen-container";
import { trpc } from "@/lib/trpc";
import { simulateFill, type SimulatedFill } from "@/lib/simulation";

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
export default function PaperScreen() {
  const [amount, setAmount] = useState("500");
  const [fill, setFill] = useState<SimulatedFill | null>(null);
  const [side, setSide] = useState<"BUY" | "SELL">("BUY");
  const [selectedAddress, setSelectedAddress] = useState<string | null>(null);
  const [message, setMessage] = useState(
    "Ready to simulate against the live public market snapshot.",
  );
  const market = trpc.market.latest.useQuery(undefined, {
    staleTime: 5_000,
    refetchInterval: 15_000,
  });
  const tokens = market.data?.tokens ?? [];
  const selected =
    tokens.find((token) => token.address === selectedAddress) ?? tokens[0];
  useEffect(() => {
    if (!selectedAddress && tokens[0]) setSelectedAddress(tokens[0].address);
  }, [selectedAddress, tokens]);
  const runSimulation = () => {
    if (!selected) {
      setMessage("Waiting for a public market quote.");
      return;
    }
    const result = simulateFill({
      side,
      notionalUsd: Number(amount) || 0,
      priceUsd: selected.priceUsd ?? 0,
      liquidityUsd: selected.liquidityUsd,
    });
    setFill(result);
    setMessage(`SIMULATED ${side} · ${selected.symbol} · local ledger updated`);
  };
  const totalCost = useMemo(
    () =>
      fill ? fill.feeUsd + (fill.requestedUsd * fill.slippagePct) / 100 : 0,
    [fill],
  );
  return (
    <ScreenContainer className="px-5" containerClassName="bg-background">
      <ScrollView
        showsVerticalScrollIndicator={false}
        contentContainerStyle={styles.content}
      >
        <View style={styles.header}>
          <View>
            <Text style={styles.kicker}>LIVE MARKET SIMULATOR</Text>
            <Text style={styles.title}>Paper wallet</Text>
            <Text style={styles.subtitle}>
              Platform-style order ticket using live public quotes. Nothing is
              sent.
            </Text>
          </View>
          <View style={styles.badge}>
            <MaterialIcons name="science" size={15} color={C.mint} />
            <Text style={styles.badgeText}>PAPER</Text>
          </View>
        </View>
        <View style={styles.balanceCard}>
          <View>
            <Text style={styles.balanceLabel}>SIMULATED EQUITY</Text>
            <Text style={styles.balance}>$10,000.00</Text>
            <Text style={styles.balanceDelta}>
              Local ledger · no wallet or funds touched
            </Text>
          </View>
          <View style={styles.balanceIcon}>
            <MaterialIcons
              name="account-balance-wallet"
              size={24}
              color={C.mint}
            />
          </View>
        </View>
        <View style={styles.liveCard}>
          <View style={styles.liveHeader}>
            <MaterialIcons name="wifi" size={17} color={C.mint} />
            <Text style={styles.liveTitle}>LIVE QUOTE</Text>
            <Text style={styles.liveStatus}>
              {market.isFetching ? "SYNC" : "LIVE"}
            </Text>
          </View>
          {selected ? (
            <>
              <Text style={styles.selectedSymbol}>${selected.symbol}</Text>
              <Text style={styles.selectedName}>{selected.name}</Text>
              <View style={styles.quoteRow}>
                <Quote label="PRICE" value={formatPrice(selected.priceUsd)} />
                <Quote label="LIQUIDITY" value={money(selected.liquidityUsd)} />
                <Quote
                  label="1H CHANGE"
                  value={`${selected.change1hPct >= 0 ? "+" : ""}${selected.change1hPct.toFixed(1)}%`}
                  positive={selected.change1hPct >= 0}
                />
              </View>
              <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                contentContainerStyle={styles.tokenRow}
              >
                {tokens.slice(0, 20).map((token) => (
                  <Pressable
                    key={token.address}
                    onPress={() => {
                      setSelectedAddress(token.address);
                      setFill(null);
                    }}
                    style={[
                      styles.tokenChip,
                      token.address === selected.address &&
                        styles.tokenChipActive,
                    ]}
                  >
                    <Text
                      style={[
                        styles.tokenChipText,
                        token.address === selected.address &&
                          styles.tokenChipTextActive,
                      ]}
                    >
                      ${token.symbol}
                    </Text>
                  </Pressable>
                ))}
              </ScrollView>
            </>
          ) : (
            <Text style={styles.emptyText}>
              Waiting for the live public market snapshot...
            </Text>
          )}
        </View>
        <View style={styles.ticket}>
          <View style={styles.ticketHeader}>
            <Text style={styles.sectionTitle}>Order ticket</Text>
            <Text style={styles.ticketMode}>SIMULATED ONLY</Text>
          </View>
          <Text style={styles.fieldLabel}>NOTIONAL USD</Text>
          <TextInput
            value={amount}
            onChangeText={setAmount}
            keyboardType="decimal-pad"
            style={styles.input}
            placeholder="500"
            placeholderTextColor={C.muted}
          />
          <Text style={styles.fieldLabel}>SIDE</Text>
          <View style={styles.sideRow}>
            <Pressable
              onPress={() => setSide("BUY")}
              style={[styles.side, side === "BUY" && styles.buyActive]}
            >
              <Text
                style={[styles.sideText, side === "BUY" && styles.activeText]}
              >
                BUY
              </Text>
            </Pressable>
            <Pressable
              onPress={() => setSide("SELL")}
              style={[styles.side, side === "SELL" && styles.sellActive]}
            >
              <Text
                style={[styles.sideText, side === "SELL" && styles.sellText]}
              >
                SELL
              </Text>
            </Pressable>
          </View>
          <Pressable
            onPress={runSimulation}
            style={({ pressed }) => [
              styles.simButton,
              pressed && styles.pressed,
            ]}
          >
            <MaterialIcons name="play-arrow" size={17} color={C.bg} />
            <Text style={styles.simButtonText}>SIMULATE {side} ORDER</Text>
          </Pressable>
        </View>
        {fill && (
          <View style={styles.result}>
            <View style={styles.resultHeader}>
              <MaterialIcons name="receipt-long" size={18} color={C.mint} />
              <Text style={styles.resultTitle}>
                Simulated fill · {selected?.symbol}
              </Text>
              <Text style={styles.simulated}>NOT SENT</Text>
            </View>
            <Row label="Requested" value={`$${fill.requestedUsd.toFixed(2)}`} />
            <Row
              label="Estimated price"
              value={formatPrice(fill.estimatedPriceUsd)}
            />
            <Row
              label="Price impact"
              value={`${fill.priceImpactPct.toFixed(2)}%`}
            />
            <Row
              label="Slippage model"
              value={`${fill.slippagePct.toFixed(2)}%`}
            />
            <Row label="Fee assumption" value={`$${fill.feeUsd.toFixed(2)}`} />
            <Row
              label="Total modeled cost"
              value={`$${totalCost.toFixed(2)}`}
            />
            <Text style={styles.assumption}>
              {fill.assumptions.join(" · ")}
            </Text>
          </View>
        )}
        <View style={styles.riskCard}>
          <View style={styles.riskHeader}>
            <MaterialIcons name="verified-user" size={18} color={C.amber} />
            <Text style={styles.riskTitle}>Guardrails</Text>
            <Text style={styles.active}>ACTIVE</Text>
          </View>
          <Row label="Max position size" value="10% equity" amber />
          <Row label="Fee model" value="1.25%" amber />
          <Row label="Hard stop" value="-12%" amber />
        </View>
        <View style={styles.message}>
          <MaterialIcons name="check-circle" size={16} color={C.mint} />
          <Text style={styles.messageText}>{message}</Text>
        </View>
      </ScrollView>
    </ScreenContainer>
  );
}
function formatPrice(value: number | null) {
  if (value === null || !Number.isFinite(value)) return "—";
  if (value >= 1) return `$${value.toFixed(2)}`;
  if (value >= 0.01) return `$${value.toFixed(4)}`;
  return `$${value.toExponential(3)}`;
}
function money(value: number) {
  return value >= 1_000_000
    ? `$${(value / 1_000_000).toFixed(1)}M`
    : value >= 1_000
      ? `$${(value / 1_000).toFixed(1)}K`
      : `$${value.toFixed(0)}`;
}
function Quote({
  label,
  value,
  positive,
}: {
  label: string;
  value: string;
  positive?: boolean;
}) {
  return (
    <View>
      <Text style={styles.quoteLabel}>{label}</Text>
      <Text
        style={[
          styles.quoteValue,
          positive !== undefined && { color: positive ? C.mint : C.red },
        ]}
      >
        {value}
      </Text>
    </View>
  );
}
function Row({
  label,
  value,
  amber,
}: {
  label: string;
  value: string;
  amber?: boolean;
}) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      <Text style={[styles.rowValue, amber && { color: C.amber }]}>
        {value}
      </Text>
    </View>
  );
}
const styles = StyleSheet.create({
  content: { paddingTop: 16, paddingBottom: 32, gap: 15 },
  header: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "flex-start",
  },
  kicker: {
    color: C.mint,
    fontSize: 10,
    fontWeight: "800",
    letterSpacing: 1.3,
  },
  title: { color: C.text, fontSize: 28, fontWeight: "800", marginTop: 4 },
  subtitle: { color: C.muted, fontSize: 11, marginTop: 4, maxWidth: 300 },
  badge: {
    flexDirection: "row",
    alignItems: "center",
    gap: 5,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 10,
    paddingHorizontal: 8,
    paddingVertical: 6,
  },
  badgeText: { color: C.mint, fontWeight: "900", fontSize: 9 },
  balanceCard: {
    backgroundColor: C.surface,
    borderColor: C.border,
    borderWidth: 1,
    borderRadius: 19,
    padding: 18,
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
  },
  balanceLabel: {
    color: C.muted,
    fontSize: 9,
    fontWeight: "800",
    letterSpacing: 1,
  },
  balance: { color: C.text, fontSize: 32, fontWeight: "800", marginTop: 7 },
  balanceDelta: {
    color: C.mint,
    fontSize: 11,
    fontWeight: "700",
    marginTop: 4,
  },
  balanceIcon: {
    width: 48,
    height: 48,
    borderRadius: 16,
    backgroundColor: C.mintSoft,
    alignItems: "center",
    justifyContent: "center",
  },
  liveCard: {
    backgroundColor: "#0B251D",
    borderWidth: 1,
    borderColor: "#164D3B",
    borderRadius: 17,
    padding: 14,
  },
  liveHeader: { flexDirection: "row", alignItems: "center", gap: 7 },
  liveTitle: {
    color: C.mint,
    fontSize: 9,
    fontWeight: "900",
    letterSpacing: 1,
    flex: 1,
  },
  liveStatus: { color: C.mint, fontSize: 9, fontWeight: "900" },
  selectedSymbol: {
    color: C.text,
    fontSize: 24,
    fontWeight: "900",
    marginTop: 10,
  },
  selectedName: { color: C.muted, fontSize: 10, marginTop: 2 },
  quoteRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    borderTopWidth: 1,
    borderTopColor: C.border,
    marginTop: 12,
    paddingTop: 10,
  },
  quoteLabel: { color: C.muted, fontSize: 8, fontWeight: "800" },
  quoteValue: { color: C.text, fontSize: 11, fontWeight: "800", marginTop: 4 },
  tokenRow: { gap: 7, paddingTop: 13 },
  tokenChip: {
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 9,
    paddingHorizontal: 9,
    paddingVertical: 7,
  },
  tokenChipActive: { backgroundColor: C.mint, borderColor: C.mint },
  tokenChipText: { color: C.muted, fontSize: 9, fontWeight: "800" },
  tokenChipTextActive: { color: C.bg },
  emptyText: { color: C.muted, fontSize: 11, marginTop: 12 },
  ticket: {
    backgroundColor: C.surface,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 17,
    padding: 15,
  },
  ticketHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 13,
  },
  sectionTitle: { color: C.text, fontSize: 16, fontWeight: "800" },
  ticketMode: { color: C.mint, fontSize: 9, fontWeight: "900" },
  fieldLabel: {
    color: C.muted,
    fontSize: 9,
    fontWeight: "800",
    letterSpacing: 1,
    marginTop: 7,
    marginBottom: 7,
  },
  input: {
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 11,
    color: C.text,
    height: 43,
    paddingHorizontal: 12,
    fontSize: 14,
    backgroundColor: "#0A1715",
  },
  sideRow: { flexDirection: "row", gap: 8 },
  side: {
    flex: 1,
    alignItems: "center",
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 10,
    paddingVertical: 10,
  },
  buyActive: { backgroundColor: C.mint, borderColor: C.mint },
  sellActive: { backgroundColor: "#4A2226", borderColor: C.red },
  sideText: { color: C.muted, fontWeight: "900", fontSize: 11 },
  activeText: { color: C.bg },
  sellText: { color: C.red },
  simButton: {
    flexDirection: "row",
    justifyContent: "center",
    alignItems: "center",
    gap: 6,
    backgroundColor: C.mint,
    borderRadius: 11,
    paddingVertical: 12,
    marginTop: 15,
  },
  simButtonText: { color: C.bg, fontWeight: "900", fontSize: 11 },
  pressed: { opacity: 0.75, transform: [{ scale: 0.98 }] },
  result: {
    backgroundColor: "#0B251D",
    borderWidth: 1,
    borderColor: "#164D3B",
    borderRadius: 16,
    padding: 14,
  },
  resultHeader: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    marginBottom: 8,
  },
  resultTitle: { color: C.text, fontSize: 13, fontWeight: "800", flex: 1 },
  simulated: { color: C.mint, fontSize: 9, fontWeight: "900" },
  row: {
    flexDirection: "row",
    justifyContent: "space-between",
    borderTopWidth: 1,
    borderTopColor: C.border,
    paddingVertical: 8,
  },
  rowLabel: { color: C.muted, fontSize: 10 },
  rowValue: { color: C.text, fontSize: 10, fontWeight: "800" },
  assumption: { color: C.muted, fontSize: 9, lineHeight: 14, marginTop: 5 },
  riskCard: {
    backgroundColor: "#211B0E",
    borderColor: "#5A421D",
    borderWidth: 1,
    borderRadius: 16,
    padding: 14,
  },
  riskHeader: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    marginBottom: 3,
  },
  riskTitle: { color: C.text, fontSize: 13, fontWeight: "800", flex: 1 },
  active: { color: C.amber, fontSize: 9, fontWeight: "900" },
  message: {
    flexDirection: "row",
    gap: 8,
    alignItems: "center",
    paddingTop: 2,
  },
  messageText: { color: C.muted, fontSize: 10, flex: 1 },
});
