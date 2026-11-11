package com.ntq.training.momentbox.core.media

import kotlinx.coroutines.flow.MutableStateFlow
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.ntq.training.momentbox.MomentBoxApp
import kotlinx.coroutines.flow.first

class AndroidPlaybackAudio private constructor(private val context: Application) : PlaybackAudio {
    private val bridge get() = (context as? MomentBoxApp)?.playerAudioBridge
    private val recordingOwners = mutableSetOf<Any>()
    private val recordersIdle = MutableStateFlow(true)
    suspend fun awaitRecordersReleased() {
        recordersIdle.first { it }
    }

    private val ownership = AudioOwnership()
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var legacyListener: AudioManager.OnAudioFocusChangeListener? = null
    private var receiverRegistered = false
    private var focusGeneration = 0L
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) interrupt()
        }
    }

    fun acquireOwnership(owner: Any, onInterrupted: () -> Unit): Boolean =
        acquireOwnershipInternal(owner, onInterrupted, false)

    fun acquireRecordingOwnership(owner: Any, onInterrupted: () -> Unit): Boolean =
        acquireOwnershipInternal(owner, onInterrupted, true)

    private fun acquireOwnershipInternal(
        owner: Any,
        onInterrupted: () -> Unit,
        transientLoss: Boolean
    ): Boolean {
        if (recordingOwners.any { it !== owner }) return false
        if (!ownership.acquire(owner, onInterrupted, true)) return false
        if (transientLoss) {
            recordingOwners.add(owner); recordersIdle.value = false
        }
        bridge?.interruptForMain(transientLoss)
        abandonPlatformFocus()
        unregisterNoisyReceiver()
        return true
    }

    override fun acquire(
        owner: Any,
        onInterrupted: () -> Unit,
        mayInterrupt: Boolean,
        kind: AudioPlaybackKind
    ): Boolean {
        // Mic đang ghi có ưu tiên hơn media; tránh khởi động engine thứ hai trong lúc stop IO.
        if (recordingOwners.any { it !== owner }) return false
        if (!mayInterrupt && bridge?.active == true) return false
        if (ownership.isOwner(owner)) return true
        if (!ownership.acquire(owner, onInterrupted, mayInterrupt)) return false
        if (kind == AudioPlaybackKind.RECORDING) {
            recordingOwners.add(owner); recordersIdle.value = false
        }
        bridge?.interruptForMain(kind != AudioPlaybackKind.VIDEO)
        // Mỗi yêu cầu giữ generation riêng để callback focus cũ không dừng player mới.
        abandonPlatformFocus()
        val requestGeneration = ++focusGeneration
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (requestGeneration == focusGeneration && change < 0) interrupt()
        }
        // Cùng một owner trong app nhưng hợp đồng với âm thanh ngoài app khác theo loại media.
        val gain = when (kind) {
            AudioPlaybackKind.VOICE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioPlaybackKind.RECORDING -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            AudioPlaybackKind.VIDEO -> AudioManager.AUDIOFOCUS_GAIN
        }
        val contentType = if (kind != AudioPlaybackKind.VIDEO) AudioAttributes.CONTENT_TYPE_SPEECH
        else AudioAttributes.CONTENT_TYPE_MOVIE
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(contentType).build()
                )
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        } else {
            legacyListener = listener
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, gain)
        } == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            // Quick recorder chưa vào PREPARING nên callback finish có thể chưa chạy cleanup.
            // Request bị OS từ chối chưa sở hữu mic: rollback lease ngay, không khóa owner kế tiếp.
            recordingOwners.remove(owner); recordersIdle.value = recordingOwners.isEmpty()
            interrupt()
            return false
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        return true
    }

    override fun release(owner: Any) {
        recordingOwners.remove(owner); recordersIdle.value = recordingOwners.isEmpty()
        if (!ownership.isOwner(owner)) return
        ownership.release(owner)
        abandonPlatformFocus()
        unregisterNoisyReceiver()
    }

    fun interruptForRemotePlayer() {
        interrupt()
    }

    private fun interrupt() {
        abandonPlatformFocus()
        unregisterNoisyReceiver()
        ownership.interrupt()
    }

    private fun abandonPlatformFocus() {
        focusGeneration++
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let { manager.abandonAudioFocus(it) }
            legacyListener = null
        }
    }

    private fun unregisterNoisyReceiver() {
        if (receiverRegistered) {
            context.unregisterReceiver(noisyReceiver)
            receiverRegistered = false
        }
    }

    companion object {
        @Volatile
        private var instance: AndroidPlaybackAudio? = null
        fun get(context: Context): AndroidPlaybackAudio = instance ?: synchronized(this) {
            instance ?: AndroidPlaybackAudio(context.applicationContext as Application).also {
                instance = it
            }
        }
    }
}