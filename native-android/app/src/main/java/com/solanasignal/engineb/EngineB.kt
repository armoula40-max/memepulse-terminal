package com.solanasignal.engineb

import com.solanasignal.network.FieldAvailability
import com.solanasignal.network.LiveMarketState
import com.solanasignal.network.MarketValue

/** Lifecycle states derived only when the current canonical snapshot/history supports them. */
enum class EngineBLifecycleState {
    DISCOVERED,
    WATCH,
    BUILDING,
    ACCELERATING,
    WEAKENING,
    STALE,
    REMOVED,
}

enum class EngineBSignalState {
    NO_SIGNAL,
    WATCH,
    BUILDING,
    ENTRY_CANDIDATE,
    STRONG_CANDIDATE,
    WEAKENING,
    RISK,
    STALE,
}

enum class EngineBDataQuality { COMPLETE, PARTIAL, STALE, INSUFFICIENT }
enum class EngineBAxisLevel { NOT_ASSESSED, LOW, MODERATE, HIGH }
enum class EngineBFeatureSource { SNAPSHOT_DERIVED, TRADE_DERIVED }
enum class EngineBTradeSide { BUY, SELL }

data class EngineBAxisResult(
    val level: EngineBAxisLevel = EngineBAxisLevel.NOT_ASSESSED,
    /** Names of actually calculated features supporting this categorical assessment. */
    val evidence: List<String> = emptyList(),
)

data class EngineBFeature(
    val name: String,
    val value: Number?,
    val availability: FieldAvailability,
    val freshnessMs: Long?,
    val sourceType: EngineBFeatureSource = EngineBFeatureSource.SNAPSHOT_DERIVED,
    val observationTimestamp: Long? = null,
    val windowSeconds: Int? = null,
    val elapsedMs: Long? = null,
)

/** Normalized, provider-neutral individual trade plus its unit-aware observations. */
data class EngineBTradeObservation(
    val eventId: String,
    val side: EngineBTradeSide,
    val trader: String?,
    val observationTimestamp: Long,
    val providerTimestamp: Long?,
    val providerSequence: Long?,
    val solAmount: MarketValue<Double>,
    val priceNative: MarketValue<Double>,
    val priceUsd: MarketValue<Double>,
    val marketCapUsd: MarketValue<Double>,
)

/** A point-in-time market snapshot and only the actual normalized trades available to Engine B. */
data class EngineBInput(
    val marketState: LiveMarketState,
    val tradeHistory: List<EngineBTradeObservation>,
)

data class EngineBResult(
    val engineVersion: String,
    val timestamp: Long,
    val lifecycleState: EngineBLifecycleState,
    val pumpPotential: EngineBAxisResult,
    val collapseRisk: EngineBAxisResult,
    val signalState: EngineBSignalState,
    val reasons: List<String>,
    /** Values read or calculated by Engine B, with unit-independent provenance and age. */
    val featuresUsed: List<EngineBFeature>,
    val unknownFields: List<String>,
    val staleFields: List<String>,
    val dataQuality: EngineBDataQuality,
)

/**
 * Engine B vB.1.0 adds deterministic momentum and trade-pressure measurements. It is a
 * side-effect-free calculation over LiveMarketState and provider-neutral, persisted trade
 * observations. It does not call providers, emit probabilities, or calculate collapse-risk models.
 */
object EngineB {
    const val VERSION = "B.1.0"

    /** Compatibility API: evaluate canonical state only; without history, trend features stay unknown. */
    fun evaluate(state: LiveMarketState): EngineBResult = buildResult(
        state = state,
        features = snapshotFeatures(state),
        momentum = null,
    )

    /** Evaluate a snapshot using only actual normalized trade observations available at its timestamp. */
    fun evaluate(input: EngineBInput): EngineBResult {
        val momentum = EngineBMomentumCalculator.calculate(input)
        return buildResult(
            state = input.marketState,
            features = snapshotFeatures(input.marketState) + momentum.features,
            momentum = momentum,
        )
    }

    private fun buildResult(
        state: LiveMarketState,
        features: List<EngineBFeature>,
        momentum: EngineBMomentumSummary?,
    ): EngineBResult {
        val unknownFields = features.filter { it.availability == FieldAvailability.UNKNOWN }.map { it.name }
        val staleFields = features.filter { it.availability == FieldAvailability.STALE }.map { it.name }
        val normalizedLifecycle = state.lifecycle.trim().uppercase()
        val dataQuality = when {
            staleFields.isNotEmpty() || normalizedLifecycle == "STALE" -> EngineBDataQuality.STALE
            features.all { it.availability == FieldAvailability.KNOWN || it.availability == FieldAvailability.ZERO } -> EngineBDataQuality.COMPLETE
            features.any { it.availability == FieldAvailability.KNOWN || it.availability == FieldAvailability.ZERO } -> EngineBDataQuality.PARTIAL
            else -> EngineBDataQuality.INSUFFICIENT
        }
        val hasPrice = state.priceNative.isPositiveKnown() || state.priceUsd.isPositiveKnown()
        val hasMarketCap = state.marketCapNative.isPositiveKnown() || state.marketCapUsd.isPositiveKnown()
        val lifecycleState = when {
            normalizedLifecycle in REMOVED_MARKERS -> EngineBLifecycleState.REMOVED
            dataQuality == EngineBDataQuality.STALE -> EngineBLifecycleState.STALE
            momentum?.lifecycleState != null -> momentum.lifecycleState
            hasPrice && hasMarketCap -> EngineBLifecycleState.WATCH
            else -> EngineBLifecycleState.DISCOVERED
        }
        val pumpPotential = momentum?.pumpPotential ?: EngineBAxisResult()
        // Full collapse-risk models (liquidity, concentration, authorities) are intentionally later work.
        val collapseRisk = EngineBAxisResult()
        val signalState = when {
            collapseRisk.level == EngineBAxisLevel.HIGH -> EngineBSignalState.RISK
            lifecycleState == EngineBLifecycleState.REMOVED -> EngineBSignalState.NO_SIGNAL
            lifecycleState == EngineBLifecycleState.STALE -> EngineBSignalState.STALE
            lifecycleState == EngineBLifecycleState.WEAKENING -> EngineBSignalState.WEAKENING
            lifecycleState == EngineBLifecycleState.ACCELERATING -> EngineBSignalState.ENTRY_CANDIDATE
            lifecycleState == EngineBLifecycleState.BUILDING -> EngineBSignalState.BUILDING
            lifecycleState == EngineBLifecycleState.WATCH -> EngineBSignalState.WATCH
            else -> EngineBSignalState.NO_SIGNAL
        }
        val reasons = linkedSetOf<String>()
        if (normalizedLifecycle in REMOVED_MARKERS) reasons += "token_removed"
        if (dataQuality == EngineBDataQuality.STALE) reasons += "stale_market_data"
        if (dataQuality == EngineBDataQuality.PARTIAL || dataQuality == EngineBDataQuality.INSUFFICIENT) {
            reasons += "incomplete_market_context"
        }
        momentum?.reasons?.let(reasons::addAll)

        return EngineBResult(
            engineVersion = VERSION,
            timestamp = state.observationTimestamp,
            lifecycleState = lifecycleState,
            pumpPotential = pumpPotential,
            collapseRisk = collapseRisk,
            signalState = signalState,
            reasons = reasons.toList(),
            featuresUsed = features,
            unknownFields = unknownFields,
            staleFields = staleFields,
            dataQuality = dataQuality,
        )
    }

    private fun snapshotFeatures(state: LiveMarketState) = listOf(
        feature("tokenAmount", state.tokenAmount, state.observationTimestamp),
        feature("solAmount", state.solAmount, state.observationTimestamp),
        feature("priceNative", state.priceNative, state.observationTimestamp),
        feature("priceUsd", state.priceUsd, state.observationTimestamp),
        feature("marketCapNative", state.marketCapNative, state.observationTimestamp),
        feature("marketCapUsd", state.marketCapUsd, state.observationTimestamp),
        feature("liquidityUsd", state.liquidityUsd, state.observationTimestamp),
        feature("holders", state.holders, state.observationTimestamp),
    )

    private fun <T : Number> feature(name: String, value: MarketValue<T>, observedAt: Long) = EngineBFeature(
        name = name,
        value = value.value,
        availability = value.availability,
        freshnessMs = value.freshnessMs,
        sourceType = EngineBFeatureSource.SNAPSHOT_DERIVED,
        observationTimestamp = observedAt,
    )

    private fun MarketValue<Double>.isPositiveKnown(): Boolean =
        availability == FieldAvailability.KNOWN && value != null && value > 0.0

    private val REMOVED_MARKERS = setOf("REMOVED", "INVALID", "DELISTED", "UNAVAILABLE")
}
