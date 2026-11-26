package com.ntq.training.momentbox.feature.journal

import com.ntq.training.momentbox.core.media.VoiceAttachmentProgress
import com.ntq.training.momentbox.feature.journal.view.AudioWaveformView
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAttachmentPlaybackTest {

    @Test
    fun waveformClampProgressClampsRangeAndRejectsNonFinite() {
        assertEquals(0f, AudioWaveformView.clampProgress(-0.25f), 0.0001f)
        assertEquals(0f, AudioWaveformView.clampProgress(0f), 0.0001f)
        assertEquals(0.42f, AudioWaveformView.clampProgress(0.42f), 0.0001f)
        assertEquals(1f, AudioWaveformView.clampProgress(1.5f), 0.0001f)
        assertEquals(0f, AudioWaveformView.clampProgress(Float.NaN), 0.0001f)
        assertEquals(0f, AudioWaveformView.clampProgress(Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun waveformIsBarPlayedTracksProgressAcrossBars() {
        // Progress 0: no bars played, even at x = 0.
        assertFalse(AudioWaveformView.isBarPlayed(barCenterX = 0f, totalWidth = 200f, progress = 0f))
        assertFalse(AudioWaveformView.isBarPlayed(barCenterX = 5f, totalWidth = 200f, progress = 0f))

        // Mid playback (50% of 200px = 100px): bars at/before 100px are played, bars after are unplayed.
        assertTrue(AudioWaveformView.isBarPlayed(barCenterX = 20f, totalWidth = 200f, progress = 0.5f))
        assertTrue(AudioWaveformView.isBarPlayed(barCenterX = 100f, totalWidth = 200f, progress = 0.5f))
        assertFalse(AudioWaveformView.isBarPlayed(barCenterX = 101f, totalWidth = 200f, progress = 0.5f))

        // Progress 1: all bars played.
        assertTrue(AudioWaveformView.isBarPlayed(barCenterX = 199f, totalWidth = 200f, progress = 1f))

        // Degenerate zero width never marks played.
        assertFalse(AudioWaveformView.isBarPlayed(barCenterX = 0f, totalWidth = 0f, progress = 0.5f))
    }

    @Test
    fun waveformTouchRatioNormalizesWithinBounds() {
        assertEquals(0f, AudioWaveformView.touchRatio(-10f, 200), 0.0001f)
        assertEquals(0.25f, AudioWaveformView.touchRatio(50f, 200), 0.0001f)
        assertEquals(1f, AudioWaveformView.touchRatio(250f, 200), 0.0001f)
        assertEquals(0f, AudioWaveformView.touchRatio(50f, 0), 0.0001f)
    }

    @Test
    fun displayStateShowsTotalDurationWhenIdleAndElapsedWhilePlayingOrPaused() {
        // Idle at position 0 -> shows total recording duration (23s) and 0 progress.
        val idle = VoiceAttachmentProgress.computeDisplayState(
            isPlaying = false,
            positionMs = 0L,
            playerDurationMs = 0L,
            metadataDurationMs = 23_000L,
        )
        assertEquals(0f, idle.progress, 0.0001f)
        assertEquals(23_000L, idle.displayedTimeMs)
        assertFalse(idle.isPlaying)

        // Playing at 0ms -> immediately switches display time to 0:00 instead of staying frozen at 0:23.
        val started = VoiceAttachmentProgress.computeDisplayState(
            isPlaying = true,
            positionMs = 0L,
            playerDurationMs = 23_000L,
            metadataDurationMs = 23_000L,
        )
        assertEquals(0f, started.progress, 0.0001f)
        assertEquals(0L, started.displayedTimeMs)
        assertTrue(started.isPlaying)

        // Playing mid-clip (11.5s / 23s) -> progress 0.5 and elapsed time 11.5s.
        val playing = VoiceAttachmentProgress.computeDisplayState(
            isPlaying = true,
            positionMs = 11_500L,
            playerDurationMs = 23_000L,
            metadataDurationMs = 23_000L,
        )
        assertEquals(0.5f, playing.progress, 0.0001f)
        assertEquals(11_500L, playing.displayedTimeMs)
        assertTrue(playing.isPlaying)

        // Paused mid-clip -> preserves paused progress and paused timestamp.
        val paused = VoiceAttachmentProgress.computeDisplayState(
            isPlaying = false,
            positionMs = 11_500L,
            playerDurationMs = 23_000L,
            metadataDurationMs = 23_000L,
        )
        assertEquals(0.5f, paused.progress, 0.0001f)
        assertEquals(11_500L, paused.displayedTimeMs)
        assertFalse(paused.isPlaying)
    }

    @Test
    fun displayStateClampsOvershootAndFallsBackToPlayerDurationWhenMetadataMissing() {
        val overshoot = VoiceAttachmentProgress.computeDisplayState(
            isPlaying = true,
            positionMs = 23_400L,
            playerDurationMs = 23_000L,
            metadataDurationMs = 23_000L,
        )
        assertEquals(1f, overshoot.progress, 0.0001f)
        assertEquals(23_000L, overshoot.displayedTimeMs)

        val fallback = VoiceAttachmentProgress.computeDisplayState(
            isPlaying = true,
            positionMs = 5_000L,
            playerDurationMs = 20_000L,
            metadataDurationMs = 0L,
        )
        assertEquals(0.25f, fallback.progress, 0.0001f)
        assertEquals(5_000L, fallback.displayedTimeMs)

        val idleFallback = VoiceAttachmentProgress.computeDisplayState(
            isPlaying = false,
            positionMs = 0L,
            playerDurationMs = 20_000L,
            metadataDurationMs = 0L,
        )
        assertEquals(0f, idleFallback.progress, 0.0001f)
        assertEquals(20_000L, idleFallback.displayedTimeMs)
    }

    @Test
    fun computeSeekPositionAndFormatDurationHandleBoundaryValues() {
        assertEquals(0, VoiceAttachmentProgress.computeSeekPositionMs(0f, 23_000L))
        assertEquals(11_500, VoiceAttachmentProgress.computeSeekPositionMs(0.5f, 23_000L))
        assertEquals(23_000, VoiceAttachmentProgress.computeSeekPositionMs(1.2f, 23_000L))
        assertEquals(0, VoiceAttachmentProgress.computeSeekPositionMs(0.5f, 0L))
        assertEquals(0, VoiceAttachmentProgress.computeSeekPositionMs(Float.NaN, 23_000L))

        assertEquals("0:00", VoiceAttachmentProgress.formatDuration(-500L, Locale.US))
        assertEquals("0:00", VoiceAttachmentProgress.formatDuration(0L, Locale.US))
        assertEquals("0:09", VoiceAttachmentProgress.formatDuration(9_900L, Locale.US))
        assertEquals("0:23", VoiceAttachmentProgress.formatDuration(23_000L, Locale.US))
        assertEquals("2:05", VoiceAttachmentProgress.formatDuration(125_000L, Locale.US))
    }
}
