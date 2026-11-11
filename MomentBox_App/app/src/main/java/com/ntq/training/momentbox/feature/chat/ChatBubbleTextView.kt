package com.ntq.training.momentbox.feature.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.Paint
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.ntq.training.momentbox.R

class ChatBubbleTextView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) :
    View(c, a) {
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val stamp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var content = "";
    private var clock = "";
    private var own = false;
    private var status = ""
    private var textLayout: StaticLayout? = null
    private var icon: Drawable? = null
    private var metaWidth = 0f;
    private var metaTop = 0f
    var maximumWidth = Int.MAX_VALUE
        set(value) {
            if (field != value) {
                field = value; requestLayout()
            }
        }

    fun bind(text: String, time: String, state: String, outgoing: Boolean) {
        if (content == text && clock == time && status == state && own == outgoing) return
        content = text; clock = time; status = state; own = outgoing
        val density = resources.displayMetrics.scaledDensity
        body.textSize = 14 * density; body.typeface =
            ResourcesCompat.getFont(context, R.font.be_vietnam_pro_regular)
        body.color =
            ContextCompat.getColor(context, if (own) R.color.white else R.color.text_primary)
        stamp.textSize = 10.5f * density; stamp.typeface = body.typeface
        stamp.color =
            if (own) 0xA6FFFFFF.toInt() else ContextCompat.getColor(context, R.color.text_tertiary)
        icon = if (own && status != "FAILED") AppCompatResources.getDrawable(
            context, when (status) {
                "SENDING" -> R.drawable.ic_chat_pending; "SENT" -> R.drawable.ic_chat_done; else -> R.drawable.ic_chat_done_all
            }
        )?.mutate()?.apply {
            setTint(if (status == "SEEN") Color.WHITE else 0xA6FFFFFF.toInt())
        } else null
        contentDescription = listOf(
            text, time, if (outgoing) context.getString(
                when (state) {
                    "SENDING" -> R.string.m5_chat_sending; "FAILED" -> R.string.m5_chat_retry; "SENT" -> R.string.m5_chat_sent; "DELIVERED" -> R.string.m5_chat_delivered; else -> R.string.m5_chat_seen
                }
            ) else ""
        ).filter { it.isNotBlank() }.joinToString(", ")
        requestLayout(); invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val d = resources.displayMetrics.density
        val available = minOf(
            maximumWidth,
            MeasureSpec.getSize(widthSpec)
                .takeIf { MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED }
                ?: maximumWidth).coerceAtLeast(1)
        metaWidth = stamp.measureText(clock) + (if (icon != null) 16 * d else 0f)
        val natural = Layout.getDesiredWidth(content, body) + metaWidth + 8 * d
        val width = minOf(available, kotlin.math.ceil(natural).toInt().coerceAtLeast(1))
        val layout = StaticLayout.Builder.obtain(content, 0, content.length, body, width)
            .setIncludePad(false).setLineSpacing(0f, 1.4f)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY).build()
        textLayout = layout
        val last = layout.lineCount - 1
        val stampHeight = stamp.fontMetrics.descent - stamp.fontMetrics.ascent
        val fits = layout.getLineWidth(last) + metaWidth + 8 * d <= width
        val height = if (fits) maxOf(
            layout.height.toFloat(),
            stampHeight
        ) else layout.height + stampHeight + 2 * d
        metaTop = height - stampHeight
        setMeasuredDimension(width, kotlin.math.ceil(height).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        textLayout?.draw(canvas)
        val d = resources.displayMetrics.density;
        val left = width - metaWidth
        canvas.drawText(clock, left, metaTop - stamp.fontMetrics.ascent, stamp)
        icon?.let { glyph ->
            val size = (14 * d).toInt();
            val x = (left + stamp.measureText(clock) + 2 * d).toInt();
            val top = (height - size).coerceAtLeast(0); glyph.setBounds(
            x,
            top,
            x + size,
            top + size
        ); glyph.draw(canvas)
        }
    }
}
