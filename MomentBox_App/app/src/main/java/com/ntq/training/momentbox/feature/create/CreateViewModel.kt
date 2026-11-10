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

    fun configure(imageOnly: Boolean, limit: Int = 10) {
        saved["imagesOnly"] = imageOnly
        saved["maxItems"] = limit.coerceIn(0, 10)
        selection = MediaSelection(selection.items, imageOnly, maxItems)
        publish()
    }

    fun toggle(item: SelectedMedia): SelectionChange =
        selection.toggle(item).also { change ->
            if (change == SelectionChange.REMOVED)
                saved["processed"] =
                    ArrayList(
                        (saved.get<ArrayList<String>>("processed") ?: arrayListOf()).filterNot {
                            it == item.id
                        }
                    )
            publish()
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
            ArrayList((saved.get<ArrayList<String>>("processed") ?: arrayListOf()) + id)
        publish()
    }

    private fun publish() {
        _items.value = selection.items
        saved["media"] = gson.toJson(selection.items)
    }
}
