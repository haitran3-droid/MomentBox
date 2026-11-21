package com.ntq.training.momentbox.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.ntq.training.momentbox.core.network.SessionRequest
import com.ntq.training.momentbox.data.local.db.AppDatabase
import com.ntq.training.momentbox.data.local.db.ChatUserEntity
import com.ntq.training.momentbox.data.local.db.ConversationEntity
import com.ntq.training.momentbox.data.local.db.MessageEntity
import com.ntq.training.momentbox.data.remote.api.ChatApi
import com.ntq.training.momentbox.data.remote.dto.ChatMemberDto
import com.ntq.training.momentbox.data.remote.dto.ChatMessageDto
import com.ntq.training.momentbox.data.remote.dto.CreateConversationBody
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class ChatSession(val ownerId: String, val source: String, val generation: Long)

/**
 * Estimates server-vs-device clock offset (`serverTime - deviceTime`) so optimistic local
 * timestamps stay chronologically aligned with server timestamps even when device clocks drift.
 */
internal class ChatClockSkew {
    @Volatile
    private var offsetMs: Long? = null

    val currentOffsetMs: Long? get() = offsetMs
    val isCalibrated: Boolean get() = offsetMs != null

    fun observeLiveServerTime(serverTimeMs: Long, localNowMs: Long): Long {
        val sample = serverTimeMs - localNowMs
        val previous = offsetMs
        val next = if (previous == null || abs(sample - previous) > 5_000L) {
            sample
        } else {
            (previous * 3L + sample) / 4L
        }
        offsetMs = next
        return next
    }

    fun observeHistoricalServerTime(serverTimeMs: Long, localNowMs: Long) {
        val sample = serverTimeMs - localNowMs
        val current = offsetMs
        if (sample > (current ?: 0L)) {
            offsetMs = sample
        }
    }

    fun estimateServerNow(localNowMs: Long): Long = localNowMs + (offsetMs ?: 0L)

    fun optimisticTimestamp(localNowMs: Long, latestConfirmedAt: Long?): Long {
        val adjusted = estimateServerNow(localNowMs)
        return if (latestConfirmedAt != null && adjusted <= latestConfirmedAt) {
            latestConfirmedAt + 1L
        } else {
            adjusted
        }
    }
}

internal fun recalibrateUnconfirmedTimestamps(
    candidatesNewestFirst: List<MessageEntity>,
    liveServerTimeMs: Long,
    clockOffsetMs: Long,
    uncalibratedLocalTimes: Map<String, Long>
): Map<String, Long> {
    val updates = linkedMapOf<String, Long>()
    var upperBound = liveServerTimeMs - 1L
    for (candidate in candidatesNewestFirst) {
        if (candidate.serverId != null) continue
        val sentLocal = uncalibratedLocalTimes[candidate.localId]
        val calibrated = sentLocal?.plus(clockOffsetMs) ?: candidate.createdAt
        val target = if (candidate.status == "FAILED") {
            val clamped = minOf(calibrated, upperBound)
            upperBound = clamped - 1L
            clamped
        } else {
            calibrated
        }
        if (target != candidate.createdAt) {
            updates[candidate.localId] = target
        }
    }
    return updates
}

internal fun resolveSyncedConversation(
    serverRow: ConversationEntity,
    previous: ConversationEntity?,
    localNewestConfirmed: MessageEntity?,
    remoteLastMessageId: Long?,
    inFlightSending: MessageEntity?
): ConversationEntity {
    val hasNewerConfirmed = localNewestConfirmed != null && (
        (remoteLastMessageId != null && (localNewestConfirmed.serverId ?: -1L) > remoteLastMessageId) ||
            localNewestConfirmed.createdAt > serverRow.updatedAt
        )
    if (previous != null && hasNewerConfirmed) {
        return serverRow.copy(
            lastMessagePreview = previous.lastMessagePreview,
            lastMessageType = previous.lastMessageType,
            lastSenderId = previous.lastSenderId,
            unreadCount = previous.unreadCount,
            updatedAt = previous.updatedAt
        )
    }
    if (inFlightSending != null && inFlightSending.createdAt >= serverRow.updatedAt) {
        return serverRow.copy(
            lastMessagePreview = inFlightSending.content,
            lastMessageType = inFlightSending.type,
            lastSenderId = inFlightSending.senderId,
            updatedAt = inFlightSending.createdAt
        )
    }
    return serverRow
}

internal fun shouldAdvancePreviewForServerMessage(
    messageCreatedAt: Long,
    messageServerId: Long,
    conversationUpdatedAt: Long,
    newestConfirmedServerId: Long?,
    inFlightSending: MessageEntity?
): Boolean {
    if (inFlightSending != null && inFlightSending.createdAt > messageCreatedAt) {
        return false
    }
    val isNewestConfirmed = newestConfirmedServerId == null || messageServerId >= newestConfirmedServerId
    return messageCreatedAt >= conversationUpdatedAt || isNewestConfirmed
}

class ChatRepository(
    private val database: AppDatabase,
    private val api: ChatApi,
    private val sessionProvider: suspend () -> ChatSession?,
    private val nowProvider: () -> Long
) {
    constructor(
        database: AppDatabase,
        api: ChatApi,
        sessionProvider: suspend () -> ChatSession?
    ) : this(database, api, sessionProvider, System::currentTimeMillis)
    private val clockSkew = ChatClockSkew()
    private val inFlightIds = ConcurrentHashMap<Triple<String, String, Long>, MutableSet<String>>()
    private val uncalibratedLocalTimes = ConcurrentHashMap<String, Long>()

    private fun roomKey(expected: ChatSession, conversation: Long) =
        Triple(expected.ownerId, expected.source, conversation)

    private fun trackInFlight(expected: ChatSession, conversation: Long, localId: String) {
        inFlightIds.computeIfAbsent(roomKey(expected, conversation)) {
            ConcurrentHashMap.newKeySet()
        }.add(localId)
    }

    private fun untrackInFlight(expected: ChatSession, conversation: Long, localId: String) {
        inFlightIds[roomKey(expected, conversation)]?.remove(localId)
    }

    private suspend fun newestInFlightSending(
        expected: ChatSession,
        conversation: Long,
        excludingLocalId: String? = null
    ): MessageEntity? {
        val tracked = inFlightIds[roomKey(expected, conversation)]?.toList().orEmpty()
        if (tracked.isEmpty()) return null
        val dao = database.chatDao()
        var newest: MessageEntity? = null
        for (localId in tracked) {
            if (localId == excludingLocalId) continue
            val row = dao.findLocal(expected.ownerId, expected.source, localId)
            if (row != null && row.serverId == null && row.status == "SENDING") {
                if (newest == null || row.createdAt > newest.createdAt) {
                    newest = row
                }
            } else {
                inFlightIds[roomKey(expected, conversation)]?.remove(localId)
            }
        }
        return newest
    }

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
                val localNow = nowProvider()
                database.withTransaction {
                    if (sessionProvider() != expected) throw StaleSession()
                    val dao = database.chatDao()
                    for (dto in response.data) {
                        val previous = dao.conversation(expected.ownerId, expected.source, dto.id)
                        val updated = dto.updatedAt?.let { Instant.parse(it).toEpochMilli() } ?: 0L
                        if (updated > 0L) {
                            clockSkew.observeHistoricalServerTime(updated, localNow)
                        }
                        val localNewestServerId = dao.newestServerId(
                            expected.ownerId,
                            expected.source,
                            dto.id
                        )
                        val localNewestConfirmed = localNewestServerId?.let { id ->
                            dao.matching(expected.ownerId, expected.source, null, id).firstOrNull()
                        }
                        val inFlightSending = newestInFlightSending(expected, dto.id)
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
                        // REST bắt đầu trước một socket event có thể kết thúc sau event: không hạ
                        // preview/unread của tin đã xác nhận mới hơn, nhưng không để tin FAILED/lệch giờ khóa preview.
                        val merged = resolveSyncedConversation(
                            serverRow = row,
                            previous = previous,
                            localNewestConfirmed = localNewestConfirmed,
                            remoteLastMessageId = dto.lastMessage?.id,
                            inFlightSending = inFlightSending
                        )
                        dao.upsertConversations(listOf(merged))
                        dao.upsertUsers(dto.members.map { u ->
                            ChatUserEntity(
                                expected.ownerId,
                                expected.source,
                                u.id,
                                u.displayName.orEmpty(),
                                u.avatarUrl,
                                u.online,
                                u.lastSeenAt?.let { Instant.parse(it).toEpochMilli() }
                            )
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
            } catch (_: StaleSession) {
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
                val created = Instant.parse(message.createdAt).toEpochMilli()
                val offset = clockSkew.observeLiveServerTime(created, nowProvider())
                // Read recent messages outside withTransaction to avoid Room Flow contention.
                val unconfirmedCandidates = database.chatDao()
                    .messages(expected.ownerId, expected.source, message.conversationId, 60)
                    .first()
                    .filter {
                        it.serverId == null &&
                            (message.clientId == null || it.clientId != message.clientId)
                    }
                database.withTransaction {
                    if (sessionProvider() != expected) throw StaleSession()
                    val dao = database.chatDao()
                    if (unconfirmedCandidates.isNotEmpty()) {
                        val liveCandidates = unconfirmedCandidates.mapNotNull { candidate ->
                            dao.findLocal(expected.ownerId, expected.source, candidate.localId)
                                ?.takeIf { it.serverId == null }
                        }
                        val updates = recalibrateUnconfirmedTimestamps(
                            candidatesNewestFirst = liveCandidates,
                            liveServerTimeMs = created,
                            clockOffsetMs = offset,
                            uncalibratedLocalTimes = uncalibratedLocalTimes
                        )
                        for (candidate in liveCandidates) {
                            uncalibratedLocalTimes.remove(candidate.localId)
                            val recalibrated = updates[candidate.localId] ?: continue
                            dao.upsertMessage(candidate.copy(createdAt = recalibrated))
                        }
                    }
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
            } catch (_: StaleSession) {
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
        clockSkew.observeHistoricalServerTime(created, nowProvider())
        val dao = database.chatDao()
        val matches = dao.matching(expected.ownerId, expected.source, message.clientId, message.id)
        val previous =
            matches.firstOrNull { it.clientId != null && it.clientId == message.clientId }
                ?: matches.firstOrNull()
        val localId =
            previous?.localId ?: message.clientId?.let { "client:$it" } ?: "server:${message.id}"
        // Nếu REST và optimistic từng tạo hai identity riêng, gộp trước khi chạm unique index.
        matches.filter { it.localId != localId }.forEach {
            untrackInFlight(expected, message.conversationId, it.localId)
            uncalibratedLocalTimes.remove(it.localId)
            dao.deleteMessage(expected.ownerId, expected.source, it.localId)
        }
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
        untrackInFlight(expected, message.conversationId, localId)
        uncalibratedLocalTimes.remove(localId)
        if (updatePreview) {
            dao.conversation(expected.ownerId, expected.source, message.conversationId)
                ?.let { conversation ->
                    val increment = countUnread &&
                        previous == null &&
                        message.senderId.toString() != expected.ownerId &&
                        visibleConversation != message.conversationId &&
                        status != "SEEN"
                    val unread = conversation.unreadCount + if (increment) 1 else 0
                    val newestConfirmedId = dao.newestServerId(
                        expected.ownerId,
                        expected.source,
                        message.conversationId
                    )
                    val inFlightSending = newestInFlightSending(
                        expected,
                        message.conversationId,
                        excludingLocalId = localId
                    )
                    val advance = shouldAdvancePreviewForServerMessage(
                        messageCreatedAt = created,
                        messageServerId = message.id,
                        conversationUpdatedAt = conversation.updatedAt,
                        newestConfirmedServerId = newestConfirmedId,
                        inFlightSending = inFlightSending
                    )
                    val updated = if (advance) {
                        conversation.copy(
                            lastMessagePreview = message.content,
                            lastMessageType = message.type,
                            lastSenderId = message.senderId,
                            updatedAt = created,
                            unreadCount = unread
                        )
                    } else {
                        conversation.copy(unreadCount = unread)
                    }
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
            val rows = if (normalized == "SEEN" && reader.toString() != expected.ownerId) {
                (
                    dao.outgoingThrough(expected.ownerId, expected.source, conversation, serverId) +
                        dao.matching(expected.ownerId, expected.source, clientId, serverId)
                            .filter {
                                it.conversationId == conversation &&
                                    it.senderId.toString() == expected.ownerId
                            }
                    ).distinctBy { it.localId }
            } else {
                dao.matching(expected.ownerId, expected.source, clientId, serverId)
                    .filter { it.conversationId == conversation }
            }
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
                    block()
                    coroutineContext.ensureActive()
                    if (sessionProvider() != expected) throw StaleSession()
                }
            } catch (_: StaleSession) {
            }
        }
    }

    suspend fun following(expected: ChatSession): List<ChatMemberDto> {
        check(sessionProvider() == expected)
        val response = api.following(SessionRequest(expected.generation))
        check(sessionProvider() == expected && response.success)
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
            val dao = database.chatDao()
            val room = checkNotNull(
                dao.conversation(expected.ownerId, expected.source, conversation)
            )
            val previous = retry?.let { dao.findLocal(expected.ownerId, expected.source, it) }
            if (retry != null) {
                check(
                    previous != null &&
                        previous.conversationId == conversation &&
                        previous.senderId.toString() == expected.ownerId &&
                        previous.status == "FAILED"
                )
            }
            val id = previous?.clientId ?: UUID.randomUUID().toString()
            val localId = previous?.localId ?: "client:$id"
            val row = if (previous != null) {
                val offset = clockSkew.currentOffsetMs
                val uncalibratedSent = if (offset != null) {
                    uncalibratedLocalTimes.remove(previous.localId)
                } else {
                    null
                }
                val adjustedCreatedAt = uncalibratedSent?.plus(offset ?: 0L) ?: previous.createdAt
                previous.copy(status = "SENDING", createdAt = adjustedCreatedAt)
            } else {
                val newestServerId = dao.newestServerId(
                    expected.ownerId,
                    expected.source,
                    conversation
                )
                val latestConfirmedAt = newestServerId?.let { serverId ->
                    dao.matching(expected.ownerId, expected.source, null, serverId)
                        .firstOrNull()?.createdAt
                }
                val localNow = nowProvider()
                val createdAt = clockSkew.optimisticTimestamp(localNow, latestConfirmedAt)
                if (!clockSkew.isCalibrated) {
                    uncalibratedLocalTimes[localId] = localNow
                }
                MessageEntity(
                    expected.ownerId,
                    expected.source,
                    localId,
                    null,
                    id,
                    conversation,
                    expected.ownerId.toLong(),
                    type,
                    content,
                    url,
                    duration,
                    "SENDING",
                    createdAt,
                    ownedPath
                )
            }
            dao.upsertMessage(row)
            trackInFlight(expected, conversation, row.localId)
            if (row.createdAt >= room.updatedAt) {
                dao.upsertConversations(
                    listOf(
                        room.copy(
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
            val dao = database.chatDao()
            dao.findLocal(expected.ownerId, expected.source, localId)
                ?.takeIf { it.status == "SENDING" }
                ?.let { row ->
                    untrackInFlight(expected, row.conversationId, row.localId)
                    dao.upsertMessage(row.copy(status = "FAILED"))
                }
        }
    }

    suspend fun read(expected: ChatSession, conversation: Long, through: Long) {
        guarded(expected) {
            val dao = database.chatDao()
            dao.markIncomingSeen(expected.ownerId, expected.source, conversation, through)
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
            val dao = database.chatDao()
            val previous = checkNotNull(dao.findLocal(expected.ownerId, expected.source, id))
            require(previous.type in setOf("image", "voice") && previous.serverId == null)
            val row = previous.copy(mediaUrl = url, status = "SENDING")
            dao.upsertMessage(row)
            trackInFlight(expected, row.conversationId, row.localId)
            result = row
        }
        return result.takeIf { sessionProvider() == expected }
    }

    suspend fun mute(expected: ChatSession, conversation: Long, muted: Boolean) {
        guarded(expected) {
            val dao = database.chatDao()
            dao.conversation(expected.ownerId, expected.source, conversation)
                ?.let { dao.upsertConversations(listOf(it.copy(muted = muted))) }
        }
    }

    /** Mỗi reconnect đi từ trang mới nhất đến mốc đã cache trước sync. Không chỉ lấy lastMessage
     * hoặc một trang 30: outage dài có thể bỏ lỡ nhiều trang. Không gửi lại FAILED tự động.
     * Cursor phải giảm nghiêm ngặt, tránh backend lỗi làm loop; mỗi trang giữ session fence. */
    suspend fun catchUp(expected: ChatSession, conversation: Long, knownNewest: Long?) {
        check(sessionProvider() == expected)
        if (knownNewest == null) {
            // Older history stays available through the room's scroll pager.
            page(expected, conversation, before = null)
            return
        }
        var before: Long? = null
        while (true) {
            coroutineContext.ensureActive()
            check(sessionProvider() == expected)
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
                    require(message.conversationId == conversation)
                    mergeMessage(
                        expected,
                        message,
                        null,
                        countUnread = false,
                        updatePreview = false
                    )
                }
            }
            if (messages.isEmpty() ||
                response.meta?.hasMore != true ||
                messages.any { it.id <= knownNewest }
            ) {
                break
            }
            val next = checkNotNull(response.meta.nextBefore)
            check(next > 0 && (before == null || next < before)) { "CHAT_INVALID_CURSOR" }
            before = next
        }
    }

    private class StaleSession : RuntimeException()
}
