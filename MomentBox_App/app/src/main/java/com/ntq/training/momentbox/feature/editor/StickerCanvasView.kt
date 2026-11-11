package com.ntq.training.momentbox.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.media.BitmapRenderSnapshot
import com.ntq.training.momentbox.feature.camera.PhotoFilter
import com.ntq.training.momentbox.feature.camera.PhotoFilters
import com.ntq.training.momentbox.feature.crop.Affine
import com.ntq.training.momentbox.feature.crop.Point
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

class StickerCanvasView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    companion object {
        const val STICKER_MIME = "text/vnd.momentbox.sticker"

        val STICKER_GROUPS =
            listOf(
                listOf("😍", "🥰", "😎", "🌸", "☕", "🌿", "✈️", "📸", "🎉", "⭐", "🍜", "🌈"),
                listOf("✈️", "📷", "🌈"),
                listOf("☕", "🍜"),
                listOf("LOVE", "HELLO", "WOW"),
            )

        val STICKERS = STICKER_GROUPS.flatten()
    }

    var history = EditorHistory(EditorDocument())
        set(value) {
            field = value
            invalidate()
        }

    var onChanged: (() -> Unit)? = null
    var tool = "sticker"

    var drawColor = 0xFFFF5B82.toInt()

    var drawThickness = 6f
    var selectedId: String? = null
        private set

    private var image: Bitmap? = null
    private var selectedDocument: EditorDocument? = null
    private var working: EditorDocument? = null
    private var pointer = -1

    private var second = -1
    private var anchor = Point(0.0, 0.0)

    private var origin: EditorLayer? = null
    private var startDistance = 1.0

    private var startAngle = 0.0

    private var startCenter = Point(0.0, 0.0)
    private var stroke = mutableListOf<StrokePoint>()

    private var deleteGesture = false

    private var handleGesture = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val selectionPath = Path()
    private val selectionDash =
        DashPathEffect(
            floatArrayOf(
                4 * resources.displayMetrics.density,
                2.5f * resources.displayMetrics.density,
            ),
            0f,
        )
    private val deleteIcon =
        ContextCompat.getDrawable(
            context,
            R.drawable.ic_m4_editor_handle_close,
        )!!
    private val expandIcon =
        ContextCompat.getDrawable(
            context,
            R.drawable.ic_m4_editor_handle_expand,
        )!!
    private var drop = false
    private var strokeInterrupted = false

    private fun base(): Affine {
        val bm = image ?: return Affine()

        val s = minOf(width.toDouble() / bm.width, height.toDouble() / bm.height)
        return Affine.translation((width - bm.width * s) / 2, (height - bm.height * s) / 2) *
            Affine.scale(s, s)
    }

    private fun matrix(a: Affine) =
        Matrix().apply {
            setValues(
                floatArrayOf(
                    a.a.toFloat(),
                    a.c.toFloat(),
                    a.tx.toFloat(),
                    a.b.toFloat(),
                    a.d.toFloat(),
                    a.ty.toFloat(),
                    0f,
                    0f,
                    1f,
                )
            )
        }

    private fun layerMatrix(l: EditorLayer) =
        Affine.translation(l.x.toDouble(), l.y.toDouble()) *
            Affine.rotation(l.rotation.toDouble()) *
            Affine.scale(l.scale.toDouble(), l.scale.toDouble())

    private fun imagePoint(x: Float, y: Float) =
        base().inverse().map(Point(x.toDouble(), y.toDouble()))

    private fun size() = (image?.width ?: 1000) * .22f

    private fun textSize(layer: EditorLayer) =
        (image?.width ?: 1000) * (if (layer.kind == "text") .085f else .22f)

    private fun layerBounds(layer: EditorLayer): RectF {
        val p =
            Paint().apply {
                textSize = textSize(layer)
                typeface =
                    if (layer.kind == "text")
                        ResourcesCompat.getFont(
                            context,
                            R.font.be_vietnam_pro_bold,
                        )
                    else Typeface.DEFAULT
            }
        return RectF(
            -p.measureText(layer.text) / 2 - textSize(layer) * .1f,
            -textSize(layer) * .6f,
            p.measureText(layer.text) / 2 + textSize(layer) * .1f,
            textSize(layer) * .6f,
        )
    }

    fun setImage(bitmap: Bitmap) {
        image = bitmap
        invalidate()
    }

    fun addSticker(text: String, x: Float? = null, y: Float? = null) {
        if (text !in STICKERS) return
        add(
            EditorLayer(
                UUID.randomUUID().toString(),
                "sticker",
                text,
                x ?: ((image?.width ?: 0) / 2f),
                y ?: ((image?.height ?: 0) / 2f),
            )
        )
    }

    fun addText(text: String, color: Int = Color.WHITE, background: Boolean = false) {
        if (text.isBlank()) return
        val existing = history.document.layers.find { it.id == selectedId && it.kind == "text" }
        if (existing != null)
            commit(
                history.document.copy(
                    layers =
                        history.document.layers.map {
                            if (it.id == existing.id)
                                it.copy(
                                    text = text.take(240),
                                    color = color,
                                    background = background,
                                )
                            else it
                        }
                )
            )
        else
            add(
                EditorLayer(
                    UUID.randomUUID().toString(),
                    "text",
                    text.take(240),
                    (image?.width ?: 0) / 2f,
                    (image?.height ?: 0) / 2f,
                    color = color,
                    background = background,
                )
            )
    }

    private fun add(layer: EditorLayer) {
        commit(history.document.copy(layers = history.document.layers + layer))
        selectedId = layer.id
    }

    private fun commit(document: EditorDocument) {
        history.commit(document)
        onChanged?.invoke()
        invalidate()
    }

    fun undo() {
        history.undo()
        selectedId = null
        onChanged?.invoke()
        invalidate()
    }

    fun redo() {
        history.redo()
        selectedId = null
        onChanged?.invoke()
        invalidate()
    }

    fun adjust(
        filter: Int = history.document.filter,
        brightness: Float = history.document.brightness,
        contrast: Float = history.document.contrast,
        saturation: Float = history.document.saturation,
    ) {
        commit(
            history.document.copy(
                filter = filter,
                brightness = brightness,
                contrast = contrast,
                saturation = saturation,
            )
        )
    }

    private fun render(canvas: Canvas, document: EditorDocument, bm: Bitmap, selection: Boolean) {
        val cm =
            ColorMatrix(PhotoFilters.matrix(PhotoFilter.entries[document.filter.coerceIn(0, 5)]))
        cm.postConcat(ColorMatrix().apply { setSaturation(document.saturation) })
        val c = document.contrast

        val delta = (1 - c) * 128 + document.brightness * 255
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c,
                    0f,
                    0f,
                    0f,
                    delta,
                    0f,
                    c,
                    0f,
                    0f,
                    delta,
                    0f,
                    0f,
                    c,
                    0f,
                    delta,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                )
            )
        )
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bm, 0f, 0f, paint)
        paint.colorFilter = null
        for (l in document.layers) {
            paint.color = l.color
            paint.style = Paint.Style.FILL
            canvas.save()
            canvas.concat(matrix(layerMatrix(l)))
            if (l.kind == "draw") {
                val path = Path()
                l.points.firstOrNull()?.let { path.moveTo(it.x, it.y) }
                l.points.zipWithNext().forEach { (a, b) ->
                    path.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = l.thickness
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL
            } else {
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = textSize(l)
                paint.typeface =
                    if (l.kind == "text")
                        ResourcesCompat.getFont(
                            context,
                            R.font.be_vietnam_pro_bold,
                        )
                    else Typeface.DEFAULT
                if (l.background) {
                    val color = paint.color
                    paint.color = 0x99000000.toInt()
                    canvas.drawRoundRect(
                        -paint.measureText(l.text) / 2 - 12,
                        -textSize(l) * .6f,
                        paint.measureText(l.text) / 2 + 12,
                        textSize(l) * .6f,
                        12f,
                        12f,
                        paint,
                    )
                    paint.color = color
                }
                canvas.drawText(l.text, 0f, -(paint.ascent() + paint.descent()) / 2, paint)
            }
            canvas.restore()
            if (selection && selectedId == l.id && l.kind != "draw") drawSelection(canvas, l)
        }
    }

    private fun drawSelection(canvas: Canvas, l: EditorLayer) {
        val bounds = layerBounds(l)
        val mapping = base() * layerMatrix(l)

        val dp = resources.displayMetrics.density
        val corners =
            listOf(
                    Point(bounds.left.toDouble(), bounds.top.toDouble()),
                    Point(bounds.right.toDouble(), bounds.top.toDouble()),
                    Point(bounds.right.toDouble(), bounds.bottom.toDouble()),
                    Point(bounds.left.toDouble(), bounds.bottom.toDouble()),
                )
                .map(mapping::map)
        // Renderer đang ở tọa độ ảnh. Bỏ base rồi vẽ controls bằng tọa độ View: vòng26dp,
        // glyph16dp và nét1.5dp không đổi khi ảnh12MP/nhỏ hoặc layer được phóng to/thu nhỏ.
        // Hit test phía dưới vẫn dùng bán kính24dp để giữ vùng chạm48dp cho người dùng.
        canvas.save()
        canvas.concat(matrix(base().inverse()))
        selectionPath.reset()
        selectionPath.moveTo(corners[0].x.toFloat(), corners[0].y.toFloat())
        corners.drop(1).forEach { selectionPath.lineTo(it.x.toFloat(), it.y.toFloat()) }
        selectionPath.close()
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * dp
        paint.pathEffect = selectionDash
        canvas.drawPath(selectionPath, paint)
        paint.pathEffect = null
        val remove = corners[0]

        val resize = corners[2]
        paint.style = Paint.Style.FILL
        paint.color = 0xFFE5484D.toInt()
        canvas.drawCircle(remove.x.toFloat(), remove.y.toFloat(), 13 * dp, paint)
        paint.style = Paint.Style.STROKE
        paint.color = Color.WHITE
        paint.strokeWidth = 2 * dp
        canvas.drawCircle(remove.x.toFloat(), remove.y.toFloat(), 12 * dp, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(resize.x.toFloat(), resize.y.toFloat(), 13 * dp, paint)
        for ((point, icon) in listOf(remove to deleteIcon, resize to expandIcon)) {
            canvas.save()
            canvas.translate(point.x.toFloat(), point.y.toFloat())
            canvas.rotate(l.rotation)
            val half = (8 * dp).roundToInt()
            icon.setBounds(-half, -half, half, half)
            icon.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val bm = image ?: return
        canvas.save()
        canvas.concat(matrix(base()))
        render(canvas, working ?: history.document, bm, true)
        canvas.restore()
        if (drop) {
            paint.color = 0xFF5B4BDB.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6f
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.style = Paint.Style.FILL
        }
    }

    fun exportSnapshot(): BitmapRenderSnapshot {
        val bm = requireNotNull(image).copy(Bitmap.Config.ARGB_8888, false)

        val document = history.document
        // Renderer riêng tránh chia sẻ Paint/Canvas giữa Main và worker.
        return BitmapRenderSnapshot(bm) { input ->
            val out = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
            try {
                val renderer = StickerCanvasView(context.applicationContext)
                renderer.image = input
                renderer.render(Canvas(out), document, input, false)
                out
            } catch (error: Throwable) {
                out.recycle()
                throw error
            }
        }
    }

    private fun replace(layer: EditorLayer) {
        working =
            (working ?: history.document).copy(
                layers =
                    (working ?: history.document).layers.map {
                        if (it.id == layer.id) layer else it
                    }
            )
        invalidate()
    }

    private fun rebase(e: MotionEvent) {
        val indices =
            (0 until e.pointerCount).filter {
                it != e.actionIndex || e.actionMasked != MotionEvent.ACTION_POINTER_UP
            }
        pointer = indices.firstOrNull()?.let(e::getPointerId) ?: -1
        second = indices.getOrNull(1)?.let(e::getPointerId) ?: -1
        val i = e.findPointerIndex(pointer)
        if (i < 0) return
        anchor = imagePoint(e.getX(i), e.getY(i))
        origin = (working ?: history.document).layers.find { it.id == selectedId }
        if (second >= 0) {
            val k = e.findPointerIndex(second)

            val p = imagePoint(e.getX(k), e.getY(k))
            startDistance = hypot(p.x - anchor.x, p.y - anchor.y).coerceAtLeast(1.0)
            startAngle = atan2(p.y - anchor.y, p.x - anchor.x)
            startCenter = Point((p.x + anchor.x) / 2, (p.y + anchor.y) / 2)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (image == null || !isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                working = history.document
                selectedDocument = history.document
                deleteGesture = false
                handleGesture = false
                strokeInterrupted = false
                origin = null
                pointer = e.getPointerId(0)
                second = -1
                anchor = imagePoint(e.x, e.y)
                if (tool == "draw") {
                    stroke = mutableListOf(StrokePoint(anchor.x.toFloat(), anchor.y.toFloat()))
                } else {
                    val selected = history.document.layers.find { it.id == selectedId }
                    if (selected != null) {
                        val inverse = layerMatrix(selected).inverse().map(anchor)

                        val bounds = layerBounds(selected)

                        val tolerance =
                            24 * resources.displayMetrics.density /
                                (hypot(base().a, base().b) * selected.scale)
                        deleteGesture =
                            hypot(inverse.x - bounds.left, inverse.y - bounds.top) < tolerance
                        handleGesture =
                            hypot(inverse.x - bounds.right, inverse.y - bounds.bottom) < tolerance
                    }
                    if (!deleteGesture && !handleGesture)
                        selectedId =
                            history.document.layers
                                .asReversed()
                                .firstOrNull { l ->
                                    l.kind != "draw" &&
                                        layerMatrix(l).inverse().map(anchor).let {
                                            layerBounds(l).contains(it.x.toFloat(), it.y.toFloat())
                                        }
                                }
                                ?.id
                    origin = history.document.layers.find { it.id == selectedId }
                }
                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> {
                if (tool == "draw") {
                    if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) strokeInterrupted = true
                } else rebase(e)
            }

            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointer)
                if (i < 0) return true

                val p = imagePoint(e.getX(i), e.getY(i))
                if (tool == "draw") {
                    if (strokeInterrupted) return true
                    if (stroke.size < 1024) stroke.add(StrokePoint(p.x.toFloat(), p.y.toFloat()))
                    working =
                        history.document.copy(
                            layers =
                                history.document.layers +
                                    EditorLayer(
                                        "stroke",
                                        "draw",
                                        color = drawColor,
                                        thickness = drawThickness,
                                        points = stroke.toList(),
                                    )
                        )
                } else
                    origin?.let { old ->
                        if (second >= 0) {
                            val k = e.findPointerIndex(second)
                            if (k >= 0) {
                                val q = imagePoint(e.getX(k), e.getY(k))

                                val factor = hypot(q.x - p.x, q.y - p.y) / startDistance

                                val angle = atan2(q.y - p.y, q.x - p.x) - startAngle
                                replace(
                                    old.copy(
                                        x = old.x + ((q.x + p.x) / 2 - startCenter.x).toFloat(),
                                        y = old.y + ((q.y + p.y) / 2 - startCenter.y).toFloat(),
                                        scale = (old.scale * factor.toFloat()).coerceIn(.3f, 4f),
                                        rotation = old.rotation + Math.toDegrees(angle).toFloat(),
                                    )
                                )
                            }
                        } else if (handleGesture) {
                            val before =
                                hypot(anchor.x - old.x, anchor.y - old.y).coerceAtLeast(1.0)

                            val after = hypot(p.x - old.x, p.y - old.y)

                            val angle =
                                atan2(p.y - old.y, p.x - old.x) -
                                    atan2(anchor.y - old.y, anchor.x - old.x)
                            replace(
                                old.copy(
                                    scale =
                                        (old.scale * after / before).toFloat().coerceIn(.3f, 4f),
                                    rotation = old.rotation + Math.toDegrees(angle).toFloat(),
                                )
                            )
                        } else if (!deleteGesture)
                            replace(
                                old.copy(
                                    x = old.x + (p.x - anchor.x).toFloat(),
                                    y = old.y + (p.y - anchor.y).toFloat(),
                                )
                            )
                    }
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                var next = working ?: history.document
                if (deleteGesture) {
                    next = next.copy(layers = next.layers.filterNot { it.id == selectedId })
                    selectedId = null
                }
                if (tool == "draw")
                    next =
                        next.copy(
                            layers =
                                next.layers.map {
                                    if (it.id == "stroke")
                                        it.copy(id = UUID.randomUUID().toString())
                                    else it
                                }
                        )
                working = null
                commit(next)
                pointer = -1
                second = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                working = null
                pointer = -1
                second = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
        return true
    }

    init {
        setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED ->
                    event.clipDescription?.hasMimeType(STICKER_MIME) == true
                DragEvent.ACTION_DRAG_ENTERED -> {
                    drop = true
                    invalidate()
                    true
                }

                DragEvent.ACTION_DRAG_EXITED,
                DragEvent.ACTION_DRAG_ENDED -> {
                    drop = false
                    invalidate()
                    true
                }

                DragEvent.ACTION_DROP -> {
                    val text = event.clipData?.getItemAt(0)?.text?.toString()
                    if (text in STICKERS) {
                        val point = imagePoint(event.x, event.y)
                        addSticker(text!!, point.x.toFloat(), point.y.toFloat())
                    }
                    drop = false
                    invalidate()
                    true
                }

                else -> true
            }
        }
    }

    override fun onSaveInstanceState(): Parcelable =
        Bundle().apply {
            putParcelable("parent", super.onSaveInstanceState())
            putString("selected", selectedId)
            putString("tool", tool)
            putInt("color", drawColor)
            putFloat("thickness", drawThickness)
        }

    @Suppress("DEPRECATION")
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            super.onRestoreInstanceState(state.getParcelable("parent"))
            selectedId = state.getString("selected")
            tool = state.getString("tool") ?: "sticker"
            drawColor = state.getInt("color", 0xFFFF5B82.toInt())
            drawThickness = state.getFloat("thickness", 6f)
        } else super.onRestoreInstanceState(state)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun release() {
        image?.recycle()
        image = null
        onChanged = null
    }
}
