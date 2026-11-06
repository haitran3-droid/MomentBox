package com.ntq.training.momentbox.data.repository

import com.ntq.training.momentbox.data.local.db.DayMood
import com.ntq.training.momentbox.data.local.db.JournalAttachmentEntity
import com.ntq.training.momentbox.data.local.db.JournalEntryEntity
import com.ntq.training.momentbox.data.local.db.JournalEntryWithDetails
import com.ntq.training.momentbox.data.local.db.TagCount
import com.ntq.training.momentbox.data.local.prefs.JournalDraft
import com.ntq.training.momentbox.data.local.prefs.JournalFilterState
import kotlinx.coroutines.flow.Flow

interface JournalRepository {
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

    fun observeDraft(): Flow<JournalDraft?>
    suspend fun getDraft(): JournalDraft?
    suspend fun saveDraft(draft: JournalDraft)
    suspend fun clearDraft()

    fun observeFilter(): Flow<JournalFilterState>
    suspend fun getFilter(): JournalFilterState
    suspend fun saveFilter(filter: JournalFilterState)
    suspend fun resetFilter()

    suspend fun stageAttachmentFile(sourcePathOrUri: String, isAudio: Boolean): Result<String>
}