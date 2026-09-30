package com.solanasignal.engineb

import com.solanasignal.network.FieldAvailability
import com.solanasignal.network.LiveMarketState
import com.solanasignal.network.MarketValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineBPhase4Test {
    private val asOf = 1_000_000L

    private fun value(value: Double?, at: Long, availability: FieldAvailability? = null): MarketValue<Double> {
        val computed = MarketValue.of(value, at, at, at)
        if (value == null || availability == null || availability == computed.availability) return computed
        return MarketValue(value, availability, at, at, at, 0L)
    }

    private fun state(timestamp: Long = asOf): LiveMarketState = LiveMarketState.unknown(
        mint = "phase4-mint",
        lifecycle = "TRADING",
        providerTimestamp = timestamp,
        receivedTimestamp = timestamp,
        observationTimestamp = timestamp,
        providerSequence = null,
        eventId = "snapshot-$timestamp",
    ).copy(
        priceNative = value(0.01, timestamp),
        priceUsd = value(0.10, timestamp),
        marketCapNative = value(50.0, timestamp),
        marketCapUsd = value(1_000.0, timestamp),
    )

    private fun trade(
        id: Int,
        at: Long,
        side: EngineBTradeSide = EngineBTradeSide.BUY,
        trader: String? = "wallet-$id",
        solAmount: Double? = 1.0,
        priceNative: Double? = id * 0.01,
        priceUsd: Double? = priceNative?.times(100.0),
        marketCapUsd: Double? = priceUsd?.times(1_000.0),
        solAvailability: FieldAvailability? = null,
        nativeAvailability: FieldAvailability? = null,
        usdAvailability: FieldAvailability? = null,
        capUsdAvailability: FieldAvailability? = null,
        providerTimestamp: Long? = at,
    ): EngineBTradeObservation = EngineBTradeObservation(
        eventId = "event-$id",
        side = side,
        trader = trader,
        observationTimestamp = at,
        providerTimestamp = providerTimestamp,
        providerSequence = id.toLong(),
        solAmount = value(solAmount, at, solAvailability),
        priceNative = value(priceNative, at, nativeAvailability),
        priceUsd = value(priceUsd, at, usdAvailability),
        marketCapUsd = value(marketCapUsd, at, capUsdAvailability),
    )

    private fun evaluate(trades: List<EngineBTradeObservation>) = EngineB.evaluate(EngineBInput(state(), trades))
    private fun resultFeature(result: EngineBResult, name: String) = result.featuresUsed.single { it.name == name }
    private fun numeric(result: EngineBResult, name: String): Double? =
        resultFeature(result, name).value?.toDouble()

    @Test fun priceVelocityUsesActualObservationElapsedTimeAndRecordsProvenance() {
        val result = evaluate(
            listOf(
                trade(1, asOf - 10_000, priceUsd = 1.0),
                trade(2, asOf - 2_000, priceUsd = 3.0),
            ),
        )
        val velocity = resultFeature(result, "priceVelocityUsd_30")
        assertEquals(0.25, velocity.value!!.toDouble(), 0.0000001)
        assertEquals(8_000L, velocity.elapsedMs)
        assertEquals(asOf, velocity.observationTimestamp)
        assertEquals(30, velocity.windowSeconds)
        assertEquals(EngineBFeatureSource.TRADE_DERIVED, velocity.sourceType)
        assertEquals(EngineBLifecycleState.BUILDING, result.lifecycleState)
    }

    @Test fun priceAccelerationRequiresThreeDistinctTimedObservations() {
        val sufficient = evaluate(
            listOf(
                trade(1, asOf - 12_000, priceUsd = 1.0),
                trade(2, asOf - 8_000, priceUsd = 2.0),
                trade(3, asOf - 4_000, priceUsd = 4.0),
            ),
        )
        assertTrue(numeric(sufficient, "priceAccelerationUsd_30")!! > 0.0)
        assertEquals(EngineBDataQuality.PARTIAL, sufficient.dataQuality)

        val insufficient = evaluate(
            listOf(
                trade(1, asOf - 8_000, priceUsd = 1.0),
                trade(2, asOf - 2_000, priceUsd = 2.0),
            ),
        )
        assertNull(numeric(insufficient, "priceAccelerationUsd_30"))
        assertEquals(FieldAvailability.UNKNOWN, resultFeature(insufficient, "priceAccelerationUsd_30").availability)
    }

    @Test fun nativeAndUsdFeaturesNeverInferOneCurrencyFromTheOther() {
        val result = evaluate(
            listOf(
                trade(1, asOf - 10_000, priceNative = 0.1, priceUsd = null, marketCapUsd = null),
                trade(2, asOf - 2_000, priceNative = 0.3, priceUsd = null, marketCapUsd = null),
            ),
        )
        assertEquals(FieldAvailability.UNKNOWN, resultFeature(result, "priceVelocityUsd_30").availability)
        assertEquals(FieldAvailability.UNKNOWN, resultFeature(result, "marketCapVelocityUsd_30").availability)
        assertEquals(0.025, numeric(result, "priceVelocityNative_30")!!, 0.0000001)
    }

    @Test fun pressureCountsVolumesRatiosAndNetNativeFlowAreMeasuredFromTrades() {
        val result = evaluate(
            listOf(
                trade(1, asOf - 22_000, trader = "buyer-a", solAmount = 0.5),
                trade(2, asOf - 19_000, trader = "buyer-b", solAmount = 0.5),
                trade(3, asOf - 10_000, trader = "buyer-c", solAmount = 0.5),
                trade(4, asOf - 1_000, side = EngineBTradeSide.SELL, trader = "seller-a", solAmount = 1.0),
            ),
        )
        assertEquals(3.0, numeric(result, "buyCount_30")!!, 0.0)
        assertEquals(1.0, numeric(result, "sellCount_30")!!, 0.0)
        assertEquals(1.5, numeric(result, "buyVolumeSol_30")!!, 0.0)
        assertEquals(1.0, numeric(result, "sellVolumeSol_30")!!, 0.0)
        assertEquals(0.5, numeric(result, "netFlowSol_30")!!, 0.0000001)
        assertEquals(0.75, numeric(result, "buyRatio_30")!!, 0.0000001)
        assertEquals(0.6, numeric(result, "volumeBuyRatio_30")!!, 0.0000001)
        assertEquals(EngineBFeatureSource.TRADE_DERIVED, resultFeature(result, "netFlowSol_30").sourceType)
    }

    @Test fun explicitZeroVolumeIsNotCoercedToMissingOrUsd() {
        val result = evaluate(
            listOf(
                trade(1, asOf - 2_000, solAmount = 0.0),
                trade(2, asOf - 1_000, side = EngineBTradeSide.SELL, solAmount = 1.0),
            ),
        )
        val buyVolume = resultFeature(result, "buyVolumeSol_30")
        assertEquals(0.0, buyVolume.value!!.toDouble(), 0.0)
        assertEquals(FieldAvailability.ZERO, buyVolume.availability)
        assertEquals(0.0, numeric(result, "volumeBuyRatio_30")!!, 0.0)
        assertEquals(FieldAvailability.ZERO, resultFeature(result, "volumeBuyRatio_30").availability)
    }

    @Test fun repeatedTradesFromOneWalletDoNotAppearAsBroadBuyerGrowth() {
        val result = evaluate(
            listOf(
                trade(1, asOf - 24_000, trader = "same-wallet"),
                trade(2, asOf - 20_000, trader = "same-wallet"),
                trade(3, asOf - 10_000, trader = "same-wallet"),
                trade(4, asOf - 6_000, trader = "same-wallet"),
                trade(5, asOf - 1_000, trader = "same-wallet"),
            ),
        )
        assertEquals(5.0, numeric(result, "buyCount_30")!!, 0.0)
        assertEquals(1.0, numeric(result, "uniqueBuyers_30")!!, 0.0)
        assertEquals(0.0, numeric(result, "buyerGrowth_30")!!, 0.0)
        assertFalse(result.reasons.contains("increasing_unique_buyers"))
    }

    @Test fun distinctBuyerExpansionIsVisibleAsGrowth() {
        val result = evaluate(
            listOf(
                trade(1, asOf - 24_000, trader = "buyer-a"),
                trade(2, asOf - 10_000, trader = "buyer-b"),
                trade(3, asOf - 6_000, trader = "buyer-c"),
                trade(4, asOf - 1_000, trader = "buyer-d"),
            ),
        )
        assertEquals(4.0, numeric(result, "uniqueBuyers_30")!!, 0.0)
        assertEquals(2.0, numeric(result, "buyerGrowth_30")!!, 0.0)
        assertTrue(result.reasons.contains("increasing_unique_buyers"))
    }

    @Test fun sustainedPositiveFlowAcrossSeveralObservedBucketsIsPersistent() {
        val result = evaluate(
            listOf(
                trade(1, asOf - 28_000, trader = "buyer-a", priceUsd = 0.01),
                trade(2, asOf - 18_000, trader = "buyer-b", priceUsd = 0.02),
                trade(3, asOf - 8_000, trader = "buyer-c", priceUsd = 0.04),
                trade(4, asOf - 1_000, trader = "buyer-d", priceUsd = 0.08),
            ),
        )
        assertEquals(1.0, numeric(result, "persistentBuyPressure_30s")!!, 0.0)
        assertTrue(numeric(result, "positiveFlowWindows_30s")!! >= 2.0)
        assertTrue(result.reasons.contains("persistent_buy_pressure"))
        assertEquals(EngineBLifecycleState.ACCELERATING, result.lifecycleState)
        assertEquals(EngineBAxisLevel.HIGH, result.pumpPotential.level)
    }

    @Test fun onePositiveTradeWindowIsNotMislabelledAsPersistentPressure() {
        val result = evaluate(listOf(trade(1, asOf - 1_000)))
        assertNull(numeric(result, "persistentBuyPressure_30s"))
        assertFalse(result.reasons.contains("persistent_buy_pressure"))
        assertEquals(EngineBAxisLevel.NOT_ASSESSED, result.pumpPotential.level)
        assertFalse(result.lifecycleState == EngineBLifecycleState.ACCELERATING)
    }

    @Test fun acceleratingPricesNeedActualPositiveVelocityAndAcceleration() {
        val result = evaluate(
            listOf(
                trade(1, asOf - 24_000, priceUsd = 0.01),
                trade(2, asOf - 16_000, priceUsd = 0.02),
                trade(3, asOf - 8_000, priceUsd = 0.05),
                trade(4, asOf, priceUsd = 0.14),
            ),
        )
        assertTrue(numeric(result, "priceVelocityUsd_30")!! > 0.0)
        assertTrue(numeric(result, "priceAccelerationUsd_30")!! > 0.0)
        assertEquals(EngineBLifecycleState.ACCELERATING, result.lifecycleState)
        assertTrue(result.reasons.contains("accelerating_price"))
    }

    @Test fun aPriceSpikeFollowedByDeclineIsWeakeningAndNotAHighPotentialSignal() {
        val result = evaluate(
            listOf(
                trade(1, asOf - 26_000, priceUsd = 0.02),
                trade(2, asOf - 18_000, priceUsd = 0.05),
                trade(3, asOf - 9_000, priceUsd = 0.30),
                trade(4, asOf - 3_000, side = EngineBTradeSide.SELL, solAmount = 2.0, priceUsd = 0.15),
                trade(5, asOf, side = EngineBTradeSide.SELL, solAmount = 2.0, priceUsd = 0.10),
            ),
        )
        assertTrue(numeric(result, "priceVelocityUsd_30")!! > 0.0)
        assertTrue(numeric(result, "priceVelocityUsdRecent_30s")!! < 0.0)
        assertTrue(numeric(result, "pricePullbackPctUsd_60s")!! > 0.0)
        assertEquals(0.0, numeric(result, "persistentBuyPressure_30s")!!, 0.0)
        assertEquals(EngineBLifecycleState.WEAKENING, result.lifecycleState)
        assertEquals(EngineBSignalState.WEAKENING, result.signalState)
        assertTrue(result.reasons.contains("momentum_weakening"))
        assertFalse(result.reasons.contains("persistent_buy_pressure"))
        assertFalse(result.pumpPotential.level == EngineBAxisLevel.HIGH)
    }

    @Test fun pullbackAndPartialRecoveryAreSeparateObservedFeatures() {
        val result = evaluate(
            listOf(
                trade(1, asOf - 18_000, priceUsd = 1.0),
                trade(2, asOf - 12_000, priceUsd = 2.0),
                trade(3, asOf - 6_000, priceUsd = 1.5),
                trade(4, asOf, priceUsd = 1.75),
            ),
        )
        assertEquals(0.125, numeric(result, "pricePullbackPctUsd_60s")!!, 0.0000001)
        assertEquals((1.75 - 1.5) / 1.5, numeric(result, "priceRecoveryPctUsd_60s")!!, 0.0000001)
        assertTrue(result.reasons.contains("pullback_detected"))
        assertTrue(result.reasons.contains("recovery_detected"))
    }

    @Test fun usdMarketCapPullbackAndRecoveryUseOnlyObservedUsdMarketCaps() {
        val result = evaluate(
            listOf(
                trade(1, asOf - 18_000, priceUsd = null, marketCapUsd = 10.0),
                trade(2, asOf - 12_000, priceUsd = null, marketCapUsd = 20.0),
                trade(3, asOf - 6_000, priceUsd = null, marketCapUsd = 15.0),
                trade(4, asOf, priceUsd = null, marketCapUsd = 17.5),
            ),
        )
        assertEquals(0.125, numeric(result, "marketCapPullbackPctUsd_60s")!!, 0.0000001)
        assertEquals((17.5 - 15.0) / 15.0, numeric(result, "marketCapRecoveryPctUsd_60s")!!, 0.0000001)
        assertEquals(EngineBFeatureSource.TRADE_DERIVED, resultFeature(result, "marketCapRecoveryPctUsd_60s").sourceType)
    }

    @Test fun missingTraderIdentityKeepsUniqueBuyerFeaturesUnknown() {
        val result = evaluate(
            listOf(
                trade(1, asOf - 10_000, trader = null),
                trade(2, asOf - 1_000, trader = null),
            ),
        )
        assertNull(numeric(result, "uniqueBuyers_30"))
        assertNull(numeric(result, "buyerGrowth_30"))
        assertEquals(FieldAvailability.UNKNOWN, resultFeature(result, "uniqueBuyers_30").availability)
        assertFalse(result.reasons.contains("increasing_unique_buyers"))
    }

    @Test fun marketCapOnlyMotionUsesMarketCapReasonNotPriceReason() {
        val result = evaluate(
            listOf(
                trade(1, asOf - 10_000, priceNative = null, priceUsd = null, marketCapUsd = 10.0),
                trade(2, asOf - 2_000, priceNative = null, priceUsd = null, marketCapUsd = 20.0),
            ),
        )
        assertTrue(result.reasons.contains("positive_market_cap_velocity"))
        assertFalse(result.reasons.contains("positive_price_velocity"))
    }

    @Test fun sellPressureTakingOverAfterBuyPressureProducesWeakeningReason() {
        val result = evaluate(
            listOf(
                trade(1, asOf - 28_000, trader = "buyer-a", side = EngineBTradeSide.BUY, solAmount = 1.0),
                trade(2, asOf - 22_000, trader = "buyer-b", side = EngineBTradeSide.BUY, solAmount = 1.0),
                trade(3, asOf - 17_000, trader = "buyer-c", side = EngineBTradeSide.BUY, solAmount = 1.0),
                trade(4, asOf - 8_000, trader = "seller-a", side = EngineBTradeSide.SELL, solAmount = 2.0),
                trade(5, asOf - 4_000, trader = "seller-b", side = EngineBTradeSide.SELL, solAmount = 2.0),
                trade(6, asOf, trader = "seller-c", side = EngineBTradeSide.SELL, solAmount = 2.0),
            ),
        )
        assertTrue(numeric(result, "buyRatioDelta_30")!! < 0.0)
        assertEquals(EngineBLifecycleState.WEAKENING, result.lifecycleState)
        assertTrue(result.reasons.contains("momentum_weakening"))
    }

    @Test fun staleTradePricesRemainStaleInsteadOfProducingFreshVelocity() {
        val result = evaluate(
            listOf(
                trade(1, asOf - 5_000, priceUsd = 1.0, usdAvailability = FieldAvailability.STALE),
                trade(2, asOf, priceUsd = 2.0, usdAvailability = FieldAvailability.STALE),
            ),
        )
        val velocity = resultFeature(result, "priceVelocityUsd_5")
        assertNull(velocity.value)
        assertEquals(FieldAvailability.STALE, velocity.availability)
        assertTrue(result.staleFields.contains("priceVelocityUsd_5"))
        assertEquals(EngineBDataQuality.STALE, result.dataQuality)
        assertEquals(EngineBLifecycleState.STALE, result.lifecycleState)
    }

    @Test fun sameTimestampObservationsNeverDivideByZeroOrInventVelocity() {
        val result = evaluate(
            listOf(
                trade(1, asOf - 1_000, priceUsd = 1.0),
                trade(2, asOf - 1_000, priceUsd = 9.0),
            ),
        )
        assertNull(numeric(result, "priceVelocityUsd_30"))
        assertEquals(FieldAvailability.UNKNOWN, resultFeature(result, "priceVelocityUsd_30").availability)
    }

    @Test fun futureObservationDoesNotLeakIntoAsOfFeaturesEvenWithEarlierProviderTime() {
        val history = listOf(
            trade(1, asOf - 10_000, priceUsd = 1.0),
            trade(2, asOf - 2_000, priceUsd = 2.0),
        )
        val baseline = evaluate(history)
        val future = trade(3, asOf + 1_000, priceUsd = 9_999.0, solAmount = 9_999.0, providerTimestamp = asOf - 5_000)
        val withFuture = evaluate(history + future)
        assertEquals(baseline, withFuture)
    }

    @Test fun configuredWindowsAreExplicitAndUnavailableWindowsStayUnknown() {
        val result = evaluate(listOf(trade(1, asOf - 1_000)))
        listOf(5, 10, 15, 30, 60, 120, 300).forEach { window ->
            assertEquals(FieldAvailability.UNKNOWN, resultFeature(result, "priceVelocityUsd_$window").availability)
            assertEquals(EngineBFeatureSource.TRADE_DERIVED, resultFeature(result, "buyCount_$window").sourceType)
            assertEquals(window, resultFeature(result, "buyCount_$window").windowSeconds)
        }
        assertEquals(result.featuresUsed.size, result.featuresUsed.map { it.name }.toSet().size)
    }
}
