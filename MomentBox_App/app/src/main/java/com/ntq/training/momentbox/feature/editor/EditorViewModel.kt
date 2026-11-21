package com.ntq.training.momentbox.feature.editor

import android.app.Application
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.ntq.training.momentbox.core.media.OwnedImageFiles
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    private val id =
        saved.get<String>("editorSession")
            ?: UUID.randomUUID().toString().also { saved["editorSession"] = it }
    private val file = AtomicFile(File(app.filesDir, "editor-sessions/$id.json"))
    private val queue = Channel<HistorySnapshot>(Channel.CONFLATED)
    private val _history = MutableStateFlow<EditorHistory?>(null)

    @Volatile
    private var sessionCleared = false

    val history = _history.asStateFlow()
    val storageError = MutableStateFlow(false)
    val restoreError = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            try {
                val loaded =
                    withContext(Dispatchers.IO) {
                        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
                            null
                        } else {
                            try {
                                val snapshot =
                                    file.openRead().use {
                                        Gson().fromJson(it.reader(), HistorySnapshot::class.java)
                                    }
                                EditorHistory.restore(requireNotNull(snapshot))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // Giữ dữ liệu lỗi riêng trước khi thao tác mới ghi đè phiên,
                                // ảnh gốc không bị sửa hay xóa.
                                runCatching {
                                    val backup = File(file.baseFile.path + ".corrupt")
                                    if (!backup.exists()) file.baseFile.copyTo(backup)
                                }
                                restoreError.value = true
                                null
                            }
                        }
                    }
                _history.value = loaded ?: EditorHistory(EditorDocument())
                for (snapshot in queue) {
                    if (sessionCleared) break
                    withContext(Dispatchers.IO) {
                        var stream: FileOutputStream? = null
                        try {
                            file.baseFile.parentFile?.mkdirs()
                            stream = file.startWrite()
                            stream.write(Gson().toJson(snapshot).toByteArray(Charsets.UTF_8))
                            file.finishWrite(stream)
                            storageError.value = false
                        } catch (_: Exception) {
                            file.failWrite(stream)
                            storageError.value = true
                        }
                    }
                }
            } finally {
                if (sessionCleared) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        OwnedImageFiles.deleteEditorSessionFiles(app, id)
                    }
                }
            }
        }
    }

    fun persist() {
        if (sessionCleared) return
        _history.value?.let { queue.trySend(it.snapshot()) }
    }

    fun clearSession() {
        sessionCleared = true
        queue.close()
    }

    override fun onCleared() {
        sessionCleared = true
        queue.close()
    }
}
