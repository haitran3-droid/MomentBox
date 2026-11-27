package com.ntq.training.momentbox.core.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmEnvelopeTest {

    @Test
    fun quietVoiceRecordingNormalizesPeaksToVisibleWaveformRange() {
        val envelope = PcmEnvelope(durationUs = 4_000_000L, bars = 4)
        // Simulate a quiet indoor voice recording whose linear peaks are all below 10% full scale.
        feedBar(envelope, startUs = 0L, amplitude = 0.004f)
        feedBar(envelope, startUs = 1_000_000L, amplitude = 0.02f)
        feedBar(envelope, startUs = 2_000_000L, amplitude = 0.08f)
        feedBar(envelope, startUs = 3_000_000L, amplitude = 0.04f)

        val values = envelope.values()
        assertEquals(4, values.size)
        assertTrue("Expected loudest speech bar to normalize near 1.0, was ${values[2]}", values[2] >= 0.95f)
        assertTrue("Expected mid speech bar to be clearly visible, was ${values[3]}", values[3] in 0.45f..0.85f)
        assertTrue("Expected softer bar to stay below mid bar (${values[1]} vs ${values[3]})", values[1] < values[3])
        assertTrue("Expected quiet pause bar to stay low, was ${values[0]}", values[0] < 0.25f)
    }

    @Test
    fun digitalSilenceStaysFlatWithoutAmplifyingZero() {
        val envelope = PcmEnvelope(durationUs = 1_000_000L, bars = 4)
        feedBar(envelope, startUs = 0L, amplitude = 0f)

        val values = envelope.values()
        assertEquals(listOf(0f, 0f, 0f, 0f), values)
    }

    @Test
    fun veryQuietRoomNoiseRespectsMinimumScaleFloor() {
        val envelope = PcmEnvelope(durationUs = 2_000_000L, bars = 2)
        feedBar(envelope, startUs = 0L, amplitude = 0.005f)
        feedBar(envelope, startUs = 1_000_000L, amplitude = 0.002f)

        val values = envelope.values()
        assertTrue("Expected subtle room noise not to blow up to full height, was ${values[0]}", values[0] < 0.55f)
        assertTrue("Expected relative contour to remain preserved", values[0] > values[1])
    }

    private fun feedBar(envelope: PcmEnvelope, startUs: Long, amplitude: Float) {
        val sample = (amplitude * 32767f).roundToInt().toShort()
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        repeat(4) { buffer.putShort(sample) }
        buffer.flip()
        envelope.accept(
            pcm = buffer,
            presentationTimeUs = startUs,
            sampleRate = 16000,
            channels = 1,
            encoding = 2
        )
    }
}
