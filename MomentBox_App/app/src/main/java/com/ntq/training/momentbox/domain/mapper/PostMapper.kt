package com.ntq.training.momentbox.domain.mapper

import com.ntq.training.momentbox.core.base.Mapper
import com.ntq.training.momentbox.data.remote.dto.AuthorDto
import com.ntq.training.momentbox.data.remote.dto.DummyJsonPost
import com.ntq.training.momentbox.data.remote.dto.MediaDto
import com.ntq.training.momentbox.data.remote.dto.PostDto
import com.ntq.training.momentbox.domain.model.Author
import com.ntq.training.momentbox.domain.model.MediaItem
import com.ntq.training.momentbox.domain.model.Post

class PostMapper : Mapper<PostDto, Post> {
    override fun map(input: PostDto): Post {
        val author = mapAuthor(input.author)
        val mediaList = input.media?.mapNotNull { mapMedia(it) } ?: emptyList()
        return Post(
            id = input.id,
            type = input.type ?: if(mediaList.isNotEmpty()) "PHOTO" else "TEXT",
            author = author,
            caption = input.caption ?: "",
            tags = input.tags ?: emptyList(),
            location = input.location,
            privacy = input.privacy ?: "public",
            allowComments = input.allowComments ?: true,
            media = mediaList,
            likeCount = input.likeCount ?: 0,
            commentCount = input.commentCount ?: 0,
            isLiked = input.isLiked ?: false,
            isBookmarked = input.isBookmarked ?: false,
            createdAt = input.createdAt ?: "Vừa xong"
        )
    }

    fun mapAuthor(dto: AuthorDto?): Author {
        if(dto == null){
            return Author(
                id = 0,
                username = "user",
                displayName = "Người dùng",
                avatarUrl = null,
                verified = false
            )
        }
        return Author(
            id = dto.id,
            username = dto.username,
            displayName = dto.displayName ?: dto.username,
            avatarUrl = dto.avatarUrl ?: dto.avatar,
            verified = dto.verified ?: false
        )
    }

    fun mapMedia(dto: MediaDto): MediaItem?{
        val url = dto.url ?: return null
        return MediaItem(
            type = dto.type ?: "image",
            url = url,
            durationMs = dto.durationMs,
            thumbnailUrl = dto.thumbnailUrl?.takeIf { it.isNotBlank() }
        )
    }

    fun mapFromDummyJson(dto: DummyJsonPost): Post {
        val id = dto.id
        val authorName = "Tác giả #${dto.userId ?: id}"
        val author = Author(
            id = dto.userId ?: id,
            username = "dummy_user_$id",
            displayName = authorName,
            avatarUrl = "https://picsum.photos/seed/user_$id/100/100",
            verified = (id % 3L == 0L)
        )

        val fullText = buildString {
            dto.title?.let { append(it) }
            if (dto.title != null && dto.body != null) append("\n")
            dto.body?.let { append(it) }
        }

        // Tạo 1 hoặc 2 ảnh minh họa từ picsum.photos
        val mediaList = listOf(
            MediaItem(type = "image", url = "https://picsum.photos/seed/post_$id/720/540"),
            MediaItem(type = "image", url = "https://picsum.photos/seed/post_${id}_2/720/540")
        )

        return Post(
            id = id,
            type = "PHOTO",
            author = author,
            caption = fullText,
            tags = dto.tags ?: emptyList(),
            location = "Việt Nam",
            privacy = "public",
            allowComments = true,
            media = mediaList,
            likeCount = dto.reactions?.likes ?: 0,
            commentCount = dto.views ?: 0,
            isLiked = false,
            isBookmarked = false,
            createdAt = "Hôm nay"
        )
    }
}