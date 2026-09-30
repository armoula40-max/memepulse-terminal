package com.solanasignal.paper

import androidx.room.withTransaction
import com.solanasignal.data.PaperAccountEntity
import com.solanasignal.data.PaperTradeEntity
import com.solanasignal.data.PaperTradeEventEntity
import com.solanasignal.data.SignalDatabase
import kotlinx.coroutines.flow.Flow

/** Room adapter for the pure paper reducer. All mutations are local virtual-account records only. */
class PaperTradingRepository(
    private val database: SignalDatabase,
    val config: PaperTradingConfig = PaperTradingConfig(),
) {
    private val dao = database.dao()
    private val engine = PaperTradingEngine(config)

    val account: Flow<PaperAccountEntity?> = dao.paperAccountFlow(PaperTradingEngine.ACCOUNT_ID)
    val openPositions: Flow<List<PaperTradeEntity>> = dao.openPaperTradesFlow()
    val closedTrades: Flow<List<PaperTradeEntity>> = dao.closedPaperTradesFlow()
    val recentEvents: Flow<List<PaperTradeEventEntity>> = dao.paperTradeEventsFlow()

    suspend fun initialize(at: Long = System.currentTimeMillis()): PaperAccountEntity = database.withTransaction {
        dao.insertPaperAccount(engine.initialAccount(at))
        dao.paperAccount(PaperTradingEngine.ACCOUNT_ID) ?: error("Paper account initialization failed")
    }

    /** The same signal/snapshot pair is idempotent; trade rows and audit events commit atomically. */
    suspend fun process(input: PaperMarketInput): PaperTransition = database.withTransaction {
        dao.insertPaperAccount(engine.initialAccount(input.result.timestamp))
        val currentAccount = dao.paperAccount(PaperTradingEngine.ACCOUNT_ID)
            ?: error("Paper account is unavailable")
        if (dao.paperTradeBySignal(input.signalId) != null) {
            return@withTransaction PaperTransition(
                account = currentAccount,
                decision = PaperDecisionStatus.IGNORED,
                reason = "duplicate_signal_id",
            )
        }

        val open = dao.openPaperTrades()
        val transition = engine.process(currentAccount, open, input)
        if (transition.insertedPosition == null && transition.updatedPosition == null && transition.auditEvents.isEmpty()) {
            return@withTransaction transition
        }

        transition.auditEvents.forEach { event ->
            if (dao.paperTradeEvent(event.eventId) != null) {
                return@withTransaction PaperTransition(
                    account = currentAccount,
                    decision = PaperDecisionStatus.IGNORED,
                    reason = "duplicate_observation_event",
                )
            }
        }

        transition.insertedPosition?.let { position ->
            check(dao.insertPaperTrade(position) != -1L) { "Duplicate paper signal/position in transaction" }
        }

        transition.updatedPosition?.let { position ->
            val updated = if (position.status == PaperPositionStatus.CLOSED.name) {
                dao.closePaperTrade(
                    paperTradeId = position.paperTradeId,
                    lastObservedPriceUsd = position.lastObservedPriceUsd,
                    currentPriceUsd = position.currentPriceUsd,
                    currentMarketCapUsd = position.currentMarketCapUsd,
                    currentLiquidityUsd = position.currentLiquidityUsd,
                    currentValueUsd = position.currentValueUsd,
                    valuationStatus = position.valuationStatus,
                    lastMarkTimestamp = position.lastMarkTimestamp,
                    lastMarketSnapshotRef = position.lastMarketSnapshotRef,
                    lastMarketStateJson = position.lastMarketStateJson,
                    currentSignalState = position.currentSignalState,
                    currentPumpPotential = position.currentPumpPotential,
                    currentCollapseRisk = position.currentCollapseRisk,
                    peakPriceUsd = position.peakPriceUsd,
                    troughPriceUsd = position.troughPriceUsd,
                    maximumFavorableExcursionPct = position.maximumFavorableExcursionPct,
                    maximumAdverseExcursionPct = position.maximumAdverseExcursionPct,
                    currentDrawdownFromPeakPct = position.currentDrawdownFromPeakPct,
                    maximumPositionDrawdownPct = position.maximumPositionDrawdownPct,
                    exitTimestamp = position.exitTimestamp ?: error("Closed position lacks exit timestamp"),
                    exitReason = position.exitReason ?: error("Closed position lacks exit reason"),
                    exitPriceUsd = position.exitPriceUsd ?: error("Closed position lacks observed exit price"),
                    exitMarketSnapshotRef = position.exitMarketSnapshotRef ?: error("Closed position lacks exit snapshot reference"),
                    exitMarketStateJson = position.exitMarketStateJson ?: error("Closed position lacks exit snapshot"),
                    exitSignalState = position.exitSignalState ?: error("Closed position lacks exit signal"),
                    exitNotionalUsd = position.exitNotionalUsd ?: error("Closed position lacks exit notional"),
                    exitSimulatedSlippageBps = position.exitSimulatedSlippageBps ?: error("Closed position lacks simulated slippage"),
                    exitSimulatedSlippageUsd = position.exitSimulatedSlippageUsd ?: error("Closed position lacks simulated slippage amount"),
                    effectiveExitPriceUsd = position.effectiveExitPriceUsd ?: error("Closed position lacks effective exit price"),
                    exitFeeUsd = position.exitFeeUsd ?: error("Closed position lacks exit fee"),
                    grossPnlUsd = position.grossPnlUsd ?: error("Closed position lacks gross P&L"),
                    netPnlUsd = position.netPnlUsd ?: error("Closed position lacks net P&L"),
                    grossPnlPct = position.grossPnlPct ?: error("Closed position lacks gross P&L percent"),
                    netPnlPct = position.netPnlPct ?: error("Closed position lacks net P&L percent"),
                    holdingDurationMs = position.holdingDurationMs ?: error("Closed position lacks holding duration"),
                    exitAmbiguous = position.exitAmbiguous,
                )
            } else {
                dao.updateOpenPaperMark(
                    paperTradeId = position.paperTradeId,
                    lastObservedPriceUsd = position.lastObservedPriceUsd,
                    currentPriceUsd = position.currentPriceUsd,
                    currentMarketCapUsd = position.currentMarketCapUsd,
                    currentLiquidityUsd = position.currentLiquidityUsd,
                    currentValueUsd = position.currentValueUsd,
                    unrealizedPnlUsd = position.unrealizedPnlUsd,
                    unrealizedPnlPct = position.unrealizedPnlPct,
                    valuationStatus = position.valuationStatus,
                    lastMarkTimestamp = position.lastMarkTimestamp,
                    lastMarketSnapshotRef = position.lastMarketSnapshotRef,
                    lastMarketStateJson = position.lastMarketStateJson,
                    currentSignalState = position.currentSignalState,
                    currentPumpPotential = position.currentPumpPotential,
                    currentCollapseRisk = position.currentCollapseRisk,
                    peakPriceUsd = position.peakPriceUsd,
                    troughPriceUsd = position.troughPriceUsd,
                    maximumFavorableExcursionPct = position.maximumFavorableExcursionPct,
                    maximumAdverseExcursionPct = position.maximumAdverseExcursionPct,
                    currentDrawdownFromPeakPct = position.currentDrawdownFromPeakPct,
                    maximumPositionDrawdownPct = position.maximumPositionDrawdownPct,
                )
            }
            check(updated == 1) { "Paper position was already closed or no longer updateable" }
        }

        transition.auditEvents.forEach { event ->
            check(dao.insertPaperTradeEvent(event) != -1L) { "Duplicate paper audit event in transaction" }
        }
        dao.updatePaperAccount(transition.account)
        transition
    }

    fun terminalSnapshot(
        account: PaperAccountEntity?,
        open: List<PaperTradeEntity>,
        closed: List<PaperTradeEntity>,
        events: List<PaperTradeEventEntity>,
        now: Long,
    ): PaperTerminalSnapshot = engine.terminalSnapshot(account, open, closed, events, now)

    companion object {
        const val VERSION = PAPER_TRADING_ENGINE_VERSION
    }
}
