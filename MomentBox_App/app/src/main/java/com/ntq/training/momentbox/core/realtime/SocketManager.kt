package com.ntq.training.momentbox.core.realtime

import com.ntq.training.momentbox.data.local.db.NotificationDao
import com.ntq.training.momentbox.data.local.db.NotificationEntity
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.data.local.prefs.SettingsStorage
import com.ntq.training.momentbox.data.local.prefs.TokenStore
import com.ntq.training.momentbox.BuildConfig
import com.ntq.training.momentbox.data.local.prefs.TokenStorage
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

/** Khóa chủ sở hữu bền vững: cùng tài khoản/nguồn vẫn đọc được thông báo sau khi mở lại app. */
data class NotificationScope(val ownerId: String, val source: String)

/** Tách transport để kiểm thử đúng listener sự kiện mà không phụ thuộc mạng ngoài. */
interface RealtimeSocket {
    fun on(event: String, listener: (Array<out Any>) -> Unit)
    fun connect()
    fun disconnect()
}

private class SocketIoTransport(origin: String, token: String) : RealtimeSocket {
    private val socket = IO.socket(origin, IO.Options().apply {
        path = "/socket.io"
        auth = mapOf("token" to token)
        reconnection = true
        reconnectionAttempts = 5
        reconnectionDelay = 1000
        reconnectionDelayMax = 5000
        timeout = 20000
    })
    override fun on(event: String, listener: (Array<out Any>) -> Unit) {
        socket.on(event) { args -> listener(args) }
    }
    override fun connect() { socket.connect() }
    override fun disconnect() { socket.off(); socket.disconnect() }
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
    private val socketFactory: (String, String) -> RealtimeSocket = { origin, token -> SocketIoTransport(origin, token) }
) {
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
    private var owner: NotificationScope? = null
    private val _feedNewCount = MutableStateFlow(0)
    open val feedNewCountFlow: StateFlow<Int> = _feedNewCount.asStateFlow()
    private val _isConnected = MutableStateFlow(false)
    open val isConnectedFlow: StateFlow<Boolean> = _isConnected.asStateFlow()
    private val _unreadCount = MutableStateFlow(0)
    open val unreadNotificationCountFlow: StateFlow<Int> = _unreadCount.asStateFlow()

    fun resolveSocketOrigin(): String = baseUrlProvider().trim().trimEnd('/').removeSuffix("/v1").trimEnd('/')

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
                if (epoch == lifecycleEpoch && tokenListener == null) { tokenListener = subscription; false } else true
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
                    scopeOwner == null || scopeOwner.source != source || scopeOwner.ownerId.isBlank()) {
                    invalidateLocked()
                    return@synchronized
                }
                if (socket != null && token == connectedToken && generation == snapshot.generation && owner == scopeOwner) return@synchronized
                invalidateLocked()
                val serial = connectionSerial
                generation = snapshot.generation
                connectedToken = token
                owner = scopeOwner
                try {
                    val transport = socketFactory(resolveSocketOrigin(), token)
                    socket = transport
                    transport.on(Socket.EVENT_CONNECT) { synchronized(gate) { if (owns(serial)) _isConnected.value = true } }
                    transport.on(Socket.EVENT_DISCONNECT) { synchronized(gate) { if (owns(serial)) _isConnected.value = false } }
                    transport.on(Socket.EVENT_CONNECT_ERROR) { synchronized(gate) { if (owns(serial)) _isConnected.value = false } }
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
                            scope.launch { persistNotification(snapshot.generation, scopeOwner, data) }
                        }
                    }
                    transport.on("notification", notificationListener)
                    transport.on("notification:new", notificationListener)
                    unreadJob = scope.launch {
                        notificationDao?.unreadCountForScopeFlow(scopeOwner.ownerId, scopeOwner.source)?.collect { count ->
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

    private suspend fun notificationSessionIsCurrent(expectedGeneration: Long, scopeOwner: NotificationScope): Boolean {
        if (expectedGeneration != tokenStore.getSessionGeneration()) return false
        val currentOwner = notificationScopeProvider()
        val currentSource = settingsStorage.getDataSource()
        return expectedGeneration == tokenStore.getSessionGeneration() &&
                currentOwner == scopeOwner && currentSource == scopeOwner.source
    }

    private suspend fun persistNotification(expectedGeneration: Long, scopeOwner: NotificationScope, data: JSONObject) {
        if (!notificationSessionIsCurrent(expectedGeneration, scopeOwner)) return
        val entity = NotificationEntity(
            type = data.optString("type", "system"), title = data.optString("title", "MomentBox"),
            body = data.optString("body", ""), ownerId = scopeOwner.ownerId, source = scopeOwner.source
        )
        var insertedId: Long? = null
        try {
            if (notificationSessionIsCurrent(expectedGeneration, scopeOwner)) insertedId = notificationDao?.insert(entity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Lỗi lưu cục bộ không được làm dừng scope realtime của toàn ứng dụng.
        } finally {
            // Room có thể vừa commit khi logout xảy ra; xóa riêng row cũ, kể cả coroutine đã bị hủy.
            withContext(NonCancellable) {
                if (insertedId != null && !notificationSessionIsCurrent(expectedGeneration, scopeOwner)) {
                    notificationDao?.deleteForScope(insertedId!!, scopeOwner.ownerId, scopeOwner.source)
                }
            }
        }
    }

    private fun owns(serial: Long): Boolean = serial == connectionSerial && owner != null &&
            generation == tokenStore.getSessionGeneration()

    private fun invalidateLocked() {
        ++connectRequest
        ++connectionSerial
        socket?.disconnect()
        socket = null
        connectedToken = null
        owner = null
        unreadJob?.cancel()
        unreadJob = null
        _isConnected.value = false
        _feedNewCount.value = 0
        _unreadCount.value = 0
    }

    open fun clearFeedNewCount() { _feedNewCount.value = 0 }
    open fun incrementFeedNewCount(count: Int = 1) { _feedNewCount.value += count }

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