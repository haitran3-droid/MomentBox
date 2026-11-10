package com.ntq.training.momentbox.feature.recorder

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class RecordingRecoveryStore(context: Context) {
    data class Pending(val session: String, val fileName: String)

    private val marker = AtomicFile(File(context.filesDir, "recordings/active.json"))
    private val gson = Gson()

    companion object {
        private val lock = Mutex()
    }

    suspend fun read(): Pending? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (!marker.baseFile.exists() && !File(marker.baseFile.path + ".bak").exists())
                    return@withLock null
                marker.openRead().use { input ->
                    requireNotNull(gson.fromJson(input.reader(), Pending::class.java)).also {
                        require(
                            it.session.isNotBlank() &&
                                it.fileName.matches(Regex("rec_[a-zA-Z0-9_.-]+\\.pending\\.m4a"))
                        )
                    }
                }
            }
        }

    suspend fun begin(session: String, fileName: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                marker.baseFile.parentFile!!.apply { check(mkdirs() || isDirectory) }
                val stream = marker.startWrite()
                try {
                    stream.write(
                        gson.toJson(Pending(session, fileName)).toByteArray(Charsets.UTF_8)
                    )
                    marker.finishWrite(stream)
                } catch (error: Exception) {
                    marker.failWrite(stream)
                    throw error
                }
            }
        }

    suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock { marker.delete() } }
}
