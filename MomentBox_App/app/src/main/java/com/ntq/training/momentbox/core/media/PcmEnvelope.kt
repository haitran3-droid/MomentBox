package com.ntq.training.momentbox.core.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

internal class PcmEnvelope(private val durationUs: Long, private val bars: Int = 30) {
    private val peaks = FloatArray(bars)

    init {
        require(durationUs in 1..300_000_000 && bars in 1..120)
    }

    fun accept(
        pcm: ByteBuffer,
        presentationTimeUs: Long,
        sampleRate: Int,
        channels: Int,
        encoding: Int
    ) {
        require(sampleRate > 0 && channels in 1..8 && encoding in setOf(2, 3, 4))
        val input = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bytes = when (encoding) {
            4 -> 4
            3 -> 1
            else -> 2
        }
        var frame = 0L
        while (input.remaining() >= bytes * channels) {
            var amplitude = 0f
            repeat(channels) {
                val value = when (encoding) {
                    4 -> input.float
                    3 -> ((input.get()
                        .toInt() and 255) - 128) / 128f
                        else -> input.short / 32768f
                }
                if (value.isFinite()) amplitude = max(amplitude, abs(value).coerceIn(0f, 1f))
            }
            val timestamp = (presentationTimeUs + frame * 1_000_000 / sampleRate).coerceAtLeast(0)
            val index = (timestamp * bars / durationUs).coerceIn(0, bars - 1L).toInt()
            peaks[index] = max(peaks[index], amplitude)
            frame++
        }
    }

    fun values(): List<Float> = peaks.toList()
}