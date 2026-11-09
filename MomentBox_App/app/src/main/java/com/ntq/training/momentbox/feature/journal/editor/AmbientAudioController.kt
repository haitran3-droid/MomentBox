package com.ntq.training.momentbox.feature.journal.editor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.ntq.training.momentbox.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream


import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import com.ntq.training.momentbox.core.media.AndroidPlaybackAudio

class AmbientAudioController(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    enum class SoundType(val assetPath: String, val titleRes: Int) {
        RAIN("ambient-rain.wav", R.string.editor_ambient_rain),
        OCEAN("ambient-ocean.wav", R.string.editor_ambient_ocean), OFF(
            "",
            R.string.editor_ambient_off
        )
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val ownership = AndroidPlaybackAudio.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val trackMutex = Mutex()
    private var writer: Job? = null
    internal val completedLoops = java.util.concurrent.atomic.AtomicLong()
    private var releaseJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var currentSound = SoundType.RAIN
    @Volatile
    private var userVolume = 0.6f
    @Volatile
    private var isDucked = false
    @Volatile
    private var isPlaying = false
    private var isUserPaused = true
    private var isPausedByFocus = false
    private var foreground = true
    private var released = false
    private var generation = 0L
    private var focusGeneration = 0L
    private var focusRequest: AudioFocusRequest? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var noisyReceiverRegistered = false
    var onPlaybackStateChanged: ((Boolean, SoundType, Float) -> Unit)? = null
    var onPlaybackError: (() -> Unit)? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    fun setForeground(visible: Boolean) {
        foreground = visible
        if (!visible) pause() // Kể cả đã transient-paused: phải xóa quyền auto-resume.
    }

    fun getSound() = currentSound
    fun getVolume() = userVolume
    fun isPlaying() = isPlaying
    fun setVolume(value: Float) {
        userVolume = value.coerceIn(0f, 1f); notifyState()
    }

    fun setSound(sound: SoundType) {
        if (currentSound == sound) return
        val restart = isPlaying
        pause()
        currentSound = sound
        if (restart && sound != SoundType.OFF) play() else notifyState()
    }

    fun togglePlayPause() {
        if (isPlaying) pause() else play()
    }

    fun play() {
        if (released || !foreground || currentSound == SoundType.OFF || isPlaying) return
        if (!ownership.acquireOwnership(this) { pause() } || !requestPlatformFocus()) {
            ownership.release(this)
            return
        }
        isUserPaused = false; isPausedByFocus = false; isDucked = false
        if (!noisyReceiverRegistered) {
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyReceiverRegistered = true
        }
        startWriter()
    }

    fun pause() {
        // Không early-return khi isPlaying=false; user Pause luôn vô hiệu hóa callback GAIN cũ.
        isUserPaused = true; isPausedByFocus = false; isDucked = false
        stopWriter()
        abandonFocus(); ownership.release(this)
        if (noisyReceiverRegistered) {
            context.unregisterReceiver(noisyReceiver); noisyReceiverRegistered = false
        }
    }

    private fun stopWriter() {
        generation++
        val previous = writer
        previous?.cancel()
        isPlaying = false
        writer = scope.launch(dispatcher) {
            previous?.cancelAndJoin()
            trackMutex.withLock {
                audioTrack?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }
                    ?.let { it.pause(); it.flush() }
            }
        }
        notifyState()
    }

    private fun startWriter() {
        val previous = writer
        previous?.cancel()
        val expected = ++generation
        val path = currentSound.assetPath
        isPlaying = true
        notifyState()
        writer = scope.launch(dispatcher) {
            previous?.cancelAndJoin()
            try {
                trackMutex.withLock {
                    val track = audioTrack ?: AudioTrack.Builder().setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                    )
                        .setAudioFormat(
                            AudioFormat.Builder().setSampleRate(22050)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                        )
                        .setBufferSizeInBytes(
                            AudioTrack.getMinBufferSize(
                                22050,
                                AudioFormat.CHANNEL_OUT_MONO,
                                AudioFormat.ENCODING_PCM_16BIT
                            ).coerceAtLeast(8192)
                        )
                        .setTransferMode(AudioTrack.MODE_STREAM).build().also { audioTrack = it }
                    check(track.state == AudioTrack.STATE_INITIALIZED)
                    track.pause(); track.flush(); track.play()
                    val info = context.assets.open(path).use { parseWavHeader(it) }
                    check(info.dataLength > 0 && info.dataLength < Long.MAX_VALUE)
                    val buffer = ByteArray(4096)
                    while (isActive) {
                        context.assets.open(path).use { input ->
                            var skip = info.dataOffset
                            while (skip > 0) {
                                val n = input.skip(skip); check(n > 0); skip -= n
                            }
                            var remaining = info.dataLength
                            while (isActive && remaining > 0) {
                                val count = input.read(
                                    buffer,
                                    0,
                                    minOf(remaining, buffer.size.toLong()).toInt()
                                )
                                check(count > 0); remaining -= count
                                var offset = 0
                                while (isActive && offset < count) {
                                    track.setVolume(if (isDucked) userVolume * 0.30f else userVolume)
                                    // Nonblocking write cho phép cancellation được quan sát; 0 là backpressure, âm là lỗi.
                                    val n = track.write(
                                        buffer,
                                        offset,
                                        count - offset,
                                        AudioTrack.WRITE_NON_BLOCKING
                                    )
                                    check(n >= 0) { "AudioTrack write error $n" }
                                    if (n == 0) kotlinx.coroutines.delay(5) else offset += n
                                }
                            }
                            if (remaining == 0L) completedLoops.incrementAndGet()
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                withContext(Dispatchers.Main) {
                    if (generation == expected && !released) {
                        pause(); onPlaybackError?.invoke()
                    }
                }
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        pause()
        onPlaybackStateChanged = null; onPlaybackError = null
        val previous = writer
        releaseJob = scope.launch(dispatcher) {
            previous?.cancelAndJoin()
            try {
                trackMutex.withLock { audioTrack?.release(); audioTrack = null }
            } finally {
                scope.cancel()
            }
        }
    }

    suspend fun awaitReleased() {
        releaseJob?.join()
    }

    private fun notifyState() {
        onPlaybackStateChanged?.invoke(isPlaying, currentSound, userVolume)
    }

    private fun requestPlatformFocus(): Boolean {
        val expected = ++focusGeneration
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (expected != focusGeneration || released || !foreground) return@OnAudioFocusChangeListener
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> isDucked = true
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (isPlaying) {
                    isPausedByFocus = true; stopWriter()
                }

                AudioManager.AUDIOFOCUS_LOSS -> pause()
                AudioManager.AUDIOFOCUS_GAIN -> {
                    isDucked = false
                    if (isPausedByFocus && !isUserPaused) {
                        isPausedByFocus = false; startWriter()
                    }
                }
            }
        }
        return if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper())).build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            legacyFocusListener = listener
            @Suppress("DEPRECATION")
            (audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }
    }

    private fun abandonFocus() {
        focusGeneration++
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let {
            audioManager.abandonAudioFocusRequest(
                it
            )
        }
        else {
            @Suppress("DEPRECATION")
            legacyFocusListener?.let { audioManager.abandonAudioFocus(it) }
        }
        focusRequest = null; legacyFocusListener = null
    }

    /**
     * Phân tích cấu trúc Header WAV để tìm offset bắt đầu của chunk `data` và độ dài byte dữ liệu.
     */
    data class WavInfo(val dataOffset: Long, val dataLength: Long)

    private fun parseWavHeader(stream: InputStream): WavInfo {
        val header = ByteArray(12)
        stream.read(header, 0, 12)
        var offset = 12L
        var dataOffset = 44L
        var dataLength = Long.MAX_VALUE

        val chunkHeader = ByteArray(8)
        while (stream.read(chunkHeader, 0, 8) == 8) {
            offset += 8
            val chunkId = String(chunkHeader, 0, 4)
            val chunkSize = (chunkHeader[4].toInt() and 0xFF) or
                    ((chunkHeader[5].toInt() and 0xFF) shl 8) or
                    ((chunkHeader[6].toInt() and 0xFF) shl 16) or
                    ((chunkHeader[7].toInt() and 0xFF) shl 24)

            if (chunkId == "data") {
                dataOffset = offset
                dataLength = chunkSize.toLong()
                break
            } else {
                stream.skip(chunkSize.toLong())
                offset += chunkSize
            }
        }
        return WavInfo(dataOffset, dataLength)
    }
}
