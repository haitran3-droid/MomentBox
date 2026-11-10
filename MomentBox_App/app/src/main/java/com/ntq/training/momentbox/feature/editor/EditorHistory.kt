package com.ntq.training.momentbox.feature.editor

data class StrokePoint(val x: Float, val y: Float)

data class EditorLayer(
    val id: String,
    val kind: String = "text",
    val text: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val color: Int = -1,
    val background: Boolean = false,
    val thickness: Float = 6f,
    val points: List<StrokePoint> = emptyList(),
)

data class EditorDocument(
    val layers: List<EditorLayer> = emptyList(),
    val filter: Int = 0,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
)

data class DocumentCommand(val before: EditorDocument, val after: EditorDocument) {
    fun execute() = after

    fun undo() = before
}

data class HistorySnapshot(
    val document: EditorDocument,
    val undo: List<DocumentCommand>,
    val redo: List<DocumentCommand>,
)

/** Command chỉ giữ model nhỏ, không giữ bitmap cho từng bước. Một gesture commit một lần. */
class EditorHistory(initial: EditorDocument) {
    var document = initial
        private set

    private val undone = ArrayDeque<DocumentCommand>()

    private val redone = ArrayDeque<DocumentCommand>()
    val undoCount
        get() = undone.size

    val canRedo
        get() = redone.isNotEmpty()

    fun commit(next: EditorDocument) {
        if (next == document) return
        undone.addLast(DocumentCommand(document, next))
        while (undone.size > 30) undone.removeFirst()
        redone.clear()
        document = next
    }

    fun undo() {
        if (undone.isNotEmpty()) {
            val cmd = undone.removeLast()
            document = cmd.undo()
            redone.addLast(cmd)
        }
    }

    fun redo() {
        if (redone.isNotEmpty()) {
            val cmd = redone.removeLast()
            document = cmd.execute()
            undone.addLast(cmd)
        }
    }

    fun snapshot() = HistorySnapshot(document, undone.toList(), redone.toList())

    companion object {
        /**
         * JSON trên đĩa có thể hỏng, phải kiểm cả nhánh Undo/Redo trước khi đưa tọa độ vào Canvas.
         */
        private fun validate(document: EditorDocument) {
            require(
                document.filter in 0..5 &&
                    document.brightness.isFinite() &&
                    document.contrast.isFinite() &&
                    document.saturation.isFinite()
            )
            require(document.layers.map { it.id }.distinct().size == document.layers.size)
            document.layers.forEach { layer ->
                require(layer.id.isNotBlank() && layer.kind in setOf("text", "sticker", "draw"))
                require(
                    layer.x.isFinite() &&
                        layer.y.isFinite() &&
                        layer.rotation.isFinite() &&
                        layer.scale.isFinite() &&
                        layer.scale in .3f..4f
                )
                require(layer.thickness.isFinite() && layer.thickness > 0)
                require(layer.points.all { it.x.isFinite() && it.y.isFinite() })
            }
        }

        fun restore(snapshot: HistorySnapshot): EditorHistory {
            validate(snapshot.document)
            val undo = snapshot.undo.takeLast(30)

            val redo = snapshot.redo.takeLast(30)
            (undo + redo).forEach {
                validate(it.before)
                validate(it.after)
            }
            return EditorHistory(snapshot.document).apply {
                undone.addAll(undo)
                redone.addAll(redo)
            }
        }
    }
}
