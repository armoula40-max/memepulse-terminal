package com.solanasignal.engineb

import com.solanasignal.network.FieldAvailability
import com.solanasignal.network.LiveMarketState
import com.solanasignal.network.MarketValue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class EngineBRiskSummary(
    val liquidity: EngineBLiquidityAssessment,
    val safety: EngineBSafetyAssessment,
    val freshness: EngineBFreshnessAssessment,
    val collapseRisk: EngineBAxisResult,
    val reasons: List<String>,
)

/**
 * Provider-neutral B.2.0 research assessment. All history is bounded and filtered to observation
 * time <= T. Thresholds are fixed descriptive rules, not probabilities or adaptive regimes.
 */
internal object EngineBSafetyRiskCalculator {
    private const val WINDOW_SECONDS = 300
    private const val WINDOW_MS = WINDOW_SECONDS * 1_000L
    private const val FRESH_AFTER_MS = MarketValue.DEFAULT_STALE_AFTER_MS
    private const val DETERIORATION_MODERATE_PCT = -20.0
    private const val DETERIORATION_HIGH_PCT = -50.0
    private const val LOW_LIQUIDITY_TO_CAP = 0.01
    private const val VERY_LOW_LIQUIDITY_TO_CAP = 0.005
    private const val VOLUME_EXCEEDS_LIQUIDITY_RATIO = 1.0
    private const val SEVERE_VOLUME_LIQUIDITY_RATIO = 0.25

    private data class LiquidityPoint(
        val eventId: String,
        val observedAt: Long,
        val valueUsd: Double,
        val freshnessMs: Long,
        val source: String,
        val isStale: Boolean = false,
        val isCurrentState: Boolean = false,
    )

    private data class RiskSignal(
        val name: String,
        val domain: String,
        val reason: String,
        val severe: Boolean = false,
    )

    private data class LiquidityBuild(
        val assessment: EngineBLiquidityAssessment,
        val signals: List<RiskSignal>,
    )

    private data class SafetyBuild(
        val assessment: EngineBSafetyAssessment,
        val signals: List<RiskSignal>,
    )

    fun calculate(input: EngineBInput, momentum: EngineBMomentumSummary?): EngineBRiskSummary {
        val asOf = input.marketState.observationTimestamp
        val trades = orderedTrades(input.tradeHistory, asOf)
        val liquidity = liquidityAssessment(input, trades, asOf)
        val safety = safetyAssessment(input.safetyContext, input.marketState, asOf)
        val freshness = freshnessAssessment(input.marketState, trades, liquidity.assessment, safety.assessment, asOf)
        val signals = buildList {
            addAll(liquidity.signals)
            addAll(safety.signals)
            addAll(momentumSignals(momentum, trades, asOf))
        }
        val riskDomains = signals.map { it.domain }.toSet()
        val severeSignals = signals.count { it.severe }
        val fullyAssessed = liquidity.assessment.availability == EngineBObservedAvailability.AVAILABLE &&
            safety.assessment.availability == EngineBObservedAvailability.AVAILABLE &&
            freshness.status == EngineBFreshnessStatus.FRESH
        val collapseLevel = when {
            riskDomains.size >= 4 && severeSignals > 0 -> EngineBAxisLevel.CRITICAL
            severeSignals > 0 || riskDomains.size >= 2 -> EngineBAxisLevel.HIGH
            signals.isNotEmpty() -> EngineBAxisLevel.MODERATE
            fullyAssessed -> EngineBAxisLevel.LOW
            else -> EngineBAxisLevel.UNASSESSED
        }
        val evidence = signals.map { it.name }.distinct()
        val riskReasons = linkedSetOf<String>()
        signals.forEach { riskReasons += it.reason }
        riskReasons += liquidity.assessment.reasons
        riskReasons += safety.assessment.reasons
        riskReasons += freshness.reasons
        if (collapseLevel == EngineBAxisLevel.UNASSESSED) {
            riskReasons += "Collapse-risk assessment incomplete; missing evidence is not treated as safe"
        }
        if (collapseLevel == EngineBAxisLevel.LOW) {
            riskReasons += "No configured collapse-risk indicators observed in the available assessment"
        }
        val collapseRisk = EngineBAxisResult(
            level = collapseLevel,
            evidence = (evidence + listOfNotNull(
                if (liquidity.assessment.availability == EngineBObservedAvailability.AVAILABLE) "liquidityAssessmentComplete" else null,
                if (safety.assessment.availability == EngineBObservedAvailability.AVAILABLE) "safetyAssessmentComplete" else null,
            )).distinct(),
            reasons = riskReasons.toList(),
        )
        return EngineBRiskSummary(
            liquidity = liquidity.assessment,
            safety = safety.assessment,
            freshness = freshness,
            collapseRisk = collapseRisk,
            reasons = riskReasons.toList(),
        )
    }

    private fun liquidityAssessment(
        input: EngineBInput,
        trades: List<EngineBTradeObservation>,
        asOf: Long,
    ): LiquidityBuild {
        val state = input.marketState
        val current = fromMarketValue(state.liquidityUsd, asOf, "LiveMarketState.liquidityUsd")
        val points = mutableListOf<LiquidityPoint>()
        input.liquidityHistory.asSequence()
            .filter { it.liquidityUsd.observationTimestamp != null && it.liquidityUsd.observationTimestamp!! <= asOf }
            .filter { it.liquidityUsd.observationTimestamp!! >= asOf - WINDOW_MS }
            .map { observation -> observation.eventId to asOfValue(observation.liquidityUsd, asOf) }
            .forEach { (eventId, value) ->
                val numeric = value.value?.takeIf { it.isFinite() && it >= 0.0 }
                if (numeric != null && value.availability in setOf(EngineBObservedAvailability.AVAILABLE, EngineBObservedAvailability.STALE)) {
                    points += LiquidityPoint(
                        eventId = eventId,
                        observedAt = value.observationTimestamp!!,
                        valueUsd = numeric,
                        freshnessMs = value.freshnessMs ?: (asOf - value.observationTimestamp).coerceAtLeast(0L),
                        source = value.source ?: "normalized_liquidity_history",
                        isStale = value.availability == EngineBObservedAvailability.STALE,
                    )
                }
            }
        val statePoint = current.value?.takeIf { it.isFinite() && it >= 0.0 }?.let { value ->
            LiquidityPoint(
                eventId = state.eventId,
                observedAt = state.liquidityUsd.observationTimestamp,
                valueUsd = value,
                freshnessMs = current.freshnessMs ?: (asOf - state.liquidityUsd.observationTimestamp).coerceAtLeast(0L),
                source = current.source ?: "LiveMarketState.liquidityUsd",
                isStale = current.availability == EngineBObservedAvailability.STALE,
                isCurrentState = true,
            )
        }
        if (statePoint != null && statePoint.observedAt <= asOf && statePoint.observedAt >= asOf - WINDOW_MS) points += statePoint
        val dedupedPoints = points.sortedWith(compareBy<LiquidityPoint> { it.observedAt }.thenBy { it.eventId })
            .groupBy { it.observedAt }
            .values.map { sameTime -> sameTime.firstOrNull { it.isCurrentState } ?: sameTime.last() }
        val currentFeature = numericFeature("liquidityUsdCurrent", current, asOf, EngineBFeatureSource.LIQUIDITY_DERIVED)
        val availablePoints = dedupedPoints.filter { !it.isStale && it.freshnessMs <= FRESH_AFTER_MS }
        val staleHistoryExists = dedupedPoints.any { it.isStale || it.freshnessMs > FRESH_AFTER_MS }
        val currentIsAvailable = current.availability == EngineBObservedAvailability.AVAILABLE && current.value != null && current.observationTimestamp != null &&
            current.observationTimestamp == state.liquidityUsd.observationTimestamp && current.observationTimestamp <= asOf
        val previousPoint = if (currentIsAvailable) availablePoints.lastOrNull { it.observedAt < current.observationTimestamp!! } else null
        val previousValue = previousPoint?.let {
            EngineBObservedValue.available(it.valueUsd, it.observedAt, it.freshnessMs, it.source)
        } ?: EngineBObservedValue.insufficient("fewer than two timestamped liquidity observations")
        val windowPoints = availablePoints.filter { it.observedAt in (asOf - WINDOW_MS)..asOf }
        val lastPoint = windowPoints.lastOrNull()
        val firstPoint = windowPoints.firstOrNull()
        val elapsed = if (firstPoint != null && lastPoint != null) lastPoint.observedAt - firstPoint.observedAt else 0L
        val changePct = if (currentIsAvailable && windowPoints.size >= 2 && firstPoint != null && lastPoint != null && firstPoint.valueUsd > 0.0 && elapsed > 0L) {
            (lastPoint.valueUsd - firstPoint.valueUsd) / firstPoint.valueUsd * 100.0
        } else null
        val velocity = if (currentIsAvailable && changePct != null && elapsed > 0L && firstPoint != null && lastPoint != null) {
            (lastPoint.valueUsd - firstPoint.valueUsd) / (elapsed / 1_000.0)
        } else null
        val deteriorationPct = changePct?.let { max(0.0, -it) }
        val pullbackPct = if (currentIsAvailable && windowPoints.size >= 2 && lastPoint != null) {
            val peak = windowPoints.dropLast(1).maxOfOrNull { it.valueUsd }
            peak?.takeIf { it > 0.0 }?.let { max(0.0, (it - lastPoint.valueUsd) / it * 100.0) }
        } else null
        val recoveryPct = if (currentIsAvailable && windowPoints.size >= 3 && lastPoint != null) {
            var peak = windowPoints.first().valueUsd
            var trough: Double? = null
            windowPoints.drop(1).dropLast(1).forEach { point ->
                if (point.valueUsd > peak) {
                    peak = point.valueUsd
                    trough = null
                }
                else if (point.valueUsd < peak && (trough == null || point.valueUsd < trough!!)) trough = point.valueUsd
            }
            val low = trough
            if (low != null && low > 0.0 && lastPoint.valueUsd > low) (lastPoint.valueUsd - low) / low * 100.0 else 0.0
        } else null
        val stability = if (currentIsAvailable && windowPoints.size >= 3) {
            val maxValue = windowPoints.maxOf { it.valueUsd }
            val minValue = windowPoints.minOf { it.valueUsd }
            if (maxValue > 0.0) (1.0 - (maxValue - minValue) / maxValue).coerceIn(0.0, 1.0) else null
        } else null
        val countValue = if (windowPoints.isEmpty()) EngineBObservedValue.insufficient("no usable liquidity observations")
        else EngineBObservedValue.available(windowPoints.size.toDouble(), lastPoint!!.observedAt, lastPoint.freshnessMs, "normalized liquidity observations")
        val previousFeature = numericFeature("liquidityUsdPrevious", previousValue, asOf, EngineBFeatureSource.LIQUIDITY_DERIVED, WINDOW_SECONDS)
        val changeFeature = derivedFeature("liquidityChangePct_300s", changePct, asOf, lastPoint?.observedAt, elapsed, EngineBObservedValue.available(0.0, asOf).availabilityOrInsufficient(changePct), EngineBFeatureSource.LIQUIDITY_DERIVED, WINDOW_SECONDS)
        val velocityFeature = derivedFeature("liquidityVelocityUsdPerSecond_300s", velocity, asOf, lastPoint?.observedAt, elapsed, EngineBObservedValue.available(0.0, asOf).availabilityOrInsufficient(velocity), EngineBFeatureSource.LIQUIDITY_DERIVED, WINDOW_SECONDS)
        val deteriorationFeature = derivedFeature("liquidityDeteriorationPct_300s", deteriorationPct, asOf, lastPoint?.observedAt, elapsed, EngineBObservedValue.available(0.0, asOf).availabilityOrInsufficient(deteriorationPct), EngineBFeatureSource.LIQUIDITY_DERIVED, WINDOW_SECONDS)
        val pullbackFeature = derivedFeature("liquidityPullbackPct_300s", pullbackPct, asOf, lastPoint?.observedAt, elapsed, EngineBObservedValue.available(0.0, asOf).availabilityOrInsufficient(pullbackPct), EngineBFeatureSource.LIQUIDITY_DERIVED, WINDOW_SECONDS)
        val recoveryFeature = derivedFeature("liquidityRecoveryPct_300s", recoveryPct, asOf, lastPoint?.observedAt, elapsed, EngineBObservedValue.available(0.0, asOf).availabilityOrInsufficient(recoveryPct), EngineBFeatureSource.LIQUIDITY_DERIVED, WINDOW_SECONDS)
        val stabilityFeature = derivedFeature("liquidityStabilityRatio_300s", stability, asOf, lastPoint?.observedAt, elapsed, EngineBObservedValue.available(0.0, asOf).availabilityOrInsufficient(stability), EngineBFeatureSource.LIQUIDITY_DERIVED, WINDOW_SECONDS)
        val countFeature = numericFeature("liquidityObservationCount_300s", countValue, asOf, EngineBFeatureSource.LIQUIDITY_DERIVED, WINDOW_SECONDS)

        val marketCap = fromMarketValue(state.marketCapUsd, asOf, "LiveMarketState.marketCapUsd")
        val marketCapValue = marketCap.value?.takeIf { marketCap.availability == EngineBObservedAvailability.AVAILABLE && it > 0.0 }
        val liquidityValue = current.value?.takeIf { currentIsAvailable && it >= 0.0 }
        val liquidityToCap = if (liquidityValue != null && marketCapValue != null) liquidityValue / marketCapValue else null
        val marketCapAvailability = if (marketCap.availability == EngineBObservedAvailability.STALE) EngineBObservedAvailability.STALE else if (marketCapValue == null) EngineBObservedAvailability.UNKNOWN else EngineBObservedAvailability.AVAILABLE
        val liquidityCapFeature = derivedFeature("liquidityToMarketCapRatio", liquidityToCap, asOf, minOfNotNull(current.observationTimestamp, marketCap.observationTimestamp), null, availabilityFor(liquidityToCap, if (liquidityToCap == null) marketCapAvailability else EngineBObservedAvailability.AVAILABLE), EngineBFeatureSource.LIQUIDITY_DERIVED)

        val observedVolume = observedTradeVolumeUsd(trades, asOf)
        val volumeFeature = numericFeature("observedTradeVolumeUsd_300s", observedVolume, asOf, EngineBFeatureSource.TRADE_DERIVED, WINDOW_SECONDS)
        val volume = observedVolume.value?.takeIf { observedVolume.availability == EngineBObservedAvailability.AVAILABLE && it > 0.0 }
        val liquidityToVolume = if (liquidityValue != null && volume != null) liquidityValue / volume else null
        val volumeRatioAvailability = when {
            observedVolume.availability == EngineBObservedAvailability.STALE -> EngineBObservedAvailability.STALE
            liquidityValue == null -> current.availability
            volume == null && observedVolume.availability == EngineBObservedAvailability.AVAILABLE -> EngineBObservedAvailability.INSUFFICIENT
            liquidityToVolume == null -> observedVolume.availability
            else -> EngineBObservedAvailability.AVAILABLE
        }
        val volumeRatioFeature = derivedFeature("liquidityToObservedVolumeRatio_300s", liquidityToVolume, asOf, current.observationTimestamp, null, availabilityFor(liquidityToVolume, volumeRatioAvailability), EngineBFeatureSource.LIQUIDITY_DERIVED, WINDOW_SECONDS)

        val features = listOf(
            currentFeature, previousFeature, changeFeature, velocityFeature, deteriorationFeature,
            pullbackFeature, recoveryFeature, stabilityFeature, countFeature, liquidityCapFeature,
            volumeFeature, volumeRatioFeature,
        )
        val anyCurrentPointStale = current.availability == EngineBObservedAvailability.STALE
        val status = when {
            anyCurrentPointStale -> EngineBObservedAvailability.STALE
            (staleHistoryExists && availablePoints.size < 2) || observedVolume.availability == EngineBObservedAvailability.STALE || marketCap.availability == EngineBObservedAvailability.STALE -> EngineBObservedAvailability.STALE
            current.availability == EngineBObservedAvailability.UNAVAILABLE -> EngineBObservedAvailability.UNAVAILABLE
            current.value == null -> if (dedupedPoints.isEmpty()) current.availability else EngineBObservedAvailability.PARTIAL
            windowPoints.size < 2 -> EngineBObservedAvailability.INSUFFICIENT
            changePct == null || stability == null || liquidityToCap == null || liquidityToVolume == null -> EngineBObservedAvailability.PARTIAL
            else -> EngineBObservedAvailability.AVAILABLE
        }
        val signals = mutableListOf<RiskSignal>()
        if (changePct != null && changePct <= DETERIORATION_MODERATE_PCT) {
            signals += RiskSignal(
                name = "liquidityChangePct_300s",
                domain = "liquidity",
                reason = "Liquidity deteriorating across recent observations (${changePct.roundToOne()}% over 300 seconds)",
                severe = changePct <= DETERIORATION_HIGH_PCT,
            )
        }
        if (liquidityToCap != null && liquidityToCap < LOW_LIQUIDITY_TO_CAP) {
            signals += RiskSignal(
                name = "liquidityToMarketCapRatio",
                domain = "liquidity",
                reason = "Observed USD liquidity is below 1% of observed USD market cap",
                severe = liquidityToCap < VERY_LOW_LIQUIDITY_TO_CAP,
            )
        }
        if (liquidityToVolume != null && liquidityToVolume < VOLUME_EXCEEDS_LIQUIDITY_RATIO) {
            signals += RiskSignal(
                name = "liquidityToObservedVolumeRatio_300s",
                domain = "liquidity",
                reason = "Observed 5-minute USD trade notional exceeds observed USD liquidity; this is a liquidity risk indicator, not predicted slippage",
                severe = liquidityToVolume < SEVERE_VOLUME_LIQUIDITY_RATIO,
            )
        }
        if (stability != null && stability < 0.70) {
            signals += RiskSignal("liquidityStabilityRatio_300s", "liquidity", "Liquidity varied materially across recent observations")
        }
        val indicatorLevel = when {
            signals.any { it.severe } -> EngineBAxisLevel.HIGH
            signals.isNotEmpty() -> EngineBAxisLevel.MODERATE
            status == EngineBObservedAvailability.AVAILABLE -> EngineBAxisLevel.LOW
            else -> EngineBAxisLevel.UNASSESSED
        }
        val reasons = linkedSetOf<String>()
        signals.forEach { reasons += it.reason }
        when (status) {
            EngineBObservedAvailability.UNKNOWN -> reasons += "Liquidity unavailable: no observed USD liquidity value"
            EngineBObservedAvailability.UNAVAILABLE -> reasons += "Liquidity unavailable in the normalized input contract"
            EngineBObservedAvailability.INSUFFICIENT -> reasons += "Liquidity history insufficient for change, stability, and recovery calculations"
            EngineBObservedAvailability.PARTIAL -> reasons += "Liquidity assessment partial: one or more required observed inputs are unavailable"
            EngineBObservedAvailability.STALE -> reasons += "Liquidity observation stale"
            EngineBObservedAvailability.AVAILABLE -> Unit
        }
        return LiquidityBuild(
            assessment = EngineBLiquidityAssessment(
                availability = status,
                riskIndicator = EngineBAxisResult(indicatorLevel, signals.map { it.name }.distinct(), reasons.toList()),
                features = features,
                reasons = reasons.toList(),
            ),
            signals = signals,
        )
    }

    private fun safetyAssessment(context: EngineBSafetyContext, state: LiveMarketState, asOf: Long): SafetyBuild {
        val holderCount = fromMarketValue(state.holders, asOf, "LiveMarketState.holders")
        val top1 = validatePercent(asOfValue(context.topHolderPercent, asOf), "top-holder")
        val top5 = validatePercent(asOfValue(context.topFivePercent, asOf), "top-five-holder")
        val top10 = validatePercent(asOfValue(context.topTenPercent, asOf), "top-ten-holder")
        val mint = asOfValue(context.mintAuthority, asOf)
        val freeze = asOfValue(context.freezeAuthority, asOf)
        // Creator identity is static metadata; it is not made stale merely because it was first observed earlier.
        val creator = asOfValue(context.creatorIdentity, asOf, Long.MAX_VALUE)
        val deployerRisk = asOfValue(context.deployerRisk, asOf)
        val concentration = listOf(holderCount, top1, top5, top10, mint, freeze, creator, deployerRisk)
        val featureList = mutableListOf<EngineBFeature>()
        featureList += numericFeature("holderCount", holderCount, asOf, EngineBFeatureSource.SNAPSHOT_DERIVED)
        featureList += numericFeature("topHolderPercent", top1, asOf, EngineBFeatureSource.SAFETY_DERIVED)
        featureList += numericFeature("topFiveHolderPercent", top5, asOf, EngineBFeatureSource.SAFETY_DERIVED)
        featureList += numericFeature("topTenHolderPercent", top10, asOf, EngineBFeatureSource.SAFETY_DERIVED)
        featureList += categoryFeature("mintAuthorityStatus", mint.mapValue { it.name }, asOf)
        featureList += categoryFeature("freezeAuthorityStatus", freeze.mapValue { it.name }, asOf)
        featureList += categoryFeature(
            "creatorIdentityStatus",
            when {
                creator.availability == EngineBObservedAvailability.AVAILABLE && !creator.value.isNullOrBlank() -> creator.mapValue { "PRESENT" }
                creator.availability == EngineBObservedAvailability.STALE -> EngineBObservedValue.stale("PRESENT", creator.observationTimestamp, creator.freshnessMs, creator.source)
                else -> creator.mapValue { "UNKNOWN" }
            },
            asOf,
        )
        featureList += categoryFeature("deployerRiskStatus", deployerRisk.mapValue { it.name }, asOf)

        val validHistory = context.concentrationHistory.asSequence()
            .filter { observation ->
                listOf(observation.topHolderPercent, observation.topFivePercent, observation.topTenPercent)
                    .any { it.observationTimestamp != null && it.observationTimestamp!! in (asOf - WINDOW_MS)..asOf }
            }
            .map { observation ->
                observation.eventId to validatePercent(asOfValue(observation.topHolderPercent, asOf), "top-holder")
            }
            .filter { (_, value) -> value.availability == EngineBObservedAvailability.AVAILABLE && value.value != null }
            .map { (id, value) -> id to value }
            .toList()
        val historyWithCurrent = (validHistory + listOf("current" to top1))
            .filter { (_, value) -> value.availability == EngineBObservedAvailability.AVAILABLE && value.value != null && value.observationTimestamp != null && value.observationTimestamp!! <= asOf }
            .groupBy { it.second.observationTimestamp }
            .values
            .map { sameTime -> sameTime.firstOrNull { it.first == "current" } ?: sameTime.minBy { it.first } }
            .sortedBy { it.second.observationTimestamp }
        val concentrationTrend = if (historyWithCurrent.size >= 2) {
            val first = historyWithCurrent.first().second
            val last = historyWithCurrent.last().second
            last.value!! - first.value!!
        } else null
        val trendObservedAt = historyWithCurrent.lastOrNull()?.second?.observationTimestamp
        featureList += derivedFeature(
            "topHolderConcentrationChangePctPoints_300s",
            concentrationTrend,
            asOf,
            trendObservedAt,
            historyWithCurrent.lastOrNull()?.second?.observationTimestamp?.minus(historyWithCurrent.firstOrNull()?.second?.observationTimestamp ?: 0L),
            availabilityFor(concentrationTrend, if (concentrationTrend == null) EngineBObservedAvailability.INSUFFICIENT else EngineBObservedAvailability.AVAILABLE),
            EngineBFeatureSource.SAFETY_DERIVED,
            WINDOW_SECONDS,
        )

        val signals = mutableListOf<RiskSignal>()
        fun concentrationSignal(value: EngineBObservedValue<Double>, name: String, threshold: Double, severeAt: Double) {
            val percent = value.value?.takeIf { value.availability == EngineBObservedAvailability.AVAILABLE }
            if (percent != null && percent >= threshold) {
                signals += RiskSignal(name, "concentration", "High holder concentration observed ($name = ${percent.roundToOne()}%)", severe = percent >= severeAt)
            }
        }
        concentrationSignal(top1, "topHolderPercent", 25.0, 50.0)
        concentrationSignal(top5, "topFiveHolderPercent", 50.0, 80.0)
        concentrationSignal(top10, "topTenHolderPercent", 70.0, 90.0)
        if (concentrationTrend != null && concentrationTrend >= 10.0) {
            signals += RiskSignal("topHolderConcentrationChangePctPoints_300s", "concentration", "Top-holder concentration increased by at least 10 percentage points across observed history")
        }
        if (mint.availability == EngineBObservedAvailability.AVAILABLE && mint.value == EngineBAuthorityStatus.PRESENT) {
            signals += RiskSignal("mintAuthorityStatus", "authority", "Mint authority is present")
        }
        if (freeze.availability == EngineBObservedAvailability.AVAILABLE && freeze.value == EngineBAuthorityStatus.PRESENT) {
            signals += RiskSignal("freezeAuthorityStatus", "authority", "Freeze authority is present")
        }
        if (mint.availability == EngineBObservedAvailability.AVAILABLE && freeze.availability == EngineBObservedAvailability.AVAILABLE &&
            mint.value == EngineBAuthorityStatus.PRESENT && freeze.value == EngineBAuthorityStatus.PRESENT) {
            signals += RiskSignal("bothAuthoritiesPresent", "authority", "Both mint and freeze authorities are present", severe = true)
        }
        if (deployerRisk.availability == EngineBObservedAvailability.AVAILABLE && deployerRisk.value == EngineBDeployerRiskStatus.RISK_EVIDENCE) {
            signals += RiskSignal("deployerRiskStatus", "deployer", "Observed creator/deployer behavior risk evidence is present")
        }

        val reasons = linkedSetOf<String>()
        listOf(
            unavailableReason("Holder count", holderCount.availability),
            unavailableReason("Top-holder concentration", top1.availability),
            unavailableReason("Top-five concentration", top5.availability),
            unavailableReason("Top-ten concentration", top10.availability),
            unavailableReason("Mint authority status", mint.availability),
            unavailableReason("Freeze authority status", freeze.availability),
            unavailableReason("Creator identity", creator.availability),
            unavailableReason("Deployer behavior risk", deployerRisk.availability),
        ).filterNotNull().forEach(reasons::add)
        signals.forEach { reasons += it.reason }
        val status = assessmentAvailability(concentration)
        if (status != EngineBObservedAvailability.AVAILABLE) reasons += "Safety assessment ${status.name.lowercase()}; unknown is not treated as safe"
        return SafetyBuild(
            assessment = EngineBSafetyAssessment(status, featureList, reasons.toList()),
            signals = signals,
        )
    }

    private fun freshnessAssessment(
        state: LiveMarketState,
        trades: List<EngineBTradeObservation>,
        liquidity: EngineBLiquidityAssessment,
        safety: EngineBSafetyAssessment,
        asOf: Long,
    ): EngineBFreshnessAssessment {
        val marketValues = listOf(
            fromMarketValue(state.priceUsd, asOf, "market_state"),
            fromMarketValue(state.priceNative, asOf, "market_state"),
            fromMarketValue(state.marketCapUsd, asOf, "market_state"),
            fromMarketValue(state.marketCapNative, asOf, "market_state"),
            fromMarketValue(state.liquidityUsd, asOf, "market_state"),
            fromMarketValue(state.holders, asOf, "market_state"),
        )
        val marketStatus = when {
            marketValues.any { it.availability == EngineBObservedAvailability.STALE } -> EngineBFreshnessStatus.STALE
            marketValues.any { it.availability == EngineBObservedAvailability.AVAILABLE } &&
                marketValues.any { it.availability in setOf(EngineBObservedAvailability.UNKNOWN, EngineBObservedAvailability.UNAVAILABLE) } -> EngineBFreshnessStatus.PARTIAL
            marketValues.any { it.availability == EngineBObservedAvailability.AVAILABLE } -> EngineBFreshnessStatus.FRESH
            marketValues.any { it.availability == EngineBObservedAvailability.UNKNOWN } -> EngineBFreshnessStatus.UNKNOWN
            else -> EngineBFreshnessStatus.INSUFFICIENT
        }
        val tradeStatus = when {
            trades.isEmpty() -> EngineBFreshnessStatus.INSUFFICIENT
            (asOf - trades.maxOf { it.observationTimestamp }).coerceAtLeast(0L) > FRESH_AFTER_MS -> EngineBFreshnessStatus.STALE
            else -> EngineBFreshnessStatus.FRESH
        }
        val liquidityStatus = when (liquidity.availability) {
            EngineBObservedAvailability.AVAILABLE -> EngineBFreshnessStatus.FRESH
            EngineBObservedAvailability.PARTIAL -> EngineBFreshnessStatus.PARTIAL
            EngineBObservedAvailability.STALE -> EngineBFreshnessStatus.STALE
            EngineBObservedAvailability.UNKNOWN, EngineBObservedAvailability.UNAVAILABLE -> EngineBFreshnessStatus.UNKNOWN
            EngineBObservedAvailability.INSUFFICIENT -> EngineBFreshnessStatus.INSUFFICIENT
        }
        val safetyStatus = when (safety.availability) {
            EngineBObservedAvailability.AVAILABLE -> EngineBFreshnessStatus.FRESH
            EngineBObservedAvailability.PARTIAL -> EngineBFreshnessStatus.PARTIAL
            EngineBObservedAvailability.STALE -> EngineBFreshnessStatus.STALE
            EngineBObservedAvailability.UNKNOWN, EngineBObservedAvailability.UNAVAILABLE -> EngineBFreshnessStatus.UNKNOWN
            EngineBObservedAvailability.INSUFFICIENT -> EngineBFreshnessStatus.INSUFFICIENT
        }
        val components = listOf(marketStatus, tradeStatus, liquidityStatus, safetyStatus)
        val overall = when {
            components.any { it == EngineBFreshnessStatus.STALE } -> EngineBFreshnessStatus.STALE
            components.all { it == EngineBFreshnessStatus.UNKNOWN } -> EngineBFreshnessStatus.UNKNOWN
            components.all { it in setOf(EngineBFreshnessStatus.UNKNOWN, EngineBFreshnessStatus.INSUFFICIENT) } -> EngineBFreshnessStatus.INSUFFICIENT
            components.all { it == EngineBFreshnessStatus.FRESH } -> EngineBFreshnessStatus.FRESH
            else -> EngineBFreshnessStatus.PARTIAL
        }
        val features = listOf(
            categoricalFeature("marketStateFreshness", EngineBObservedValue.available(marketStatus.name, asOf), asOf, EngineBFeatureSource.RISK_DERIVED),
            categoricalFeature("tradeHistoryFreshness", EngineBObservedValue.available(tradeStatus.name, asOf), asOf, EngineBFeatureSource.RISK_DERIVED),
            categoricalFeature("liquidityFreshness", EngineBObservedValue.available(liquidityStatus.name, asOf), asOf, EngineBFeatureSource.RISK_DERIVED),
            categoricalFeature("safetyDataFreshness", EngineBObservedValue.available(safetyStatus.name, asOf), asOf, EngineBFeatureSource.RISK_DERIVED),
            categoricalFeature("freshnessRiskStatus", EngineBObservedValue.available(overall.name, asOf), asOf, EngineBFeatureSource.RISK_DERIVED),
        )
        val reasons = when (overall) {
            EngineBFreshnessStatus.FRESH -> emptyList()
            EngineBFreshnessStatus.PARTIAL -> listOf("Freshness assessment partial: one or more market, trade, liquidity, or safety inputs are incomplete")
            EngineBFreshnessStatus.STALE -> listOf("Market state, trade history, liquidity, or safety input is stale")
            EngineBFreshnessStatus.UNKNOWN -> listOf("Freshness is unknown because no usable timestamped observations are available")
            EngineBFreshnessStatus.INSUFFICIENT -> listOf("Freshness assessment insufficient without timestamped market or trade observations")
        }
        return EngineBFreshnessAssessment(overall, features, reasons)
    }

    private fun momentumSignals(momentum: EngineBMomentumSummary?, trades: List<EngineBTradeObservation>, asOf: Long): List<RiskSignal> {
        if (momentum == null) return emptyList()
        val values = momentum.features.associateBy { it.name }
        fun numeric(name: String): Double? = values[name]?.takeIf {
            it.availability == FieldAvailability.KNOWN || it.availability == FieldAvailability.ZERO
        }?.value?.toDouble()?.takeIf(Double::isFinite)
        val signals = mutableListOf<RiskSignal>()
        val buyRatio = numeric("buyRatio_30")
        val volumeBuyRatio = numeric("volumeBuyRatio_30")
        if (buyRatio != null && volumeBuyRatio != null && buyRatio <= 0.25 && volumeBuyRatio <= 0.25) {
            signals += RiskSignal("severeSellPressure_30s", "pressure", "Severe sell pressure observed in both trade count and native-volume ratios", severe = true)
        }
        val buyerGrowth = numeric("buyerGrowth_30")
        val sellerGrowth = numeric("sellerGrowth_30")
        if (buyerGrowth != null && sellerGrowth != null && buyerGrowth < 0.0 && sellerGrowth > 0.0) {
            signals += RiskSignal("buyerGrowth_30", "pressure", "Sell pressure increasing while buyer growth is weakening")
        }
        val buyDelta = numeric("buyRatioDelta_30")
        val volumeDelta = numeric("volumeBuyRatioDelta_30")
        if (buyDelta != null && volumeDelta != null && buyDelta <= -0.25 && volumeDelta <= -0.25) {
            signals += RiskSignal("buyRatioDelta_30", "pressure", "Buy/sell imbalance is declining across both trade-count and volume observations")
        }
        if (rapidPullbackAfterAcceleration(trades, asOf)) {
            val pullback = listOfNotNull(numeric("pricePullbackPctUsd_60s"), numeric("pricePullbackPctNative_60s")).maxOrNull()
            signals += RiskSignal(
                "rapidPullbackAfterAcceleration_60s",
                "momentum",
                "Rapid pullback after an observed accelerating price sequence${pullback?.let { " (${(it * 100.0).roundToOne()}%)" } ?: ""}",
                severe = pullback != null && pullback >= 0.50,
            )
        }
        return signals
    }

    private fun rapidPullbackAfterAcceleration(trades: List<EngineBTradeObservation>, asOf: Long): Boolean {
        val since = asOf - 60_000L
        val usd = trades.mapNotNull { trade ->
            usableNumber(trade.priceUsd)?.takeIf { it > 0.0 }?.let { trade.observationTimestamp to it }
        }
        val native = trades.mapNotNull { trade ->
            usableNumber(trade.priceNative)?.takeIf { it > 0.0 }?.let { trade.observationTimestamp to it }
        }
        val points = (if (usd.size >= 3) usd else native).filter { it.first in since..asOf }
            .sortedBy { it.first }.groupBy { it.first }.values.map { it.last() }
        if (points.size < 4) return false
        for (index in 0 until points.size - 3) {
            val first = points[index]
            val middle = points[index + 1]
            val lastOfAcceleration = points[index + 2]
            val dt1 = (middle.first - first.first) / 1_000.0
            val dt2 = (lastOfAcceleration.first - middle.first) / 1_000.0
            if (dt1 <= 0.0 || dt2 <= 0.0) continue
            val firstVelocity = (middle.second - first.second) / dt1
            val secondVelocity = (lastOfAcceleration.second - middle.second) / dt2
            if (firstVelocity <= 0.0 || secondVelocity <= firstVelocity) continue
            val subsequent = points.drop(index + 2)
            val peak = subsequent.maxByOrNull { it.second } ?: continue
            val current = points.last()
            if (current.first > peak.first && peak.second > 0.0 && (peak.second - current.second) / peak.second >= 0.30) return true
        }
        return false
    }

    private fun observedTradeVolumeUsd(trades: List<EngineBTradeObservation>, asOf: Long): EngineBObservedValue<Double> {
        if (trades.isEmpty()) return EngineBObservedValue.insufficient("no observed trades in 300-second window")
        var total = 0.0
        var latest: Long? = null
        for (trade in trades) {
            val tokens = trade.tokenAmount ?: return EngineBObservedValue.unknown(trade.observationTimestamp, "token amount not normalized")
            val tokenValue = usableNumber(tokens)
            val priceValue = usableNumber(trade.priceUsd)
            if (tokens.availability == FieldAvailability.STALE || trade.priceUsd.availability == FieldAvailability.STALE) {
                return EngineBObservedValue.stale(null, trade.observationTimestamp, source = "observed trade notional")
            }
            if (tokenValue == null || priceValue == null || tokenValue < 0.0 || priceValue < 0.0) {
                return EngineBObservedValue.unknown(trade.observationTimestamp, "incomplete observed USD trade notional")
            }
            val notional = tokenValue * priceValue
            if (!notional.isFinite()) return EngineBObservedValue.unknown(trade.observationTimestamp, "non-finite observed USD trade notional")
            total += notional
            latest = max(latest ?: trade.observationTimestamp, trade.observationTimestamp)
        }
        if (!total.isFinite()) return EngineBObservedValue.unknown(latest, "non-finite observed USD trade volume")
        val latestAt = latest ?: trades.last().observationTimestamp
        return EngineBObservedValue.available(
            total,
            latestAt,
            (asOf - latestAt).coerceAtLeast(0L),
            "observed token amount × observed trade USD price",
        )
    }

    private fun orderedTrades(input: List<EngineBTradeObservation>, asOf: Long): List<EngineBTradeObservation> = input.asSequence()
        .filter { it.observationTimestamp in (asOf - WINDOW_MS)..asOf }
        .sortedWith(
            compareBy<EngineBTradeObservation> { it.observationTimestamp }
                .thenBy { it.providerTimestamp ?: Long.MIN_VALUE }
                .thenBy { it.providerSequence ?: Long.MAX_VALUE }
                .thenBy { it.eventId },
        )
        .distinctBy { it.eventId }
        .toList()

    private fun <T : Number> fromMarketValue(value: MarketValue<T>, asOf: Long, source: String): EngineBObservedValue<Double> {
        val age = (asOf - (value.providerTimestamp ?: value.receivedTimestamp ?: value.observationTimestamp)).coerceAtLeast(0L)
        val numeric = value.value?.toDouble()?.takeIf { it.isFinite() }
        if (value.value != null && numeric == null) return EngineBObservedValue.unknown(value.observationTimestamp, "$source: non-finite value")
        if (numeric != null && numeric < 0.0) return EngineBObservedValue.unknown(value.observationTimestamp, "$source: negative numeric value rejected")
        return when {
            value.availability == FieldAvailability.STALE || (numeric != null && age > FRESH_AFTER_MS) ->
                EngineBObservedValue.stale(numeric, value.observationTimestamp, age, source)
            value.availability in setOf(FieldAvailability.KNOWN, FieldAvailability.ZERO) && numeric != null ->
                EngineBObservedValue.available(numeric, value.observationTimestamp, age, source)
            else -> EngineBObservedValue.unknown(value.observationTimestamp, source)
        }
    }

    private fun <T> asOfValue(value: EngineBObservedValue<T>, asOf: Long, maxAgeMs: Long = FRESH_AFTER_MS): EngineBObservedValue<T> {
        val at = value.observationTimestamp
        if (at != null && at > asOf) return EngineBObservedValue.unknown(source = value.source)
        if (value.availability in setOf(EngineBObservedAvailability.UNKNOWN, EngineBObservedAvailability.UNAVAILABLE, EngineBObservedAvailability.INSUFFICIENT)) return value
        if (at == null) return if (value.availability == EngineBObservedAvailability.STALE) value else EngineBObservedValue.insufficient(value.source)
        val age = (asOf - at).coerceAtLeast(0L)
        return when {
            value.availability == EngineBObservedAvailability.STALE -> value.copy(freshnessMs = age)
            age > maxAgeMs -> EngineBObservedValue.stale(value.value, at, age, value.source)
            else -> value.copy(freshnessMs = age)
        }
    }

    private fun validatePercent(value: EngineBObservedValue<Double>, label: String): EngineBObservedValue<Double> =
        if (value.availability == EngineBObservedAvailability.AVAILABLE && (value.value == null || value.value !in 0.0..100.0)) {
            EngineBObservedValue.unknown(value.observationTimestamp, "invalid $label percentage; expected 0..100")
        } else value

    private fun assessmentAvailability(values: List<EngineBObservedValue<*>>): EngineBObservedAvailability = when {
        values.any { it.availability == EngineBObservedAvailability.STALE } -> EngineBObservedAvailability.STALE
        values.all { it.availability == EngineBObservedAvailability.AVAILABLE } -> EngineBObservedAvailability.AVAILABLE
        values.any { it.availability == EngineBObservedAvailability.AVAILABLE || it.availability == EngineBObservedAvailability.PARTIAL } -> EngineBObservedAvailability.PARTIAL
        values.all { it.availability == EngineBObservedAvailability.UNAVAILABLE } -> EngineBObservedAvailability.UNAVAILABLE
        values.any { it.availability == EngineBObservedAvailability.INSUFFICIENT } -> EngineBObservedAvailability.INSUFFICIENT
        else -> EngineBObservedAvailability.UNKNOWN
    }

    private fun unavailableReason(label: String, availability: EngineBObservedAvailability): String? = when (availability) {
        EngineBObservedAvailability.AVAILABLE -> null
        EngineBObservedAvailability.PARTIAL -> "$label assessment partial"
        EngineBObservedAvailability.STALE -> "$label status stale"
        EngineBObservedAvailability.UNKNOWN -> "$label unknown"
        EngineBObservedAvailability.INSUFFICIENT -> "$label assessment insufficient"
        EngineBObservedAvailability.UNAVAILABLE -> "$label unavailable from normalized data"
    }

    private fun observedFeature(
        name: String,
        value: Double?,
        status: EngineBObservedAvailability,
        asOf: Long,
        observedAt: Long?,
        freshnessMs: Long?,
        sourceType: EngineBFeatureSource,
        sourceLabel: String? = null,
        windowSeconds: Int? = null,
        elapsedMs: Long? = null,
    ) = EngineBFeature(
        name = name,
        value = value,
        availability = fieldAvailability(status, value),
        freshnessMs = freshnessMs ?: observedAt?.let { (asOf - it).coerceAtLeast(0L) },
        sourceType = sourceType,
        observationTimestamp = observedAt,
        windowSeconds = windowSeconds,
        elapsedMs = elapsedMs,
        riskAvailability = status,
        sourceLabel = sourceLabel,
    )

    private fun numericFeature(
        name: String,
        value: EngineBObservedValue<Double>,
        asOf: Long,
        sourceType: EngineBFeatureSource,
        windowSeconds: Int? = null,
    ) = observedFeature(name, value.value, value.availability, asOf, value.observationTimestamp, value.freshnessMs, sourceType, value.source, windowSeconds)

    private fun derivedFeature(
        name: String,
        value: Double?,
        asOf: Long,
        observedAt: Long?,
        elapsedMs: Long?,
        status: EngineBObservedAvailability,
        sourceType: EngineBFeatureSource,
        windowSeconds: Int? = null,
    ) = observedFeature(
        name = name,
        value = value,
        status = status,
        asOf = asOf,
        observedAt = observedAt,
        freshnessMs = observedAt?.let { (asOf - it).coerceAtLeast(0L) },
        sourceType = sourceType,
        sourceLabel = "calculated from actual normalized observations",
        windowSeconds = windowSeconds,
        elapsedMs = elapsedMs,
    )

    private fun categoryFeature(
        name: String,
        value: EngineBObservedValue<String>,
        asOf: Long,
    ) = EngineBFeature(
        name = name,
        value = null,
        availability = fieldAvailability(value.availability, null),
        freshnessMs = value.freshnessMs ?: value.observationTimestamp?.let { (asOf - it).coerceAtLeast(0L) },
        sourceType = EngineBFeatureSource.SAFETY_DERIVED,
        observationTimestamp = value.observationTimestamp,
        categoryValue = value.value ?: value.availability.name,
        riskAvailability = value.availability,
        sourceLabel = value.source,
    )

    private fun categoricalFeature(
        name: String,
        value: EngineBObservedValue<String>,
        asOf: Long,
        sourceType: EngineBFeatureSource,
    ) = EngineBFeature(
        name = name,
        value = null,
        availability = fieldAvailability(value.availability, null),
        freshnessMs = value.freshnessMs ?: value.observationTimestamp?.let { (asOf - it).coerceAtLeast(0L) },
        sourceType = sourceType,
        observationTimestamp = value.observationTimestamp,
        categoryValue = value.value ?: value.availability.name,
        riskAvailability = value.availability,
        sourceLabel = value.source,
    )

    private fun fieldAvailability(status: EngineBObservedAvailability, value: Double?): FieldAvailability = when {
        status == EngineBObservedAvailability.STALE -> FieldAvailability.STALE
        status != EngineBObservedAvailability.AVAILABLE && status != EngineBObservedAvailability.PARTIAL -> FieldAvailability.UNKNOWN
        value == 0.0 -> FieldAvailability.ZERO
        else -> FieldAvailability.KNOWN
    }

    private fun availabilityFor(value: Double?, ifMissing: EngineBObservedAvailability) =
        if (value == null) ifMissing else EngineBObservedAvailability.AVAILABLE

    private fun EngineBObservedValue<Double>.availabilityOrInsufficient(value: Double?): EngineBObservedAvailability =
        if (value == null) EngineBObservedAvailability.INSUFFICIENT else EngineBObservedAvailability.AVAILABLE

    private fun <T, R> EngineBObservedValue<T>.mapValue(transform: (T) -> R): EngineBObservedValue<R> = when (availability) {
        EngineBObservedAvailability.AVAILABLE, EngineBObservedAvailability.PARTIAL -> value?.let {
            EngineBObservedValue(transform(it), availability, observationTimestamp, freshnessMs, source)
        } ?: EngineBObservedValue.unknown(observationTimestamp, source)
        EngineBObservedAvailability.STALE -> EngineBObservedValue.stale(value?.let(transform), observationTimestamp, freshnessMs, source)
        EngineBObservedAvailability.UNKNOWN -> EngineBObservedValue.unknown(observationTimestamp, source)
        EngineBObservedAvailability.INSUFFICIENT -> EngineBObservedValue.insufficient(source)
        EngineBObservedAvailability.UNAVAILABLE -> EngineBObservedValue.unavailable(source)
    }

    private fun usableNumber(value: MarketValue<Double>): Double? =
        value.value?.takeIf { it.isFinite() && it >= 0.0 && value.availability in setOf(FieldAvailability.KNOWN, FieldAvailability.ZERO) }

    private fun EngineBObservedValue<Double>.roundToOne(): Double = ((value ?: 0.0) * 10.0).toInt() / 10.0
    private fun Double.roundToOne(): Double = (this * 10.0).toInt() / 10.0

    private fun minOfNotNull(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> min(first, second)
    }
}
