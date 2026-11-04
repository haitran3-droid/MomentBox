package com.ntq.training.momentbox.domain.model

data class Comment(
    val id: Long,
    val postId: Long,
    val parentId: Long? = null,
    val author: Author,
    val content: String,
    val likeCount: Int = 0,
    val isLiked: Boolean = false,
    val replyCount: Int = 0,
    val createdAt: String,
    val isSending: Boolean = false,
    val sendFailed: Boolean = false,
    val replies: List<Comment> = emptyList(),
    val isRepliesExpanded: Boolean = false
) {
    val isReply: Boolean get() = parentId != null && parentId > 0
}