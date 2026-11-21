package com.ntq.training.momentbox.core.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap

object MediaImageModel {

    private const val DEFAULT_BITMAP_MAX_SIZE_PX = 256

    fun resolve(context: Context, url: String?): Any? {
        val prefix = "android.resource://${context.packageName}/"
        if (url == null || !url.startsWith(prefix)) return url
        val id = url.removePrefix(prefix).toIntOrNull() ?: return url
        return try {
            if (context.resources.getResourceTypeName(id) !in setOf("drawable", "mipmap")) {
                url
            } else {
                ContextCompat.getDrawable(context, id) ?: url
            }
        } catch (_: Resources.NotFoundException) {
            url
        }
    }

    /**
     * Resolves a media model for Glide `asBitmap()` pipelines (e.g. blur transforms).
     * Any Android [Drawable] (including `VectorDrawable`) is rendered onto a bounded
     * software [Bitmap] so Glide never fails with a missing `VectorDrawable -> Bitmap` path.
     */
    fun resolveForBitmap(
        context: Context,
        url: String?,
        maxSizePx: Int = DEFAULT_BITMAP_MAX_SIZE_PX
    ): Any? {
        val resolved = resolve(context, url) ?: return null
        return if (resolved is Drawable) {
            drawableToBitmap(resolved, maxSizePx)
        } else {
            resolved
        }
    }

    fun drawableToBitmap(
        drawable: Drawable,
        maxSizePx: Int = DEFAULT_BITMAP_MAX_SIZE_PX
    ): Bitmap {
        val safeMax = maxSizePx.coerceAtLeast(1)
        val target = drawable.constantState?.newDrawable()?.mutate() ?: drawable.mutate()
        val intrinsicWidth = target.intrinsicWidth.takeIf { it > 0 } ?: safeMax
        val intrinsicHeight = target.intrinsicHeight.takeIf { it > 0 } ?: safeMax
        val scale = minOf(
            1f,
            safeMax.toFloat() / maxOf(intrinsicWidth, intrinsicHeight).toFloat()
        )
        val width = (intrinsicWidth * scale).toInt().coerceIn(1, safeMax)
        val height = (intrinsicHeight * scale).toInt().coerceIn(1, safeMax)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val previousBounds = Rect(target.bounds)
        target.setBounds(0, 0, width, height)
        target.draw(canvas)
        target.bounds = previousBounds
        return bitmap
    }
}
