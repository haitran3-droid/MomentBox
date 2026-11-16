package com.ntq.training.momentbox.core.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.ntq.training.momentbox.data.repository.ChatSession
import com.ntq.training.momentbox.MomentBoxApp
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class ChatActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(REPLY, SEEN)) return
        intent ?: return
        val seen = intent.action == SEEN
        val request = runCatching {
            val owner = intent.getStringExtra("owner")?.takeIf { it.length in 1..128 } ?: return
            val source = intent.getStringExtra("source")?.takeIf { it.length in 1..2048 } ?: return
            val generation = intent.getLongExtra("generation", -1)
            val room = intent.getLongExtra("conversationId", -1)
            val text =
                if (seen) "" else RemoteInput.getResultsFromIntent(intent)?.getCharSequence(INPUT)
                    ?.takeIf { it.length in 1..5000 }?.toString() ?: return
            val through = intent.getLongExtra("through", -1)
            if (generation < 0 || room <= 0 || (!seen && text.isBlank()) || (seen && through <= 0)) return
            ActionRequest(ChatSession(owner, source, generation), room, text, through)
        }.getOrNull() ?: return
        val pending = goAsync()
        val app = context.applicationContext as MomentBoxApp
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8500) {
                    val expected = request.session
                    // Kiểm generation trước activate: notification cũ không được mở lại transport của login mới.
                    if (app.tokenStore.snapshot().generation != expected.generation) return@withTimeout
                    if (app.chatRuntime.current() == null) app.chatRuntime.activate()
                    if (app.chatRuntime.current() != expected) return@withTimeout
                    if (seen) app.notificationReplySender.markSeen(
                        expected,
                        request.room,
                        request.through
                    )
                    else app.notificationReplySender.reply(expected, request.room, request.text)
                }
            } catch (_: TimeoutCancellationException) {
                // Hết hạn không phải thành công; sender đã giữ row FAILED để người dùng retry trong S17.
            } catch (_: Exception) {
                // Backend/phiên có thể biến mất ở cold start; không để exception IO làm chết main process.
            } finally {
                pending.finish()
                coroutineContext.cancel()
            }
        }
    }

    private data class ActionRequest(
        val session: ChatSession,
        val room: Long,
        val text: String,
        val through: Long
    )

    companion object {
        const val REPLY = "momentbox.chat.REPLY"
        const val SEEN = "momentbox.chat.SEEN"
        const val INPUT = "reply"
    }
}
