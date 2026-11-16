package com.ntq.training.momentbox.data.repository

import android.media.MediaMetadataRetriever
import java.io.RandomAccessFile
import java.lang.Short as JavaShort
import kotlin.math.abs

internal object JournalAudioMetadataReader {
    fun read(path: String): JournalAudioMetadata {
        val retriever = MediaMetadataRetriever()
        val duration = try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally { retriever.release() }
        require(duration > 0) { "Unsupported or empty audio" }
        val peaks = mutableListOf<Int>()
        RandomAccessFile(path, "r").use { file ->
            fun tag(): String {
                val bytes = ByteArray(4)
                file.readFully(bytes)
                return String(bytes, Charsets.US_ASCII)
            }
            fun intLE() = Integer.reverseBytes(file.readInt()).toLong() and 0xffffffffL
            if (file.length() < 12 || tag() != "RIFF") return@use
            intLE()
            if (tag() != "WAVE") return@use
            var pcm16 = false
            while (file.filePointer + 8 <= file.length()) {
                val id = tag()
                val size = intLE()
                val start = file.filePointer
                if (start + size > file.length()) break
                if (id == "fmt " && size >= 16) {
                    val format = JavaShort.reverseBytes(file.readShort()).toInt()
                    file.skipBytes(12)
                    val bits = JavaShort.reverseBytes(file.readShort()).toInt()
                    pcm16 = format == 1 && bits == 16
                }
                if (id == "data" && pcm16) {
                    val samples = size / 2
                    for (bucket in 0 until 40) {
                        var max = 0
                        val end = samples * (bucket + 1) / 40
                        var sample = samples * bucket / 40
                        file.seek(start + sample * 2)
                        while (sample < end) {
                            max = maxOf(max, abs(JavaShort.reverseBytes(file.readShort()).toInt()))
                            sample++
                        }
                        peaks += (max * 100 / 32768).coerceIn(0, 100)
                    }
                    break
                }
                file.seek(start + size + size % 2)
            }
        }
        return JournalAudioMetadata(duration, peaks)
    }
}
