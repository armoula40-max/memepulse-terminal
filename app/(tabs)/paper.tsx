import AsyncStorage from "@react-native-async-storage/async-storage";
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
import { simulateFill } from "@/lib/simulation";
import { useDirectPumpPortal } from "@/lib/pumpportal-client";
import { loadPaperAutoBuyOrders } from "@/lib/pumpportal-background";

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
const LEDGER_KEY = "memepulse.paper-ledger.v1";
type Position = {
  address: string;
  symbol: string;
  qty: number;
  avgEntryUsd: number;
  investedUsd: number;
  realizedPnlUsd: number;
};
type Order = {
  id: string;
  address: string;
  symbol: string;
  side: "BUY" | "SELL";
  notionalUsd: number;
  fillPriceUsd: number;
  qty: number;
  feeUsd: number;
  createdAt: string;
  status: "FILLED";
};
type Ledger = { cashUsd: number; positions: Position[]; orders: Order[] };
const EMPTY_LEDGER: Ledger = { cashUsd: 10_000, positions: [], orders: [] };

export default function PaperScreen() {
  const [amount, setAmount] = useState("500");
  const [side, setSide] = useState<"BUY" | "SELL">("BUY");
  const [selectedAddress, setSelectedAddress] = useState<string | null>(null);
  const [ledger, setLedger] = useState<Ledger>(EMPTY_LEDGER);
  const [message, setMessage] = useState(
    "Paper account ready. Orders are simulated locally.",
  );
  const market = trpc.market.latest.useQuery(undefined, {
    staleTime: 5_000,
    refetchInterval: 15_000,
  });
  const directPump = useDirectPumpPortal();
  const tokens = useMemo(
    () =>
      Array.from(
        new Map(
          [...(market.data?.tokens ?? []), ...directPump.tokens].map(
            (token) => [token.address, token],
          ),
        ).values(),
      ),
    [market.data, directPump.tokens],
  );
  const selected =
    tokens.find((token) => token.address === selectedAddress) ?? tokens[0];
  useEffect(() => {
    void AsyncStorage.getItem(LEDGER_KEY).then((raw) => {
      if (raw) setLedger({ ...EMPTY_LEDGER, ...JSON.parse(raw) });
    });
  }, []);
  useEffect(() => {
    void loadPaperAutoBuyOrders().then((orders) => {
      if (!orders.length) return;
      setLedger((current) => {
        const incoming = (orders as Order[]).filter(
          (order) => !current.orders.some((item) => item.id === order.id),
        );
        const positions = [...current.positions];
        for (const order of incoming) {
          const position = positions.find(
            (item) => item.address === order.address,
          );
          if (order.side === "SELL") {
            if (position) {
              position.qty = Math.max(0, position.qty - order.qty);
              position.realizedPnlUsd +=
                (order.fillPriceUsd - position.avgEntryUsd) * order.qty -
                order.feeUsd;
            }
            continue;
          }
          if (position) {
            position.qty += order.qty;
            position.investedUsd += order.notionalUsd;
            position.avgEntryUsd = position.investedUsd / position.qty;
          } else {
            positions.push({
              address: order.address,
              symbol: order.symbol,
              qty: order.qty,
              avgEntryUsd: order.fillPriceUsd,
              investedUsd: order.notionalUsd,
              realizedPnlUsd: 0,
            });
          }
        }
        return {
          ...current,
          cashUsd:
            current.cashUsd +
            incoming.reduce(
              (sum, order) =>
                sum +
                (order.side === "SELL"
                  ? order.notionalUsd - order.feeUsd
                  : -(order.notionalUsd + order.feeUsd)),
              0,
            ),
          positions,
          orders: [...current.orders, ...incoming],
        };
      });
    });
  }, []);
  useEffect(() => {
    if (!selectedAddress && tokens[0]) setSelectedAddress(tokens[0].address);
  }, [selectedAddress, tokens]);
  const saveLedger = (next: Ledger) => {
    setLedger(next);
    void AsyncStorage.setItem(LEDGER_KEY, JSON.stringify(next));
  };
  const positions = useMemo(
    () => ledger.positions.filter((position) => position.qty > 0.000000001),
    [ledger.positions],
  );
  const portfolio = useMemo(() => {
    let marketValue = 0;
    let unrealized = 0;
    for (const position of positions) {
      const token = tokens.find((item) => item.address === position.address);
      const price = token?.priceUsd ?? position.avgEntryUsd;
      marketValue += position.qty * price;
      unrealized += position.qty * (price - position.avgEntryUsd);
    }
    const realized =
      ledger.orders
        .filter((order) => order.side === "SELL")
        .reduce((sum, order) => sum + (order.notionalUsd - order.feeUsd), 0) -
      positions.reduce((sum, position) => sum + position.realizedPnlUsd, 0);
    return {
      marketValue,
      unrealized,
      realized: positions.reduce(
        (sum, position) => sum + position.realizedPnlUsd,
        0,
      ),
      equity: ledger.cashUsd + marketValue,
    };
  }, [ledger, positions, tokens]);
  const submitOrder = () => {
    if (!selected || !selected.priceUsd || selected.priceUsd <= 0) {
      setMessage("Waiting for a valid live quote.");
      return;
    }
    const requested = Math.max(0, Number(amount) || 0);
    if (!requested) {
      setMessage("Enter an order amount first.");
      return;
    }
    const fill = simulateFill({
      side,
      notionalUsd: requested,
      priceUsd: selected.priceUsd,
      liquidityUsd: selected.liquidityUsd,
    });
    const position = ledger.positions.find(
      (item) => item.address === selected.address,
    );
    const now = new Date().toISOString();
    if (side === "BUY") {
      const total = fill.requestedUsd + fill.feeUsd;
      if (total > ledger.cashUsd) {
        setMessage("Insufficient simulated cash for this order.");
        return;
      }
      const qty = fill.requestedUsd / fill.estimatedPriceUsd;
      const nextPosition: Position = position
        ? {
            ...position,
            qty: position.qty + qty,
            avgEntryUsd:
              (position.qty * position.avgEntryUsd + fill.requestedUsd) /
              (position.qty + qty),
            investedUsd: position.investedUsd + fill.requestedUsd,
          }
        : {
            address: selected.address,
            symbol: selected.symbol,
            qty,
            avgEntryUsd: fill.estimatedPriceUsd,
            investedUsd: fill.requestedUsd,
            realizedPnlUsd: 0,
          };
      const next: Ledger = {
        cashUsd: ledger.cashUsd - total,
        positions: [
          ...ledger.positions.filter(
            (item) => item.address !== selected.address,
          ),
          nextPosition,
        ],
        orders: [
          {
            id: `${Date.now()}`,
            address: selected.address,
            symbol: selected.symbol,
            side,
            notionalUsd: fill.requestedUsd,
            fillPriceUsd: fill.estimatedPriceUsd,
            qty,
            feeUsd: fill.feeUsd,
            createdAt: now,
            status: "FILLED" as const,
          },
          ...ledger.orders,
        ].slice(0, 100),
      };
      saveLedger(next);
      setMessage(
        `BUY FILLED · ${qty.toFixed(2)} $${selected.symbol} added to positions`,
      );
    } else {
      if (!position || position.qty <= 0) {
        setMessage(`No open position in $${selected.symbol} to sell.`);
        return;
      }
      const qty = Math.min(
        position.qty,
        fill.requestedUsd / fill.estimatedPriceUsd,
      );
      const gross = qty * fill.estimatedPriceUsd;
      const costBasis = qty * position.avgEntryUsd;
      const pnl = gross - fill.feeUsd - costBasis;
      const nextPosition: Position = {
        ...position,
        qty: position.qty - qty,
        investedUsd: Math.max(0, position.investedUsd - costBasis),
        realizedPnlUsd: position.realizedPnlUsd + pnl,
      };
      const next: Ledger = {
        cashUsd: ledger.cashUsd + gross - fill.feeUsd,
        positions: [
          ...ledger.positions.filter(
            (item) => item.address !== selected.address,
          ),
          nextPosition,
        ],
        orders: [
          {
            id: `${Date.now()}`,
            address: selected.address,
            symbol: selected.symbol,
            side,
            notionalUsd: gross,
            fillPriceUsd: fill.estimatedPriceUsd,
            qty,
            feeUsd: fill.feeUsd,
            createdAt: now,
            status: "FILLED" as const,
          },
          ...ledger.orders,
        ].slice(0, 100),
      };
      saveLedger(next);
      setMessage(
        `SELL FILLED · ${qty.toFixed(2)} $${selected.symbol} closed · P&L ${pnl >= 0 ? "+" : ""}$${pnl.toFixed(2)}`,
      );
    }
  };
  const selectForSell = (position: Position) => {
    setSelectedAddress(position.address);
    setSide("SELL");
    const token = tokens.find((item) => item.address === position.address);
    setAmount(
      String(
        Math.max(
          1,
          Number(
            (position.qty * (token?.priceUsd ?? position.avgEntryUsd)).toFixed(
              2,
            ),
          ),
        ),
      ),
    );
  };
  return (
    <ScreenContainer className="px-5" containerClassName="bg-background">
      <ScrollView
        showsVerticalScrollIndicator={false}
        contentContainerStyle={styles.content}
      >
        <View style={styles.header}>
          <View>
            <Text style={styles.kicker}>PAPER EXCHANGE</Text>
            <Text style={styles.title}>Trading account</Text>
            <Text style={styles.subtitle}>
              All loaded market tokens · simulated orders · local ledger
            </Text>
          </View>
          <View style={styles.badge}>
            <MaterialIcons name="science" size={15} color={C.mint} />
            <Text style={styles.badgeText}>PAPER</Text>
          </View>
        </View>
        <View style={styles.metrics}>
          <Metric
            label="EQUITY"
            value={money(portfolio.equity)}
            color={C.text}
          />
          <Metric label="CASH" value={money(ledger.cashUsd)} color={C.blue} />
          <Metric
            label="UNREALIZED P&L"
            value={`${portfolio.unrealized >= 0 ? "+" : ""}${money(portfolio.unrealized)}`}
            color={portfolio.unrealized >= 0 ? C.mint : C.red}
          />
          <Metric
            label="REALIZED P&L"
            value={`${portfolio.realized >= 0 ? "+" : ""}${money(portfolio.realized)}`}
            color={portfolio.realized >= 0 ? C.mint : C.red}
          />
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
                  label="1H"
                  value={`${selected.change1hPct >= 0 ? "+" : ""}${selected.change1hPct.toFixed(1)}%`}
                  positive={selected.change1hPct >= 0}
                />
              </View>
              <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                contentContainerStyle={styles.tokenRow}
              >
                {tokens.map((token) => (
                  <Pressable
                    key={token.address}
                    onPress={() => setSelectedAddress(token.address)}
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
            <Text style={styles.emptyText}>Waiting for a live quote...</Text>
          )}
        </View>
        <View style={styles.ticket}>
          <View style={styles.ticketHeader}>
            <Text style={styles.sectionTitle}>Order ticket</Text>
            <Text style={styles.ticketMode}>MARKET · SIMULATED</Text>
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
          <View style={styles.sideRow}>
            <Pressable
              onPress={() => setSide("BUY")}
              style={[styles.side, side === "BUY" && styles.buyActive]}
            >
              <Text
                style={[styles.sideText, side === "BUY" && styles.activeText]}
              >
                BUY / OPEN
              </Text>
            </Pressable>
            <Pressable
              onPress={() => setSide("SELL")}
              style={[styles.side, side === "SELL" && styles.sellActive]}
            >
              <Text
                style={[styles.sideText, side === "SELL" && styles.sellText]}
              >
                SELL / CLOSE
              </Text>
            </Pressable>
          </View>
          <Pressable
            onPress={submitOrder}
            style={[styles.simButton, side === "SELL" && styles.sellButton]}
          >
            <MaterialIcons
              name={side === "BUY" ? "add-shopping-cart" : "sell"}
              size={17}
              color={C.bg}
            />
            <Text style={styles.simButtonText}>
              {side === "BUY" ? "PLACE BUY ORDER" : "PLACE SELL ORDER"}
            </Text>
          </Pressable>
          <Text style={styles.orderHint}>
            Market order fills against the current public quote with simulated
            fees and slippage.
          </Text>
        </View>
        <Text style={styles.section}>OPEN POSITIONS · {positions.length}</Text>
        {positions.length ? (
          positions.map((position) => {
            const token = tokens.find(
              (item) => item.address === position.address,
            );
            const price = token?.priceUsd ?? position.avgEntryUsd;
            const pnl = position.qty * (price - position.avgEntryUsd);
            return (
              <View key={position.address} style={styles.position}>
                <View style={{ flex: 1 }}>
                  <Text style={styles.positionSymbol}>${position.symbol}</Text>
                  <Text style={styles.positionDetail}>
                    {position.qty.toFixed(2)} units · avg{" "}
                    {formatPrice(position.avgEntryUsd)}
                  </Text>
                </View>
                <View style={styles.positionRight}>
                  <Text
                    style={[
                      styles.positionPnl,
                      { color: pnl >= 0 ? C.mint : C.red },
                    ]}
                  >
                    {pnl >= 0 ? "+" : ""}
                    {money(pnl)}
                  </Text>
                  <Pressable
                    onPress={() => selectForSell(position)}
                    style={styles.closePosition}
                  >
                    <Text style={styles.closePositionText}>SELL</Text>
                  </Pressable>
                </View>
              </View>
            );
          })
        ) : (
          <View style={styles.emptyBox}>
            <MaterialIcons
              name="account-balance-wallet"
              size={18}
              color={C.muted}
            />
            <Text style={styles.emptyText}>
              No open positions. Place a simulated BUY order above.
            </Text>
          </View>
        )}
        <Text style={styles.section}>ORDER HISTORY</Text>
        {ledger.orders.slice(0, 12).map((order) => (
          <View key={order.id} style={styles.order}>
            <View
              style={[
                styles.orderIcon,
                {
                  backgroundColor:
                    order.side === "BUY" ? C.mintSoft : "#3B2022",
                },
              ]}
            >
              <MaterialIcons
                name={order.side === "BUY" ? "north-east" : "south-west"}
                size={15}
                color={order.side === "BUY" ? C.mint : C.red}
              />
            </View>
            <View style={{ flex: 1 }}>
              <Text style={styles.orderTitle}>
                {order.side} · ${order.symbol}
              </Text>
              <Text style={styles.orderDetail}>
                {order.qty.toFixed(2)} units · {formatPrice(order.fillPriceUsd)}{" "}
                · {new Date(order.createdAt).toLocaleTimeString()}
              </Text>
            </View>
            <Text style={styles.filled}>FILLED</Text>
          </View>
        ))}
        <View style={styles.message}>
          <MaterialIcons name="lock" size={15} color={C.muted} />
          <Text style={styles.messageText}>
            {message} Nothing is sent to a wallet or exchange.
          </Text>
        </View>
      </ScrollView>
    </ScreenContainer>
  );
}
function money(value: number) {
  const sign = value < 0 ? "-" : "";
  const absolute = Math.abs(value);
  return `${sign}$${absolute >= 1_000 ? `${(absolute / 1_000).toFixed(2)}K` : absolute.toFixed(2)}`;
}
function formatPrice(value: number | null) {
  if (value === null || !Number.isFinite(value)) return "—";
  if (value >= 1) return `$${value.toFixed(2)}`;
  if (value >= 0.01) return `$${value.toFixed(4)}`;
  return `$${value.toExponential(3)}`;
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
function Metric({
  label,
  value,
  color,
}: {
  label: string;
  value: string;
  color: string;
}) {
  return (
    <View style={styles.metric}>
      <Text style={styles.metricLabel}>{label}</Text>
      <Text style={[styles.metricValue, { color }]}>{value}</Text>
    </View>
  );
}
const styles = StyleSheet.create({
  content: { paddingTop: 16, paddingBottom: 32, gap: 12 },
  header: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "flex-start",
    marginBottom: 3,
  },
  kicker: {
    color: C.mint,
    fontSize: 10,
    fontWeight: "800",
    letterSpacing: 1.3,
  },
  title: { color: C.text, fontSize: 28, fontWeight: "800", marginTop: 4 },
  subtitle: { color: C.muted, fontSize: 11, marginTop: 4 },
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
  metrics: { flexDirection: "row", flexWrap: "wrap", gap: 8 },
  metric: {
    width: "48.5%",
    backgroundColor: C.surface,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 13,
    padding: 11,
  },
  metricLabel: { color: C.muted, fontSize: 8, fontWeight: "800" },
  metricValue: { fontSize: 17, fontWeight: "900", marginTop: 6 },
  liveCard: {
    backgroundColor: "#0B251D",
    borderWidth: 1,
    borderColor: "#164D3B",
    borderRadius: 17,
    padding: 14,
  },
  liveHeader: { flexDirection: "row", alignItems: "center", gap: 7 },
  liveTitle: { color: C.mint, fontSize: 9, fontWeight: "900", flex: 1 },
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
  emptyText: { color: C.muted, fontSize: 11, lineHeight: 16 },
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
  },
  sectionTitle: { color: C.text, fontSize: 16, fontWeight: "800" },
  ticketMode: { color: C.mint, fontSize: 9, fontWeight: "900" },
  fieldLabel: {
    color: C.muted,
    fontSize: 9,
    fontWeight: "800",
    letterSpacing: 1,
    marginTop: 13,
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
  sideRow: { flexDirection: "row", gap: 8, marginTop: 13 },
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
  sideText: { color: C.muted, fontWeight: "900", fontSize: 10 },
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
  sellButton: { backgroundColor: C.red },
  simButtonText: { color: C.bg, fontWeight: "900", fontSize: 11 },
  orderHint: {
    color: C.muted,
    fontSize: 9,
    lineHeight: 14,
    marginTop: 9,
    textAlign: "center",
  },
  section: {
    color: C.muted,
    fontSize: 9,
    fontWeight: "900",
    letterSpacing: 1.3,
    marginTop: 8,
  },
  position: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: C.surface,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 14,
    padding: 12,
  },
  positionSymbol: { color: C.text, fontSize: 13, fontWeight: "900" },
  positionDetail: { color: C.muted, fontSize: 9, marginTop: 4 },
  positionRight: { alignItems: "flex-end", gap: 6 },
  positionPnl: { fontSize: 12, fontWeight: "900" },
  closePosition: {
    backgroundColor: "#4A2226",
    borderRadius: 7,
    paddingHorizontal: 9,
    paddingVertical: 5,
  },
  closePositionText: { color: C.red, fontSize: 8, fontWeight: "900" },
  emptyBox: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    backgroundColor: C.surface,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 14,
    padding: 13,
  },
  order: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
    backgroundColor: C.surface,
    borderWidth: 1,
    borderColor: C.border,
    borderRadius: 13,
    padding: 10,
  },
  orderIcon: {
    width: 30,
    height: 30,
    borderRadius: 9,
    alignItems: "center",
    justifyContent: "center",
  },
  orderTitle: { color: C.text, fontSize: 11, fontWeight: "800" },
  orderDetail: { color: C.muted, fontSize: 9, marginTop: 3 },
  filled: { color: C.mint, fontSize: 8, fontWeight: "900" },
  message: {
    flexDirection: "row",
    gap: 8,
    alignItems: "center",
    borderTopWidth: 1,
    borderTopColor: C.border,
    paddingTop: 14,
    marginTop: 4,
  },
  messageText: { color: C.muted, fontSize: 10, lineHeight: 15, flex: 1 },
});
