package com.ntq.training.momentbox.domain.model

data class MediaItem(
    val type: String,
    val url: String,
    val durationMs: Long? = null
){
    val isVideo: Boolean get() = type.equals("video", ignoreCase = true)
    val isAudio: Boolean get() = type.equals("audio", ignoreCase = true)
    val isImage: Boolean get() = type.equals("image", ignoreCase = true) || (!isVideo && !isAudio)
}
