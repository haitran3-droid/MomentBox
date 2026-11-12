package com.ntq.training.momentbox.feature.profile

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class ProfileTabIndicator(private val widthPixels: Float, color: Int) : Drawable() {
    override fun getIntrinsicHeight() = (widthPixels * 3f / 56f).toInt().coerceAtLeast(1)
    override fun getIntrinsicWidth() = widthPixels.toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    override fun draw(canvas: Canvas) {
        val half = minOf(widthPixels, bounds.width().toFloat()) / 2
        canvas.drawRoundRect(
            bounds.exactCenterX() - half,
            bounds.top.toFloat(),
            bounds.exactCenterX() + half,
            bounds.bottom.toFloat(),
            bounds.height().toFloat(),
            bounds.height().toFloat(),
            paint
        )
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(filter: ColorFilter?) {
        paint.colorFilter = filter
        invalidateSelf()
    }

    @Deprecated("Drawable opacity is unused")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}