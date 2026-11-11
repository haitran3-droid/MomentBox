package com.ntq.training.momentbox.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ChatMemberDto(
    @SerializedName("id") val id: Long,
    @SerializedName("displayName") val displayName: String?,
    @SerializedName("avatarUrl") val avatarUrl: String?,
    @SerializedName("online") val online: Boolean = false,
    @SerializedName("lastSeenAt") val lastSeenAt: String? = null
)

data class ChatMessageDto(
    @SerializedName("id") val id: Long,
    @SerializedName("clientId") val clientId: String?,
    @SerializedName("conversationId") val conversationId: Long,
    @SerializedName("senderId") val senderId: Long,
    @SerializedName("type") val type: String,
    @SerializedName("content") val content: String,
    @SerializedName("mediaUrl") val mediaUrl: String?,
    @SerializedName("durationMs") val durationMs: Long?,
    @SerializedName("status") val status: String,
    @SerializedName("createdAt") val createdAt: String
)

data class ConversationDto(
    @SerializedName("id") val id: Long,
    @SerializedName("type") val type: String,
    @SerializedName("title") val title: String,
    @SerializedName("avatarUrl") val avatarUrl: String?,
    @SerializedName("members") val members: List<ChatMemberDto>,
    @SerializedName("lastMessage") val lastMessage: ChatMessageDto?,
    @SerializedName("unreadCount") val unreadCount: Int,
    @SerializedName("updatedAt") val updatedAt: String?
)

data class CreateConversationBody(@SerializedName("userId") val userId: Long)
