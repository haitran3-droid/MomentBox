package com.ntq.training.momentbox.core.notification

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.bumptech.glide.Glide
import com.google.gson.Gson
import com.ntq.training.momentbox.data.local.db.ConversationEntity
import com.ntq.training.momentbox.data.local.db.MessageEntity
import com.ntq.training.momentbox.data.remote.dto.ChatMemberDto
import com.ntq.training.momentbox.data.repository.ChatSession
import com.ntq.training.momentbox.feature.chat.ChatActivity
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

@OptIn(ExperimentalCoroutinesApi::class)
class ChatNotifications(private val app: MomentBoxApp) {
    private val runtime get() = app.chatRuntime
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refresh = MutableStateFlow(0L)
    private val capabilityRevision = MutableStateFlow(0L)
    private fun channelAllowed() = NotificationManagerCompat.from(app)
        .areNotificationsEnabled() && NotificationChannels.channelEnabled(
        app,
        NotificationChannels.CHAT
    )

    private val lastAllowed = AtomicBoolean(channelAllowed())

    // Off/on có thể xảy ra khi app vẫn ở nền. Epoch giữ lại cả thay đổi bị Flow conflation,
    // chỉ reset fingerprint khi quyền/channel đổi, không repost chỉ vì người dùng vuốt bỏ notification.
    private val blockChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT < 28) return
            val relevant = intent.action == NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED ||
                    (intent.action == NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED && intent.getStringExtra(
                        NotificationManager.EXTRA_NOTIFICATION_CHANNEL_ID
                    ) == NotificationChannels.CHAT)
            if (relevant) {
                lastAllowed.set(channelAllowed())
                capabilityRevision.update { it + 1 }
            }
        }
    }

    // Quyền có thể đổi trong Settings mà Room không phát bản ghi mới. Chỉ giữ Application,
    // không giữ Activity; mỗi lần quay lại UI đánh thức collector, fingerprint vẫn tránh alert lặp.
    private val foreground = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            val allowed = channelAllowed()
            if (lastAllowed.getAndSet(allowed) != allowed) capabilityRevision.update { it + 1 }
            refresh.update { it + 1 }
        }

        override fun onActivityCreated(activity: Activity, saved: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private data class RoomMessages(val room: ConversationEntity, val messages: List<MessageEntity>)

    init {
        app.registerActivityLifecycleCallbacks(foreground)
        if (Build.VERSION.SDK_INT >= 28) ContextCompat.registerReceiver(
            app,
            blockChanges,
            IntentFilter().apply {
                addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
                addAction(
                NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED
            )
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        scope.launch {
            // Process trước có thể bị kill không chạy finally; chỉ dọn namespace chat do lớp này sở hữu.
            manager.activeNotifications.filter { it.tag?.startsWith(PREFIX) == true }
                .forEach { manager.cancel(it.tag, it.id) }
            runtime.session.collectLatest { session ->
                if (session == null) return@collectLatest
                val tag = prefix(session)
                val signatures = mutableMapOf<Long, String>()
                val latest = mutableMapOf<Long, String>()
                var observedCapability = capabilityRevision.value
                try {
                    app.database.chatDao().conversations(session.ownerId, session.source)
                        .flatMapLatest { rooms ->
                            val eligible = rooms.filter { it.unreadCount > 0 && !it.muted }.take(25)
                            if (eligible.isEmpty()) flowOf(emptyList()) else combine(eligible.map { room ->
                                app.database.chatDao()
                                    .messages(session.ownerId, session.source, room.id, 25)
                                    .map { RoomMessages(room, it) }
                            }) { it.toList() }
                        }
                        .combine(runtime.visibleConversationFlow) { rooms, visible -> rooms.filter { it.room.id != visible } }
                        .combine(refresh) { rooms, _ -> rooms }
                        .combine(capabilityRevision) { rooms, revision -> rooms to revision }
                        .retryWhen { error, attempt ->
                            if (error is CancellationException || attempt >= 3) false else {
                                delay(1000)
                                true
                            }
                        }
                        .collect { (rooms, revision) ->
                            if (observedCapability != revision) {
                                signatures.clear()
                                observedCapability = revision
                            }
                            if (runtime.current() != session) return@collect
                            val permitted =
                                (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                                    app,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(
                                    app
                                ).areNotificationsEnabled() && NotificationChannels.channelEnabled(
                                    app,
                                    NotificationChannels.CHAT
                                )
                            if (!permitted) {
                                signatures.clear()
                                return@collect
                            }
                            val allowed =
                                rooms.filter { it.messages.any { m -> m.senderId.toString() != session.ownerId && m.status != "SEEN" } }
                            val ids = allowed.map { it.room.id }.toSet()
                            manager.activeNotifications.filter {
                                it.tag?.startsWith(tag + ".") == true && it.notification.extras.getLong(
                                    ROOM,
                                    -1
                                ) > 0 && it.notification.extras.getLong(ROOM) !in ids
                            }.forEach { manager.cancel(it.tag, it.id) }
                            signatures.keys.retainAll(ids)
                            latest.keys.retainAll(ids)
                            for (row in allowed) {
                                currentCoroutineContext().ensureActive()
                                if (runtime.current() != session) break
                                val signature = row.toString()
                                if (signatures[row.room.id] == signature) continue
                                val incoming =
                                    row.messages.firstOrNull { it.senderId.toString() != session.ownerId }?.localId.orEmpty()
                                val notification =
                                    notice(session, row, latest[row.room.id] == incoming)
                                if (runtime.current() != session) break
                                if (NotificationManagerCompat.from(app).areNotificationsEnabled()) {
                                    runCatching {
                                        manager.notify(
                                            "$tag.${row.room.id}",
                                            CHILD,
                                            notification
                                        )
                                    }.onSuccess {
                                        signatures[row.room.id] = signature
                                        latest[row.room.id] =
                                        incoming
                                    }
                                }
                            }
                            if (allowed.isEmpty()) manager.cancel("$tag.summary", SUMMARY)
                            else if (runtime.current() == session && NotificationManagerCompat.from(
                                    app
                                ).areNotificationsEnabled()
                            ) {
                                val total = allowed.sumOf { it.room.unreadCount }
                                val builder =
                                    NotificationCompat.Builder(app, NotificationChannels.CHAT)
                                        .setSmallIcon(R.drawable.ic_chat_bubble)
                                        .setContentTitle(app.getString(R.string.m5_chat_title))
                                        .setContentText(
                                            app.getString(
                                                R.string.m5_notification_summary,
                                                total
                                            )
                                        )
                                        .setNumber(total).setGroup(tag).setGroupSummary(true)
                                        .setOnlyAlertOnce(true)
                                        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                                        .setContentIntent(content(session, allowed.first().room.id))
                                runCatching {
                                    manager.notify(
                                        "$tag.summary",
                                        SUMMARY,
                                        builder.build()
                                    )
                                }
                            }
                            if (runtime.current() != session) cancel(session)
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { /* Room/permission có thể đổi khi app nền; không làm chết Application. */
                } finally {
                    cancel(session)
                }
            }
        }
    }

    private fun notice(session: ChatSession, value: RoomMessages, quiet: Boolean): Notification {
        val room = value.room
        val members = runCatching {
            Gson().fromJson(room.membersJson, Array<ChatMemberDto>::class.java).orEmpty()
        }.getOrDefault(emptyArray())
        val people = members.associate { user -> user.id to person(user) }
        val me = people[session.ownerId.toLongOrNull()] ?: Person.Builder()
            .setName(app.getString(R.string.m5_notification_you)).setKey(session.ownerId).build()
        val style = NotificationCompat.MessagingStyle(me).setGroupConversation(room.type == "group")
        if (room.type == "group") style.conversationTitle = room.title
        value.messages.asReversed()
            .filter { it.serverId != null || it.senderId.toString() == session.ownerId }
            .forEach { row ->
                val text = when (row.type) {
                    "image" -> app.getString(R.string.m5_chat_image)
                    "voice" -> app.getString(R.string.m5_chat_voice)
                    else -> row.content.take(
                        5000
                    )
                }
                val body =
                    if (row.status == "FAILED") app.getString(R.string.m5_notification_reply_failed) + ": " + text else text
                style.addMessage(
                    body,
                    row.createdAt,
                    if (row.senderId.toString() == session.ownerId) me else people[row.senderId]
                        ?: Person.Builder().setName(room.title).setKey(row.senderId.toString())
                            .build()
                )
            }
        val input = RemoteInput.Builder(ChatActionReceiver.INPUT)
            .setLabel(app.getString(R.string.m5_notification_reply)).build()
        val action = NotificationCompat.Action.Builder(
            R.drawable.ic_chat_bubble,
            app.getString(R.string.m5_notification_reply),
            reply(session, room.id)
        )
            .addRemoteInput(input).setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build()
        val through = value.messages.filter { it.senderId.toString() != session.ownerId }
            .mapNotNull { it.serverId }.maxOrNull() ?: 0L
        val seen = NotificationCompat.Action.Builder(
            R.drawable.ic_chat_done_all,
            app.getString(R.string.m5_notification_seen),
            seen(session, room.id, through)
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).build()
        return NotificationCompat.Builder(app, NotificationChannels.CHAT)
            .setSmallIcon(R.drawable.ic_chat_bubble).setContentTitle(room.title)
            .setStyle(style).setNumber(room.unreadCount).setGroup(prefix(session))
            .setOnlyAlertOnce(quiet)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN).setSilent(quiet)
            .setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(content(session, room.id)).addAction(action).addAction(seen)
            .addExtras(Bundle().apply { putLong(ROOM, room.id) }).build()
    }

    private fun person(user: ChatMemberDto): Person {
        val builder = Person.Builder().setName(user.displayName?.takeIf { it.isNotBlank() }
            ?: app.getString(R.string.app_name)).setKey(user.id.toString())
        // Dùng bitmap từ Glide cache với kích thước64; copy trước clear để không giữ bitmap pool đã trả.
        // Không chờ mạng trước khi hiện tin mới. Nếu cache chưa có, SystemUI dùng tên/initial thật.
        user.avatarUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val future =
                Glide.with(app).asBitmap().load(url).onlyRetrieveFromCache(true).submit(64, 64)
            try {
                builder.setIcon(
                    IconCompat.createWithBitmap(
                        future.get(150, TimeUnit.MILLISECONDS)
                            .copy(Bitmap.Config.ARGB_8888, false)
                    )
                )
            } catch (_: Exception) {
            } finally {
                Glide.with(app).clear(future)
            }
        }
        return builder.build()
    }

    private fun reply(session: ChatSession, room: Long): PendingIntent {
        val intent = Intent(app, ChatActionReceiver::class.java).setAction(ChatActionReceiver.REPLY)
            .setData(Uri.parse("momentbox-internal://chat/${hash(session)}/${session.generation}/$room/reply"))
            .putExtra("owner", session.ownerId).putExtra("source", session.source)
            .putExtra("generation", session.generation).putExtra("conversationId", room)
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            app,
            19011,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutable
        )
    }

    private fun seen(session: ChatSession, room: Long, through: Long): PendingIntent {
        val intent = Intent(app, ChatActionReceiver::class.java).setAction(ChatActionReceiver.SEEN)
            .setData(Uri.parse("momentbox-internal://chat/${hash(session)}/${session.generation}/$room/seen/$through"))
            .putExtra("owner", session.ownerId).putExtra("source", session.source)
            .putExtra("generation", session.generation).putExtra("conversationId", room)
            .putExtra("through", through)
        return PendingIntent.getBroadcast(
            app,
            19013,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun content(session: ChatSession, room: Long): PendingIntent {
        val intent = ChatActivity.intent(app, room)
            .setData(Uri.parse("momentbox-internal://chat/${hash(session)}/${session.generation}/$room/open"))
            .putExtra("m5Notification", true).putExtra("owner", session.ownerId)
            .putExtra("source", session.source).putExtra("generation", session.generation)
        return PendingIntent.getActivity(
            app,
            19012,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancel(session: ChatSession) {
        val prefix = prefix(session) + "."
        manager.activeNotifications.filter {
            it.tag?.startsWith(
                prefix
            ) == true
        }.forEach { manager.cancel(it.tag, it.id) }
    }

    companion object {
        const val PREFIX = "m5.chat."
        const val ROOM = "m5.room"
        private const val CHILD = 19001
        private const val SUMMARY = 19000
        private fun hash(session: ChatSession) = MessageDigest.getInstance("SHA-256")
            .digest((session.ownerId + "|" + session.source).toByteArray()).take(12)
            .joinToString("") { "%02x".format(it) }

        private fun prefix(session: ChatSession) = PREFIX + hash(session) + "." + session.generation
    }
}
