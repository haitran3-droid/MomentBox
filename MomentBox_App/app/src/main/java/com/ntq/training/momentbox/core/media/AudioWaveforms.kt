package com.ntq.training.momentbox.core.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object AudioWaveforms {
    private val network = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS).build()
    private val cache = object : LinkedHashMap<String, List<Float>>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Float>>?) =
            size > 64
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
    }

    suspend fun read(context: Context, value: String): List<Float> = withContext(Dispatchers.IO) {
        val uri = Uri.parse(value)
        val key =
            value + if (uri.scheme == "file") ":" + File(checkNotNull(uri.path)).lastModified() else ""
        synchronized(cache) { cache[key] }?.let { return@withContext it }
        var temporary: File? = null
        try {
            val file = if (uri.scheme == "file") File(checkNotNull(uri.path)) else {
                require(uri.scheme in setOf("https", "http"))
                val result =
                    File.createTempFile("m5-wave-", ".audio", context.cacheDir); temporary = result
                val call = network.newCall(Request.Builder().url(value).build())
                // Child hủy Call ngay khi parent bị cancel, thay vì đợi blocking read timeout.
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.execute().use { response ->
                        check(response.isSuccessful)
                        checkNotNull(response.body).byteStream().use { input ->
                            result.outputStream().use { output ->
                                val buffer = ByteArray(8192);
                                var count = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive();
                                    val read =
                                        input.read(buffer); if (read < 0) break; count += read; check(
                                        count <= 50L * 1024 * 1024
                                    ); output.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                } finally {
                    watcher.cancelAndJoin()
                }
                result
            }
            val values = decode(file)
            synchronized(cache) { cache[key] = values }
            values
        } finally {
            temporary?.delete()
        }
    }

    private suspend fun decode(file: File): List<Float> {
        val extractor = MediaExtractor();
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            }
            extractor.selectTrack(track);
            val format = extractor.getTrackFormat(track)
            val duration =
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
            val envelope = PcmEnvelope(duration)
            val decoder =
                MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME))); codec =
                decoder
            decoder.configure(format, null, null, 0); decoder.start()
            var inputDone = false;
            var outputDone = false;
            val info = MediaCodec.BufferInfo();
            val start = SystemClock.elapsedRealtime()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            while (!outputDone) {
                currentCoroutineContext().ensureActive(); check(SystemClock.elapsedRealtime() - start < 20000)
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = checkNotNull(decoder.getInputBuffer(index));
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                index,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            ); inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                index,
                                0,
                                size,
                                extractor.sampleTime,
                                0
                            ); extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = decoder.outputFormat; sampleRate =
                        output.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels =
                        output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding =
                        output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val buffer = checkNotNull(decoder.getOutputBuffer(index)); buffer.position(
                            info.offset
                        ); buffer.limit(info.offset + info.size)
                        envelope.accept(
                            buffer,
                            info.presentationTimeUs,
                            sampleRate,
                            channels,
                            encoding
                        )
                    }
                    outputDone =
                        info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0; decoder.releaseOutputBuffer(
                        index,
                        false
                    )
                }
            }
            return envelope.values()
        } finally {
            runCatching { codec?.stop() }; codec?.release(); extractor.release()
        }
    }
}
