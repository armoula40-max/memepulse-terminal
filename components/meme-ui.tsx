import { MaterialIcons } from "@expo/vector-icons";
import { Pressable, StyleSheet, Text, View, type PressableProps, type ViewStyle } from "react-native";

export const UI = {
  bg: "#07111F",
  panel: "#0D1B2A",
  panelAlt: "#12243A",
  border: "#1D3852",
  text: "#F4F8FC",
  muted: "#8FA6BC",
  mint: "#23E6A0",
  mintSoft: "#123E3A",
  cyan: "#59D6FF",
  amber: "#F6C667",
  red: "#FF7180",
  purple: "#9D8CFF",
};

export function Card({ children, style }: { children: React.ReactNode; style?: ViewStyle }) {
  return <View style={[styles.card, style]}>{children}</View>;
}

export function SectionHeader({ title, detail, action }: { title: string; detail?: string; action?: string }) {
  return (
    <View style={styles.sectionHeader}>
      <View>
        <Text style={styles.sectionTitle}>{title}</Text>
        {detail ? <Text style={styles.sectionDetail}>{detail}</Text> : null}
      </View>
      {action ? <Text style={styles.sectionAction}>{action}</Text> : null}
    </View>
  );
}

export function Pill({ label, active = false, color = UI.mint, onPress }: { label: string; active?: boolean; color?: string; onPress?: PressableProps["onPress"] }) {
  const content = <Text style={[styles.pillText, active && { color: UI.bg }]}>{label}</Text>;
  if (!onPress) return <View style={[styles.pill, active && { backgroundColor: color, borderColor: color }]}>{content}</View>;
  return <Pressable onPress={onPress} style={[styles.pill, active && { backgroundColor: color, borderColor: color }]}>{content}</Pressable>;
}

export function TokenAvatar({ symbol, tone = UI.mint }: { symbol: string; tone?: string }) {
  return <View style={[styles.avatar, { backgroundColor: `${tone}24`, borderColor: `${tone}70` }]}><Text style={[styles.avatarText, { color: tone }]}>{symbol.slice(0, 1).toUpperCase()}</Text></View>;
}

export function SignalLine({ value, color = UI.mint, width = 64 }: { value: number; color?: string; width?: number }) {
  const bars = Array.from({ length: 9 }, (_, index) => Math.max(4, 10 + Math.sin(index * 1.7 + value) * 5 + (index * value) % 8));
  return <View style={[styles.signalLine, { width }]}>{bars.map((height, index) => <View key={index} style={[styles.signalBar, { height, backgroundColor: color, opacity: 0.45 + index / 18 }]} />)}</View>;
}

export function StatTile({ label, value, delta, icon }: { label: string; value: string; delta?: string; icon?: keyof typeof MaterialIcons.glyphMap }) {
  return <Card style={styles.statTile}>{icon ? <MaterialIcons name={icon} size={15} color={UI.mint} /> : null}<Text style={styles.statLabel}>{label}</Text><Text style={styles.statValue}>{value}</Text>{delta ? <Text style={styles.statDelta}>{delta}</Text> : null}</Card>;
}

const styles = StyleSheet.create({
  card: { backgroundColor: UI.panel, borderWidth: 1, borderColor: UI.border, borderRadius: 16, padding: 14 },
  sectionHeader: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", marginTop: 4 },
  sectionTitle: { color: UI.text, fontSize: 16, fontWeight: "800" },
  sectionDetail: { color: UI.muted, fontSize: 10, marginTop: 3 },
  sectionAction: { color: UI.mint, fontSize: 10, fontWeight: "800" },
  pill: { borderRadius: 9, borderWidth: 1, borderColor: UI.border, paddingHorizontal: 11, paddingVertical: 7, backgroundColor: UI.panel },
  pillText: { color: UI.muted, fontSize: 10, fontWeight: "800" },
  avatar: { width: 38, height: 38, borderRadius: 13, borderWidth: 1, alignItems: "center", justifyContent: "center" },
  avatarText: { fontSize: 17, fontWeight: "900" },
  signalLine: { height: 22, flexDirection: "row", alignItems: "flex-end", justifyContent: "space-between", gap: 2 },
  signalBar: { width: 4, borderRadius: 3 },
  statTile: { width: "48.4%", minHeight: 88, padding: 12 },
  statLabel: { color: UI.muted, fontSize: 9, fontWeight: "800", letterSpacing: 0.4, marginTop: 7 },
  statValue: { color: UI.text, fontSize: 18, fontWeight: "900", marginTop: 4 },
  statDelta: { color: UI.mint, fontSize: 9, fontWeight: "800", marginTop: 3 },
});
