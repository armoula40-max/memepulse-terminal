package com.solanasignal.engineb

import com.solanasignal.network.FieldAvailability
import com.solanasignal.network.MarketValue
import kotlin.math.max

internal data class EngineBMomentumSummary(
    val features: List<EngineBFeature>,
    val lifecycleState: EngineBLifecycleState?,
    val pumpPotential: EngineBAxisResult,
    val reasons: List<String>,
)

/**
 * Pure rolling calculations over at most the preferred five-minute history window.
 *
 * Deterministic conventions: price/cap velocity uses first-to-last distinct usable observations
 * in each lookback and their actual timestamp delta; acceleration uses the latest three distinct
 * observations and the actual elapsed time between adjacent-interval midpoints. Positive-flow
 * persistence is measured in six 5-second buckets anchored at T; a bucket is positive only when
 * both buy count and known SOL buy volume exceed sells. The persistent flag is known only with at
 * least two populated, volume-complete buckets, and is true only for two or more consecutive
 * positive buckets starting at the newest bucket.
 *
 * State thresholds: ACCELERATING requires positive 30-second selected movement, positive latest
 * two-observation price velocity, positive selected acceleration, buy-count and buy-volume ratios
 * both above 0.5, and persistent pressure. WEAKENING is set by a buy-to-sell takeover between the
 * two 30-second halves (either count or volume ratio moves from above 0.5 to below 0.5), a negative
 * latest price step after positive 30-second movement, or negative movement with negative
 * acceleration. Otherwise BUILDING requires positive selected movement, or both buy ratios above
 * 0.5 with persistent pressure; insufficient history leaves the existing lifecycle unchanged.
 * Pump potential is categorical, not a probability: HIGH requires ACCELERATING plus positive
 * buyer growth or price consistency >= 0.7; MODERATE requires positive movement with either buy
 * ratio above 0.5 (or the BUILDING pressure condition); LOW reflects WEAKENING. No risk model is
 * inferred from missing data.
 */
internal object EngineBMomentumCalculator {
    private const val MAX_WINDOW_SECONDS = 300
    private const val PERSISTENCE_BUCKET_SECONDS = 5
    private const val PERSISTENCE_WINDOW_SECONDS = 30
    private val WINDOWS_SECONDS = listOf(5, 10, 15, 30, 60, 120, 300)

    private data class PricePoint(val observedAt: Long, val value: Double, val eventId: String)
    private data class Measure(
        val value: Number?,
        val unavailableAs: FieldAvailability = FieldAvailability.UNKNOWN,
        val elapsedMs: Long? = null,
        val latestAt: Long? = null,
    )
    private data class WindowStats(
        val rows: List<EngineBTradeObservation>,
        val buyRows: List<EngineBTradeObservation>,
        val sellRows: List<EngineBTradeObservation>,
        val buyVolume: Measure,
        val sellVolume: Measure,
        val totalVolume: Measure,
        val buyRatio: Measure,
        val volumeBuyRatio: Measure,
        val countImbalance: Measure,
        val volumeImbalance: Measure,
    )

    fun calculate(input: EngineBInput): EngineBMomentumSummary {
        val asOf = input.marketState.observationTimestamp
        val lowerBound = asOf - MAX_WINDOW_SECONDS * 1_000L
        // Sort before deduplication so duplicate IDs resolve deterministically; observations after T
        // are excluded even if a provider timestamp or sequence would otherwise sort them earlier.
        val trades = input.tradeHistory.asSequence()
            .filter { it.observationTimestamp in lowerBound..asOf }
            .sortedWith(
                compareBy<EngineBTradeObservation> { it.observationTimestamp }
                    .thenBy { it.providerTimestamp ?: Long.MIN_VALUE }
                    .thenBy { it.providerSequence ?: Long.MAX_VALUE }
                    .thenBy { it.eventId },
            )
            .distinctBy { it.eventId }
            .toList()

        val features = mutableListOf<EngineBFeature>()
        val usdPrices = pricePoints(trades) { it.priceUsd }
        val nativePrices = pricePoints(trades) { it.priceNative }
        val usdCaps = pricePoints(trades) { it.marketCapUsd }
        for (window in WINDOWS_SECONDS) {
            val usdVelocity = velocity("priceVelocityUsd_$window", usdPrices, trades, window, asOf) { it.priceUsd }
            val nativeVelocity = velocity("priceVelocityNative_$window", nativePrices, trades, window, asOf) { it.priceNative }
            val capVelocity = velocity("marketCapVelocityUsd_$window", usdCaps, trades, window, asOf) { it.marketCapUsd }
            features += feature("priceVelocityUsd_$window", usdVelocity, asOf, window)
            features += feature("priceVelocityNative_$window", nativeVelocity, asOf, window)
            features += feature("marketCapVelocityUsd_$window", capVelocity, asOf, window)

            val usdAcceleration = acceleration("priceAccelerationUsd_$window", usdPrices, trades, window, asOf) { it.priceUsd }
            val nativeAcceleration = acceleration("priceAccelerationNative_$window", nativePrices, trades, window, asOf) { it.priceNative }
            val capAcceleration = acceleration("marketCapAccelerationUsd_$window", usdCaps, trades, window, asOf) { it.marketCapUsd }
            features += feature("priceAccelerationUsd_$window", usdAcceleration, asOf, window)
            features += feature("priceAccelerationNative_$window", nativeAcceleration, asOf, window)
            features += feature("marketCapAccelerationUsd_$window", capAcceleration, asOf, window)

            val rows = rowsInWindow(trades, asOf, window)
            val stats = stats(rows)
            appendPressureFeatures(features, stats, rows, asOf, window)
        }

        val latestUsdVelocity = lastPairVelocity("priceVelocityUsdRecent_30s", usdPrices, trades, 30, asOf) { it.priceUsd }
        val latestNativeVelocity = lastPairVelocity("priceVelocityNativeRecent_30s", nativePrices, trades, 30, asOf) { it.priceNative }
        features += feature("priceVelocityUsdRecent_30s", latestUsdVelocity, asOf, 30)
        features += feature("priceVelocityNativeRecent_30s", latestNativeVelocity, asOf, 30)

        val persistence = persistence(trades, asOf)
        features += feature("positiveFlowWindows_30s", persistence.first, asOf, PERSISTENCE_WINDOW_SECONDS)
        features += feature("consecutivePositiveFlowWindows_30s", persistence.second, asOf, PERSISTENCE_WINDOW_SECONDS)
        features += feature("persistentBuyPressure_30s", persistence.third, asOf, PERSISTENCE_WINDOW_SECONDS)

        val usdConsistency = consistency("priceConsistencyUsd_60s", usdPrices, trades, 60, asOf) { it.priceUsd }
        val nativeConsistency = consistency("priceConsistencyNative_60s", nativePrices, trades, 60, asOf) { it.priceNative }
        features += feature("priceConsistencyUsd_60s", usdConsistency, asOf, 60)
        features += feature("priceConsistencyNative_60s", nativeConsistency, asOf, 60)

        val usdPullback = pullback("pricePullbackPctUsd_60s", usdPrices, trades, 60, asOf) { it.priceUsd }
        val nativePullback = pullback("pricePullbackPctNative_60s", nativePrices, trades, 60, asOf) { it.priceNative }
        features += feature("pricePullbackPctUsd_60s", usdPullback, asOf, 60)
        features += feature("pricePullbackPctNative_60s", nativePullback, asOf, 60)
        val usdRecovery = recovery("priceRecoveryPctUsd_60s", usdPrices, trades, 60, asOf) { it.priceUsd }
        val nativeRecovery = recovery("priceRecoveryPctNative_60s", nativePrices, trades, 60, asOf) { it.priceNative }
        features += feature("priceRecoveryPctUsd_60s", usdRecovery, asOf, 60)
        features += feature("priceRecoveryPctNative_60s", nativeRecovery, asOf, 60)
        val capPullback = pullback("marketCapPullbackPctUsd_60s", usdCaps, trades, 60, asOf) { it.marketCapUsd }
        val capRecovery = recovery("marketCapRecoveryPctUsd_60s", usdCaps, trades, 60, asOf) { it.marketCapUsd }
        features += feature("marketCapPullbackPctUsd_60s", capPullback, asOf, 60)
        features += feature("marketCapRecoveryPctUsd_60s", capRecovery, asOf, 60)

        val buyerAcceleration = buyerAcceleration(trades, asOf, 30)
        features += feature("buyerAcceleration_30s", buyerAcceleration, asOf, 30)

        val featureByName = features.associateBy { it.name }
        fun value(name: String): Double? = featureByName[name]?.let { feature ->
            if (feature.availability == FieldAvailability.KNOWN || feature.availability == FieldAvailability.ZERO) {
                feature.value?.toDouble()?.takeIf(Double::isFinite)
            } else null
        }
        fun firstValue(vararg names: String): Pair<String, Double>? = names.firstNotNullOfOrNull { name -> value(name)?.let { name to it } }

        val priceVelocity = firstValue("priceVelocityUsd_30", "priceVelocityNative_30")
        val marketCapVelocity = value("marketCapVelocityUsd_30")?.let { "marketCapVelocityUsd_30" to it }
        val velocity = priceVelocity ?: marketCapVelocity
        val recentVelocity = firstValue("priceVelocityUsdRecent_30s", "priceVelocityNativeRecent_30s")
        val priceAcceleration = when (priceVelocity?.first) {
            "priceVelocityUsd_30" -> value("priceAccelerationUsd_30")
            "priceVelocityNative_30" -> value("priceAccelerationNative_30")
            else -> null
        }
        val marketCapAcceleration = value("marketCapAccelerationUsd_30")
        val acceleration = if (priceVelocity != null) priceAcceleration else marketCapAcceleration
        val buyRatio = value("buyRatio_30")
        val volumeBuyRatio = value("volumeBuyRatio_30")
        val countDominance = buyRatio?.let { it > 0.5 }
        val volumeDominance = volumeBuyRatio?.let { it > 0.5 }
        val persistent = value("persistentBuyPressure_30s") == 1.0
        val buyerGrowth = value("buyerGrowth_30")
        val consistency = firstValue("priceConsistencyUsd_60s", "priceConsistencyNative_60s")?.second
        val previousBuyRatio = value("previousBuyRatio_30")
        val currentBuyRatio = value("currentBuyRatio_30")
        val previousVolumeRatio = value("previousVolumeBuyRatio_30")
        val currentVolumeRatio = value("currentVolumeBuyRatio_30")
        val pressureTakeover = (previousBuyRatio != null && currentBuyRatio != null && previousBuyRatio > 0.5 && currentBuyRatio < 0.5) ||
            (previousVolumeRatio != null && currentVolumeRatio != null && previousVolumeRatio > 0.5 && currentVolumeRatio < 0.5)
        val weakening = pressureTakeover ||
            (velocity?.second?.let { it > 0.0 } == true && recentVelocity?.second?.let { it < 0.0 } == true) ||
            (velocity?.second?.let { it < 0.0 } == true && acceleration?.let { it < 0.0 } == true)
        val accelerating = velocity?.second?.let { it > 0.0 } == true &&
            recentVelocity?.second?.let { it > 0.0 } == true &&
            acceleration?.let { it > 0.0 } == true &&
            countDominance == true && volumeDominance == true && persistent
        val positiveMotion = velocity?.second?.let { it > 0.0 } == true || (countDominance == true && volumeDominance == true && persistent)
        val lifecycleState = when {
            accelerating -> EngineBLifecycleState.ACCELERATING
            weakening -> EngineBLifecycleState.WEAKENING
            positiveMotion -> EngineBLifecycleState.BUILDING
            else -> null
        }

        val pumpEvidence = linkedSetOf<String>()
        if (velocity?.second?.let { it > 0.0 } == true) pumpEvidence += velocity.first
        if (marketCapVelocity?.second?.let { it > 0.0 } == true) pumpEvidence += marketCapVelocity.first
        if (acceleration?.let { it > 0.0 } == true) {
            val accelerationName = when (velocity?.first) {
                "priceVelocityUsd_30" -> "priceAccelerationUsd_30"
                "priceVelocityNative_30" -> "priceAccelerationNative_30"
                else -> "marketCapAccelerationUsd_30"
            }
            pumpEvidence += accelerationName
        }
        if (countDominance == true) pumpEvidence += "buyRatio_30"
        if (volumeDominance == true) pumpEvidence += "volumeBuyRatio_30"
        if (persistent) pumpEvidence += "persistentBuyPressure_30s"
        if (buyerGrowth?.let { it > 0.0 } == true) pumpEvidence += "buyerGrowth_30"
        if (consistency?.let { it >= 0.7 } == true) {
            pumpEvidence += if (value("priceConsistencyUsd_60s") != null) "priceConsistencyUsd_60s" else "priceConsistencyNative_60s"
        }
        val pumpLevel = when {
            accelerating && (buyerGrowth?.let { it > 0.0 } == true || consistency?.let { it >= 0.7 } == true) -> EngineBAxisLevel.HIGH
            velocity?.second?.let { it > 0.0 } == true && (countDominance == true || volumeDominance == true) -> EngineBAxisLevel.MODERATE
            positiveMotion -> EngineBAxisLevel.MODERATE
            weakening -> EngineBAxisLevel.LOW
            else -> EngineBAxisLevel.NOT_ASSESSED
        }

        val reasons = linkedSetOf<String>()
        if (priceVelocity?.second?.let { it > 0.0 } == true) reasons += "positive_price_velocity"
        if (marketCapVelocity?.second?.let { it > 0.0 } == true) reasons += "positive_market_cap_velocity"
        if (priceAcceleration?.let { it > 0.0 } == true && priceVelocity?.second?.let { it > 0.0 } == true) reasons += "accelerating_price"
        if (marketCapAcceleration?.let { it > 0.0 } == true && marketCapVelocity?.second?.let { it > 0.0 } == true) reasons += "accelerating_market_cap"
        if (countDominance == true) reasons += "buy_count_dominance"
        if (volumeDominance == true) reasons += "buy_volume_dominance"
        if (buyerGrowth?.let { it > 0.0 } == true) reasons += "increasing_unique_buyers"
        if (persistent) reasons += "persistent_buy_pressure"
        if (consistency?.let { it >= 0.7 } == true && priceVelocity?.second?.let { it > 0.0 } == true) reasons += "momentum_consistent"
        if (weakening) reasons += "momentum_weakening"
        if (listOfNotNull(value("pricePullbackPctUsd_60s"), value("pricePullbackPctNative_60s"), value("marketCapPullbackPctUsd_60s")).any { it > 0.0 }) reasons += "pullback_detected"
        if (listOfNotNull(value("priceRecoveryPctUsd_60s"), value("priceRecoveryPctNative_60s"), value("marketCapRecoveryPctUsd_60s")).any { it > 0.0 }) reasons += "recovery_detected"
        if (velocity == null && trades.size < 2) reasons += "insufficient_history"

        return EngineBMomentumSummary(
            features = features,
            lifecycleState = lifecycleState,
            pumpPotential = EngineBAxisResult(pumpLevel, pumpEvidence.toList()),
            reasons = reasons.toList(),
        )
    }

    private fun orderedInWindow(points: List<PricePoint>, asOf: Long, windowSeconds: Int): List<PricePoint> {
        val since = asOf - windowSeconds * 1_000L
        return points.filter { it.observedAt in since..asOf }
    }

    private fun <T : Number> pricePoints(
        trades: List<EngineBTradeObservation>,
        select: (EngineBTradeObservation) -> MarketValue<T>,
    ): List<PricePoint> = trades.mapNotNull { trade ->
        val observed = select(trade)
        val price = observed.value?.toDouble()
        if (observed.availability !in USABLE_AVAILABILITY || price == null || !price.isFinite()) null
        else PricePoint(trade.observationTimestamp, price, trade.eventId)
    }.groupBy { it.observedAt }
        .toSortedMap()
        .values
        .map { it.last() }

    private fun <T : Number> velocity(
        name: String,
        points: List<PricePoint>,
        allTrades: List<EngineBTradeObservation>,
        window: Int,
        asOf: Long,
        select: (EngineBTradeObservation) -> MarketValue<T>,
    ): Measure {
        val windowPoints = orderedInWindow(points, asOf, window)
        if (windowPoints.size < 2) return Measure(null, staleIfPresent(allTrades, asOf, window, select))
        val first = windowPoints.first()
        val last = windowPoints.last()
        val elapsed = last.observedAt - first.observedAt
        if (elapsed <= 0L) return Measure(null, staleIfPresent(allTrades, asOf, window, select), latestAt = last.observedAt)
        val rate = (last.value - first.value) / (elapsed / 1_000.0)
        return if (rate.isFinite()) Measure(rate, elapsedMs = elapsed, latestAt = last.observedAt)
        else Measure(null, latestAt = last.observedAt)
    }

    private fun <T : Number> lastPairVelocity(
        name: String,
        points: List<PricePoint>,
        allTrades: List<EngineBTradeObservation>,
        window: Int,
        asOf: Long,
        select: (EngineBTradeObservation) -> MarketValue<T>,
    ): Measure {
        val windowPoints = orderedInWindow(points, asOf, window).takeLast(2)
        if (windowPoints.size < 2) return Measure(null, staleIfPresent(allTrades, asOf, window, select))
        val first = windowPoints[0]
        val last = windowPoints[1]
        val elapsed = last.observedAt - first.observedAt
        if (elapsed <= 0L) return Measure(null, latestAt = last.observedAt)
        val rate = (last.value - first.value) / (elapsed / 1_000.0)
        return if (rate.isFinite()) Measure(rate, elapsedMs = elapsed, latestAt = last.observedAt) else Measure(null, latestAt = last.observedAt)
    }

    private fun <T : Number> acceleration(
        name: String,
        points: List<PricePoint>,
        allTrades: List<EngineBTradeObservation>,
        window: Int,
        asOf: Long,
        select: (EngineBTradeObservation) -> MarketValue<T>,
    ): Measure {
        val windowPoints = orderedInWindow(points, asOf, window).takeLast(3)
        if (windowPoints.size < 3) return Measure(null, staleIfPresent(allTrades, asOf, window, select))
        val first = windowPoints[0]
        val middle = windowPoints[1]
        val last = windowPoints[2]
        val dt1 = middle.observedAt - first.observedAt
        val dt2 = last.observedAt - middle.observedAt
        if (dt1 <= 0L || dt2 <= 0L) return Measure(null, latestAt = last.observedAt)
        val velocity1 = (middle.value - first.value) / (dt1 / 1_000.0)
        val velocity2 = (last.value - middle.value) / (dt2 / 1_000.0)
        val midpointDeltaSeconds = (last.observedAt - first.observedAt) / 2_000.0
        if (midpointDeltaSeconds <= 0.0) return Measure(null, latestAt = last.observedAt)
        val value = (velocity2 - velocity1) / midpointDeltaSeconds
        return if (value.isFinite()) Measure(value, elapsedMs = ((last.observedAt - first.observedAt) / 2.0).toLong(), latestAt = last.observedAt)
        else Measure(null, latestAt = last.observedAt)
    }

    private fun <T : Number> staleIfPresent(
        trades: List<EngineBTradeObservation>,
        asOf: Long,
        window: Int,
        select: (EngineBTradeObservation) -> MarketValue<T>,
    ): FieldAvailability = if (trades.any {
            it.observationTimestamp in (asOf - window * 1_000L)..asOf && select(it).availability == FieldAvailability.STALE
        }) FieldAvailability.STALE else FieldAvailability.UNKNOWN

    private fun rowsInWindow(trades: List<EngineBTradeObservation>, asOf: Long, window: Int): List<EngineBTradeObservation> {
        val since = asOf - window * 1_000L
        return trades.filter { it.observationTimestamp in since..asOf }
    }

    private fun stats(rows: List<EngineBTradeObservation>): WindowStats {
        val buys = rows.filter { it.side == EngineBTradeSide.BUY }
        val sells = rows.filter { it.side == EngineBTradeSide.SELL }
        val latestAt = rows.lastOrNull()?.observationTimestamp
        val buyVolume = sumVolume(buys, rows.isNotEmpty(), latestAt)
        val sellVolume = sumVolume(sells, rows.isNotEmpty(), latestAt)
        val totalVolume = sumVolume(rows, rows.isNotEmpty(), latestAt)
        val buyRatio = if (rows.isEmpty()) Measure(null) else Measure(buys.size.toDouble() / rows.size, latestAt = latestAt)
        val volumeBuyRatio = ratio(buyVolume, totalVolume, latestAt)
        val countImbalance = if (rows.isEmpty()) Measure(null) else Measure((buys.size - sells.size).toDouble() / rows.size, latestAt = latestAt)
        val volumeImbalance = when {
            buyVolume.value == null || sellVolume.value == null -> Measure(null, combinedUnavailable(buyVolume, sellVolume), latestAt = latestAt)
            totalVolume.value == null -> Measure(null, totalVolume.unavailableAs, latestAt = latestAt)
            totalVolume.value.toDouble() <= 0.0 -> Measure(null, latestAt = latestAt)
            else -> Measure((buyVolume.value.toDouble() - sellVolume.value.toDouble()) / totalVolume.value.toDouble(), latestAt = latestAt)
        }
        return WindowStats(rows, buys, sells, buyVolume, sellVolume, totalVolume, buyRatio, volumeBuyRatio, countImbalance, volumeImbalance)
    }

    private fun sumVolume(rows: List<EngineBTradeObservation>, windowHasTrades: Boolean, latestAt: Long?): Measure {
        if (!windowHasTrades) return Measure(null)
        if (rows.isEmpty()) return Measure(0.0, latestAt = latestAt)
        if (rows.any { it.solAmount.availability == FieldAvailability.STALE }) return Measure(null, FieldAvailability.STALE, latestAt = latestAt)
        if (rows.any { it.solAmount.availability !in USABLE_AVAILABILITY || it.solAmount.value == null }) return Measure(null, latestAt = latestAt)
        val total = rows.sumOf { it.solAmount.value!!.toDouble() }
        return if (total.isFinite()) Measure(total, latestAt = latestAt) else Measure(null, latestAt = latestAt)
    }

    private fun ratio(numerator: Measure, denominator: Measure, latestAt: Long?): Measure = when {
        numerator.value == null -> Measure(null, numerator.unavailableAs, latestAt = latestAt)
        denominator.value == null -> Measure(null, denominator.unavailableAs, latestAt = latestAt)
        denominator.value.toDouble() <= 0.0 -> Measure(null, latestAt = latestAt)
        else -> Measure(numerator.value.toDouble() / denominator.value.toDouble(), latestAt = latestAt)
    }

    private fun combinedUnavailable(first: Measure, second: Measure): FieldAvailability = when {
        first.unavailableAs == FieldAvailability.STALE || second.unavailableAs == FieldAvailability.STALE -> FieldAvailability.STALE
        else -> FieldAvailability.UNKNOWN
    }

    private fun appendPressureFeatures(
        output: MutableList<EngineBFeature>,
        current: WindowStats,
        rows: List<EngineBTradeObservation>,
        asOf: Long,
        window: Int,
    ) {
        val latestAt = rows.lastOrNull()?.observationTimestamp
        fun add(name: String, measure: Measure) = output.add(feature(name, measure, asOf, window))
        add("totalTradeCount_$window", if (rows.isEmpty()) Measure(null) else Measure(rows.size, latestAt = latestAt))
        add("buyCount_$window", if (rows.isEmpty()) Measure(null) else Measure(current.buyRows.size, latestAt = latestAt))
        add("sellCount_$window", if (rows.isEmpty()) Measure(null) else Measure(current.sellRows.size, latestAt = latestAt))
        add("buyVolumeSol_$window", current.buyVolume)
        add("sellVolumeSol_$window", current.sellVolume)
        add("totalVolumeSol_$window", current.totalVolume)
        add("netFlowSol_$window", when {
            current.buyVolume.value == null || current.sellVolume.value == null ->
                Measure(null, combinedUnavailable(current.buyVolume, current.sellVolume), latestAt = latestAt)
            else -> Measure(current.buyVolume.value.toDouble() - current.sellVolume.value.toDouble(), latestAt = latestAt)
        })
        add("buyRatio_$window", current.buyRatio)
        add("volumeBuyRatio_$window", current.volumeBuyRatio)
        add("countImbalance_$window", current.countImbalance)
        add("volumeImbalance_$window", current.volumeImbalance)
        add("uniqueBuyers_$window", uniqueCount(rows, EngineBTradeSide.BUY, latestAt))
        add("uniqueSellers_$window", uniqueCount(rows, EngineBTradeSide.SELL, latestAt))

        val midpoint = asOf - window * 500L
        val previousRows = rows.filter { it.observationTimestamp < midpoint }
        val currentRows = rows.filter { it.observationTimestamp >= midpoint }
        val previous = stats(previousRows)
        val recent = stats(currentRows)
        val elapsed = meanTimestamp(currentRows)?.minus(meanTimestamp(previousRows) ?: 0.0)
        val elapsedMs = elapsed?.takeIf { it > 0.0 }?.toLong()
        add("buyCountDelta_$window", delta(previousRows, currentRows, previous.buyRows.size, recent.buyRows.size))
        add("sellCountDelta_$window", delta(previousRows, currentRows, previous.sellRows.size, recent.sellRows.size))
        add("buyVolumeDeltaSol_$window", deltaMeasure(previousRows, currentRows, previous.buyVolume, recent.buyVolume))
        add("sellVolumeDeltaSol_$window", deltaMeasure(previousRows, currentRows, previous.sellVolume, recent.sellVolume))
        add("buyRatioDelta_$window", deltaMeasure(previousRows, currentRows, previous.buyRatio, recent.buyRatio))
        add("volumeBuyRatioDelta_$window", deltaMeasure(previousRows, currentRows, previous.volumeBuyRatio, recent.volumeBuyRatio))
        add("buyerGrowth_$window", uniqueGrowth(previousRows, currentRows, EngineBTradeSide.BUY))
        add("sellerGrowth_$window", uniqueGrowth(previousRows, currentRows, EngineBTradeSide.SELL))
        add("buyCountVelocity_$window", rate(delta(previousRows, currentRows, previous.buyRows.size, recent.buyRows.size), elapsedMs))
        add("buyVolumeVelocitySol_$window", rate(deltaMeasure(previousRows, currentRows, previous.buyVolume, recent.buyVolume), elapsedMs))
        add("buyerVelocity_$window", rate(uniqueGrowth(previousRows, currentRows, EngineBTradeSide.BUY), elapsedMs))
        add("sellCountVelocity_$window", rate(delta(previousRows, currentRows, previous.sellRows.size, recent.sellRows.size), elapsedMs))
        add("sellVolumeVelocitySol_$window", rate(deltaMeasure(previousRows, currentRows, previous.sellVolume, recent.sellVolume), elapsedMs))

        if (window == 30) {
            add("previousBuyRatio_30", previous.buyRatio)
            add("currentBuyRatio_30", recent.buyRatio)
            add("previousVolumeBuyRatio_30", previous.volumeBuyRatio)
            add("currentVolumeBuyRatio_30", recent.volumeBuyRatio)
        }
    }

    private fun delta(
        previousRows: List<EngineBTradeObservation>,
        currentRows: List<EngineBTradeObservation>,
        previous: Int,
        current: Int,
    ): Measure = if (previousRows.isEmpty() || currentRows.isEmpty()) Measure(null)
    else Measure(current - previous, latestAt = currentRows.last().observationTimestamp)

    private fun deltaMeasure(
        previousRows: List<EngineBTradeObservation>,
        currentRows: List<EngineBTradeObservation>,
        previous: Measure,
        current: Measure,
    ): Measure = when {
        previousRows.isEmpty() || currentRows.isEmpty() -> Measure(null)
        previous.value == null -> Measure(null, previous.unavailableAs)
        current.value == null -> Measure(null, current.unavailableAs)
        else -> Measure(
            current.value.toDouble() - previous.value.toDouble(),
            elapsedMs = meanTimestamp(currentRows)?.minus(meanTimestamp(previousRows) ?: 0.0)?.toLong(),
            latestAt = currentRows.last().observationTimestamp,
        )
    }

    private fun rate(delta: Measure, elapsedMs: Long?): Measure {
        if (delta.value == null) return delta
        if (elapsedMs == null || elapsedMs <= 0L) return Measure(null, latestAt = delta.latestAt)
        val rate = delta.value.toDouble() / (elapsedMs / 1_000.0)
        return if (rate.isFinite()) Measure(rate, elapsedMs = elapsedMs, latestAt = delta.latestAt) else Measure(null, latestAt = delta.latestAt)
    }

    private fun uniqueCount(rows: List<EngineBTradeObservation>, side: EngineBTradeSide, latestAt: Long?): Measure {
        if (rows.isEmpty()) return Measure(null)
        val relevant = rows.filter { it.side == side }
        if (relevant.isEmpty()) return Measure(0, latestAt = latestAt)
        if (relevant.any { it.trader.isNullOrBlank() }) return Measure(null, latestAt = latestAt)
        return Measure(relevant.mapNotNull { it.trader }.toSet().size, latestAt = latestAt)
    }

    private fun uniqueGrowth(
        previousRows: List<EngineBTradeObservation>,
        currentRows: List<EngineBTradeObservation>,
        side: EngineBTradeSide,
    ): Measure {
        if (previousRows.isEmpty() || currentRows.isEmpty()) return Measure(null)
        val previous = uniqueCount(previousRows, side, previousRows.last().observationTimestamp)
        val current = uniqueCount(currentRows, side, currentRows.last().observationTimestamp)
        if (previous.value == null || current.value == null) return Measure(null)
        return Measure(current.value.toDouble() - previous.value.toDouble(), latestAt = currentRows.last().observationTimestamp)
    }

    private fun meanTimestamp(rows: List<EngineBTradeObservation>): Double? =
        rows.takeIf { it.isNotEmpty() }?.map { it.observationTimestamp.toDouble() }?.average()?.takeIf(Double::isFinite)

    private fun persistence(trades: List<EngineBTradeObservation>, asOf: Long): Triple<Measure, Measure, Measure> {
        val bucketMs = PERSISTENCE_BUCKET_SECONDS * 1_000L
        val buckets = (0 until PERSISTENCE_WINDOW_SECONDS / PERSISTENCE_BUCKET_SECONDS).map { index ->
            val end = asOf - index * bucketMs
            val start = end - bucketMs
            trades.filter { trade ->
                if (index == PERSISTENCE_WINDOW_SECONDS / PERSISTENCE_BUCKET_SECONDS - 1) {
                    trade.observationTimestamp in start..end
                } else {
                    trade.observationTimestamp > start && trade.observationTimestamp <= end
                }
            }
        }
        val populated = buckets.filter { it.isNotEmpty() }
        if (populated.isEmpty()) return Triple(Measure(null), Measure(null), Measure(null))
        val bucketDominance = buckets.map { rows ->
            if (rows.isEmpty()) null
            else {
                val stats = stats(rows)
                val countPositive = stats.buyRows.size > stats.sellRows.size
                val volumePositive = stats.buyVolume.value != null && stats.sellVolume.value != null &&
                    stats.buyVolume.value.toDouble() > stats.sellVolume.value.toDouble()
                val volumesKnown = stats.buyVolume.value != null && stats.sellVolume.value != null
                if (!volumesKnown) null else countPositive && volumePositive
            }
        }
        val valid = bucketDominance.filterNotNull()
        val latestAt = populated.maxOf { it.maxOf(EngineBTradeObservation::observationTimestamp) }
        val positiveCount = if (valid.size == populated.size) Measure(valid.count { it }, latestAt = latestAt) else Measure(null)
        val currentBucketValid = bucketDominance.firstOrNull() != null
        val consecutive = if (!currentBucketValid) null else bucketDominance.takeWhile { it == true }.size
        val consecutiveMeasure = consecutive?.let { Measure(it, latestAt = latestAt) } ?: Measure(null)
        val persistent = if (populated.size < 2 || valid.size != populated.size || consecutive == null) Measure(null)
        else Measure(if (consecutive >= 2) 1 else 0, latestAt = latestAt)
        return Triple(positiveCount, consecutiveMeasure, persistent)
    }

    private fun <T : Number> consistency(
        name: String,
        points: List<PricePoint>,
        trades: List<EngineBTradeObservation>,
        window: Int,
        asOf: Long,
        select: (EngineBTradeObservation) -> MarketValue<T>,
    ): Measure {
        val inWindow = orderedInWindow(points, asOf, window)
        if (inWindow.size < 3) return Measure(null, staleIfPresent(trades, asOf, window, select))
        val directions = inWindow.zipWithNext().map { (a, b) -> b.value.compareTo(a.value) }
        if (directions.size < 2) return Measure(null, latestAt = inWindow.last().observedAt)
        val positive = directions.count { it > 0 }
        val value = positive.toDouble() / directions.size
        return Measure(value, elapsedMs = inWindow.last().observedAt - inWindow.first().observedAt, latestAt = inWindow.last().observedAt)
    }

    private fun <T : Number> pullback(
        name: String,
        points: List<PricePoint>,
        trades: List<EngineBTradeObservation>,
        window: Int,
        asOf: Long,
        select: (EngineBTradeObservation) -> MarketValue<T>,
    ): Measure {
        val inWindow = orderedInWindow(points, asOf, window)
        if (inWindow.size < 3) return Measure(null, staleIfPresent(trades, asOf, window, select))
        val peak = inWindow.dropLast(1).maxOf { it.value }
        val latest = inWindow.last()
        if (peak <= 0.0) return Measure(null, latestAt = latest.observedAt)
        return Measure(max(0.0, (peak - latest.value) / peak), elapsedMs = inWindow.last().observedAt - inWindow.first().observedAt, latestAt = latest.observedAt)
    }

    private fun <T : Number> recovery(
        name: String,
        points: List<PricePoint>,
        trades: List<EngineBTradeObservation>,
        window: Int,
        asOf: Long,
        select: (EngineBTradeObservation) -> MarketValue<T>,
    ): Measure {
        val inWindow = orderedInWindow(points, asOf, window)
        if (inWindow.size < 3) return Measure(null, staleIfPresent(trades, asOf, window, select))
        val latest = inWindow.last()
        var peak = inWindow.first().value
        var trough: Double? = null
        inWindow.drop(1).dropLast(1).forEach { point ->
            if (point.value > peak) {
                peak = point.value
                trough = null
            } else if (point.value < peak && (trough == null || point.value < trough!!)) {
                trough = point.value
            }
        }
        val low = trough ?: return Measure(null, latestAt = latest.observedAt)
        if (low <= 0.0 || latest.value <= low) return Measure(null, latestAt = latest.observedAt)
        val value = (latest.value - low) / low
        return if (value.isFinite()) Measure(value, elapsedMs = inWindow.last().observedAt - inWindow.first().observedAt, latestAt = latest.observedAt)
        else Measure(null, latestAt = latest.observedAt)
    }

    private fun buyerAcceleration(trades: List<EngineBTradeObservation>, asOf: Long, window: Int): Measure {
        val inWindow = rowsInWindow(trades, asOf, window)
        val third = window * 1_000L / 3
        val lower = asOf - window * 1_000L
        val segments = listOf(
            inWindow.filter { it.observationTimestamp >= lower && it.observationTimestamp < lower + third },
            inWindow.filter { it.observationTimestamp >= lower + third && it.observationTimestamp < lower + 2 * third },
            inWindow.filter { it.observationTimestamp >= lower + 2 * third },
        )
        if (segments.any { it.isEmpty() }) return Measure(null)
        val counts = segments.map { uniqueCount(it, EngineBTradeSide.BUY, it.last().observationTimestamp) }
        if (counts.any { it.value == null }) return Measure(null)
        val centers = segments.mapNotNull(::meanTimestamp)
        if (centers.size != 3) return Measure(null)
        val dt1 = (centers[1] - centers[0]) / 1_000.0
        val dt2 = (centers[2] - centers[1]) / 1_000.0
        if (dt1 <= 0.0 || dt2 <= 0.0) return Measure(null, latestAt = segments.last().last().observationTimestamp)
        val velocity1 = (counts[1].value!!.toDouble() - counts[0].value!!.toDouble()) / dt1
        val velocity2 = (counts[2].value!!.toDouble() - counts[1].value!!.toDouble()) / dt2
        val midpointDelta = ((centers[1] + centers[2]) / 2.0 - (centers[0] + centers[1]) / 2.0) / 1_000.0
        if (midpointDelta <= 0.0) return Measure(null, latestAt = segments.last().last().observationTimestamp)
        val acceleration = (velocity2 - velocity1) / midpointDelta
        return if (acceleration.isFinite()) Measure(acceleration, elapsedMs = (midpointDelta * 1_000.0).toLong(), latestAt = segments.last().last().observationTimestamp)
        else Measure(null, latestAt = segments.last().last().observationTimestamp)
    }

    private fun feature(name: String, measure: Measure, asOf: Long, window: Int): EngineBFeature {
        val availability = when {
            measure.value == null -> measure.unavailableAs
            measure.value.toDouble() == 0.0 -> FieldAvailability.ZERO
            else -> FieldAvailability.KNOWN
        }
        return EngineBFeature(
            name = name,
            value = measure.value,
            availability = availability,
            freshnessMs = measure.latestAt?.let { (asOf - it).coerceAtLeast(0L) },
            sourceType = EngineBFeatureSource.TRADE_DERIVED,
            observationTimestamp = asOf,
            windowSeconds = window,
            elapsedMs = measure.elapsedMs,
        )
    }

    private val USABLE_AVAILABILITY = setOf(FieldAvailability.KNOWN, FieldAvailability.ZERO)
}
