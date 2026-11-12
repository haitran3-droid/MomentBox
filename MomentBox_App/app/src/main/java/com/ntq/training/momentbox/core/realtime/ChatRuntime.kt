package com.ntq.training.momentbox.core.realtime

import kotlin.math.sin
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.gson.Gson
import com.ntq.training.momentbox.data.local.db.AppDatabase
import com.ntq.training.momentbox.data.local.db.MessageEntity
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.data.local.prefs.TokenStore
import com.ntq.training.momentbox.data.remote.api.ChatApi
import com.ntq.training.momentbox.data.remote.dto.ChatMessageDto
import com.ntq.training.momentbox.data.repository.ChatRepository
import com.ntq.training.momentbox.data.repository.ChatSession
import com.ntq.training.momentbox.domain.model.User
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ChatRuntime(
    private val tokens: TokenStore, private val settings: SettingsDataStore,
    private val socket: SocketManager, private val database: AppDatabase, api: ChatApi,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : DefaultLifecycleObserver {
    private val gate = Any()
    private val _session = MutableStateFlow<ChatSession?>(null)
    val session = _session.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _typing = MutableStateFlow<Map<Long, Set<Long>>>(emptyMap())
    val typing = _typing.asStateFlow()
    val repository = ChatRepository(database, api) { current() }
    private var workers: Job? = null
    private val syncMutex = Mutex()
    private var backgroundTimer: Job? = null
    private var foreground = false
    private var visibleRoom: Long? = null
    private var visiblePanel: String? = null
    private var readingRoom: Long? = null
    val visibleConversation: Long? get() = synchronized(gate) { visibleRoom.takeIf { foreground } }
    private val typingJobs = mutableMapOf<Pair<Long, Long>, Job>()

    init {
        tokens.addSnapshotListener { value ->
            synchronized(gate) { if (value.generation != _session.value?.generation || value.accessToken.isNullOrBlank()) invalidateLocked() }
        }
        scope.launch {
            settings.dataSourceFlow.collect { source ->
                synchronized(gate) { if (_session.value?.source?.substringBefore('|') != source) invalidateLocked() }
            }
        }
    }

    /** Kiểm lại cả token và nguồn ở mỗi ranh giới suspend; getter không tự phục hồi phiên đã hủy. */
    suspend fun current(): ChatSession? {
        val expected = _session.value ?: return null
        return expected.takeIf {
            tokens.getSessionGeneration() == it.generation && !tokens.getAccessToken()
                .isNullOrBlank() &&
                    settings.getDataSource() + "|" + socket.resolveSocketOrigin() == it.source && _session.value == it
        }
    }

    suspend fun activate() {
        val snapshot = tokens.snapshot()
        val source = settings.getDataSource()
        if (snapshot.accessToken.isNullOrBlank() || source != SettingsDataStore.SOURCE_MB_SERVER) return
        val user = runCatching {
            Gson().fromJson(
                settings.getSavedUserJson(),
                User::class.java
            )
        }.getOrNull() ?: return
        val next = ChatSession(
            user.id.toString(),
            source + "|" + socket.resolveSocketOrigin(),
            snapshot.generation
        )
        synchronized(gate) {
            if (tokens.getSessionGeneration() != snapshot.generation) return
            if (_session.value == next) {
                if (foreground) socket.connect()
                return
            }
            invalidateLocked()
            _session.value = next
            workers = scope.launch {
                supervisorScope {
                    launch { sync(next) }
                    launch { socket.isConnectedFlow.collect { if (it) sync(next) } }
                    launch {
                        val attemptedTokens = mutableSetOf<String>()
                        socket.state.collect { state ->
                            if (state.phase == SocketPhase.AUTH_EXPIRED && current() == next) {
                                val token = tokens.cachedAccessToken()
                                if (token != null && attemptedTokens.add(token)) sync(next)
                            }
                        }
                    }

                    listOf(
                        "presence:update",
                        "typing",
                        "message:new",
                        "message:status"
                    ).forEach { event ->
                        launch {
                            while (isActive && current() == next) {
                                try {
                                    socket.events(event).collect { value ->
                                        if (value.generation == next.generation && value.owner.ownerId == next.ownerId && value.origin == next.source.substringAfter(
                                                '|'
                                            ) && current() == next
                                        ) handle(next, value)
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    _error.value = "CHAT_RESYNC_REQUIRED"
                                    sync(next)
                                    delay(1000)
                                }
                            }
                        }
                    }
                }
            }
            if (foreground) socket.connect()
        }
    }

    suspend fun sync(expected: ChatSession? = _session.value) {
        if (expected == null) return
        syncMutex.lock()
        try {
            if (current() != expected) return
            val before = database.chatDao().conversations(expected.ownerId, expected.source).first()
                .associate {
                    it.id to database.chatDao()
                        .newestServerId(expected.ownerId, expected.source, it.id)
                }
            if (repository.sync(expected) && current() == expected) {
                val rooms =
                    database.chatDao().conversations(expected.ownerId, expected.source).first()
                for (room in rooms) {
                    if (current() != expected) return
                    // sync vừa chèn lastMessage; mốc phải lấy trước sync để không dừng sớm mất phần giữa.
                    repository.catchUp(expected, room.id, before[room.id] ?: 0L)
                }
                _error.value = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (current() == expected) _error.value = "CHAT_SYNC_FAILED"
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun handle(expected: ChatSession, event: ScopedChatEvent) {
        val p = event.payload
        when (event.event) {
            "message:new" -> p.optJSONObject("message")?.let {
                repository.receive(
                    expected,
                    Gson().fromJson(it.toString(), ChatMessageDto::class.java),
                    synchronized(gate) { readingRoom.takeIf { foreground } })
                if (database.chatDao().conversation(
                        expected.ownerId,
                        expected.source,
                        it.optLong("conversationId")
                    ) == null
                ) sync(expected)
            }

            "presence:update" -> repository.updatePresence(
                expected,
                p.optLong("userId"),
                p.optBoolean("online"),
                p.optString("lastSeenAt").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { Instant.parse(it).toEpochMilli() })

            "typing" -> {
                val room = p.optLong("conversationId")
                val user = p.optLong("userId")
                if (user.toString() == expected.ownerId || database.chatDao()
                        .conversation(expected.ownerId, expected.source, room) == null
                ) return
                synchronized(gate) {
                    val key = room to user
                    typingJobs.remove(key)?.cancel()
                    setTyping(room, user, p.optBoolean("isTyping"))
                    if (p.optBoolean("isTyping")) typingJobs[key] = scope.launch {
                        delay(5000)
                        synchronized(gate) {
                        if (_session.value == expected) {
                            setTyping(room, user, false)
                            typingJobs.remove(key)
                        }
                    }
                    }
                }
            }

            "message:status" -> repository.updateStatus(
                expected,
                p.optLong("conversationId"),
                p.optLong("messageId"),
                p.optString("clientId").takeUnless { it.isBlank() || it == "null" },
                p.optString("status"),
                p.optLong("userId", -1)
            )
        }
    }

    /** Một room chỉ visible khi panel đang RESUMED, app foreground và ở đáy danh sách.
     * Main giữ Fragment ẩn STARTED nên không thể dùng STARTED để tự động đánh seen. */
    fun visible(room: Long?, active: Boolean, panel: String, reading: Boolean = true) {
        synchronized(gate) {
            if (active) {
                visibleRoom = room
                visiblePanel = panel
                readingRoom = room.takeIf { reading }
            } else if (visiblePanel == panel) {
                visibleRoom = null
                visiblePanel = null
                readingRoom = null
            }
        }
    }

    fun typing(room: Long, active: Boolean) {
        val expected = _session.value ?: return
        socket.emitForSession(
            expected,
            if (active) "typing:start" else "typing:stop",
            JSONObject().put("conversationId", room)
        )
    }

    fun seen(room: Long, through: Long) {
        val expected = _session.value ?: return
        if (!synchronized(gate) { foreground && readingRoom == room }) return
        if (socket.emitForSession(
                expected,
                "message:seen",
                JSONObject().put("conversationId", room).put("messageId", through)
            )
        ) scope.launch { repository.read(expected, room, through) }
    }

    /** Job thuộc Application, không bị rotation hủy giữa local commit và emit. Một callback
     * hoàn tất đúng một lần; ACK đến sau logout bị cả transport và Room session fence loại. */
    var retryMedia: ((ChatSession, MessageEntity) -> Unit)? =
        null

    fun send(room: Long, text: String, retry: String? = null) {
        val expected = _session.value ?: return
        scope.launch {
            try {
                val row = repository.prepare(expected, room, text, retry = retry) ?: return@launch
                if (row.type != "text" && row.mediaUrl == null) retryMedia?.invoke(expected, row)
                else transmit(expected, row)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (current() == expected) _error.value = "CHAT_SEND_FAILED"
            }
        }
    }

    /** Media đã có local UUID và upload URL; emit đúng row đó, không tạo thêm optimistic row. */
    fun sendPrepared(
        expected: ChatSession,
        row: MessageEntity
    ) {
        scope.launch {
            try {
                transmit(expected, row)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                repository.failed(expected, row.localId)
            }
        }
    }

    private suspend fun transmit(
        expected: ChatSession,
        row: MessageEntity
    ) {
        if (current() != expected) return
        val finished = AtomicBoolean(false)
        val callback: (JSONObject?) -> Unit = { response ->
            if (finished.compareAndSet(false, true)) scope.launch {
                try {
                    val message = response?.optJSONObject("message")
                    if (response?.optBoolean("ok") == true && message != null) {
                        if (repository.receive(
                                expected,
                                Gson().fromJson(message.toString(), ChatMessageDto::class.java)
                            )
                        ) pop()
                    } else repository.failed(expected, row.localId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    repository.failed(expected, row.localId)
                }
            }
        }
        val payload = JSONObject().put("conversationId", row.conversationId)
            .put("clientId", row.clientId).put("type", row.type).put("content", row.content)
        row.mediaUrl?.let {
            payload.put(
                "mediaUrl",
                it
            )
        }
        row.durationMs?.let { payload.put("durationMs", it) }
        socket.emitForSession(expected, "message:send", payload, callback)
        delay(5000)
        callback(null)
    }

    private suspend fun pop() {
        withContext(Dispatchers.IO) {
            var track: AudioTrack? = null
            try {
                val rate = 22050
                val samples = ShortArray(rate / 30) { i ->
                    (sin(2 * Math.PI * 880 * i / rate) * 5000 * (1.0 - i.toDouble() / (rate / 30))).toInt()
                        .toShort()
                }
                track = AudioTrack.Builder().setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.size * 2).build()
                track.write(samples, 0, samples.size)
                track.play()
                delay(100)
            } catch (_: Exception) {
            } finally {
                track?.release()
            }
        }
    }

    private fun setTyping(room: Long, user: Long, active: Boolean) {
        val users = _typing.value[room].orEmpty().let { if (active) it + user else it - user }
        _typing.value =
            if (users.isEmpty()) _typing.value - room else _typing.value + (room to users)
    }

    private fun invalidateLocked() {
        _session.value = null
        visibleRoom = null
        visiblePanel = null
        readingRoom =
            null
            workers?.cancel()
            workers = null
        typingJobs.values.forEach { it.cancel() }
        typingJobs.clear()
        _typing.value =
            emptyMap()
            _error.value = null
    }

    override fun onStart(owner: LifecycleOwner) {
        synchronized(gate) {
            foreground = true
            backgroundTimer?.cancel()
            backgroundTimer = null
            if (_session.value != null) socket.connect()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        synchronized(gate) {
            foreground = false
            visibleRoom = null
            visiblePanel = null
            readingRoom =
            null
            backgroundTimer?.cancel()
            backgroundTimer =
                scope.launch { delay(300_000)
                synchronized(gate) { if (!foreground) socket.disconnect() } }
        }
    }
}