package com.ntq.training.momentbox.feature.chat

import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.ntq.training.momentbox.core.media.AndroidPlaybackAudio
import com.ntq.training.momentbox.core.media.AudioPlaybackKind
import com.ntq.training.momentbox.feature.recorder.RecordedAudio
import java.io.File
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class QuickRecordPhase { IDLE, PREPARING, RECORDING, STOPPING, ERROR }
data class QuickRecordState(
    val phase: QuickRecordPhase = QuickRecordPhase.IDLE,
    val elapsedMs: Long = 0,
    val cancelled: Boolean = false
)

class QuickChatRecorder(context: Context, private val result: (RecordedAudio) -> Boolean) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(QuickRecordState())
    val state = _state.asStateFlow()
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startAt = 0L
    @Volatile
    private var cancelRequested = false
    private var ticker: Job? = null
    private var closed = false
    private val amplitude = mutableListOf<Int>()
    private val audio = AndroidPlaybackAudio.get(context)
    fun start() {
        if (closed || _state.value.phase !in setOf(
                QuickRecordPhase.IDLE,
                QuickRecordPhase.ERROR
            )
        ) return
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = QuickRecordState(QuickRecordPhase.ERROR)
            return
        }
        if (!audio.acquire(
                this,
                { finish(true) },
                kind = AudioPlaybackKind.RECORDING
            )
        ) {
            _state.value = QuickRecordState(QuickRecordPhase.ERROR)
            return
        }
        cancelRequested = false
        _state.value = QuickRecordState(QuickRecordPhase.PREPARING)
        scope.launch {
            mutex.withLock {
                try {
                    val folder = File(context.filesDir, "chat-quick").apply { mkdirs() }
                    file =
                        File(folder, UUID.randomUUID().toString() + ".m4a")
                        amplitude.clear()
                    @Suppress("DEPRECATION") val engine =
                        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
                    recorder = engine
                    engine.setAudioSource(MediaRecorder.AudioSource.MIC)
                    engine.setOutputFormat(
                        MediaRecorder.OutputFormat.MPEG_4
                    )
                    engine.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    engine.setAudioSamplingRate(
                        44100
                    )
                    engine.setAudioEncodingBitRate(128000)
                    engine.setOutputFile(file!!.absolutePath)
                    engine.setOnErrorListener { _, _, _ ->
                        finish(
                            true
                        )
                    }
                    engine.prepare()
                    if (cancelRequested || closed) {
                        cleanup()
                        if (!closed) _state.value = QuickRecordState()
                        return@withLock
                    }
                    engine.start()
                    startAt = SystemClock.elapsedRealtime()
                    _state.value =
                        QuickRecordState(QuickRecordPhase.RECORDING)
                    ticker = scope.launch {
                        while (isActive) {
                            delay(100)
                            mutex.withLock {
                                if (recorder === engine && _state.value.phase == QuickRecordPhase.RECORDING) {
                                    val elapsed = SystemClock.elapsedRealtime() - startAt
                                    amplitude += (runCatching { engine.maxAmplitude }.getOrDefault(0) / 327.67f).toInt()
                                        .coerceIn(0, 100)
                                    _state.value = _state.value.copy(elapsedMs = elapsed)
                                    if (elapsed >= 300_000) finish(false)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    cleanup()
                    _state.value = QuickRecordState(QuickRecordPhase.ERROR)
                }
            }
        }
    }

    fun finish(cancel: Boolean) {
        if (cancel) cancelRequested = true
        if (_state.value.phase in setOf(QuickRecordPhase.IDLE, QuickRecordPhase.ERROR)) return
        scope.launch {
            mutex.withLock {
                val engine = recorder ?: return@withLock
                _state.value = _state.value.copy(
                    phase = QuickRecordPhase.STOPPING,
                    cancelled = cancelRequested
                )
                ticker?.cancel()
                ticker = null
                val output = file
                try {
                    engine.stop()
                    engine.release()
                    recorder = null
                    val cancelled = cancelRequested || closed
                    if (!cancelled && output != null && output.length() > 0) {
                        val metadata = MediaMetadataRetriever()
                        val duration = try {
                            metadata.setDataSource(output.absolutePath)
                            metadata.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_DURATION
                            )?.toLongOrNull() ?: 0L
                        } finally {
                            metadata.release()
                        }
                        if (duration > 0) {
                            val bars = amplitude.toList().let { values ->
                                if (values.size <= 60) values else List(60) { i ->
                                    values.subList(
                                        i * values.size / 60,
                                        (i + 1) * values.size / 60
                                    ).maxOrNull() ?: 0
                                }
                            }
                            val claimed = withContext(Dispatchers.Main) {
                                if (!cancelRequested && !closed) result(
                                    RecordedAudio(
                                        Uri.fromFile(
                                            output
                                        ).toString(), duration, bars, output.nameWithoutExtension
                                    )
                                ) else false
                            }
                            // Sau handoff, caller phải copy rồi xóa file quick thuộc app; nếu không nhận thì cleanup IO.
                            if (claimed) file = null
                        }
                    }
                } catch (_: Exception) { /* Nhả quá sớm có thể chưa có frame AAC; không phát result thành công. */
                } finally {
                    cleanup()
                    if (!closed) _state.value = QuickRecordState()
                }
            }
        }
    }

    private suspend fun cleanup() {
        ticker?.cancel()
        ticker = null
        runCatching { recorder?.release() }
        recorder = null
        file?.delete()
        file = null
        withContext(Dispatchers.Main) { audio.release(this@QuickChatRecorder) }
    }

    fun close() {
        closed = true
        cancelRequested = true
        scope.launch { mutex.withLock { cleanup() }
        scope.cancel() }
    }
}