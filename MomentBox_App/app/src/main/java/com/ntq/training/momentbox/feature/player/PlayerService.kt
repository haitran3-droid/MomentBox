package com.ntq.training.momentbox.feature.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.RemoteCallbackList
import androidx.core.content.ContextCompat
import com.ntq.training.momentbox.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlayerService : Service() {
    companion object {
        const val CHANNEL = "momentbox.playback";
        const val NOTIFICATION = 18001
        const val PLAY = "player.PLAY";
        const val PAUSE = "player.PAUSE";
        const val NEXT = "player.NEXT";
        const val PREVIOUS = "player.PREVIOUS";
        const val CLOSE = "player.CLOSE"
        fun intent(context: Context, action: String) =
            Intent(context, PlayerService::class.java).setAction(action)
    }

    private lateinit var thread: HandlerThread;
    private lateinit var handler: Handler
    private lateinit var audio: AudioManager;
    private lateinit var session: MediaSession
    private val queue = PlayerQueue();
    private val focusPolicy = PlayerFocusPolicy()
    private var player: MediaPlayer? = null;
    private var prepared = false;
    private var generation = 0L
    private var speed = 1f;
    private var duration = 0L;
    private var error: String? = null;
    private var focus = "NONE";
    private var volume = 1f
    private var focusRequest: AudioFocusRequest? = null;
    private var focusGeneration = 0L
    private var legacyFocus: AudioManager.OnAudioFocusChangeListener? = null
    private var noisyRegistered = false;

    @Volatile
    private var closed = false;
    private var route = "unknown"
    private var sleepAt = 0L;
    private var sleepDuration = 0L;
    private var endOfTrack = false;
    private var sleepJob: Runnable? = null

    @Volatile
    private var foreground = false

    @Volatile
    private var snapshot = PlayerState(servicePid = Process.myPid())
    private val callbacks = object : RemoteCallbackList<IPlayerCallback>() {
        override fun onCallbackDied(callback: IPlayerCallback?) {
            handler.post { updatePositionTicker() }
        }
    }
    private var handoverNonce = 0L;
    private var pendingHandover = 0L;
    private var handoverTimeout: Runnable? = null
    private val peers = object : RemoteCallbackList<IAudioPeer>() {
        override fun onCallbackDied(peer: IAudioPeer?) {
            handler.post {
                if (pendingHandover != 0L && registeredCallbackCount == 0) {
                    cancelHandover(); playEngine()
                }
            }
        }
    }
    private val binder = object : IPlayerService.Stub() {
        override fun registerAudioPeer(peer: IAudioPeer?) = command {
            if (peer != null) {
                peers.register(peer); runCatching { peer.onPlaybackChanged(isPlaying() || (player != null && !prepared) || pendingHandover != 0L) }
            }
        }

        override fun unregisterAudioPeer(peer: IAudioPeer?) = command {
            if (peer != null) peers.unregister(peer); if (pendingHandover != 0L && peers.registeredCallbackCount == 0) {
            cancelHandover(); playEngine()
        }
        }

        override fun confirmFocus(nonce: Long) = command {
            if (nonce != 0L && nonce == pendingHandover) {
                cancelHandover(); playEngine()
            }
        }

        override fun interruptForMain(transientLoss: Boolean) = command {
            cancelHandover(); if (transientLoss) {
            focusPolicy.change(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                isPlaying() || (player != null && !prepared)
            ); pause(false)
        } else pause(true)
        }

        override fun setQueue(tracks: MutableList<Track>?, startIndex: Int) {
            enforceOwner();
            val copy = tracks.orEmpty()
                .map { it.copy(waveform = it.waveform.toList()) }; PlayerProtocol.validate(
                copy,
                startIndex
            )
            command {
                val old = queue.tracks.getOrNull(queue.index);
                val selected = copy.getOrNull(startIndex)
                val keep =
                    old?.id == selected?.id && old?.uri == selected?.uri && old?.ownerScope == selected?.ownerScope && player != null
                queue.replace(copy, startIndex)
                if (!keep) {
                    focusPolicy.userPause(); releaseEngine(); abandonFocus(); duration = 0; error =
                        null
                    // Hẹn phút giữ deadline khi chọn bài cùng thư viện; hết-bài chỉ gắn bài cũ.
                    // Đổi owner hoặc bỏ queue phải hủy timer để không áp dụng sang tài khoản khác.
                    if (old?.ownerScope != selected?.ownerScope || selected == null || endOfTrack) cancelSleep()
                }
                publish()
            }
        }

        override fun play(): Unit = command { this@PlayerService.play() }
        override fun pause() = command { pause(true) }
        override fun seekTo(positionMs: Long) = command { seek(positionMs) }
        override fun next() = command { advance(false, false) }
        override fun previous() = command { advance(true, false) }
        override fun setSpeed(value: Float) {
            require(value.isFinite() && value in .75f..2f); command {
                speed = value; if (prepared && isPlaying()) player?.playbackParams =
                PlaybackParams().setSpeed(speed); publish()
            }
        }

        override fun setSleepTimer(millis: Long) {
            require(millis == -1L || millis == 0L || millis in 1..30 * 60 * 1000); command {
                setSleep(
                    millis
                )
            }
        }

        // Toggle/cycle được tính ngay trên thread sở hữu queue, không dựa snapshot UI đang trễ.
        override fun toggleShuffle() = command { queue.shuffle = !queue.shuffle; publish() }
        override fun cycleRepeat() =
            command { queue.repeatMode = (queue.repeatMode + 1) % 3; publish() }

        override fun setShuffle(enabled: Boolean) = command { queue.shuffle = enabled; publish() }
        override fun setRepeatMode(mode: Int) {
            require(mode in 0..2); command { queue.repeatMode = mode; publish() }
        }

        override fun setFavorite(trackId: String?, favorite: Boolean) =
            command { trackId?.let { queue.favorite(it, favorite) }; publish() }

        override fun close() = command { shutdown() }
        override fun getState(): PlayerState {
            enforceOwner();
            val done = CountDownLatch(1)
            handler.post {
                if (prepared) snapshot = snapshot.copy(positionMs = runCatching {
                    player?.currentPosition?.toLong() ?: 0
                }.getOrDefault(0)); done.countDown()
            }
            done.await(1, TimeUnit.SECONDS); return snapshot
        }

        override fun registerCallback(callback: IPlayerCallback?) = command {
            if (callback != null) {
                callbacks.register(callback); runCatching { callback.onStateChanged(snapshot) }; updatePositionTicker()
            }
        }

        override fun unregisterCallback(callback: IPlayerCallback?) =
            command { if (callback != null) callbacks.unregister(callback); updatePositionTicker() }
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) handler.post {
                pause(
                    true
                )
            }
        }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = updateRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) =
            updateRoute()
    }

    override fun onCreate() {
        super.onCreate(); thread =
            HandlerThread("momentbox-player-owner").apply { start() }; handler =
            Handler(thread.looper)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.m5_player_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        session = MediaSession(this, "MomentBoxPlayer").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    runCatching {
                        ContextCompat.startForegroundService(
                            this@PlayerService,
                            intent(this@PlayerService, PLAY)
                        )
                    }.onFailure { error = "FGS_NOT_ALLOWED"; publish() }
                };
                override fun onPause() {
                    pause(true)
                };
                override fun onSkipToNext() {
                    advance(false, false)
                };
                override fun onSkipToPrevious() {
                    advance(true, false)
                };
                override fun onSeekTo(pos: Long) {
                    seek(pos)
                };
                override fun onStop() {
                    shutdown()
                }
            }, handler)
        }
        audio.registerAudioDeviceCallback(devices, handler)
        handler.post { updateRoute(); publish() }
    }

    override fun onBind(intent: Intent?): IBinder? = if (closed) null else binder
    override fun onUnbind(intent: Intent?): Boolean {
        handler.post { updatePositionTicker() }; return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // User/notification command bắt đầu từforeground hợp lệ. Promote ngay trước bất kỳ prepare/focus.
        if (intent?.action != CLOSE && !foreground) try {
            val notice = notification(snapshot)
            if (Build.VERSION.SDK_INT >= 29) startForeground(
                NOTIFICATION,
                notice,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            ) else startForeground(NOTIFICATION, notice)
            foreground = true
        } catch (_: Exception) {
            handler.post {
                error = "FGS_NOT_ALLOWED"; publish()
            }; stopSelf(); return START_NOT_STICKY
        }
        handler.post {
            when (intent?.action) {
                PLAY -> play(); PAUSE -> pause(true); NEXT -> advance(
                false,
                false
            ); PREVIOUS -> advance(true, false); CLOSE -> shutdown()
            }
        }
        return START_NOT_STICKY
    }

    private fun enforceOwner() {
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Internal player only")
    }

    private fun command(block: () -> Unit) {
        enforceOwner(); handler.post {
            if (!closed) try {
                block()
            } catch (_: Exception) {
                fail("PLAYER_COMMAND_FAILED")
            }
        }
    }

    private fun isPlaying() =
        prepared && runCatching { player?.isPlaying == true }.getOrDefault(false)

    /** Oneway handshake không chặn Binder pool/owner thread. ACK cũ sau pause/đổi bài bị nonce loại. */
    private fun play() {
        cancelHandover()
        if (queue.index !in queue.tracks.indices) {
            playEngine(); return
        }
        if (peers.registeredCallbackCount == 0) {
            playEngine(); return
        }
        val nonce = ++handoverNonce; pendingHandover = nonce; publish()
        val count = peers.beginBroadcast()
        try {
            repeat(count) { i -> runCatching { peers.getBroadcastItem(i).onRequestFocus(nonce) } }
        } finally {
            peers.finishBroadcast()
        }
        handoverTimeout = Runnable {
            if (pendingHandover == nonce) {
                cancelHandover(); error = "AUDIO_HANDOVER_TIMEOUT"; publish()
            }
        }.also { handler.postDelayed(it, 1500) }
    }

    private fun cancelHandover() {
        pendingHandover = 0; handoverTimeout?.let { handler.removeCallbacks(it) }; handoverTimeout =
            null
    }

    private fun playEngine() {
        if (closed || queue.index !in queue.tracks.indices) {
            error = "PLAYER_EMPTY_QUEUE"; publish(); return
        }
        if (!foreground) {
            error = "PLAYER_NEEDS_FOREGROUND_START"; publish(); return
        }
        if (!requestFocus()) {
            focusPolicy.userPlay(false); error = "AUDIO_FOCUS_DENIED"; publish(); return
        }
        focusPolicy.userPlay(true); volume = 1f; error = null
        if (player != null && prepared) {
            player?.setVolume(1f, 1f); player?.start(); player?.playbackParams =
                PlaybackParams().setSpeed(speed); registerNoisy(); publish(); return
        }
        if (player != null) {
            publish(); return
        }
        val track = queue.tracks[queue.index];
        val request = ++generation
        val engine = MediaPlayer(); player = engine; prepared = false; duration = 0
        try {
            engine.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            engine.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            val uri = Uri.parse(track.uri)
            if (uri.scheme == "file") engine.setDataSource(checkNotNull(uri.path)) else engine.setDataSource(
                applicationContext,
                uri
            )
            engine.setOnPreparedListener {
                if (request != generation || player !== engine) return@setOnPreparedListener
                prepared = true; duration =
                runCatching { engine.duration.toLong() }.getOrDefault(0).coerceAtLeast(0)
                if (focusPolicy.wantsPlay && focus in setOf("GAIN", "DUCK")) {
                    engine.start(); engine.playbackParams =
                        PlaybackParams().setSpeed(speed); engine.setVolume(
                        volume,
                        volume
                    ); registerNoisy()
                }
                publish()
            }
            engine.setOnCompletionListener {
                if (request == generation && player === engine) {
                    if (endOfTrack) {
                        cancelSleep(); pause(true); seek(0)
                    } else advance(false, true)
                }
            }
            engine.setOnErrorListener { _, _, _ ->
                if (request == generation && player === engine) fail(
                    "PLAYER_MEDIA_ERROR"
                ); true
            }
            engine.prepareAsync(); publish()
        } catch (_: Exception) {
            fail("PLAYER_MEDIA_UNAVAILABLE")
        }
    }

    private fun pause(user: Boolean) {
        cancelHandover()
        if (user) focusPolicy.userPause()
        if (isPlaying()) runCatching { player?.pause() }
        unregisterNoisy()
        if (user) abandonFocus()
        publish()
    }

    private fun seek(position: Long) {
        if (prepared) {
            player?.seekTo(position.coerceIn(0, duration).toInt()); publish()
        }
    }

    private fun advance(previous: Boolean, automatic: Boolean) {
        val next = if (previous) queue.previous() else queue.next(automatic)
        if (next == null) {
            pause(true); seek(0); return
        }
        val intent = focusPolicy.wantsPlay
        releaseEngine(); duration = 0; error = null
        // Hẹn phút giữ deadline khi đổi bài; hết-bài gắn bài hiện tại nên hủy khi user đổi bài.
        if (endOfTrack) cancelSleep()
        if (intent) play() else publish()
    }

    private fun requestFocus(): Boolean {
        if (focus == "GAIN") return true
        abandonFocus();
        val expected = ++focusGeneration
        val listener = AudioManager.OnAudioFocusChangeListener { value ->
            handler.post {
                if (expected == focusGeneration && !closed) {
                    focus = when (value) {
                        AudioManager.AUDIOFOCUS_GAIN -> "GAIN"; AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "TRANSIENT"; AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "DUCK"; else -> "LOSS"
                    }
                    when (focusPolicy.change(value, isPlaying() || (!prepared && player != null))) {
                        FocusEffect.PLAY -> {
                            volume = 1f; if (prepared) {
                                player?.setVolume(1f, 1f); player?.start(); player?.playbackParams =
                                    PlaybackParams().setSpeed(speed); registerNoisy()
                            }
                        }

                        FocusEffect.PAUSE -> {
                            pause(false); if (value == AudioManager.AUDIOFOCUS_LOSS) abandonFocus()
                        }

                        FocusEffect.DUCK -> {
                            volume = .2f; player?.setVolume(volume, volume)
                        }

                        FocusEffect.RESTORE -> {
                            volume = 1f; player?.setVolume(1f, 1f)
                        }

                        FocusEffect.NONE -> Unit
                    }
                    publish()
                }
            }
        }
        val granted = if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                ).setWillPauseWhenDucked(false).setOnAudioFocusChangeListener(listener, handler)
                .build()
            focusRequest = request; audio.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION") audio.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ).also { legacyFocus = listener }
        }
        focus = if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GAIN" else "DENIED"
        return focus == "GAIN"
    }

    private fun abandonFocus() {
        ++focusGeneration
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audio.abandonAudioFocusRequest(it) } else {
            @Suppress("DEPRECATION") legacyFocus?.let { audio.abandonAudioFocus(it) }
        }
        focusRequest = null; legacyFocus = null; focus = "NONE"
    }

    private fun registerNoisy() {
        if (!noisyRegistered) {
            ContextCompat.registerReceiver(
                this,
                noisy,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            ); noisyRegistered = true
        }
    }

    private fun unregisterNoisy() {
        if (noisyRegistered) {
            unregisterReceiver(noisy); noisyRegistered = false
        }
    }

    private fun releaseEngine() {
        cancelHandover(); ++generation;
        val old = player; player = null; prepared =
            false; runCatching { old?.release() }; unregisterNoisy()
    }

    private fun fail(code: String) {
        focusPolicy.userPause(); releaseEngine(); abandonFocus(); error = code; publish()
    }

    private fun setSleep(millis: Long) {
        cancelSleep()
        if (millis == -1L) endOfTrack = true else if (millis > 0) {
            sleepDuration = millis; sleepAt = System.currentTimeMillis() + millis
            sleepJob = Runnable {
                sleepAt = 0; sleepDuration = 0; sleepJob = null; pause(true)
            }.also { handler.postDelayed(it, millis) }
        }
        publish()
    }

    private fun cancelSleep() {
        sleepJob?.let { handler.removeCallbacks(it) }; sleepJob = null; sleepAt = 0; sleepDuration =
            0; endOfTrack = false
    }

    private fun updateRoute() {
        val current = if (Build.VERSION.SDK_INT >= 28) player?.routedDevice else null
        val type = current?.type ?: audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type in setOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET
            )
        }?.type
        route = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bluetooth"; AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> "headphones"; else -> "speaker"
        }
        publish()
    }

    private fun publish() {
        if (closed) return
        val peerCount = peers.beginBroadcast()
        try {
            repeat(peerCount) { i ->
                runCatching {
                    peers.getBroadcastItem(i)
                        .onPlaybackChanged(isPlaying() || (player != null && !prepared) || pendingHandover != 0L)
                }
            }
        } finally {
            peers.finishBroadcast()
        }
        val position = if (prepared) runCatching {
            player?.currentPosition?.toLong() ?: 0
        }.getOrDefault(0) else 0
        snapshot = PlayerState(
            queue.tracks.map { it.copy(waveform = it.waveform.toList()) },
            queue.index,
            isPlaying(),
            (player != null && !prepared) || pendingHandover != 0L,
            position,
            duration,
            speed,
            queue.shuffle,
            queue.repeatMode,
            sleepAt,
            endOfTrack,
            route,
            focus,
            error,
            Process.myPid(),
            snapshot.revision + 1,
            sleepDuration
        )
        val count = callbacks.beginBroadcast()
        try {
            repeat(count) { i ->
                runCatching {
                    callbacks.getBroadcastItem(i).onStateChanged(snapshot)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
        val state =
            if (snapshot.playing) PlaybackState.STATE_PLAYING else if (snapshot.preparing) PlaybackState.STATE_BUFFERING else if (error != null) PlaybackState.STATE_ERROR else PlaybackState.STATE_PAUSED
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
                .setState(state, position, speed).build()
        )
        queue.tracks.getOrNull(queue.index)?.let { track ->
            session.setMetadata(
                MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration).build()
            )
        }
        session.isActive = queue.tracks.isNotEmpty()
        if (foreground) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
            NOTIFICATION,
            notification(snapshot)
        )
        updatePositionTicker()
    }

    private val positionTick = object : Runnable {
        override fun run() {
            if (closed || callbacks.registeredCallbackCount == 0 || !isPlaying()) return
            val value = runCatching { player!!.currentPosition.toLong() }.getOrDefault(0)
            snapshot = snapshot.copy(positionMs = value)
            val id = queue.tracks.getOrNull(queue.index)?.id ?: return
            val count = callbacks.beginBroadcast(); try {
                repeat(count) { i ->
                    runCatching {
                        callbacks.getBroadcastItem(i)
                            .onPositionChanged(snapshot.revision, id, value)
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun updatePositionTicker() {
        handler.removeCallbacks(positionTick); if (!closed && callbacks.registeredCallbackCount > 0 && isPlaying()) handler.postDelayed(
            positionTick,
            500
        )
    }

    private fun pending(action: String, code: Int) = PendingIntent.getService(
        this,
        code,
        intent(this, action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun notification(state: PlayerState): Notification {
        val content = PendingIntent.getActivity(
            this,
            18000,
            Intent().setClassName(packageName, packageName + ".feature.player.PlayerActivity")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION") val builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(
                this,
                CHANNEL
            ) else Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.ic_mic).setContentTitle(
            state.queue.getOrNull(state.index)?.title ?: getString(R.string.m5_player_title)
        )
            .setContentText(getString(if (state.playing) R.string.m5_player_playing else R.string.m5_player_paused))
            .setContentIntent(content).setOnlyAlertOnce(true).setOngoing(state.playing)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(
                        this,
                        R.drawable.ic_player_previous
                    ), getString(R.string.m5_player_previous), pending(PREVIOUS, 18011)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(
                        this,
                        if (state.playing) R.drawable.ic_media_pause else R.drawable.ic_media_play
                    ),
                    getString(if (state.playing) R.string.m5_player_pause else R.string.m5_player_play),
                    pending(if (state.playing) PAUSE else PLAY, 18012)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(
                        this,
                        R.drawable.ic_player_next
                    ), getString(R.string.m5_player_next), pending(NEXT, 18013)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(
                        this,
                        R.drawable.ic_close
                    ), getString(R.string.m5_player_close), pending(CLOSE, 18014)
                ).build()
            )
            .setStyle(
                Notification.MediaStyle().setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            ).build()
    }

    private fun shutdown() {
        if (closed) return
        focusPolicy.userPause(); cancelSleep(); releaseEngine(); abandonFocus()
        queue.replace(emptyList(), -1); error = "PLAYER_CLOSED"; publish()
        val count = peers.beginBroadcast(); try {
            repeat(count) { i -> runCatching { peers.getBroadcastItem(i).onClosed() } }
        } finally {
            peers.finishBroadcast()
        }
        peers.kill(); closed = true
        handler.removeCallbacks(positionTick); callbacks.kill(); session.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; stopSelf()
    }

    override fun onDestroy() {
        handler.post { if (!closed) shutdown(); audio.unregisterAudioDeviceCallback(devices); session.release(); thread.quitSafely() }
        super.onDestroy()
    }
}
