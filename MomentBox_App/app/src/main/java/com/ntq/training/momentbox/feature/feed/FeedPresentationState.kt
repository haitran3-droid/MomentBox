package com.ntq.training.momentbox.feature.feed

class FeedPresentationState {
    private val expandedPostIds = mutableSetOf<Long>()
    private val albumPositions = mutableMapOf<Long, Int>()

    fun isCaptionExpanded(postId: Long): Boolean = postId in expandedPostIds
    fun toggleCaption(postId: Long) {
        if (!expandedPostIds.remove(postId)) expandedPostIds.add(postId)
    }
    fun albumPosition(postId: Long): Int = albumPositions[postId] ?: 0
    fun setAlbumPosition(postId: Long, position: Int) {
        albumPositions[postId] = position.coerceAtLeast(0)
    }
}
