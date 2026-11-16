package com.ntq.training.momentbox.feature.camera

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import kotlin.math.abs

class CameraShutterButton(context: Context, attrs: AttributeSet?) :
    AppCompatImageButton(context, attrs) {
    var isVideoMode: () -> Boolean = { false }
    var onShutterClick: (() -> Unit)? = null
    var onVideoHoldStart: (() -> Unit)? = null
    var onVideoHoldEnd: ((quickTap: Boolean) -> Unit)? = null
    private var holdingVideo = false

    init {
        isClickable = true
    }

    override fun performClick(): Boolean {
        val click = onShutterClick ?: return super.performClick()
        super.performClick()
        click()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holdingVideo = isVideoMode()
                if (holdingVideo) onVideoHoldStart?.invoke() else performClick()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (holdingVideo) {
                    holdingVideo = false
                    val quickTap = event.actionMasked == MotionEvent.ACTION_UP &&
                        event.eventTime - event.downTime < VIDEO_HOLD_THRESHOLD_MS
                    onVideoHoldEnd?.invoke(quickTap)
                }
            }
        }
        return true
    }
}

/** Keeps horizontal swipes on the mode row while child labels handle their own clicks. */
class CameraModeStrip(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    var onSwipe: ((dx: Float, dy: Float) -> Boolean)? = null
    private var downX = 0f
    private var downY = 0f
    private var multiple = false

    init {
        isMotionEventSplittingEnabled = false
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val swipe = onSwipe ?: return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                multiple = false
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> multiple = true
            MotionEvent.ACTION_UP -> {
                parent.requestDisallowInterceptTouchEvent(false)
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                val tapRadius = (15 * resources.displayMetrics.density).toInt()
                if (multiple || swipe(dx, dy) || abs(dx) >= tapRadius || abs(dy) >= tapRadius) {
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    try {
                        super.dispatchTouchEvent(cancel)
                    } finally {
                        cancel.recycle()
                    }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
        }
        super.dispatchTouchEvent(event)
        return true
    }
}

internal const val VIDEO_HOLD_THRESHOLD_MS = 250L
