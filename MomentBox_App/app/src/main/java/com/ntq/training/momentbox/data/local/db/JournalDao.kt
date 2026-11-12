package com.ntq.training.momentbox.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.ntq.training.momentbox.data.repository.JournalDayCount
import com.ntq.training.momentbox.data.repository.JournalPageSnapshot
import com.ntq.training.momentbox.data.repository.PendingJournalDelete
import kotlinx.coroutines.flow.Flow

@Dao
interface JournalDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntry(entry: JournalEntryEntity): Long

    @Update
    suspend fun updateEntry(entry: JournalEntryEntity): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachment(attachment: List<JournalAttachmentEntity>): List<Long>

    @Query("Delete from journal_attachments where entry_id = :entryId")
    suspend fun deleteAttachmentsByEntryId(entryId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<JournalTagEntity>): List<Long>

    @Query("Delete from journal_tags where entry_id = :entryId")
    suspend fun deleteTagsByEntryId(entryId: Long): Int

    @Query("Select * from journal_attachments where entry_id = :entryId order by sort_order asc")
    suspend fun getAttachmentsByEntryId(entryId: Long): List<JournalAttachmentEntity>

    @Query("Select * from journal_tags where entry_id = :entryId order by tag asc")
    suspend fun getTagsByEntryId(entryId: Long): List<JournalTagEntity>

    @Query("Select count(*) from journal_attachments where local_path = :path")
    suspend fun countAttachmentReferences(path: String): Int

    @Transaction
    suspend fun save(
        entry: JournalEntryEntity,
        attachments: List<JournalAttachmentEntity>,
        tags: List<String>
    ): Long {
        val calculateWordCount = if (entry.content.isBlank()) 0 else {
            entry.content.trim().split("\\s+".toRegex()).count { it.isNotBlank() }
        }
        val calculatedPhotoCount = attachments.count { it.type.equals("image", ignoreCase = true) }
        val calculatedAudioCount = attachments.count { it.type.equals("audio", ignoreCase = true) }

        val entryToSave = entry.copy(
            wordCount = calculateWordCount,
            photoCount = calculatedPhotoCount,
            audioCount = calculatedAudioCount,
            updatedAt = System.currentTimeMillis()
        )

        val targetEntryId: Long
        if (entryToSave.id == 0L) {
            targetEntryId = insertEntry(entryToSave)
        } else {
            targetEntryId = entryToSave.id
            val existing = getEntryByIdIncludingDeleted(targetEntryId)
            check(existing?.entry?.deletedAt == null) { "Entry is pending deletion" }
            val preserved =
                entryToSave.copy(isPinned = existing?.entry?.isPinned ?: entryToSave.isPinned)
            if (updateEntry(preserved) == 0) insertEntry(preserved)
            // Thay thế attachments và tags để tránh xung đột id/sortOrder
            deleteAttachmentsByEntryId(targetEntryId)
            deleteTagsByEntryId(targetEntryId)
        }

        if (attachments.isNotEmpty()) {
            val indexedAttachments = attachments.mapIndexed { index, item ->
                item.copy(
                    id = 0,
                    entryId = targetEntryId,
                    sortOrder = if (item.sortOrder != 0) item.sortOrder else index
                )
            }
            insertAttachment(indexedAttachments)
        }
        if (tags.isNotEmpty()) {
            val distinctCleanTags = tags
                .map { it.trim().removePrefix("#").trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .map { JournalTagEntity(entryId = targetEntryId, tag = it) }
            if (distinctCleanTags.isNotEmpty()) {
                insertTags(distinctCleanTags)
            }
        }
        return targetEntryId
    }

    @Transaction
    @Query("Select * from journal_entries where id = :id and deleted_at is null")
    suspend fun getEntryById(id: Long): JournalEntryWithDetails?

    @Transaction
    @Query("Select * from journal_entries where id = :id")
    suspend fun getEntryByIdIncludingDeleted(id: Long): JournalEntryWithDetails?

    /**
     * Truy vấn danh sách bài viết theo bộ lọc và sắp xếp
     */
    @Transaction
    @Query(
        """
        SELECT * FROM journal_entries
        WHERE deleted_at IS NULL
          AND (:query IS NULL OR :query = '' OR title LIKE '%' || :query || '%' ESCAPE '\'
               OR content LIKE '%' || :query || '%' ESCAPE '\'
               OR id IN (SELECT entry_id FROM journal_tags WHERE tag LIKE '%' || :query || '%' ESCAPE '\'))
          AND (:moodsEmpty = 1 OR mood IN (:moods))
          AND (:hasPhoto = 0 OR photo_count > 0)
          AND (:hasAudio = 0 OR audio_count > 0)
          AND created_at BETWEEN :from AND :to
        ORDER BY is_pinned DESC,
          CASE WHEN :sort = 'OLDEST' THEN created_at END ASC,
          CASE WHEN :sort = 'MOOD' THEN mood END ASC,
          created_at DESC,
          id DESC
        LIMIT :limit OFFSET :offset
    """
    )
    fun observeEntries(
        query: String?,
        moods: List<Int>,
        moodsEmpty: Boolean,
        hasPhoto: Boolean,
        hasAudio: Boolean,
        from: Long,
        to: Long,
        sort: String,
        limit: Int,
        offset: Int
    ): Flow<List<JournalEntryWithDetails>>

    /**
     * Đếm tổng số bài viết thỏa mãn chính xác tiêu chí lọc
     */
    @Query(
        """
        SELECT COUNT(*) FROM journal_entries
        WHERE deleted_at IS NULL
          AND (:query IS NULL OR :query = '' OR title LIKE '%' || :query || '%' ESCAPE '\'
               OR content LIKE '%' || :query || '%' ESCAPE '\'
               OR id IN (SELECT entry_id FROM journal_tags WHERE tag LIKE '%' || :query || '%' ESCAPE '\'))
          AND (:moodsEmpty = 1 OR mood IN (:moods))
          AND (:hasPhoto = 0 OR photo_count > 0)
          AND (:hasAudio = 0 OR audio_count > 0)
          AND created_at BETWEEN :from AND :to
    """
    )
    fun countEntries(
        query: String?,
        moods: List<Int>,
        moodsEmpty: Boolean,
        hasPhoto: Boolean,
        hasAudio: Boolean,
        from: Long,
        to: Long
    ): Flow<Int>

    @Query("SELECT COUNT(*) FROM journal_entries WHERE deleted_at IS NULL AND created_at >= :start AND created_at < :endExclusive")
    suspend fun countNonDeletedInRange(start: Long, endExclusive: Long): Int

    /**
     * Lấy danh sách cảm xúc theo ngày trong khoảng thời gian xác định (theo 'local time')
     * phục vụ vẽ các ô ngày có màu trên moodcalendarview
     */
    @Query(
        """
        SELECT strftime('%Y-%m-%d', created_at / 1000, 'unixepoch', 'localtime') AS day,
               mood,
               created_at AS createdAt
        FROM journal_entries
        WHERE deleted_at IS NULL AND created_at BETWEEN :from AND :to
        ORDER BY created_at ASC
    """
    )
    fun moodByDay(from: Long, to: Long): Flow<List<DayMood>>

    /**
     * Lấy danh sách các nhãn được dùng nhiều nhất (Top Tags), loại trừ các bài viết đã bị xóa mềm.
     */
    @Query(
        """
        SELECT t.tag, COUNT(*) AS cnt
        FROM journal_tags t
        INNER JOIN journal_entries e ON t.entry_id = e.id
        WHERE e.deleted_at IS NULL
        GROUP BY t.tag
        ORDER BY cnt DESC, t.tag ASC
        LIMIT :limit
    """
    )
    fun topTags(limit: Int): Flow<List<TagCount>>

    /**
     * Gợi ý danh sách tất cả các nhãn phân biệt để hiển thị autocomplete/chip trong S08.
     */
    @Query(
        """
        SELECT DISTINCT t.tag
        FROM journal_tags t
        INNER JOIN journal_entries e ON t.entry_id = e.id
        WHERE e.deleted_at IS NULL
        ORDER BY t.tag ASC
    """
    )
    fun distinctTags(): Flow<List<String>>

    /**
     * Lấy toàn bộ danh sách bài viết không bị xóa mềm để JournalStatsCalculator tính toán chỉ số thống kê.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM journal_entries
        WHERE deleted_at IS NULL AND created_at BETWEEN :from AND :to
        ORDER BY created_at ASC
    """
    )
    suspend fun getEntriesForPeriod(from: Long, to: Long): List<JournalEntryWithDetails>

    @Transaction
    @Query("SELECT * FROM journal_entries WHERE deleted_at IS NULL ORDER BY created_at ASC")
    suspend fun getAllEntries(): List<JournalEntryWithDetails>

    @Transaction
    @Query("SELECT * FROM journal_entries WHERE deleted_at IS NULL AND audio_count > 0 ORDER BY created_at DESC, id DESC LIMIT :limit")
    suspend fun audioLibrary(limit: Int): List<JournalEntryWithDetails>

    @Transaction
    @Query("SELECT e.* FROM journal_entries e INNER JOIN journal_attachments a ON a.entry_id = e.id WHERE e.deleted_at IS NULL AND a.type = 'audio' AND a.id = :attachmentId LIMIT 1")
    suspend fun audioEntryByAttachment(attachmentId: Long): JournalEntryWithDetails?

    // === THAO TÁC GHIM & XÓA MỀM / HOÀN TÁC (PIN, SOFT DELETE & UNDO) ===

    /**
     * Đảo trạng thái ghim bài viết lên đầu danh sách.
     */
    @Query("UPDATE journal_entries SET is_pinned = CASE WHEN is_pinned = 1 THEN 0 ELSE 1 END WHERE id = :id")
    suspend fun togglePin(id: Long): Int

    /**
     * Đánh dấu xóa mềm bằng cách gán mốc thời gian [deletedAt].
     */
    @Query("UPDATE journal_entries SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Long, deletedAt: Long): Int

    /**
     * Hoàn tác xóa mềm: Gán `deleted_at = NULL` để khôi phục bài viết.
     */
    @Query("UPDATE journal_entries SET deleted_at = NULL WHERE id = :id")
    suspend fun undoDelete(id: Long): Int

    /**
     * Xóa hoàn toàn bản ghi khỏi cơ sở dữ liệu sau khi hết thời hạn Undo 5 giây.
     */
    @Query("DELETE FROM journal_entries WHERE id = :id")
    suspend fun hardDelete(id: Long): Int

    /**
     * Dọn dẹp tất cả bài viết xóa mềm đã quá hạn [threshold].
     */
    @Query("DELETE FROM journal_entries WHERE deleted_at IS NOT NULL AND deleted_at <= :threshold")
    suspend fun purgeExpiredDeleted(threshold: Long): Int

    @Query("SELECT id, deleted_at AS deletedAt FROM journal_entries WHERE deleted_at IS NOT NULL")
    fun observePendingDeletes(): Flow<List<PendingJournalDelete>>

    @Query("UPDATE journal_entries SET deleted_at = NULL WHERE id = :id AND deleted_at > :threshold")
    suspend fun undoDeleteBeforeDeadline(id: Long, threshold: Long): Int

    @Query("SELECT * FROM journal_entries WHERE deleted_at IS NOT NULL AND deleted_at <= :threshold")
    suspend fun getExpiredDeletedEntries(threshold: Long): List<JournalEntryEntity>

    @Query(
        """
        SELECT strftime('%Y-%m-%d', created_at / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS count
        FROM journal_entries
        WHERE deleted_at IS NULL
          AND (:query IS NULL OR :query = '' OR title LIKE '%' || :query || '%' ESCAPE '\'
               OR content LIKE '%' || :query || '%' ESCAPE '\'
               OR id IN (SELECT entry_id FROM journal_tags WHERE tag LIKE '%' || :query || '%' ESCAPE '\'))
          AND (:moodsEmpty = 1 OR mood IN (:moods))
          AND (:hasPhoto = 0 OR photo_count > 0)
          AND (:hasAudio = 0 OR audio_count > 0)
          AND created_at BETWEEN :from AND :to
        GROUP BY day
    """
    )
    fun observeDayCounts(
        query: String?, moods: List<Int>, moodsEmpty: Boolean, hasPhoto: Boolean,
        hasAudio: Boolean, from: Long, to: Long
    ): Flow<List<JournalDayCount>>

    @Query("SELECT DISTINCT strftime('%Y-%m-%d', created_at / 1000, 'unixepoch', 'localtime') FROM journal_entries WHERE deleted_at IS NULL")
    fun observeWrittenDays(): Flow<List<String>>

    @RawQuery(observedEntities = [JournalEntryEntity::class, JournalAttachmentEntity::class, JournalTagEntity::class])
    fun observeJournalChanges(query: SupportSQLiteQuery): Flow<Int>

    @Transaction
    @Query(
        """
        SELECT * FROM journal_entries
        WHERE deleted_at IS NULL
          AND (:query IS NULL OR :query = '' OR title LIKE '%' || :query || '%' ESCAPE '\'
               OR content LIKE '%' || :query || '%' ESCAPE '\'
               OR id IN (SELECT entry_id FROM journal_tags WHERE tag LIKE '%' || :query || '%' ESCAPE '\'))
          AND (:moodsEmpty = 1 OR mood IN (:moods))
          AND (:hasPhoto = 0 OR photo_count > 0)
          AND (:hasAudio = 0 OR audio_count > 0)
          AND created_at BETWEEN :from AND :to
        ORDER BY is_pinned DESC,
          CASE WHEN :sort = 'OLDEST' THEN created_at END ASC,
          CASE WHEN :sort = 'MOOD' THEN mood END ASC,
          created_at DESC,
          id DESC
        LIMIT :limit OFFSET :offset
    """
    )
    suspend fun readEntryPage(
        query: String?, moods: List<Int>, moodsEmpty: Boolean, hasPhoto: Boolean, hasAudio: Boolean,
        from: Long, to: Long, sort: String, limit: Int, offset: Int
    ): List<JournalEntryWithDetails>

    @Query(
        """
        SELECT strftime('%Y-%m-%d', created_at / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS count
        FROM journal_entries
        WHERE deleted_at IS NULL
          AND (:query IS NULL OR :query = '' OR title LIKE '%' || :query || '%' ESCAPE '\'
               OR content LIKE '%' || :query || '%' ESCAPE '\'
               OR id IN (SELECT entry_id FROM journal_tags WHERE tag LIKE '%' || :query || '%' ESCAPE '\'))
          AND (:moodsEmpty = 1 OR mood IN (:moods))
          AND (:hasPhoto = 0 OR photo_count > 0)
          AND (:hasAudio = 0 OR audio_count > 0)
          AND created_at BETWEEN :from AND :to
        GROUP BY day
    """
    )
    suspend fun readDayCounts(
        query: String?, moods: List<Int>, moodsEmpty: Boolean, hasPhoto: Boolean,
        hasAudio: Boolean, from: Long, to: Long
    ): List<JournalDayCount>

    @Transaction
    suspend fun readPageSnapshot(
        query: String?, moods: List<Int>, moodsEmpty: Boolean, hasPhoto: Boolean, hasAudio: Boolean,
        from: Long, to: Long, sort: String, pages: Int
    ): JournalPageSnapshot {
        // Invalidation có thể đến từ pin/update giữa các trang. Cùng transaction tránh trộn trang mới/cũ.
        val entries = (0 until pages).flatMap { page ->
            readEntryPage(
                query,
                moods,
                moodsEmpty,
                hasPhoto,
                hasAudio,
                from,
                to,
                sort,
                20,
                page * 20
            )
        }
        return JournalPageSnapshot(
            entries,
            readDayCounts(query, moods, moodsEmpty, hasPhoto, hasAudio, from, to)
        )
    }

    @Query("SELECT DISTINCT local_path FROM journal_attachments")
    suspend fun attachmentPathsForLogout(): List<String>

    @Query("DELETE FROM journal_entries")
    suspend fun deleteAllForLogout(): Int
}
