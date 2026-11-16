package com.ntq.training.momentbox.feature.reminder

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.withTranslation
import kotlin.math.abs
import kotlin.math.ceil

class ReminderClockView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatTextView(context, attrs) {
    private var naturalHeight = 0
    private val maximumTextSize = textSize
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available =
            (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(1)
        val measuredAtMaximum = paint.measureText(text.toString()) * (maximumTextSize / textSize)
        val wanted =
            if (measuredAtMaximum > available) maximumTextSize * available / measuredAtMaximum else maximumTextSize
        if (abs(textSize - wanted) > .1f) setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            wanted
        )
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        naturalHeight = measuredHeight
        val target = ceil(textSize * 1.1).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(measuredWidth, resolveSize(target, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        canvas.withTranslation(y = (height - naturalHeight) / 2f) { super.onDraw(this) }
    }
}