package com.ntq.training.momentbox.core.notification

import com.google.gson.annotations.SerializedName
import com.google.gson.Gson
import com.ntq.training.momentbox.core.network.ApiResponse
import com.ntq.training.momentbox.core.network.SessionRequest
import com.ntq.training.momentbox.core.realtime.ChatRuntime
import com.ntq.training.momentbox.core.realtime.SocketManager
import com.ntq.training.momentbox.data.local.db.AppDatabase
import com.ntq.training.momentbox.data.local.db.MessageEntity
import com.ntq.training.momentbox.data.remote.dto.ChatMemberDto
import com.ntq.training.momentbox.data.remote.dto.ChatMessageDto
import com.ntq.training.momentbox.data.repository.ChatSession
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import retrofit2.http.Body
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.Tag

interface ChatActionApi {
    @POST("v1/conversations/{id}/messages")
    suspend fun send(
        @Path("id") conversation: Long, @Body body: NotificationReplyBody,
        @Tag session: SessionRequest
    ): ApiResponse<ChatMessageDto>
}

data class NotificationReplyBody(
    @SerializedName("clientId") val clientId: String,
    @SerializedName("type") val type: String = "text",
    @SerializedName("content") val content: String
)

class NotificationReplySender(
    private val runtime: ChatRuntime,
    private val socket: SocketManager,
    private val database: AppDatabase,
    private val api: ChatActionApi
) {
    private val operations = ConcurrentHashMap.newKeySet<Job>()
    suspend fun cancelForLogout() {
        val work = operations.toList()
        work.forEach { it.cancel() }
        work.joinAll()
    }

    suspend fun reply(expected: ChatSession, room: Long, content: String): Boolean {
        val job = currentCoroutineContext()[Job]!!
        operations.add(job)
        try {
            return replyActive(expected, room, content)
        } finally {
            operations.remove(job)
        }
    }

    private suspend fun replyActive(expected: ChatSession, room: Long, content: String): Boolean {
        if (content.isBlank() || content.length > 5000 || room <= 0 || runtime.current() != expected) return false
        val conversation =
            database.chatDao().conversation(expected.ownerId, expected.source, room) ?: return false
        val members = runCatching {
            Gson().fromJson(
                conversation.membersJson,
                Array<ChatMemberDto>::class.java
            ).orEmpty()
        }.getOrDefault(emptyArray())
        if (members.none { it.id.toString() == expected.ownerId } || runtime.current() != expected) return false
        val row = runtime.repository.prepare(expected, room, content.trim()) ?: return false
        try {
            val payload = JSONObject().put("conversationId", room).put("clientId", row.clientId)
                .put("type", "text").put("content", row.content)
            val ack = withTimeoutOrNull(1500) {
                suspendCancellableCoroutine<JSONObject?> { continuation ->
                    val once = AtomicBoolean(false)
                    val finish: (JSONObject?) -> Unit = { value ->
                        if (once.compareAndSet(
                                false,
                                true
                            ) && continuation.isActive
                        ) continuation.resume(value)
                    }
                    val emitted = socket.emitForSession(expected, "message:send", payload, finish)
                    if (!emitted) finish(null)
                }
            }
            val message = ack?.takeIf { it.optBoolean("ok") }?.optJSONObject("message")
                ?.let { Gson().fromJson(it.toString(), ChatMessageDto::class.java) }
            if (message != null && accept(expected, row, message)) return true
            if (runtime.current() != expected) return false
            val response = api.send(
                room,
                NotificationReplyBody(checkNotNull(row.clientId), content = row.content),
                SessionRequest(expected.generation)
            )
            if (response.success && response.data?.let {
                    accept(
                        expected,
                        row,
                        it
                    )
                } == true) return true
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runtime.repository.failed(expected, row.localId) }
            throw cancelled
        } catch (_: Exception) { /* Không log body/token/nội dung trả lời; UI chỉ nhận thất bại thật. */
        }
        runtime.repository.failed(expected, row.localId)
        return false
    }

    suspend fun markSeen(expected: ChatSession, room: Long, through: Long): Boolean {
        if (room <= 0 || through <= 0 || runtime.current() != expected) return false
        val conversation =
            database.chatDao().conversation(expected.ownerId, expected.source, room) ?: return false
        val members = runCatching {
            Gson().fromJson(
                conversation.membersJson,
                Array<ChatMemberDto>::class.java
            ).orEmpty()
        }.getOrDefault(emptyArray())
        if (members.none { it.id.toString() == expected.ownerId }) return false
        val target = database.chatDao().matching(expected.ownerId, expected.source, null, through)
            .singleOrNull() ?: return false
        if (target.conversationId != room || target.senderId.toString() == expected.ownerId || runtime.current() != expected) return false
        if (!socket.emitForSession(
                expected,
                "message:seen",
                JSONObject().put("conversationId", room).put("messageId", through)
            )
        ) return false
        runtime.repository.read(expected, room, through)
        return runtime.current() == expected
    }

    private suspend fun accept(
        expected: ChatSession,
        row: MessageEntity,
        message: ChatMessageDto
    ): Boolean {
        if (message.id <= 0 || message.clientId != row.clientId || message.conversationId != row.conversationId || message.senderId.toString() != expected.ownerId || runtime.current() != expected) return false
        return runtime.repository.receive(expected, message)
    }
}
