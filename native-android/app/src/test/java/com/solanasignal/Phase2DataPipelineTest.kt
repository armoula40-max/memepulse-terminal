package com.solanasignal

import com.solanasignal.network.Diagnostics
import com.solanasignal.network.FieldAvailability
import com.solanasignal.network.LiveMarketState
import com.solanasignal.network.MarketValue
import com.solanasignal.network.NormalizedMigrationEvent
import com.solanasignal.network.NormalizedTokenCreatedEvent
import com.solanasignal.network.NormalizedTradeEvent
import com.solanasignal.network.PumpPortalNormalizer
import com.solanasignal.network.RecentEventIds
import com.solanasignal.network.recordNormalized
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2DataPipelineTest {
    private fun event(json: String, received: Long = 1_700_000_000_000L) =
        PumpPortalNormalizer.normalize(
            PumpPortalNormalizer.parseObject(json) ?: error("invalid test json"),
            receivedTimestamp = received,
            observationTimestamp = received,
        ) ?: error("unrecognized event")

    @Test fun tokenAmountCannotBecomePriceUsd() {
        val trade = event("""{"mint":"mint-a","txType":"buy","tokenAmount":1000000,"solAmount":2,"signature":"sig-a"}""") as NormalizedTradeEvent
        assertEquals(1_000_000.0, trade.tokenAmount!!, 0.0)
        assertNull(trade.priceUsd)
        val state = LiveMarketState.from(trade)
        assertNull(state.priceUsd.value)
        assertEquals(FieldAvailability.UNKNOWN, state.priceUsd.availability)
    }

    @Test fun solAmountIsNotPriceUsd() {
        val trade = event("""{"mint":"mint-b","txType":"sell","solAmount":0.75,"signature":"sig-b"}""") as NormalizedTradeEvent
        assertEquals(0.75, trade.solAmount!!, 0.0)
        assertNull(trade.priceUsd)
        assertEquals(FieldAvailability.UNKNOWN, LiveMarketState.from(trade).priceUsd.availability)
    }

    @Test fun nativePriceAndUsdPriceRemainDistinct() {
        val trade = event("""{"mint":"mint-c","txType":"buy","solAmount":0.2,"tokenAmount":100,"priceUsd":0.04,"marketCapSol":50,"marketCapUsd":9000,"signature":"sig-c"}""") as NormalizedTradeEvent
        assertEquals(0.002, trade.priceNative!!, 0.0)
        assertEquals(0.04, trade.priceUsd!!, 0.0)
        assertEquals(50.0, trade.marketCapNative!!, 0.0)
        assertEquals(9000.0, trade.marketCapUsd!!, 0.0)
        val state = LiveMarketState.from(trade)
        assertEquals(0.002, state.priceNative.value!!, 0.0)
        assertEquals(0.04, state.priceUsd.value!!, 0.0)
        assertEquals(FieldAvailability.KNOWN, state.priceNative.availability)
        assertEquals(FieldAvailability.KNOWN, state.priceUsd.availability)
    }

    @Test fun missingLiquidityAndHoldersRemainUnknown() {
        val state = LiveMarketState.from(event("""{"mint":"mint-d","txType":"create"}"""))
        assertNull(state.liquidityUsd.value)
        assertEquals(FieldAvailability.UNKNOWN, state.liquidityUsd.availability)
        assertNull(state.holders.value)
        assertEquals(FieldAvailability.UNKNOWN, state.holders.availability)
    }

    @Test fun explicitZeroRemainsZero() {
        val zero = MarketValue.of(0.0, 1_700_000_000_000L, 1_700_000_000_000L, 1_700_000_000_000L)
        assertEquals(0.0, zero.value!!, 0.0)
        assertEquals(FieldAvailability.ZERO, zero.availability)
    }

    @Test fun staleObservationRetainsValueAndBecomesStale() {
        val value = MarketValue.of(123.0, 1_000L, 120_000L, 120_000L)
        assertEquals(123.0, value.value!!, 0.0)
        assertEquals(FieldAvailability.STALE, value.availability)
        assertTrue(value.isStale)
        assertEquals(119_000L, value.freshnessMs)
    }

    @Test fun duplicateProviderEventIdsAreDeduplicatedAcrossDeliveryTimes() {
        val body = """{"mint":"mint-e","txType":"buy","solAmount":1,"tokenAmount":10,"signature":"same-signature"}"""
        val first = event(body, 1_700_000_000_000L)
        val redelivery = event(body, 1_700_000_005_000L)
        assertEquals(first.eventId, redelivery.eventId)
        val ids = RecentEventIds(capacity = 2)
        assertTrue(ids.addIfNew(first.eventId))
        assertFalse(ids.addIfNew(redelivery.eventId))
    }

    @Test fun eventIdentityScopesSignaturesByMintAndTradeType() {
        val first = event("""{"mint":"mint-e1","txType":"buy","signature":"shared-signature"}""")
        val otherMint = event("""{"mint":"mint-e2","txType":"buy","signature":"shared-signature"}""")
        val otherType = event("""{"mint":"mint-e1","txType":"sell","signature":"shared-signature"}""")
        assertNotEquals(first.eventId, otherMint.eventId)
        assertNotEquals(first.eventId, otherType.eventId)
    }

    @Test fun idlessTradeIdentityUsesProviderFieldsNotReceiveTime() {
        val body = """{"mint":"mint-f","txType":"sell","timestamp":1700000000,"traderPublicKey":"wallet","solAmount":0.2,"tokenAmount":5,"sequence":8}"""
        val first = event(body, 1_700_000_001_000L)
        val redelivery = event(body, 1_700_000_010_000L)
        assertEquals(first.eventId, redelivery.eventId)
    }

    @Test fun eventOrderingIsDeterministicAndUsesProviderSequence() {
        val later = event("""{"mint":"mint-g","txType":"buy","timestamp":1700000000,"sequence":2,"signature":"sig-2"}""", 1_700_000_000_000L)
        val earlier = event("""{"mint":"mint-g","txType":"buy","timestamp":1700000000,"sequence":1,"signature":"sig-1"}""", 1_700_000_000_000L)
        val ordered = listOf(later, earlier).sortedWith(PumpPortalNormalizer::compareOrder)
        assertEquals(earlier.eventId, ordered.first().eventId)
        assertEquals(later.eventId, ordered.last().eventId)
    }

    @Test fun liveMarketStatePreservesUnitsAvailabilityAndTimestamps() {
        val trade = event("""{"mint":"mint-h","txType":"buy","timestamp":1700000000,"solAmount":0.5,"tokenAmount":200,"marketCapSol":80,"signature":"sig-h"}""") as NormalizedTradeEvent
        val state = LiveMarketState.from(trade)
        assertEquals(200.0, state.tokenAmount.value!!, 0.0)
        assertEquals(0.5, state.solAmount.value!!, 0.0)
        assertEquals(0.0025, state.priceNative.value!!, 0.0)
        assertEquals(80.0, state.marketCapNative.value!!, 0.0)
        assertNull(state.priceUsd.value)
        assertNull(state.marketCapUsd.value)
        assertEquals(FieldAvailability.UNKNOWN, state.liquidityUsd.availability)
        assertEquals(trade.providerTimestamp, state.providerTimestamp)
        assertEquals(trade.receivedTimestamp, state.receivedTimestamp)
        assertEquals(trade.observationTimestamp, state.observationTimestamp)
        assertEquals(trade.eventId, state.eventId)
    }

    @Test fun createBuySellAndMigrationHaveDistinctSemanticsAndCounters() {
        val create = event("""{"mint":"mint-i","txType":"create","signature":"create-sig"}""")
        val buy = event("""{"mint":"mint-i","txType":"buy","signature":"buy-sig"}""")
        val sell = event("""{"mint":"mint-i","txType":"sell","signature":"sell-sig"}""")
        val migration = event("""{"mint":"mint-i","txType":"migration","signature":"migration-sig"}""")
        assertTrue(create is NormalizedTokenCreatedEvent)
        assertTrue(buy is NormalizedTradeEvent && buy.txType == "buy")
        assertTrue(sell is NormalizedTradeEvent && sell.txType == "sell")
        assertTrue(migration is NormalizedMigrationEvent)
        val counters = listOf(create, buy, sell, migration).fold(Diagnostics()) { d, item -> d.recordNormalized(item) }
        assertEquals(4L, counters.eventsNormalized)
        assertEquals(2L, counters.tradesReceived)
        assertEquals(1L, counters.buys)
        assertEquals(1L, counters.sells)
        assertEquals(1L, counters.creations)
        assertEquals(1L, counters.migrations)
    }

    @Test fun createNeverFallsBackToTradeWhenProviderTypeIsMissing() {
        val untyped = PumpPortalNormalizer.normalize(
            PumpPortalNormalizer.parseObject("""{"mint":"mint-j","symbol":"J"}""")!!,
            1_700_000_000_000L,
        )
        assertNull(untyped)
        val explicitCreate = event("""{"mint":"mint-j","txType":"create"}""")
        assertNotNull(explicitCreate as? NormalizedTokenCreatedEvent)
    }
}
