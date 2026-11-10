package com.ntq.training.momentbox.core.media

import android.graphics.Bitmap

class BitmapRenderSnapshot(source: Bitmap, private val renderer: (Bitmap) -> Bitmap) :
    () -> Bitmap, AutoCloseable {
    private val lock = Any()

    private var source: Bitmap? = source

    private var output: Bitmap? = null

    private var closed = false

    fun render() {
        val input =
            synchronized(lock) {
                check(!closed)
                checkNotNull(source).also { source = null }
            }
        try {
            val image = renderer(input)
            synchronized(lock) { if (closed) image.recycle() else output = image }
        } finally {
            input.recycle()
        }
    }

    fun take(): Bitmap =
        synchronized(lock) {
            check(!closed)
            checkNotNull(output).also { output = null }
        }

    override fun invoke(): Bitmap {
        render()
        return take()
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            source?.recycle()
            source = null
            output?.recycle()
            output = null
        }
    }
}
