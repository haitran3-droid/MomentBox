package com.ntq.training.momentbox.core.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StaticPcmSound(context: Context, asset: String) : AutoCloseable {
    private var track: AudioTrack? = null

    init {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"
        )
        var rate = 0

        var channels = 0

        var bits = 0

        var data: ByteArray? = null

        var offset = 12
        while (offset + 8 <= bytes.size) {
            val length = buffer.getInt(offset + 4)
            require(length >= 0 && offset + 8L + length <= bytes.size)
            when (String(bytes, offset, 4, Charsets.US_ASCII)) {
                "fmt " -> {
                    require(buffer.getShort(offset + 8).toInt() == 1)
                    channels = buffer.getShort(offset + 10).toInt()
                    rate = buffer.getInt(offset + 12)
                    bits = buffer.getShort(offset + 22).toInt()
                }

                "data" -> data = bytes.copyOfRange(offset + 8, offset + 8 + length)
            }
            offset += 8 + length + (length % 2)
        }
        require(rate > 0 && channels in 1..2 && bits == 16)
        val pcm = requireNotNull(data)
        track =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(
                            if (channels == 1) AudioFormat.CHANNEL_OUT_MONO
                            else AudioFormat.CHANNEL_OUT_STEREO
                        )
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size)
                .build()
                .also { require(it.write(pcm, 0, pcm.size) == pcm.size) }
    }

    fun play() {
        track?.let {
            it.stop()
            it.reloadStaticData()
            it.play()
        }
    }

    override fun close() {
        track?.release()
        track = null
    }
}
