package com.solanasignal.engineb

import com.solanasignal.network.FieldAvailability
import com.solanasignal.network.LiveMarketState
import com.solanasignal.network.MarketValue

/** Lifecycle projection from one canonical market snapshot. Trend-driven states are reserved until their features exist. */
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

data class EngineBAxisResult(
    val level: EngineBAxisLevel = EngineBAxisLevel.NOT_ASSESSED,
    val evidence: List<String> = emptyList(),
)

data class EngineBFeature(
    val name: String,
    val value: Number?,
    val availability: FieldAvailability,
    val freshnessMs: Long?,
)

data class EngineBResult(
    val engineVersion: String,
    val timestamp: Long,
    val lifecycleState: EngineBLifecycleState,
    val pumpPotential: EngineBAxisResult,
    val collapseRisk: EngineBAxisResult,
    val signalState: EngineBSignalState,
    val reasons: List<String>,
    /** Canonical observations inspected for quality/context, with exact availability and age. */
    val featuresUsed: List<EngineBFeature>,
    val unknownFields: List<String>,
    val staleFields: List<String>,
    val dataQuality: EngineBDataQuality,
)

/**
 * Engine B foundation vB.0.1. This is a side-effect-free projection over the canonical
 * LiveMarketState only. It does not call providers, calculate momentum/risk scores, or emit
 * probabilities. Trend-driven lifecycle states and candidate signals remain reserved.
 */
object EngineB {
    const val VERSION = "B.0.1"

    fun evaluate(state: LiveMarketState): EngineBResult {
        val features = listOf(
            feature("tokenAmount", state.tokenAmount),
            feature("solAmount", state.solAmount),
            feature("priceNative", state.priceNative),
            feature("priceUsd", state.priceUsd),
            feature("marketCapNative", state.marketCapNative),
            feature("marketCapUsd", state.marketCapUsd),
            feature("liquidityUsd", state.liquidityUsd),
            feature("holders", state.holders),
        )
        val unknownFields = features.filter { it.availability == FieldAvailability.UNKNOWN }.map { it.name }
        val staleFields = features.filter { it.availability == FieldAvailability.STALE }.map { it.name }
        val normalizedLifecycle = state.lifecycle.trim().uppercase()
        val inputMarkedStale = normalizedLifecycle == "STALE"
        val dataQuality = when {
            staleFields.isNotEmpty() || inputMarkedStale -> EngineBDataQuality.STALE
            features.all { it.availability == FieldAvailability.KNOWN || it.availability == FieldAvailability.ZERO } -> EngineBDataQuality.COMPLETE
            features.any { it.availability == FieldAvailability.KNOWN || it.availability == FieldAvailability.ZERO } -> EngineBDataQuality.PARTIAL
            else -> EngineBDataQuality.INSUFFICIENT
        }

        val freshPrice = state.priceNative.isPositiveKnown() || state.priceUsd.isPositiveKnown()
        val freshMarketCap = state.marketCapNative.isPositiveKnown() || state.marketCapUsd.isPositiveKnown()
        val lifecycleState = when {
            normalizedLifecycle in REMOVED_MARKERS -> EngineBLifecycleState.REMOVED
            dataQuality == EngineBDataQuality.STALE -> EngineBLifecycleState.STALE
            freshPrice && freshMarketCap -> EngineBLifecycleState.WATCH
            else -> EngineBLifecycleState.DISCOVERED
        }
        val pumpPotential = EngineBAxisResult()
        val collapseRisk = EngineBAxisResult()
        val signalState = when {
            collapseRisk.level == EngineBAxisLevel.HIGH -> EngineBSignalState.RISK
            lifecycleState == EngineBLifecycleState.REMOVED -> EngineBSignalState.NO_SIGNAL
            lifecycleState == EngineBLifecycleState.STALE -> EngineBSignalState.STALE
            lifecycleState == EngineBLifecycleState.WEAKENING -> EngineBSignalState.WEAKENING
            lifecycleState == EngineBLifecycleState.BUILDING -> EngineBSignalState.BUILDING
            lifecycleState == EngineBLifecycleState.ACCELERATING -> EngineBSignalState.WATCH
            lifecycleState == EngineBLifecycleState.WATCH -> EngineBSignalState.WATCH
            else -> EngineBSignalState.NO_SIGNAL
        }
        val reasons = buildList {
            if (normalizedLifecycle in REMOVED_MARKERS) add("token_removed")
            if (dataQuality == EngineBDataQuality.STALE) add("stale_market_data")
            if (dataQuality == EngineBDataQuality.PARTIAL || dataQuality == EngineBDataQuality.INSUFFICIENT) {
                add("incomplete_market_context")
            }
        }

        return EngineBResult(
            engineVersion = VERSION,
            timestamp = state.observationTimestamp,
            lifecycleState = lifecycleState,
            pumpPotential = pumpPotential,
            collapseRisk = collapseRisk,
            signalState = signalState,
            reasons = reasons,
            featuresUsed = features,
            unknownFields = unknownFields,
            staleFields = staleFields,
            dataQuality = dataQuality,
        )
    }

    private fun <T : Number> feature(name: String, value: MarketValue<T>) = EngineBFeature(
        name = name,
        value = value.value,
        availability = value.availability,
        freshnessMs = value.freshnessMs,
    )

    private fun MarketValue<Double>.isPositiveKnown(): Boolean =
        availability == FieldAvailability.KNOWN && value != null && value > 0.0

    private val REMOVED_MARKERS = setOf("REMOVED", "INVALID", "DELISTED", "UNAVAILABLE")
}
