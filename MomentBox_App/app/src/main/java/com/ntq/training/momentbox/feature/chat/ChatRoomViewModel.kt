package com.ntq.training.momentbox.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ntq.training.momentbox.core.realtime.ChatRuntime
import com.ntq.training.momentbox.data.local.db.ChatDao
import com.ntq.training.momentbox.data.local.db.ChatUserEntity
import com.ntq.training.momentbox.data.local.db.ConversationEntity
import com.ntq.training.momentbox.data.local.db.MessageEntity
import com.ntq.training.momentbox.data.remote.dto.ChatMemberDto
import com.ntq.training.momentbox.data.repository.ChatSession
import com.ntq.training.momentbox.MomentBoxApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class RoomState(
    val room: ConversationEntity? = null,
    val messages: List<MessageEntity> = emptyList(),
    val users: List<ChatUserEntity> = emptyList(),
    val owner: String = ""
)

/** Room Flow là nguồn render; REST page là tác vụ bổ sung. Cursor/selection/draft có phạm vi
 * ViewModel/SavedStateHandle, không truyền list tin qua Bundle. Mỗi đổi phòng hủy pager cũ. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRoomViewModel(
    private val runtime: ChatRuntime,
    private val dao: ChatDao,
    private val saved: SavedStateHandle,
    private val application: MomentBoxApp
) : ViewModel() {
    private var quickTarget: Pair<ChatSession, Long>? =
        null
    val quick = QuickChatRecorder(application) { audio ->
        val target = quickTarget
        if (target == null) false else {
            application.chatMediaRepository.send(
                target.first,
                target.second,
                audio.uri,
                "voice",
                audio.durationMs,
                ownedQuick = true
            ); true
        }
    }

    fun startQuick() {
        val session = runtime.session.value ?: return; if (state.value.room == null) return
        quickTarget = session to selected.value; quick.start()
    }

    fun stopQuick(cancel: Boolean) {
        quick.finish(cancel)
    }

    /** Activity result có thể về sau rotate hoặc đổi phòng. Lưu caller scope dưới dạng primitive;
     * không dùng selected hiện tại để gửi nhầm media sang phòng mới. Repository kiểm lại generation. */
    fun beginAttachment(): Boolean {
        val session = runtime.session.value ?: return false
        if (state.value.room == null || saved.get<Boolean>("attachmentPending") == true) return false
        saved["attachmentPending"] = true; saved["attachmentRoom"] =
            selected.value; saved["attachmentOwner"] = session.ownerId
        saved["attachmentSource"] = session.source; saved["attachmentGeneration"] =
            session.generation
        return true
    }

    fun attachment(uri: String?, type: String, duration: Long? = null) {
        if (saved.get<Boolean>("attachmentPending") != true) return
        val room = saved.get<Long>("attachmentRoom") ?: -1
        val owner = saved.get<String>("attachmentOwner");
        val source = saved.get<String>("attachmentSource");
        val generation = saved.get<Long>("attachmentGeneration")
        saved["attachmentPending"] = false
        if (uri != null && room > 0 && owner != null && source != null && generation != null) application.chatMediaRepository.send(
            ChatSession(owner, source, generation),
            room,
            uri,
            type,
            duration
        )
    }

    override fun onCleared() {
        quick.close(); super.onCleared()
    }

    val selected = saved.getStateFlow("conversationId", -1L)
    private val limit = MutableStateFlow(60)
    val error = MutableStateFlow(false);
    val busy = MutableStateFlow(false)
    private var cursor: Long? = null;
    private var hasMore = true;
    private var pager: Job? = null
    val state = combine(runtime.session, selected, limit) { session, room, count ->
        Triple(
            session,
            room,
            count
        )
    }.flatMapLatest { (session, room, count) ->
        if (session == null || room <= 0) flowOf(RoomState()) else combine(
            dao.conversationFlow(
                session.ownerId,
                session.source,
                room
            ),
            dao.messages(session.ownerId, session.source, room, count),
            dao.users(session.ownerId, session.source)
        ) { conversation, messages, users ->
            val members = runCatching {
                Gson().fromJson<List<ChatMemberDto>>(conversation?.membersJson,
                    object : TypeToken<List<ChatMemberDto>>() {}.type)
            }.getOrNull().orEmpty()
            if (runtime.session.value != session || members.none { it.id.toString() == session.ownerId }) RoomState()
            else RoomState(conversation, messages, users, session.ownerId)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RoomState())

    init {
        val initial = saved.get<Long>("conversationId") ?: -1
        if (initial > 0 && !saved.contains("draft:$initial")) saved["draft:$initial"] =
            saved.get<String>("seedDraft").orEmpty().take(5000)
    }

    init {
        viewModelScope.launch {
            combine(runtime.session, selected) { a, b -> a to b }.collectLatest { (session, room) ->
                pager?.cancel(); cursor = null; hasMore = true; limit.value = 60
                if (session != null && room > 0) {
                    if (dao.conversation(
                            session.ownerId,
                            session.source,
                            room
                        ) == null
                    ) runtime.sync(session)
                    load(false)
                }
            }
        }
    }

    fun select(room: Long) {
        if (room > 0 && room != selected.value) {
            quick.finish(true); quickTarget = null; saved["conversationId"] = room
        }
    }

    var draft: String
        get() = saved["draft:${selected.value}"] ?: ""
        set(value) {
            saved["draft:${selected.value}"] = value
        }

    fun load(older: Boolean = true) {
        if (pager?.isActive == true || (older && !hasMore)) return
        val room = selected.value
        pager = viewModelScope.launch {
            busy.value = true; error.value = false
            try {
                val expected = checkNotNull(runtime.current())
                val page = runtime.repository.page(expected, room, if (older) cursor else null)
                if (selected.value == room) {
                    cursor = page.first; hasMore = page.second; if (older) limit.value += 30
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (selected.value == room) error.value = true
            } finally {
                if (selected.value == room) busy.value = false
            }
        }
    }

    fun send() {
        val text = draft.trim(); if (text.isEmpty()) return; runtime.send(
            selected.value,
            text
        ); draft = ""
    }

    fun retry(id: String) {
        runtime.send(selected.value, "", id)
    }
}
