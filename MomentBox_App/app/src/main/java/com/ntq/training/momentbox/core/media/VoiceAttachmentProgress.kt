package com.ntq.training.momentbox.core.media

import java.util.Locale

internal data class VoiceAttachmentDisplayState(
    val progress: Float,
    val displayedTimeMs: Long,
    val isPlaying: Boolean,
)

/**
 * Pure playback progress & duration formatting logic shared by compact audio attachment bars
 * (S08 Journal Editor and S15 Compose).
 */
internal object VoiceAttachmentProgress {
    const val POLL_INTERVAL_MS = 50L

    fun resolveDurationMs(playerDurationMs: Long, metadataDurationMs: Long): Long =
        when {
            playerDurationMs > 0L -> playerDurationMs
            metadataDurationMs > 0L -> metadataDurationMs
            else -> 0L
        }

    fun computeDisplayState(
        isPlaying: Boolean,
        positionMs: Long,
        playerDurationMs: Long,
        metadataDurationMs: Long,
    ): VoiceAttachmentDisplayState {
        val progressDurationMs = resolveDurationMs(playerDurationMs, metadataDurationMs)
        val idleDurationMs =
            if (metadataDurationMs > 0L) metadataDurationMs else playerDurationMs.coerceAtLeast(0L)
        val safePositionMs = positionMs.coerceAtLeast(0L)
        val isActive = isPlaying || safePositionMs > 0L
        if (!isActive) {
            return VoiceAttachmentDisplayState(
                progress = 0f,
                displayedTimeMs = idleDurationMs,
                isPlaying = false,
            )
        }
        val clampedPositionMs =
            if (progressDurationMs > 0L) safePositionMs.coerceAtMost(progressDurationMs)
            else safePositionMs
        val progress =
            if (progressDurationMs > 0L) {
                (clampedPositionMs.toFloat() / progressDurationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        return VoiceAttachmentDisplayState(
            progress = progress,
            displayedTimeMs = clampedPositionMs,
            isPlaying = isPlaying,
        )
    }

    fun computeSeekPositionMs(ratio: Float, durationMs: Long): Int {
        if (durationMs <= 0L || !ratio.isFinite()) return 0
        return (durationMs * ratio.coerceIn(0f, 1f)).toInt()
    }

    fun formatDuration(durationMs: Long, locale: Locale = Locale.getDefault()): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(locale, "%d:%02d", minutes, seconds)
    }
}
