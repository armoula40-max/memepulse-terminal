package com.solanasignal

import android.content.*
import android.net.Uri
import android.os.Bundle
import android.text.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.solanasignal.network.Diagnostics
import com.solanasignal.service.ScannerForegroundService
import com.solanasignal.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm by viewModels<MainViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 700); enableEdgeToEdge(); setContent { SolanaSignalApp(vm) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SolanaSignalApp(vm: MainViewModel) {
    val state by vm.state.collectAsState(); val statusMessage by vm.statusMessage.collectAsState(); val diagnostics by vm.diagnostics.collectAsState(); val signals by vm.signals.collectAsState(); val tokens by vm.tokens.collectAsState(); var tab by remember { mutableIntStateOf(0) }; var key by remember { mutableStateOf("") }
    MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF65E6A7), background = androidx.compose.ui.graphics.Color(0xFF07100F), surface = androidx.compose.ui.graphics.Color(0xFF0D1B18))) {
        Scaffold(topBar = { TopAppBar(title = { Text("Solana Signal") }, actions = { Text(state.name, style = MaterialTheme.typography.labelSmall) }) }, bottomBar = { NavigationBar { listOf("Dashboard" to Icons.Default.Home, "Scanner" to Icons.Default.Search, "Signals" to Icons.Default.Notifications, "Settings" to Icons.Default.Settings).forEachIndexed { i, item -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(item.second, item.first) }, label = { Text(item.first) }) } } }) { pad -> when(tab) { 0 -> Dashboard(pad, statusMessage, diagnostics, tokens.size, signals.size); 1 -> Scanner(pad, tokens.size, statusMessage); 2 -> Signals(pad, signals); else -> Settings(pad, key, { key = it }, vm::saveKey, { vm.start(); }) } }
    }
}
@Composable private fun Dashboard(pad: PaddingValues, state: String, diagnostics: Diagnostics, tokens: Int, signals: Int) {
    LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("SIGNAL ENGINE", style = MaterialTheme.typography.headlineMedium) }
        item { Text("No trading. Manual Photon only.") }
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
@Composable private fun Scanner(pad: PaddingValues, tokens: Int, state: String) { Column(Modifier.padding(pad).padding(16.dp)) { Text("LIVE SCANNER", style = MaterialTheme.typography.headlineMedium); Text("Connection: $state"); Spacer(Modifier.height(16.dp)); Text(if (tokens == 0) "No verified live token data yet. Configure your API key and start the foreground scanner." else "$tokens tracked tokens") } }
@Composable private fun Signals(pad: PaddingValues, signals: List<com.solanasignal.data.SignalEntity>) { val context = androidx.compose.ui.platform.LocalContext.current; LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { Text("SIGNAL HISTORY", style = MaterialTheme.typography.headlineMedium) }; items(signals) { signal -> Card { Column(Modifier.padding(12.dp)) { Text(signal.signalType, style = MaterialTheme.typography.titleLarge); Text("Score ${signal.score}/100 · ${signal.mint}"); Text(if (signal.reasons.isBlank()) "Reasons: UNKNOWN" else signal.reasons); Text("Hypothetical only; no trade was executed.", style = MaterialTheme.typography.labelSmall); OutlinedButton(onClick = { com.solanasignal.ui.PhotonLauncher.open(context, signal.mint) }) { Text("Copy mint + open Photon") } } } } } }
@Composable private fun Settings(pad: PaddingValues, key: String, onKey: (String) -> Unit, save: (String) -> Unit, start: () -> Unit) { Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("SETTINGS", style = MaterialTheme.typography.headlineMedium); OutlinedTextField(key, onKey, Modifier.fillMaxWidth(), label = { Text("PumpPortal API key") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()); Button(onClick = { save(key); start() }) { Text("Save and start scanner") }; Text("The key is stored with Android encrypted storage. It is never logged or sent to our server."); Text("Subscriptions to token/account trades may be metered by PumpPortal."); Text("Mock Mode: available for tests only; disabled in production builds.") } }
@Composable private fun StatusCard(label: String, value: String) { Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Text(value, color = MaterialTheme.colorScheme.primary) } } }
