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

class AmbientAudioController(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    enum class SoundType(val assetPath: String, val titleRes: Int) {
        RAIN("audio/ambient-rain.wav", R.string.editor_ambient_rain),
        OCEAN("audio/ambient-ocean.wav", R.string.editor_ambient_ocean),
        OFF("", R.string.editor_ambient_off)
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var streamingJob: Job? = null

    private var audioTrack: AudioTrack? = null
    private var currentSound: SoundType = SoundType.RAIN
    private var userVolume: Float = 0.6f
    private var isPlaying: Boolean = false
    private var isUserPaused: Boolean = true
    private var isPausedByFocus: Boolean = false
    private var isDucked: Boolean = false

    private var focusRequest: AudioFocusRequest? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var noisyReceiverRegistered = false

    var onPlaybackStateChanged: ((isPlaying: Boolean, sound: SoundType, volume: Float) -> Unit)? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause()
            }
        }
    }

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        val sampleRate = 22050
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(8192)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .setEncoding(audioFormat)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        updateTrackVolume()
    }

    fun setSound(sound: SoundType) {
        if (currentSound == sound) return
        val wasPlaying = isPlaying
        stopStreaming()
        currentSound = sound
        if (sound == SoundType.OFF) {
            isUserPaused = true
            abandonFocus()
            notifyState()
            return
        }
        if (wasPlaying) {
            play()
        } else {
            notifyState()
        }
    }

    fun getSound(): SoundType = currentSound

    fun setVolume(volume: Float) {
        userVolume = volume.coerceIn(0f, 1f)
        updateTrackVolume()
        notifyState()
    }

    fun getVolume(): Float = userVolume

    fun isPlaying(): Boolean = isPlaying

    fun play() {
        if (currentSound == SoundType.OFF) return
        if (isPlaying) return

        if (!requestPlatformFocus()) {
            return
        }

        isUserPaused = false
        isPausedByFocus = false
        registerNoisyReceiver()

        audioTrack?.let { track ->
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                initAudioTrack()
            }
            track.play()
        }

        startStreaming(currentSound.assetPath)
        isPlaying = true
        notifyState()
    }

    fun pause() {
        if (!isPlaying) return
        isUserPaused = true
        isPausedByFocus = false
        stopStreaming()
        audioTrack?.pause()
        unregisterNoisyReceiver()
        abandonFocus()
        isPlaying = false
        notifyState()
    }

    fun togglePlayPause() {
        if (isPlaying) pause() else play()
    }

    fun release() {
        stopStreaming()
        audioTrack?.let {
            try {
                if (it.state == AudioTrack.STATE_INITIALIZED) {
                    it.stop()
                    it.release()
                }
            } catch (_: Exception) {}
        }
        audioTrack = null
        unregisterNoisyReceiver()
        abandonFocus()
        scope.cancel()
        isPlaying = false
    }

    private fun startStreaming(assetPath: String) {
        streamingJob?.cancel()
        streamingJob = scope.launch(dispatcher) {
            val track = audioTrack ?: return@launch
            var inputStream: InputStream? = null
            try {
                var assetStream = context.assets.open(assetPath)
                val wavHeader = parseWavHeader(assetStream)
                assetStream.close()

                val dataOffset = wavHeader.dataOffset
                val dataLength = wavHeader.dataLength

                val buffer = ByteArray(4096)
                while (isActive) {
                    inputStream = context.assets.open(assetPath)
                    // Bỏ qua header để trỏ thẳng tới dữ liệu PCM âm thanh
                    var skipped = 0L
                    while (skipped < dataOffset) {
                        val s = inputStream.skip(dataOffset - skipped)
                        if (s <= 0) break
                        skipped += s
                    }

                    var bytesRemainingInLoop = dataLength
                    while (isActive && bytesRemainingInLoop > 0) {
                        val toRead = minOf(buffer.size.toLong(), bytesRemainingInLoop).toInt()
                        val read = inputStream.read(buffer, 0, toRead)
                        if (read <= 0) break
                        bytesRemainingInLoop -= read

                        var written = 0
                        while (written < read && isActive) {
                            val ret = track.write(buffer, written, read - written)
                            if (ret <= 0) break
                            written += ret
                        }
                    }
                    inputStream.close()
                    inputStream = null
                }
            } catch (_: Exception) {
                // Thoát streaming an toàn khi bị hủy job
            } finally {
                try { inputStream?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
    }

    private fun updateTrackVolume() {
        val effectiveVolume = if (isDucked) userVolume * 0.30f else userVolume
        audioTrack?.let {
            if (it.state == AudioTrack.STATE_INITIALIZED) {
                it.setVolume(effectiveVolume)
            }
        }
    }

    private fun requestPlatformFocus(): Boolean {
        val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // BA 11.4: Giảm âm lượng còn 30%
                    isDucked = true
                    updateTrackVolume()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // BA 11.4: Tạm dừng phát
                    if (isPlaying) {
                        isPausedByFocus = true
                        stopStreaming()
                        audioTrack?.pause()
                        isPlaying = false
                        notifyState()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // BA 11.4: Mất hẳn focus -> Dừng
                    pause()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // Khôi phục sau ducking hoặc transient loss
                    if (isDucked) {
                        isDucked = false
                        updateTrackVolume()
                    }
                    if (isPausedByFocus && !isUserPaused) {
                        isPausedByFocus = false
                        audioTrack?.play()
                        startStreaming(currentSound.assetPath)
                        isPlaying = true
                        notifyState()
                    }
                }
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            legacyFocusListener = listener
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            legacyFocusListener?.let {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(it)
            }
            legacyFocusListener = null
        }
    }

    private fun registerNoisyReceiver() {
        if (!noisyReceiverRegistered) {
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyReceiverRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (noisyReceiverRegistered) {
            try { context.unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
            noisyReceiverRegistered = false
        }
    }

    private fun notifyState() {
        onPlaybackStateChanged?.invoke(isPlaying, currentSound, userVolume)
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
