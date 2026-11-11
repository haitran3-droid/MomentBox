package com.ntq.training.momentbox.feature.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.ntq.training.momentbox.R

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
    var onThumbnail: ((Bitmap) -> Unit)? = null
    private var thumbnail: Bitmap? = null
    private val thumbnailCanvas = Canvas()
    private val thumbnailTransform = Matrix()
    private val thumbnailPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    var filter = PhotoFilter.ORIGINAL
        set(value) {
            field = value
            paint.colorFilter = if (value == PhotoFilter.ORIGINAL) null else effects[value]
            invalidate()
        }

    var focusX = -1f

    var focusY = -1f
    var exposureAvailable = false
    var exposureFraction = .5f
    private val sun by lazy {
        ContextCompat.getDrawable(
            context,
            R.drawable.ic_m4_camera_sun,
        )
    }
    var processedFrames = 0L
        private set

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
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        var thumbnailUpdate: Bitmap? = null
        if (showFrames) canvas.drawColor(Color.BLACK)
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
                    // Cùng FILL_CENTER với PreviewView: toàn vùng ngắm chỉ có một frame đã lọc.
                    val scale = maxOf(width / rotatedWidth, height / rotatedHeight)
                    dest.set(0f, 0f, width.toFloat(), height.toFloat())
                    transform.postScale(if (mirror) -scale else scale, scale)
                    transform.postTranslate(width / 2f, height / 2f)
                    paint.alpha = 255
                    canvas.drawBitmap(bitmap, transform, paint)
                    if (lastDisplayedSequence != processedFrames) {
                        lastDisplayedSequence = processedFrames
                        displayedFrames++
                        if (onThumbnail != null) {
                            val small =
                                thumbnail
                                    ?: Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888).also {
                                        thumbnail = it
                                        thumbnailCanvas.setBitmap(it)
                                    }
                            thumbnailTransform.reset()
                            thumbnailTransform.postTranslate(
                                -bitmap.width / 2f,
                                -bitmap.height / 2f,
                            )
                            thumbnailTransform.postRotate(angle.toFloat())
                            val thumbnailScale = 108f / minOf(bitmap.width, bitmap.height)
                            thumbnailTransform.postScale(
                                if (mirror) -thumbnailScale else thumbnailScale,
                                thumbnailScale,
                            )
                            thumbnailTransform.postTranslate(54f, 54f)
                            thumbnailCanvas.drawBitmap(bitmap, thumbnailTransform, thumbnailPaint)
                            thumbnailUpdate = small
                        }
                    }
                }
        }
        if (!showFrames) dest.set(0f, 0f, width.toFloat(), height.toFloat())
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
            val dp = resources.displayMetrics.density
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f * dp
            paint.color = 0xFFFFD54F.toInt()
            canvas.drawCircle(focusX, focusY, 35 * dp, paint)
            paint.style = Paint.Style.FILL
            if (exposureAvailable) {
                val x = (focusX + 52 * dp).coerceIn(12 * dp, width - 12 * dp)
                sun?.apply {
                    setBounds(
                        (x - 9 * dp).toInt(),
                        (focusY - 35 * dp).toInt(),
                        (x + 9 * dp).toInt(),
                        (focusY - 17 * dp).toInt(),
                    )
                    draw(canvas)
                }
                val top = focusY - 12 * dp

                val bottom = top + 46 * dp
                paint.color = 0x80FFD54F.toInt()
                paint.strokeWidth = 2 * dp
                canvas.drawLine(x, top, x, bottom, paint)
                paint.color = 0xFFFFD54F.toInt()
                canvas.drawCircle(
                    x,
                    bottom - exposureFraction.coerceIn(0f, 1f) * 46 * dp,
                    5 * dp,
                    paint,
                )
            }
        }
        paint.alpha = 255
        paint.colorFilter = if (filter == PhotoFilter.ORIGINAL) null else effects[filter]
        thumbnailUpdate?.let { onThumbnail?.invoke(it) }
    }

    fun release() {
        onThumbnail = null
        synchronized(lock) {
            frame?.recycle()
            frame = null
        }
        thumbnailCanvas.setBitmap(null)
        thumbnail = null
    }
}
