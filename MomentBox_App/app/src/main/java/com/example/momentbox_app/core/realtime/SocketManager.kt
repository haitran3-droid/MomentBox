package com.example.momentbox_app.core.realtime

import com.example.momentbox_app.data.local.db.NotificationDao
import com.example.momentbox_app.data.local.db.NotificationEntity
import com.example.momentbox_app.data.local.prefs.SettingsDataStore
import com.example.momentbox_app.data.local.prefs.SettingsStorage
import com.example.momentbox_app.data.local.prefs.TokenStore
import com.ntq.training.momentbox.BuildConfig
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

open class SocketManager(
    private val tokenStore: TokenStore,
    private val settingsStorage: SettingsStorage,
    private val notificationDao: NotificationDao? = null,
    private val baseUrlProvider: () -> String = { BuildConfig.BASE_URL },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private var socket: Socket? = null
    private var lastConnectedToken: String? = null

    private val _feedNewCount = MutableStateFlow(0)
    open val feedNewCountFlow: StateFlow<Int> = _feedNewCount.asStateFlow()
    private val _isConnected = MutableStateFlow(false)
    open val isConnectedFlow: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun resolveSocketOrigin(): String {
        var origin = baseUrlProvider().trim()
        while (origin.endsWith("/")) {
            origin = origin.substring(0, origin.length - 1)
        }
        if (origin.endsWith("/v1")) {
            origin = origin.substring(0, origin.length - 3)
        }
        while (origin.endsWith("/")) {
            origin = origin.substring(0, origin.length - 1)
        }
        return origin
    }

    open fun connect() {
        scope.launch {
            val dataSource = settingsStorage.getDataSource()
            if (dataSource != SettingsDataStore.SOURCE_MB_SERVER) {
                disconnect()
                return@launch
            }

            val token = tokenStore.getAccessToken()
            if (token.isNullOrBlank()) {
                disconnect()
                return@launch
            }
            if (token != lastConnectedToken && socket != null) {
                disconnect()
            }
            if (socket?.connected() == true) return@launch
            try {
                val socketUrl = resolveSocketOrigin()
                val options = IO.Options().apply {
                    path = "/socket.io"
                    auth = mapOf("token" to token)
                    reconnection = true
                    reconnectionAttempts = 5
                    reconnectionDelay = 1000
                    reconnectionDelayMax = 5000
                    timeout = 20000
                }

                socket?.disconnect()
                socket?.off()
                val newSocket = IO.socket(socketUrl, options)
                newSocket.on(Socket.EVENT_CONNECT) {
                    _isConnected.value = true
                    lastConnectedToken = token
                }
                newSocket.on(Socket.EVENT_DISCONNECT) {
                    _isConnected.value = false
                }
                newSocket.on(Socket.EVENT_CONNECT_ERROR) {
                    _isConnected.value = false
                }

                newSocket.on("feed:new") { args ->
                    if (args.isNotEmpty()) {
                        val data = args[0]
                        var count = 1
                        if (data is JSONObject) {
                            count = data.optInt("count", 1)
                        } else if (data is Number) {
                            count = data.toInt()
                        }
                        _feedNewCount.value += count
                    }
                }

                val notificationListener: (Array<Any>) -> Unit = { args ->
                    if (args.isNotEmpty()) {
                        val data = args[0]
                        if (data is JSONObject) {
                            val type = data.optString("type", "system")
                            val title = data.optString("title", "MomentBox")
                            val body = data.optString("body", "Thông báo mới")
                            val entity = NotificationEntity(
                                type = type,
                                title = title,
                                body = body,
                                createdAt = System.currentTimeMillis(),
                                isRead = false
                            )
                            scope.launch {
                                try {
                                    notificationDao?.insert(entity)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                }
                newSocket.on("notification") { args -> notificationListener(args) }
                newSocket.on("notification:new") { args -> notificationListener(args) }
                socket = newSocket
                newSocket.connect()
            } catch (_: Exception) {
                _isConnected.value = false
            }
        }
    }

    open fun clearFeedNewCount() {
        _feedNewCount.value = 0
    }

    open fun incrementFeedNewCount(count: Int = 1) {
        _feedNewCount.value += count
    }

    open fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
        lastConnectedToken = null
        _isConnected.value = false
        _feedNewCount.value = 0
    }
}