package com.ntq.training.momentbox.feature.create

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Activity sở hữu lựa chọn, Fragment chỉ quan sát, tránh mất thứ tự khi Back. */
class CreateViewModel(private val saved: SavedStateHandle) : ViewModel() {
    private val gson = Gson()
    private val restored =
        runCatching {
            gson
                .fromJson(saved.get<String>("media") ?: "[]", Array<SelectedMedia>::class.java)
                .toList()
        }
            .getOrDefault(emptyList())
    private var selection =
        MediaSelection(restored, saved["imagesOnly"] ?: false, saved["maxItems"] ?: 10)
    private val _items = MutableStateFlow(selection.items)
    val items = _items.asStateFlow()

    val imagesOnly
        get() = saved.get<Boolean>("imagesOnly") ?: false

    val maxItems
        get() = (saved.get<Int>("maxItems") ?: 10).coerceIn(0, 10)

    val pendingCameraCaptureId: String?
        get() = saved.get<String>(KEY_PENDING_CAPTURE_ID)

    fun configure(imageOnly: Boolean, limit: Int = 10) {
        saved["imagesOnly"] = imageOnly
        saved["maxItems"] = limit.coerceIn(0, 10)
        selection = MediaSelection(selection.items, imageOnly, maxItems)
        publish()
    }

    fun toggle(item: SelectedMedia): SelectionChange =
        selection.toggle(item).also { change ->
            if (change == SelectionChange.REMOVED) {
                saved["processed"] =
                    ArrayList(
                        (saved.get<ArrayList<String>>("processed") ?: arrayListOf()).filterNot {
                            it == item.id
                        }
                    )
            }
            publish()
        }

    /**
     * Adds a freshly captured camera media item. Photos are marked as pending-from-camera until
     * confirmed via [commitCameraCapture], so pressing Back from Crop cleanly rolls them back.
     */
    fun beginCameraCapture(item: SelectedMedia): SelectionChange {
        val change = toggle(item)
        if (change == SelectionChange.ADDED && !item.isVideo) {
            saved[KEY_PENDING_CAPTURE_ID] = item.id
            saved[KEY_PENDING_CAPTURE_URI] = item.uri
            saved[KEY_PENDING_CAPTURE_PREV_SUBMITTED] = hasSubmitted
            submitted()
        }
        return change
    }

    /**
     * Confirms a pending camera capture once editing completes. Returns the original capture URI
     * so the caller can clean up the superseded intermediate file if needed.
     */
    fun commitCameraCapture(id: String): String? {
        if (saved.get<String>(KEY_PENDING_CAPTURE_ID) != id) return null
        val uri = saved.get<String>(KEY_PENDING_CAPTURE_URI)
        clearPendingCaptureKeys()
        return uri
    }

    /**
     * Rolls back an unconfirmed camera capture when the user backs out of Crop back to Camera.
     * Removes the item from selection (restoring `maxItems` capacity) and returns the abandoned
     * capture URI so its temp file can be deleted.
     */
    fun rollbackPendingCameraCapture(): String? {
        val id = saved.get<String>(KEY_PENDING_CAPTURE_ID) ?: return null
        val uri = saved.get<String>(KEY_PENDING_CAPTURE_URI)
        val previousSubmitted = saved.get<Boolean>(KEY_PENDING_CAPTURE_PREV_SUBMITTED) ?: false
        clearPendingCaptureKeys()
        val existing = items.value.firstOrNull { it.id == id }
        if (existing != null) {
            toggle(existing)
        }
        saved["submitted"] = previousSubmitted
        return uri
    }

    private fun clearPendingCaptureKeys() {
        saved.remove<String>(KEY_PENDING_CAPTURE_ID)
        saved.remove<String>(KEY_PENDING_CAPTURE_URI)
        saved.remove<Boolean>(KEY_PENDING_CAPTURE_PREV_SUBMITTED)
    }

    fun setSelection(items: List<SelectedMedia>) {
        selection = MediaSelection(items, imagesOnly, maxItems)
        publish()
    }

    fun seed(items: List<SelectedMedia>) {
        if (selection.items.isEmpty()) {
            selection = MediaSelection(items, imagesOnly, maxItems)
            publish()
        }
    }

    val hasAddBackup
        get() = saved.get<String>("addBackup") != null

    /**
     * Add là transaction UI: Back bỏ thay đổi tạm, quota và IDs đã xử lý được khôi phục cùng
     * selection.
     */
    fun beginAdd() {
        if (!hasAddBackup) {
            saved["addBackup"] = gson.toJson(items.value)
            saved["addProcessed"] =
                ArrayList(saved.get<ArrayList<String>>("processed") ?: arrayListOf())
        }
    }

    fun cancelAdd() {
        val json = saved.get<String>("addBackup") ?: return
        val previous =
            runCatching { gson.fromJson(json, Array<SelectedMedia>::class.java).toList() }
                .getOrDefault(emptyList())
        saved["processed"] = saved.get<ArrayList<String>>("addProcessed") ?: arrayListOf()
        setSelection(previous)
        completeAdd()
    }

    fun completeAdd() {
        saved.remove<String>("addBackup")
        saved.remove<ArrayList<String>>("addProcessed")
    }

    val hasSubmitted
        get() = saved.get<Boolean>("submitted") ?: false

    fun submitted() {
        saved["submitted"] = true
    }

    /** Media khôi phục đã qua editor, Add chỉ xử lý ảnh mới, không crop lại bản nháp. */
    fun restoreSelection(items: List<SelectedMedia>) {
        saved["processed"] =
            ArrayList(
                ((saved.get<ArrayList<String>>("processed") ?: arrayListOf()) +
                    items.filterNot { it.isVideo }.map { it.id })
                    .distinct()
            )
        submitted()
        setSelection(items)
    }

    fun nextPhoto(): SelectedMedia? {
        val processed = saved.get<ArrayList<String>>("processed") ?: arrayListOf()
        return items.value.firstOrNull { !it.isVideo && it.id !in processed }
    }

    fun edited(id: String, uri: String) {
        selection.replace(id, uri)
        saved["processed"] =
            ArrayList(((saved.get<ArrayList<String>>("processed") ?: arrayListOf()) + id).distinct())
        publish()
    }

    private fun publish() {
        _items.value = selection.items
        saved["media"] = gson.toJson(selection.items)
    }

    companion object {
        private const val KEY_PENDING_CAPTURE_ID = "pendingCameraCaptureId"
        private const val KEY_PENDING_CAPTURE_URI = "pendingCameraCaptureUri"
        private const val KEY_PENDING_CAPTURE_PREV_SUBMITTED = "pendingCameraCapturePrevSubmitted"
    }
}
