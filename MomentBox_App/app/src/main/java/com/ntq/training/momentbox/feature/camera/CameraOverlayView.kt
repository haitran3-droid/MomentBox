package com.ntq.training.momentbox.feature.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Khóa tampon khi vẽ: analyzer không ghi đè bitmap UI đang đọc. Đếm xử lý/vẽ riêng. */
class CameraOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    private val lock = Any()

    private var frame: Bitmap? = null
    private val copyCanvas = Canvas()
    private val effects =
        PhotoFilter.entries.associateWith { ColorMatrixColorFilter(PhotoFilters.matrix(it)) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val transform = Matrix()

    private val source = RectF()

    private val dest = RectF()
    private var angle = 0

    private var mirror = false
    var showFrames = true
    var viewportRatio = 3f / 4f
    var onThumbnail: ((Bitmap) -> Unit)? = null
    private var lastThumbnail = 0L
    var filter = PhotoFilter.ORIGINAL
        set(value) {
            field = value
            paint.colorFilter = if (value == PhotoFilter.ORIGINAL) null else effects[value]
            invalidate()
        }

    var focusX = -1f

    var focusY = -1f
    var processedFrames = 0L
        private set

    // Mỗi lần invalidate có thể vẽ lại cùng bitmap. Chỉ tăng FPS khi số thứ tự nguồn đổi,
    // các frame bị KEEP_ONLY_LATEST gộp trước lúc UI vẽ không được tính là đã hiển thị.
    private var lastDisplayedSequence = 0L
    var displayedFrames = 0L
        private set

    fun accept(bitmap: Bitmap, rotation: Int, front: Boolean) {
        synchronized(lock) {
            if (frame?.width != bitmap.width || frame?.height != bitmap.height) {
                frame?.recycle()
                frame = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                copyCanvas.setBitmap(frame)
            }
            copyCanvas.drawBitmap(bitmap, 0f, 0f, null)
            angle = rotation
            mirror = front
            processedFrames++
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastThumbnail >= 1000 && onThumbnail != null) {
            lastThumbnail = now
            val thumb = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
            val m =
                Matrix().apply {
                    postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
                    postRotate(rotation.toFloat())

                    val scale = 108f / minOf(bitmap.width, bitmap.height)
                    postScale(if (front) -scale else scale, scale)
                    postTranslate(54f, 54f)
                }
            Canvas(thumb).drawBitmap(bitmap, m, null)
            post { if (isAttachedToWindow) onThumbnail?.invoke(thumb) }
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        synchronized(lock) {
            frame
                ?.takeIf { showFrames }
                ?.let { bitmap ->
                    transform.reset()
                    transform.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
                    transform.postRotate(angle.toFloat())
                    source.set(
                        -bitmap.width / 2f,
                        -bitmap.height / 2f,
                        bitmap.width / 2f,
                        bitmap.height / 2f,
                    )
                    val rotatedWidth = if (angle % 180 == 0) source.width() else source.height()
                    val rotatedHeight = if (angle % 180 == 0) source.height() else source.width()
                    val scale = minOf(width / rotatedWidth, height / rotatedHeight)
                    dest.set(
                        (width - rotatedWidth * scale) / 2,
                        (height - rotatedHeight * scale) / 2,
                        (width + rotatedWidth * scale) / 2,
                        (height + rotatedHeight * scale) / 2,
                    )
                    transform.postScale(if (mirror) -scale else scale, scale)
                    transform.postTranslate(width / 2f, height / 2f)
                    paint.alpha = 255
                    canvas.drawBitmap(bitmap, transform, paint)
                    if (lastDisplayedSequence != processedFrames) {
                        lastDisplayedSequence = processedFrames
                        displayedFrames++
                    }
                }
        }
        if (!showFrames) {
            val w = minOf(width.toFloat(), height * viewportRatio)

            val h = w / viewportRatio
            dest.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        }
        paint.colorFilter = null
        paint.color = 0x47FFFFFF
        paint.strokeWidth = resources.displayMetrics.density / 2
        for (i in 1..2) {
            val x = dest.left + dest.width() * i / 3

            val y = dest.top + dest.height() * i / 3
            canvas.drawLine(x, dest.top, x, dest.bottom, paint)
            canvas.drawLine(dest.left, y, dest.right, y, paint)
        }
        if (focusX >= 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f * resources.displayMetrics.density
            paint.color = 0xFFFFD54F.toInt()
            canvas.drawCircle(focusX, focusY, 35 * resources.displayMetrics.density, paint)
            paint.style = Paint.Style.FILL
        }
        paint.alpha = 255
        paint.colorFilter = if (filter == PhotoFilter.ORIGINAL) null else effects[filter]
    }

    fun release() {
        onThumbnail = null
        synchronized(lock) {
            frame?.recycle()
            frame = null
        }
    }
}
