package com.ntq.training.momentbox.feature.crop

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.core.os.BundleCompat
import com.ntq.training.momentbox.core.media.BitmapRenderSnapshot
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
    private var ratioAnimation: ValueAnimator? = null

    val currentAngle
        get() = angle

    val currentRatio
        get() = ratio

    val canExport: Boolean
        get() = image != null && crop.width >= 1.0 && crop.height >= 1.0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
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

    private fun centeredRectForRatio(value: Double): CropRect {
        val maxW = (width - 56 * density).toDouble().coerceAtLeast(1.0)
        val maxH = (height - 56 * density).toDouble().coerceAtLeast(1.0)
        val w = if (value <= 0.0) maxW else minOf(maxW, maxH * value)
        val h = if (value <= 0.0) maxH else w / value
        return CropRect(
            (width - w) / 2.0,
            (height - h) / 2.0,
            (width + w) / 2.0,
            (height + h) / 2.0,
        )
    }

    private fun initialize() {
        val bitmap = image ?: return
        if (width <= 0 || height <= 0) return
        val old = restored
        if (old != null) {
            restored = null
            if (tryRestoreState(old)) {
                cover()
                return
            }
        }
        val pad = 28 * density
        val s =
            minOf((width - 2 * pad) / bitmap.width, (height - 2 * pad) / bitmap.height)
                .coerceAtLeast(0.01f)
                .toDouble()
        transform =
            Affine.translation(width / 2.0, height / 2.0) *
                Affine.scale(s, s) *
                Affine.translation(-bitmap.width / 2.0, -bitmap.height / 2.0)
        crop =
            if (ratio > 0.0) {
                centeredRectForRatio(ratio)
            } else {
                CropRect(
                    (width - bitmap.width * s) / 2.0,
                    (height - bitmap.height * s) / 2.0,
                    (width + bitmap.width * s) / 2.0,
                    (height + bitmap.height * s) / 2.0,
                )
            }
        cover()
    }

    private fun tryRestoreState(old: Bundle): Boolean {
        if (!old.getBoolean("hasImage", false)) return false
        val oldWidth = old.getInt("width", 0)
        val oldHeight = old.getInt("height", 0)
        val a = old.getDoubleArray("matrix") ?: return false
        val c = old.getDoubleArray("crop") ?: return false
        if (oldWidth <= 0 || oldHeight <= 0 || a.size != 6 || c.size != 4) return false
        if (a.any { !it.isFinite() } || c.any { !it.isFinite() }) return false
        val restoredAffine = Affine(a[0], a[1], a[2], a[3], a[4], a[5])
        val det = restoredAffine.a * restoredAffine.d - restoredAffine.b * restoredAffine.c
        if (!det.isFinite() || abs(det) <= 1e-12) return false
        val scale = minOf(width.toDouble() / oldWidth, height.toDouble() / oldHeight)
        if (!scale.isFinite() || scale <= 0.0) return false
        val mapping =
            Affine.translation(width / 2.0, height / 2.0) *
                Affine.scale(scale, scale) *
                Affine.translation(-oldWidth / 2.0, -oldHeight / 2.0)
        val first = mapping.map(Point(c[0], c[1]))
        val last = mapping.map(Point(c[2], c[3]))
        val mappedCrop = CropRect(first.x, first.y, last.x, last.y)
        if (
            !mappedCrop.width.isFinite() ||
                !mappedCrop.height.isFinite() ||
                mappedCrop.width < 1.0 ||
                mappedCrop.height < 1.0
        ) {
            return false
        }
        transform = mapping * restoredAffine
        crop = mappedCrop
        val savedRatio = old.getDouble("ratio", 0.0)
        if (ratio <= 0.0 && savedRatio.isFinite() && savedRatio >= 0.0) {
            ratio = savedRatio
        }
        val savedAngle = old.getDouble("angle", 0.0)
        angle = if (savedAngle.isFinite()) savedAngle else 0.0
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        ratioAnimation?.cancel()
        if (w <= 0 || h <= 0) return
        if (oldw > 0 && oldh > 0 && image != null && crop.width >= 1.0 && crop.height >= 1.0) {
            val scale = minOf(w.toDouble() / oldw, h.toDouble() / oldh)
            val mapping =
                Affine.translation(w / 2.0, h / 2.0) *
                    Affine.scale(scale, scale) *
                    Affine.translation(-oldw / 2.0, -oldh / 2.0)
            transform = mapping * transform
            val p = mapping.map(Point(crop.left, crop.top))
            val q = mapping.map(Point(crop.right, crop.bottom))
            crop = CropRect(p.x, p.y, q.x, q.y)
            cover()
        } else {
            initialize()
        }
    }

    private fun cover() {
        val bitmap = image ?: return
        if (crop.width < 1.0 || crop.height < 1.0) return
        transform = CropMath.cover(transform, bitmap.width.toDouble(), bitmap.height.toDouble(), crop)
        invalidate()
    }

    fun setRatio(value: Double, animate: Boolean = false) {
        if (busy) return
        ratioAnimation?.cancel()
        ratio = value
        if (image == null || width <= 0 || height <= 0) return
        val target = centeredRectForRatio(value)
        if (!animate || crop.width < 1.0 || crop.height < 1.0) {
            crop = target
            cover()
        } else {
            val before = crop
            ratioAnimation =
                ValueAnimator.ofFloat(0f, 1f).apply {
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
        if (busy || !canExport) return
        transform =
            Affine.translation(crop.cx, crop.cy) *
                Affine.rotation(90.0) *
                Affine.translation(-crop.cx, -crop.cy) *
                transform
        cover()
    }

    fun flip() {
        if (busy || !canExport) return
        transform =
            Affine.translation(crop.cx, crop.cy) *
                Affine.scale(-1.0, 1.0) *
                Affine.translation(-crop.cx, -crop.cy) *
                transform
        cover()
    }

    fun setAngle(value: Double) {
        if (busy || !canExport) return
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
    fun exportSnapshot(): BitmapRenderSnapshot {
        if (ratioAnimation?.isRunning == true) {
            ratioAnimation?.end()
        }
        val bitmap = requireNotNull(image).copy(Bitmap.Config.ARGB_8888, false)
        val state = transform
        val frame = crop
        val lockedRatio = ratio
        require(frame.width >= 1.0 && frame.height >= 1.0) { "Crop rect is empty" }
        return BitmapRenderSnapshot(bitmap) { input ->
            val pixelScale = hypot(state.a, state.b)
            require(pixelScale.isFinite() && pixelScale > 1e-9) { "Invalid crop transform" }
            var w = (frame.width / pixelScale).roundToInt().coerceIn(1, 2048)
            var h = (frame.height / pixelScale).roundToInt().coerceIn(1, 2048)
            if (lockedRatio == 1.0) {
                val side = minOf(w, h)
                w = side
                h = side
            }
            val out = createBitmap(w, h, Bitmap.Config.ARGB_8888)
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

    fun export(): Bitmap = exportSnapshot().invoke()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        image?.let {
            paint.color = Color.WHITE
            paint.style = Paint.Style.FILL
            canvas.drawBitmap(it, androidMatrix(), paint)
        }
        if (crop.width < 1.0 || crop.height < 1.0) return
        paint.color = 0x99000000.toInt()
        paint.style = Paint.Style.FILL
        val l = crop.left.toFloat()
        val t = crop.top.toFloat()
        val r = crop.right.toFloat()
        val b = crop.bottom.toFloat()
        canvas.drawRect(0f, 0f, width.toFloat(), t, paint)
        canvas.drawRect(0f, b, width.toFloat(), height.toFloat(), paint)
        canvas.drawRect(0f, t, l, b, paint)
        canvas.drawRect(r, t, width.toFloat(), b, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        canvas.drawRect(l, t, r, b, paint)
        if (dragging) {
            paint.alpha = 100
            for (i in 1..2) {
                val x = (crop.left + crop.width * i / 3.0).toFloat()
                val y = (crop.top + crop.height * i / 3.0).toFloat()
                canvas.drawLine(x, t, x, b, paint)
                canvas.drawLine(l, y, r, y, paint)
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
                    if (!canExport) return false
                    val scale = d.scaleFactor.coerceIn(0.8f, 1.2f).toDouble()
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
        if (busy || !canExport) return false
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ratioAnimation?.cancel()
                parent.requestDisallowInterceptTouchEvent(true)
                activePointer = event.getPointerId(0)
                down = Point(event.x.toDouble(), event.y.toDouble())
                val hit = 24 * density
                edges = 0
                val withinVertical = event.y in (crop.top - hit)..(crop.bottom + hit)
                val withinHorizontal = event.x in (crop.left - hit)..(crop.right + hit)
                if (withinVertical) {
                    val nearLeft = abs(event.x - crop.left) < hit
                    val nearRight = abs(event.x - crop.right) < hit
                    when {
                        nearLeft && nearRight ->
                            edges =
                                edges or
                                    if (abs(event.x - crop.left) <= abs(event.x - crop.right)) 1
                                    else 2
                        nearLeft -> edges = edges or 1
                        nearRight -> edges = edges or 2
                    }
                }
                if (withinHorizontal) {
                    val nearTop = abs(event.y - crop.top) < hit
                    val nearBottom = abs(event.y - crop.bottom) < hit
                    when {
                        nearTop && nearBottom ->
                            edges =
                                edges or
                                    if (abs(event.y - crop.top) <= abs(event.y - crop.bottom)) 4
                                    else 8
                        nearTop -> edges = edges or 4
                        nearBottom -> edges = edges or 8
                    }
                }
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
                    crop =
                        resizeCropRect(
                            current = crop,
                            edges = edges,
                            dx = dx,
                            dy = dy,
                            viewWidth = width.toDouble(),
                            viewHeight = height.toDouble(),
                            ratio = ratio,
                            minSidePx = 48.0 * density,
                        )
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
            putBoolean("hasImage", canExport)
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
                BundleCompat.getParcelable(state, "super", Parcelable::class.java)
            )
            restored = state
            if (image != null && width > 0 && height > 0) initialize()
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    fun release() {
        ratioAnimation?.cancel()
        image?.recycle()
        image = null
    }

    companion object {
        internal fun resizeCropRect(
            current: CropRect,
            edges: Int,
            dx: Double,
            dy: Double,
            viewWidth: Double,
            viewHeight: Double,
            ratio: Double,
            minSidePx: Double,
        ): CropRect {
            if (viewWidth < 1.0 || viewHeight < 1.0) return current
            if (edges == 0) {
                val x = dx.coerceIn(-current.left, viewWidth - current.right)
                val y = dy.coerceIn(-current.top, viewHeight - current.bottom)
                return CropRect(
                    current.left + x,
                    current.top + y,
                    current.right + x,
                    current.bottom + y,
                )
            }
            if (ratio <= 0.0) {
                val min =
                    minOf(minSidePx, current.width, current.height, viewWidth, viewHeight)
                        .coerceAtLeast(1.0)
                var l = current.left
                var t = current.top
                var rr = current.right
                var bb = current.bottom
                if (edges and 1 != 0) l = (l + dx).coerceIn(0.0, rr - min)
                if (edges and 2 != 0) rr = (rr + dx).coerceIn(l + min, viewWidth)
                if (edges and 4 != 0) t = (t + dy).coerceIn(0.0, bb - min)
                if (edges and 8 != 0) bb = (bb + dy).coerceIn(t + min, viewHeight)
                return CropRect(l, t, rr, bb)
            }

            val baseMin = minOf(minSidePx, viewWidth, viewHeight).coerceAtLeast(1.0)
            val minW: Double
            val minH: Double
            if (ratio >= 1.0) {
                minH = minOf(baseMin, viewHeight, viewWidth / ratio).coerceAtLeast(1.0)
                minW = minH * ratio
            } else {
                minW = minOf(baseMin, viewWidth, viewHeight * ratio).coerceAtLeast(1.0)
                minH = minW / ratio
            }

            val leftEdge = (edges and 1) != 0
            val rightEdge = (edges and 2) != 0
            val topEdge = (edges and 4) != 0
            val bottomEdge = (edges and 8) != 0
            val hasH = leftEdge || rightEdge
            val hasV = topEdge || bottomEdge

            return when {
                hasH && !hasV -> {
                    val anchorX = if (leftEdge) current.right else current.left
                    val maxWByX = if (leftEdge) anchorX else (viewWidth - anchorX)
                    val maxW = minOf(maxWByX, viewHeight * ratio).coerceAtLeast(minW)
                    val desiredW =
                        if (leftEdge) anchorX - (current.left + dx)
                        else (current.right + dx) - anchorX
                    val newW = desiredW.coerceIn(minW, maxW)
                    val newH = newW / ratio
                    val l = if (leftEdge) anchorX - newW else anchorX
                    val rr = if (leftEdge) anchorX else anchorX + newW
                    val t = (current.cy - newH / 2.0).coerceIn(0.0, (viewHeight - newH).coerceAtLeast(0.0))
                    val bb = t + newH
                    CropRect(l, t, rr, bb)
                }

                hasV && !hasH -> {
                    val anchorY = if (topEdge) current.bottom else current.top
                    val maxHByY = if (topEdge) anchorY else (viewHeight - anchorY)
                    val maxH = minOf(maxHByY, viewWidth / ratio).coerceAtLeast(minH)
                    val desiredH =
                        if (topEdge) anchorY - (current.top + dy)
                        else (current.bottom + dy) - anchorY
                    val newH = desiredH.coerceIn(minH, maxH)
                    val newW = newH * ratio
                    val t = if (topEdge) anchorY - newH else anchorY
                    val bb = if (topEdge) anchorY else anchorY + newH
                    val l = (current.cx - newW / 2.0).coerceIn(0.0, (viewWidth - newW).coerceAtLeast(0.0))
                    val rr = l + newW
                    CropRect(l, t, rr, bb)
                }

                else -> {
                    val anchorX = if (leftEdge) current.right else current.left
                    val anchorY = if (topEdge) current.bottom else current.top
                    val maxWByX = if (leftEdge) anchorX else (viewWidth - anchorX)
                    val maxHByY = if (topEdge) anchorY else (viewHeight - anchorY)
                    val maxW = minOf(maxWByX, maxHByY * ratio).coerceAtLeast(minW)
                    val candW =
                        if (leftEdge) anchorX - (current.left + dx)
                        else (current.right + dx) - anchorX
                    val candH =
                        if (topEdge) anchorY - (current.top + dy)
                        else (current.bottom + dy) - anchorY
                    val desiredW = if (abs(dx) >= abs(dy)) candW else candH * ratio
                    val newW = desiredW.coerceIn(minW, maxW)
                    val newH = newW / ratio
                    val l = if (leftEdge) anchorX - newW else anchorX
                    val rr = if (leftEdge) anchorX else anchorX + newW
                    val t = if (topEdge) anchorY - newH else anchorY
                    val bb = if (topEdge) anchorY else anchorY + newH
                    CropRect(l, t, rr, bb)
                }
            }
        }
    }
}
