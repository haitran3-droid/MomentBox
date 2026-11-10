package com.ntq.training.momentbox.feature.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class CropOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    private var image: Bitmap? = null
    var transform = Affine()
        private set

    var crop = CropRect(0.0, 0.0, 0.0, 0.0)
        private set

    private var ratio = 0.0

    private var angle = 0.0
    private var restored: Bundle? = null
    private var ratioAnimation: android.animation.ValueAnimator? = null
    val currentAngle
        get() = angle

    val currentRatio
        get() = ratio

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    private val path = Path()
    private var down = Point(0.0, 0.0)

    private var activePointer = -1
    private var edges = 0

    private var dragging = false

    private var scaling = false
    var busy = false
    private val density = resources.displayMetrics.density

    private fun androidMatrix(): Matrix {
        matrix.setValues(
            floatArrayOf(
                transform.a.toFloat(),
                transform.c.toFloat(),
                transform.tx.toFloat(),
                transform.b.toFloat(),
                transform.d.toFloat(),
                transform.ty.toFloat(),
                0f,
                0f,
                1f,
            )
        )
        return matrix
    }

    fun setImage(bitmap: Bitmap) {
        image = bitmap
        if (width > 0 && height > 0) initialize()
        invalidate()
    }

    private fun initialize() {
        val bitmap = image ?: return
        val old = restored
        if (old != null) {
            val oldWidth = old.getInt("width", width).coerceAtLeast(1)

            val oldHeight = old.getInt("height", height).coerceAtLeast(1)
            val scale = minOf(width.toDouble() / oldWidth, height.toDouble() / oldHeight)
            val mapping =
                Affine.translation(width / 2.0, height / 2.0) *
                    Affine.scale(scale, scale) *
                    Affine.translation(-oldWidth / 2.0, -oldHeight / 2.0)
            val a = old.getDoubleArray("matrix")!!
            transform = mapping * Affine(a[0], a[1], a[2], a[3], a[4], a[5])
            val c = old.getDoubleArray("crop")!!

            val first = mapping.map(Point(c[0], c[1]))

            val last = mapping.map(Point(c[2], c[3]))
            crop = CropRect(first.x, first.y, last.x, last.y)
            ratio = old.getDouble("ratio")
            angle = old.getDouble("angle")
            restored = null
            cover()
            return
        }
        val pad = 28 * density
        val s =
            minOf((width - 2 * pad) / bitmap.width, (height - 2 * pad) / bitmap.height)
                .coerceAtLeast(.01f)
                .toDouble()
        transform =
            Affine.translation(width / 2.0, height / 2.0) *
                Affine.scale(s, s) *
                Affine.translation(-bitmap.width / 2.0, -bitmap.height / 2.0)
        crop =
            CropRect(
                (width - bitmap.width * s) / 2,
                (height - bitmap.height * s) / 2,
                (width + bitmap.width * s) / 2,
                (height + bitmap.height * s) / 2,
            )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        ratioAnimation?.cancel()
        if (oldw > 0 && oldh > 0 && image != null) {
            val scale = minOf(w.toDouble() / oldw, h.toDouble() / oldh)
            val mapping =
                Affine.translation(w / 2.0, h / 2.0) *
                    Affine.scale(scale, scale) *
                    Affine.translation(-oldw / 2.0, -oldh / 2.0)
            transform = mapping * transform

            val p = mapping.map(Point(crop.left, crop.top))

            val q = mapping.map(Point(crop.right, crop.bottom))
            crop = CropRect(p.x, p.y, q.x, q.y)
        } else initialize()
    }

    private fun cover() {
        image?.let {
            transform = CropMath.cover(transform, it.width.toDouble(), it.height.toDouble(), crop)
        }
        invalidate()
    }

    fun setRatio(value: Double, animate: Boolean = false) {
        if (busy || image == null) return
        ratioAnimation?.cancel()
        ratio = value
        val maxW = (width - 56 * density).toDouble().coerceAtLeast(1.0)

        val maxH = (height - 56 * density).toDouble().coerceAtLeast(1.0)
        val w = if (value <= 0) maxW else minOf(maxW, maxH * value)

        val h = if (value <= 0) maxH else w / value
        val target = CropRect((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        if (!animate) {
            crop = target
            cover()
        } else {
            val before = crop
            ratioAnimation =
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 160
                    addUpdateListener { animator ->
                        val f = (animator.animatedValue as Float).toDouble()
                        val mix: (Double, Double) -> Double = { a, b -> a + (b - a) * f }
                        crop =
                            CropRect(
                                mix(before.left, target.left),
                                mix(before.top, target.top),
                                mix(before.right, target.right),
                                mix(before.bottom, target.bottom),
                            )
                        cover()
                    }
                    start()
                }
        }
    }

    fun rotateQuarter() {
        if (busy) return
        transform =
            Affine.translation(crop.cx, crop.cy) *
                Affine.rotation(90.0) *
                Affine.translation(-crop.cx, -crop.cy) *
                transform
        cover()
    }

    fun flip() {
        if (busy) return
        transform =
            Affine.translation(crop.cx, crop.cy) *
                Affine.scale(-1.0, 1.0) *
                Affine.translation(-crop.cx, -crop.cy) *
                transform
        cover()
    }

    fun setAngle(value: Double) {
        if (busy) return
        transform =
            Affine.translation(crop.cx, crop.cy) *
                Affine.rotation(value - angle) *
                Affine.translation(-crop.cx, -crop.cy) *
                transform
        angle = value
        cover()
    }

    fun reset() {
        ratioAnimation?.cancel()
        ratio = 0.0
        angle = 0.0
        restored = null
        initialize()
        invalidate()
    }

    /** Snapshot trên Main trước khi giao pixel work sang Default, worker sở hữu bản copy riêng. */
    fun exportSnapshot(): com.ntq.training.momentbox.core.media.BitmapRenderSnapshot {
        val bitmap = requireNotNull(image).copy(Bitmap.Config.ARGB_8888, false)
        val state = transform

        val frame = crop
        return com.ntq.training.momentbox.core.media.BitmapRenderSnapshot(bitmap) { input ->
            run {
                val pixelScale = hypot(state.a, state.b)
                val w = (frame.width / pixelScale).roundToInt().coerceIn(1, 2048)

                val h = (frame.height / pixelScale).roundToInt().coerceIn(1, 2048)
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val m =
                    Matrix().apply {
                        setValues(
                            floatArrayOf(
                                state.a.toFloat(),
                                state.c.toFloat(),
                                state.tx.toFloat(),
                                state.b.toFloat(),
                                state.d.toFloat(),
                                state.ty.toFloat(),
                                0f,
                                0f,
                                1f,
                            )
                        )
                    }
                Canvas(out).apply {
                    scale(w / frame.width.toFloat(), h / frame.height.toFloat())
                    translate(-frame.left.toFloat(), -frame.top.toFloat())
                    concat(m)
                    drawBitmap(input, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                }
                out
            }
        }
    }

    fun export(): Bitmap = exportSnapshot().invoke()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        image?.let {
            paint.color = Color.WHITE
            paint.style = Paint.Style.FILL
            canvas.drawBitmap(it, androidMatrix(), paint)
        }
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addRect(
            crop.left.toFloat(),
            crop.top.toFloat(),
            crop.right.toFloat(),
            crop.bottom.toFloat(),
            Path.Direction.CW,
        )
        paint.color = 0x99000000.toInt()
        canvas.drawPath(path, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        canvas.drawRect(
            crop.left.toFloat(),
            crop.top.toFloat(),
            crop.right.toFloat(),
            crop.bottom.toFloat(),
            paint,
        )
        if (dragging) {
            paint.alpha = 100
            for (i in 1..2) {
                val x = (crop.left + crop.width * i / 3).toFloat()

                val y = (crop.top + crop.height * i / 3).toFloat()
                canvas.drawLine(x, crop.top.toFloat(), x, crop.bottom.toFloat(), paint)
                canvas.drawLine(crop.left.toFloat(), y, crop.right.toFloat(), y, paint)
            }
            paint.alpha = 255
        }
        paint.strokeWidth = 4 * density
        crop.corners().forEach { p ->
            val sx = if (p.x == crop.left) 1 else -1

            val sy = if (p.y == crop.top) 1 else -1
            canvas.drawLine(
                p.x.toFloat(),
                p.y.toFloat(),
                (p.x + sx * 22 * density).toFloat(),
                p.y.toFloat(),
                paint,
            )
            canvas.drawLine(
                p.x.toFloat(),
                p.y.toFloat(),
                p.x.toFloat(),
                (p.y + sy * 22 * density).toFloat(),
                paint,
            )
        }
        paint.style = Paint.Style.FILL
    }

    private val detector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    val scale = d.scaleFactor.coerceIn(.8f, 1.2f).toDouble()
                    transform =
                        Affine.translation(d.focusX.toDouble(), d.focusY.toDouble()) *
                            Affine.scale(scale, scale) *
                            Affine.translation(-d.focusX.toDouble(), -d.focusY.toDouble()) *
                            transform
                    cover()
                    return true
                }
            },
        )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (busy || image == null) return false
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ratioAnimation?.cancel()
                parent.requestDisallowInterceptTouchEvent(true)
                activePointer = event.getPointerId(0)
                down = Point(event.x.toDouble(), event.y.toDouble())

                val hit = 24 * density
                edges = 0
                if (abs(event.x - crop.left) < hit) edges = edges or 1
                if (abs(event.x - crop.right) < hit) edges = edges or 2
                if (abs(event.y - crop.top) < hit) edges = edges or 4
                if (abs(event.y - crop.bottom) < hit) edges = edges or 8
                dragging = true
                scaling = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> scaling = true
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                activePointer = event.getPointerId(remaining)
                down = Point(event.getX(remaining).toDouble(), event.getY(remaining).toDouble())
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointer)
                if (index < 0) return true
                val point = Point(event.getX(index).toDouble(), event.getY(index).toDouble())

                val dx = point.x - down.x

                val dy = point.y - down.y
                if (!detector.isInProgress && !scaling) {
                    if (edges == 0) {
                        val x = dx.coerceIn(-crop.left, width - crop.right)

                        val y = dy.coerceIn(-crop.top, height - crop.bottom)
                        crop =
                            CropRect(crop.left + x, crop.top + y, crop.right + x, crop.bottom + y)
                    } else {
                        val min =
                            minOf(
                                    48.0 * density,
                                    crop.width,
                                    crop.height,
                                    width.toDouble(),
                                    height.toDouble(),
                                )
                                .coerceAtLeast(1.0)

                        var l = crop.left

                        var t = crop.top

                        var rr = crop.right

                        var bb = crop.bottom
                        if (edges and 1 != 0) l = (l + dx).coerceIn(0.0, rr - min)
                        if (edges and 2 != 0) rr = (rr + dx).coerceIn(l + min, width.toDouble())
                        if (edges and 4 != 0) t = (t + dy).coerceIn(0.0, bb - min)
                        if (edges and 8 != 0) bb = (bb + dy).coerceIn(t + min, height.toDouble())
                        if (ratio > 0) {
                            if ((rr - l) / (bb - t) > ratio) rr = l + (bb - t) * ratio
                            else bb = t + (rr - l) / ratio
                        }
                        crop = CropRect(l, t, rr, bb)
                    }
                    cover()
                }
                down = point
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                scaling = false
                activePointer = -1
                parent.requestDisallowInterceptTouchEvent(false)
                performClick()
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onSaveInstanceState(): Parcelable =
        Bundle().apply {
            putParcelable("super", super.onSaveInstanceState())
            putInt("width", width)
            putInt("height", height)
            putDoubleArray(
                "matrix",
                doubleArrayOf(
                    transform.a,
                    transform.b,
                    transform.c,
                    transform.d,
                    transform.tx,
                    transform.ty,
                ),
            )
            putDoubleArray("crop", doubleArrayOf(crop.left, crop.top, crop.right, crop.bottom))
            putDouble("ratio", ratio)
            putDouble("angle", angle)
        }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            super.onRestoreInstanceState(
                androidx.core.os.BundleCompat.getParcelable(state, "super", Parcelable::class.java)
            )
            restored = state
            if (image != null && width > 0) initialize()
        } else super.onRestoreInstanceState(state)
    }

    fun release() {
        ratioAnimation?.cancel()
        image?.recycle()
        image = null
    }
}
