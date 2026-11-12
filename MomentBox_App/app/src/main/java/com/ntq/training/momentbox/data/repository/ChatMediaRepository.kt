package com.ntq.training.momentbox.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.ntq.training.momentbox.core.media.OwnedImageFiles
import com.ntq.training.momentbox.core.network.ProgressRequestBody
import com.ntq.training.momentbox.core.network.SessionRequest
import com.ntq.training.momentbox.core.realtime.ChatRuntime
import com.ntq.training.momentbox.data.local.db.MessageEntity
import com.ntq.training.momentbox.data.remote.api.ChatUploadApi
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody

data class ChatMediaProgress(
    val session: ChatSession,
    val bytes: Long,
    val total: Long,
    val uploading: Boolean,
    val failed: Boolean = false
)

class ChatMediaRepository(
    context: Context,
    private val runtime: ChatRuntime,
    private val api: ChatUploadApi
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _progress = MutableStateFlow<Map<String, ChatMediaProgress>>(emptyMap())
    val progress = _progress.asStateFlow()
    private val sessionObserver = scope.launch {
        runtime.session.collect { current ->
            _progress.value.filterValues { it.session != current }.keys.forEach { jobs[it]?.cancel() }
            _progress.update { map -> map.filterValues { it.session == current } }
        }
    }

    fun send(
        expected: ChatSession,
        room: Long,
        uri: String,
        type: String,
        duration: Long? = null,
        ownedQuick: Boolean = false
    ) {
        require(type in setOf("image", "voice"))
        scope.launch {
            var file: File? = null
            var row: MessageEntity? = null
            // Chỉ handoff file chat-quick mới được xóa nguồn; URI Gallery/S14 luôn giữ nguyên.
            val quick = if (ownedQuick) runCatching {
                val parsed = Uri.parse(uri)
                check(parsed.scheme == "file")
                File(
                checkNotNull(
                    parsed.path
                )
            ).canonicalFile.takeIf {
                it.parentFile == File(
                    context.filesDir,
                    "chat-quick"
                ).canonicalFile
            }
            }.getOrNull() else null
            try {
                check(runtime.current() == expected)
                val folder = folder(expected).apply { mkdirs() }
                file = File(
                    folder,
                    UUID.randomUUID().toString() + if (type == "image") ".jpg" else ".m4a"
                )
                if (type == "image") {
                    val bitmap = OwnedImageFiles.decode(context, Uri.parse(uri), 2048)
                    try {
                        file.outputStream()
                            .use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
                    } finally {
                        bitmap.recycle()
                    }
                } else context.contentResolver.openInputStream(Uri.parse(uri))!!.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count =
                                input.read(buffer)
                                if (count < 0) break
                                total += count
                                check(
                                total <= 50L * 1024 * 1024
                            )
                            output.write(buffer, 0, count)
                        }
                    }
                }
                check(file.length() in 1..50L * 1024 * 1024)
                check(runtime.current() == expected)
                row = runtime.repository.prepare(
                    expected,
                    room,
                    "",
                    type,
                    duration = duration,
                    ownedPath = file.absolutePath
                )
                if (row == null) {
                    file.delete()
                    return@launch
                }
                upload(expected, row)
            } catch (cancelled: CancellationException) {
                if (row == null) file?.delete()
                throw cancelled
            } catch (_: Exception) {
                if (row == null) file?.delete() else runtime.repository.failed(
                    expected,
                    row.localId
                )
            } finally {
                quick?.delete()
            }
        }
    }

    /** Scope và parent canonical đều phải đúng trước khi upload file từ row đã lưu trên đĩa. */
    fun retry(expected: ChatSession, row: MessageEntity) {
        scope.launch { upload(expected, row) }
    }

    private suspend fun upload(expected: ChatSession, row: MessageEntity) {
        val operation = currentCoroutineContext()[Job]!!
        if (jobs.putIfAbsent(row.localId, operation) != null) return
        try {
            check(runtime.current() == expected && row.ownerId == expected.ownerId && row.source == expected.source)
            val file = File(checkNotNull(row.ownedPath)).canonicalFile
            check(file.parentFile == folder(expected).canonicalFile && file.isFile && file.length() in 1..50L * 1024 * 1024)
            _progress.update {
                it + (row.localId to ChatMediaProgress(
                    expected,
                    0,
                    file.length(),
                    true
                ))
            }
            val body = ProgressRequestBody(
                file,
                (if (row.type == "image") "image/jpeg" else "audio/mp4").toMediaType(),
                { !operation.isActive }) { bytes ->
                if (operation.isActive) _progress.update {
                    it + (row.localId to ChatMediaProgress(
                        expected,
                        bytes,
                        file.length(),
                        true
                    ))
                }
            }
            val response = api.upload(
                MultipartBody.Part.createFormData("file", file.name, body),
                SessionRequest(expected.generation)
            )
            check(runtime.current() == expected && response.success)
            val result =
                checkNotNull(response.data)
                check(result.type == row.type || (row.type == "voice" && result.type == "audio"))
            val confirmed = runtime.repository.uploaded(expected, row.localId, result.url) ?: return
            _progress.update {
                it + (row.localId to ChatMediaProgress(
                    expected,
                    file.length(),
                    file.length(),
                    false
                ))
            }
            runtime.sendPrepared(expected, confirmed)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runtime.repository.failed(expected, row.localId) }
            _progress.update { it - row.localId }
            throw cancelled
        } catch (_: Exception) {
            runtime.repository.failed(expected, row.localId)
            if (runtime.current() == expected) _progress.update {
                it + (row.localId to ChatMediaProgress(
                    expected,
                    0,
                    0,
                    false,
                    true
                ))
            }
        } finally {
            jobs.remove(row.localId, operation)
        }
    }

    /** Cả copy-before-row và upload đều là children của scope. Hủy/chờ toàn bộ trước khi
     * xóa cache scope cũ, giữ chính scope để lần login sau tiếp tục dùng repository. */
    suspend fun cancelForLogout() {
        val work = scope.coroutineContext[Job]!!.children.toList()
        // Observer đầu tiên là collector session sống suốt Application; chỉ hủy các media jobs.
        val operations = work.filter { it !== sessionObserver }
        operations.forEach { it.cancel() }
        operations.joinAll()
        _progress.value = emptyMap()
    }

    fun cancel(id: String) {
        jobs[id]?.cancel()
    }

    private fun folder(expected: ChatSession): File {
        val key = MessageDigest.getInstance("SHA-256")
            .digest((expected.ownerId + "|" + expected.source).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "chat-media"), key)
    }
}