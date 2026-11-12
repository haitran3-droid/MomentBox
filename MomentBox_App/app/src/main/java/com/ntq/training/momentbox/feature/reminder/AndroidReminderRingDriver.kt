package com.ntq.training.momentbox.feature.reminder

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.ntq.training.momentbox.core.media.AndroidPlaybackAudio
import com.ntq.training.momentbox.core.notification.NotificationChannels
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class AndroidReminderRingDriver(
    context: Context,
    private val scope: CoroutineScope
): ReminderRingDriver {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val ownership = AndroidPlaybackAudio.get(app)
    private val owner = Any()
    private var player: MediaPlayer? = null
    private var preparing: Job? = null
    private var generation = 0L
    private var request: AudioFocusRequest? = null
    private var listener: AudioManager.OnAudioFocusChangeListener? = null
    private var noisyRegistered = false
    private var interrupted: (() -> Unit)? = null
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) interrupted?.invoke()
        }
    }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    override fun canPlay(): Boolean {
        if (!AndroidReminderAlarms(app).capabilities().notifications) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = app.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(NotificationChannels.REMINDER) ?: return false
            // Kênh cũ có sound dùng chuông OS, không thêm MediaPlayer gây hai nguồn âm thanh.
            if (channel.sound != null || channel.importance < NotificationManager.IMPORTANCE_DEFAULT) return false
        }
        return RingtoneManager.getActualDefaultRingtoneUri(
            app,
            RingtoneManager.TYPE_ALARM
        ) != null && audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0
    }

    override fun acquire(onInterrupted: () -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        interrupted = onInterrupted
        if (!ownership.acquireReminderOwnership(owner, onInterrupted)) return false
        val epoch = generation
        val callback =
            AudioManager.OnAudioFocusChangeListener { change -> if (epoch == generation && change < 0) interrupted?.invoke() }
        listener = callback
        val granted = if (Build.VERSION.SDK_INT >= 26) {
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(callback, Handler(Looper.getMainLooper())).build()
            request = focus
            audio.requestAudioFocus(focus)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(
                callback,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        } == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            ContextCompat.registerReceiver(
                app,
                noisy,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyRegistered = true
        }
        return granted
    }

    override fun prepare(onReady: () -> Unit, onError: (String) -> Unit) {
        val epoch = generation
        preparing = scope.launch {
            var descriptor: AssetFileDescriptor? = null
            try {
                val uri = withContext(Dispatchers.IO) {
                    RingtoneManager.getActualDefaultRingtoneUri(
                        app,
                        RingtoneManager.TYPE_ALARM
                    )
                }
                    ?: throw FileNotFoundException("NO_DEFAULT_ALARM")
                withContext(Dispatchers.IO) {
                    descriptor = app.contentResolver.openAssetFileDescriptor(uri, "r")
                }
                ensureActive()
                if (epoch != generation) return@launch
                val fd = descriptor ?: throw IOException("ALARM_UNAVAILABLE")
                val native = MediaPlayer()
                player = native
                native.setAudioAttributes(attributes)
                native.setWakeMode(
                    app,
                    PowerManager.PARTIAL_WAKE_LOCK
                )
                native.isLooping = true
                native.setVolume(0f, 0f)
                native.setOnPreparedListener { if (epoch == generation && player === it) onReady() }
                native.setOnErrorListener { _, what, extra -> if (epoch == generation) onError("NATIVE_${what}_$extra")
                true }
                if (fd.declaredLength < 0) native.setDataSource(fd.fileDescriptor)
                else native.setDataSource(fd.fileDescriptor, fd.startOffset, fd.declaredLength)
                native.prepareAsync()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (epoch == generation) onError("PREPARE_" + failure.javaClass.simpleName)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { descriptor?.close() } }
            }
        }
    }

    override fun start(onStarted: () -> Unit, onError: (String) -> Unit) {
        val native = player ?: run { onError("NO_PLAYER")
        return }
        native.start()
        if (native.isPlaying) onStarted() else onError("NOT_PLAYING")
    }

    override fun volume(value: Float) {
        player?.setVolume(value, value)
    }

    override fun release() {
        ++generation
        interrupted = null
        preparing?.cancel()
        preparing = null
        player?.let { native ->
            player = null
            runCatching {
            native.setOnPreparedListener(null)
            native.setOnErrorListener(
            null
        )
        native.release()
        }
        }
        if (noisyRegistered) {
            noisyRegistered = false
            app.unregisterReceiver(noisy)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            request?.let { audio.abandonAudioFocusRequest(it) }
            request = null
        } else {
            @Suppress("DEPRECATION") listener?.let { audio.abandonAudioFocus(it) }
        }
        listener = null
        ownership.release(owner)
    }
}
