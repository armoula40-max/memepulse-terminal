package com.solanasignal.paper

import com.solanasignal.data.PaperAccountEntity
import com.solanasignal.data.PaperTradeEntity
import com.solanasignal.data.PaperTradeEventEntity
import com.solanasignal.engineb.EngineBAxisLevel
import com.solanasignal.engineb.EngineBDataQuality
import com.solanasignal.engineb.EngineBLifecycleState
import com.solanasignal.engineb.EngineBResult
import com.solanasignal.engineb.EngineBSignalState
import com.solanasignal.network.FieldAvailability
import com.solanasignal.network.LiveMarketState
import com.solanasignal.network.MarketValue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val PAPER_TRADING_ENGINE_VERSION = "Paper Trading Engine v1.0"

/** One current Engine B result plus the canonical market snapshot available at that decision time. */
data class PaperMarketInput(
    val signalId: String,
    val result: EngineBResult,
    val marketState: LiveMarketState,
    val tokenSymbol: String? = null,
    /** Lifecycle snapshots can mark an existing position, but only trade events may open one. */
    val allowEntry: Boolean = true,
)

data class PaperTransition(
    val account: PaperAccountEntity,
    val insertedPosition: PaperTradeEntity? = null,
    /** Open mark or closed record; persistence updates only the mutable mark/exit columns. */
    val updatedPosition: PaperTradeEntity? = null,
    val auditEvents: List<PaperTradeEventEntity> = emptyList(),
    val decision: PaperDecisionStatus = PaperDecisionStatus.IGNORED,
    val reason: String? = null,
)

/**
 * A deterministic simulator only. It consumes the existing Engine B result and LiveMarketState;
 * it has no provider, wallet, order, signer, transaction, or RPC dependency.
 */
class PaperTradingEngine(val config: PaperTradingConfig = PaperTradingConfig()) {
    companion object {
        const val VERSION = PAPER_TRADING_ENGINE_VERSION
        const val ACCOUNT_ID = "default"
        private const val BPS = 10_000.0
        private val REMOVED_LIFECYCLES = setOf("REMOVED", "INVALID", "DELISTED", "UNAVAILABLE")
    }

    fun initialAccount(at: Long): PaperAccountEntity = PaperAccountEntity(
        accountId = ACCOUNT_ID,
        startingBalanceUsd = config.startingBalanceUsd,
        cashBalanceUsd = config.startingBalanceUsd,
        reservedOpenPositionValueUsd = 0.0,
        realizedPnlUsd = 0.0,
        totalFeesUsd = 0.0,
        totalSimulatedSlippageUsd = 0.0,
        tradeCount = 0,
        closedTradeCount = 0,
        winningTrades = 0,
        losingTrades = 0,
        currentEquityUsd = config.startingBalanceUsd,
        peakEquityUsd = config.startingBalanceUsd,
        maximumDrawdownPct = 0.0,
        valuationStatus = PaperValuationStatus.FRESH.name,
        createdAt = at,
        updatedAt = at,
    )

    /**
     * Apply exactly one point-in-time observation. For ambiguous TP/SL gaps, the documented
     * conservative rule is to close at the current observed price as STOP_LOSS, never an inferred
     * intrabar price or ordering. Thresholds are fixed defaults, not outcome-optimized values.
     */
    fun process(
        account: PaperAccountEntity,
        openPositions: List<PaperTradeEntity>,
        input: PaperMarketInput,
    ): PaperTransition {
        val state = input.marketState
        val result = input.result
        val asOf = result.timestamp
        if (input.signalId.isBlank()) return ignored(account, asOf, "missing_signal_id")
        if (state.observationTimestamp != asOf) return ignored(account, asOf, "signal_and_market_snapshot_timestamps_do_not_match")
        if (state.mint.isBlank() || asOf <= 0L) return ignored(account, asOf, "invalid_market_snapshot_identity_or_timestamp")

        val matchingOpen = openPositions.firstOrNull { it.mint == state.mint && it.status == PaperPositionStatus.OPEN.name }
        if (matchingOpen != null) {
            if (asOf <= matchingOpen.entryTimestamp || (matchingOpen.lastMarkTimestamp != null && asOf <= matchingOpen.lastMarkTimestamp)) {
                return ignored(account, asOf, "duplicate_or_out_of_order_market_observation")
            }
            return markOpenPosition(account, openPositions, matchingOpen, input)
        }

        if (!input.allowEntry) {
            return PaperTransition(account, decision = PaperDecisionStatus.IGNORED, reason = "entry_requires_trade_event")
        }

        val rejection = entryRejection(input)
        if (rejection != null) return PaperTransition(account = account, decision = PaperDecisionStatus.REJECTED, reason = rejection)

        val accountBeforeEntry = accountView(account, openPositions, asOf)
        val equity = accountBeforeEntry.currentEquityUsd
            ?: return PaperTransition(account, decision = PaperDecisionStatus.REJECTED, reason = "account_equity_unknown_or_stale")
        val cash = account.cashBalanceUsd
        val feeFraction = config.feeRateBps / BPS
        val sizeFraction = min(config.positionSizeFraction, config.maximumPositionFraction)
        val notional = min(equity * sizeFraction, cash / (1.0 + feeFraction))
        if (!notional.isFinite() || notional <= 0.0) {
            return PaperTransition(account, decision = PaperDecisionStatus.REJECTED, reason = "insufficient_virtual_cash")
        }

        val observedPrice = freshValue(state.priceUsd, asOf, requirePositive = true)
            ?: return PaperTransition(account, decision = PaperDecisionStatus.REJECTED, reason = "required_usd_price_unavailable_or_stale")
        val entrySlipBps = slippageBps(notional, freshValue(state.liquidityUsd, asOf, requirePositive = false)?.value)
        val effectiveEntry = observedPrice.value * (1.0 + entrySlipBps / BPS)
        if (!effectiveEntry.isFinite() || effectiveEntry <= 0.0) {
            return PaperTransition(account, decision = PaperDecisionStatus.REJECTED, reason = "invalid_effective_entry_price")
        }
        val quantity = notional / effectiveEntry
        val entryFee = notional * feeFraction
        val entrySlipUsd = quantity * (effectiveEntry - observedPrice.value)
        if (!quantity.isFinite() || quantity <= 0.0 || !entryFee.isFinite() || !entrySlipUsd.isFinite()) {
            return PaperTransition(account, decision = PaperDecisionStatus.REJECTED, reason = "invalid_paper_sizing_calculation")
        }

        val marketJson = marketSnapshotJson(state)
        val featureJson = featureSnapshotJson(result)
        val tradeId = "paper:${state.mint}:${input.signalId}"
        val excursionPct = (observedPrice.value / effectiveEntry - 1.0) * 100.0
        val initial = PaperTradeEntity(
            paperTradeId = tradeId,
            signalId = input.signalId,
            mint = state.mint,
            tokenSymbol = input.tokenSymbol?.takeIf { it.isNotBlank() },
            status = PaperPositionStatus.OPEN.name,
            entryTimestamp = asOf,
            entryPriceUsd = observedPrice.value,
            entryPriceObservedAt = observedPrice.observedAt,
            entryMarketCapUsd = freshValue(state.marketCapUsd, asOf, requirePositive = false)?.value,
            entryLiquidityUsd = freshValue(state.liquidityUsd, asOf, requirePositive = false)?.value,
            entrySignalState = result.signalState.name,
            entryPumpPotential = result.pumpPotential.level.name,
            entryPumpEvidenceJson = stringArrayJson(result.pumpPotential.evidence + result.pumpPotential.reasons),
            entryCollapseRisk = result.collapseRisk.level.name,
            entryCollapseEvidenceJson = stringArrayJson(result.collapseRisk.evidence + result.collapseRisk.reasons),
            entryMarketCapQuality = "UNKNOWN",
            entryDataFreshness = result.freshnessAssessment.status.name,
            entrySnapshotRef = state.eventId,
            entryMarketStateJson = marketJson,
            entryFeaturesJson = featureJson,
            entryNotionalUsd = notional,
            tokenQuantity = quantity,
            entryFeeUsd = entryFee,
            entrySimulatedSlippageBps = entrySlipBps,
            entrySimulatedSlippageUsd = entrySlipUsd,
            effectiveEntryPriceUsd = effectiveEntry,
            lastObservedPriceUsd = observedPrice.value,
            currentPriceUsd = observedPrice.value,
            currentMarketCapUsd = freshValue(state.marketCapUsd, asOf, requirePositive = false)?.value,
            currentLiquidityUsd = freshValue(state.liquidityUsd, asOf, requirePositive = false)?.value,
            currentValueUsd = quantity * observedPrice.value,
            unrealizedPnlUsd = null,
            unrealizedPnlPct = null,
            valuationStatus = PaperValuationStatus.FRESH.name,
            lastMarkTimestamp = asOf,
            lastMarketSnapshotRef = state.eventId,
            lastMarketStateJson = marketJson,
            currentSignalState = result.signalState.name,
            currentPumpPotential = result.pumpPotential.level.name,
            currentCollapseRisk = result.collapseRisk.level.name,
            peakPriceUsd = observedPrice.value,
            troughPriceUsd = observedPrice.value,
            maximumFavorableExcursionPct = max(0.0, excursionPct),
            maximumAdverseExcursionPct = min(0.0, excursionPct),
            currentDrawdownFromPeakPct = 0.0,
            maximumPositionDrawdownPct = 0.0,
            exitTimestamp = null,
            exitReason = null,
            exitPriceUsd = null,
            exitMarketSnapshotRef = null,
            exitMarketStateJson = null,
            exitSignalState = null,
            exitNotionalUsd = null,
            exitSimulatedSlippageBps = null,
            exitSimulatedSlippageUsd = null,
            effectiveExitPriceUsd = null,
            exitFeeUsd = null,
            grossPnlUsd = null,
            netPnlUsd = null,
            grossPnlPct = null,
            netPnlPct = null,
            holdingDurationMs = null,
            exitAmbiguous = false,
        )
        val initialMark = unrealizedPnl(initial, observedPrice.value, initial.currentLiquidityUsd)
        val enteredPosition = initial.copy(
            unrealizedPnlUsd = initialMark.netPnl,
            unrealizedPnlPct = initialMark.netPnl?.div(notional + entryFee)?.times(100.0),
        )
        val newAccount = account.copy(
            cashBalanceUsd = cash - notional - entryFee,
            totalFeesUsd = account.totalFeesUsd + entryFee,
            totalSimulatedSlippageUsd = account.totalSimulatedSlippageUsd + entrySlipUsd,
            tradeCount = account.tradeCount + 1,
            updatedAt = asOf,
        )
        val allOpenAfterEntry = openPositions + enteredPosition
        val accountAfterEntry = recalculateAccount(newAccount, allOpenAfterEntry, asOf)
        val entryEvent = auditEvent(
            id = "ENTRY:$tradeId",
            position = enteredPosition,
            input = input,
            action = "ENTRY",
            reason = "ENGINE_B_${result.signalState.name}",
            observedPrice = observedPrice.value,
            observedPriceAt = observedPrice.observedAt,
            ambiguous = false,
        )
        return PaperTransition(
            account = accountAfterEntry,
            insertedPosition = enteredPosition,
            auditEvents = listOf(entryEvent),
            decision = PaperDecisionStatus.ENTERED,
            reason = "eligible_engine_b_signal",
        )
    }

    /** Build a time-aware terminal view; old marks become stale without reusing their price/P&L. */
    fun terminalSnapshot(
        account: PaperAccountEntity?,
        openPositions: List<PaperTradeEntity>,
        closedTrades: List<PaperTradeEntity>,
        recentEvents: List<PaperTradeEventEntity>,
        now: Long,
    ): PaperTerminalSnapshot {
        if (account == null) return PaperTerminalSnapshot(
            engineVersion = VERSION,
            account = null,
            openPositions = emptyList(),
            closedTrades = closedTrades,
            recentEvents = recentEvents,
            currentEquityUsd = null,
            unrealizedPnlUsd = null,
            totalPnlUsd = null,
            winRatePct = null,
            valuationStatus = PaperValuationStatus.UNKNOWN,
            asOfTimestamp = now,
        )
        val viewPositions = openPositions.map { positionView(it, now) }
        val state = valuationFor(viewPositions, now)
        val unrealized = if (state.first == PaperValuationStatus.FRESH) viewPositions.sumOf { it.unrealizedPnlUsd ?: 0.0 } else null
        val equity = if (state.first == PaperValuationStatus.FRESH) account.cashBalanceUsd + state.second else null
        val winRate = if (account.closedTradeCount > 0) account.winningTrades.toDouble() / account.closedTradeCount * 100.0 else null
        return PaperTerminalSnapshot(
            engineVersion = VERSION,
            account = account,
            openPositions = viewPositions,
            closedTrades = closedTrades,
            recentEvents = recentEvents,
            currentEquityUsd = equity,
            unrealizedPnlUsd = unrealized,
            totalPnlUsd = equity?.minus(account.startingBalanceUsd),
            winRatePct = winRate,
            valuationStatus = state.first,
            asOfTimestamp = now,
        )
    }

    private fun markOpenPosition(
        account: PaperAccountEntity,
        allOpen: List<PaperTradeEntity>,
        position: PaperTradeEntity,
        input: PaperMarketInput,
    ): PaperTransition {
        val state = input.marketState
        val result = input.result
        val asOf = result.timestamp
        val marketJson = marketSnapshotJson(state)
        val observed = freshValue(state.priceUsd, asOf, requirePositive = true)
        if (observed == null) {
            val staleStatus = if (state.priceUsd.availability == FieldAvailability.STALE ||
                state.priceUsd.freshnessMs?.let { it > config.marketFreshnessLimitMs } == true
            ) PaperValuationStatus.STALE else PaperValuationStatus.UNKNOWN
            val stalePosition = position.copy(
                currentPriceUsd = null,
                currentMarketCapUsd = freshValue(state.marketCapUsd, asOf, requirePositive = false)?.value,
                currentLiquidityUsd = freshValue(state.liquidityUsd, asOf, requirePositive = false)?.value,
                currentValueUsd = null,
                unrealizedPnlUsd = null,
                unrealizedPnlPct = null,
                valuationStatus = staleStatus.name,
                lastMarkTimestamp = asOf,
                lastMarketSnapshotRef = state.eventId,
                lastMarketStateJson = marketJson,
                currentSignalState = result.signalState.name,
                currentPumpPotential = result.pumpPotential.level.name,
                currentCollapseRisk = result.collapseRisk.level.name,
                currentDrawdownFromPeakPct = null,
            )
            val event = auditEvent(
                id = "MARK:${position.paperTradeId}:${input.signalId}",
                position = stalePosition,
                input = input,
                action = "MARK",
                reason = if (staleStatus == PaperValuationStatus.STALE) "stale_price_no_fabricated_mark" else "unknown_or_future_price_no_fabricated_mark",
                observedPrice = null,
                observedPriceAt = state.priceUsd.observationTimestamp.takeIf { it <= asOf },
                ambiguous = false,
            )
            val openAfter = allOpen.map { if (it.paperTradeId == position.paperTradeId) stalePosition else it }
            return PaperTransition(
                account = recalculateAccount(account, openAfter, asOf),
                updatedPosition = stalePosition,
                auditEvents = listOf(event),
                decision = PaperDecisionStatus.MARKED,
                reason = event.reason,
            )
        }

        val peak = max(position.peakPriceUsd, observed.value)
        val trough = min(position.troughPriceUsd, observed.value)
        val mfe = max(position.maximumFavorableExcursionPct, max(0.0, (peak / position.effectiveEntryPriceUsd - 1.0) * 100.0))
        val mae = min(position.maximumAdverseExcursionPct, min(0.0, (trough / position.effectiveEntryPriceUsd - 1.0) * 100.0))
        val drawdown = if (peak > 0.0) (observed.value / peak - 1.0) * 100.0 else null
        val maxDrawdown = max(position.maximumPositionDrawdownPct, drawdown?.let { max(0.0, -it) } ?: 0.0)
        val currentMarketCap = freshValue(state.marketCapUsd, asOf, requirePositive = false)?.value
        val currentLiquidity = freshValue(state.liquidityUsd, asOf, requirePositive = false)?.value
        val value = position.tokenQuantity * observed.value
        val marked = position.copy(
            lastObservedPriceUsd = observed.value,
            currentPriceUsd = observed.value,
            currentMarketCapUsd = currentMarketCap,
            currentLiquidityUsd = currentLiquidity,
            currentValueUsd = value,
            valuationStatus = PaperValuationStatus.FRESH.name,
            lastMarkTimestamp = asOf,
            lastMarketSnapshotRef = state.eventId,
            lastMarketStateJson = marketJson,
            currentSignalState = result.signalState.name,
            currentPumpPotential = result.pumpPotential.level.name,
            currentCollapseRisk = result.collapseRisk.level.name,
            peakPriceUsd = peak,
            troughPriceUsd = trough,
            maximumFavorableExcursionPct = mfe,
            maximumAdverseExcursionPct = mae,
            currentDrawdownFromPeakPct = drawdown,
            maximumPositionDrawdownPct = maxDrawdown,
        )
        val calculatedUnrealized = unrealizedPnl(marked, observed.value, currentLiquidity)
        val updated = marked.copy(
            currentValueUsd = calculatedUnrealized.currentGrossValue,
            unrealizedPnlUsd = calculatedUnrealized.netPnl,
            unrealizedPnlPct = calculatedUnrealized.netPnl?.div(position.entryNotionalUsd + position.entryFeeUsd)?.times(100.0),
        )
        val exitReason = exitReason(position, updated, input, observed.value)
        if (exitReason == null) {
            val event = auditEvent(
                id = "MARK:${position.paperTradeId}:${input.signalId}",
                position = updated,
                input = input,
                action = "MARK",
                reason = "fresh_market_mark",
                observedPrice = observed.value,
                observedPriceAt = observed.observedAt,
                ambiguous = false,
            )
            val openAfter = allOpen.map { if (it.paperTradeId == position.paperTradeId) updated else it }
            return PaperTransition(
                account = recalculateAccount(account, openAfter, asOf),
                updatedPosition = updated,
                auditEvents = duplicateEntryEventIfNeeded(updated, input, event),
                decision = PaperDecisionStatus.MARKED,
                reason = "fresh_market_mark",
            )
        }

        val exitSlipBps = slippageBps(position.tokenQuantity * observed.value, currentLiquidity)
        val effectiveExit = observed.value * (1.0 - exitSlipBps / BPS)
        if (!effectiveExit.isFinite() || effectiveExit <= 0.0) {
            return PaperTransition(account, decision = PaperDecisionStatus.REJECTED, reason = "invalid_effective_exit_price")
        }
        val exitNotional = position.tokenQuantity * effectiveExit
        val exitFee = exitNotional * config.feeRateBps / BPS
        val gross = exitNotional - position.entryNotionalUsd
        val net = gross - position.entryFeeUsd - exitFee
        val exitSlipUsd = position.tokenQuantity * (observed.value - effectiveExit)
        val closed = updated.copy(
            status = PaperPositionStatus.CLOSED.name,
            currentValueUsd = position.tokenQuantity * observed.value,
            unrealizedPnlUsd = 0.0,
            unrealizedPnlPct = 0.0,
            exitTimestamp = asOf,
            exitReason = exitReason.first.name,
            exitPriceUsd = observed.value,
            exitMarketSnapshotRef = state.eventId,
            exitMarketStateJson = marketJson,
            exitSignalState = result.signalState.name,
            exitNotionalUsd = exitNotional,
            exitSimulatedSlippageBps = exitSlipBps,
            exitSimulatedSlippageUsd = exitSlipUsd,
            effectiveExitPriceUsd = effectiveExit,
            exitFeeUsd = exitFee,
            grossPnlUsd = gross,
            netPnlUsd = net,
            grossPnlPct = gross / position.entryNotionalUsd * 100.0,
            netPnlPct = net / (position.entryNotionalUsd + position.entryFeeUsd) * 100.0,
            holdingDurationMs = (asOf - position.entryTimestamp).coerceAtLeast(0L),
            exitAmbiguous = exitReason.second,
        )
        val cashAfter = account.cashBalanceUsd + exitNotional - exitFee
        val accountAfterClose = recalculateAccount(
            account.copy(
                cashBalanceUsd = cashAfter,
                realizedPnlUsd = account.realizedPnlUsd + net,
                totalFeesUsd = account.totalFeesUsd + exitFee,
                totalSimulatedSlippageUsd = account.totalSimulatedSlippageUsd + exitSlipUsd,
                closedTradeCount = account.closedTradeCount + 1,
                winningTrades = account.winningTrades + if (net > 0.0) 1 else 0,
                losingTrades = account.losingTrades + if (net < 0.0) 1 else 0,
                updatedAt = asOf,
            ),
            allOpen.filterNot { it.paperTradeId == position.paperTradeId },
            asOf,
        )
        val markEvent = auditEvent(
            id = "MARK:${position.paperTradeId}:${input.signalId}",
            position = updated,
            input = input,
            action = "MARK",
            reason = "fresh_market_mark_before_exit",
            observedPrice = observed.value,
            observedPriceAt = observed.observedAt,
            ambiguous = exitReason.second,
        )
        val exitEvent = auditEvent(
            id = "EXIT:${position.paperTradeId}:${input.signalId}",
            position = closed,
            input = input,
            action = "EXIT",
            reason = if (exitReason.second) "${exitReason.first.name}: ambiguous TP/SL gap; conservative current observed price used" else exitReason.first.name,
            observedPrice = observed.value,
            observedPriceAt = observed.observedAt,
            ambiguous = exitReason.second,
        )
        return PaperTransition(
            account = accountAfterClose,
            updatedPosition = closed,
            auditEvents = duplicateEntryEventIfNeeded(closed, input, markEvent) + exitEvent,
            decision = PaperDecisionStatus.EXITED,
            reason = exitReason.first.name,
        )
    }

    private fun duplicateEntryEventIfNeeded(
        position: PaperTradeEntity,
        input: PaperMarketInput,
        markEvent: PaperTradeEventEntity,
    ): List<PaperTradeEventEntity> {
        if (input.result.signalState !in config.eligibleEntryStates || input.result.signalState in FORBIDDEN_ENTRY_STATES) return listOf(markEvent)
        return listOf(markEvent, auditEvent(
            id = "DUPLICATE:${position.paperTradeId}:${input.signalId}",
            position = position,
            input = input,
            action = "IGNORED_DUPLICATE_ENTRY",
            reason = "one_open_position_per_mint",
            observedPrice = freshValue(input.marketState.priceUsd, input.result.timestamp, requirePositive = true)?.value,
            observedPriceAt = input.marketState.priceUsd.observationTimestamp,
            ambiguous = false,
        ))
    }

    private fun entryRejection(input: PaperMarketInput): String? {
        val result = input.result
        val state = input.marketState
        if (result.signalState in FORBIDDEN_ENTRY_STATES) return "engine_b_signal_not_entry_eligible_${result.signalState.name.lowercase()}"
        if (result.signalState !in config.eligibleEntryStates) return "engine_b_signal_not_in_configured_entry_states"
        if (result.collapseRisk.level != EngineBAxisLevel.LOW) return "engine_b_collapse_risk_not_low"
        if (result.pumpPotential.level in setOf(EngineBAxisLevel.NOT_ASSESSED, EngineBAxisLevel.UNASSESSED)) return "engine_b_pump_potential_unassessed"
        if (result.dataQuality in setOf(EngineBDataQuality.STALE, EngineBDataQuality.INSUFFICIENT)) return "engine_b_data_stale_or_insufficient"
        if (result.freshnessAssessment.status in setOf(
                com.solanasignal.engineb.EngineBFreshnessStatus.STALE,
                com.solanasignal.engineb.EngineBFreshnessStatus.UNKNOWN,
                com.solanasignal.engineb.EngineBFreshnessStatus.INSUFFICIENT,
            )) return "engine_b_freshness_not_usable"
        if (result.lifecycleState in setOf(EngineBLifecycleState.STALE, EngineBLifecycleState.REMOVED)) return "engine_b_lifecycle_not_entry_eligible"
        if (state.lifecycle.trim().uppercase() in REMOVED_LIFECYCLES) return "invalid_or_removed_market_state"
        if (freshValue(state.priceUsd, result.timestamp, requirePositive = true) == null) return "required_usd_price_unavailable_or_stale"
        return null
    }

    private fun exitReason(
        original: PaperTradeEntity,
        marked: PaperTradeEntity,
        input: PaperMarketInput,
        currentPrice: Double,
    ): Pair<PaperExitReason, Boolean>? {
        val result = input.result
        val lifecycleRemoved = input.marketState.lifecycle.trim().uppercase() in REMOVED_LIFECYCLES ||
            result.lifecycleState == EngineBLifecycleState.REMOVED
        if (lifecycleRemoved && config.exitOnInvalidState) return PaperExitReason.INVALID_STATE to false
        if (result.signalState == EngineBSignalState.RISK && config.exitOnRisk) return PaperExitReason.RISK_ESCALATION to false
        if (result.signalState == EngineBSignalState.STALE && config.exitOnStaleSignal) return PaperExitReason.STALE_DATA_EXIT to false
        if (result.signalState == EngineBSignalState.WEAKENING && config.exitOnWeakening) return PaperExitReason.SIGNAL_WEAKENING to false

        val tp = config.takeProfitFraction?.let { original.effectiveEntryPriceUsd * (1.0 + it) }
        val sl = config.stopLossFraction?.let { original.effectiveEntryPriceUsd * (1.0 - it) }
        val previous = original.lastObservedPriceUsd ?: original.entryPriceUsd
        if (tp != null && sl != null && min(previous, currentPrice) <= sl && max(previous, currentPrice) >= tp) {
            return PaperExitReason.STOP_LOSS to true
        }
        if (sl != null && currentPrice <= sl) return PaperExitReason.STOP_LOSS to false
        if (tp != null && currentPrice >= tp) return PaperExitReason.TAKE_PROFIT to false
        val holding = (input.result.timestamp - original.entryTimestamp).coerceAtLeast(0L)
        if (config.maximumHoldingTimeMs != null && holding >= config.maximumHoldingTimeMs) {
            return PaperExitReason.MAXIMUM_HOLDING_TIME to false
        }
        return null
    }

    private data class FreshValue(val value: Double, val observedAt: Long, val ageMs: Long)

    private fun freshValue(value: MarketValue<Double>, asOf: Long, requirePositive: Boolean): FreshValue? {
        if (value.availability !in setOf(FieldAvailability.KNOWN, FieldAvailability.ZERO)) return null
        if (value.observationTimestamp > asOf) return null
        if (value.providerTimestamp?.let { it > asOf } == true || value.receivedTimestamp?.let { it > asOf } == true) return null
        val ageBase = value.providerTimestamp ?: value.receivedTimestamp ?: value.observationTimestamp
        val age = (asOf - ageBase).coerceAtLeast(0L)
        if (age > config.marketFreshnessLimitMs) return null
        val number = value.value?.takeIf { it.isFinite() } ?: return null
        if (requirePositive && number <= 0.0) return null
        if (!requirePositive && number < 0.0) return null
        return FreshValue(number, value.observationTimestamp, age)
    }

    private fun slippageBps(notionalUsd: Double, liquidityUsd: Double?): Double {
        val adjustment = if (!config.liquidityAwareSlippage || liquidityUsd == null || liquidityUsd <= 0.0) 0.0 else {
            min(config.maximumLiquidityAdjustmentBps, notionalUsd / liquidityUsd * config.liquidityImpactBpsPerNotionalFraction)
        }
        return config.fixedSlippageBps + adjustment
    }

    private data class Unrealized(val currentGrossValue: Double, val netPnl: Double?)

    private fun unrealizedPnl(position: PaperTradeEntity, observedPrice: Double, liquidityUsd: Double?): Unrealized {
        val grossValue = position.tokenQuantity * observedPrice
        val exitSlip = slippageBps(grossValue, liquidityUsd)
        val effectiveExit = observedPrice * (1.0 - exitSlip / BPS)
        if (!effectiveExit.isFinite() || effectiveExit <= 0.0) return Unrealized(grossValue, null)
        val proceeds = position.tokenQuantity * effectiveExit
        val exitFee = proceeds * config.feeRateBps / BPS
        return Unrealized(grossValue, proceeds - exitFee - position.entryNotionalUsd - position.entryFeeUsd)
    }

    private fun recalculateAccount(account: PaperAccountEntity, open: List<PaperTradeEntity>, asOf: Long): PaperAccountEntity {
        val view = valuationFor(open, asOf)
        val reserved = open.sumOf { it.entryNotionalUsd }
        val equity = if (view.first == PaperValuationStatus.FRESH) account.cashBalanceUsd + view.second else null
        val priorPeak = account.peakEquityUsd
        val peak = if (equity != null) max(priorPeak ?: account.startingBalanceUsd, equity) else priorPeak
        val drawdown = if (equity != null && peak != null && peak > 0.0) max(account.maximumDrawdownPct, (peak - equity).coerceAtLeast(0.0) / peak * 100.0) else account.maximumDrawdownPct
        return account.copy(
            reservedOpenPositionValueUsd = reserved,
            currentEquityUsd = equity,
            peakEquityUsd = peak,
            maximumDrawdownPct = drawdown,
            valuationStatus = view.first.name,
            updatedAt = asOf,
        )
    }

    /** second value is net liquidation value; a single unknown/stale holding makes equity unknown. */
    private fun valuationFor(open: List<PaperTradeEntity>, asOf: Long): Pair<PaperValuationStatus, Double> {
        if (open.isEmpty()) return PaperValuationStatus.FRESH to 0.0
        var liquidationValue = 0.0
        for (position in open) {
            val markAt = position.lastMarkTimestamp
            if (markAt == null || markAt > asOf) return PaperValuationStatus.UNKNOWN to 0.0
            if (asOf - markAt > config.marketFreshnessLimitMs) return PaperValuationStatus.STALE to 0.0
            if (position.valuationStatus != PaperValuationStatus.FRESH.name || position.currentPriceUsd == null) {
                val status = runCatching { PaperValuationStatus.valueOf(position.valuationStatus) }.getOrDefault(PaperValuationStatus.UNKNOWN)
                return status to 0.0
            }
            val liquidity = position.currentLiquidityUsd
            val slip = slippageBps(position.entryNotionalUsd, liquidity)
            val effectiveExit = position.currentPriceUsd * (1.0 - slip / BPS)
            if (!effectiveExit.isFinite() || effectiveExit <= 0.0) return PaperValuationStatus.UNKNOWN to 0.0
            val proceeds = position.tokenQuantity * effectiveExit
            liquidationValue += proceeds - proceeds * config.feeRateBps / BPS
        }
        return PaperValuationStatus.FRESH to liquidationValue
    }

    private fun positionView(position: PaperTradeEntity, now: Long): PaperTradeEntity {
        val markAt = position.lastMarkTimestamp
        if (position.status != PaperPositionStatus.OPEN.name) return position
        if (markAt == null || markAt > now) return position.copy(
            currentPriceUsd = null, currentValueUsd = null, unrealizedPnlUsd = null, unrealizedPnlPct = null,
            valuationStatus = PaperValuationStatus.UNKNOWN.name, currentDrawdownFromPeakPct = null,
        )
        if (now - markAt > config.marketFreshnessLimitMs || position.valuationStatus != PaperValuationStatus.FRESH.name) return position.copy(
            currentPriceUsd = null, currentValueUsd = null, unrealizedPnlUsd = null, unrealizedPnlPct = null,
            valuationStatus = if (now - markAt > config.marketFreshnessLimitMs) PaperValuationStatus.STALE.name else position.valuationStatus,
            currentDrawdownFromPeakPct = null,
        )
        return position
    }

    private fun ignored(account: PaperAccountEntity, at: Long, reason: String) =
        PaperTransition(account.copy(updatedAt = max(account.updatedAt, at)), decision = PaperDecisionStatus.IGNORED, reason = reason)

    private fun auditEvent(
        id: String,
        position: PaperTradeEntity,
        input: PaperMarketInput,
        action: String,
        reason: String?,
        observedPrice: Double?,
        observedPriceAt: Long?,
        ambiguous: Boolean,
    ) = PaperTradeEventEntity(
        eventId = id,
        paperTradeId = position.paperTradeId,
        mint = position.mint,
        timestamp = input.result.timestamp,
        action = action,
        snapshotRef = input.marketState.eventId,
        observedPriceUsd = observedPrice,
        observedPriceTimestamp = observedPriceAt,
        signalState = input.result.signalState.name,
        reason = reason,
        ambiguous = ambiguous,
        eventSnapshotJson = eventSnapshotJson(input, reason, ambiguous),
    )

    private fun eventSnapshotJson(input: PaperMarketInput, reason: String?, ambiguous: Boolean): String =
        Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("paperEngineVersion", VERSION)
            put("engineBVersion", input.result.engineVersion)
            put("signalId", input.signalId)
            put("signalState", input.result.signalState.name)
            put("pumpPotential", input.result.pumpPotential.level.name)
            put("collapseRisk", input.result.collapseRisk.level.name)
            put("reason", reason ?: "unknown")
            put("ambiguous", ambiguous)
            put("marketState", marketSnapshotJsonObject(input.marketState))
        })

    private fun marketSnapshotJson(state: LiveMarketState): String =
        Json.encodeToString(JsonObject.serializer(), marketSnapshotJsonObject(state))

    private fun marketSnapshotJsonObject(state: LiveMarketState): JsonObject = buildJsonObject {
        put("mint", state.mint)
        put("lifecycle", state.lifecycle)
        put("providerTimestamp", state.providerTimestamp?.let(::JsonPrimitive) ?: JsonNull)
        put("receivedTimestamp", state.receivedTimestamp)
        put("observationTimestamp", state.observationTimestamp)
        put("eventId", state.eventId)
        put("priceUsd", marketValueJson(state.priceUsd))
        put("marketCapUsd", marketValueJson(state.marketCapUsd))
        put("liquidityUsd", marketValueJson(state.liquidityUsd))
        put("holders", marketValueJson(state.holders))
        put("priceNative", marketValueJson(state.priceNative))
        put("marketCapNative", marketValueJson(state.marketCapNative))
        put("tokenAmount", marketValueJson(state.tokenAmount))
        put("solAmount", marketValueJson(state.solAmount))
    }

    private fun <T : Number> marketValueJson(value: MarketValue<T>): JsonObject = buildJsonObject {
        val numeric = value.value?.toDouble()?.takeIf { it.isFinite() }
        put("value", numeric?.let(::JsonPrimitive) ?: JsonNull)
        put("availability", value.availability.name)
        put("providerTimestamp", value.providerTimestamp?.let(::JsonPrimitive) ?: JsonNull)
        put("receivedTimestamp", value.receivedTimestamp?.let(::JsonPrimitive) ?: JsonNull)
        put("observationTimestamp", value.observationTimestamp)
        put("freshnessMs", value.freshnessMs?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun featureSnapshotJson(result: EngineBResult): String {
        val features = result.featuresUsed + result.liquidityAssessment.features + result.safetyAssessment.features + result.freshnessAssessment.features
        val json = buildJsonArray { features.forEach { feature ->
            add(buildJsonObject {
                put("name", feature.name)
                put("value", feature.value?.toDouble()?.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: JsonNull)
                put("availability", feature.availability.name)
                put("riskAvailability", feature.riskAvailability?.name?.let(::JsonPrimitive) ?: JsonNull)
                put("categoryValue", feature.categoryValue?.let(::JsonPrimitive) ?: JsonNull)
                put("sourceType", feature.sourceType.name)
                put("sourceLabel", feature.sourceLabel?.let(::JsonPrimitive) ?: JsonNull)
                put("observationTimestamp", feature.observationTimestamp?.let(::JsonPrimitive) ?: JsonNull)
                put("freshnessMs", feature.freshnessMs?.let(::JsonPrimitive) ?: JsonNull)
                put("windowSeconds", feature.windowSeconds?.let(::JsonPrimitive) ?: JsonNull)
                put("elapsedMs", feature.elapsedMs?.let(::JsonPrimitive) ?: JsonNull)
            })
        } }
        return Json.encodeToString(JsonArray.serializer(), json)
    }

    private fun stringArrayJson(values: List<String>): String =
        Json.encodeToString(JsonArray.serializer(), JsonArray(values.distinct().map(::JsonPrimitive)))

    private fun accountView(account: PaperAccountEntity, open: List<PaperTradeEntity>, asOf: Long): PaperTerminalSnapshot {
        val equity = valuationFor(open, asOf)
        val currentEquity = if (equity.first == PaperValuationStatus.FRESH) account.cashBalanceUsd + equity.second else null
        val unrealized = if (currentEquity != null) currentEquity - account.cashBalanceUsd - open.sumOf { it.entryNotionalUsd } - open.sumOf { it.entryFeeUsd } else null
        return PaperTerminalSnapshot(
            engineVersion = VERSION,
            account = account,
            openPositions = open,
            closedTrades = emptyList(),
            recentEvents = emptyList(),
            currentEquityUsd = currentEquity,
            unrealizedPnlUsd = unrealized,
            totalPnlUsd = currentEquity?.minus(account.startingBalanceUsd),
            winRatePct = null,
            valuationStatus = equity.first,
            asOfTimestamp = asOf,
        )
    }

    private val FORBIDDEN_ENTRY_STATES = setOf(
        EngineBSignalState.NO_SIGNAL,
        EngineBSignalState.STALE,
        EngineBSignalState.RISK,
        EngineBSignalState.WEAKENING,
    )
}
