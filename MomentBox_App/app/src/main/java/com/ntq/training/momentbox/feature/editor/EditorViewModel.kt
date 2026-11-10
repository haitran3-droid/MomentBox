package com.ntq.training.momentbox.feature.editor

import android.app.Application
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
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

    val history = _history.asStateFlow()
    val storageError = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val loaded =
                withContext(Dispatchers.IO) {
                    runCatching {
                            file.openRead().use {
                                Gson().fromJson(it.reader(), HistorySnapshot::class.java)
                            }
                        }
                        .getOrNull()
                }
            _history.value =
                loaded?.let { runCatching { EditorHistory.restore(it) }.getOrNull() }
                    ?: EditorHistory(EditorDocument())
            for (snapshot in queue) withContext(Dispatchers.IO) {
                var stream: java.io.FileOutputStream? = null
                try {
                    file.baseFile.parentFile?.mkdirs()
                    stream = file.startWrite()
                    stream.write(Gson().toJson(snapshot).toByteArray(Charsets.UTF_8))
                    file.finishWrite(stream)
                    storageError.value = false
                } catch (e: Exception) {
                    file.failWrite(stream)
                    storageError.value = true
                }
            }
        }
    }

    fun persist() {
        _history.value?.let { queue.trySend(it.snapshot()) }
    }
}
