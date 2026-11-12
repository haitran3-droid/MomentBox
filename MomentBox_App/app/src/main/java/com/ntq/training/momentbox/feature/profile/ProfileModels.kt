package com.ntq.training.momentbox.feature.profile

import com.ntq.training.momentbox.domain.model.User
import com.ntq.training.momentbox.domain.model.Post

enum class ProfileTab { POSTS, VIDEOS, SAVED }

data class ProfileScope(val ownerId: Long, val source: String, val generation: Long)
data class ProfilePage(val posts: List<Post>, val page: Int, val total: Int?, val hasMore: Boolean)
data class ProfilePageState(
    val items: List<Post> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val nextPage: Int = 1,
    val total: Int? = null,
    val hasMore: Boolean = true,
    val error: String? = null
)

data class ProfileUiState(
    val scope: ProfileScope? = null,
    val userId: Long = 0,
    val user: User? = null,
    val isMe: Boolean = false,
    val tabs: List<ProfileTab> = emptyList(),
    val pages: Map<ProfileTab, ProfilePageState> = emptyMap(),
    val headerLoading: Boolean = true,
    val headerError: String? = null,
    val actionBusy: Boolean = false,
    val actionError: String? = null,
    val editSaved: Long = 0,
    val people: List<User> = emptyList(),
    val peopleLoading: Boolean = false,
    val peopleError: Boolean = false
)
