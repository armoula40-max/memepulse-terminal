package com.solanasignal.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "tokens", primaryKeys = ["mint"], indices = [Index("createdAt")])
data class TokenEntity(
    val mint: String,
    val symbol: String?,
    val name: String?,
    val creator: String?,
    val uri: String?,
    val createdAt: Long?,
    val firstSeenAt: Long,
    val marketCapUsd: Double?,
    val liquidityUsd: Double?,
    val priceUsd: Double?,
    val lifecycle: String = "UNKNOWN",
    val source: String = "pumpportal",
    val marketCapNative: Double? = null,
    val priceNative: Double? = null,
    val latestTokenAmount: Double? = null,
    val latestSolAmount: Double? = null,
    val holders: Int? = null,
    @ColumnInfo(defaultValue = "'UNKNOWN'") val latestTokenAmountAvailability: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val latestSolAmountAvailability: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val marketCapNativeAvailability: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val marketCapUsdAvailability: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val priceNativeAvailability: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val priceUsdAvailability: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val liquidityUsdAvailability: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val holdersAvailability: String = "UNKNOWN",
    val providerTimestamp: Long? = null,
    val receivedTimestamp: Long? = null,
    val observationTimestamp: Long? = null,
    val freshnessMs: Long? = null,
    @ColumnInfo(defaultValue = "0") val isStale: Boolean = false,
    val lastEventId: String? = null,
)

@Entity(tableName = "trades", primaryKeys = ["dedupeKey"], indices = [Index("mint"), Index("timestamp")])
data class TradeEntity(
    val dedupeKey: String,
    val mint: String,
    val txType: String?,
    val trader: String?,
    val solAmount: Double?,
    val tokenAmount: Double?,
    /** Legacy provider-named SOL field retained for existing rows; never use as a USD amount. */
    val marketCapSol: Double?,
    val priceUsd: Double?,
    val timestamp: Long,
    val signature: String?,
    val priceNative: Double? = null,
    val marketCapNative: Double? = null,
    val marketCapUsd: Double? = null,
    @ColumnInfo(defaultValue = "'UNKNOWN'") val solAmountAvailability: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val tokenAmountAvailability: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val priceNativeAvailability: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val priceUsdAvailability: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val marketCapNativeAvailability: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val marketCapUsdAvailability: String = "UNKNOWN",
    val providerTimestamp: Long? = null,
    val receivedTimestamp: Long? = null,
    val observationTimestamp: Long? = null,
    val freshnessMs: Long? = null,
    val providerSequence: Long? = null,
)
@Entity(tableName = "metrics", primaryKeys = ["mint", "windowSeconds"])
data class MetricEntity(val mint: String, val windowSeconds: Int, val updatedAt: Long, val trades: Int, val buys: Int, val sells: Int, val uniqueBuyers: Int, val uniqueSellers: Int, val buyVolumeSol: Double?, val sellVolumeSol: Double?, val latestPriceUsd: Double?, val priceChangePct: Double?, val volumeVelocity: Double?, val buyerVelocity: Double?, val dataQuality: String)
@Entity(tableName = "scores", primaryKeys = ["mint", "timestamp"])
data class ScoreEntity(val mint: String, val timestamp: Long, val total: Int, val buyerPressure: Int?, val volumePressure: Int?, val volumeVelocity: Int?, val priceMomentum: Int?, val liquidity: Int?, val holderDistribution: Int?, val safety: Int?, val reasons: String, val unknowns: String)
@Entity(tableName = "signals", primaryKeys = ["id"], indices = [Index("mint"), Index("timestamp"), Index("signalType"), Index("score")])
data class SignalEntity(val id: String, val mint: String, val timestamp: Long, val signalType: String, val score: Int, val reasons: String, val marketCapUsd: Double?, val liquidityUsd: Double?, val buyers: Int?, val sellers: Int?, val buyVolume: Double?, val sellVolume: Double?, val priceUsd: Double?)
@Entity(tableName = "signal_outcomes", primaryKeys = ["signalId", "horizonSeconds"])
data class SignalOutcomeEntity(val signalId: String, val horizonSeconds: Int, val baselinePriceUsd: Double?, val observedPriceUsd: Double?, val hypotheticalChangePct: Double?, val status: String)
@Entity(tableName = "system_events", primaryKeys = ["id"], indices = [Index("timestamp")])
data class SystemEventEntity(val id: String, val timestamp: Long, val level: String, val type: String, val message: String)
@Entity(tableName = "settings")
data class SettingEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "provider_events", primaryKeys = ["eventId"], indices = [Index("mint"), Index("eventTimestamp")])
data class ProviderEventEntity(
    val eventId: String,
    val mint: String,
    val eventType: String,
    val providerTimestamp: Long?,
    val receivedTimestamp: Long,
    val observationTimestamp: Long,
    val providerSequence: Long?,
    val eventTimestamp: Long,
)
