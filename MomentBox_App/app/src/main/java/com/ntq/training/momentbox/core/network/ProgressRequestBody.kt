package com.ntq.training.momentbox.core.network

import java.io.File
import java.io.IOException
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

class ProgressRequestBody(
    private val file: File,
    private val mime: MediaType?,
    private val cancelled: () -> Boolean,
    private val progress: (Long) -> Unit,
) : RequestBody() {
    override fun contentType() = mime

    override fun contentLength() = file.length()

    override fun writeTo(sink: BufferedSink) {
        var sent = 0L

        val buffer = ByteArray(8192)
        file.inputStream().use { input ->
            while (true) {
                if (cancelled()) throw IOException("Upload cancelled")

                val size = input.read(buffer)
                if (size < 0) break
                sink.write(buffer, 0, size)
                sent += size
                progress(sent)
            }
        }
    }
}
