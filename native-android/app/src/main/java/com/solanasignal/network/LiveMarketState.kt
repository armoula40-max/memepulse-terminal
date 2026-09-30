package com.solanasignal.network

data class LiveMarketState(
    val mint: String,
    val lifecycle: String,
    val tokenAmount: MarketValue<Double>,
    val solAmount: MarketValue<Double>,
    val priceNative: MarketValue<Double>,
    val priceUsd: MarketValue<Double>,
    val marketCapNative: MarketValue<Double>,
    val marketCapUsd: MarketValue<Double>,
    val liquidityUsd: MarketValue<Double>,
    val holders: MarketValue<Int>,
    val providerTimestamp: Long?,
    val receivedTimestamp: Long,
    val observationTimestamp: Long,
    val providerSequence: Long?,
    val eventId: String,
) {
    fun at(now: Long): LiveMarketState = copy(
        tokenAmount = tokenAmount.at(now),
        solAmount = solAmount.at(now),
        priceNative = priceNative.at(now),
        priceUsd = priceUsd.at(now),
        marketCapNative = marketCapNative.at(now),
        marketCapUsd = marketCapUsd.at(now),
        liquidityUsd = liquidityUsd.at(now),
        holders = holders.at(now),
    )

    fun apply(event: NormalizedProviderEvent): LiveMarketState {
        require(event.mint == mint) { "Cannot apply an event for a different mint" }
        return when (event) {
            is NormalizedTokenCreatedEvent -> copy(
                lifecycle = "CREATED",
                providerTimestamp = event.providerTimestamp,
                receivedTimestamp = event.receivedTimestamp,
                observationTimestamp = event.observationTimestamp,
                providerSequence = event.providerSequence,
                eventId = event.eventId,
            )
            is NormalizedMigrationEvent -> copy(
                lifecycle = "MIGRATED",
                providerTimestamp = event.providerTimestamp,
                receivedTimestamp = event.receivedTimestamp,
                observationTimestamp = event.observationTimestamp,
                providerSequence = event.providerSequence,
                eventId = event.eventId,
            )
            is NormalizedTradeEvent -> copy(
                tokenAmount = MarketValue.of(event.tokenAmount, event.providerTimestamp, event.receivedTimestamp, event.observationTimestamp),
                solAmount = MarketValue.of(event.solAmount, event.providerTimestamp, event.receivedTimestamp, event.observationTimestamp),
                priceNative = event.priceNative?.let { MarketValue.of(it, event.providerTimestamp, event.receivedTimestamp, event.observationTimestamp) }
                    ?: priceNative.at(event.observationTimestamp),
                priceUsd = event.priceUsd?.let { MarketValue.of(it, event.providerTimestamp, event.receivedTimestamp, event.observationTimestamp) }
                    ?: priceUsd.at(event.observationTimestamp),
                marketCapNative = event.marketCapNative?.let { MarketValue.of(it, event.providerTimestamp, event.receivedTimestamp, event.observationTimestamp) }
                    ?: marketCapNative.at(event.observationTimestamp),
                marketCapUsd = event.marketCapUsd?.let { MarketValue.of(it, event.providerTimestamp, event.receivedTimestamp, event.observationTimestamp) }
                    ?: marketCapUsd.at(event.observationTimestamp),
                providerTimestamp = event.providerTimestamp,
                receivedTimestamp = event.receivedTimestamp,
                observationTimestamp = event.observationTimestamp,
                providerSequence = event.providerSequence,
                eventId = event.eventId,
            )
        }
    }

    companion object {
        fun unknown(
            mint: String,
            lifecycle: String,
            providerTimestamp: Long?,
            receivedTimestamp: Long,
            observationTimestamp: Long,
            providerSequence: Long?,
            eventId: String,
        ) = LiveMarketState(
            mint = mint,
            lifecycle = lifecycle,
            tokenAmount = MarketValue.unknown(providerTimestamp, receivedTimestamp, observationTimestamp),
            solAmount = MarketValue.unknown(providerTimestamp, receivedTimestamp, observationTimestamp),
            priceNative = MarketValue.unknown(providerTimestamp, receivedTimestamp, observationTimestamp),
            priceUsd = MarketValue.unknown(providerTimestamp, receivedTimestamp, observationTimestamp),
            marketCapNative = MarketValue.unknown(providerTimestamp, receivedTimestamp, observationTimestamp),
            marketCapUsd = MarketValue.unknown(providerTimestamp, receivedTimestamp, observationTimestamp),
            liquidityUsd = MarketValue.unknown(providerTimestamp, receivedTimestamp, observationTimestamp),
            holders = MarketValue.unknown(providerTimestamp, receivedTimestamp, observationTimestamp),
            providerTimestamp = providerTimestamp,
            receivedTimestamp = receivedTimestamp,
            observationTimestamp = observationTimestamp,
            providerSequence = providerSequence,
            eventId = eventId,
        )

        fun from(event: NormalizedProviderEvent): LiveMarketState =
            unknown(
                mint = event.mint,
                lifecycle = when (event) {
                    is NormalizedTokenCreatedEvent -> "CREATED"
                    is NormalizedMigrationEvent -> "MIGRATED"
                    is NormalizedTradeEvent -> "TRADING"
                },
                providerTimestamp = event.providerTimestamp,
                receivedTimestamp = event.receivedTimestamp,
                observationTimestamp = event.observationTimestamp,
                providerSequence = event.providerSequence,
                eventId = event.eventId,
            ).apply(event)
    }
}
