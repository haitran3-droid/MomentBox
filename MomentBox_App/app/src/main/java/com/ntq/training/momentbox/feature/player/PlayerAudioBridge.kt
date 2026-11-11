package com.ntq.training.momentbox.feature.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.ntq.training.momentbox.core.media.AndroidPlaybackAudio
import com.ntq.training.momentbox.feature.recorder.RecordingService
import com.ntq.training.momentbox.feature.recorder.RecordPhase
import com.ntq.training.momentbox.MomentBoxApp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull

class PlayerAudioBridge(private val app: MomentBoxApp) {
    private val main = Handler(Looper.getMainLooper());
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<() -> Unit>(32)
    @Volatile
    private var remote: IPlayerService? = null
    @Volatile
    var active = false; private set
    private var connection: ServiceConnection? = null;
    private var callback: IAudioPeer? = null;
    @Volatile
    private var epoch = 0L

    init {
        scope.launch { for (command in commands) runCatching { command() } }
    }

    /** create=false lúc cold main chỉ nối player đang tồn tại, không tự khởi động audio nền. */
    fun connect(create: Boolean = false) {
        check(Looper.myLooper() == Looper.getMainLooper()); if (connection != null) return
        val version = ++epoch
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (version != epoch || binder == null) return
                val api = IPlayerService.Stub.asInterface(binder); remote = api
                val peer = object : IAudioPeer.Stub() {
                    override fun onPlaybackChanged(value: Boolean) {
                        if (version == epoch && remote === api) active = value
                    }

                    override fun onClosed() {
                        main.post { if (version == epoch && remote === api) disconnect() }
                    }

                    override fun onRequestFocus(nonce: Long) {
                        main.post {
                            if (version == epoch && remote === api) {
                                AndroidPlaybackAudio.get(app).interruptForRemotePlayer()
                                RecordingService.interruptForPlayback(
                                    app
                                )
                                scope.launch {
                                    val released = withTimeoutOrNull(1000) {
                                        RecordingService.state.first {
                                            it.phase !in listOf(
                                                RecordPhase.PREPARING,
                                                RecordPhase.RECORDING
                                            )
                                        }
                                        AndroidPlaybackAudio.get(app).awaitRecordersReleased()
                                        true
                                    } == true
                                    if (released) commands.trySend {
                                        if (version == epoch && remote === api) api.confirmFocus(
                                            nonce
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                callback =
                    peer; commands.trySend { if (remote === api) api.registerAudioPeer(peer) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (version == epoch) {
                    remote = null; active = false
                }
            }

            override fun onBindingDied(name: ComponentName?) {
                if (version == epoch) disconnect()
            }

            override fun onNullBinding(name: ComponentName?) {
                if (version == epoch) disconnect()
            }
        }
        connection = next
        if (!app.bindService(
                Intent(app, PlayerService::class.java),
                next,
                if (create) Context.BIND_AUTO_CREATE else 0
            )
        ) {
            connection = null; active = false
        }
    }

    fun disconnect() {
        ++epoch;
        val old = remote;
        val peer = callback; remote = null; callback = null; active = false
        commands.trySend { if (peer != null) old?.unregisterAudioPeer(peer) }
        connection?.let { runCatching { app.unbindService(it) } }; connection = null
    }

    /** Auto Feed không được cướp quyền đang phát. User voice là transient, video/ambient là
     * ý định mới; Service giữ/xóa resume intent phù hợp rồi Android phân phối focus thực. */
    fun interruptForMain(transientLoss: Boolean) {
        val api = remote ?: return; commands.trySend {
            if (remote === api) api.interruptForMain(
                transientLoss
            )
        }
    }
}
