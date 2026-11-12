package com.ntq.training.momentbox.core.realtime

import com.ntq.training.momentbox.BuildConfig
import com.ntq.training.momentbox.data.local.db.NotificationDao
import com.ntq.training.momentbox.data.local.db.NotificationEntity
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.data.local.prefs.SettingsStorage
import com.ntq.training.momentbox.data.local.prefs.TokenStorage
import com.ntq.training.momentbox.data.repository.ChatSession
import io.socket.client.AckWithTimeout
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.awaitClose

/** Event mang scope tại thời điểm transport nhận, để consumer kiểm generation trước khi ghi Room. */
data class ScopedChatEvent(
    val event: String,
    val payload: JSONObject,
    val owner: NotificationScope,
    val generation: Long,
    val origin: String
)

/** Khóa chủ sở hữu bền vững: cùng tài khoản/nguồn vẫn đọc được thông báo sau khi mở lại app. */
enum class SocketPhase { DISCONNECTED, CONNECTING, CONNECTED, WAITING, FAILED, AUTH_EXPIRED }
data class SocketState(
    val phase: SocketPhase = SocketPhase.DISCONNECTED,
    val attempt: Int = 0,
    val retryAtMs: Long? = null,
    val reason: String? = null
)

data class NotificationScope(val ownerId: String, val source: String)

/** Tách transport để kiểm thử đúng listener sự kiện mà không phụ thuộc mạng ngoài. */
interface RealtimeSocket {
    fun on(event: String, listener: (Array<out Any>) -> Unit)
    fun off(event: String, listener: (Array<out Any>) -> Unit) {}
    fun emitEvent(event: String, payload: JSONObject) {}
    fun emitAck(
        event: String,
        payload: JSONObject,
        timeoutMs: Long,
        callback: (JSONObject?) -> Unit
    ): AutoCloseable = AutoCloseable {}

    fun connect()
    fun disconnect()
}

private class SocketIoTransport(origin: String, token: String) : RealtimeSocket {
    companion object {
        private val network =
            OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS)
                .build()
        private val closingAware = CloseAwareWebSocketFactory(network)
    }

    private val socket = IO.socket(origin, IO.Options().apply {
        path = "/socket.io"
        callFactory = network
        webSocketFactory = closingAware
        auth = mapOf("token" to token)
        reconnection = true
        reconnectionAttempts = 5
        reconnectionDelay = 1000
        reconnectionDelayMax = 5000
        timeout = 20000
    })

    init {
        socket.io().randomizationFactor(0.0)
    } // Backoff xác định để deadline UI khớp timer transport.

    private fun emitter(event: String): Emitter =
        if (event.startsWith("manager:")) socket.io() else socket

    private fun eventName(event: String) = event.removePrefix("manager:")

    // Phải giữ đúng wrapper Emitter.Listener: tạo lambda mới lúc off sẽ không gỡ listener cũ.
    private val callbacks =
        mutableMapOf<Pair<String, (Array<out Any>) -> Unit>, Emitter.Listener>()

    override fun on(event: String, listener: (Array<out Any>) -> Unit) {
        synchronized(callbacks) {
            callbacks.remove(event to listener)?.let { emitter(event).off(eventName(event), it) }
            val wrapper = Emitter.Listener { args -> listener(args) }
            callbacks[event to listener] = wrapper
            emitter(event).on(eventName(event), wrapper)
        }
    }

    override fun off(event: String, listener: (Array<out Any>) -> Unit) {
        synchronized(callbacks) {
            callbacks.remove(event to listener)?.let { emitter(event).off(eventName(event), it) }
        }
    }

    override fun emitEvent(event: String, payload: JSONObject) {
        socket.emit(event, payload)
    }

    override fun emitAck(
        event: String,
        payload: JSONObject,
        timeoutMs: Long,
        callback: (JSONObject?) -> Unit
    ): AutoCloseable {
        val ack = object : AckWithTimeout(timeoutMs) {
            override fun onSuccess(vararg args: Any?) {
                callback(args.firstOrNull() as? JSONObject)
            }

            override fun onTimeout() {
                callback(null)
            }
        }
        socket.emit(event, payload, ack)
        return AutoCloseable { ack.cancelTimer() }
    }

    override fun connect() {
        socket.connect()
    }

    override fun disconnect() {
        synchronized(callbacks) {
            callbacks.forEach { (key, wrapper) ->
                emitter(key.first).off(
                    eventName(key.first),
                    wrapper
                )
            }
            callbacks.clear()
        }
        socket.disconnect()
    }
}

/**
 * Socket giữ generation của phiên và khóa chủ sở hữu của từng kết nối.
 * Token REST được làm mới sẽ tạo transport mới để lần bắt tay sau dùng đúng token.
 * Listener cũ và tác vụ Room đến muộn không được cập nhật badge của phiên mới.
 */
open class SocketManager(
    private val tokenStore: TokenStorage,
    private val settingsStorage: SettingsStorage,
    private val notificationDao: NotificationDao? = null,
    private val baseUrlProvider: () -> String = { BuildConfig.BASE_URL },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val notificationScopeProvider: suspend () -> NotificationScope? = { null },
    private val socketFactory: (String, String) -> RealtimeSocket = { origin, token ->
        SocketIoTransport(
            origin,
            token
        )
    }
) {
    private val _state = MutableStateFlow(SocketState())
    val state = _state.asStateFlow()
    private val gate = Any()
    private var socket: RealtimeSocket? = null
    private var tokenListener: AutoCloseable? = null
    private var sourceListener: Job? = null
    private var unreadJob: Job? = null
    private var connectionSerial = 0L
    private var connectRequest = 0L
    private var lifecycleEpoch = 0L
    private var generation = -1L
    private var connectedToken: String? = null
    private var connectedOrigin: String? = null
    private var owner: NotificationScope? = null
    private val _feedNewCount = MutableStateFlow(0)
    open val feedNewCountFlow: StateFlow<Int> = _feedNewCount.asStateFlow()
    private val _isConnected = MutableStateFlow(false)
    open val isConnectedFlow: StateFlow<Boolean> = _isConnected.asStateFlow()
    private val _unreadCount = MutableStateFlow(0)
    open val unreadNotificationCountFlow: StateFlow<Int> = _unreadCount.asStateFlow()

    private class ChatSubscription(
        val event: String,
        val deliver: (ScopedChatEvent) -> Boolean,
        val close: (Throwable) -> Unit
    ) {
        var identity: Triple<NotificationScope, Long, String>? = null
        var transport: RealtimeSocket? = null
        var listener: ((Array<out Any>) -> Unit)? = null
    }

    private val chatSubscriptions = mutableSetOf<ChatSubscription>()

    /** Cold Flow chỉ mượn transport Application hiện có. awaitClose gỡ đúng callback của collector,
     * không disconnect socket chung của Feed/notification hoặc collector khác.
     * Refresh cùng phiên được nối lại; login/backend khác hủy Flow cũ, không phát data chủ mới vào nó. */
    fun events(event: String): Flow<ScopedChatEvent> =
        kotlinx.coroutines.flow.callbackFlow {
            require(event in setOf("presence:update", "typing", "message:new", "message:status"))
            val subscription = ChatSubscription(event,
                { value -> trySend(value).isSuccess },
                { cause -> close(cause) })
            synchronized(gate) {
                chatSubscriptions.add(subscription)
                val transport = socket
                val currentOwner = owner
                if (transport != null && currentOwner != null) attachChat(
                    subscription,
                    transport,
                    connectionSerial,
                    currentOwner,
                    generation,
                    resolveSocketOrigin()
                )
            }
            awaitClose {
                synchronized(gate) {
                    detachChat(subscription)
                    chatSubscriptions.remove(
                    subscription
                )
                }
            }
        }

    private fun detachChat(subscription: ChatSubscription) {
        val transport = subscription.transport
        val listener = subscription.listener
        if (transport != null && listener != null) transport.off(subscription.event, listener)
        subscription.transport = null
        subscription.listener = null
    }

    private fun attachChat(
        subscription: ChatSubscription,
        transport: RealtimeSocket,
        serial: Long,
        scopeOwner: NotificationScope,
        sessionGeneration: Long,
        origin: String
    ) {
        val identity = Triple(scopeOwner, sessionGeneration, origin)
        if (subscription.identity != null && subscription.identity != identity) {
            subscription.close(CancellationException("Chat session changed"))
            return
        }
        subscription.identity = identity
        detachChat(subscription)
        val listener: (Array<out Any>) -> Unit = { args ->
            val payload = args.firstOrNull() as? JSONObject
            synchronized(gate) {
                if (payload != null && owns(serial) && owner == scopeOwner && generation == sessionGeneration) {
                    if (!subscription.deliver(
                            ScopedChatEvent(
                                subscription.event,
                                payload,
                                scopeOwner,
                                sessionGeneration,
                                origin
                            )
                        )
                    ) {
                        // Queue đầy không được im lặng bỏ tin: consumer phải catch lỗi và sync REST khi subscribe lại.
                        subscription.close(IllegalStateException("Chat event buffer full; resynchronize REST"))
                    }
                }
            }
        }
        subscription.transport = transport
        subscription.listener = listener
        transport.on(subscription.event, listener)
    }

    /** Không queue emit lúc offline: một thao tác FAILED chỉ được gửi lại khi user chọn retry.
     * Ack giữ scope phiên (không chỉ transport serial) để token refresh cùng owner vẫn reconcile được. */
    fun emitForSession(
        expected: ChatSession,
        event: String,
        payload: JSONObject,
        ack: ((JSONObject?) -> Unit)? = null
    ): Boolean {
        require(event in setOf("message:send", "message:seen", "typing:start", "typing:stop"))
        synchronized(gate) {
            if (!_isConnected.value || owner?.ownerId != expected.ownerId ||
                generation != expected.generation || tokenStore.getSessionGeneration() != expected.generation ||
                owner?.source + "|" + resolveSocketOrigin() != expected.source
            ) return false
            val current = socket ?: return false
            if (ack == null) current.emitEvent(event, payload) else current.emitAck(
                event,
                payload,
                5000
            ) { response ->
                val valid = synchronized(gate) {
                    tokenStore.getSessionGeneration() == expected.generation &&
                            owner?.ownerId == expected.ownerId && owner?.source + "|" + resolveSocketOrigin() == expected.source
                }
                if (valid) ack(response)
            }
            return true
        }
    }

    fun resolveSocketOrigin(): String =
        baseUrlProvider().trim().trimEnd('/').removeSuffix("/v1").trimEnd('/')

    open fun connect() {
        connectForEpoch()
    }

    private fun connectForEpoch(expectedEpoch: Long? = null, expectedGeneration: Long? = null) {
        val (epoch, shouldSubscribe) = synchronized(gate) {
            if (expectedEpoch != null && expectedEpoch != lifecycleEpoch) return
            if (expectedGeneration != null && expectedGeneration != tokenStore.getSessionGeneration()) return
            if (tokenListener == null) {
                generation = tokenStore.getSessionGeneration()
                lifecycleEpoch to true
            } else lifecycleEpoch to false
        }
        if (shouldSubscribe) {
            // Không giữ gate khi đăng ký hoặc hủy listener: TokenStore phát callback dưới khóa riêng.
            val subscription = tokenStore.addSnapshotListener { snapshot ->
                val refresh = synchronized(gate) {
                    if (epoch != lifecycleEpoch) return@synchronized false
                    val sameSessionRefresh = snapshot.generation == generation &&
                            !snapshot.accessToken.isNullOrBlank() && snapshot.accessToken != connectedToken
                    if (snapshot.generation != generation || snapshot.accessToken.isNullOrBlank()) invalidateLocked()
                    sameSessionRefresh
                }
                if (refresh) scope.launch { connectForEpoch(epoch, snapshot.generation) }
            }
            val duplicate = synchronized(gate) {
                if (epoch == lifecycleEpoch && tokenListener == null) {
                    tokenListener = subscription
                    false
                } else true
            }
            if (duplicate) subscription.close()
        }
        val request = synchronized(gate) {
            if (epoch != lifecycleEpoch) return
            if (expectedGeneration != null && expectedGeneration != tokenStore.getSessionGeneration()) return
            if (sourceListener == null) {
                sourceListener = scope.launch {
                    settingsStorage.dataSourceFlow.collect { source ->
                        synchronized(gate) {
                            if (epoch == lifecycleEpoch && owner != null && source != owner?.source) invalidateLocked()
                        }
                    }
                }
            }
            ++connectRequest
        }
        scope.launch {
            val snapshot = tokenStore.snapshot()
            val source = settingsStorage.getDataSource()
            val scopeOwner = notificationScopeProvider()
            synchronized(gate) {
                if (request != connectRequest || snapshot.generation != tokenStore.getSessionGeneration()) return@synchronized
                if (expectedGeneration != null && snapshot.generation != expectedGeneration) return@synchronized
                val token = snapshot.accessToken
                if (source != SettingsDataStore.SOURCE_MB_SERVER || token.isNullOrBlank() ||
                    scopeOwner == null || scopeOwner.source != source || scopeOwner.ownerId.isBlank()
                ) {
                    invalidateLocked()
                    return@synchronized
                }
                if (socket != null && token == connectedToken && generation == snapshot.generation && owner == scopeOwner && connectedOrigin == resolveSocketOrigin()) {
                    if (!_isConnected.value && _state.value.phase in setOf(
                            SocketPhase.FAILED,
                            SocketPhase.DISCONNECTED
                        )
                    ) {
                        _state.value = SocketState(SocketPhase.CONNECTING)
                        socket?.connect()
                    }
                    return@synchronized
                }
                invalidateLocked()
                val serial = connectionSerial
                generation = snapshot.generation
                connectedToken = token
                owner = scopeOwner
                connectedOrigin = resolveSocketOrigin()
                _state.value = SocketState(SocketPhase.CONNECTING)
                try {
                    val transport = socketFactory(resolveSocketOrigin(), token)
                    socket = transport
                    transport.on(Socket.EVENT_CONNECT) {
                        synchronized(gate) {
                            if (owns(serial)) {
                                _isConnected.value = true
                                _state.value =
                                    SocketState(SocketPhase.CONNECTED)
                            }
                        }
                    }
                    transport.on(Socket.EVENT_DISCONNECT) { args ->
                        synchronized(gate) {
                            if (owns(serial)) {
                                _isConnected.value = false
                                val reason = args.firstOrNull()?.toString()
                                _state.value = if (reason in setOf(
                                        "io client disconnect",
                                        "io server disconnect"
                                    )
                                ) SocketState(reason = reason)
                                else waiting(1, reason)
                            }
                        }
                    }
                    transport.on(Socket.EVENT_CONNECT_ERROR) { args ->
                        synchronized(gate) {
                            if (owns(serial)) {
                                _isConnected.value = false
                                val raw = args.firstOrNull()
                                val reason = when (raw) {
                                    is JSONObject -> raw.optString(
                                        "message",
                                        ""
                                    )
                                    is Throwable -> raw.message.orEmpty()
                                    else -> raw?.toString()
                                        .orEmpty()
                                }
                                _state.value = when (reason) {
                                    "TOKEN_EXPIRED" -> SocketState(
                                        SocketPhase.AUTH_EXPIRED,
                                        reason = reason
                                    )

                                    "UNAUTHORIZED" -> SocketState(
                                        SocketPhase.FAILED,
                                        reason = reason
                                    )

                                    else -> if (_state.value.attempt == 0) waiting(
                                        1,
                                        "transport"
                                    ) else _state.value
                                }
                            }
                        }
                    }
                    transport.on("manager:reconnect_attempt") { args ->
                        synchronized(gate) {
                            if (owns(serial)) {
                                val attempt = (args.firstOrNull() as? Number)?.toInt() ?: 1
                                _state.value = SocketState(SocketPhase.CONNECTING, attempt)
                            }
                        }
                    }
                    transport.on("manager:reconnect_error") {
                        synchronized(gate) {
                            if (owns(serial)) {
                                val next = _state.value.attempt + 1
                                if (next <= 5) _state.value = waiting(next, "transport")
                            }
                        }
                    }
                    transport.on("manager:reconnect_failed") {
                        synchronized(gate) {
                            if (owns(serial)) {
                                _isConnected.value = false
                                _state.value = SocketState(
                                    SocketPhase.FAILED,
                                    _state.value.attempt,
                                    reason = "attempts_exhausted"
                                )
                            }
                        }
                    }
                    transport.on("feed:new") { args ->
                        synchronized(gate) {
                            if (owns(serial)) {
                                val count = when (val data = args.firstOrNull()) {
                                    is JSONObject -> data.optInt("count", 1)
                                    is Number -> data.toInt()
                                    else -> 1
                                }
                                _feedNewCount.value += count.coerceAtLeast(0)
                            }
                        }
                    }
                    val notificationListener: (Array<out Any>) -> Unit = { args ->
                        val data = args.firstOrNull() as? JSONObject
                        // Serial chỉ xác nhận lúc nhận event. Refresh token có thể đổi transport
                        // trước khi Room ghi xong nhưng thông báo vẫn thuộc cùng tài khoản/phiên.
                        if (data != null && synchronized(gate) { owns(serial) }) {
                            scope.launch {
                                persistNotification(
                                    snapshot.generation,
                                    scopeOwner,
                                    data
                                )
                            }
                        }
                    }
                    transport.on("notification", notificationListener)
                    transport.on("notification:new", notificationListener)
                    chatSubscriptions.toList().forEach {
                        attachChat(
                            it,
                            transport,
                            serial,
                            scopeOwner,
                            snapshot.generation,
                            resolveSocketOrigin()
                        )
                    }
                    unreadJob = scope.launch {
                        notificationDao?.unreadCountForScopeFlow(
                            scopeOwner.ownerId,
                            scopeOwner.source
                        )?.collect { count ->
                            synchronized(gate) { if (owns(serial)) _unreadCount.value = count }
                        }
                    }
                    transport.connect()
                } catch (_: Exception) {
                    invalidateLocked()
                }
            }
        }
    }

    private suspend fun notificationSessionIsCurrent(
        expectedGeneration: Long,
        scopeOwner: NotificationScope
    ): Boolean {
        if (expectedGeneration != tokenStore.getSessionGeneration()) return false
        val currentOwner = notificationScopeProvider()
        val currentSource = settingsStorage.getDataSource()
        return expectedGeneration == tokenStore.getSessionGeneration() &&
                currentOwner == scopeOwner && currentSource == scopeOwner.source
    }

    private suspend fun persistNotification(
        expectedGeneration: Long,
        scopeOwner: NotificationScope,
        data: JSONObject
    ) {
        if (!notificationSessionIsCurrent(expectedGeneration, scopeOwner)) return
        val entity = NotificationEntity(
            type = data.optString("type", "system"),
            title = data.optString("title", "MomentBox"),
            body = data.optString("body", ""),
            ownerId = scopeOwner.ownerId,
            source = scopeOwner.source
        )
        var insertedId: Long? = null
        try {
            if (notificationSessionIsCurrent(expectedGeneration, scopeOwner)) insertedId =
                notificationDao?.insert(entity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Lỗi lưu cục bộ không được làm dừng scope realtime của toàn ứng dụng.
        } finally {
            // Room có thể vừa commit khi logout xảy ra; xóa riêng row cũ, kể cả coroutine đã bị hủy.
            withContext(NonCancellable) {
                if (insertedId != null && !notificationSessionIsCurrent(
                        expectedGeneration,
                        scopeOwner
                    )
                ) {
                    notificationDao?.deleteForScope(
                        insertedId!!,
                        scopeOwner.ownerId,
                        scopeOwner.source
                    )
                }
            }
        }
    }

    /** Manager phát attempt sau khoảng chờ, error sau khi đã đặt timer kế tiếp.
     * Jitter=0 ở transport nên cùng delay 1/2/4/5/5 giây, UI không bịa countdown ngẫu nhiên. */
    private fun waiting(attempt: Int, reason: String?) = SocketState(
        SocketPhase.WAITING, attempt,
        System.currentTimeMillis() + minOf(5000L, 1000L shl (attempt - 1).coerceIn(0, 3)), reason
    )

    private fun owns(serial: Long): Boolean = serial == connectionSerial && owner != null &&
            generation == tokenStore.getSessionGeneration() && connectedOrigin == resolveSocketOrigin()

    private fun invalidateLocked() {
        ++connectRequest
        ++connectionSerial
        chatSubscriptions.toList().forEach(::detachChat)
        socket?.disconnect()
        socket = null
        connectedToken = null
        connectedOrigin = null
        _state.value = SocketState()
        owner = null
        unreadJob?.cancel()
        unreadJob = null
        _isConnected.value = false
        _feedNewCount.value = 0
        _unreadCount.value = 0
    }

    open fun clearFeedNewCount() {
        _feedNewCount.value = 0
    }

    open fun incrementFeedNewCount(count: Int = 1) {
        _feedNewCount.value += count
    }

    open fun disconnect() {
        val subscription = synchronized(gate) {
            // Chặn cả đăng ký listener đang dở và reconnect đã xếp hàng trước khi màn hình đóng.
            ++lifecycleEpoch
            invalidateLocked()
            sourceListener?.cancel()
            sourceListener = null
            tokenListener.also { tokenListener = null }
        }
        // Không giữ khóa socket khi hủy listener, tránh đảo thứ tự khóa với TokenStore.
        subscription?.close()
    }
}