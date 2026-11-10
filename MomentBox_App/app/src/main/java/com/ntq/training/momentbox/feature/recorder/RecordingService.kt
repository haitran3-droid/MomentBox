package com.ntq.training.momentbox.feature.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.media.AndroidPlaybackAudio
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.getValue
import kotlin.math.ln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordingService : Service() {
    companion object {
        private val mutable = MutableStateFlow(RecordingSnapshot())

        val state = mutable.asStateFlow()
        private const val INTERRUPT = "record.interrupt"

        const val START = "record.start"

        const val TOGGLE = "record.toggle"

        const val SAVE = "record.save"

        const val CANCEL = "record.cancel"
        private const val CHANNEL = "momentbox.recording"

        private const val NOTICE = 14001

        fun intent(context: Context, action: String, session: String) =
            Intent(context, RecordingService::class.java)
                .setAction(action)
                .putExtra("session", session)
    }

    inner class LocalBinder : Binder() {
        fun command(action: String, session: String) {
            commands.trySend(action to session)
        }
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Channel<Pair<String, String>>(Channel.UNLIMITED)
    private var recorder: MediaRecorder? = null

    private var file: File? = null
    private var ticker: Job? = null

    private val clock = RecorderClock()

    private val samples = ArrayList<Int>()
    private val owner = Any()

    private val shared by lazy { AndroidPlaybackAudio.get(this) }
    private val manager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var focusRequest: AudioFocusRequest? = null

    private var legacyFocus: AudioManager.OnAudioFocusChangeListener? = null
    private var focusGeneration = 0L
    private val routes =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) {
                mutable.value = mutable.value.copy(input = actualInput())
            }

            override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) {
                mutable.value = mutable.value.copy(input = actualInput())
            }
        }
    private var tick: com.ntq.training.momentbox.core.media.StaticPcmSound? = null
    private var lastNotify = 0L
    private var wake: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        tick =
            runCatching {
                    com.ntq.training.momentbox.core.media.StaticPcmSound(this, "audio/sfx-tick.wav")
                }
                .getOrNull()
        manager.registerAudioDeviceCallback(routes, Handler(Looper.getMainLooper()))
        if (Build.VERSION.SDK_INT >= 26)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        getString(R.string.m4_record_title),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
        scope.launch {
            for ((action, session) in commands) {
                try {
                    if (
                        action == START &&
                            mutable.value.phase !in
                                listOf(
                                    RecordPhase.PREPARING,
                                    RecordPhase.RECORDING,
                                    RecordPhase.PAUSED,
                                    RecordPhase.STOPPING,
                                )
                    )
                        start(session)
                    else if (session == mutable.value.session)
                        when (action) {
                            INTERRUPT -> if (mutable.value.phase == RecordPhase.RECORDING) pause()
                            TOGGLE ->
                                if (mutable.value.phase == RecordPhase.RECORDING) pause()
                                else if (mutable.value.phase == RecordPhase.PAUSED) resume()
                            SAVE -> finish(false)
                            CANCEL -> finish(true)
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    fail()
                }
            }
        }
    }

    override fun onBind(intent: Intent) = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = intent?.getStringExtra("session") ?: return START_NOT_STICKY
        val action = intent.action ?: return START_NOT_STICKY
        if (action == START) {
            // FGS luôn promote ngay, trước prepare mic/IO, thao tác bắt đầu phải đến từ Activity
            // foreground.
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTICE,
                    notification(RecordingSnapshot(session, RecordPhase.PREPARING)),
                    if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    else 0,
                )
            } catch (_: Exception) {
                mutable.value = RecordingSnapshot(session, RecordPhase.ERROR, error = true)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        commands.trySend(action to session)
        return START_NOT_STICKY
    }

    private fun acquireFocus(): Boolean {
        if (
            !shared.acquireOwnership(owner) {
                if (mutable.value.phase == RecordPhase.RECORDING)
                    commands.trySend(INTERRUPT to mutable.value.session)
            }
        )
            return false
        val generation = ++focusGeneration
        val listener =
            AudioManager.OnAudioFocusChangeListener { change ->
                if (
                    change < 0 &&
                        generation == focusGeneration &&
                        mutable.value.phase == RecordPhase.RECORDING
                )
                    commands.trySend(INTERRUPT to mutable.value.session)
            }
        val granted =
            if (Build.VERSION.SDK_INT >= 26) {
                val request =
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
                        .build()
                focusRequest = request
                manager.requestAudioFocus(request)
            } else {
                legacyFocus = listener
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
                )
            }
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) releaseFocus()
        return granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun releaseFocus() {
        focusGeneration++
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        else {
            @Suppress("DEPRECATION") legacyFocus?.let { manager.abandonAudioFocus(it) }
        }
        focusRequest = null
        legacyFocus = null
        shared.release(owner)
    }

    private fun actualInput(): String? =
        if (Build.VERSION.SDK_INT >= 28) recorder?.routedDevice?.productName?.toString() else null

    private suspend fun start(session: String) {
        mutable.value = RecordingSnapshot(session, RecordPhase.PREPARING)
        samples.clear()
        if (!acquireFocus()) error("Audio focus denied")
        val folder = File(filesDir, "recordings").apply { check(mkdirs() || isDirectory) }
        val output =
            File(
                folder,
                "rec_${
                SimpleDateFormat(
                    "yyyyMMdd_HHmmss",
                    Locale.ROOT,
                ).format(Date())
            }_${UUID.randomUUID()}.pending.m4a",
            )
        file = output
        tick?.play()
        delay(120)
        val rec =
            if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
            else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
        recorder = rec
        withContext(Dispatchers.IO) {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128000)
            rec.setAudioSamplingRate(44100)
            rec.setAudioChannels(1)
            rec.setOutputFile(output.absolutePath)
            rec.prepare()
            rec.start()
        }
        clock.start(SystemClock.elapsedRealtime())
        mutable.value = mutable.value.copy(phase = RecordPhase.RECORDING, input = actualInput())
        wake =
            (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MomentBox:Recording")
                .apply { acquire(310_000) }
        ticker =
            scope.launch {
                var ticks = 0
                while (isActive) {
                    delay(50)

                    val now = SystemClock.elapsedRealtime()
                    if (mutable.value.phase == RecordPhase.RECORDING) {
                        val raw =
                            runCatching { recorder?.maxAmplitude ?: 0 }
                                .getOrDefault(0)
                                .coerceAtLeast(0)
                        if (samples.size < 6000)
                            samples.add(
                                (ln(1.0 + raw) / ln(32768.0) * 100).toInt().coerceIn(0, 100)
                            )
                    }
                    if (++ticks % 2 == 0)
                        mutable.value =
                            mutable.value.copy(
                                elapsedMs = clock.elapsed(now),
                                waveform = samples.toList(),
                                input = actualInput(),
                            )
                    if (now - lastNotify >= 1000) {
                        lastNotify = now
                        notifyState()
                    }
                    if (mutable.value.phase == RecordPhase.RECORDING && clock.atLimit(now)) {
                        commands.send(SAVE to session)
                        break
                    }
                }
            }
        notifyState()
    }

    private suspend fun pause() {
        withContext(Dispatchers.IO) { recorder?.pause() }
        clock.pause(SystemClock.elapsedRealtime())
        mutable.value =
            mutable.value.copy(
                phase = RecordPhase.PAUSED,
                elapsedMs = clock.elapsed(SystemClock.elapsedRealtime()),
            )
        releaseFocus()
        wake?.takeIf { it.isHeld }?.release()
        notifyState()
    }

    private suspend fun resume() {
        if (!acquireFocus()) return
        withContext(Dispatchers.IO) { recorder?.resume() }
        clock.resume(SystemClock.elapsedRealtime())
        mutable.value = mutable.value.copy(phase = RecordPhase.RECORDING)
        wake?.acquire(310_000)
        notifyState()
    }

    private suspend fun finish(cancel: Boolean) {
        if (mutable.value.phase !in listOf(RecordPhase.RECORDING, RecordPhase.PAUSED)) return
        mutable.value = mutable.value.copy(phase = RecordPhase.STOPPING)
        ticker?.cancel()
        ticker = null
        val output = file
        try {
            withContext(Dispatchers.IO) {
                recorder?.stop()
                recorder?.release()
                recorder = null
            }
            releaseFocus()
            wake?.takeIf { it.isHeld }?.release()
            if (cancel) {
                output?.delete()
                mutable.value = RecordingSnapshot(mutable.value.session)
            } else {
                check(output != null && output.length() > 0)
                val duration =
                    withContext(Dispatchers.IO) {
                        MediaMetadataRetriever().run {
                            try {
                                setDataSource(output.absolutePath)
                                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                    ?.toLongOrNull() ?: 0
                            } finally {
                                release()
                            }
                        }
                    }
                check(duration > 0)
                val final = File(output.parentFile, output.name.replace(".pending.m4a", ".m4a"))
                check(output.renameTo(final))
                mutable.value =
                    mutable.value.copy(
                        phase = RecordPhase.SAVED,
                        elapsedMs = duration,
                        waveform = samples.toList(),
                        uri =
                            FileProvider.getUriForFile(this, "$packageName.media", final).toString(),
                    )
            }
        } catch (_: Exception) {
            fail()
        } finally {
            file = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fail() {
        ticker?.cancel()
        runCatching { recorder?.release() }
        recorder = null
        file?.delete()
        file = null
        releaseFocus()
        wake?.takeIf { it.isHeld }?.release()
        mutable.value = mutable.value.copy(phase = RecordPhase.ERROR, error = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun pending(action: String, session: String) =
        PendingIntent.getService(
            this,
            (session + action).hashCode(),
            intent(this, action, session).setData(Uri.parse("momentbox://record/$session/$action")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notification(s: RecordingSnapshot): Notification {
        val content =
            PendingIntent.getActivity(
                this,
                s.session.hashCode(),
                Intent(this, RecorderActivity::class.java)
                    .putExtra("session", s.session)
                    .setData(Uri.parse("momentbox://record/${s.session}")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_journal_mic)
            .setContentTitle(getString(R.string.m4_record_title))
            .setContentText(
                getString(
                    if (s.phase == RecordPhase.PAUSED) R.string.m4_paused else R.string.m4_recording
                )
            )
            .setContentIntent(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(System.currentTimeMillis() - s.elapsedMs)
            .setUsesChronometer(s.phase == RecordPhase.RECORDING)
            .addAction(
                0,
                getString(
                    if (s.phase == RecordPhase.PAUSED) R.string.m4_resume else R.string.m4_pause
                ),
                pending(TOGGLE, s.session),
            )
            .addAction(0, getString(R.string.m4_done), pending(SAVE, s.session))
            .build()
    }

    private fun notifyState() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
            NOTICE,
            notification(mutable.value),
        )
    }

    override fun onDestroy() {
        tick?.close()
        tick = null
        manager.unregisterAudioDeviceCallback(routes)
        ticker?.cancel()
        runCatching { recorder?.release() }
        recorder = null
        wake?.takeIf { it.isHeld }?.release()
        releaseFocus()
        if (
            mutable.value.phase in
                listOf(RecordPhase.RECORDING, RecordPhase.PAUSED, RecordPhase.PREPARING)
        )
            mutable.value = mutable.value.copy(phase = RecordPhase.ERROR, error = true)
        scope.cancel()
        super.onDestroy()
    }
}
