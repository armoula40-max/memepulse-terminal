package com.solanasignal.paper

import com.solanasignal.data.PaperAccountEntity
import com.solanasignal.data.PaperTradeEntity
import com.solanasignal.engineb.EngineBAxisLevel
import com.solanasignal.engineb.EngineBDataQuality
import com.solanasignal.engineb.EngineBLifecycleState
import com.solanasignal.engineb.EngineBSignalState
import com.solanasignal.network.FieldAvailability
import com.solanasignal.network.MarketValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperTradingEngineTest {
    private val baseConfig = PaperTradingConfig(
        startingBalanceUsd = 10_000.0,
        positionSizeFraction = 0.01,
        maximumPositionFraction = 0.25,
        feeRateBps = 0.0,
        fixedSlippageBps = 0.0,
        liquidityAwareSlippage = false,
        liquidityImpactBpsPerNotionalFraction = 0.0,
        maximumLiquidityAdjustmentBps = 0.0,
        takeProfitFraction = 0.20,
        stopLossFraction = 0.10,
        maximumHoldingTimeMs = null,
    )

    private fun engine(config: PaperTradingConfig = baseConfig) = PaperTradingEngine(config)
    private fun account(engine: PaperTradingEngine, at: Long = PaperTradingFixtures.T0) = engine.initialAccount(at)

    @Test fun eligibleEntryUsesActualEngineBSignalAndOnlyConsumesConfiguredVirtualCapital() {
        val engine = engine()
        val input = PaperTradingFixtures.input()
        assertEquals(EngineBSignalState.ENTRY_CANDIDATE, input.result.signalState)
        assertEquals(EngineBAxisLevel.LOW, input.result.collapseRisk.level)
        assertTrue(input.result.pumpPotential.level !in setOf(EngineBAxisLevel.NOT_ASSESSED, EngineBAxisLevel.UNASSESSED))

        val opened = engine.process(account(engine), emptyList(), input)
        val position = opened.insertedPosition!!
        assertEquals(PaperDecisionStatus.ENTERED, opened.decision)
        assertEquals(100.0, position.entryNotionalUsd, 0.000001)
        assertTrue(position.entryNotionalUsd < opened.account.startingBalanceUsd)
        assertEquals(1, opened.account.tradeCount)
        assertEquals("ENTRY_CANDIDATE", position.entrySignalState)
        assertEquals("LOW", position.entryCollapseRisk)
        assertEquals("UNKNOWN", position.entryMarketCapQuality)
        assertTrue(position.entryFeaturesJson.contains("priceUsd"))
        assertTrue(position.entryMarketStateJson.contains(input.marketState.eventId))
        assertEquals("ENTRY", opened.auditEvents.single().action)
    }

    @Test fun configuredLiquidityAwareSlippageIsBoundedAndExplicit() {
        val engine = engine(PaperTradingConfig(
            startingBalanceUsd = 10_000.0,
            positionSizeFraction = 0.01,
            feeRateBps = 0.0,
            fixedSlippageBps = 50.0,
            liquidityAwareSlippage = true,
            liquidityImpactBpsPerNotionalFraction = 10_000.0,
            maximumLiquidityAdjustmentBps = 200.0,
            takeProfitFraction = null,
            stopLossFraction = null,
            maximumHoldingTimeMs = null,
        ))
        val opened = engine.process(account(engine), emptyList(), PaperTradingFixtures.input())
        val position = opened.insertedPosition!!
        assertEquals(70.0, position.entrySimulatedSlippageBps, 0.000001)
        assertTrue(position.entrySimulatedSlippageUsd > 0.0)
        assertEquals(position.entryNotionalUsd, 100.0, 0.000001)
        assertEquals(position.entryPriceUsd * 1.007, position.effectiveEntryPriceUsd, 0.000001)
    }

    @Test fun missingLiquidityStaysUnknownAndDoesNotInventImpact() {
        val engine = engine(PaperTradingConfig(
            startingBalanceUsd = 10_000.0,
            positionSizeFraction = 0.01,
            feeRateBps = 0.0,
            fixedSlippageBps = 50.0,
            liquidityAwareSlippage = true,
            liquidityImpactBpsPerNotionalFraction = 10_000.0,
            maximumLiquidityAdjustmentBps = 200.0,
        ))
        val opened = engine.process(
            account(engine),
            emptyList(),
            PaperTradingFixtures.input(liquidityUsd = null, riskLevel = EngineBAxisLevel.LOW),
        )
        val position = opened.insertedPosition!!
        assertNull(position.entryLiquidityUsd)
        assertEquals(50.0, position.entrySimulatedSlippageBps, 0.000001)
        assertTrue(position.entryMarketStateJson.contains("\"availability\":\"UNKNOWN\""))
    }

    @Test fun riskStaleAndNoSignalAreNeverEntryEligible() {
        val engine = engine()
        val original = account(engine)
        val risky = PaperTradingFixtures.input(signalState = EngineBSignalState.ENTRY_CANDIDATE, riskLevel = EngineBAxisLevel.HIGH)
        val stale = PaperTradingFixtures.input(
            signalState = EngineBSignalState.ENTRY_CANDIDATE,
            priceOverride = MarketValue.of(1.0, PaperTradingFixtures.T0 - 70_000, PaperTradingFixtures.T0 - 70_000, PaperTradingFixtures.T0),
        )
        val noSignal = PaperTradingFixtures.input(signalState = EngineBSignalState.NO_SIGNAL)
        for (input in listOf(risky, stale, noSignal)) {
            val result = engine.process(original, emptyList(), input)
            assertNull(result.insertedPosition)
            assertEquals(PaperDecisionStatus.REJECTED, result.decision)
        }
    }

    @Test fun buildingAndWatchAreOptInAndUnsafeStatesCannotBeAddedToConfig() {
        val engine = engine()
        val building = PaperTradingFixtures.input(signalState = EngineBSignalState.BUILDING)
        assertEquals(PaperDecisionStatus.REJECTED, engine.process(account(engine), emptyList(), building).decision)
        val configured = engine(baseConfig.copy(eligibleEntryStates = setOf(EngineBSignalState.BUILDING)))
        assertEquals(PaperDecisionStatus.ENTERED, configured.process(account(configured), emptyList(), building).decision)
        val invalid = runCatching { baseConfig.copy(eligibleEntryStates = setOf(EngineBSignalState.RISK)) }
        assertTrue(invalid.isFailure)
    }

    @Test fun missingOrFutureUsdPriceCannotOpenPosition() {
        val engine = engine()
        val t = PaperTradingFixtures.T0
        val unknown = MarketValue.unknown<Double>(observationTimestamp = t)
        val missingPrice = PaperTradingFixtures.input(priceOverride = unknown)
        assertEquals(PaperDecisionStatus.REJECTED, engine.process(account(engine), emptyList(), missingPrice).decision)

        val futurePrice = MarketValue.of(1.0, t + 1, t, t)
        assertEquals(FieldAvailability.KNOWN, futurePrice.availability)
        val future = PaperTradingFixtures.input(priceOverride = futurePrice)
        val rejected = engine.process(account(engine), emptyList(), future)
        assertEquals(PaperDecisionStatus.REJECTED, rejected.decision)
        assertTrue(rejected.reason!!.contains("price"))
    }

    @Test fun oneOpenPositionPerMintAndRepeatedObservationAreIdempotent() {
        val engine = engine()
        val first = PaperTradingFixtures.input()
        val opened = engine.process(account(engine), emptyList(), first)
        val duplicateMint = PaperTradingFixtures.input(at = PaperTradingFixtures.T0 + 10_000, priceUsd = 1.05, id = "second-signal")
        val second = engine.process(opened.account, listOf(opened.insertedPosition!!), duplicateMint)
        assertNull(second.insertedPosition)
        assertEquals(PaperDecisionStatus.MARKED, second.decision)
        assertTrue(second.auditEvents.any { it.action == "IGNORED_DUPLICATE_ENTRY" })

        val sameTime = PaperTradingFixtures.input(at = PaperTradingFixtures.T0, priceUsd = 1.1, id = "same-time")
        val replay = engine.process(opened.account, listOf(opened.insertedPosition!!), sameTime)
        assertEquals(PaperDecisionStatus.IGNORED, replay.decision)
        assertTrue(replay.reason!!.contains("out_of_order"))
    }

    @Test fun lifecycleSnapshotCannotOpenButCanRefreshAnExistingPosition() {
        val engine = engine()
        val initialAccount = account(engine)
        val lifecycleOnly = PaperTradingFixtures.input(allowEntry = false, id = "create-lifecycle")
        val rejected = engine.process(initialAccount, emptyList(), lifecycleOnly)
        assertEquals(PaperDecisionStatus.IGNORED, rejected.decision)
        assertEquals("entry_requires_trade_event", rejected.reason)
        assertNull(rejected.insertedPosition)

        val opened = engine.process(initialAccount, emptyList(), PaperTradingFixtures.input())
        val update = PaperTradingFixtures.input(
            at = PaperTradingFixtures.T0 + 5_000,
            priceUsd = 1.05,
            signalState = EngineBSignalState.WATCH,
            id = "migration-mark",
            allowEntry = false,
        )
        val marked = engine.process(opened.account, listOf(opened.insertedPosition!!), update)
        assertEquals(PaperDecisionStatus.MARKED, marked.decision)
        assertEquals(1.05, marked.updatedPosition!!.currentPriceUsd!!, 0.0)
        assertNull(marked.insertedPosition)
        assertEquals(1, marked.account.tradeCount)
    }

    @Test fun takeProfitClosesAtObservedPriceAndPersistsNetPnl() {
        val engine = engine()
        val opened = engine.process(account(engine), emptyList(), PaperTradingFixtures.input())
        val position = opened.insertedPosition!!
        val exited = engine.process(
            opened.account,
            listOf(position),
            PaperTradingFixtures.input(at = PaperTradingFixtures.T0 + 20_000, priceUsd = 1.25, signalState = EngineBSignalState.WATCH, id = "take-profit"),
        )
        val closed = exited.updatedPosition!!
        assertEquals(PaperDecisionStatus.EXITED, exited.decision)
        assertEquals("CLOSED", closed.status)
        assertEquals(PaperExitReason.TAKE_PROFIT.name, closed.exitReason)
        assertEquals(1.25, closed.exitPriceUsd!!, 0.0)
        assertEquals(25.0, closed.netPnlUsd!!, 0.000001)
        assertEquals(10_025.0, exited.account.cashBalanceUsd, 0.000001)
        assertEquals(25.0, exited.account.realizedPnlUsd, 0.000001)
        assertEquals(1, exited.account.closedTradeCount)
        assertEquals(1, exited.account.winningTrades)
        assertTrue(exited.auditEvents.any { it.action == "EXIT" })
    }

    @Test fun stopLossRecordsLossAndUpdatesAccountDrawdown() {
        val engine = engine()
        val opened = engine.process(account(engine), emptyList(), PaperTradingFixtures.input())
        val exited = engine.process(
            opened.account,
            listOf(opened.insertedPosition!!),
            PaperTradingFixtures.input(at = PaperTradingFixtures.T0 + 20_000, priceUsd = 0.85, signalState = EngineBSignalState.WATCH, id = "stop-loss"),
        )
        assertEquals(PaperExitReason.STOP_LOSS.name, exited.updatedPosition!!.exitReason)
        assertEquals(-15.0, exited.updatedPosition.netPnlUsd!!, 0.000001)
        assertEquals(1, exited.account.losingTrades)
        assertTrue(exited.account.maximumDrawdownPct > 0.0)
    }

    @Test fun feesAndEntryExitSlippageAreDistinctAndIncludedInNetPnl() {
        val engine = engine(PaperTradingConfig(
            startingBalanceUsd = 10_000.0,
            positionSizeFraction = 0.01,
            feeRateBps = 100.0,
            fixedSlippageBps = 100.0,
            liquidityAwareSlippage = false,
            takeProfitFraction = null,
            stopLossFraction = null,
            maximumHoldingTimeMs = null,
        ))
        val opened = engine.process(account(engine), emptyList(), PaperTradingFixtures.input())
        val position = opened.insertedPosition!!
        assertEquals(1.0, position.entryFeeUsd, 0.000001)
        assertTrue(position.entrySimulatedSlippageUsd > 0.0)
        val closed = engine.process(
            opened.account,
            listOf(position),
            PaperTradingFixtures.input(at = PaperTradingFixtures.T0 + 20_000, priceUsd = 1.0, signalState = EngineBSignalState.WEAKENING, id = "fee-exit"),
        )
        val trade = closed.updatedPosition!!
        assertEquals(PaperExitReason.SIGNAL_WEAKENING.name, trade.exitReason)
        assertTrue(trade.exitFeeUsd!! > 0.0)
        assertTrue(trade.exitSimulatedSlippageUsd!! > 0.0)
        assertTrue(trade.netPnlUsd!! < trade.grossPnlUsd!!)
        assertEquals(trade.entryFeeUsd + trade.exitFeeUsd!!, closed.account.totalFeesUsd, 0.000001)
        assertEquals(trade.entrySimulatedSlippageUsd + trade.exitSimulatedSlippageUsd!!, closed.account.totalSimulatedSlippageUsd, 0.000001)
    }

    @Test fun weakeningAndRiskSignalsExitOnTheCurrentObservedPrice() {
        val engine = engine()
        for ((signal, expected) in listOf(
            EngineBSignalState.WEAKENING to PaperExitReason.SIGNAL_WEAKENING,
            EngineBSignalState.RISK to PaperExitReason.RISK_ESCALATION,
        )) {
            val opened = engine.process(account(engine), emptyList(), PaperTradingFixtures.input(mint = "mint-$signal"))
            val exited = engine.process(
                opened.account,
                listOf(opened.insertedPosition!!),
                PaperTradingFixtures.input(
                    mint = "mint-$signal",
                    at = PaperTradingFixtures.T0 + 5_000,
                    priceUsd = 0.99,
                    signalState = signal,
                    riskLevel = if (signal == EngineBSignalState.RISK) EngineBAxisLevel.HIGH else null,
                    id = "exit-$signal",
                ),
            )
            assertEquals(expected.name, exited.updatedPosition!!.exitReason)
            assertEquals(0.99, exited.updatedPosition.exitPriceUsd!!, 0.0)
        }
    }

    @Test fun staleSignalCanOnlyExitWhenEnabledAndPriceItselfIsFresh() {
        val engine = engine(baseConfig.copy(exitOnStaleSignal = true))
        val opened = engine.process(account(engine), emptyList(), PaperTradingFixtures.input())
        val staleSignal = PaperTradingFixtures.input(
            at = PaperTradingFixtures.T0 + 5_000,
            priceUsd = 0.99,
            signalState = EngineBSignalState.STALE,
            id = "stale-signal-fresh-price",
        )
        val exited = engine.process(opened.account, listOf(opened.insertedPosition!!), staleSignal)
        assertEquals(PaperExitReason.STALE_DATA_EXIT.name, exited.updatedPosition!!.exitReason)
        assertEquals(0.99, exited.updatedPosition.exitPriceUsd!!, 0.0)
    }

    @Test fun invalidStateExitUsesOnlyFreshObservedUsdPrice() {
        val engine = engine()
        val opened = engine.process(account(engine), emptyList(), PaperTradingFixtures.input())
        val removed = PaperTradingFixtures.input(
            at = PaperTradingFixtures.T0 + 5_000,
            priceUsd = 0.98,
            signalState = EngineBSignalState.NO_SIGNAL,
            lifecycle = "REMOVED",
            id = "removed-fresh-price",
        )
        val exited = engine.process(opened.account, listOf(opened.insertedPosition!!), removed)
        assertEquals(PaperExitReason.INVALID_STATE.name, exited.updatedPosition!!.exitReason)
        assertEquals(0.98, exited.updatedPosition.exitPriceUsd!!, 0.0)
    }

    @Test fun maximumHoldingTimeClosesAtTheNextFreshObservation() {
        val engine = engine(baseConfig.copy(maximumHoldingTimeMs = 10_000L))
        val opened = engine.process(account(engine), emptyList(), PaperTradingFixtures.input())
        val mark = engine.process(
            opened.account,
            listOf(opened.insertedPosition!!),
            PaperTradingFixtures.input(at = PaperTradingFixtures.T0 + 10_000, priceUsd = 1.02, signalState = EngineBSignalState.WATCH, id = "max-hold"),
        )
        assertEquals(PaperExitReason.MAXIMUM_HOLDING_TIME.name, mark.updatedPosition!!.exitReason)
        assertEquals(10_000L, mark.updatedPosition.holdingDurationMs)
    }

    @Test fun stalePositionNeverCarriesForwardAProfitableMarkAsCurrent() {
        val engine = engine()
        val opened = engine.process(account(engine), emptyList(), PaperTradingFixtures.input())
        val t = PaperTradingFixtures.T0 + 20_000
        val old = MarketValue.of(1.5, t - 70_000, t - 70_000, t)
        val stale = PaperTradingFixtures.input(
            at = t,
            priceUsd = 1.5,
            signalState = EngineBSignalState.WATCH,
            priceOverride = old,
            id = "stale-position",
        )
        val update = engine.process(opened.account, listOf(opened.insertedPosition!!), stale)
        val position = update.updatedPosition!!
        assertEquals(PaperValuationStatus.STALE.name, position.valuationStatus)
        assertNull(position.currentPriceUsd)
        assertNull(position.currentValueUsd)
        assertNull(position.unrealizedPnlUsd)
        assertEquals(1.0, position.lastObservedPriceUsd!!, 0.0)
        assertNull(update.account.currentEquityUsd)
    }

    @Test fun idlePositionsBecomeStaleInTheTimeAwareTerminalSnapshot() {
        val engine = engine()
        val opened = engine.process(account(engine), emptyList(), PaperTradingFixtures.input())
        val view = engine.terminalSnapshot(
            account = opened.account,
            openPositions = listOf(opened.insertedPosition!!),
            closedTrades = emptyList(),
            recentEvents = opened.auditEvents,
            now = PaperTradingFixtures.T0 + 60_001,
        )
        assertEquals(PaperValuationStatus.STALE, view.valuationStatus)
        assertNull(view.currentEquityUsd)
        assertNull(view.totalPnlUsd)
        assertNull(view.openPositions.single().currentPriceUsd)
        assertNull(view.openPositions.single().unrealizedPnlUsd)
    }

    @Test fun mfeMaeAndDrawdownAreUpdatedFromObservedMarksOnly() {
        val engine = engine(baseConfig.copy(takeProfitFraction = null, stopLossFraction = null))
        val opened = engine.process(account(engine), emptyList(), PaperTradingFixtures.input())
        val high = engine.process(
            opened.account,
            listOf(opened.insertedPosition!!),
            PaperTradingFixtures.input(at = PaperTradingFixtures.T0 + 5_000, priceUsd = 1.4, signalState = EngineBSignalState.WATCH, id = "high"),
        )
        val falling = engine.process(
            high.account,
            listOf(high.updatedPosition!!),
            PaperTradingFixtures.input(at = PaperTradingFixtures.T0 + 10_000, priceUsd = 0.9, signalState = EngineBSignalState.WEAKENING, id = "low"),
        )
        val position = falling.updatedPosition!!
        assertEquals(40.0, position.maximumFavorableExcursionPct, 0.000001)
        assertEquals(-10.0, position.maximumAdverseExcursionPct, 0.000001)
        assertEquals(-((1.4 - 0.9) / 1.4) * 100.0, position.currentDrawdownFromPeakPct!!, 0.000001)
        assertTrue(position.maximumPositionDrawdownPct > 35.0)
        assertEquals(PaperExitReason.SIGNAL_WEAKENING.name, position.exitReason)
    }

    @Test fun ambiguousGapUsesConservativeStopLossAtCurrentObservedPrice() {
        val priorPolicy = engine(baseConfig.copy(takeProfitFraction = null, stopLossFraction = null))
        val opened = priorPolicy.process(account(priorPolicy), emptyList(), PaperTradingFixtures.input())
        val priorMark = priorPolicy.process(
            opened.account,
            listOf(opened.insertedPosition!!),
            PaperTradingFixtures.input(at = PaperTradingFixtures.T0 + 5_000, priceUsd = 1.3, signalState = EngineBSignalState.WATCH, id = "gap-prior"),
        )
        val updatedPolicy = engine(baseConfig.copy(takeProfitFraction = 0.20, stopLossFraction = 0.10))
        val gap = updatedPolicy.process(
            priorMark.account,
            listOf(priorMark.updatedPosition!!),
            PaperTradingFixtures.input(at = PaperTradingFixtures.T0 + 10_000, priceUsd = 0.8, signalState = EngineBSignalState.WATCH, id = "gap-current"),
        )
        assertEquals(PaperExitReason.STOP_LOSS.name, gap.updatedPosition!!.exitReason)
        assertTrue(gap.updatedPosition.exitAmbiguous)
        assertEquals(0.8, gap.updatedPosition.exitPriceUsd!!, 0.0)
        assertTrue(gap.auditEvents.any { it.action == "EXIT" && it.ambiguous })
    }

    @Test fun entrySnapshotsRemainImmutableAfterLaterMarketAndSignalObservations() {
        val engine = engine(baseConfig.copy(takeProfitFraction = null, stopLossFraction = null))
        val opened = engine.process(account(engine), emptyList(), PaperTradingFixtures.input())
        val entry = opened.insertedPosition!!
        var balance = opened.account
        var position = entry
        listOf(30_000L, 60_000L, 300_000L, 3_600_000L).forEachIndexed { index, offset ->
            val later = engine.process(
                balance,
                listOf(position),
                PaperTradingFixtures.input(
                    at = PaperTradingFixtures.T0 + offset,
                    priceUsd = 1.0 + (index + 1) * 0.01,
                    signalState = EngineBSignalState.WATCH,
                    id = "future-$offset",
                ),
            )
            assertEquals(PaperDecisionStatus.MARKED, later.decision)
            balance = later.account
            position = later.updatedPosition!!
            assertEquals(entry.entryTimestamp, position.entryTimestamp)
            assertEquals(entry.entryPriceUsd, position.entryPriceUsd, 0.0)
            assertEquals(entry.entryMarketStateJson, position.entryMarketStateJson)
            assertEquals(entry.entryFeaturesJson, position.entryFeaturesJson)
            assertEquals(entry.entrySignalState, position.entrySignalState)
            assertEquals(entry.entryPumpPotential, position.entryPumpPotential)
            assertEquals(entry.entryCollapseRisk, position.entryCollapseRisk)
        }
        assertNotEquals(entry.lastMarketStateJson, position.lastMarketStateJson)
        assertEquals(3_600_000L, position.lastMarkTimestamp!! - entry.entryTimestamp)
    }

    @Test fun deterministicReplayProducesIdenticalTradeAndAccountResults() {
        fun replay(): Pair<PaperAccountEntity, PaperTradeEntity> {
            val engine = engine()
            val opened = engine.process(account(engine), emptyList(), PaperTradingFixtures.input())
            val closed = engine.process(
                opened.account,
                listOf(opened.insertedPosition!!),
                PaperTradingFixtures.input(at = PaperTradingFixtures.T0 + 20_000, priceUsd = 1.25, signalState = EngineBSignalState.WATCH, id = "replay-exit"),
            )
            return closed.account to closed.updatedPosition!!
        }
        assertEquals(replay(), replay())
    }

    @Test fun accountDrawdownTracksPeakToTroughAndOpenValueUnknownBlocksNewEntry() {
        val engine = engine()
        val first = engine.process(account(engine), emptyList(), PaperTradingFixtures.input(mint = "winner"))
        val won = engine.process(
            first.account,
            listOf(first.insertedPosition!!),
            PaperTradingFixtures.input(mint = "winner", at = PaperTradingFixtures.T0 + 5_000, priceUsd = 1.25, signalState = EngineBSignalState.WATCH, id = "winner-exit"),
        )
        assertTrue(won.account.currentEquityUsd!! > 10_000.0)
        val second = engine.process(
            won.account,
            emptyList(),
            PaperTradingFixtures.input(mint = "loser", at = PaperTradingFixtures.T0 + 10_000, priceUsd = 1.0, id = "loser-entry"),
        )
        val loss = engine.process(
            second.account,
            listOf(second.insertedPosition!!),
            PaperTradingFixtures.input(mint = "loser", at = PaperTradingFixtures.T0 + 15_000, priceUsd = 0.85, signalState = EngineBSignalState.WATCH, id = "loser-exit"),
        )
        assertTrue(loss.account.maximumDrawdownPct > 0.0)
        assertTrue(loss.account.peakEquityUsd!! >= loss.account.currentEquityUsd!!)
        assertEquals(2, loss.account.tradeCount)
        assertEquals(2, loss.account.closedTradeCount)
        assertEquals(1, loss.account.winningTrades)
        assertEquals(1, loss.account.losingTrades)
    }

    @Test fun engineBUnknownPumpOrRiskAxesCannotBeSilentlyUpgradedToAnEntry() {
        val engine = engine()
        val candidate = PaperTradingFixtures.input()
        val unassessedPump = candidate.copy(result = candidate.result.copy(
            pumpPotential = com.solanasignal.engineb.EngineBAxisResult(EngineBAxisLevel.NOT_ASSESSED),
        ))
        assertEquals(PaperDecisionStatus.REJECTED, engine.process(account(engine), emptyList(), unassessedPump).decision)
        val unassessedRisk = candidate.copy(result = candidate.result.copy(
            collapseRisk = com.solanasignal.engineb.EngineBAxisResult(EngineBAxisLevel.UNASSESSED),
        ))
        assertEquals(PaperDecisionStatus.REJECTED, engine.process(account(engine), emptyList(), unassessedRisk).decision)
    }

    @Test fun staleInputCannotForceAnExitAtItsRetainedOldValue() {
        val engine = engine()
        val opened = engine.process(account(engine), emptyList(), PaperTradingFixtures.input())
        val t = PaperTradingFixtures.T0 + 5_000
        val stale = PaperTradingFixtures.input(
            at = t,
            priceUsd = 0.4,
            signalState = EngineBSignalState.RISK,
            riskLevel = EngineBAxisLevel.HIGH,
            priceOverride = MarketValue.of(0.4, t - 120_000, t - 120_000, t),
            id = "stale-risk",
        )
        val update = engine.process(opened.account, listOf(opened.insertedPosition!!), stale)
        assertEquals(PaperPositionStatus.OPEN.name, update.updatedPosition!!.status)
        assertNull(update.updatedPosition.exitPriceUsd)
        assertNull(update.updatedPosition.currentPriceUsd)
        assertNull(update.updatedPosition.unrealizedPnlUsd)
        assertEquals(1.0, update.updatedPosition.lastObservedPriceUsd!!, 0.0)
    }
}
