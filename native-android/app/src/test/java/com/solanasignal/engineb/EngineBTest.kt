package com.solanasignal.engineb

import com.solanasignal.network.FieldAvailability
import com.solanasignal.network.LiveMarketState
import com.solanasignal.network.MarketValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineBTest {
    private fun unknownState(lifecycle: String = "CREATED"): LiveMarketState = LiveMarketState.unknown(
        mint = "mint-test",
        lifecycle = lifecycle,
        providerTimestamp = 10_000L,
        receivedTimestamp = 10_000L,
        observationTimestamp = 10_000L,
        providerSequence = null,
        eventId = "create:mint-test",
    )

    private fun <T : Number> known(value: T): MarketValue<T> = MarketValue.of(
        value = value,
        providerTimestamp = 10_000L,
        receivedTimestamp = 10_000L,
        observationTimestamp = 10_000L,
    )

    private fun watchableState() = unknownState().copy(
        priceNative = known(0.01),
        marketCapNative = known(100.0),
    )

    private fun completeState() = watchableState().copy(
        tokenAmount = known(10_000.0),
        solAmount = known(1.0),
        priceUsd = known(0.05),
        marketCapUsd = known(500.0),
        liquidityUsd = known(25_000.0),
        holders = known(125),
    )

    @Test fun newTokenAndAdequateContextEnterDeterministicLifecycleStates() {
        val discovered = EngineB.evaluate(unknownState())
        assertEquals(EngineBLifecycleState.DISCOVERED, discovered.lifecycleState)
        assertEquals(EngineBSignalState.NO_SIGNAL, discovered.signalState)

        val watched = EngineB.evaluate(watchableState())
        assertEquals(EngineBLifecycleState.WATCH, watched.lifecycleState)
        assertEquals(EngineBSignalState.WATCH, watched.signalState)
    }

    @Test fun unknownLiquidityRemainsUnknownAndIsListed() {
        val result = EngineB.evaluate(watchableState())
        assertEquals(EngineBDataQuality.PARTIAL, result.dataQuality)
        val liquidity = result.featuresUsed.single { it.name == "liquidityUsd" }
        assertNull(liquidity.value)
        assertEquals(FieldAvailability.UNKNOWN, liquidity.availability)
        assertTrue(result.unknownFields.contains("liquidityUsd"))
    }

    @Test fun unknownHolderCountRemainsUnknownAndIsListed() {
        val result = EngineB.evaluate(watchableState())
        val holders = result.featuresUsed.single { it.name == "holders" }
        assertNull(holders.value)
        assertEquals(FieldAvailability.UNKNOWN, holders.availability)
        assertTrue(result.unknownFields.contains("holders"))
    }

    @Test fun explicitZeroAvailabilityIsPreservedInFeatureProvenance() {
        val zeroLiquidity = MarketValue.of(0.0, 10_000L, 10_000L, 10_000L)
        val result = EngineB.evaluate(watchableState().copy(liquidityUsd = zeroLiquidity))
        val liquidity = result.featuresUsed.single { it.name == "liquidityUsd" }
        assertEquals(0.0, liquidity.value!!.toDouble(), 0.0)
        assertEquals(FieldAvailability.ZERO, liquidity.availability)
        assertFalse(result.unknownFields.contains("liquidityUsd"))
    }

    @Test fun staleCriticalMarketDataProducesStaleLifecycleAndQuality() {
        val stalePrice = MarketValue.of(
            value = 0.01,
            providerTimestamp = 1_000L,
            receivedTimestamp = 120_000L,
            observationTimestamp = 120_000L,
        )
        val result = EngineB.evaluate(watchableState().copy(priceNative = stalePrice))
        assertEquals(EngineBLifecycleState.STALE, result.lifecycleState)
        assertEquals(EngineBDataQuality.STALE, result.dataQuality)
        assertTrue(result.staleFields.contains("priceNative"))
        assertTrue(result.reasons.contains("stale_market_data"))
    }

    @Test fun engineBApiAcceptsCanonicalStateAndExposesNoProviderClients() {
        val evaluate = EngineB::class.java.getDeclaredMethod("evaluate", LiveMarketState::class.java)
        assertArrayEquals(arrayOf(LiveMarketState::class.java), evaluate.parameterTypes)
        val referencedTypes = EngineB::class.java.declaredMethods.flatMap { method ->
            method.parameterTypes.toList() + method.returnType
        }.map { it.name.lowercase() }
        listOf("pumpportal", "dexscreener", "rugcheck", "rpc", "retrofit", "okhttp").forEach { forbidden ->
            assertFalse("Engine B references provider client type $forbidden", referencedTypes.any { forbidden in it })
        }
    }

    @Test fun futureOutcomeAndProviderObjectsAreNotAcceptedAsInputs() {
        val evaluate = EngineB::class.java.getDeclaredMethod("evaluate", LiveMarketState::class.java)
        assertEquals(1, evaluate.parameterCount)
        assertArrayEquals(arrayOf(LiveMarketState::class.java), evaluate.parameterTypes)
        val resultProperties = EngineBResult::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(resultProperties.any { "outcome" in it || "future" in it || "maxprice" in it })
    }

    @Test fun pumpPotentialAndCollapseRiskAreIndependentAxes() {
        val result = EngineB.evaluate(watchableState())
        assertNotSame(result.pumpPotential, result.collapseRisk)
        assertEquals(EngineBAxisLevel.NOT_ASSESSED, result.pumpPotential.level)
        assertEquals(EngineBAxisLevel.UNASSESSED, result.collapseRisk.level)
        assertTrue(result.collapseRisk.reasons.any { it.contains("not treated as safe") })
    }

    @Test fun highPotentialAndHighRiskCanCoexistInTheContract() {
        val result = EngineB.evaluate(watchableState()).copy(
            pumpPotential = EngineBAxisResult(EngineBAxisLevel.HIGH),
            collapseRisk = EngineBAxisResult(EngineBAxisLevel.HIGH),
        )
        assertNotSame(result.pumpPotential, result.collapseRisk)
        assertEquals(EngineBAxisLevel.HIGH, result.pumpPotential.level)
        assertEquals(EngineBAxisLevel.HIGH, result.collapseRisk.level)
    }

    @Test fun missingContextReducesQualityWithoutFillingMissingValues() {
        val result = EngineB.evaluate(unknownState())
        assertEquals(EngineBDataQuality.INSUFFICIENT, result.dataQuality)
        assertTrue(result.featuresUsed.all { it.value == null && it.availability == FieldAvailability.UNKNOWN })
        assertEquals(result.featuresUsed.map { it.name }.toSet(), result.unknownFields.toSet())
        assertEquals(EngineBAxisLevel.NOT_ASSESSED, result.pumpPotential.level)
        assertEquals(EngineBAxisLevel.UNASSESSED, result.collapseRisk.level)
    }

    @Test fun completeCanonicalObservationsAreReportedAsComplete() {
        val result = EngineB.evaluate(completeState())
        assertEquals(EngineBDataQuality.COMPLETE, result.dataQuality)
        assertTrue(result.unknownFields.isEmpty())
        assertTrue(result.staleFields.isEmpty())
        assertTrue(result.featuresUsed.all { it.freshnessMs != null })
    }

    @Test fun lifecycleAndSignalStatesAreDeterministicForIdenticalInput() {
        val input = watchableState()
        val first = EngineB.evaluate(input)
        val second = EngineB.evaluate(input)
        assertEquals(first.lifecycleState, second.lifecycleState)
        assertEquals(first.signalState, second.signalState)
        assertEquals(first, second)
    }

    @Test fun removedLifecycleIsRepresentedWithoutFabricatingAProviderField() {
        val result = EngineB.evaluate(unknownState(lifecycle = "REMOVED"))
        assertEquals(EngineBLifecycleState.REMOVED, result.lifecycleState)
        assertEquals(EngineBSignalState.NO_SIGNAL, result.signalState)
        assertTrue(result.reasons.contains("token_removed"))
    }

    @Test fun signalStateIsDeterministicForIdenticalLiveMarketState() {
        val input = watchableState()
        assertEquals(EngineB.evaluate(input).signalState, EngineB.evaluate(input).signalState)
    }

    @Test fun engineVersionIsExposedOnEveryResult() {
        assertEquals("B.2.0", EngineB.evaluate(watchableState()).engineVersion)
        assertEquals(EngineB.VERSION, EngineB.evaluate(unknownState()).engineVersion)
    }

    @Test fun reasonsOnlyReferenceImplementedDataQualityFeatures() {
        val partial = EngineB.evaluate(watchableState())
        val stale = EngineB.evaluate(watchableState().copy(
            priceNative = MarketValue.of(0.01, 1_000L, 120_000L, 120_000L),
        ))
        val supported = setOf("token_removed", "stale_market_data", "incomplete_market_context")
        assertTrue(partial.reasons.all { it in supported })
        assertTrue(stale.reasons.all { it in supported })
        assertTrue(partial.reasons.contains("incomplete_market_context"))
        assertTrue(stale.reasons.contains("stale_market_data"))
    }

    @Test fun lifecycleAndSignalEnumsExposeTheSpecifiedStates() {
        assertEquals(
            setOf("DISCOVERED", "WATCH", "BUILDING", "ACCELERATING", "WEAKENING", "STALE", "REMOVED"),
            EngineBLifecycleState.values().map { it.name }.toSet(),
        )
        assertEquals(
            setOf("NO_SIGNAL", "WATCH", "BUILDING", "ENTRY_CANDIDATE", "STRONG_CANDIDATE", "WEAKENING", "RISK", "STALE"),
            EngineBSignalState.values().map { it.name }.toSet(),
        )
    }
}
