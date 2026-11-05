package com.ntq.training.momentbox.core.media

import android.content.Context
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
        get() = if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f
}

/** Một player cho Feed; engine và bộ hẹn giờ có thể thay thế trong unit test. */
class VoicePlayerManager internal constructor(
    private val createEngine: () -> VoiceEngine,
    private val audio: PlaybackAudio,
    private val scheduler: VoiceProgressScheduler
) {
    constructor(context: Context) : this({ AndroidVoiceEngine() }, AndroidPlaybackAudio.get(context), AndroidVoiceProgressScheduler())

    private val _playbackState = MutableStateFlow(VoicePlaybackState())
    val playbackState: StateFlow<VoicePlaybackState> = _playbackState.asStateFlow()
    private var engine: VoiceEngine? = null
    private var currentPostId: Long? = null
    private var audioUrl: String = ""
    private var prepared = false
    private var generation = 0L
    private var userIntendedPlay = false
    private var currentSpeed = 1f
    private var expectedDurationMs = 0

    private val updateProgress = object : Runnable {
        override fun run() {
            val player = engine ?: return
            runCatching {
                if (prepared && player.isPlaying) {
                    _playbackState.value = _playbackState.value.copy(currentPositionMs = player.position)
                    scheduler.schedule(this)
                }
            }.onFailure { fail(it.message ?: "Lỗi đọc tiến trình âm thanh") }
        }
    }

    fun playVoice(postId: Long, audioUrl: String, durationSec: Int = 0) {
        if (currentPostId == postId && engine != null) {
            if (prepared) resumeVoice()
            return
        }
        generation++
        val request = generation
        disposeEngine()
        currentPostId = postId
        this.audioUrl = audioUrl
        expectedDurationMs = durationSec * 1000
        currentSpeed = 1f
        userIntendedPlay = true
        _playbackState.value = VoicePlaybackState(postId = postId, isLoading = true, durationMs = expectedDurationMs)
        if (!audio.acquire(this, { pauseVoice() }, kind = AudioPlaybackKind.VOICE)) {
            fail("Không thể giành quyền phát âm thanh")
            return
        }
        try {
            // Giữ reference ngay sau cấp phát, trước mọi thao tác có thể ném lỗi setup.
            val candidate = createEngine()
            engine = candidate
            run {
                candidate.onPrepared = {
                    if (request == generation && engine === candidate && userIntendedPlay) {
                        prepared = true
                        runCatching {
                            candidate.start()
                            candidate.setSpeed(currentSpeed)
                            _playbackState.value = _playbackState.value.copy(isPlaying = true, isLoading = false,
                                errorMessage = null, durationMs = candidate.duration.takeIf { it > 0 } ?: expectedDurationMs)
                            scheduler.schedule(updateProgress)
                        }.onFailure { fail(it.message ?: "Lỗi phát âm thanh") }
                    }
                }
                candidate.onError = { message ->
                    if (request == generation && engine === candidate) fail(message)
                }
                candidate.onCompleted = {
                    if (request == generation && engine === candidate) {
                        userIntendedPlay = false
                        disposeEngine()
                        audio.release(this)
                        _playbackState.value = _playbackState.value.copy(isPlaying = false, isLoading = false, currentPositionMs = 0)
                    }
                }
                candidate.prepare(audioUrl)
            }
        } catch (error: Exception) {
            fail(error.message ?: "Không thể mở tệp âm thanh")
        }
    }

    fun pauseVoice() {
        userIntendedPlay = false
        scheduler.cancel(updateProgress)
        // MediaPlayer đang preparing không thể pause. Hủy phiên và tạo mới khi người dùng bấm lại.
        if (!prepared) {
            generation++
            disposeEngine()
        } else {
            runCatching { if (engine?.isPlaying == true) engine?.pause() }
                .onFailure { fail(it.message ?: "Lỗi tạm dừng âm thanh") }
        }
        audio.release(this)
        _playbackState.value = _playbackState.value.copy(isPlaying = false, isLoading = false)
    }

    fun resumeVoice() {
        val postId = currentPostId ?: return
        if (engine == null) {
            playVoice(postId, audioUrl, expectedDurationMs / 1000)
            return
        }
        if (!prepared) return
        userIntendedPlay = true
        if (!audio.acquire(this, { pauseVoice() }, kind = AudioPlaybackKind.VOICE)) {
            fail("Không thể giành quyền phát âm thanh")
            return
        }
        runCatching {
            engine?.start()
            engine?.setSpeed(currentSpeed)
            _playbackState.value = _playbackState.value.copy(isPlaying = true, isLoading = false, errorMessage = null)
            scheduler.schedule(updateProgress)
        }.onFailure { fail(it.message ?: "Lỗi tiếp tục phát") }
    }

    fun seekTo(postId: Long, progressRatio: Float) {
        if (currentPostId != postId || !prepared) return
        val position = (_playbackState.value.durationMs * progressRatio.coerceIn(0f, 1f)).toInt()
        runCatching { engine?.seekTo(position) }
        _playbackState.value = _playbackState.value.copy(currentPositionMs = position)
    }

    fun cycleSpeed(postId: Long): Float {
        if (currentPostId != postId) return 1f
        currentSpeed = when (currentSpeed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        // PlaybackParams có thể tự start MediaPlayer; chỉ áp dụng lúc đang phát.
        if (_playbackState.value.isPlaying) runCatching { engine?.setSpeed(currentSpeed) }
        _playbackState.value = _playbackState.value.copy(speed = currentSpeed)
        return currentSpeed
    }

    private fun disposeEngine() {
        scheduler.cancel(updateProgress)
        val old = engine
        engine = null
        prepared = false
        runCatching { old?.release() }
    }

    private fun fail(message: String) {
        generation++
        userIntendedPlay = false
        disposeEngine()
        audio.release(this)
        _playbackState.value = _playbackState.value.copy(isPlaying = false, isLoading = false, errorMessage = message)
    }

    fun stopVoice() {
        generation++
        userIntendedPlay = false
        disposeEngine()
        currentPostId = null
        audioUrl = ""
        audio.release(this)
        _playbackState.value = VoicePlaybackState()
    }
    fun release() = stopVoice()
}