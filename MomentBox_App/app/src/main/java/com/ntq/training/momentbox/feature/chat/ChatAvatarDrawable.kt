package com.ntq.training.momentbox.feature.chat

import java.util.Locale
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable

class ChatAvatarDrawable(name: String) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letters =
        name.split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }
            .uppercase(Locale.getDefault())

    override fun draw(canvas: Canvas) {
        val size = minOf(bounds.width(), bounds.height()).toFloat(); paint.color =
            0xFFECE9FF.toInt()
        canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), size / 2, paint)
        paint.color = 0xFF5B4BDB.toInt(); paint.textSize = size * .34f; paint.textAlign =
            Paint.Align.CENTER; paint.typeface =
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        canvas.drawText(
            letters,
            bounds.exactCenterX(),
            bounds.exactCenterY() - (paint.ascent() + paint.descent()) / 2,
            paint
        )
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Drawable opacity compatibility")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}