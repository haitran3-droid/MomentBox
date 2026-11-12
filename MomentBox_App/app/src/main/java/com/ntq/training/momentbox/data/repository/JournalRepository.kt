package com.ntq.training.momentbox.data.repository

import com.ntq.training.momentbox.data.local.db.DayMood
import com.ntq.training.momentbox.data.local.db.JournalAttachmentEntity
import com.ntq.training.momentbox.data.local.db.JournalEntryEntity
import com.ntq.training.momentbox.data.local.db.JournalEntryWithDetails
import com.ntq.training.momentbox.data.local.db.TagCount
import com.ntq.training.momentbox.data.local.prefs.JournalDraft
import com.ntq.training.momentbox.data.local.prefs.JournalFilterState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.collections.emptyList

interface JournalRepository {
    /** Snapshot nhiều trang trong một transaction. Null cho repository test không có Room. */
    fun observeAtomicPages(
        query: String?,
        filter: JournalFilterState,
        pages: Int
    ): Flow<JournalPageSnapshot>? = null

    fun observeDayCounts(query: String?, filter: JournalFilterState): Flow<List<JournalDayCount>> =
        flowOf(emptyList())

    fun observeWrittenDays(): Flow<List<String>> = kotlinx.coroutines.flow.flow {
        emit(getEntriesForStats().map {
            Instant.ofEpochMilli(it.entry.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
                .toString()
        }.distinct())
    }

    val handlesDeletionDeadlines: Boolean get() = false
    fun observePendingDeletes(): Flow<List<PendingJournalDelete>> = flowOf(emptyList())

    /**
     * Lấy dòng dữ liệu danh sách bài viết theo bộ lọc và phân trang.
     */
    fun observeEntries(
        query: String?,
        filter: JournalFilterState,
        limit: Int = 20,
        offset: Int = 0
    ): Flow<List<JournalEntryWithDetails>>

    /**
     * Đếm tổng số bài viết thỏa mãn tiêu chí lọc để cập nhật huy hiệu CTA S07b và số lượng S07.
     */
    fun countEntries(query: String?, filter: JournalFilterState): Flow<Int>
    fun observeMoodByDay(from: Long, to: Long): Flow<List<DayMood>>
    fun observeTopTags(limit: Int = 5): Flow<List<TagCount>>
    fun observeDistinctTags(): Flow<List<String>>
    suspend fun getEntryById(id: Long): JournalEntryWithDetails?
    suspend fun saveEntry(
        entry: JournalEntryEntity,
        attachments: List<JournalAttachmentEntity>,
        tags: List<String>,
        stagedFilesToKeep: List<String> = emptyList(),
        oldFilesToDeleteAfterCommit: List<String> = emptyList()
    ): Result<Long>

    suspend fun togglePin(id: Long): Result<Unit>
    suspend fun softDelete(id: Long): Result<Unit>
    suspend fun undoDelete(id: Long): Result<Unit>
    suspend fun hardDelete(id: Long): Result<Unit>
    suspend fun purgeExpiredDeleted(thresholdEpochMs: Long): Result<Int>
    suspend fun getEntriesForStats(
        from: Long? = null,
        to: Long? = null
    ): List<JournalEntryWithDetails>

    suspend fun prepareForLogout() {}
    suspend fun finishLogout(keepJournal: Boolean) {
        check(keepJournal) { "JOURNAL_DELETE_UNSUPPORTED" }
    }

    fun resumeAfterLogin() {}
    fun observeDraft(): Flow<JournalDraft?>
    suspend fun getDraft(): JournalDraft?
    suspend fun saveDraft(draft: JournalDraft)
    suspend fun clearDraft()

    fun observeFilter(): Flow<JournalFilterState>
    suspend fun getFilter(): JournalFilterState
    suspend fun saveFilter(filter: JournalFilterState)
    suspend fun resetFilter()

    suspend fun readAudioMetadata(path: String): JournalAudioMetadata? = null

    suspend fun deleteUnreferencedFiles(paths: List<String>) {}
    suspend fun stageAttachmentFile(sourcePathOrUri: String, isAudio: Boolean): Result<String>
}

data class JournalDayCount(val day: String, val count: Int)

data class JournalAudioMetadata(val durationMs: Long, val waveform: List<Int>)

data class JournalPageSnapshot(
    val entries: List<JournalEntryWithDetails>,
    val dayCounts: List<JournalDayCount>
)
