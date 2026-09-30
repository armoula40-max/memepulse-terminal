package com.solanasignal.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.TimeUnit
import kotlin.math.min

class PumpPortalWebSocketManager(private val apiKey: suspend () -> String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED); val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<NormalizedProviderEvent>(extraBufferCapacity = 512); val events: SharedFlow<NormalizedProviderEvent> = _events.asSharedFlow()
    private val _diagnostics = MutableStateFlow(Diagnostics()); val diagnostics: StateFlow<Diagnostics> = _diagnostics.asStateFlow()
    private var socket: WebSocket? = null
    private var reconnectAttempt = 0
    private var stopped = true
    private val trackedTokens = LinkedHashSet<String>()
    private val trackedAccounts = LinkedHashSet<String>()

    fun start() { if (!stopped) return; stopped = false; connect() }
    fun stop() { stopped = true; socket?.close(1000, "stopped"); socket = null; _state.value = ConnectionState.DISCONNECTED }
    fun subscribeToken(mint: String) { if (trackedTokens.add(mint)) send(mapOf("method" to "subscribeTokenTrade", "keys" to listOf(mint))) }
    fun unsubscribeToken(mint: String) { if (trackedTokens.remove(mint)) send(mapOf("method" to "unsubscribeTokenTrade", "keys" to listOf(mint))) }
    fun subscribeAccount(address: String) { if (trackedAccounts.add(address)) send(mapOf("method" to "subscribeAccountTrade", "keys" to listOf(address))) }
    fun unsubscribeAccount(address: String) { if (trackedAccounts.remove(address)) send(mapOf("method" to "unsubscribeAccountTrade", "keys" to listOf(address))) }

    fun recordDuplicate() = updateDiagnostics { it.copy(duplicates = it.duplicates + 1) }
    fun recordLateEvent() = updateDiagnostics { it.copy(lateEvents = it.lateEvents + 1) }
    fun recordStaleData(count: Long = 1) = updateDiagnostics { it.copy(staleData = it.staleData + count) }
    fun recordUnknownFields(count: Long) { if (count > 0) updateDiagnostics { it.copy(unknownFields = it.unknownFields + count) } }
    fun recordMetricGenerated() = updateDiagnostics { it.copy(metricsGenerated = it.metricsGenerated + 1) }

    private inline fun updateDiagnostics(crossinline transform: (Diagnostics) -> Diagnostics) {
        _diagnostics.update { transform(it) }
    }

    private fun connect() {
        scope.launch {
            val key = apiKey()
            if (key.isBlank()) { _state.value = ConnectionState.DISCONNECTED; updateDiagnostics { it.copy(lastError = "API key is missing") }; return@launch }
            withContext(Dispatchers.Main) { _state.value = if (reconnectAttempt == 0) ConnectionState.CONNECTING else ConnectionState.RECONNECTING }
            val request = Request.Builder().url("wss://pumpportal.fun/api/data?api-key=${java.net.URLEncoder.encode(key, "UTF-8")}").build()
            socket = client.newWebSocket(request, listener)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { reconnectAttempt = 0; _state.value = ConnectionState.CONNECTED; send(mapOf("method" to "subscribeNewToken")); send(mapOf("method" to "subscribeMigration")); trackedTokens.chunked(5000).forEach { send(mapOf("method" to "subscribeTokenTrade", "keys" to it)) }; trackedAccounts.chunked(5000).forEach { send(mapOf("method" to "subscribeAccountTrade", "keys" to it)) } }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val receivedAt = System.currentTimeMillis()
            updateDiagnostics { it.copy(lastEventAt = receivedAt, messages = it.messages + 1, eventsReceived = it.eventsReceived + 1) }
            parse(text, receivedAt)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { _state.value = ConnectionState.DISCONNECTED; updateDiagnostics { it.copy(lastError = "WebSocket failure: ${t.javaClass.simpleName}") }; scheduleReconnect() }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (!stopped) { _state.value = ConnectionState.RECONNECTING; updateDiagnostics { it.copy(lastError = "WebSocket closed: $code") }; scheduleReconnect() } }
    }

    private fun parse(text: String, receivedAt: Long) {
        val obj = PumpPortalNormalizer.parseObject(text)
        if (obj == null) {
            updateDiagnostics { it.copy(parserErrors = it.parserErrors + 1, invalidEvents = it.invalidEvents + 1) }
            return
        }
        updateDiagnostics { it.copy(eventsParsed = it.eventsParsed + 1) }
        val event = PumpPortalNormalizer.normalize(obj, receivedAt, System.currentTimeMillis())
        if (event == null) {
            updateDiagnostics { it.copy(invalidEvents = it.invalidEvents + 1) }
            return
        }

        val unknownCount = when (event) {
            is NormalizedTradeEvent -> listOf(
                event.solAmount == null,
                event.tokenAmount == null,
                event.marketCapNative == null,
                event.priceUsd == null,
                event.marketCapUsd == null,
                obj["liquidityUsd"] == null,
                obj["holders"] == null,
            ).count { it }.toLong()
            else -> listOf("priceUsd", "marketCapUsd", "liquidityUsd", "holders").count { obj[it] == null }.toLong()
        }
        val freshnessMs = PumpPortalNormalizer.freshnessMs(event, event.observationTimestamp)
        val isStale = freshnessMs > MarketValue.DEFAULT_STALE_AFTER_MS
        updateDiagnostics { current ->
            current.recordNormalized(event).copy(
                latencyMs = freshnessMs,
                staleData = current.staleData + if (isStale) 1 else 0,
                unknownFields = current.unknownFields + unknownCount,
            )
        }
        if (!_events.tryEmit(event)) updateDiagnostics { it.copy(invalidEvents = it.invalidEvents + 1, lastError = "Normalized event buffer is full") }
    }

    private fun send(payload: Map<String, Any>) { val json = buildJsonObject { payload.forEach { (key, value) -> put(key, when (value) { is String -> JsonPrimitive(value); is List<*> -> JsonArray(value.map { JsonPrimitive(it.toString()) }); else -> JsonPrimitive(value.toString()) }) } }.toString(); if (socket?.send(json) != true) return }
    private fun scheduleReconnect() { if (stopped) return; val attempt = reconnectAttempt++; val delayMs = min(60_000L, 1_000L * (1L shl min(attempt, 6))); updateDiagnostics { it.copy(reconnects = it.reconnects + 1) }; scope.launch { delay(delayMs); if (!stopped) connect() } }
}
