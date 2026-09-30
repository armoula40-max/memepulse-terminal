package com.solanasignal.engine

import android.content.Context
import androidx.room.withTransaction
import com.solanasignal.data.*
import com.solanasignal.network.*
import com.solanasignal.security.Secrets
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class SignalRepository(context: Context) {
    private val db = SignalDatabase.get(context)
    private val dao = db.dao()
    private val secrets = Secrets(context)
    private val manager = PumpPortalWebSocketManager { secrets.getPumpPortalKey() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentEventIds = RecentEventIds()
    private val _liveMarketStates = MutableStateFlow<Map<String, LiveMarketState>>(emptyMap())
    private var collector: Job? = null

    val connection = manager.state
    val diagnostics = manager.diagnostics
    val signals = dao.signals()
    val tokens = dao.tokens()
    val liveMarketStates: StateFlow<Map<String, LiveMarketState>> = _liveMarketStates.asStateFlow()

    fun setApiKey(value: String) { secrets.setPumpPortalKey(value) }
    fun apiKeyConfigured() = secrets.getPumpPortalKey().isNotBlank()

    fun start() {
        if (collector?.isActive == true) return
        collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            manager.events.collect { event -> handleEvent(event) }
        }
        manager.start()
    }

    fun stop() {
        manager.stop()
        collector?.cancel()
        collector = null
    }

    private suspend fun handleEvent(event: NormalizedProviderEvent) {
        val inserted = db.withTransaction {
            val accepted = dao.insertProviderEvent(
                ProviderEventEntity(
                    eventId = event.eventId,
                    mint = event.mint,
                    eventType = when (event) {
                        is NormalizedTokenCreatedEvent -> "CREATE"
                        is NormalizedTradeEvent -> event.txType.uppercase()
                        is NormalizedMigrationEvent -> "MIGRATION"
                    },
                    providerTimestamp = event.providerTimestamp,
                    receivedTimestamp = event.receivedTimestamp,
                    observationTimestamp = event.observationTimestamp,
                    providerSequence = event.providerSequence,
                    eventTimestamp = event.eventTimestamp,
                ),
            )
            if (accepted == -1L) return@withTransaction false
            if (event is NormalizedTradeEvent && dao.insertTrade(event.toEntity()) == -1L) return@withTransaction false
            true
        }
        if (!inserted || !recentEventIds.addIfNew(event.eventId)) {
            recentEventIds.addIfNew(event.eventId)
            manager.recordDuplicate()
            return
        }

        val prior = _liveMarketStates.value[event.mint] ?: dao.token(event.mint)?.toLiveMarketState()
        val isLatest = prior == null || compareOrder(event, prior) > 0
        if (!isLatest) manager.recordLateEvent()
        val state = when {
            prior == null -> LiveMarketState.from(event)
            isLatest -> prior.apply(event)
            else -> prior.at(System.currentTimeMillis())
        }.at(System.currentTimeMillis())
        if (isLatest) _liveMarketStates.update { it + (event.mint to state) }

        when (event) {
            is NormalizedTokenCreatedEvent -> {
                persistTokenSnapshot(state, event)
                manager.subscribeToken(event.mint)
            }
            is NormalizedMigrationEvent -> persistTokenSnapshot(state, event)
            is NormalizedTradeEvent -> {
                persistTokenSnapshot(state, event)
                onTrade(event, state)
            }
        }
    }

    private suspend fun persistTokenSnapshot(state: LiveMarketState, event: NormalizedProviderEvent) {
        val previous = dao.token(event.mint)
        val creation = event as? NormalizedTokenCreatedEvent
        val current = state.at(System.currentTimeMillis())
        val observedValues = listOf(
            current.tokenAmount.availability,
            current.solAmount.availability,
            current.priceNative.availability,
            current.priceUsd.availability,
            current.marketCapNative.availability,
            current.marketCapUsd.availability,
            current.liquidityUsd.availability,
            current.holders.availability,
        )
        dao.upsertToken(
            TokenEntity(
                mint = event.mint,
                symbol = creation?.symbol ?: previous?.symbol,
                name = creation?.name ?: previous?.name,
                creator = creation?.creator ?: previous?.creator,
                uri = creation?.uri ?: previous?.uri,
                createdAt = previous?.createdAt ?: if (creation != null) event.providerTimestamp else null,
                firstSeenAt = minOf(previous?.firstSeenAt ?: event.receivedTimestamp, event.receivedTimestamp),
                marketCapUsd = current.marketCapUsd.value,
                liquidityUsd = current.liquidityUsd.value,
                priceUsd = current.priceUsd.value,
                lifecycle = current.lifecycle,
                source = previous?.source ?: "pumpportal",
                marketCapNative = current.marketCapNative.value,
                priceNative = current.priceNative.value,
                latestTokenAmount = current.tokenAmount.value,
                latestSolAmount = current.solAmount.value,
                holders = current.holders.value,
                latestTokenAmountAvailability = current.tokenAmount.availability.name,
                latestSolAmountAvailability = current.solAmount.availability.name,
                marketCapNativeAvailability = current.marketCapNative.availability.name,
                marketCapUsdAvailability = current.marketCapUsd.availability.name,
                priceNativeAvailability = current.priceNative.availability.name,
                priceUsdAvailability = current.priceUsd.availability.name,
                liquidityUsdAvailability = current.liquidityUsd.availability.name,
                holdersAvailability = current.holders.availability.name,
                providerTimestamp = current.providerTimestamp,
                receivedTimestamp = current.receivedTimestamp,
                observationTimestamp = current.observationTimestamp,
                freshnessMs = listOfNotNull(
                    current.tokenAmount.freshnessMs,
                    current.solAmount.freshnessMs,
                    current.priceNative.freshnessMs,
                    current.priceUsd.freshnessMs,
                    current.marketCapNative.freshnessMs,
                    current.marketCapUsd.freshnessMs,
                    current.liquidityUsd.freshnessMs,
                    current.holders.freshnessMs,
                ).maxOrNull(),
                isStale = observedValues.any { it == FieldAvailability.STALE },
                lastEventId = current.eventId,
            ),
        )
    }

    private suspend fun onTrade(event: NormalizedTradeEvent, state: LiveMarketState) {
        val rows = dao.tradesSince(event.mint, System.currentTimeMillis() - 300_000)
        val buysRows = rows.filter { it.txType.equals("buy", true) }
        val sellsRows = rows.filter { it.txType.equals("sell", true) }
        val buys = buysRows.size
        val sells = sellsRows.size
        val buyVol = knownVolumeOrUnknown(buysRows)
        val sellVol = knownVolumeOrUnknown(sellsRows)
        val priceUsd = stateUsdValue(state.priceUsd)
        val marketCapUsd = stateUsdValue(state.marketCapUsd)
        val liquidityUsd = stateUsdValue(state.liquidityUsd)
        val metric = MetricEntity(
            mint = event.mint,
            windowSeconds = 300,
            updatedAt = event.observationTimestamp,
            trades = rows.size,
            buys = buys,
            sells = sells,
            uniqueBuyers = buysRows.mapNotNull { it.trader }.toSet().size,
            uniqueSellers = sellsRows.mapNotNull { it.trader }.toSet().size,
            buyVolumeSol = buyVol,
            sellVolumeSol = sellVol,
            latestPriceUsd = priceUsd,
            priceChangePct = null,
            volumeVelocity = null,
            buyerVelocity = null,
            dataQuality = if (rows.all { it.solAmount != null } && priceUsd != null) "COMPLETE" else "PARTIAL",
        )
        dao.upsertMetric(metric)
        manager.recordMetricGenerated()

        // Baseline A remains unchanged. Do not run it with UNKNOWN SOL volume, which its legacy
        // formula would otherwise coerce to zero.
        if (buyVol == null || sellVol == null) return
        val score = MomentumEngine.score(metric, liquidityUsd, null, listOf("UNKNOWN"))
        val confirmations = listOf(score.buyerPressure, score.volumePressure, score.volumeVelocity, score.priceMomentum)
            .count { it != null && it >= 70 }
        val type = MomentumEngine.classify(score, confirmations, safetyPassed = false)
        if (type != "REJECTED") {
            dao.insertScore(
                ScoreEntity(
                    event.mint,
                    System.currentTimeMillis(),
                    score.total,
                    score.buyerPressure,
                    score.volumePressure,
                    score.volumeVelocity,
                    score.priceMomentum,
                    score.liquidity,
                    score.holderDistribution,
                    score.safety,
                    score.reasons.joinToString("; "),
                    score.unknowns.joinToString("; "),
                ),
            )
            dao.insertSignal(
                SignalEntity(
                    id = "signal:${event.eventId}:$type",
                    mint = event.mint,
                    timestamp = event.observationTimestamp,
                    signalType = type,
                    score = score.total,
                    reasons = score.reasons.joinToString("; "),
                    marketCapUsd = marketCapUsd,
                    liquidityUsd = liquidityUsd,
                    buyers = buys,
                    sellers = sells,
                    buyVolume = buyVol,
                    sellVolume = sellVol,
                    priceUsd = priceUsd,
                ),
            )
        }
    }

    private fun NormalizedTradeEvent.toEntity(): TradeEntity {
        val sol = MarketValue.of(solAmount, providerTimestamp, receivedTimestamp, observationTimestamp)
        val tokens = MarketValue.of(tokenAmount, providerTimestamp, receivedTimestamp, observationTimestamp)
        val nativePrice = MarketValue.of(priceNative, providerTimestamp, receivedTimestamp, observationTimestamp)
        val usdPrice = MarketValue.of(priceUsd, providerTimestamp, receivedTimestamp, observationTimestamp)
        val nativeCap = MarketValue.of(marketCapNative, providerTimestamp, receivedTimestamp, observationTimestamp)
        val usdCap = MarketValue.of(marketCapUsd, providerTimestamp, receivedTimestamp, observationTimestamp)
        return TradeEntity(
            dedupeKey = eventId,
            mint = mint,
            txType = txType,
            trader = trader,
            solAmount = solAmount,
            tokenAmount = tokenAmount,
            marketCapSol = marketCapNative,
            priceUsd = priceUsd,
            timestamp = eventTimestamp,
            signature = signature,
            priceNative = priceNative,
            marketCapNative = marketCapNative,
            marketCapUsd = marketCapUsd,
            solAmountAvailability = sol.availability.name,
            tokenAmountAvailability = tokens.availability.name,
            priceNativeAvailability = nativePrice.availability.name,
            priceUsdAvailability = usdPrice.availability.name,
            marketCapNativeAvailability = nativeCap.availability.name,
            marketCapUsdAvailability = usdCap.availability.name,
            providerTimestamp = providerTimestamp,
            receivedTimestamp = receivedTimestamp,
            observationTimestamp = observationTimestamp,
            freshnessMs = PumpPortalNormalizer.freshnessMs(this, observationTimestamp),
            providerSequence = providerSequence,
        )
    }

    private fun knownVolumeOrUnknown(rows: List<TradeEntity>): Double? = when {
        rows.isEmpty() -> 0.0
        rows.any { it.solAmount == null } -> null
        else -> rows.sumOf { it.solAmount!! }
    }

    private fun stateUsdValue(value: MarketValue<Double>): Double? =
        value.value.takeIf { value.availability == FieldAvailability.KNOWN || value.availability == FieldAvailability.ZERO }

    private fun compareOrder(event: NormalizedProviderEvent, previous: LiveMarketState): Int {
        val eventTime = event.providerTimestamp ?: event.receivedTimestamp
        val previousTime = previous.providerTimestamp ?: previous.receivedTimestamp
        return compareValues(eventTime, previousTime).takeIf { it != 0 }
            ?: compareValues(event.receivedTimestamp, previous.receivedTimestamp).takeIf { it != 0 }
            ?: compareNullableSequence(event.providerSequence, previous.providerSequence).takeIf { it != 0 }
            ?: event.eventId.compareTo(previous.eventId)
    }

    private fun compareNullableSequence(a: Long?, b: Long?): Int = when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> compareValues(a, b)
    }

    private fun TokenEntity.toLiveMarketState(): LiveMarketState {
        val provider = providerTimestamp
        val received = receivedTimestamp ?: firstSeenAt
        val observed = observationTimestamp ?: received
        val state = LiveMarketState.unknown(
            mint = mint,
            lifecycle = lifecycle,
            providerTimestamp = provider,
            receivedTimestamp = received,
            observationTimestamp = observed,
            providerSequence = null,
            eventId = lastEventId ?: "legacy:$mint:$observed",
        ).copy(
            tokenAmount = storedValue(latestTokenAmount, latestTokenAmountAvailability, provider, received, observed),
            solAmount = storedValue(latestSolAmount, latestSolAmountAvailability, provider, received, observed),
            priceNative = storedValue(priceNative, priceNativeAvailability, provider, received, observed),
            priceUsd = storedValue(priceUsd, priceUsdAvailability, provider, received, observed),
            marketCapNative = storedValue(marketCapNative, marketCapNativeAvailability, provider, received, observed),
            marketCapUsd = storedValue(marketCapUsd, marketCapUsdAvailability, provider, received, observed),
            liquidityUsd = storedValue(liquidityUsd, liquidityUsdAvailability, provider, received, observed),
            holders = storedValue(holders, holdersAvailability, provider, received, observed),
        )
        return state.at(System.currentTimeMillis())
    }

    private fun <T : Number> storedValue(
        value: T?,
        rawAvailability: String,
        provider: Long?,
        received: Long,
        observed: Long,
    ): MarketValue<T> {
        val computed = MarketValue.of(value, provider, received, observed)
        if (value == null) return computed
        val stored = runCatching { FieldAvailability.valueOf(rawAvailability) }.getOrDefault(computed.availability)
        val safeStored = if (stored == FieldAvailability.UNKNOWN) computed.availability else stored
        return computed.copy(availability = safeStored).at(System.currentTimeMillis())
    }
}
