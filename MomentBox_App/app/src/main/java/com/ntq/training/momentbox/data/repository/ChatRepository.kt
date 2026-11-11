package com.ntq.training.momentbox.data.repository

import com.ntq.training.momentbox.data.local.db.AppDatabase
import com.ntq.training.momentbox.data.remote.api.ChatApi
import com.ntq.training.momentbox.data.local.db.ConversationEntity
import com.ntq.training.momentbox.data.local.db.ChatUserEntity
import androidx.room.withTransaction
import com.google.gson.Gson
import com.ntq.training.momentbox.core.network.SessionRequest
import com.ntq.training.momentbox.data.remote.dto.ChatMemberDto
import com.ntq.training.momentbox.data.remote.dto.CreateConversationBody
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.time.Instant
import java.util.Locale
import com.ntq.training.momentbox.data.local.db.MessageEntity
import com.ntq.training.momentbox.data.remote.dto.ChatMessageDto

data class ChatSession(val ownerId: String, val source: String, val generation: Long)

class ChatRepository(
    private val database: AppDatabase,
    private val api: ChatApi,
    private val sessionProvider: suspend () -> ChatSession?
) {
    fun conversations(session: ChatSession, search: String = "") =
        database.chatDao().conversations(session.ownerId, session.source, search)

    suspend fun sync(expected: ChatSession): Boolean {
        if (sessionProvider() != expected) return false
        val response = api.conversations(SessionRequest(expected.generation))
        if (sessionProvider() != expected) return false
        check(response.success && response.data != null) {
            response.error?.code ?: "CHAT_SYNC_FAILED"
        }
        return withContext(Dispatchers.IO) {
            try {
                database.withTransaction {
                    if (sessionProvider() != expected) throw StaleSession()
                    val dao = database.chatDao()
                    for (dto in response.data) {
                        val previous = dao.conversation(expected.ownerId, expected.source, dto.id)
                        val updated = dto.updatedAt?.let { Instant.parse(it).toEpochMilli() } ?: 0L
                        val row = ConversationEntity(
                            expected.ownerId,
                            expected.source,
                            dto.id,
                            dto.type,
                            dto.title,
                            dto.avatarUrl,
                            dto.lastMessage?.content.orEmpty(),
                            dto.lastMessage?.type ?: "text",
                            dto.lastMessage?.senderId,
                            dto.unreadCount.coerceAtLeast(0),
                            updated,
                            previous?.muted ?: false,
                            Gson().toJson(dto.members)
                        )
                        // REST bắt đầu trước một socket event có thể kết thúc sau event: không hạ preview/unread cũ hơn.
                        val merged = if (previous != null && previous.updatedAt > updated) row.copy(
                            lastMessagePreview = previous.lastMessagePreview,
                            lastMessageType = previous.lastMessageType,
                            lastSenderId = previous.lastSenderId,
                            unreadCount = previous.unreadCount,
                            updatedAt = previous.updatedAt
                        ) else row
                        dao.upsertConversations(listOf(merged))
                        dao.upsertUsers(dto.members.map { u ->
                            ChatUserEntity(
                                expected.ownerId,
                                expected.source,
                                u.id,
                                u.displayName.orEmpty(),
                                u.avatarUrl,
                                u.online,
                                u.lastSeenAt?.let { Instant.parse(it).toEpochMilli() })
                        })
                        dto.lastMessage?.let {
                            mergeMessage(
                                expected,
                                it,
                                null,
                                countUnread = false,
                                updatePreview = false
                            )
                        }
                    }
                    coroutineContext.ensureActive()
                    if (sessionProvider() != expected) throw StaleSession()
                }
                sessionProvider() == expected
            } catch (stale: StaleSession) {
                false
            }
        }
    }

    suspend fun receive(
        expected: ChatSession,
        message: ChatMessageDto,
        visibleConversation: Long? = null
    ): Boolean {
        if (sessionProvider() != expected) return false
        return withContext(Dispatchers.IO) {
            try {
                database.withTransaction {
                    if (sessionProvider() != expected) throw StaleSession()
                    mergeMessage(
                        expected,
                        message,
                        visibleConversation,
                        countUnread = true,
                        updatePreview = true
                    )
                    coroutineContext.ensureActive()
                    if (sessionProvider() != expected) throw StaleSession()
                }
                sessionProvider() == expected
            } catch (stale: StaleSession) {
                false
            }
        }
    }

    private suspend fun mergeMessage(
        expected: ChatSession,
        message: ChatMessageDto,
        visibleConversation: Long?,
        countUnread: Boolean,
        updatePreview: Boolean
    ) {
        val incoming = message.status.uppercase(Locale.ROOT)
        require(incoming in setOf("SENT", "DELIVERED", "SEEN")) { "Invalid remote message status" }
        val created = Instant.parse(message.createdAt).toEpochMilli()
        val dao = database.chatDao()
        val matches = dao.matching(expected.ownerId, expected.source, message.clientId, message.id)
        val previous =
            matches.firstOrNull { it.clientId != null && it.clientId == message.clientId }
                ?: matches.firstOrNull()
        val localId =
            previous?.localId ?: message.clientId?.let { "client:$it" } ?: "server:${message.id}"
        // Nếu REST và optimistic từng tạo hai identity riêng, gộp trước khi chạm unique index.
        matches.filter { it.localId != localId }
            .forEach { dao.deleteMessage(expected.ownerId, expected.source, it.localId) }
        val ranks = mapOf("SENDING" to 0, "FAILED" to 0, "SENT" to 1, "DELIVERED" to 2, "SEEN" to 3)
        val status = (matches.map { it.status } + incoming).maxBy { ranks[it] ?: -1 }
        dao.upsertMessage(
            MessageEntity(
                expected.ownerId,
                expected.source,
                localId,
                message.id,
                message.clientId ?: previous?.clientId,
                message.conversationId,
                message.senderId,
                message.type,
                message.content,
                message.mediaUrl,
                message.durationMs,
                status,
                created,
                previous?.ownedPath
            )
        )
        if (updatePreview) {
            dao.conversation(expected.ownerId, expected.source, message.conversationId)
                ?.let { conversation ->
                    val increment =
                        countUnread && previous == null && message.senderId.toString() != expected.ownerId && visibleConversation != message.conversationId && status != "SEEN"
                    val unread = conversation.unreadCount + if (increment) 1 else 0
                    val updated = if (created >= conversation.updatedAt) conversation.copy(
                        lastMessagePreview = message.content,
                        lastMessageType = message.type,
                        lastSenderId = message.senderId,
                        updatedAt = created,
                        unreadCount = unread
                    )
                    else conversation.copy(unreadCount = unread)
                    dao.upsertConversations(listOf(updated))
                }
        }
    }

    suspend fun updateStatus(
        expected: ChatSession,
        conversation: Long,
        serverId: Long,
        clientId: String?,
        status: String,
        reader: Long
    ) {
        guarded(expected) {
            val normalized = status.uppercase(Locale.ROOT)
            val rank =
                mapOf("SENDING" to 0, "FAILED" to 0, "SENT" to 1, "DELIVERED" to 2, "SEEN" to 3)
            require(normalized in setOf("SENT", "DELIVERED", "SEEN"))
            val dao = database.chatDao()
            val rows = if (normalized == "SEEN" && reader.toString() != expected.ownerId) (
                    dao.outgoingThrough(expected.ownerId, expected.source, conversation, serverId) +
                            dao.matching(expected.ownerId, expected.source, clientId, serverId)
                                .filter { it.conversationId == conversation && it.senderId.toString() == expected.ownerId }
                    ).distinctBy { it.localId }
            else dao.matching(expected.ownerId, expected.source, clientId, serverId)
                .filter { it.conversationId == conversation }
            rows.filter { (rank[it.status] ?: 0) < rank.getValue(normalized) }
                .forEach { dao.upsertMessage(it.copy(status = normalized)) }
        }
    }

    suspend fun updatePresence(
        expected: ChatSession,
        user: Long,
        online: Boolean,
        lastSeen: Long?
    ) {
        guarded(expected) {
            database.chatDao().presence(expected.ownerId, expected.source, user, online, lastSeen)
        }
    }

    private suspend fun guarded(expected: ChatSession, block: suspend () -> Unit) {
        if (sessionProvider() != expected) return
        withContext(Dispatchers.IO) {
            try {
                database.withTransaction {
                    if (sessionProvider() != expected) throw StaleSession()
                    block(); coroutineContext.ensureActive()
                    if (sessionProvider() != expected) throw StaleSession()
                }
            } catch (_: StaleSession) {
            }
        }
    }

    suspend fun following(expected: ChatSession): List<ChatMemberDto> {
        check(sessionProvider() == expected)
        val response =
            api.following(SessionRequest(expected.generation)); check(
            sessionProvider() == expected && response.success
        )
        return checkNotNull(response.data).filter { it.id.toString() != expected.ownerId }
    }

    suspend fun create(expected: ChatSession, user: Long): Long {
        check(sessionProvider() == expected)
        val response = api.create(
            CreateConversationBody(user),
            SessionRequest(expected.generation)
        )
        check(sessionProvider() == expected && response.success)
        val conversation = checkNotNull(response.data)
        check(conversation.members.any { it.id.toString() == expected.ownerId })
        check(sync(expected))
        return conversation.id
    }

    /** Pager trả cursor server, không suy nextBefore từ local optimistic ID. Tin REST không
     * cộng lại unread; bản cache vẫn hiện ngay khi request fail/offline. */
    suspend fun page(
        expected: ChatSession,
        conversation: Long,
        before: Long?
    ): Pair<Long?, Boolean> {
        check(sessionProvider() == expected)
        val response = api.messages(
            conversation,
            before,
            30,
            SessionRequest(expected.generation)
        )
        check(sessionProvider() == expected && response.success && response.data != null)
        guarded(expected) {
            response.data!!.forEach { message ->
                require(message.conversationId == conversation)
                mergeMessage(expected, message, null, countUnread = false, updatePreview = false)
            }
        }
        check(sessionProvider() == expected)
        return response.meta?.nextBefore to (response.meta?.hasMore ?: false)
    }

    /** Phải commit SENDING trước emit. Retry lấy lại row/clientId cũ, không sinh UUID mới.
     * ownedPath chỉ trỏ file app sở hữu, phục vụ retry media sau rotation. */
    suspend fun prepare(
        expected: ChatSession,
        conversation: Long,
        content: String,
        type: String = "text",
        url: String? = null,
        duration: Long? = null,
        ownedPath: String? = null,
        retry: String? = null
    ): MessageEntity? {
        var result: MessageEntity? = null
        guarded(expected) {
            val dao = database.chatDao(); check(
            dao.conversation(
                expected.ownerId,
                expected.source,
                conversation
            ) != null
        )
            val previous = retry?.let { dao.findLocal(expected.ownerId, expected.source, it) }
            if (retry != null) check(previous != null && previous.conversationId == conversation && previous.senderId.toString() == expected.ownerId && previous.status == "FAILED")
            val id = previous?.clientId ?: UUID.randomUUID().toString()
            val row = previous?.copy(status = "SENDING") ?: MessageEntity(
                expected.ownerId,
                expected.source,
                "client:$id",
                null,
                id,
                conversation,
                expected.ownerId.toLong(),
                type,
                content,
                url,
                duration,
                "SENDING",
                System.currentTimeMillis(),
                ownedPath
            )
            dao.upsertMessage(row)
            dao.conversation(expected.ownerId, expected.source, conversation)
                ?.takeIf { row.createdAt >= it.updatedAt }?.let {
                dao.upsertConversations(
                    listOf(
                        it.copy(
                            lastMessagePreview = row.content,
                            lastMessageType = row.type,
                            lastSenderId = row.senderId,
                            updatedAt = row.createdAt
                        )
                    )
                )
            }
            result = row
        }
        return result.takeIf { sessionProvider() == expected }
    }

    suspend fun failed(expected: ChatSession, localId: String) {
        guarded(expected) {
            val dao = database.chatDao(); dao.findLocal(expected.ownerId, expected.source, localId)
            ?.takeIf { it.status == "SENDING" }
            ?.let { dao.upsertMessage(it.copy(status = "FAILED")) }
        }
    }

    suspend fun read(expected: ChatSession, conversation: Long, through: Long) {
        guarded(expected) {
            val dao = database.chatDao(); dao.markIncomingSeen(
            expected.ownerId,
            expected.source,
            conversation,
            through
        )
            dao.conversation(expected.ownerId, expected.source, conversation)?.let {
                dao.upsertConversations(
                    listOf(
                        it.copy(
                            unreadCount = dao.unreadAfter(
                                expected.ownerId,
                                expected.source,
                                conversation,
                                through
                            )
                        )
                    )
                )
            }
        }
    }

    /** Upload ACK chỉ sửa row cùng identity đã tạo, không chèn một UUID mới trước emit. */
    suspend fun uploaded(expected: ChatSession, id: String, url: String): MessageEntity? {
        var result: MessageEntity? = null
        guarded(expected) {
            val dao = database.chatDao();
            val previous = checkNotNull(dao.findLocal(expected.ownerId, expected.source, id))
            require(previous.type in setOf("image", "voice") && previous.serverId == null)
            val row =
                previous.copy(mediaUrl = url, status = "SENDING"); dao.upsertMessage(row); result =
            row
        }
        return result.takeIf { sessionProvider() == expected }
    }

    suspend fun mute(expected: ChatSession, conversation: Long, muted: Boolean) {
        guarded(expected) {
            val dao = database.chatDao(); dao.conversation(
            expected.ownerId,
            expected.source,
            conversation
        )?.let { dao.upsertConversations(listOf(it.copy(muted = muted))) }
        }
    }

    /** Mỗi reconnect đi từ trang mới nhất đến mốc đã cache trước sync. Không chỉ lấy lastMessage
     * hoặc một trang30: outage dài có thể bỏ lỡ nhiều trang. Không gửi lại FAILED tự động.
     * Cursor phải giảm nghiêm ngặt, tránh backend lỗi làm loop; mỗi trang giữ session fence. */
    suspend fun catchUp(expected: ChatSession, conversation: Long, knownNewest: Long? = null) {
        check(sessionProvider() == expected)
        val stop = knownNewest ?: database.chatDao()
            .newestServerId(expected.ownerId, expected.source, conversation)
        var before: Long? = null
        while (true) {
            coroutineContext.ensureActive(); check(sessionProvider() == expected)
            val response = api.messages(
                conversation,
                before,
                30,
                SessionRequest(expected.generation)
            )
            check(response.success && response.data != null && sessionProvider() == expected)
            val messages = response.data
            guarded(expected) {
                messages.forEach { message ->
                    require(message.conversationId == conversation); mergeMessage(
                    expected,
                    message,
                    null,
                    countUnread = false,
                    updatePreview = false
                )
                }
            }
            if (messages.isEmpty() || response.meta?.hasMore != true || (stop != null && messages.any { it.id <= stop })) break
            val next = checkNotNull(response.meta.nextBefore)
            check(next > 0 && (before == null || next < before)) { "CHAT_INVALID_CURSOR" }; before =
                next
        }
    }

    private class StaleSession : RuntimeException()
}