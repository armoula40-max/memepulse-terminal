package com.solanasignal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.solanasignal.data.PaperTradeEntity
import com.solanasignal.data.PaperTradeEventEntity
import com.solanasignal.network.Diagnostics
import com.solanasignal.service.ScannerForegroundService
import com.solanasignal.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 700)
        enableEdgeToEdge()
        setContent { SolanaSignalApp(vm) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolanaSignalApp(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val statusMessage by vm.statusMessage.collectAsState()
    val diagnostics by vm.diagnostics.collectAsState()
    val signals by vm.signals.collectAsState()
    val tokens by vm.tokens.collectAsState()
    val paper by vm.paperTerminal.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var key by remember { mutableStateOf("") }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF65E6A7),
            background = Color(0xFF07100F),
            surface = Color(0xFF0D1B18),
        ),
    ) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Solana Signal") }, actions = { Text(state.name, style = MaterialTheme.typography.labelSmall) }) },
            bottomBar = {
                NavigationBar {
                    listOf(
                        "Dashboard" to Icons.Default.Home,
                        "Scanner" to Icons.Default.Search,
                        "Signals" to Icons.Default.Notifications,
                        "Paper" to Icons.Default.Home,
                        "Settings" to Icons.Default.Settings,
                    ).forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(item.second, item.first) },
                            label = { Text(item.first) },
                        )
                    }
                }
            },
        ) { pad ->
            when (tab) {
                0 -> Dashboard(pad, statusMessage, diagnostics, tokens.size, signals.size)
                1 -> Scanner(pad, tokens.size, statusMessage)
                2 -> Signals(pad, signals)
                3 -> PaperTerminal(pad, paper)
                else -> Settings(pad, key, { key = it }, vm::saveKey, { vm.start() })
            }
        }
    }
}

@Composable
private fun Dashboard(pad: PaddingValues, state: String, diagnostics: Diagnostics, tokens: Int, signals: Int) {
    LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("SIGNAL ENGINE", style = MaterialTheme.typography.headlineMedium) }
        item { Text("No real trading. Paper simulation only; manual Photon links remain user-initiated.") }
        item { StatusCard("PumpPortal WebSocket", state) }
        item { StatusCard("Tokens tracked", tokens.toString()) }
        item { StatusCard("Events received · parsed · normalized", "${diagnostics.eventsReceived} · ${diagnostics.eventsParsed} · ${diagnostics.eventsNormalized}") }
        item { StatusCard("Trades · buys · sells", "${diagnostics.tradesReceived} · ${diagnostics.buys} · ${diagnostics.sells}") }
        item { StatusCard("Creations · migrations", "${diagnostics.creations} · ${diagnostics.migrations}") }
        item { StatusCard("Duplicates · invalid · late", "${diagnostics.duplicates} · ${diagnostics.invalidEvents} · ${diagnostics.lateEvents}") }
        item { StatusCard("Stale observations · unknown fields", "${diagnostics.staleData} · ${diagnostics.unknownFields}") }
        item { StatusCard("Metrics generated · signals stored", "${diagnostics.metricsGenerated} · $signals") }
        item { Text("A signal is not a prediction or guaranteed profit.", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Scanner(pad: PaddingValues, tokens: Int, state: String) {
    Column(Modifier.padding(pad).padding(16.dp)) {
        Text("LIVE SCANNER", style = MaterialTheme.typography.headlineMedium)
        Text("Connection: $state")
        Spacer(Modifier.height(16.dp))
        Text(if (tokens == 0) "No verified live token data yet. Configure your API key and start the foreground scanner." else "$tokens tracked tokens")
    }
}

@Composable
private fun Signals(pad: PaddingValues, signals: List<com.solanasignal.data.SignalEntity>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("SIGNAL HISTORY", style = MaterialTheme.typography.headlineMedium) }
        items(signals) { signal ->
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text(signal.signalType, style = MaterialTheme.typography.titleLarge)
                    Text("Score ${signal.score}/100 · ${signal.mint}")
                    Text(if (signal.reasons.isBlank()) "Reasons: UNKNOWN" else signal.reasons)
                    Text("No live trade was executed; a separate paper-only simulator may track this signal.", style = MaterialTheme.typography.labelSmall)
                    OutlinedButton(onClick = { com.solanasignal.ui.PhotonLauncher.open(context, signal.mint) }) { Text("Copy mint + open Photon") }
                }
            }
        }
    }
}

@Composable
private fun PaperTerminal(pad: PaddingValues, snapshot: com.solanasignal.paper.PaperTerminalSnapshot) {
    val account = snapshot.account
    LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { Text("PAPER TRADING TERMINAL", style = MaterialTheme.typography.headlineSmall) }
        item { Text("${snapshot.engineVersion} · Engine B ${com.solanasignal.engineb.EngineB.VERSION}", style = MaterialTheme.typography.labelMedium) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("SIMULATION ONLY", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                    Text("Virtual USD ledger. No wallet, order, signing, or transaction is used. Fees and slippage are estimates; this is not execution or a performance claim.")
                }
            }
        }
        item {
            StatusCard(
                "Mark-to-market status",
                if (snapshot.currentEquityUsd == null) "${snapshot.valuationStatus.name} · current equity/P&L suppressed" else snapshot.valuationStatus.name,
            )
        }
        item { StatusCard("Starting balance", account?.let { usd(it.startingBalanceUsd) } ?: "Initializing…") }
        item { StatusCard("Virtual cash", account?.let { usd(it.cashBalanceUsd) } ?: "—") }
        item { StatusCard("Current equity · net liquidation estimate", snapshot.currentEquityUsd?.let(::usd) ?: "UNKNOWN / STALE") }
        item { StatusCard("Reserved at entry · open positions", account?.let { "${usd(it.reservedOpenPositionValueUsd)} · ${snapshot.openPositions.size}" } ?: "—") }
        item { StatusCard("Realized · unrealized · total P&L", "${account?.let { usd(it.realizedPnlUsd) } ?: "—"} · ${snapshot.unrealizedPnlUsd?.let(::usd) ?: "UNKNOWN"} · ${snapshot.totalPnlUsd?.let(::usd) ?: "UNKNOWN"}") }
        item { StatusCard("Trades · wins · losses · win rate", account?.let { "${it.tradeCount} · ${it.winningTrades} · ${it.losingTrades} · ${snapshot.winRatePct?.let(::pct) ?: "—"}" } ?: "—") }
        item { StatusCard("Fees paid · simulated slippage", account?.let { "${usd(it.totalFeesUsd)} · ${usd(it.totalSimulatedSlippageUsd)}" } ?: "—") }
        item { StatusCard("Maximum account drawdown", account?.let { pct(it.maximumDrawdownPct) } ?: "—") }

        item { Text("OPEN POSITIONS (${snapshot.openPositions.size})", style = MaterialTheme.typography.titleLarge) }
        if (snapshot.openPositions.isEmpty()) item { StatusCard("Paper positions", "No eligible Engine B entry yet") }
        items(snapshot.openPositions) { position -> OpenPaperPosition(position) }

        item { Text("CLOSED PAPER TRADES (${snapshot.closedTrades.size})", style = MaterialTheme.typography.titleLarge) }
        if (snapshot.closedTrades.isEmpty()) item { StatusCard("Trade history", "No paper exits yet") }
        items(snapshot.closedTrades) { position -> ClosedPaperPosition(position) }

        item { Text("RECENT PAPER AUDIT (${snapshot.recentEvents.size})", style = MaterialTheme.typography.titleLarge) }
        if (snapshot.recentEvents.isEmpty()) item { StatusCard("Audit events", "Entry, mark, and exit snapshots will appear here") }
        items(snapshot.recentEvents.take(40)) { event -> PaperAuditRow(event) }
    }
}

@Composable
private fun OpenPaperPosition(position: PaperTradeEntity) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${position.tokenSymbol ?: shortMint(position.mint)} · OPEN", style = MaterialTheme.typography.titleMedium)
            Text("${position.mint} · entry ${time(position.entryTimestamp)} · age ${duration(position.lastMarkTimestamp?.minus(position.entryTimestamp) ?: 0L)}")
            Text("Observed entry ${usd(position.entryPriceUsd)} @ ${time(position.entryPriceObservedAt)} · effective ${usd(position.effectiveEntryPriceUsd)}")
            Text(if (position.currentPriceUsd == null) "Current price UNKNOWN/STALE · last observed ${position.lastObservedPriceUsd?.let(::usd) ?: "—"}" else "Current ${usd(position.currentPriceUsd)} · gross value ${position.currentValueUsd?.let(::usd) ?: "—"}")
            Text("Unrealized ${position.unrealizedPnlUsd?.let(::usd) ?: "UNKNOWN"} (${position.unrealizedPnlPct?.let(::pct) ?: "—"}) · valuation ${position.valuationStatus}")
            Text("Entry notional ${usd(position.entryNotionalUsd)} · token qty ${position.tokenQuantity.format(6)} · entry fee ${usd(position.entryFeeUsd)}")
            Text("Entry cap/liquidity ${position.entryMarketCapUsd?.let(::usd) ?: "UNKNOWN"} / ${position.entryLiquidityUsd?.let(::usd) ?: "UNKNOWN"}")
            Text("Current cap/liquidity ${position.currentMarketCapUsd?.let(::usd) ?: "UNKNOWN"} / ${position.currentLiquidityUsd?.let(::usd) ?: "UNKNOWN"}")
            Text("Entry ${position.entrySignalState} · pump ${position.entryPumpPotential} · collapse risk ${position.entryCollapseRisk} · freshness ${position.entryDataFreshness}")
            Text("Current ${position.currentSignalState ?: "UNKNOWN"} · pump ${position.currentPumpPotential ?: "UNKNOWN"} · risk ${position.currentCollapseRisk ?: "UNKNOWN"}")
            Text("MFE ${pct(position.maximumFavorableExcursionPct)} · MAE ${pct(position.maximumAdverseExcursionPct)} · max drawdown ${pct(position.maximumPositionDrawdownPct)}")
            Text("Last snapshot ${position.lastMarketSnapshotRef ?: "UNKNOWN"} · entry snapshot ${position.entrySnapshotRef}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ClosedPaperPosition(position: PaperTradeEntity) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${position.tokenSymbol ?: shortMint(position.mint)} · ${position.exitReason ?: position.status}", style = MaterialTheme.typography.titleMedium)
            Text("${position.entrySignalState} / ${position.entryCollapseRisk} · ${time(position.entryTimestamp)} → ${position.exitTimestamp?.let(::time) ?: "—"} · ${duration(position.holdingDurationMs ?: 0L)}")
            Text("Observed ${position.entryPriceUsd.let(::usd)} → ${position.exitPriceUsd?.let(::usd) ?: "UNKNOWN"} · effective ${position.effectiveEntryPriceUsd.let(::usd)} → ${position.effectiveExitPriceUsd?.let(::usd) ?: "—"}")
            Text("Gross ${position.grossPnlUsd?.let(::usd) ?: "UNKNOWN"} · net ${position.netPnlUsd?.let(::usd) ?: "UNKNOWN"} (${position.netPnlPct?.let(::pct) ?: "—"})")
            Text("Fees ${usd(position.entryFeeUsd + (position.exitFeeUsd ?: 0.0))} · simulated slippage ${usd(position.entrySimulatedSlippageUsd + (position.exitSimulatedSlippageUsd ?: 0.0))}")
            Text("MFE ${pct(position.maximumFavorableExcursionPct)} · MAE ${pct(position.maximumAdverseExcursionPct)} · max drawdown ${pct(position.maximumPositionDrawdownPct)}")
            Text("${if (position.exitAmbiguous) "Ambiguous TP/SL gap: conservative stop-loss rule applied. " else ""}No actual order or execution.")
        }
    }
}

@Composable
private fun PaperAuditRow(event: PaperTradeEventEntity) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${event.action} · ${event.signalState ?: "UNKNOWN"} · ${time(event.timestamp)}", style = MaterialTheme.typography.titleSmall)
            Text("${shortMint(event.mint)} · ${event.reason ?: "—"} · observed ${event.observedPriceUsd?.let(::usd) ?: "UNKNOWN"} · snapshot ${event.snapshotRef}")
            if (event.ambiguous) Text("Ambiguous gap: conservative stop-loss policy.", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Settings(pad: PaddingValues, key: String, onKey: (String) -> Unit, save: (String) -> Unit, start: () -> Unit) {
    Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SETTINGS", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(key, onKey, Modifier.fillMaxWidth(), label = { Text("PumpPortal API key") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Button(onClick = { save(key); start() }) { Text("Save and start scanner") }
        Text("The key is stored with Android encrypted storage. It is never logged or sent to our server.")
        Text("Subscriptions to token/account trades may be metered by PumpPortal.")
        Text("Paper Trading is local simulation only; it never submits real trades.")
        Text("Mock Mode: available for tests only; disabled in production builds.")
    }
}

@Composable
private fun StatusCard(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, modifier = Modifier.weight(1f))
            Text(value, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

private fun usd(value: Double): String = String.format(Locale.US, "$%.2f", value)
private fun pct(value: Double): String = String.format(Locale.US, "%.2f%%", value)
private fun Double.format(decimals: Int): String = String.format(Locale.US, "%.$decimals" + "f", this)
private fun shortMint(mint: String): String = if (mint.length > 12) "${mint.take(6)}…${mint.takeLast(4)}" else mint
private fun time(timestamp: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
private fun duration(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) "${hours}h ${minutes}m" else if (minutes > 0) "${minutes}m ${remainingSeconds}s" else "${remainingSeconds}s"
}
