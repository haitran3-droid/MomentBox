package com.ntq.training.momentbox.feature.profile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class QuarterBlurTransformation(private val sigmaPx: Int = 5, private val sampling: Int = 4) :
    BitmapTransformation() {
        init {
            require(sigmaPx in 1..128)
            require(sampling in 1..16)
        }

        public override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        val width = max(1, toTransform.width / sampling)
        val height = max(1, toTransform.height / sampling)
        val result = pool.get(width, height, Bitmap.Config.ARGB_8888)
        try {
            result.density =
                toTransform.density
                result.setHasAlpha(true)
                result.eraseColor(Color.TRANSPARENT)
            Canvas(result).drawBitmap(
                toTransform,
                null,
                Rect(0, 0, width, height),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            val pixels = IntArray(width * height)
            val scratch = IntArray(pixels.size)
            result.getPixels(pixels, 0, width, 0, 0, width, height)
            // Tính màu premultiplied để vùng alpha trong suốt không sinh viền màu khi trộn.
            for (index in pixels.indices) {
                val c = pixels[index]
                val a = Color.alpha(c)
                pixels[index] = Color.argb(
                    a,
                    Color.red(c) * a / 255,
                    Color.green(c) * a / 255,
                    Color.blue(c) * a / 255
                )
            }
            val radius = max(1, (sigmaPx / (sampling * sqrt(2f))).roundToInt())
            repeat(3) {
                box(pixels, scratch, width, height, radius, true)
                box(
                scratch,
                pixels,
                width,
                height,
                radius,
                false
            )
            }
            for (index in pixels.indices) {
                val c = pixels[index]
                val a = Color.alpha(c)
                pixels[index] = if (a == 0) Color.TRANSPARENT else Color.argb(
                    a,
                    ((Color.red(c) * 255 + a / 2) / a).coerceAtMost(255),
                    ((Color.green(c) * 255 + a / 2) / a).coerceAtMost(255),
                    ((Color.blue(c) * 255 + a / 2) / a).coerceAtMost(255)
                )
            }
            result.setPixels(pixels, 0, width, 0, 0, width, height)
            return result
        } catch (failure: Throwable) {
            pool.put(result)
            throw failure
        }
    }

        private fun box(
        input: IntArray,
        output: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean
    ) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val count = radius * 2 + 1
        for (line in 0 until lines) {
            fun index(position: Int) = if (horizontal) line * width + position.coerceIn(
                0,
                length - 1
            ) else position.coerceIn(0, length - 1) * width + line

            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (offset in -radius..radius) {
                val c =
                    input[index(offset)]
                    a += Color.alpha(c)
                    r += Color.red(c)
                    g += Color.green(c)
                    b += Color.blue(
                    c
                )
            }
            for (position in 0 until length) {
                output[index(position)] = Color.argb(a / count, r / count, g / count, b / count)
                val before = input[index(position - radius)]
                val after = input[index(position + radius + 1)]
                a += Color.alpha(after) - Color.alpha(before)
                r += Color.red(after) - Color.red(
                    before
                )
                g += Color.green(after) - Color.green(before)
                b += Color.blue(after) - Color.blue(
                    before
                )
            }
        }
    }

        override fun equals(other: Any?) =
        other is QuarterBlurTransformation && sigmaPx == other.sigmaPx && sampling == other.sampling

        override fun hashCode() = 31 * (31 * javaClass.name.hashCode() + sigmaPx) + sampling
        override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        // Version có trong key để đổi thuật toán sau này không đọc nhầm ảnh cache cũ.
            messageDigest.update("MomentBox.QuarterBlur.v1".toByteArray(Charsets.UTF_8))
            messageDigest.update(ByteBuffer.allocate(8).putInt(sigmaPx).putInt(sampling).array())
        }
    }