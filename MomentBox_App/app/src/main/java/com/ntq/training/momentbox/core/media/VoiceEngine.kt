package com.ntq.training.momentbox.core.media

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/** Tách decoder Android khỏi trạng thái phát để thử callback đến muộn trên JVM. */
internal interface VoiceEngine {
    var onPrepared: () -> Unit
    var onCompleted: () -> Unit
    var onError: (String) -> Unit
    val duration: Int
    val position: Int
    val isPlaying: Boolean
    fun prepare(url: String)
    fun start()
    fun pause()
    fun release()
    fun seekTo(position: Int)
    fun setSpeed(speed: Float)
}

internal interface VoiceProgressScheduler {
    fun schedule(task: Runnable)
    fun cancel(task: Runnable)
}

internal class AndroidVoiceProgressScheduler : VoiceProgressScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun schedule(task: Runnable) { handler.postDelayed(task, 50) }
    override fun cancel(task: Runnable) { handler.removeCallbacks(task) }
}

internal class AndroidVoiceEngine : VoiceEngine {
    private val player = MediaPlayer()
    override var onPrepared: () -> Unit = {}
    override var onCompleted: () -> Unit = {}
    override var onError: (String) -> Unit = {}
    override val duration get() = player.duration
    override val position get() = player.currentPosition
    override val isPlaying get() = player.isPlaying
    override fun prepare(url: String) {
        player.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA).build())
        player.setDataSource(url)
        player.setOnPreparedListener { onPrepared() }
        player.setOnCompletionListener { onCompleted() }
        player.setOnErrorListener { _, what, extra -> onError("Lỗi phát âm thanh ($what, $extra)"); true }
        player.prepareAsync()
    }
    override fun start() = player.start()
    override fun pause() = player.pause()
    override fun release() = player.release()
    override fun seekTo(position: Int) = player.seekTo(position)
    override fun setSpeed(speed: Float) { player.playbackParams = player.playbackParams.setSpeed(speed) }
}
