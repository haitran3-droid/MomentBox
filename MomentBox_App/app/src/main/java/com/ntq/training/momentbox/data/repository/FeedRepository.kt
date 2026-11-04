package com.ntq.training.momentbox.data.repository

import com.ntq.training.momentbox.domain.model.Comment
import com.ntq.training.momentbox.domain.model.FeedItem
import com.ntq.training.momentbox.domain.model.Post
import com.ntq.training.momentbox.domain.model.Story
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface FeedRepository {
    val postsMapFlow: StateFlow<Map<Long, Post>>
    fun observePost(postId: Long): Flow<Post?>
    suspend fun getStories(): Result<List<Story>>
    suspend fun getFeed(page: Int, pageSize: Int = 10): Result<List<FeedItem>>
    suspend fun getPostDetail(postId: Long): Result<Post>
    suspend fun toggleLike(postId: Long): Result<Post>
    suspend fun toggleBookmark(postId: Long): Result<Post>
    suspend fun deletePost(postId: Long): Result<Unit>
    suspend fun toggleFollowUser(userId: Long, currentFlowing: Boolean): Result<Boolean>
    suspend fun getComments(
        postId: Long,
        page: Int = 1,
        pageSize: Int = 20,
        sort: String = "top",
        parentId: Long? = null
    ): Result<List<Comment>>

    suspend fun postComment(
        postId: Long,
        content: String,
        parentId: Long? = null
    ): Result<Comment>

    suspend fun toggleLikeComment(
        commentId: Long,
        currentLiked: Boolean
    ): Result<Boolean>

    fun clearCache()
}