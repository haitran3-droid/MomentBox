package com.ntq.training.momentbox.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Insert
import androidx.room.Update
import androidx.room.OnConflictStrategy
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class ConversationPreview(
    @Embedded val conversation: ConversationEntity,
    @ColumnInfo(name = "preview_status") val status: String?,
    @ColumnInfo(name = "preview_duration") val duration: Long?
)

@Dao
abstract class ChatDao {
    @Query("SELECT MAX(server_id) FROM messages WHERE owner_id=:owner AND source=:source AND conversation_id=:conversation")
    abstract suspend fun newestServerId(owner: String, source: String, conversation: Long): Long?

    @Query("SELECT * FROM conversations WHERE owner_id=:owner AND source=:source AND id=:id")
    abstract fun conversationFlow(
        owner: String,
        source: String,
        id: Long
    ): Flow<ConversationEntity?>

    @Query("SELECT c.*, (SELECT status FROM messages m WHERE m.owner_id=c.owner_id AND m.source=c.source AND m.conversation_id=c.id ORDER BY created_at DESC,COALESCE(server_id,0) DESC,local_id DESC LIMIT 1) AS preview_status, (SELECT duration_ms FROM messages m WHERE m.owner_id=c.owner_id AND m.source=c.source AND m.conversation_id=c.id ORDER BY created_at DESC,COALESCE(server_id,0) DESC,local_id DESC LIMIT 1) AS preview_duration FROM conversations c WHERE c.owner_id=:owner AND c.source=:source AND c.title LIKE '%' || :search || '%' ORDER BY c.updated_at DESC,c.id DESC")
    abstract fun previews(
        owner: String,
        source: String,
        search: String
    ): Flow<List<ConversationPreview>>

    @Query("SELECT * FROM conversations WHERE owner_id=:owner AND source=:source AND title LIKE '%' || :search || '%' ORDER BY updated_at DESC,id DESC")
    abstract fun conversations(
        owner: String,
        source: String,
        search: String = ""
    ): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM messages WHERE owner_id=:owner AND source=:source AND conversation_id=:conversation ORDER BY created_at DESC,COALESCE(server_id,0) DESC,local_id DESC LIMIT :limit")
    abstract fun messages(
        owner: String,
        source: String,
        conversation: Long,
        limit: Int = 60
    ): Flow<List<MessageEntity>>

    @Query("SELECT * FROM conversations WHERE owner_id=:owner AND source=:source AND id=:id")
    abstract suspend fun conversation(owner: String, source: String, id: Long): ConversationEntity?

    @Query("SELECT * FROM messages WHERE owner_id=:owner AND source=:source AND ((client_id IS NOT NULL AND client_id=:clientId) OR (server_id IS NOT NULL AND server_id=:serverId))")
    abstract suspend fun matching(
        owner: String,
        source: String,
        clientId: String?,
        serverId: Long?
    ): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE owner_id=:owner AND source=:source AND conversation_id=:conversation AND CAST(sender_id AS TEXT)=:owner AND server_id<=:through")
    abstract suspend fun outgoingThrough(
        owner: String,
        source: String,
        conversation: Long,
        through: Long
    ): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE owner_id=:owner AND source=:source AND local_id=:id")
    abstract suspend fun findLocal(owner: String, source: String, id: String): MessageEntity?

    @Query("UPDATE messages SET status='SEEN' WHERE owner_id=:owner AND source=:source AND conversation_id=:conversation AND CAST(sender_id AS TEXT)!=:owner AND server_id<=:through")
    abstract suspend fun markIncomingSeen(
        owner: String,
        source: String,
        conversation: Long,
        through: Long
    ): Int

    @Query("SELECT COUNT(*) FROM messages WHERE owner_id=:owner AND source=:source AND conversation_id=:conversation AND CAST(sender_id AS TEXT)!=:owner AND status!='SEEN' AND server_id>:through")
    abstract suspend fun unreadAfter(
        owner: String,
        source: String,
        conversation: Long,
        through: Long
    ): Int

    @Upsert
    abstract suspend fun upsertConversations(rows: List<ConversationEntity>): List<Long>
    @Upsert
    abstract suspend fun upsertUsers(rows: List<ChatUserEntity>): List<Long>

    /** Chỉ upsert theo local identity đã chọn ở tầng reconcile. Room 2.6 Upsert có thể
     * bỏ qua conflict unique khác PK mà không báo lỗi; dùng ABORT để không đánh mất ack/echo.
     * Transaction ngăn hai writer cùng thấy localId chưa tồn tại rồi chèn đua nhau. */
    @Transaction
    open suspend fun upsertMessage(row: MessageEntity): Long {
        return if (localMessage(row.ownerId, row.source, row.localId) == null) insertMessage(row)
        else updateMessage(row).toLong()
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMessage(row: MessageEntity): Long

    @Update(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun updateMessage(row: MessageEntity): Int

    @Query("SELECT * FROM messages WHERE owner_id=:owner AND source=:source AND local_id=:id")
    protected abstract suspend fun localMessage(
        owner: String,
        source: String,
        id: String
    ): MessageEntity?


    @Query("SELECT SUM(unread_count) FROM conversations WHERE owner_id=:owner AND source=:source")
    abstract fun unread(owner: String, source: String): Flow<Int?>

    @Query("SELECT * FROM users_cache WHERE owner_id=:owner AND source=:source ORDER BY online DESC,display_name ASC,id ASC")
    abstract fun users(owner: String, source: String): Flow<List<ChatUserEntity>>

    @Query("UPDATE users_cache SET online=:online,last_seen_at=:lastSeen WHERE owner_id=:owner AND source=:source AND id=:id")
    abstract suspend fun presence(
        owner: String,
        source: String,
        id: Long,
        online: Boolean,
        lastSeen: Long?
    ): Int

    @Query("DELETE FROM messages WHERE owner_id=:owner AND source=:source AND local_id=:id")
    abstract suspend fun deleteMessage(owner: String, source: String, id: String): Int

    @Query("DELETE FROM messages WHERE owner_id=:owner AND source=:source")
    abstract suspend fun clearMessages(owner: String, source: String): Int

    @Query("DELETE FROM conversations WHERE owner_id=:owner AND source=:source")
    abstract suspend fun clearConversations(owner: String, source: String): Int

    @Query("DELETE FROM users_cache WHERE owner_id=:owner AND source=:source")
    abstract suspend fun clearUsers(owner: String, source: String): Int
}