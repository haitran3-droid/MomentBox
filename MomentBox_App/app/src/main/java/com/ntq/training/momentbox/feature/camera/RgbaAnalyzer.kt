package com.ntq.training.momentbox.feature.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteOrder

class RgbaAnalyzer(private val overlay: CameraOverlayView, private val front: () -> Boolean) :
    ImageAnalysis.Analyzer, AutoCloseable {
    private var pixels = IntArray(0)

    private var bitmap: Bitmap? = null

    @Synchronized
    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]

            val buffer = plane.buffer

            val rect = image.cropRect
            val w = rect.width()

            val h = rect.height()
            if (w <= 0 || h <= 0) return
            if (bitmap?.width != w || bitmap?.height != h) {
                pixels = IntArray(w * h)
                bitmap?.recycle()
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }
            // RGBA packed thông thường chỉ cần một lần đọc Int mỗi pixel, nhánh stride lạ vẫn đúng
            // byte.
            val packed =
                if (plane.pixelStride == 4 && plane.rowStride % 4 == 0)
                    buffer
                        .duplicate()
                        .apply { position(0) }
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asIntBuffer()
                else null
            for (y in 0 until h) for (x in 0 until w) {
                val p = (y + rect.top) * plane.rowStride + (x + rect.left) * plane.pixelStride
                pixels[y * w + x] =
                    if (packed != null) {
                        val v = packed.get(p / 4)
                        (v and 0xFF000000.toInt()) or
                            ((v and 255) shl 16) or
                            (v and 0xFF00) or
                            ((v ushr 16) and 255)
                    } else {
                        val red = buffer.get(p).toInt() and 255

                        val green = buffer.get(p + 1).toInt() and 255

                        val blue = buffer.get(p + 2).toInt() and 255

                        val alpha = buffer.get(p + 3).toInt() and 255
                        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                    }
            }
            bitmap!!.setPixels(pixels, 0, w, 0, 0, w, h)
            overlay.accept(bitmap!!, image.imageInfo.rotationDegrees, front())
        } finally {
            image.close()
        }
    }

    @Synchronized
    override fun close() {
        bitmap?.recycle()
        bitmap = null
        pixels = IntArray(0)
    }
}
