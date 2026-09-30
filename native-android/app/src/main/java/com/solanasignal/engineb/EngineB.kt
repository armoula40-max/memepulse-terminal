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
enum class EngineBAxisLevel { NOT_ASSESSED, UNASSESSED, LOW, MODERATE, HIGH, CRITICAL }
enum class EngineBFeatureSource { SNAPSHOT_DERIVED, TRADE_DERIVED, LIQUIDITY_DERIVED, SAFETY_DERIVED, RISK_DERIVED }
enum class EngineBTradeSide { BUY, SELL }
enum class EngineBObservedAvailability { AVAILABLE, PARTIAL, STALE, UNKNOWN, INSUFFICIENT, UNAVAILABLE }
enum class EngineBAuthorityStatus { SAFE, PRESENT }
enum class EngineBDeployerRiskStatus { NO_RISK_EVIDENCE, RISK_EVIDENCE }
enum class EngineBFreshnessStatus { FRESH, PARTIAL, STALE, UNKNOWN, INSUFFICIENT }

data class EngineBAxisResult(
    val level: EngineBAxisLevel = EngineBAxisLevel.NOT_ASSESSED,
    /** Names of actually calculated features supporting this categorical assessment. */
    val evidence: List<String> = emptyList(),
    val reasons: List<String> = emptyList(),
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
    /** Categorical values (such as SAFE/PRESENT) stay textual; they are never encoded as scores. */
    val categoryValue: String? = null,
    /** Rich availability for risk inputs, including UNAVAILABLE and INSUFFICIENT. */
    val riskAvailability: EngineBObservedAvailability? = null,
    val sourceLabel: String? = null,
)

/** Provider-neutral observed value with an explicit source status and as-of provenance. */
data class EngineBObservedValue<T>(
    val value: T?,
    val availability: EngineBObservedAvailability,
    val observationTimestamp: Long? = null,
    val freshnessMs: Long? = null,
    val source: String? = null,
) {
    init {
        if (availability == EngineBObservedAvailability.AVAILABLE) {
            require(value != null) { "AVAILABLE observations must carry an observed value" }
        }
        if (availability in setOf(
                EngineBObservedAvailability.UNKNOWN,
                EngineBObservedAvailability.UNAVAILABLE,
                EngineBObservedAvailability.INSUFFICIENT,
            )) {
            require(value == null) { "$availability observations must not carry a value" }
        }
        if (value is Double) require(value.isFinite()) { "Observed numeric values must be finite" }
    }

    companion object {
        fun <T> unavailable(source: String? = null) = EngineBObservedValue<T>(
            value = null,
            availability = EngineBObservedAvailability.UNAVAILABLE,
            source = source,
        )

        fun <T> unknown(observationTimestamp: Long? = null, source: String? = null) = EngineBObservedValue<T>(
            value = null,
            availability = EngineBObservedAvailability.UNKNOWN,
            observationTimestamp = observationTimestamp,
            source = source,
        )

        fun <T> insufficient(source: String? = null) = EngineBObservedValue<T>(
            value = null,
            availability = EngineBObservedAvailability.INSUFFICIENT,
            source = source,
        )

        fun <T> available(
            value: T,
            observationTimestamp: Long,
            freshnessMs: Long? = 0L,
            source: String? = null,
        ) = EngineBObservedValue(
            value = value,
            availability = EngineBObservedAvailability.AVAILABLE,
            observationTimestamp = observationTimestamp,
            freshnessMs = freshnessMs,
            source = source,
        )

        fun <T> stale(
            value: T?,
            observationTimestamp: Long?,
            freshnessMs: Long? = null,
            source: String? = null,
        ) = EngineBObservedValue(
            value = value,
            availability = EngineBObservedAvailability.STALE,
            observationTimestamp = observationTimestamp,
            freshnessMs = freshnessMs,
            source = source,
        )
    }
}

/** Actual normalized liquidity observation. The engine filters every point to evaluation time T. */
data class EngineBLiquidityObservation(
    val eventId: String,
    val liquidityUsd: EngineBObservedValue<Double>,
)

data class EngineBHolderConcentrationObservation(
    val eventId: String,
    val topHolderPercent: EngineBObservedValue<Double> = EngineBObservedValue.unknown(),
    val topFivePercent: EngineBObservedValue<Double> = EngineBObservedValue.unknown(),
    val topTenPercent: EngineBObservedValue<Double> = EngineBObservedValue.unknown(),
)

/** Normalized safety values only; absence is UNKNOWN/UNAVAILABLE, never interpreted as safe. */
data class EngineBSafetyContext(
    val topHolderPercent: EngineBObservedValue<Double> = EngineBObservedValue.unknown(source = "normalized_provider_contract"),
    val topFivePercent: EngineBObservedValue<Double> = EngineBObservedValue.unknown(source = "normalized_provider_contract"),
    val topTenPercent: EngineBObservedValue<Double> = EngineBObservedValue.unknown(source = "normalized_provider_contract"),
    val mintAuthority: EngineBObservedValue<EngineBAuthorityStatus> = EngineBObservedValue.unavailable("normalized_provider_contract"),
    val freezeAuthority: EngineBObservedValue<EngineBAuthorityStatus> = EngineBObservedValue.unavailable("normalized_provider_contract"),
    val creatorIdentity: EngineBObservedValue<String> = EngineBObservedValue.unavailable("normalized_create_event"),
    val deployerRisk: EngineBObservedValue<EngineBDeployerRiskStatus> = EngineBObservedValue.unavailable("creator_behavior_not_provided"),
    val concentrationHistory: List<EngineBHolderConcentrationObservation> = emptyList(),
)

data class EngineBLiquidityAssessment(
    val availability: EngineBObservedAvailability = EngineBObservedAvailability.UNAVAILABLE,
    /** Research-level liquidity risk indicator, not order-book depth or an execution/slippage estimate. */
    val riskIndicator: EngineBAxisResult = EngineBAxisResult(EngineBAxisLevel.UNASSESSED),
    val features: List<EngineBFeature> = emptyList(),
    val reasons: List<String> = emptyList(),
)

data class EngineBSafetyAssessment(
    val availability: EngineBObservedAvailability = EngineBObservedAvailability.UNAVAILABLE,
    val features: List<EngineBFeature> = emptyList(),
    val reasons: List<String> = emptyList(),
)

data class EngineBFreshnessAssessment(
    val status: EngineBFreshnessStatus = EngineBFreshnessStatus.INSUFFICIENT,
    val features: List<EngineBFeature> = emptyList(),
    val reasons: List<String> = emptyList(),
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
    /** Optional observed token quantity, used with the same trade's USD price for notional volume. */
    val tokenAmount: MarketValue<Double>? = null,
)

/**
 * A point-in-time canonical snapshot, normalized trades, and optional provider-neutral risk inputs.
 * Empty histories preserve unknown/insufficient states; Engine B never fetches or fabricates data.
 */
data class EngineBInput(
    val marketState: LiveMarketState,
    val tradeHistory: List<EngineBTradeObservation>,
    val liquidityHistory: List<EngineBLiquidityObservation> = emptyList(),
    val safetyContext: EngineBSafetyContext = EngineBSafetyContext(),
)

data class EngineBResult(
    val engineVersion: String,
    val timestamp: Long,
    val lifecycleState: EngineBLifecycleState,
    val pumpPotential: EngineBAxisResult,
    val collapseRisk: EngineBAxisResult,
    val signalState: EngineBSignalState,
    val reasons: List<String>,
    /** Momentum/pressure feature evidence retained from Phase 4. */
    val featuresUsed: List<EngineBFeature>,
    val unknownFields: List<String>,
    val staleFields: List<String>,
    val dataQuality: EngineBDataQuality,
    val liquidityAssessment: EngineBLiquidityAssessment = EngineBLiquidityAssessment(),
    val safetyAssessment: EngineBSafetyAssessment = EngineBSafetyAssessment(),
    val freshnessAssessment: EngineBFreshnessAssessment = EngineBFreshnessAssessment(),
)

/**
 * Engine B vB.2.0 keeps pump potential (momentum/pressure) separate from categorical collapse risk.
 * It consumes normalized observations only and does not call providers, emit probabilities, or claim
 * guaranteed exits/slippage. Missing and stale safety inputs remain explicit and gate entry signals.
 */
object EngineB {
    const val VERSION = "B.2.0"

    /** Compatibility API: evaluate canonical state only; without history, momentum features stay absent. */
    fun evaluate(state: LiveMarketState): EngineBResult {
        val input = EngineBInput(state, emptyList())
        val risk = EngineBSafetyRiskCalculator.calculate(input, momentum = null)
        return buildResult(state, snapshotFeatures(state), momentum = null, risk = risk)
    }

    /** Evaluate using only actual normalized observations at or before the canonical state's timestamp. */
    fun evaluate(input: EngineBInput): EngineBResult {
        val momentum = EngineBMomentumCalculator.calculate(input)
        val risk = EngineBSafetyRiskCalculator.calculate(input, momentum)
        return buildResult(
            state = input.marketState,
            features = snapshotFeatures(input.marketState) + momentum.features,
            momentum = momentum,
            risk = risk,
        )
    }

    private fun buildResult(
        state: LiveMarketState,
        features: List<EngineBFeature>,
        momentum: EngineBMomentumSummary?,
        risk: EngineBRiskSummary,
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
            dataQuality == EngineBDataQuality.STALE || risk.freshness.status == EngineBFreshnessStatus.STALE -> EngineBLifecycleState.STALE
            momentum?.lifecycleState != null -> momentum.lifecycleState
            hasPrice && hasMarketCap -> EngineBLifecycleState.WATCH
            else -> EngineBLifecycleState.DISCOVERED
        }
        val signalState = when {
            normalizedLifecycle in REMOVED_MARKERS -> EngineBSignalState.NO_SIGNAL
            risk.collapseRisk.level in setOf(EngineBAxisLevel.HIGH, EngineBAxisLevel.CRITICAL) -> EngineBSignalState.RISK
            dataQuality == EngineBDataQuality.STALE || risk.freshness.status == EngineBFreshnessStatus.STALE -> EngineBSignalState.STALE
            lifecycleState == EngineBLifecycleState.WEAKENING -> EngineBSignalState.WEAKENING
            lifecycleState == EngineBLifecycleState.ACCELERATING && risk.collapseRisk.level == EngineBAxisLevel.LOW -> EngineBSignalState.ENTRY_CANDIDATE
            lifecycleState == EngineBLifecycleState.ACCELERATING -> EngineBSignalState.WATCH
            lifecycleState == EngineBLifecycleState.BUILDING && risk.collapseRisk.level == EngineBAxisLevel.LOW -> EngineBSignalState.BUILDING
            lifecycleState == EngineBLifecycleState.BUILDING -> EngineBSignalState.WATCH
            lifecycleState == EngineBLifecycleState.WATCH -> EngineBSignalState.WATCH
            else -> EngineBSignalState.NO_SIGNAL
        }
        val reasons = linkedSetOf<String>()
        if (normalizedLifecycle in REMOVED_MARKERS) reasons += "token_removed"
        if (dataQuality == EngineBDataQuality.STALE || risk.freshness.status == EngineBFreshnessStatus.STALE) reasons += "stale_market_data"
        if (dataQuality == EngineBDataQuality.PARTIAL || dataQuality == EngineBDataQuality.INSUFFICIENT) {
            reasons += "incomplete_market_context"
        }
        momentum?.reasons?.let(reasons::addAll)
        if (risk.collapseRisk.level != EngineBAxisLevel.UNASSESSED) reasons += risk.reasons
        if (lifecycleState in setOf(EngineBLifecycleState.ACCELERATING, EngineBLifecycleState.BUILDING) &&
            risk.collapseRisk.level == EngineBAxisLevel.UNASSESSED) {
            reasons += "momentum_signal_downgraded_risk_assessment_unavailable"
        }
        if (lifecycleState in setOf(EngineBLifecycleState.ACCELERATING, EngineBLifecycleState.BUILDING) && risk.collapseRisk.level == EngineBAxisLevel.MODERATE) {
            reasons += "momentum_signal_downgraded_moderate_collapse_risk"
        }

        return EngineBResult(
            engineVersion = VERSION,
            timestamp = state.observationTimestamp,
            lifecycleState = lifecycleState,
            pumpPotential = momentum?.pumpPotential ?: EngineBAxisResult(),
            collapseRisk = risk.collapseRisk,
            signalState = signalState,
            reasons = reasons.toList(),
            featuresUsed = features,
            unknownFields = unknownFields,
            staleFields = staleFields,
            dataQuality = dataQuality,
            liquidityAssessment = risk.liquidity,
            safetyAssessment = risk.safety,
            freshnessAssessment = risk.freshness,
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
