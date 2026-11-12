package com.ntq.training.momentbox.feature.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

class PlayerClient(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<suspend () -> Unit>(64)
    private val mutable = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = mutable.asStateFlow()
    private val connectionState = MutableStateFlow("disconnected")
    val connection: StateFlow<String> = connectionState.asStateFlow()
    @Volatile
    private var remote: IPlayerService? = null
    private var current: ServiceConnection? = null
    private var death: IBinder.DeathRecipient? = null
    private var started = false
    private var retries = 0
    private var epoch = 0L
    private var activeCallback: IPlayerCallback? = null
    private fun callbackFor(version: Long, api: IPlayerService) = object : IPlayerCallback.Stub() {
        override fun onStateChanged(value: PlayerState?) {
            if (value != null) main.post {
                if (started && version == epoch && remote === api && (value.servicePid != mutable.value.servicePid || value.revision >= mutable.value.revision)) mutable.value =
                    value
            }
        }

        override fun onPositionChanged(revision: Long, trackId: String?, positionMs: Long) {
            main.post {
                val value =
                    mutable.value
                    if (started && version == epoch && remote === api && revision == value.revision && value.queue.getOrNull(
                    value.index
                )?.id == trackId
            ) mutable.value = value.copy(positionMs = positionMs)
            }
        }
    }

    init {
        scope.launch {
            for (action in commands) try {
                action()
            } catch (_: DeadObjectException) {
                main.post { lost() }
            } catch (_: Exception) {
                main.post { connectionState.value = "command_error" }
            }
        }
    }

    private fun enqueue(action: suspend () -> Unit) {
        if (!commands.trySend(action).isSuccess) connectionState.value = "busy"
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (started) return
        started =
            true
            retries = 0
            bind()
    }

    private fun bind() {
        if (!started || current != null) return
        connectionState.value = "connecting"
        val version = ++epoch
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (!started || version != epoch || binder == null) return
                val api = IPlayerService.Stub.asInterface(binder)
                remote = api
                val callback = callbackFor(version, api)
                activeCallback = callback
                val recipient =
                    IBinder.DeathRecipient { main.post { if (remote === api) lost() } }
                    death =
                    recipient
                enqueue {
                    binder.linkToDeath(recipient, 0)
                    api.registerCallback(callback)
                    val snapshot = api.state
                    main.post {
                        if (started && version == epoch && remote === api) {
                            mutable.value = snapshot
                            connectionState.value = "bound"
                            retries = 0
                        }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (version == epoch) lost()
            }

            override fun onBindingDied(name: ComponentName?) {
                if (version == epoch) lost()
            }

            override fun onNullBinding(name: ComponentName?) {
                if (version == epoch) lost()
            }
        }
        current = connection
        if (!context.bindService(
                Intent(context, PlayerService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
        ) lost()
    }

    private fun detach() {
        ++epoch
        val old = remote
        val recipient = death
        val callback = activeCallback
        activeCallback = null
        remote = null
        death = null
        // Unregister có thể còn đang chờ IO: giữ đúng binder cũ, không xóa callback của lần bind mới.
        enqueue {
            runCatching { if (callback != null) old?.unregisterCallback(callback) }
            if (recipient != null) runCatching {
            old?.asBinder()?.unlinkToDeath(recipient, 0)
        }
        }
        current?.let { runCatching { context.unbindService(it) } }
        current = null
    }

    private fun lost() {
        if (current == null && remote == null) return
        detach()
        mutable.value = mutable.value.copy(
            playing = false,
            preparing = false,
            error = "PLAYER_SERVICE_LOST"
        )
        connectionState.value = "service_lost"
        if (started && retries < 3) {
            val delay = 1000L shl retries++
            main.postDelayed(
                { if (started && current == null) bind() },
                delay
            )
        }
    }

    fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        started =
            false
            main.removeCallbacksAndMessages(null)
            detach()
            connectionState.value =
            "disconnected"
    }

    fun destroy() {
        stop()
        commands.close()
        scope.launch { delay(2000)
        scope.cancel() }
    }

    /** Consumer IO giữ thứ tự setQueue → promote FGS → play. Không phát lại tự động khi rebind. */
    fun submit(command: suspend (IPlayerService) -> Unit) {
        val expected = remote ?: return
        enqueue { if (remote === expected) command(expected) }
    }

    fun setQueue(values: List<Track>, index: Int, play: Boolean = false) {
        PlayerProtocol.validate(values, index)
        val copy = values.map { it.copy(waveform = it.waveform.toList()) }
        submit { api -> api.setQueue(copy, index)
        if (play) startPlayback() }
    }

    fun play() {
        if (remote != null) enqueue { startPlayback() }
    }

    private suspend fun startPlayback() = withContext(Dispatchers.Main) {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                PlayerService.intent(context, PlayerService.PLAY)
            )
        }.onFailure { connectionState.value = "foreground_denied" }
    }

    fun pause() = submit { it.pause() }
}
