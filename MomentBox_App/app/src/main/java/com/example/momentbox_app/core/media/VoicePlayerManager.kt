package com.example.momentbox_app.core.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoicePlaybackState(
    val postId: Long? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val currentPositionMs: Int = 0,
    val durationMs: Int = 0,
    val speed: Float = 1.0f,
    val errorMessage: String? = null
) {
    val progress: Float
        get() = if (durationMs > 0) currentPositionMs.toFloat() / durationMs.toFloat() else 0f
}

class VoicePlayerManager(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private var audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val _playbackState = MutableStateFlow(VoicePlaybackState())
    val playbackState: StateFlow<VoicePlaybackState> = _playbackState.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var currentPostId: Long? = null
    private var currentSpeed: Float = 1.0f
    private var expectedDurationMs: Int = 0

    private var currentGeneration: Long = 0L
    private var userIntendedPlay: Boolean = false

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    val current = player.currentPosition
                    val duration = if (player.duration > 0) player.duration else expectedDurationMs
                    _playbackState.value = VoicePlaybackState(
                        postId = currentPostId,
                        isPlaying = true,
                        isLoading = false,
                        currentPositionMs = current,
                        durationMs = duration,
                        speed = currentSpeed
                    )
                    handler.postDelayed(this, 50)
                }
            }
        }
    }

    /**
     * Bắt đầu phát hoặc tiếp tục phát voice note của bài viết.
     */
    fun playVoice(postId: Long, audioUrl: String, durationSec: Int = 0) {
        expectedDurationMs = durationSec * 1000
        if (currentPostId == postId && mediaPlayer != null) {
            resumeVoice()
            return
        }
        currentGeneration++
        val requestGen = currentGeneration
        userIntendedPlay = true

        stopVoiceInternal(resetState = false)
        currentPostId = postId
        currentSpeed = 1.0f
        _playbackState.value = VoicePlaybackState(
            postId = postId,
            isPlaying = false,
            isLoading = true,
            durationMs = expectedDurationMs,
            speed = currentSpeed
        )
        requestAudioFocus()
        var player: MediaPlayer? = null
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(audioUrl)
                setOnPreparedListener { mp ->
                    if (requestGen != currentGeneration || !userIntendedPlay || currentPostId != postId) {
                        try {
                            mp.stop()
                            mp.release()
                        } catch (_: Exception) {
                        }
                        return@setOnPreparedListener
                    }
                    applySpeed(currentSpeed)
                    mp.start()
                    val totalDuration = if (mp.duration > 0) mp.duration else expectedDurationMs
                    _playbackState.value = VoicePlaybackState(
                        postId = currentPostId,
                        isPlaying = true,
                        isLoading = false,
                        currentPositionMs = 0,
                        durationMs = totalDuration,
                        speed = currentSpeed
                    )
                    handler.post(updateProgressRunnable)
                }
                setOnCompletionListener {
                    onPlaybackCompleted()
                }
                setOnErrorListener { _, what, extra ->
                    if(requestGen == currentGeneration){
                        stopVoiceInternal(resetState = false)
                        _playbackState.value = VoicePlaybackState(
                            postId = postId,
                            isPlaying = false,
                            isLoading = false,
                            errorMessage = "Lỗi phát âm thanh ($what, $extra)"
                        )
                    }
                    true
                }
                prepareAsync()
            }
            mediaPlayer = player
        }catch (e: Exception){
            try{
                player?.release()
            }catch (_: Exception){}
            mediaPlayer = null
            _playbackState.value = VoicePlaybackState(
                postId = postId,
                isPlaying = false,
                isLoading = false,
                errorMessage = e.message ?: "Không thể mở tệp âm thanh"
            )
        }
    }

    /**Tạm dừng phát âm thanh*/
    fun pauseVoice() {
        userIntendedPlay = false
        handler.removeCallbacks(updateProgressRunnable)
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.pause()
                }
            } catch (_: Exception) {
            }
        }
        _playbackState.value = _playbackState.value.copy(isPlaying = false, isLoading = false)
    }

    /**
     * Tiếp tục phát âm thanh đang tạm dừng.
     */
    fun resumeVoice() {
        userIntendedPlay = true
        requestAudioFocus()
        mediaPlayer?.let { player ->
            try {
                player.start()
                handler.post(updateProgressRunnable)
                _playbackState.value =
                    _playbackState.value.copy(isPlaying = true, isLoading = false)
            } catch (e: Exception) {
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    isLoading = false,
                    errorMessage = e.message ?: "Lỗi tiếp tục phát"
                )
            }
        } ?: run {
            _playbackState.value = _playbackState.value.copy(isPlaying = false, isLoading = false)
        }
    }

    /**
     * Tua âm thanh đến vị trí tỉ lệ phần trăm [progressRatio] (0.0f .. 1.0f).
     */
    fun seekTo(postId: Long, progressRatio: Float) {
        if (currentPostId != postId) return
        val duration = _playbackState.value.durationMs
        if (duration <= 0) return
        val targetMs = (duration * progressRatio.coerceIn(0f, 1f)).toInt()

        mediaPlayer?.let { player ->
            try {
                player.seekTo(targetMs)
            } catch (_: Exception) {
            }
        }
        _playbackState.value = _playbackState.value.copy(currentPositionMs = targetMs)
    }

    /**
     * Chuyển đổi vòng lặp tốc độ phát: 1.0x -> 1.5x -> 2.0x -> 1.0x.
     */
    fun cycleSpeed(postId: Long): Float {
        if (currentPostId != postId) return 1.0f
        val nextSpeed = when (currentSpeed) {
            1.0f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        currentSpeed = nextSpeed
        applySpeed(nextSpeed)
        _playbackState.value = _playbackState.value.copy(speed = nextSpeed)
        return nextSpeed
    }

    private fun applySpeed(speed: Float) {
        mediaPlayer?.let { player ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val params = player.playbackParams
                    params.speed = speed
                    player.playbackParams = params
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun onPlaybackCompleted() {
        handler.removeCallbacks(updateProgressRunnable)
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            isLoading = false,
            currentPositionMs = 0
        )
        abandonAudioFocus()
    }

    /**
     * Dừng phát hoàn toàn và giải phóng phiên hiện tại.
     */
    fun stopVoice() {
        currentGeneration++
        userIntendedPlay = false
        stopVoiceInternal(resetState = true)
    }

    private fun stopVoiceInternal(resetState: Boolean) {
        handler.removeCallbacks(updateProgressRunnable)
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) player.stop()
                player.release()
            } catch (e: Exception) {
            }
        }
        mediaPlayer = null
        currentPostId = null
        if (resetState) {
            _playbackState.value = VoicePlaybackState()
            abandonAudioFocus()
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        pauseVoice()
                    }
                }
                .build()
            audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        pauseVoice()
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    /**
     * Dọn dẹp tài nguyên khi Activity/Fragment bị hủy.
     */
    fun release() {
        stopVoice()
    }
}