package com.ntq.training.momentbox.domain.model

data class Story(
    val user: Author,
    val hasUnseen: Boolean,
    val items: List<StoryMediaItem> = emptyList(),
    val isMyStory: Boolean = false
)

data class StoryMediaItem(
    val id: Long,
    val url: String,
    val type: String = "image"
)