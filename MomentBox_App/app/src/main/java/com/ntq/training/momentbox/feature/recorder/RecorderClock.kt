package com.ntq.training.momentbox.feature.recorder

class RecorderClock {
    private var active = 0L

    private var started: Long? = null

    fun start(now: Long) {
        active = 0
        started = now
    }

    fun pause(now: Long) {
        started?.let {
            active += (now - it).coerceAtLeast(0)
            started = null
        }
    }

    fun resume(now: Long) {
        if (started == null) started = now
    }

    fun elapsed(now: Long) =
        (active + (started?.let { (now - it).coerceAtLeast(0) } ?: 0)).coerceAtMost(300_000)

    fun atLimit(now: Long) = elapsed(now) >= 300_000
}

enum class RecordPhase {
    IDLE,
    PREPARING,
    RECORDING,
    PAUSED,
    STOPPING,
    SAVED,
    ERROR,
}

data class RecordingSnapshot(
    val session: String = "",
    val phase: RecordPhase = RecordPhase.IDLE,
    val elapsedMs: Long = 0,
    val waveform: List<Int> = emptyList(),
    val uri: String? = null,
    val error: Boolean = false,
    val input: String? = null,
)
