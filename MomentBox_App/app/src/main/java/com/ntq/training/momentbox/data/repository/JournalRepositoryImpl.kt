package com.ntq.training.momentbox.data.repository

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext
import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.sqlite.db.SimpleSQLiteQuery
import com.ntq.training.momentbox.data.local.db.DayMood
import com.ntq.training.momentbox.data.local.db.JournalAttachmentEntity
import com.ntq.training.momentbox.data.local.db.JournalDao
import com.ntq.training.momentbox.data.local.db.JournalEntryEntity
import com.ntq.training.momentbox.data.local.db.JournalEntryWithDetails
import com.ntq.training.momentbox.data.local.db.TagCount
import com.ntq.training.momentbox.data.local.prefs.JournalDataStore
import com.ntq.training.momentbox.data.local.prefs.JournalDraft
import com.ntq.training.momentbox.data.local.prefs.JournalFilterState
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class JournalRepositoryImpl(
    private val context: Context,
    private val journalDao: JournalDao,
    private val journalDataStore: JournalDataStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : JournalRepository, Closeable {
    private val pendingStagedFiles = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var writesEnabled = true
    private fun checkWriter() {
        if (!writesEnabled) throw CancellationException("JOURNAL_SESSION_ENDED")
    }

    override fun resumeAfterLogin() {
        writesEnabled = true
    }

    override suspend fun prepareForLogout() =
        withContext(dispatcher) { deletionMutex.withLock { writesEnabled = false } }

    override suspend fun finishLogout(keepJournal: Boolean) = withContext(dispatcher) {
        deletionMutex.withLock {
            check(!writesEnabled) { "Logout must seal journal writers first" }
            if (!keepJournal) {
                // Giữ metadata tới khi file cleanup thành công để lỗi I/O còn retry được.
                // Canonical roots chặn Gallery/cache/post/avatar và mọi file ngoài nhật ký.
                val draft = journalDataStore.getDraft()
                val paths = (journalDao.attachmentPathsForLogout() + draft?.attachments.orEmpty()
                    .map { it.localPath } + draft?.cleanupPaths.orEmpty() + pendingStagedFiles).toSet()
                paths.forEach(::safelyDeleteAppOwnedFile)
                journalDataStore.clearDraft()
                journalDao.deleteAllForLogout()
                pendingStagedFiles.clear()
            }
        }
    }

    private val deletionMutex = Mutex()
    private val cleanupScope =
        CoroutineScope(SupervisorJob() + dispatcher)
    private val deletionScheduler = JournalDeletionScheduler(this, cleanupScope)
    override fun close() {
        cleanupScope.coroutineContext[Job]?.cancel()
    }

    override val handlesDeletionDeadlines = true
    override fun observePendingDeletes() = journalDao.observePendingDeletes()


    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAtomicPages(
        query: String?,
        filter: JournalFilterState,
        pages: Int
    ): Flow<JournalPageSnapshot> =
        journalDao.observeJournalChanges(SimpleSQLiteQuery("SELECT 1"))
            .mapLatest {
                withContext(dispatcher) {
                    journalDao.readPageSnapshot(
                        escapeSqlLikePattern(query),
                        filter.selectedMoods,
                        filter.selectedMoods.isEmpty(),
                        filter.hasPhoto,
                        filter.hasAudio,
                        filter.fromDateEpochMs ?: 0L,
                        filter.toDateEpochMs ?: Long.MAX_VALUE,
                        filter.sort,
                        pages
                    )
                }
            }

    override fun observeWrittenDays() = journalDao.observeWrittenDays()
    override fun observeDayCounts(query: String?, filter: JournalFilterState) =
        journalDao.observeDayCounts(
            escapeSqlLikePattern(query),
            filter.selectedMoods,
            filter.selectedMoods.isEmpty(),
            filter.hasPhoto,
            filter.hasAudio,
            filter.fromDateEpochMs ?: 0L,
            filter.toDateEpochMs ?: Long.MAX_VALUE
        )

    override fun observeEntries(
        query: String?,
        filter: JournalFilterState,
        limit: Int,
        offset: Int
    ): Flow<List<JournalEntryWithDetails>> {
        val safeQuery = escapeSqlLikePattern(query)
        val moodsEmpty = filter.selectedMoods.isEmpty()
        val from = filter.fromDateEpochMs ?: 0L
        val to = filter.toDateEpochMs ?: Long.MAX_VALUE

        return journalDao.observeEntries(
            query = safeQuery,
            moods = filter.selectedMoods,
            moodsEmpty = moodsEmpty,
            hasPhoto = filter.hasPhoto,
            hasAudio = filter.hasAudio,
            from = from,
            to = to,
            sort = filter.sort,
            limit = limit,
            offset = offset
        )
    }

    override fun countEntries(
        query: String?,
        filter: JournalFilterState
    ): Flow<Int> {
        val safeQuery = escapeSqlLikePattern(query)
        val moodsEmpty = filter.selectedMoods.isEmpty()
        val from = filter.fromDateEpochMs ?: 0L
        val to = filter.toDateEpochMs ?: Long.MAX_VALUE

        return journalDao.countEntries(
            query = safeQuery,
            moods = filter.selectedMoods,
            moodsEmpty = moodsEmpty,
            hasPhoto = filter.hasPhoto,
            hasAudio = filter.hasAudio,
            from = from,
            to = to
        )
    }

    override fun observeMoodByDay(from: Long, to: Long): Flow<List<DayMood>> {
        return journalDao.moodByDay(from, to)
    }

    override fun observeTopTags(limit: Int): Flow<List<TagCount>> {
        return journalDao.topTags(limit)
    }

    override fun observeDistinctTags(): Flow<List<String>> {
        return journalDao.distinctTags()
    }

    override suspend fun getEntryById(id: Long): JournalEntryWithDetails? =
        withContext(dispatcher) {
            journalDao.getEntryById(id)
        }

    override suspend fun saveEntry(
        entry: JournalEntryEntity,
        attachments: List<JournalAttachmentEntity>,
        tags: List<String>,
        stagedFilesToKeep: List<String>,
        oldFilesToDeleteAfterCommit: List<String>
    ): Result<Long> = withContext(dispatcher) {
        deletionMutex.withLock {
            checkWriter()
            try {
                val savedId = journalDao.save(entry, attachments, tags)
                pendingStagedFiles.removeAll(attachments.map { it.localPath }.toSet())
                // Lưu SQLite thành công -> Xóa an toàn các file cũ đã bị người dùng gỡ bỏ
                try {
                    deleteUnreferencedFiles(oldFilesToDeleteAfterCommit)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(
                        "JournalRepository",
                        "Committed entry; attachment cleanup will retry after draft clear",
                        e
                    )
                }
                Result.success(savedId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // [Intern]: Khi lưu DB thất bại, TUYỆT ĐỐI KHÔNG xóa stagedFilesToKeep!
                // Giải thích: Bản nháp và màn hình Editor vẫn giữ danh sách tệp đính kèm này.
                // Nếu ta xóa tệp trên đĩa, khi người dùng bấm "Lưu" lại (Retry) thì Room sẽ lưu đường dẫn
                // trỏ vào một tệp không còn tồn tại trên bộ nhớ. Việc xóa file mồ côi (orphan) chỉ được
                // thực hiện khi người dùng chủ động Bỏ bản nháp (Discard) hoặc qua tiến trình dọn dẹp riêng.
                Result.failure(e)
            }
        }
    }

    override suspend fun togglePin(id: Long): Result<Unit> = withContext(dispatcher) {
        try {
            journalDao.togglePin(id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun softDelete(id: Long): Result<Unit> = withContext(dispatcher) {
        try {
            journalDao.softDelete(id, System.currentTimeMillis())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun undoDelete(id: Long): Result<Unit> = withContext(dispatcher) {
        deletionMutex.withLock {
            try {
                check(
                    journalDao.undoDeleteBeforeDeadline(
                        id,
                        System.currentTimeMillis() - JournalDeletionScheduler.UNDO_MS
                    ) > 0
                ) {
                    "Undo deadline expired"
                }
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        }
    }

    override suspend fun hardDelete(id: Long): Result<Unit> = withContext(dispatcher) {
        deletionMutex.withLock {
            try {
                val row =
                    journalDao.getEntryByIdIncludingDeleted(id) ?: return@withLock Result.success(
                        Unit
                    )
                val deletedAt = row.entry.deletedAt ?: return@withLock Result.success(Unit)
                if (System.currentTimeMillis() < deletedAt + JournalDeletionScheduler.UNDO_MS) return@withLock Result.success(
                    Unit
                )
                // Cùng khóa với Undo: job cũ không thể xóa bài vừa được phục hồi.
                // Giữ metadata tới khi dọn file xong để lỗi I/O còn có thể retry.
                val draftPaths =
                    journalDataStore.getDraft()?.attachments.orEmpty().map { it.localPath }.toSet()
                row.attachments.forEach { att ->
                    if (att.localPath !in draftPaths && journalDao.countAttachmentReferences(att.localPath) <= 1) {
                        safelyDeleteAppOwnedFile(att.localPath)
                    }
                }
                journalDao.hardDelete(id)
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        }
    }

    override suspend fun purgeExpiredDeleted(thresholdEpochMs: Long): Result<Int> =
        withContext(dispatcher) {
            try {
                var count = 0
                for (entry in journalDao.getExpiredDeletedEntries(thresholdEpochMs)) {
                    hardDelete(entry.id).getOrThrow()
                    count++
                }
                Result.success(count)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        }

    override suspend fun getEntriesForStats(from: Long?, to: Long?): List<JournalEntryWithDetails> =
        withContext(dispatcher) {
            if (from != null && to != null) {
                journalDao.getEntriesForPeriod(from, to)
            } else {
                journalDao.getAllEntries()
            }
        }

    override fun observeDraft(): Flow<JournalDraft?> = journalDataStore.draftFlow

    override suspend fun getDraft(): JournalDraft? = withContext(dispatcher) {
        journalDataStore.getDraft()
    }

    override suspend fun saveDraft(draft: JournalDraft) = withContext(dispatcher) {
        deletionMutex.withLock {
            checkWriter()
            journalDataStore.saveDraft(draft)
            pendingStagedFiles.removeAll(
            draft.attachments.map { it.localPath }.toSet()
        )
        Unit
        }
    }

    override suspend fun clearDraft() = withContext(dispatcher) {
        deletionMutex.withLock { checkWriter()
        journalDataStore.clearDraft() }
    }

    override fun observeFilter(): Flow<JournalFilterState> = journalDataStore.filterFlow

    override suspend fun getFilter(): JournalFilterState = withContext(dispatcher) {
        journalDataStore.getFilter()
    }

    override suspend fun saveFilter(filter: JournalFilterState) = withContext(dispatcher) {
        journalDataStore.saveFilter(filter)
    }

    override suspend fun resetFilter() = withContext(dispatcher) {
        journalDataStore.resetFilter()
    }

    override suspend fun deleteUnreferencedFiles(paths: List<String>) = withContext(dispatcher) {
        val draftPaths =
            journalDataStore.getDraft()?.attachments.orEmpty().map { it.localPath }.toSet()
        for (path in paths.distinct()) {
            if (path !in draftPaths && journalDao.countAttachmentReferences(path) == 0) {
                safelyDeleteAppOwnedFile(path)
                pendingStagedFiles.remove(path)
            }
        }
    }

    override suspend fun readAudioMetadata(path: String) =
        withContext(dispatcher) { JournalAudioMetadataReader.read(path) }

    override suspend fun stageAttachmentFile(
        sourcePathOrUri: String,
        isAudio: Boolean
    ): Result<String> {
        var target: File? = null
        try {
            return withContext(dispatcher) {
                deletionMutex.withLock {
                    // Cùng khóa với prepare/delete: copy đến muộn không tạo file sau logout.
                    checkWriter()
                    val directory = File(
                        context.filesDir,
                        if (isAudio) "recordings" else "journal"
                    ).also { check(it.exists() || it.mkdirs()) }
                    val uri = Uri.parse(sourcePathOrUri)
                    val extension = if (uri.scheme == "content") {
                        context.contentResolver.getType(uri)?.let {
                            MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
                        }?.let { ".$it" }
                    } else getExtension(sourcePathOrUri)
                    val file = File(
                        directory,
                        "journal_${UUID.randomUUID()}${extension ?: if (isAudio) ".audio" else ".jpg"}"
                    )
                    target = file
                    val input = if (uri.scheme in listOf(
                            "content",
                            "file",
                            "android.resource"
                        )
                    ) context.contentResolver.openInputStream(uri)
                    else File(sourcePathOrUri).inputStream()
                    requireNotNull(input) { "Cannot open attachment" }.use { source ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    pendingStagedFiles.add(file.absolutePath)
                    Result.success(file.absolutePath)
                }
            }
        } catch (e: Exception) {
            // Bắt ở ngoài withContext để cả cancellation lúc dispatch kết quả về Main cũng dọn được file.
            withContext(NonCancellable + dispatcher) {
                target?.let {
                    it.delete()
                    pendingStagedFiles.remove(
                    it.absolutePath
                )
                }
            }
            if (e is CancellationException) throw e
            return Result.failure(e)
        }
    }

    /**
     * Dọn dẹp tệp an toàn: Chỉ cho phép xóa tệp nếu nó thực sự nằm trong thư mục `filesDir` của ứng dụng.
     */
    private fun safelyDeleteAppOwnedFile(path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(path).canonicalFile
        val roots = listOf(
            File(context.filesDir, "journal").canonicalFile,
            File(context.filesDir, "recordings").canonicalFile
        )
        if (roots.none { file.path.startsWith(it.path + File.separator) }) return
        if (file.exists() && !file.delete()) throw IOException("Cannot delete owned attachment")
    }

    private fun getExtension(pathOrUri: String): String {
        val lastDot = pathOrUri.lastIndexOf('.')
        if (lastDot > 0 && lastDot < pathOrUri.length - 1) {
            val ext = pathOrUri.substring(lastDot + 1).takeWhile { it.isLetterOrDigit() }
            if (ext.length in 2..5) return "." + ext
        }
        return ".jpg"
    }

    private fun escapeSqlLikePattern(query: String?): String? {
        if (query.isNullOrBlank()) return null
        return query.trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
}