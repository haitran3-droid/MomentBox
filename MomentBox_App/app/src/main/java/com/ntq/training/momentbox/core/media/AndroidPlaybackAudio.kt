package com.ntq.training.momentbox.core.media

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

class AndroidPlaybackAudio private constructor(private val context: Application) : PlaybackAudio {
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

    fun acquireOwnership(owner: Any, onInterrupted: () -> Unit): Boolean {
        if (!ownership.acquire(owner, onInterrupted, true)) return false
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
        if (ownership.isOwner(owner)) return true
        if (!ownership.acquire(owner, onInterrupted, mayInterrupt)) return false
        abandonPlatformFocus()
        val requestGeneration = ++focusGeneration
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (requestGeneration == focusGeneration && change < 0) interrupt()
        }
        val gain = if (kind == AudioPlaybackKind.VOICE) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        else AudioManager.AUDIOFOCUS_GAIN
        val contentType = if (kind == AudioPlaybackKind.VOICE) AudioAttributes.CONTENT_TYPE_SPEECH
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
        if (!ownership.isOwner(owner)) return
        ownership.release(owner)
        abandonPlatformFocus()
        unregisterNoisyReceiver()
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