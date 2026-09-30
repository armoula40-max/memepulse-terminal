package com.solanasignal.network

import kotlinx.serialization.json.JsonObject

enum class ConnectionState { CONNECTING, CONNECTED, DEGRADED, RECONNECTING, DISCONNECTED }

enum class FieldAvailability { KNOWN, ZERO, UNKNOWN, STALE }

data class MarketValue<T : Number>(
    val value: T?,
    val availability: FieldAvailability,
    val providerTimestamp: Long?,
    val receivedTimestamp: Long?,
    val observationTimestamp: Long,
    val freshnessMs: Long?,
) {
    val isStale: Boolean get() = availability == FieldAvailability.STALE

    init {
        require((availability == FieldAvailability.UNKNOWN) == (value == null)) {
            "UNKNOWN values must be null; KNOWN, ZERO, and STALE values must be retained"
        }
        require(availability != FieldAvailability.ZERO || value?.toDouble() == 0.0) {
            "ZERO availability is only valid for an explicit numeric zero"
        }
        require(availability != FieldAvailability.KNOWN || value?.toDouble() != 0.0) {
            "Explicit zero must use ZERO availability"
        }
    }

    fun at(now: Long, staleAfterMs: Long = DEFAULT_STALE_AFTER_MS): MarketValue<T> {
        val age = ageAt(now, providerTimestamp, receivedTimestamp, observationTimestamp)
        val nextAvailability = when {
            value == null -> FieldAvailability.UNKNOWN
            age > staleAfterMs -> FieldAvailability.STALE
            value.toDouble() == 0.0 -> FieldAvailability.ZERO
            else -> FieldAvailability.KNOWN
        }
        return copy(availability = nextAvailability, freshnessMs = age)
    }

    companion object {
        const val DEFAULT_STALE_AFTER_MS = 60_000L

        fun <T : Number> of(
            value: T?,
            providerTimestamp: Long?,
            receivedTimestamp: Long?,
            observationTimestamp: Long,
            staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
        ): MarketValue<T> {
            val age = if (value == null) null else ageAt(
                observationTimestamp,
                providerTimestamp,
                receivedTimestamp,
                observationTimestamp,
            )
            val availability = when {
                value == null -> FieldAvailability.UNKNOWN
                age != null && age > staleAfterMs -> FieldAvailability.STALE
                value.toDouble() == 0.0 -> FieldAvailability.ZERO
                else -> FieldAvailability.KNOWN
            }
            return MarketValue(
                value = value,
                availability = availability,
                providerTimestamp = providerTimestamp,
                receivedTimestamp = receivedTimestamp,
                observationTimestamp = observationTimestamp,
                freshnessMs = age,
            )
        }

        fun <T : Number> unknown(
            providerTimestamp: Long? = null,
            receivedTimestamp: Long? = null,
            observationTimestamp: Long,
        ) = MarketValue<T>(
            value = null,
            availability = FieldAvailability.UNKNOWN,
            providerTimestamp = providerTimestamp,
            receivedTimestamp = receivedTimestamp,
            observationTimestamp = observationTimestamp,
            freshnessMs = null,
        )

        private fun ageAt(now: Long, provider: Long?, received: Long?, observed: Long): Long =
            (now - (provider ?: received ?: observed)).coerceAtLeast(0L)
    }
}

sealed interface NormalizedProviderEvent {
    val mint: String
    val eventId: String
    val providerTimestamp: Long?
    val receivedTimestamp: Long
    val observationTimestamp: Long
    val providerSequence: Long?
    val raw: JsonObject

    val eventTimestamp: Long get() = providerTimestamp ?: receivedTimestamp
}

data class NormalizedTokenCreatedEvent(
    override val mint: String,
    val symbol: String?,
    val name: String?,
    val creator: String?,
    val uri: String?,
    override val eventId: String,
    override val providerTimestamp: Long?,
    override val receivedTimestamp: Long,
    override val observationTimestamp: Long,
    override val providerSequence: Long?,
    override val raw: JsonObject,
) : NormalizedProviderEvent

data class NormalizedTradeEvent(
    override val mint: String,
    val txType: String,
    val trader: String?,
    val solAmount: Double?,
    val tokenAmount: Double?,
    val priceNative: Double?,
    val marketCapNative: Double?,
    val priceUsd: Double?,
    val marketCapUsd: Double?,
    val signature: String?,
    override val eventId: String,
    override val providerTimestamp: Long?,
    override val receivedTimestamp: Long,
    override val observationTimestamp: Long,
    override val providerSequence: Long?,
    override val raw: JsonObject,
) : NormalizedProviderEvent {
    init { require(txType == "buy" || txType == "sell") }
}

data class NormalizedMigrationEvent(
    override val mint: String,
    val pool: String?,
    override val eventId: String,
    override val providerTimestamp: Long?,
    override val receivedTimestamp: Long,
    override val observationTimestamp: Long,
    override val providerSequence: Long?,
    override val raw: JsonObject,
) : NormalizedProviderEvent

data class SafetyCheck(val check: String, val status: String, val reason: String, val source: String, val timestamp: Long)

data class Diagnostics(
    val lastEventAt: Long? = null,
    val latencyMs: Long? = null,
    val reconnects: Int = 0,
    val parserErrors: Int = 0,
    val messages: Long = 0,
    val lastError: String? = null,
    val eventsReceived: Long = 0,
    val eventsParsed: Long = 0,
    val eventsNormalized: Long = 0,
    val tradesReceived: Long = 0,
    val buys: Long = 0,
    val sells: Long = 0,
    val creations: Long = 0,
    val migrations: Long = 0,
    val duplicates: Long = 0,
    val invalidEvents: Long = 0,
    val lateEvents: Long = 0,
    val staleData: Long = 0,
    val unknownFields: Long = 0,
    val metricsGenerated: Long = 0,
)

fun Diagnostics.recordNormalized(event: NormalizedProviderEvent): Diagnostics = copy(
    eventsNormalized = eventsNormalized + 1,
    tradesReceived = tradesReceived + if (event is NormalizedTradeEvent) 1 else 0,
    buys = buys + if (event is NormalizedTradeEvent && event.txType == "buy") 1 else 0,
    sells = sells + if (event is NormalizedTradeEvent && event.txType == "sell") 1 else 0,
    creations = creations + if (event is NormalizedTokenCreatedEvent) 1 else 0,
    migrations = migrations + if (event is NormalizedMigrationEvent) 1 else 0,
)

data class MomentumScore(val total: Int, val buyerPressure: Int?, val volumePressure: Int?, val volumeVelocity: Int?, val priceMomentum: Int?, val liquidity: Int?, val holderDistribution: Int?, val safety: Int?, val reasons: List<String>, val unknowns: List<String>)
data class Signal(val type: String, val score: MomentumScore, val mint: String, val reasons: List<String>, val createdAt: Long)
