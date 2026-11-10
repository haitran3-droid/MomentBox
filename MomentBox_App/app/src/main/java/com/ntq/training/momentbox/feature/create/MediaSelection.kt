package com.ntq.training.momentbox.feature.create

/** Mô tả nhỏ được lưu bằng JSON, không đưa Bitmap qua Binder/SavedState. */
data class SelectedMedia(
    val id: String,
    val uri: String,
    val mimeType: String,
    val durationMs: Long? = null,
    val width: Int = 0,
    val height: Int = 0,
    val modified: Long = 0,
    val bucketId: String? = null,
    val bucketName: String? = null,
    val sourceUri: String? = null,
) {
    val isVideo
        get() = mimeType.startsWith("video/")
}

enum class SelectionChange {
    ADDED,
    REMOVED,
    LIMIT,
    UNSUPPORTED_VIDEO,
    INVALID,
}

/** Lựa chọn là một danh sách có thứ tự, dùng chung khi thêm từ S15 hoặc chụp ảnh. */
class MediaSelection(
    initial: List<SelectedMedia> = emptyList(),
    private val imagesOnly: Boolean = false,
    maxItems: Int = 10,
) {
    private val limit = maxItems.coerceIn(0, 10)
    private val selected = initial.distinctBy { it.id }.filter(::valid).take(limit).toMutableList()
    val items: List<SelectedMedia>
        get() = selected.toList()

    private fun valid(item: SelectedMedia) =
        item.id.isNotBlank() &&
            item.uri.isNotBlank() &&
            (item.mimeType.startsWith("image/") ||
                (!imagesOnly &&
                    item.isVideo &&
                    item.durationMs != null &&
                    item.durationMs in 1..60_000))

    /** Thay URI sau crop/editor nhưng giữ stable ID và vị trí gốc. */
    fun replace(id: String, uri: String) {
        val index = selected.indexOfFirst { it.id == id }
        if (index >= 0) selected[index] = selected[index].copy(uri = uri, mimeType = "image/jpeg")
    }

    fun toggle(item: SelectedMedia): SelectionChange {
        val index = selected.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            selected.removeAt(index)
            return SelectionChange.REMOVED
        }
        if (
            item.id.isBlank() ||
                item.uri.isBlank() ||
                (imagesOnly && item.isVideo) ||
                (!item.isVideo && !item.mimeType.startsWith("image/"))
        )
            return SelectionChange.INVALID
        if (item.isVideo && (item.durationMs == null || item.durationMs !in 1..60_000))
            return SelectionChange.UNSUPPORTED_VIDEO
        if (selected.size >= limit) return SelectionChange.LIMIT
        selected.add(item)
        return SelectionChange.ADDED
    }
}
