package com.ntq.training.momentbox.data.repository

import android.content.Context
import android.net.Uri
import com.ntq.training.momentbox.data.local.db.DayMood
import com.ntq.training.momentbox.data.local.db.JournalAttachmentEntity
import com.ntq.training.momentbox.data.local.db.JournalDao
import com.ntq.training.momentbox.data.local.db.JournalEntryEntity
import com.ntq.training.momentbox.data.local.db.JournalEntryWithDetails
import com.ntq.training.momentbox.data.local.db.TagCount
import com.ntq.training.momentbox.data.local.prefs.JournalDataStore
import com.ntq.training.momentbox.data.local.prefs.JournalDraft
import com.ntq.training.momentbox.data.local.prefs.JournalFilterState
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
) : JournalRepository {
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
        try {
            val savedId = journalDao.save(entry, attachments, tags)
            for (filePath in oldFilesToDeleteAfterCommit) {
                safelyDeleteAppOwnedFile(filePath)
            }
            Result.success(savedId)
        } catch (e: Exception) {
            for (filePath in stagedFilesToKeep) {
                safelyDeleteAppOwnedFile(filePath)
            }
            Result.failure(e)
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

    override suspend fun softDelete(id: Long): Result<Unit> = withContext(dispatcher){
        try{
            journalDao.softDelete(id, System.currentTimeMillis())
            Result.success(Unit)
        }catch (e: Exception){
            Result.failure(e)
        }
    }

    override suspend fun undoDelete(id: Long): Result<Unit> = withContext(dispatcher){
        try{
            journalDao.undoDelete(id)
            Result.success(Unit)
        }catch (e: Exception){
            Result.failure(e)
        }
    }

    override suspend fun hardDelete(id: Long): Result<Unit> = withContext(dispatcher){
        try {
            val attachments = journalDao.getAttachmentsByEntryId(id)
            journalDao.hardDelete(id)
            for(att in attachments){
                safelyDeleteAppOwnedFile(att.localPath)
            }
            Result.success(Unit)
        }catch (e: Exception){
            Result.failure(e)
        }
    }

    override suspend fun purgeExpiredDeleted(thresholdEpochMs: Long): Result<Int> = withContext(dispatcher){
        try{
            val expiredEntries = journalDao.getExpiredDeletedEntries(thresholdEpochMs)
            for(entry in expiredEntries){
                val attachments = journalDao.getAttachmentsByEntryId(entry.id)
                for(att in attachments){
                    safelyDeleteAppOwnedFile(att.localPath)
                }
            }
            val deletedCount = journalDao.purgeExpiredDeleted(thresholdEpochMs)
            Result.success(deletedCount)
        }catch (e: Exception){
            Result.failure(e)
        }
    }

    override suspend fun getEntriesForStats(
        from: Long?,
        to: Long?
    ): List<JournalEntryWithDetails> = withContext(dispatcher){
        if(from != null && to != null){
            journalDao.getEntriesForPeriod(from, to)
        }else{
            journalDao.getAllEntries()
        }
    }

    override fun observeDraft(): Flow<JournalDraft?> = journalDataStore.draftFlow

    override suspend fun getDraft(): JournalDraft? = withContext(dispatcher){
        journalDataStore.getDraft()
    }

    override suspend fun saveDraft(draft: JournalDraft) = withContext(dispatcher) {
        journalDataStore.saveDraft(draft)
    }

    override suspend fun clearDraft() = withContext(dispatcher) {
        journalDataStore.clearDraft()
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

    override suspend fun stageAttachmentFile(sourcePathOrUri: String, isAudio: Boolean): Result<String> = withContext(dispatcher) {
        try {
            val subDirName = if (isAudio) "recordings" else "journal"
            val targetDir = File(context.filesDir, subDirName)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val prefix = if (isAudio) "journal_audio_" else "journal_img_"
            val extension = if (isAudio) ".wav" else getExtension(sourcePathOrUri)
            val fileName = "${prefix}${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}$extension"
            val targetFile = File(targetDir, fileName)

            val uri = Uri.parse(sourcePathOrUri)
            val inputStream = if (uri.scheme == "content" || uri.scheme == "file" || uri.scheme == "android.resource") {
                context.contentResolver.openInputStream(uri)
            } else {
                val directFile = File(sourcePathOrUri)
                if (directFile.exists()) directFile.inputStream() else null
            } ?: return@withContext Result.failure(IllegalArgumentException("Không thể mở nguồn tệp: $sourcePathOrUri"))

            inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            Result.success(targetFile.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun safelyDeleteAppOwnedFile(path: String?) {
        if (path.isNullOrBlank()) return
        try {
            val file = File(path)
            val canonicalAppDir = context.filesDir.canonicalPath
            val canonicalFilePath = file.canonicalPath
            if (canonicalFilePath.startsWith(canonicalAppDir) && file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun getExtension(pathOrUri: String): String {
        val lastDot = pathOrUri.lastIndexOf('.')
        if (lastDot > 0 && lastDot < pathOrUri.length - 1) {
            val ext = pathOrUri.substring(lastDot).takeWhile { it.isLetterOrDigit() }
            if (ext.length in 2..5) return ext
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