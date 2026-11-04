package com.ntq.training.momentbox.data.remote.dto

import com.google.gson.annotations.SerializedName

data class AuthorDto(
    @SerializedName("id") val id: Long,
    @SerializedName("username") val username: String,
    @SerializedName("displayName") val displayName: String? = null,
    @SerializedName("avatarUrl") val avatarUrl: String? = null,
    @SerializedName("avatar") val avatar: String? = null,
    @SerializedName("verified") val verified: Boolean? = false
)

data class MediaDto(
    @SerializedName("type") val type: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("durationMs") val durationMs: Long? = null
)

data class PostDto(
    @SerializedName("id") val id: Long,
    @SerializedName("type") val type: String? = "PHOTO",
    @SerializedName("author") val author: AuthorDto? = null,
    @SerializedName("caption") val caption: String? = null,
    @SerializedName("tags") val tags: List<String>? = null,
    @SerializedName("location") val location: String? = null,
    @SerializedName("privacy") val privacy: String? = "public",
    @SerializedName("allowComments") val allowComments: Boolean? = true,
    @SerializedName("media") val media: List<MediaDto>? = null,
    @SerializedName("likeCount") val likeCount: Int? = 0,
    @SerializedName("commentCount") val commentCount: Int? = 0,
    @SerializedName("isLiked") val isLiked: Boolean? = false,
    @SerializedName("isBookmarked") val isBookmarked: Boolean? = false,
    @SerializedName("createdAt") val createdAt: String? = null
)

data class RawFeedItemDto(
    @SerializedName("kind") val kind: String? = null,
    @SerializedName("post") val post: PostDto? = null,
    @SerializedName("users") val users: List<UserDto>? = null
)

data class StoryDto(
    @SerializedName("user") val user: AuthorDto? = null,
    @SerializedName("hasUnseen") val hasUnseen: Boolean? = false,
    @SerializedName("items") val items: List<StoryMediaItemDto>? = null
)

data class StoryMediaItemDto(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("url") val url: String? = null,
    @SerializedName("type") val type: String? = "image"
)

data class CommentDto(
    @SerializedName("id") val id: Long,
    @SerializedName("postId") val postId: Long,
    @SerializedName("parentId") val parentId: Long? = null,
    @SerializedName("author") val author: AuthorDto? = null,
    @SerializedName("content") val content: String? = null,
    @SerializedName("likeCount") val likeCount: Int? = 0,
    @SerializedName("isLiked") val isLiked: Boolean? = false,
    @SerializedName("replyCount") val replyCount: Int? = 0,
    @SerializedName("createdAt") val createdAt: String? = null
)

data class LikeResponseDto(
    @SerializedName("postId") val postId: Long,
    @SerializedName("isLiked") val isLiked: Boolean,
    @SerializedName("likeCount") val likeCount: Int,
    @SerializedName("isBookmarked") val isBookmarked: Boolean? = null
)

data class BookmarkResponseDto(
    @SerializedName("postId") val postId: Long,
    @SerializedName("isBookmarked") val isBookmarked: Boolean,
    @SerializedName("isLiked") val isLiked: Boolean? = null,
    @SerializedName("likeCount") val likeCount: Int? = null
)

data class CommentLikeResponseDto(
    @SerializedName("commentId") val commentId: Long? = null,
    @SerializedName("isLiked") val isLiked: Boolean = false,
    @SerializedName("likeCount") val likeCount: Int = 0
)

data class PostCommentRequest(
    @SerializedName("content") val content: String,
    @SerializedName("parentId") val parentId: Long? = null
)

// === DummyJSON DTOs ===

data class DummyJsonPost(
    @SerializedName("id") val id: Long,
    @SerializedName("title") val title: String? = null,
    @SerializedName("body") val body: String? = null,
    @SerializedName("tags") val tags: List<String>? = null,
    @SerializedName("reactions") val reactions: DummyJsonReactions? = null,
    @SerializedName("views") val views: Int? = 0,
    @SerializedName("userId") val userId: Long? = null
)

data class DummyJsonReactions(
    @SerializedName("likes") val likes: Int? = 0,
    @SerializedName("dislikes") val dislikes: Int? = 0
)

data class DummyJsonPostsResponse(
    @SerializedName("posts") val posts: List<DummyJsonPost>? = null,
    @SerializedName("total") val total: Int? = 0,
    @SerializedName("skip") val skip: Int? = 0,
    @SerializedName("limit") val limit: Int? = 0
)

data class DummyJsonComment(
    @SerializedName("id") val id: Long,
    @SerializedName("body") val body: String? = null,
    @SerializedName("postId") val postId: Long? = null,
    @SerializedName("likes") val likes: Int? = 0,
    @SerializedName("user") val user: DummyJsonCommentUser? = null
)

data class DummyJsonCommentUser(
    @SerializedName("id") val id: Long,
    @SerializedName("username") val username: String? = null,
    @SerializedName("fullName") val fullName: String? = null
)

data class DummyJsonCommentsResponse(
    @SerializedName("comments") val comments: List<DummyJsonComment>? = null,
    @SerializedName("total") val total: Int? = 0,
    @SerializedName("skip") val skip: Int? = 0,
    @SerializedName("limit") val limit: Int? = 0
)