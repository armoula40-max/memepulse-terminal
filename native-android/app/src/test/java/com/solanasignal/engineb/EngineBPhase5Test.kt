package com.solanasignal.engineb

import com.solanasignal.network.FieldAvailability
import com.solanasignal.network.LiveMarketState
import com.solanasignal.network.MarketValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineBPhase5Test {
    private val asOf = 1_000_000L

    private fun marketValue(value: Double?, at: Long): MarketValue<Double> = MarketValue.of(value, at, at, at)

    private fun <T> observed(value: T, at: Long = asOf - 10_000): EngineBObservedValue<T> =
        EngineBObservedValue.available(value, at, source = "phase5_normalized_fixture")

    private fun state(liquidityUsd: Double? = 50_000.0, timestamp: Long = asOf): LiveMarketState =
        LiveMarketState.unknown(
            mint = "phase5-mint",
            lifecycle = "TRADING",
            providerTimestamp = timestamp,
            receivedTimestamp = timestamp,
            observationTimestamp = timestamp,
            providerSequence = null,
            eventId = "snapshot-$timestamp",
        ).copy(
            priceNative = marketValue(0.001, timestamp),
            priceUsd = marketValue(0.10, timestamp),
            marketCapNative = marketValue(10_000.0, timestamp),
            marketCapUsd = marketValue(1_000_000.0, timestamp),
            liquidityUsd = marketValue(liquidityUsd, timestamp),
            holders = MarketValue.of(1_000, timestamp, timestamp, timestamp),
        )

    private fun safeSafety(
        topHolder: Double = 8.0,
        topFive: Double = 22.0,
        topTen: Double = 35.0,
        mint: EngineBAuthorityStatus = EngineBAuthorityStatus.SAFE,
        freeze: EngineBAuthorityStatus = EngineBAuthorityStatus.SAFE,
        deployerRisk: EngineBDeployerRiskStatus = EngineBDeployerRiskStatus.NO_RISK_EVIDENCE,
    ) = EngineBSafetyContext(
        topHolderPercent = observed(topHolder),
        topFivePercent = observed(topFive),
        topTenPercent = observed(topTen),
        mintAuthority = observed(mint),
        freezeAuthority = observed(freeze),
        creatorIdentity = observed("creator-public-key"),
        deployerRisk = observed(deployerRisk),
    )

    private fun trade(
        id: Int,
        offsetMs: Long,
        side: EngineBTradeSide = EngineBTradeSide.BUY,
        priceUsd: Double = 0.01 * id,
        solAmount: Double = 1.0,
        tokenAmount: Double = 100.0,
        trader: String? = "wallet-$id",
    ): EngineBTradeObservation {
        val at = asOf + offsetMs
        return EngineBTradeObservation(
            eventId = "phase5-trade-$id",
            side = side,
            trader = trader,
            observationTimestamp = at,
            providerTimestamp = at,
            providerSequence = id.toLong(),
            solAmount = marketValue(solAmount, at),
            priceNative = marketValue(priceUsd / 100.0, at),
            priceUsd = marketValue(priceUsd, at),
            marketCapUsd = marketValue(priceUsd * 1_000_000.0, at),
            tokenAmount = marketValue(tokenAmount, at),
        )
    }

    private fun liquidityHistory(vararg observations: Pair<Long, Double>): List<EngineBLiquidityObservation> =
        observations.mapIndexed { index, (offsetMs, usd) ->
            val at = asOf + offsetMs
            EngineBLiquidityObservation(
                eventId = "liquidity-$index-$at",
                liquidityUsd = observed(usd, at),
            )
        }

    private fun input(
        trades: List<EngineBTradeObservation> = strongBuys(),
        currentLiquidity: Double? = 50_000.0,
        priorLiquidity: List<Pair<Long, Double>> = listOf(-10_000L to 50_000.0, -5_000L to 50_100.0),
        safety: EngineBSafetyContext = safeSafety(),
    ) = EngineBInput(
        marketState = state(currentLiquidity),
        tradeHistory = trades,
        liquidityHistory = liquidityHistory(*priorLiquidity.toTypedArray()),
        safetyContext = safety,
    )

    private fun strongBuys() = listOf(
        trade(1, -28_000, priceUsd = 0.01),
        trade(2, -18_000, priceUsd = 0.02),
        trade(3, -8_000, priceUsd = 0.04),
        trade(4, -1_000, priceUsd = 0.08),
    )

    private fun liquidityFeature(result: EngineBResult, name: String) =
        result.liquidityAssessment.features.single { it.name == name }

    private fun safetyFeature(result: EngineBResult, name: String) =
        result.safetyAssessment.features.single { it.name == name }

    private fun riskLevel(result: EngineBResult) = result.collapseRisk.level

    @Test fun strongMomentumAndObservedStableLiquidityProduceLowRiskOnlyWhenSafetyIsComplete() {
        val result = EngineB.evaluate(input())

        assertEquals(EngineBAxisLevel.HIGH, result.pumpPotential.level)
        assertEquals(EngineBAxisLevel.LOW, result.collapseRisk.level)
        assertEquals(EngineBSignalState.ENTRY_CANDIDATE, result.signalState)
        assertEquals(EngineBObservedAvailability.AVAILABLE, result.liquidityAssessment.availability)
        assertEquals(EngineBObservedAvailability.AVAILABLE, result.safetyAssessment.availability)
        assertEquals(EngineBFreshnessStatus.FRESH, result.freshnessAssessment.status)
        assertEquals(1_000.0, safetyFeature(result, "holderCount").value!!.toDouble(), 0.0)
        assertEquals(EngineBFeatureSource.SNAPSHOT_DERIVED, safetyFeature(result, "holderCount").sourceType)
        assertTrue((liquidityFeature(result, "liquidityStabilityRatio_300s").value!!.toDouble()) > 0.99)
        assertEquals(50_100.0, liquidityFeature(result, "liquidityUsdPrevious").value!!.toDouble(), 0.0)
        assertEquals(0.0, liquidityFeature(result, "liquidityVelocityUsdPerSecond_300s").value!!.toDouble(), 0.0)
        assertEquals(EngineBFeatureSource.LIQUIDITY_DERIVED, liquidityFeature(result, "liquidityChangePct_300s").sourceType)
        assertTrue(result.collapseRisk.reasons.any { it.contains("No configured collapse-risk indicators") })
    }

    @Test fun observedLiquidityPullbackCanExposeARecoveryFromTheRecordedTrough() {
        val result = EngineB.evaluate(
            input(
                priorLiquidity = listOf(-20_000L to 30_000.0, -10_000L to 10_000.0, -5_000L to 20_000.0),
                currentLiquidity = 25_000.0,
            ),
        )

        assertEquals(-16.6666666667, liquidityFeature(result, "liquidityChangePct_300s").value!!.toDouble(), 0.000001)
        assertEquals(-250.0, liquidityFeature(result, "liquidityVelocityUsdPerSecond_300s").value!!.toDouble(), 0.000001)
        assertEquals(150.0, liquidityFeature(result, "liquidityRecoveryPct_300s").value!!.toDouble(), 0.000001)
    }

    @Test fun rapidlyDeterioratingLiquidityRaisesRiskAndBlocksAnEntryCandidate() {
        val result = EngineB.evaluate(
            input(
                currentLiquidity = 20_000.0,
                priorLiquidity = listOf(-10_000L to 100_000.0, -5_000L to 60_000.0),
            ),
        )

        assertEquals(EngineBAxisLevel.HIGH, result.pumpPotential.level)
        assertEquals(EngineBAxisLevel.HIGH, riskLevel(result))
        assertEquals(EngineBSignalState.RISK, result.signalState)
        assertTrue(result.collapseRisk.evidence.contains("liquidityChangePct_300s"))
        assertTrue(result.collapseRisk.reasons.any { it.contains("Liquidity deteriorating") })
    }

    @Test fun severeObservedSellPressureDuringReversalRaisesCollapseRiskSeparatelyFromMomentum() {
        val trades = listOf(
            trade(1, -28_000, priceUsd = 0.03),
            trade(2, -25_000, priceUsd = 0.05),
            trade(3, -6_000, side = EngineBTradeSide.SELL, priceUsd = 0.04, solAmount = 3.0),
            trade(4, -5_000, side = EngineBTradeSide.SELL, priceUsd = 0.035, solAmount = 3.0),
            trade(5, -4_000, side = EngineBTradeSide.SELL, priceUsd = 0.03, solAmount = 3.0),
            trade(6, -3_000, side = EngineBTradeSide.SELL, priceUsd = 0.025, solAmount = 3.0),
            trade(7, -2_000, side = EngineBTradeSide.SELL, priceUsd = 0.02, solAmount = 3.0),
            trade(8, -1_000, side = EngineBTradeSide.SELL, priceUsd = 0.015, solAmount = 3.0),
        )
        val result = EngineB.evaluate(input(trades = trades))

        assertEquals(EngineBAxisLevel.HIGH, result.collapseRisk.level)
        assertEquals(EngineBSignalState.RISK, result.signalState)
        assertTrue(result.collapseRisk.evidence.contains("severeSellPressure_30s"))
        assertTrue(result.collapseRisk.reasons.any { it.contains("Severe sell pressure") })
        assertNotSameAxis(result.pumpPotential, result.collapseRisk)
    }

    @Test fun highHolderConcentrationIsExplicitModerateEvidenceNotAutomaticCriticalRisk() {
        val result = EngineB.evaluate(input(safety = safeSafety(topHolder = 42.0, topFive = 68.0, topTen = 84.0)))

        assertEquals(EngineBAxisLevel.MODERATE, riskLevel(result))
        assertFalse(riskLevel(result) == EngineBAxisLevel.CRITICAL)
        assertEquals(42.0, safetyFeature(result, "topHolderPercent").value!!.toDouble(), 0.0)
        assertTrue(result.collapseRisk.reasons.any { it.contains("High holder concentration observed") })
        assertEquals(EngineBSignalState.WATCH, result.signalState)
    }

    @Test fun observedConcentrationHistoryExposesTheTrendWithoutInventingMissingBuckets() {
        val safety = safeSafety(topHolder = 28.0, topFive = 38.0, topTen = 52.0).copy(
            concentrationHistory = listOf(
                EngineBHolderConcentrationObservation(
                    eventId = "holder-snapshot-before",
                    topHolderPercent = observed(7.0, asOf - 20_000),
                ),
            ),
        )
        val result = EngineB.evaluate(input(safety = safety))

        assertEquals(21.0, safetyFeature(result, "topHolderConcentrationChangePctPoints_300s").value!!.toDouble(), 0.0)
        assertTrue(result.collapseRisk.evidence.contains("topHolderConcentrationChangePctPoints_300s"))
        assertTrue(result.collapseRisk.reasons.any { it.contains("increased by at least 10 percentage points") })
    }

    @Test fun unknownHolderConcentrationIsNotConvertedToSafeOrLowRisk() {
        val unknownTopHolder = EngineBObservedValue.unknown<Double>(asOf, "holder-data-not-observed")
        val result = EngineB.evaluate(input(safety = safeSafety().copy(topHolderPercent = unknownTopHolder)))

        assertEquals(EngineBAxisLevel.UNASSESSED, riskLevel(result))
        assertEquals(EngineBObservedAvailability.UNKNOWN, safetyFeature(result, "topHolderPercent").riskAvailability)
        assertNull(safetyFeature(result, "topHolderPercent").value)
        assertEquals(EngineBFreshnessStatus.PARTIAL, result.freshnessAssessment.status)
        assertTrue(result.safetyAssessment.reasons.any { it.contains("Top-holder concentration unknown") })
        assertFalse(result.collapseRisk.level == EngineBAxisLevel.LOW)
    }

    @Test fun providerFieldsNotPresentInTheNormalizedContractStayUnavailableNotSafe() {
        val result = EngineB.evaluate(input(safety = EngineBSafetyContext()))

        assertEquals(EngineBAxisLevel.UNASSESSED, result.collapseRisk.level)
        assertEquals(EngineBObservedAvailability.PARTIAL, result.safetyAssessment.availability)
        assertEquals(EngineBObservedAvailability.UNKNOWN, safetyFeature(result, "topHolderPercent").riskAvailability)
        assertEquals("UNAVAILABLE", safetyFeature(result, "mintAuthorityStatus").categoryValue)
        assertTrue(result.safetyAssessment.reasons.any { it.contains("Top-holder concentration unknown") })
        assertTrue(result.safetyAssessment.reasons.any { it.contains("Deployer behavior risk unavailable") })
    }

    @Test fun authorityPresenceRaisesRiskButUnknownAuthorityRemainsUnassessed() {
        val present = EngineB.evaluate(input(safety = safeSafety(mint = EngineBAuthorityStatus.PRESENT)))
        assertEquals(EngineBAxisLevel.MODERATE, present.collapseRisk.level)
        assertEquals("PRESENT", safetyFeature(present, "mintAuthorityStatus").categoryValue)
        assertTrue(present.collapseRisk.reasons.any { it.contains("Mint authority is present") })

        val unknownSafety = safeSafety().copy(
            mintAuthority = EngineBObservedValue.unknown(asOf, "authority-not-observed"),
        )
        val unknown = EngineB.evaluate(input(safety = unknownSafety))
        assertEquals(EngineBAxisLevel.UNASSESSED, unknown.collapseRisk.level)
        assertEquals(EngineBObservedAvailability.UNKNOWN, safetyFeature(unknown, "mintAuthorityStatus").riskAvailability)
        assertEquals("UNKNOWN", safetyFeature(unknown, "mintAuthorityStatus").categoryValue)
    }

    @Test fun observedDeployerBehaviorRiskIsResearchEvidenceAndNotInferredFromIdentityAlone() {
        val result = EngineB.evaluate(input(safety = safeSafety(deployerRisk = EngineBDeployerRiskStatus.RISK_EVIDENCE)))

        assertEquals(EngineBAxisLevel.MODERATE, result.collapseRisk.level)
        assertEquals("RISK_EVIDENCE", safetyFeature(result, "deployerRiskStatus").categoryValue)
        assertTrue(result.collapseRisk.reasons.any { it.contains("creator/deployer behavior risk evidence") })
        val identityOnly = EngineB.evaluate(input(safety = safeSafety().copy(
            deployerRisk = EngineBObservedValue.unavailable("creator_behavior_not_provided"),
        )))
        assertEquals(EngineBObservedAvailability.UNAVAILABLE, safetyFeature(identityOnly, "deployerRiskStatus").riskAvailability)
        assertEquals(EngineBAxisLevel.UNASSESSED, identityOnly.collapseRisk.level)
    }

    @Test fun severalIndependentSevereDomainsCanReachCriticalButOneWeakSignalCannot() {
        val trades = listOf(
            trade(1, -28_000, priceUsd = 0.03),
            trade(2, -25_000, priceUsd = 0.05),
            trade(3, -6_000, side = EngineBTradeSide.SELL, priceUsd = 0.04, solAmount = 3.0),
            trade(4, -5_000, side = EngineBTradeSide.SELL, priceUsd = 0.035, solAmount = 3.0),
            trade(5, -4_000, side = EngineBTradeSide.SELL, priceUsd = 0.03, solAmount = 3.0),
            trade(6, -3_000, side = EngineBTradeSide.SELL, priceUsd = 0.025, solAmount = 3.0),
            trade(7, -2_000, side = EngineBTradeSide.SELL, priceUsd = 0.02, solAmount = 3.0),
            trade(8, -1_000, side = EngineBTradeSide.SELL, priceUsd = 0.015, solAmount = 3.0),
        )
        val severeStructure = safeSafety(
            topHolder = 55.0,
            topFive = 85.0,
            topTen = 95.0,
            mint = EngineBAuthorityStatus.PRESENT,
            freeze = EngineBAuthorityStatus.PRESENT,
        )
        val result = EngineB.evaluate(
            input(
                trades = trades,
                currentLiquidity = 20_000.0,
                priorLiquidity = listOf(-10_000L to 100_000.0, -5_000L to 60_000.0),
                safety = severeStructure,
            ),
        )

        assertEquals(EngineBAxisLevel.CRITICAL, result.collapseRisk.level)
        assertEquals(EngineBSignalState.RISK, result.signalState)
        assertTrue(result.collapseRisk.evidence.contains("liquidityChangePct_300s"))
        assertTrue(result.collapseRisk.evidence.contains("severeSellPressure_30s"))
        assertTrue(result.collapseRisk.evidence.contains("bothAuthoritiesPresent"))
    }

    @Test fun staleLiquidityWithFreshTradesDoesNotProduceNormalSignal() {
        val staleLiquidity = MarketValue(
            value = 50_000.0,
            availability = FieldAvailability.STALE,
            providerTimestamp = asOf - 120_000,
            receivedTimestamp = asOf - 120_000,
            observationTimestamp = asOf - 120_000,
            freshnessMs = 120_000,
        )
        val result = EngineB.evaluate(input().copy(marketState = state().copy(liquidityUsd = staleLiquidity)))

        assertEquals(EngineBFreshnessStatus.STALE, result.freshnessAssessment.status)
        assertEquals(EngineBObservedAvailability.STALE, result.liquidityAssessment.availability)
        assertEquals("FRESH", result.freshnessAssessment.features.single { it.name == "tradeHistoryFreshness" }.categoryValue)
        assertEquals(EngineBLifecycleState.STALE, result.lifecycleState)
        assertEquals(EngineBSignalState.STALE, result.signalState)
        assertTrue(result.reasons.contains("stale_market_data"))
        assertFalse(result.signalState == EngineBSignalState.ENTRY_CANDIDATE)
    }

    @Test fun freshPriceWithUnknownHolderCountIsPartialMarketFreshness() {
        val partialState = state().copy(holders = MarketValue.unknown(observationTimestamp = asOf))
        val result = EngineB.evaluate(input().copy(marketState = partialState))

        assertEquals(EngineBFreshnessStatus.PARTIAL, result.freshnessAssessment.status)
        assertEquals("PARTIAL", result.freshnessAssessment.features.single { it.name == "marketStateFreshness" }.categoryValue)
        assertEquals(EngineBObservedAvailability.UNKNOWN, safetyFeature(result, "holderCount").riskAvailability)
        assertFalse(result.signalState == EngineBSignalState.ENTRY_CANDIDATE)
    }

    @Test fun noTimestampedMarketOrTradeHistoryIsExplicitlyInsufficient() {
        val unknownState = LiveMarketState.unknown(
            mint = "insufficient-mint",
            lifecycle = "CREATED",
            providerTimestamp = asOf,
            receivedTimestamp = asOf,
            observationTimestamp = asOf,
            providerSequence = null,
            eventId = "unknown-at-$asOf",
        )
        val result = EngineB.evaluate(EngineBInput(unknownState, emptyList()))

        assertEquals(EngineBFreshnessStatus.INSUFFICIENT, result.freshnessAssessment.status)
        assertEquals(EngineBSignalState.NO_SIGNAL, result.signalState)
        assertTrue(result.freshnessAssessment.reasons.any { it.contains("insufficient") })
    }

    @Test fun rapidPullbackAfterAnObservedAccelerationIsExplainedAsMomentumRisk() {
        val trades = listOf(
            trade(1, -24_000, priceUsd = 0.01),
            trade(2, -16_000, priceUsd = 0.02),
            trade(3, -8_000, priceUsd = 0.05),
            trade(4, 0, priceUsd = 0.03),
        )
        val result = EngineB.evaluate(input(trades = trades))

        assertTrue(result.collapseRisk.evidence.contains("rapidPullbackAfterAcceleration_60s"))
        assertEquals(EngineBAxisLevel.MODERATE, result.collapseRisk.level)
        assertEquals(EngineBSignalState.WEAKENING, result.signalState)
        assertTrue(result.collapseRisk.reasons.any { it.contains("after an observed accelerating price sequence") })
    }

    @Test fun missingLiquidityAndInsufficientHistoryStayUnknownRatherThanLowRisk() {
        val result = EngineB.evaluate(input(currentLiquidity = null, priorLiquidity = emptyList()))

        assertEquals(EngineBObservedAvailability.UNKNOWN, result.liquidityAssessment.availability)
        assertEquals(EngineBObservedAvailability.UNKNOWN, liquidityFeature(result, "liquidityUsdCurrent").riskAvailability)
        assertNull(liquidityFeature(result, "liquidityUsdCurrent").value)
        assertEquals(EngineBAxisLevel.UNASSESSED, result.collapseRisk.level)
        assertTrue(result.liquidityAssessment.reasons.any { it.contains("no observed USD liquidity") })
    }

    @Test fun staleLiquidityHistoryIsNotTreatedAsAnAvailableValueOrCompletedScore() {
        val stale = EngineBLiquidityObservation(
            eventId = "old-liquidity",
            liquidityUsd = EngineBObservedValue.stale(75_000.0, asOf - 120_000, 120_000, "stale-provider-observation"),
        )
        val result = EngineB.evaluate(
            input(currentLiquidity = null, priorLiquidity = emptyList()).copy(liquidityHistory = listOf(stale)),
        )

        assertEquals(EngineBObservedAvailability.STALE, result.liquidityAssessment.availability)
        assertEquals(EngineBObservedAvailability.UNKNOWN, liquidityFeature(result, "liquidityUsdCurrent").riskAvailability)
        assertEquals(EngineBAxisLevel.UNASSESSED, result.liquidityAssessment.riskIndicator.level)
        assertEquals(EngineBAxisLevel.UNASSESSED, result.collapseRisk.level)
    }

    @Test fun oneCurrentLiquidityPointIsInsufficientForChangeAndStability() {
        val result = EngineB.evaluate(input(priorLiquidity = emptyList()))

        assertEquals(EngineBObservedAvailability.INSUFFICIENT, result.liquidityAssessment.availability)
        assertEquals(EngineBObservedAvailability.INSUFFICIENT, liquidityFeature(result, "liquidityChangePct_300s").riskAvailability)
        assertEquals(EngineBObservedAvailability.INSUFFICIENT, liquidityFeature(result, "liquidityStabilityRatio_300s").riskAvailability)
        assertEquals(EngineBAxisLevel.UNASSESSED, result.collapseRisk.level)
    }

    @Test fun missingUsdTradeNotionalMakesLiquidityAssessmentPartialRatherThanFabricated() {
        val tradesWithoutTokenUnits = strongBuys().map { it.copy(tokenAmount = null) }
        val result = EngineB.evaluate(input(trades = tradesWithoutTokenUnits))

        assertEquals(EngineBObservedAvailability.PARTIAL, result.liquidityAssessment.availability)
        assertEquals(EngineBObservedAvailability.UNKNOWN, liquidityFeature(result, "observedTradeVolumeUsd_300s").riskAvailability)
        assertNull(liquidityFeature(result, "observedTradeVolumeUsd_300s").value)
        assertEquals(EngineBObservedAvailability.UNKNOWN, liquidityFeature(result, "liquidityToObservedVolumeRatio_300s").riskAvailability)
        assertEquals(EngineBAxisLevel.UNASSESSED, result.collapseRisk.level)
    }

    @Test fun explicitZeroLiquidityIsPreservedAndCanIndicateSevereDeterioration() {
        val result = EngineB.evaluate(
            input(
                currentLiquidity = 0.0,
                priorLiquidity = listOf(-10_000L to 20_000.0, -5_000L to 10_000.0),
            ),
        )

        val liquidity = liquidityFeature(result, "liquidityUsdCurrent")
        assertEquals(0.0, liquidity.value!!.toDouble(), 0.0)
        assertEquals(FieldAvailability.ZERO, liquidity.availability)
        assertEquals(EngineBAxisLevel.HIGH, result.collapseRisk.level)
        assertFalse(result.collapseRisk.level == EngineBAxisLevel.CRITICAL)
    }

    @Test fun recentUsdTradeNotionalAndLiquidityRatiosAreDerivedFromObservedUnits() {
        val result = EngineB.evaluate(input())
        val volume = liquidityFeature(result, "observedTradeVolumeUsd_300s").value!!.toDouble()
        val liquidity = liquidityFeature(result, "liquidityUsdCurrent").value!!.toDouble()
        val ratio = liquidityFeature(result, "liquidityToObservedVolumeRatio_300s").value!!.toDouble()

        assertEquals(15.0, volume, 0.0000001)
        assertEquals(liquidity / volume, ratio, 0.0000001)
        assertEquals(EngineBFeatureSource.TRADE_DERIVED, liquidityFeature(result, "observedTradeVolumeUsd_300s").sourceType)
        assertTrue(result.liquidityAssessment.riskIndicator.reasons.none { it.contains("slippage") })
    }

    @Test fun futureTradeLiquidityAndHolderObservationsDoNotLeakIntoAsOfAssessment() {
        val baselineInput = input()
        val baseline = EngineB.evaluate(baselineInput)
        val futureTrade = trade(90, 5_000, priceUsd = 99_999.0, tokenAmount = 1_000_000.0)
            .copy(providerTimestamp = asOf - 5_000)
        val futureLiquidity = EngineBLiquidityObservation(
            "future-liquidity",
            observed(1.0e12, asOf + 5_000),
        )
        val futureConcentration = EngineBHolderConcentrationObservation(
            eventId = "future-holder-data",
            topHolderPercent = observed(99.0, asOf + 5_000),
        )
        val withFuture = EngineB.evaluate(
            baselineInput.copy(
                tradeHistory = baselineInput.tradeHistory + futureTrade,
                liquidityHistory = baselineInput.liquidityHistory + futureLiquidity,
                safetyContext = baselineInput.safetyContext.copy(concentrationHistory = listOf(futureConcentration)),
            ),
        )

        assertEquals(baseline, withFuture)
    }

    @Test fun categoryStatusesAndAxesAreExplicitAndRemainIndependent() {
        val result = EngineB.evaluate(input())

        assertEquals(setOf("NOT_ASSESSED", "UNASSESSED", "LOW", "MODERATE", "HIGH", "CRITICAL"), EngineBAxisLevel.values().map { it.name }.toSet())
        assertEquals("B.2.0", result.engineVersion)
        assertTrue(result.pumpPotential !== result.collapseRisk)
        assertEquals("SAFE", safetyFeature(result, "mintAuthorityStatus").categoryValue)
        assertEquals("FRESH", result.freshnessAssessment.features.single { it.name == "freshnessRiskStatus" }.categoryValue)
        assertTrue(result.liquidityAssessment.riskIndicator.reasons.isEmpty())
    }

    private fun assertNotSameAxis(first: EngineBAxisResult, second: EngineBAxisResult) {
        assertTrue(first !== second)
    }
}
