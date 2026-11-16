package com.ntq.training.momentbox.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "conversations", primaryKeys = ["owner_id", "source", "id"],
    indices = [Index(value = ["owner_id", "source", "updated_at"])]
)
data class ConversationEntity(
    @ColumnInfo(name = "owner_id") val ownerId: String,
    val source: String,
    val id: Long,
    val type: String,
    val title: String,
    @ColumnInfo(name = "avatar_url") val avatarUrl: String?,
    @ColumnInfo(name = "last_message_preview") val lastMessagePreview: String,
    @ColumnInfo(name = "last_message_type") val lastMessageType: String,
    @ColumnInfo(name = "last_sender_id") val lastSenderId: Long?,
    @ColumnInfo(name = "unread_count") val unreadCount: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val muted: Boolean = false,
    @ColumnInfo(name = "members_json") val membersJson: String = "[]"
)

@Entity(
    tableName = "messages", primaryKeys = ["owner_id", "source", "local_id"], indices = [
        Index(value = ["owner_id", "source", "server_id"], unique = true),
        Index(value = ["owner_id", "source", "client_id"], unique = true),
        Index(value = ["owner_id", "source", "conversation_id", "created_at"])
    ]
)
data class MessageEntity(
    @ColumnInfo(name = "owner_id") val ownerId: String,
    val source: String,
    @ColumnInfo(name = "local_id") val localId: String,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    @ColumnInfo(name = "client_id") val clientId: String?,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "sender_id") val senderId: Long,
    val type: String,
    val content: String,
    @ColumnInfo(name = "media_url") val mediaUrl: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "owned_path") val ownedPath: String? = null
)

@Entity(tableName = "users_cache", primaryKeys = ["owner_id", "source", "id"])
data class ChatUserEntity(
    @ColumnInfo(name = "owner_id") val ownerId: String,
    val source: String,
    val id: Long,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "avatar_url") val avatarUrl: String?,
    val online: Boolean,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long?
)