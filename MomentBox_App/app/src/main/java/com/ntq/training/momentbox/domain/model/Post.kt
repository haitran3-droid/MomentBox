package com.ntq.training.momentbox.domain.model

data class Post(
    val id: Long,
    val type: String,
    val author: Author,
    val caption: String,
    val tags: List<String> = emptyList(),
    val location: String? = null,
    val privacy: String = "public",
    val allowComments: Boolean = true,
    val media: List<MediaItem> = emptyList(),
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val isLiked: Boolean = false,
    val isBookmarked: Boolean = false,
    val createdAt: String
) {
    val isPhotoPost: Boolean get() = type.equals("PHOTO", ignoreCase = true)
    val isVideoPost: Boolean get() = type.equals("VIDEO", ignoreCase = true)
    val isVoicePost: Boolean get() = type.equals("VOICE", ignoreCase = true)
    val isTextPost: Boolean get() = type.equals("TEXT", ignoreCase = true) || (!isPhotoPost && !isVideoPost && !isVoicePost)
}
