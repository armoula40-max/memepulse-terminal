package com.solanasignal.paper

import com.solanasignal.data.PaperAccountEntity
import com.solanasignal.data.PaperTradeEntity
import com.solanasignal.data.PaperTradeEventEntity
import com.solanasignal.engineb.EngineBSignalState

/**
 * Paper-only controls. Defaults are fixed infrastructure examples, not values fitted to history.
 * Slippage is always a simulation estimate; no order book, route, wallet, or execution is consulted.
 */
data class PaperTradingConfig(
    val startingBalanceUsd: Double = 10_000.0,
    val positionSizeFraction: Double = 0.01,
    val maximumPositionFraction: Double = 0.25,
    val feeRateBps: Double = 100.0,
    val fixedSlippageBps: Double = 50.0,
    val liquidityAwareSlippage: Boolean = true,
    val liquidityImpactBpsPerNotionalFraction: Double = 10_000.0,
    val maximumLiquidityAdjustmentBps: Double = 200.0,
    val takeProfitFraction: Double? = 0.25,
    val stopLossFraction: Double? = 0.15,
    val maximumHoldingTimeMs: Long? = 24L * 60 * 60 * 1_000,
    val exitOnWeakening: Boolean = true,
    val exitOnRisk: Boolean = true,
    val exitOnStaleSignal: Boolean = false,
    val exitOnInvalidState: Boolean = true,
    val eligibleEntryStates: Set<EngineBSignalState> = setOf(
        EngineBSignalState.ENTRY_CANDIDATE,
        EngineBSignalState.STRONG_CANDIDATE,
    ),
    val marketFreshnessLimitMs: Long = 60_000L,
) {
    init {
        require(startingBalanceUsd.isFinite() && startingBalanceUsd > 0.0)
        require(maximumPositionFraction.isFinite() && maximumPositionFraction in 0.0..1.0 && maximumPositionFraction < 1.0)
        require(positionSizeFraction.isFinite() && positionSizeFraction > 0.0 && positionSizeFraction <= maximumPositionFraction)
        require(feeRateBps.isFinite() && feeRateBps in 0.0..10_000.0)
        require(fixedSlippageBps.isFinite() && fixedSlippageBps in 0.0..5_000.0)
        require(liquidityImpactBpsPerNotionalFraction.isFinite() && liquidityImpactBpsPerNotionalFraction >= 0.0)
        require(maximumLiquidityAdjustmentBps.isFinite() && maximumLiquidityAdjustmentBps in 0.0..5_000.0)
        require(takeProfitFraction == null || (takeProfitFraction.isFinite() && takeProfitFraction > 0.0))
        require(stopLossFraction == null || (stopLossFraction.isFinite() && stopLossFraction in 0.0..0.99))
        require(maximumHoldingTimeMs == null || maximumHoldingTimeMs > 0L)
        require(marketFreshnessLimitMs > 0L)
        require(eligibleEntryStates.all { it in setOf(
            EngineBSignalState.ENTRY_CANDIDATE,
            EngineBSignalState.STRONG_CANDIDATE,
            EngineBSignalState.BUILDING,
            EngineBSignalState.WATCH,
        ) }) { "RISK, STALE, NO_SIGNAL, and WEAKENING can never be configured as entry states" }
    }
}

enum class PaperPositionStatus { OPEN, CLOSED, CANCELLED, INVALIDATED }
enum class PaperValuationStatus { FRESH, STALE, UNKNOWN }
enum class PaperExitReason {
    TAKE_PROFIT,
    STOP_LOSS,
    MAXIMUM_HOLDING_TIME,
    SIGNAL_WEAKENING,
    RISK_ESCALATION,
    STALE_DATA_EXIT,
    INVALID_STATE,
}
enum class PaperDecisionStatus { ENTERED, MARKED, EXITED, IGNORED, REJECTED }

/** Snapshot used by a future terminal view. Unknown equity is nullable, never carried forward as current. */
data class PaperTerminalSnapshot(
    val engineVersion: String,
    val account: PaperAccountEntity?,
    val openPositions: List<PaperTradeEntity>,
    val closedTrades: List<PaperTradeEntity>,
    val recentEvents: List<PaperTradeEventEntity>,
    val currentEquityUsd: Double?,
    val unrealizedPnlUsd: Double?,
    val totalPnlUsd: Double?,
    val winRatePct: Double?,
    val valuationStatus: PaperValuationStatus,
    val asOfTimestamp: Long,
)
