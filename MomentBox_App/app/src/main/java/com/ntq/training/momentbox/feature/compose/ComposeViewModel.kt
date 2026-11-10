package com.ntq.training.momentbox.feature.compose

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.feature.create.SelectedMedia
import com.ntq.training.momentbox.feature.recorder.RecordedAudio
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * VM giữ bản nháp, mutex tuần tự hóa copy/persist/discard để file chỉ xóa sau khi JSON đã commit.
 */
class ComposeViewModel(app: Application, private val saved: SavedStateHandle) :
    AndroidViewModel(app) {
    private val application = app as MomentBoxApp
    private val mutable = MutableStateFlow(ComposeDraft())

    val draft = mutable.asStateFlow()
    val ready = MutableStateFlow(false)

    val error = MutableStateFlow(false)

    val restoring = MutableStateFlow(false)
    private val mutex = Mutex()

    private var persist: Job? = null

    private var importing: Job? = null
    private var persisted = ComposeDraft()
    val upload
        get() = application.uploadRepository

    init {
        viewModelScope.launch {
            try {
                application.postDraftStore.load()?.let { previous ->
                    persisted = previous.snapshot()
                    mutable.value = persisted
                    restoring.value = persisted != ComposeDraft()
                    if (ownedFiles(persisted).any { !it.isFile || it.length() == 0L })
                        error.value = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error.value = true
            } finally {
                ready.value = true
            }
        }
    }

    fun update(transform: (ComposeDraft) -> ComposeDraft) {
        if (!ready.value || restoring.value || upload.postingBlocked) return
        mutable.update { transform(it).snapshot() }
        schedule()
    }

    private fun schedule() {
        persist?.cancel()
        persist =
            viewModelScope.launch {
                delay(300)
                try {
                    mutex.withLock { persistNow(mutable.value) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error.value = true
                }
            }
    }

    /**
     * Chỉ post-drafts là tài sản của form, không xóa URI Gallery/FileProvider/Journal hoặc đường
     * dẫn từ JSON tùy ý.
     */
    private fun ownedFiles(value: ComposeDraft): Set<File> {
        val folder = File(application.filesDir, "post-drafts").canonicalFile
        return (value.media.map { it.uri } + listOfNotNull(value.voice?.uri))
            .mapNotNull { path ->
                runCatching {
                        val uri = Uri.parse(path)
                        if (uri.scheme != "file") null
                        else File(uri.path!!).canonicalFile.takeIf { it.parentFile == folder }
                    }
                    .getOrNull()
            }
            .toSet()
    }

    private suspend fun persistNow(value: ComposeDraft) =
        withContext(NonCancellable) {
            val snapshot = value.snapshot()
            application.postDraftStore.save(snapshot)
            val obsolete = ownedFiles(persisted) - ownedFiles(snapshot)
            persisted = snapshot
            withContext(Dispatchers.IO) { obsolete.forEach { it.delete() } }
        }

    private suspend fun owned(uri: String, suffix: String): String =
        withContext(Dispatchers.IO) {
            val folder =
                File(application.filesDir, "post-drafts").apply { check(mkdirs() || isDirectory) }

            val file = File(folder, "${UUID.randomUUID()}.$suffix")
            try {
                application.contentResolver.openInputStream(Uri.parse(uri))!!.use { input ->
                    file.outputStream().use { out ->
                        val buffer = ByteArray(8192)

                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()

                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            check(total <= 50L * 1024 * 1024)
                            out.write(buffer, 0, n)
                        }
                        check(total > 0)
                    }
                }
                Uri.fromFile(file).toString()
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }

    /**
     * Tạm hoãn import cho đến khi người dùng quyết định draft cũ. Mỗi đợt mới hủy copy cũ trước
     * commit.
     */
    fun importMedia(items: List<SelectedMedia>, done: (List<SelectedMedia>) -> Unit = {}) {
        if (upload.postingBlocked) return
        importing?.cancel()
        importing =
            viewModelScope.launch {
                ready.first { it }
                restoring.first { !it }
                mutex.withLock {
                    val staged = mutableListOf<File>()
                    try {
                        val old = mutable.value.media.associateBy { it.id }
                        if (items.size > 10) error.value = true
                        val next =
                            items.take(10).map { item ->
                                val previous = old[item.id]
                                if (
                                    previous != null &&
                                        (previous.uri == item.uri || previous.sourceUri == item.uri)
                                )
                                    previous
                                else {
                                    val path = owned(item.uri, if (item.isVideo) "mp4" else "jpg")
                                    staged += File(Uri.parse(path).path!!).canonicalFile
                                    item.copy(uri = path, sourceUri = item.uri)
                                }
                            }
                        val value = mutable.value.copy(media = next).snapshot()
                        persistNow(value)
                        mutable.value = value
                        done(next)
                    } catch (e: CancellationException) {
                        staged.filterNot { it in ownedFiles(persisted) }.forEach { it.delete() }
                        throw e
                    } catch (_: Exception) {
                        staged.filterNot { it in ownedFiles(persisted) }.forEach { it.delete() }
                        error.value = true
                    }
                }
            }
    }

    /**
     * Restore giữ thứ tự cũ rồi thêm lựa chọn mới, Discard không dùng lại file thuộc draft vừa bỏ.
     */
    suspend fun resolveRestore(keep: Boolean, incoming: List<SelectedMedia>): List<SelectedMedia> {
        importing?.cancelAndJoin()
        persist?.cancelAndJoin()
        return mutex.withLock {
            val previous = mutable.value

            val oldFiles = previous.media.map { it.uri }.toSet()
            val next =
                if (keep) (previous.media + incoming).distinctBy { it.id }
                else incoming.filterNot { it.uri in oldFiles }
            if (!keep) {
                application.postDraftStore.clear()
                mutable.value = ComposeDraft()

                val obsolete = ownedFiles(previous) + ownedFiles(persisted)
                persisted = ComposeDraft()
                withContext(Dispatchers.IO) { obsolete.forEach { it.delete() } }
            }
            restoring.value = false
            if (next.size > 10) error.value = true
            next.take(10)
        }
    }

    fun audio(audio: RecordedAudio) {
        if (upload.postingBlocked) return
        viewModelScope.launch {
            ready.first { it }
            restoring.first { !it }
            mutex.withLock { importAudioLocked(audio) }
        }
    }

    /**
     * Ghi âm từ nút (+) là một kết quả cần nhận đúng một lần, không phải giá trị mặc định của form.
     * Chỉ ghi dấu đã nhận sau khi bản sao riêng và DataStore đã lưu thành công. SavedStateHandle
     * giữ dấu này qua tạo lại Activity: người dùng xóa voice thì xoay màn hình không được tự thêm
     * lại. Nếu khôi phục tiến trình thấy bản sao đã lưu cùng session, xác nhận nhận xong mà không
     * copy lại.
     */
    fun incomingAudio(audio: RecordedAudio, consumed: () -> Unit) {
        val key = "consumedIncomingVoice"
        if (saved.get<String>(key) == audio.session) {
            consumed()
            return
        }
        if (upload.postingBlocked) return
        viewModelScope.launch {
            ready.first { it }
            if (mutable.value.voice?.session == audio.session) {
                saved[key] = audio.session
                consumed()
                return@launch
            }
            restoring.first { !it }
            mutex.withLock {
                if (saved.get<String>(key) == audio.session || importAudioLocked(audio)) {
                    saved[key] = audio.session
                    consumed()
                }
            }
        }
    }

    /** Caller phải giữ mutex, lỗi copy không được đánh dấu đã nhận hay xóa bản gốc của Recorder. */
    private suspend fun importAudioLocked(audio: RecordedAudio): Boolean {
        if (mutable.value.voice?.session == audio.session) return true
        var staged: File? = null
        return try {
            val path = owned(audio.uri, "m4a")
            staged = File(Uri.parse(path).path!!).canonicalFile
            val next = mutable.value.copy(voice = audio.copy(uri = path))
            persistNow(next)
            mutable.value = next
            true
        } catch (e: CancellationException) {
            staged?.takeIf { it !in ownedFiles(persisted) }?.delete()
            throw e
        } catch (_: Exception) {
            staged?.takeIf { it !in ownedFiles(persisted) }?.delete()
            error.value = true
            false
        }
    }

    suspend fun save() {
        persist?.cancelAndJoin()
        importing?.join()
        mutex.withLock { persistNow(mutable.value) }
    }

    suspend fun discard() {
        if (upload.postingBlocked) return
        persist?.cancelAndJoin()
        importing?.cancelAndJoin()
        mutex.withLock {
            application.postDraftStore.clear()

            val obsolete = ownedFiles(persisted) + ownedFiles(mutable.value)
            persisted = ComposeDraft()
            mutable.value = ComposeDraft()
            restoring.value = false
            withContext(Dispatchers.IO) { obsolete.forEach { it.delete() } }
        }
    }

    fun post() {
        if (upload.postingBlocked || !ready.value || restoring.value) return
        viewModelScope.launch {
            try {
                persist?.cancelAndJoin()
                importing?.join()
                mutex.withLock {
                    persistNow(mutable.value)
                    upload.start(mutable.value)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error.value = true
            }
        }
    }
}
