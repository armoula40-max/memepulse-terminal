package com.solanasignal.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

object PumpPortalNormalizer {
    fun parseObject(text: String): JsonObject? = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
    }.getOrNull()

    fun normalize(
        obj: JsonObject,
        receivedTimestamp: Long,
        observationTimestamp: Long = receivedTimestamp,
    ): NormalizedProviderEvent? {
        val mint = obj.string("mint")?.takeIf(String::isNotBlank) ?: return null
        val providerTimestamp = obj.timestamp("timestamp", "blockTime", "createdAt")
        val sequence = obj.long("sequence", "slot", "blockNumber", "eventIndex", "order")
        val signature = obj.string("signature", "txHash", "transactionHash", "eventId", "event_id")
        val txType = obj.string("txType", "type")?.lowercase()

        return when {
            txType == "create" -> NormalizedTokenCreatedEvent(
                mint = mint,
                symbol = obj.string("symbol"),
                name = obj.string("name"),
                creator = obj.string("traderPublicKey", "creator", "creatorPublicKey"),
                uri = obj.string("uri"),
                eventId = "create:${signature ?: mint}",
                providerTimestamp = providerTimestamp,
                receivedTimestamp = receivedTimestamp,
                observationTimestamp = observationTimestamp,
                providerSequence = sequence,
                raw = obj,
            )

            txType == "buy" || txType == "sell" -> {
                val solAmount = obj.finiteDouble("solAmount")
                val tokenAmount = obj.finiteDouble("tokenAmount")
                val priceNative = if (solAmount != null && tokenAmount != null && tokenAmount > 0.0)
                    (solAmount / tokenAmount).takeIf(Double::isFinite) else null
                NormalizedTradeEvent(
                    mint = mint,
                    txType = txType,
                    trader = obj.string("traderPublicKey", "trader"),
                    solAmount = solAmount,
                    tokenAmount = tokenAmount,
                    priceNative = priceNative,
                    marketCapNative = obj.finiteDouble("marketCapSol", "marketCapNative"),
                    priceUsd = obj.finiteDouble("priceUsd"),
                    marketCapUsd = obj.finiteDouble("marketCapUsd"),
                    signature = signature,
                    eventId = tradeId(obj, mint, txType, signature, providerTimestamp, sequence, solAmount, tokenAmount),
                    providerTimestamp = providerTimestamp,
                    receivedTimestamp = receivedTimestamp,
                    observationTimestamp = observationTimestamp,
                    providerSequence = sequence,
                    raw = obj,
                )
            }

            txType in setOf("migration", "migrate", "complete") || obj.isMigrationEvent() ||
                (txType == null && obj.string("pool") != null) -> NormalizedMigrationEvent(
                mint = mint,
                pool = obj.string("pool", "poolAddress"),
                eventId = "migration:${signature ?: hashId(listOf(mint, providerTimestamp, sequence, obj.string("pool", "poolAddress"))) }",
                providerTimestamp = providerTimestamp,
                receivedTimestamp = receivedTimestamp,
                observationTimestamp = observationTimestamp,
                providerSequence = sequence,
                raw = obj,
            )

            else -> null
        }
    }

    fun compareOrder(a: NormalizedProviderEvent, b: NormalizedProviderEvent): Int {
        val aTime = a.providerTimestamp ?: a.receivedTimestamp
        val bTime = b.providerTimestamp ?: b.receivedTimestamp
        return compareValues(aTime, bTime)
            .takeIf { it != 0 }
            ?: compareValues(a.receivedTimestamp, b.receivedTimestamp).takeIf { it != 0 }
            ?: compareNullableSequence(a.providerSequence, b.providerSequence).takeIf { it != 0 }
            ?: a.eventId.compareTo(b.eventId)
    }

    fun freshnessMs(event: NormalizedProviderEvent, now: Long = event.observationTimestamp): Long =
        (now - (event.providerTimestamp ?: event.receivedTimestamp)).coerceAtLeast(0L)

    private fun tradeId(
        obj: JsonObject,
        mint: String,
        txType: String,
        signature: String?,
        providerTimestamp: Long?,
        sequence: Long?,
        solAmount: Double?,
        tokenAmount: Double?,
    ): String {
        if (!signature.isNullOrBlank()) return "trade:$signature:$mint:$txType"
        val providerIdentity = listOf(
            txType,
            mint,
            providerTimestamp,
            sequence,
            obj.string("traderPublicKey", "trader"),
            solAmount,
            tokenAmount,
            obj.finiteDouble("marketCapSol", "marketCapNative"),
        )
        return "trade:${hashId(providerIdentity)}"
    }

    private fun hashId(parts: List<Any?>): String {
        val canonical = parts.joinToString("|") { it?.toString() ?: "<null>" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun compareNullableSequence(a: Long?, b: Long?): Int = when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> compareValues(a, b)
    }

    private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        this[key]?.let { element ->
            if (element == JsonNull) null else runCatching { element.jsonPrimitive.content.takeIf(String::isNotBlank) }.getOrNull()
        }
    }

    private fun JsonObject.isMigrationEvent(): Boolean = when (val marker = this["migration"]) {
        null, JsonNull -> false
        is JsonPrimitive -> marker.booleanOrNull ?: marker.content.isNotBlank()
        else -> true
    }

    private fun JsonObject.long(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
        this[key]?.let { element -> runCatching { element.jsonPrimitive.longOrNull }.getOrNull() }
    }

    private fun JsonObject.finiteDouble(vararg keys: String): Double? = keys.firstNotNullOfOrNull { key ->
        this[key]?.let { element ->
            runCatching { element.jsonPrimitive.doubleOrNull }
                .getOrNull()
                ?.takeIf(Double::isFinite)
        }
    }

    private fun JsonObject.timestamp(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
        val primitive = this[key] as? JsonPrimitive ?: return@firstNotNullOfOrNull null
        primitive.longOrNull?.let { raw -> if (raw in 1L..99_999_999_999L) raw * 1_000L else raw }
            ?: runCatching { Instant.parse(primitive.content).toEpochMilli() }.getOrNull()
    }
}

class RecentEventIds(private val capacity: Int = 4_096) {
    private val seen = LinkedHashSet<String>()

    init { require(capacity > 0) }

    @Synchronized
    fun addIfNew(eventId: String): Boolean {
        if (!seen.add(eventId)) return false
        while (seen.size > capacity) seen.remove(seen.first())
        return true
    }
}
