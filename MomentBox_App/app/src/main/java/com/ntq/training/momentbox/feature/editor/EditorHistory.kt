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
        fun restore(snapshot: HistorySnapshot) =
            EditorHistory(snapshot.document).apply {
                undone.addAll(snapshot.undo.takeLast(30))
                redone.addAll(snapshot.redo.takeLast(30))
            }
    }
}
