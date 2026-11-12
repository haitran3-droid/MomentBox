package com.ntq.training.momentbox.feature.profile

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntq.training.momentbox.data.repository.AuthRepository
import com.ntq.training.momentbox.data.repository.FeedProfileUpdate
import com.ntq.training.momentbox.data.repository.FeedRepository
import com.ntq.training.momentbox.data.repository.ProfileData
import com.ntq.training.momentbox.domain.model.User
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class ProfileViewModel(
    private val repository: ProfileData,
    private val saved: SavedStateHandle,
    private val auth: AuthRepository? = null,
    private val feed: FeedRepository? = null
) : ViewModel() {
    private val mutable = MutableStateFlow(ProfileUiState())
    val state = mutable.asStateFlow()
    private var epoch = 0L
    private var header: Job? = null
    private var headerRequest = 0L
    private var action: Job? = null
    private var people: Job? = null
    private val pages = mutableMapOf<ProfileTab, Job>()
    private val requests = mutableMapOf<ProfileTab, Long>()

    init {
        viewModelScope.launch {
            repository.sessions.collect { scope ->
                ++epoch
                header?.cancel()
                action?.cancel()
                people?.cancel()
                pages.values.forEach { it.cancel() }
                pages.clear()
                requests.clear()
                if (scope == null) {
                    mutable.value = ProfileUiState(headerLoading = false)
                    return@collect
                }
                val id = saved.get<Long>("userId")?.takeIf { it > 0 } ?: scope.ownerId
                val me = id == scope.ownerId
                val tabs =
                    if (me) ProfileTab.entries else listOf(ProfileTab.POSTS, ProfileTab.VIDEOS)
                mutable.value = ProfileUiState(scope, id, isMe = me, tabs = tabs)
                refreshHeader()
                tabs.forEach { request(it, true) }
            }
        }
        if (feed != null) viewModelScope.launch {
            feed.profileUpdates.collect { update ->
                val current = mutable.value
                val scope = current.scope ?: return@collect
                if (scope.generation != update.generation || scope.source != update.source) return@collect

                // Đổi thành viên của trang Đã lưu từ phản hồi server, để thứ tự và total vẫn đúng khi phân trang.
                when (val change = update.change) {
                    FeedProfileUpdate.Change.Bookmarks -> if (current.isMe) refresh(ProfileTab.SAVED)
                    FeedProfileUpdate.Change.Posts -> if (current.isMe) {
                        refresh(ProfileTab.POSTS)
                        refresh(ProfileTab.VIDEOS)
                        refreshHeader()
                    }

                    is FeedProfileUpdate.Change.Follow -> if (!current.isMe && current.userId == change.userId) {
                        val user = current.user
                        if (user != null && user.isFollowing != change.following) {
                            val delta = if (change.following) 1 else -1
                            mutable.update {
                                it.copy(
                                    user = user.copy(
                                        isFollowing = change.following,
                                        followerCount = (user.followerCount + delta).coerceAtLeast(0)
                                    )
                                )
                            }
                        }
                        if (!current.actionBusy) refreshHeader()
                    }
                }
            }
        }
        if (auth != null) viewModelScope.launch {
            auth.userFlow.collect { user ->
                val current = mutable.value
                if (current.isMe && current.scope != null && user?.id == current.scope.ownerId) mutable.update {
                    it.copy(
                        user = user
                    )
                }
            }
        }
    }

    fun refreshHeader() {
        val before = mutable.value
        val scope = before.scope ?: return
        val captured = epoch
        val read = ++headerRequest
        header?.cancel()
        mutable.value = before.copy(headerLoading = true, headerError = null)
        header = viewModelScope.launch {
            try {
                val cache = repository.cachedUser(scope, before.userId)
                if (epoch != captured || headerRequest != read || mutable.value.scope != scope) return@launch
                if (cache != null) mutable.update { it.copy(user = cache) }
                val user = repository.user(scope, before.userId)
                if (epoch == captured && headerRequest == read && mutable.value.scope == scope) mutable.update {
                    it.copy(
                        user = user,
                        headerLoading = false,
                        headerError = null
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch == captured && headerRequest == read && mutable.value.scope == scope) mutable.update {
                    it.copy(
                        headerLoading = false,
                        headerError = "PROFILE_LOAD_FAILED"
                    )
                }
            }
        }
    }

    fun loadMore(tab: ProfileTab) = request(tab, false)
    fun refresh(tab: ProfileTab) = request(tab, true)
    private fun request(tab: ProfileTab, refresh: Boolean) {
        val before = mutable.value
        val scope = before.scope ?: return
        if (tab !in before.tabs) return
        val previous = before.pages[tab] ?: ProfilePageState()
        if (!refresh && (previous.loading || !previous.hasMore)) return
        pages[tab]?.cancel()
        val id = (requests[tab] ?: 0) + 1
        requests[tab] = id
        val captured = epoch
        val page = if (refresh) 1 else previous.nextPage
        mutable.update {
            it.copy(
                pages = it.pages + (tab to previous.copy(
                    loading = true,
                    error = null
                ))
            )
        }
        pages[tab] = viewModelScope.launch {
            try {
                val response = repository.page(scope, before.userId, tab, page, 30)
                if (epoch != captured || requests[tab] != id || mutable.value.scope != scope) return@launch
                mutable.update { state ->
                    val old = state.pages[tab] ?: ProfilePageState()
                    val merged =
                        ((if (refresh) emptyList() else old.items) + response.posts).distinctBy { it.id }
                    state.copy(
                        pages = state.pages + (tab to ProfilePageState(
                            merged,
                            false,
                            true,
                            page + 1,
                            response.total,
                            response.hasMore
                        ))
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch == captured && requests[tab] == id && mutable.value.scope == scope) mutable.update { state ->
                    val old = state.pages[tab] ?: ProfilePageState()
                    state.copy(
                        pages = state.pages + (tab to old.copy(
                            loading = false,
                            error = "PROFILE_PAGE_FAILED"
                        ))
                    )
                }
            }
        }
    }

    /** Dialog giữ form trong lifecycle riêng; chỉ ACK mới đóng form và đổi user hiển thị. */
    fun editProfile(name: String, bio: String, location: String) = mutate(true) {
        requireNotNull(auth).editProfile(name, bio, location).getOrThrow()
    }

    fun toggleFollow() {
        val user = mutable.value.user ?: return
        mutate(false) {
            val followed =
                requireNotNull(feed).toggleFollowUser(user.id, user.isFollowing).getOrThrow()
            val delta = if (followed == user.isFollowing) 0 else if (followed) 1 else -1
            user.copy(
                isFollowing = followed,
                followerCount = (user.followerCount + delta).coerceAtLeast(0)
            )
        }
    }

    fun uploadAvatar(context: Context, uri: Uri) {
        val application = context.applicationContext
        mutate(false) {
            var copied: File? = null
            try {
                withContext(Dispatchers.IO) {
                    require(uri.scheme == "content" && uri.authority == application.packageName + ".media")
                    copied = File.createTempFile("m5-avatar-", ".jpg", application.cacheDir)
                    application.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input)
                        copied!!.outputStream().use { input.copyTo(it) }
                    }
                    val bounds =
                        BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(copied!!.path, bounds)
                    require(bounds.outWidth > 0 && bounds.outWidth == bounds.outHeight) { "AVATAR_MUST_BE_SQUARE" }
                }
                requireNotNull(auth).updateAvatar(requireNotNull(copied)).getOrThrow()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    copied?.delete()
                    // Chỉ xóa file crop mới do OwnedImageFiles sinh trong create-media; không xóa
                    // content URI Gallery, ảnh nhật ký hoặc file ngoài directory đã xác minh.
                    if (uri.authority == application.packageName + ".media") uri.lastPathSegment?.takeIf {
                        it.matches(
                            Regex("image-[a-fA-F0-9-]{36}\\.jpg")
                        )
                    }?.let { name ->
                        val directory =
                            File(application.filesDir, "create-media").canonicalFile
                        val file = File(directory, name).canonicalFile
                        if (file.parentFile == directory) file.delete()
                    }
                }
            }
        }
    }

    private fun mutate(
        edit: Boolean,
        block: suspend () -> User
    ) {
        val before = mutable.value
        val scope = before.scope ?: return
        if (before.actionBusy || before.user == null) return
        val captured = epoch
        header?.cancel()
        ++headerRequest
        mutable.update { it.copy(actionBusy = true, actionError = null) }
        action = viewModelScope.launch {
            try {
                val result = block()
                if (epoch == captured && mutable.value.scope == scope) {
                    mutable.update {
                        it.copy(
                            user = result,
                            actionBusy = false,
                            actionError = null,
                            editSaved = it.editSaved + if (edit) 1 else 0
                        )
                    }
                    if (!before.isMe) refreshHeader()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (epoch == captured && mutable.value.scope == scope) mutable.update {
                    it.copy(
                        actionBusy = false,
                        actionError = if (failure is UnsupportedOperationException) "UNSUPPORTED" else "UPDATE_FAILED"
                    )
                }
            }
        }
    }

    fun loadPeople() {
        val before = mutable.value
        val scope = before.scope ?: return
        if (before.peopleLoading) return
        val captured = epoch
        mutable.update { it.copy(peopleLoading = true, peopleError = false) }
        people = viewModelScope.launch {
            try {
                val users = repository.suggestions(scope)
                if (epoch == captured && mutable.value.scope == scope) mutable.update {
                    it.copy(
                        people = users,
                        peopleLoading = false
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch == captured && mutable.value.scope == scope) mutable.update {
                    it.copy(
                        peopleLoading = false,
                        peopleError = true
                    )
                }
            }
        }
    }
}
