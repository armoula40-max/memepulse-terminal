package com.solanasignal.paper

import com.solanasignal.engineb.EngineBAuthorityStatus
import com.solanasignal.engineb.EngineBAxisLevel
import com.solanasignal.engineb.EngineBAxisResult
import com.solanasignal.engineb.EngineBDeployerRiskStatus
import com.solanasignal.engineb.EngineBFreshnessStatus
import com.solanasignal.engineb.EngineBInput
import com.solanasignal.engineb.EngineBLifecycleState
import com.solanasignal.engineb.EngineBLiquidityObservation
import com.solanasignal.engineb.EngineBObservedValue
import com.solanasignal.engineb.EngineBSafetyContext
import com.solanasignal.engineb.EngineBSignalState
import com.solanasignal.engineb.EngineBTradeObservation
import com.solanasignal.engineb.EngineBTradeSide
import com.solanasignal.network.FieldAvailability
import com.solanasignal.network.LiveMarketState
import com.solanasignal.network.MarketValue

internal object PaperTradingFixtures {
    const val T0 = 1_700_000_000_000L

    fun state(
        mint: String = "paper-mint",
        at: Long = T0,
        priceUsd: Double = 1.0,
        eventId: String = "snapshot-$mint-$at",
        lifecycle: String = "TRADING",
        priceOverride: MarketValue<Double>? = null,
        liquidityUsd: Double? = 50_000.0,
    ): LiveMarketState = LiveMarketState.unknown(
        mint = mint,
        lifecycle = lifecycle,
        providerTimestamp = at,
        receivedTimestamp = at,
        observationTimestamp = at,
        providerSequence = at,
        eventId = eventId,
    ).copy(
        tokenAmount = known(10_000.0, at),
        solAmount = known(1.0, at),
        priceNative = known(0.001, at),
        priceUsd = priceOverride ?: known(priceUsd, at),
        marketCapNative = known(10_000.0, at),
        marketCapUsd = known(1_000_000.0, at),
        liquidityUsd = MarketValue.of(liquidityUsd, at, at, at),
        holders = MarketValue.of(1_000, at, at, at),
    )

    fun input(
        mint: String = "paper-mint",
        at: Long = T0,
        priceUsd: Double = 1.0,
        signalState: EngineBSignalState = EngineBSignalState.ENTRY_CANDIDATE,
        lifecycle: String = "TRADING",
        priceOverride: MarketValue<Double>? = null,
        liquidityUsd: Double? = 50_000.0,
        riskLevel: EngineBAxisLevel? = null,
        id: String = "signal-$mint-$at",
        allowEntry: Boolean = true,
    ): PaperMarketInput {
        val state = state(mint, at, priceUsd, "snapshot-$mint-$at-$id", lifecycle, priceOverride, liquidityUsd)
        val evaluated = com.solanasignal.engineb.EngineB.evaluate(
            EngineBInput(
                marketState = state,
                tradeHistory = trades(at),
                liquidityHistory = listOf(
                    liquidity(at - 10_000L, 50_000.0, "liq-early-$id"),
                    liquidity(at - 5_000L, 50_100.0, "liq-late-$id"),
                ),
                safetyContext = safeSafety(at),
            ),
        )
        val result = evaluated.copy(
            timestamp = at,
            signalState = signalState,
            lifecycleState = when {
                lifecycle.uppercase() in setOf("REMOVED", "INVALID", "DELISTED", "UNAVAILABLE") -> EngineBLifecycleState.REMOVED
                signalState == EngineBSignalState.WEAKENING -> EngineBLifecycleState.WEAKENING
                signalState in setOf(EngineBSignalState.ENTRY_CANDIDATE, EngineBSignalState.STRONG_CANDIDATE) -> EngineBLifecycleState.ACCELERATING
                else -> EngineBLifecycleState.WATCH
            },
            collapseRisk = riskLevel?.let { EngineBAxisResult(it, listOf("fixture_risk"), listOf("fixture")) } ?: evaluated.collapseRisk,
            dataQuality = if (priceOverride?.availability == FieldAvailability.STALE) {
                com.solanasignal.engineb.EngineBDataQuality.STALE
            } else {
                com.solanasignal.engineb.EngineBDataQuality.COMPLETE
            },
            freshnessAssessment = if (priceOverride?.availability == FieldAvailability.STALE) {
                com.solanasignal.engineb.EngineBFreshnessAssessment(EngineBFreshnessStatus.STALE)
            } else {
                com.solanasignal.engineb.EngineBFreshnessAssessment(EngineBFreshnessStatus.FRESH)
            },
        )
        return PaperMarketInput(id, result, state, tokenSymbol = "PAPR", allowEntry = allowEntry)
    }

    private fun <T : Number> known(value: T, at: Long): MarketValue<T> = MarketValue.of(value, at, at, at)

    private fun trades(asOf: Long): List<EngineBTradeObservation> = listOf(
        trade(asOf, 1, -28_000, 0.01),
        trade(asOf, 2, -18_000, 0.02),
        trade(asOf, 3, -8_000, 0.04),
        trade(asOf, 4, -1_000, 0.08),
    )

    private fun trade(asOf: Long, id: Int, offset: Long, price: Double) : EngineBTradeObservation {
        val at = asOf + offset
        return EngineBTradeObservation(
            eventId = "paper-fixture-trade-$id-$asOf",
            side = EngineBTradeSide.BUY,
            trader = "wallet-$id",
            observationTimestamp = at,
            providerTimestamp = at,
            providerSequence = id.toLong(),
            solAmount = known(1.0, at),
            priceNative = known(price / 100.0, at),
            priceUsd = known(price, at),
            marketCapUsd = known(price * 1_000_000.0, at),
            tokenAmount = known(100.0, at),
        )
    }

    private fun liquidity(at: Long, usd: Double, id: String) = EngineBLiquidityObservation(
        eventId = id,
        liquidityUsd = EngineBObservedValue.available(usd, at, source = "paper_fixture"),
    )

    private fun safeSafety(at: Long) = EngineBSafetyContext(
        topHolderPercent = EngineBObservedValue.available(8.0, at - 10_000L, source = "paper_fixture"),
        topFivePercent = EngineBObservedValue.available(22.0, at - 10_000L, source = "paper_fixture"),
        topTenPercent = EngineBObservedValue.available(35.0, at - 10_000L, source = "paper_fixture"),
        mintAuthority = EngineBObservedValue.available(EngineBAuthorityStatus.SAFE, at - 10_000L, source = "paper_fixture"),
        freezeAuthority = EngineBObservedValue.available(EngineBAuthorityStatus.SAFE, at - 10_000L, source = "paper_fixture"),
        creatorIdentity = EngineBObservedValue.available("fixture-creator", at - 10_000L, source = "paper_fixture"),
        deployerRisk = EngineBObservedValue.available(EngineBDeployerRiskStatus.NO_RISK_EVIDENCE, at - 10_000L, source = "paper_fixture"),
    )

}
