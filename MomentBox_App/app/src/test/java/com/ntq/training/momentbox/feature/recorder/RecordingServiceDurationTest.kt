package com.ntq.training.momentbox.feature.recorder

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingServiceDurationTest {
    @Test
    fun formatDurationFormatsMinutesAndSecondsCleanly() {
        assertEquals("00:00", RecordingService.formatDuration(-500L))
        assertEquals("00:00", RecordingService.formatDuration(0L))
        assertEquals("00:05", RecordingService.formatDuration(5_900L))
        assertEquals("01:05", RecordingService.formatDuration(65_000L))
        assertEquals("05:00", RecordingService.formatDuration(300_000L))
    }
}
