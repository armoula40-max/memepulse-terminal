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

@Entity(tableName = "paper_accounts")
data class PaperAccountEntity(
    @PrimaryKey val accountId: String,
    val startingBalanceUsd: Double,
    val cashBalanceUsd: Double,
    val reservedOpenPositionValueUsd: Double,
    val realizedPnlUsd: Double,
    val totalFeesUsd: Double,
    val totalSimulatedSlippageUsd: Double,
    val tradeCount: Int,
    val closedTradeCount: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val currentEquityUsd: Double?,
    val peakEquityUsd: Double?,
    val maximumDrawdownPct: Double,
    val valuationStatus: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Entry* columns are immutable snapshots. Repository updates only the mark/exit columns. */
@Entity(
    tableName = "paper_trades",
    indices = [
        Index(value = ["mint", "status"], name = "index_paper_trades_mint_status"),
        Index(value = ["signalId"], unique = true, name = "index_paper_trades_signal_id"),
        Index(value = ["entryTimestamp"], name = "index_paper_trades_entry_timestamp"),
    ],
)
data class PaperTradeEntity(
    @PrimaryKey val paperTradeId: String,
    val signalId: String,
    val mint: String,
    val tokenSymbol: String?,
    val status: String,
    val entryTimestamp: Long,
    val entryPriceUsd: Double,
    val entryPriceObservedAt: Long,
    val entryMarketCapUsd: Double?,
    val entryLiquidityUsd: Double?,
    val entrySignalState: String,
    val entryPumpPotential: String,
    val entryPumpEvidenceJson: String,
    val entryCollapseRisk: String,
    val entryCollapseEvidenceJson: String,
    val entryMarketCapQuality: String,
    val entryDataFreshness: String,
    val entrySnapshotRef: String,
    val entryMarketStateJson: String,
    val entryFeaturesJson: String,
    val entryNotionalUsd: Double,
    val tokenQuantity: Double,
    val entryFeeUsd: Double,
    val entrySimulatedSlippageBps: Double,
    val entrySimulatedSlippageUsd: Double,
    val effectiveEntryPriceUsd: Double,
    val lastObservedPriceUsd: Double?,
    val currentPriceUsd: Double?,
    val currentMarketCapUsd: Double?,
    val currentLiquidityUsd: Double?,
    val currentValueUsd: Double?,
    val unrealizedPnlUsd: Double?,
    val unrealizedPnlPct: Double?,
    val valuationStatus: String,
    val lastMarkTimestamp: Long?,
    val lastMarketSnapshotRef: String?,
    val lastMarketStateJson: String?,
    val currentSignalState: String?,
    val currentPumpPotential: String?,
    val currentCollapseRisk: String?,
    val peakPriceUsd: Double,
    val troughPriceUsd: Double,
    val maximumFavorableExcursionPct: Double,
    val maximumAdverseExcursionPct: Double,
    val currentDrawdownFromPeakPct: Double?,
    val maximumPositionDrawdownPct: Double,
    val exitTimestamp: Long?,
    val exitReason: String?,
    val exitPriceUsd: Double?,
    val exitMarketSnapshotRef: String?,
    val exitMarketStateJson: String?,
    val exitSignalState: String?,
    val exitNotionalUsd: Double?,
    val exitSimulatedSlippageBps: Double?,
    val exitSimulatedSlippageUsd: Double?,
    val effectiveExitPriceUsd: Double?,
    val exitFeeUsd: Double?,
    val grossPnlUsd: Double?,
    val netPnlUsd: Double?,
    val grossPnlPct: Double?,
    val netPnlPct: Double?,
    val holdingDurationMs: Long?,
    val exitAmbiguous: Boolean,
)

@Entity(
    tableName = "paper_trade_events",
    indices = [
        Index(value = ["paperTradeId", "timestamp"], name = "index_paper_trade_events_trade_time"),
        Index(value = ["timestamp"], name = "index_paper_trade_events_timestamp"),
    ],
)
data class PaperTradeEventEntity(
    @PrimaryKey val eventId: String,
    val paperTradeId: String,
    val mint: String,
    val timestamp: Long,
    val action: String,
    val snapshotRef: String,
    val observedPriceUsd: Double?,
    val observedPriceTimestamp: Long?,
    val signalState: String?,
    val reason: String?,
    val ambiguous: Boolean,
    val eventSnapshotJson: String,
)
