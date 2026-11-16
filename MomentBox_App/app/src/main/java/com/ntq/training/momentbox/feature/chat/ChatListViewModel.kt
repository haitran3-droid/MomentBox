package com.ntq.training.momentbox.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntq.training.momentbox.core.realtime.ChatRuntime
import com.ntq.training.momentbox.data.local.db.ChatDao
import com.ntq.training.momentbox.data.local.db.ChatUserEntity
import com.ntq.training.momentbox.data.local.db.ConversationPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatListState(
    val owner: String = "",
    val rows: List<ConversationPreview> = emptyList(),
    val users: List<ChatUserEntity> = emptyList(),
    val typing: Map<Long, Set<Long>> = emptyMap()
)

/** flatMapLatest hủy query owner cũ trước khi collect owner mới. Màn chỉ render snapshot bất biến;
 * search nằm trong ViewModel nên rotation không làm mất bộ lọc hoặc chạy SQL đồng bộ trên Main. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListViewModel(
    private val runtime: ChatRuntime,
    private val dao: ChatDao,
    private val saved: SavedStateHandle
) : ViewModel() {
    val search = saved.getStateFlow("search", "")
    val selected = saved.getStateFlow("conversationId", -1L)
    fun search(value: String) {
        saved["search"] = value
    }

    fun select(id: Long) {
        if (id > 0) {
            runtime.session.value?.let { saved["selectionScope"] = it.ownerId + "|" + it.source }
            saved["conversationId"] = id
        }
    }

    fun clearSelection() {
        saved["conversationId"] = -1L
    }

    init {
        viewModelScope.launch {
            runtime.session.collect { session ->
                if (session != null) {
                    val scope = session.ownerId + "|" + session.source
                    if (saved.get<String>("selectionScope")
                            ?.let { it != scope } == true
                    ) clearSelection()
                    saved["selectionScope"] = scope
                }
            }
        }
    }

    val state = combine(
        runtime.session,
        search
    ) { session, query -> session to query }.flatMapLatest { (session, query) ->
        if (session == null) flowOf(ChatListState()) else combine(
            dao.previews(
                session.ownerId,
                session.source,
                query
            ), dao.users(session.ownerId, session.source), runtime.typing
        ) { rows, users, typing ->
            if (runtime.session.value != session) ChatListState() else ChatListState(
                session.ownerId,
                rows,
                users.filter { it.id.toString() != session.ownerId },
                typing
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatListState())

    fun refresh() {
        viewModelScope.launch { runtime.sync() }
    }
}