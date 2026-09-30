package com.solanasignal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.solanasignal.data.SignalDatabase
import com.solanasignal.engine.SignalRepository
import com.solanasignal.paper.PaperTerminalSnapshot
import com.solanasignal.paper.PaperTradingRepository
import com.solanasignal.paper.PaperTradingEngine
import com.solanasignal.paper.PaperValuationStatus
import com.solanasignal.service.ScannerForegroundService
import com.solanasignal.service.ScannerRuntime
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SignalRepository(app)
    private val paperRepo = PaperTradingRepository(SignalDatabase.get(app))
    val state = ScannerRuntime.state
    val statusMessage = ScannerRuntime.message
    val diagnostics = repo.diagnostics
    val signals = repo.signals.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tokens = repo.tokens.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val paperClock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(5_000)
        }
    }
    val paperTerminal = combine(
        paperRepo.account,
        paperRepo.openPositions,
        paperRepo.closedTrades,
        paperRepo.recentEvents,
        paperClock,
    ) { account, open, closed, events, now ->
        paperRepo.terminalSnapshot(account, open, closed, events, now)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PaperTerminalSnapshot(
            engineVersion = PaperTradingEngine.VERSION,
            account = null,
            openPositions = emptyList(),
            closedTrades = emptyList(),
            recentEvents = emptyList(),
            currentEquityUsd = null,
            unrealizedPnlUsd = null,
            totalPnlUsd = null,
            winRatePct = null,
            valuationStatus = PaperValuationStatus.UNKNOWN,
            asOfTimestamp = System.currentTimeMillis(),
        ),
    )
    var keyConfigured = repo.apiKeyConfigured()
    init { viewModelScope.launch { paperRepo.initialize() } }
    fun saveKey(key: String) { repo.setApiKey(key); keyConfigured = key.isNotBlank() }
    fun start() { if (!repo.apiKeyConfigured()) { ScannerRuntime.update(com.solanasignal.network.ConnectionState.DISCONNECTED, "API key is missing") ; return }; ContextCompat.startForegroundService(getApplication(), Intent(getApplication(), ScannerForegroundService::class.java)) }
    fun stop() { getApplication<Application>().stopService(Intent(getApplication(), ScannerForegroundService::class.java)); ScannerRuntime.update(com.solanasignal.network.ConnectionState.DISCONNECTED, "Scanner stopped") }
}
