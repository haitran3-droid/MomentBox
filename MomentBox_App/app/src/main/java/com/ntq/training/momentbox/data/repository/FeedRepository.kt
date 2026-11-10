package com.ntq.training.momentbox.data.repository

import com.ntq.training.momentbox.domain.model.Comment
import com.ntq.training.momentbox.domain.model.FeedItem
import com.ntq.training.momentbox.domain.model.Post
import com.ntq.training.momentbox.domain.model.Story
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MultipartBody
import okhttp3.RequestBody

interface FeedRepository {
    val createdPostsFlow: StateFlow<List<Post>>
        get() = EMPTY_CREATED

    suspend fun createPost(
        fields: Map<String, RequestBody>,
        media: List<MultipartBody.Part>,
        voice: MultipartBody.Part?,
    ): Result<Post> = Result.failure(UnsupportedOperationException("Posting unsupported"))

    val postsMapFlow: StateFlow<Map<Long, Post>>
    val followingMapFlow: StateFlow<Map<Long, Boolean>>
        get() = EMPTY_FOLLOWING

    companion object {
        private val EMPTY_CREATED: StateFlow<List<Post>> = MutableStateFlow(emptyList())
        private val EMPTY_FOLLOWING: StateFlow<Map<Long, Boolean>> = MutableStateFlow(emptyMap())
    }

    fun observePost(postId: Long): Flow<Post?>

    suspend fun getStories(): Result<List<Story>>

    suspend fun getFeed(page: Int, pageSize: Int = 10): Result<List<FeedItem>>

    suspend fun getPostDetail(postId: Long): Result<Post>

    suspend fun toggleLike(postId: Long): Result<Post>

    suspend fun toggleBookmark(postId: Long): Result<Post>

    suspend fun deletePost(postId: Long): Result<Unit>

    suspend fun loadFollowingState(userId: Long): Result<Boolean> =
        Result.failure(IllegalStateException("Follow relationship is not available"))

    suspend fun toggleFollowUser(userId: Long, currentFlowing: Boolean): Result<Boolean>

    suspend fun getComments(
        postId: Long,
        page: Int = 1,
        pageSize: Int = 20,
        sort: String = "top",
        parentId: Long? = null,
    ): Result<List<Comment>>

    suspend fun postComment(postId: Long, content: String, parentId: Long? = null): Result<Comment>

    suspend fun toggleLikeComment(commentId: Long, currentLiked: Boolean): Result<Boolean>

    fun clearCache()
}
