package com.ntq.training.momentbox.domain.mapper

import com.ntq.training.momentbox.core.base.Mapper
import com.ntq.training.momentbox.data.remote.dto.CommentDto
import com.ntq.training.momentbox.data.remote.dto.DummyJsonComment
import com.ntq.training.momentbox.domain.model.Author
import com.ntq.training.momentbox.domain.model.Comment

class CommentMapper(private val postMapper: PostMapper = PostMapper()) :
    Mapper<CommentDto, Comment> {
    override fun map(input: CommentDto): Comment {
        return Comment(
            id = input.id,
            postId = input.postId,
            parentId = input.parentId,
            author = postMapper.mapAuthor(input.author),
            content = input.content ?: "",
            likeCount = input.likeCount ?: 0,
            isLiked = input.isLiked ?: false,
            replyCount = input.replyCount ?: 0,
            createdAt = input.createdAt ?: "Vừa xong",
            isSending = false,
            sendFailed = false
        )
    }

    fun mapFromDummyJson(input: DummyJsonComment): Comment {
        val author = Author(
            id = input.user?.id ?: 0,
            username = input.user?.username ?: "user",
            displayName = input.user?.fullName ?: input.user?.username ?: "Người dùng",
            avatarUrl = "https://picsum.photos/seed/comment_user_${input.user?.id ?: 0}/80/80",
            verified = false
        )

        return Comment(
            id = input.id,
            postId = input.postId ?: 0,
            parentId = null,
            author = author,
            content = input.body ?: "",
            likeCount = input.likes ?: 0,
            isLiked = false,
            replyCount = 0,
            createdAt = "Hôm nay",
            isSending = false,
            sendFailed = false
        )
    }
}