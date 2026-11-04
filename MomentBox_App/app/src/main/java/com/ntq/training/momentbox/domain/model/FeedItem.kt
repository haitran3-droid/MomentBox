package com.ntq.training.momentbox.domain.model

sealed class FeedItem {
    abstract val stableId: String
    data class StoryBar(
        val stories: List<Story>
    ): FeedItem(){
        override val stableId: String = "feed_item_story_bar"
    }
    data class PostItem(val post: Post): FeedItem(){
        override val stableId: String = "feed_post_${post.id}"
    }
    data class Suggestions(
        val users: List<User>
    ): FeedItem(){
        override val stableId: String = "feed_item_suggestions"
    }

    data class LoadingFooter(
        val isLoading: Boolean = false,
        val errorMessage: String? = null
    ): FeedItem(){
        override val stableId: String = "feed_item_loading_footer"
    }
    companion object{
        const val VIEW_TYPE_STORY_BAR = 0
        const val VIEW_TYPE_POST_PHOTO = 1
        const val VIEW_TYPE_POST_VIDEO = 2
        const val VIEW_TYPE_POST_VOICE = 3
        const val VIEW_TYPE_POST_TEXT = 4
        const val VIEW_TYPE_SUGGESTIONS = 5
        const val VIEW_TYPE_LOADING_FOOTER = 6

        fun getViewType(item: FeedItem): Int {
            return when (item) {
                is StoryBar -> VIEW_TYPE_STORY_BAR
                is Suggestions -> VIEW_TYPE_SUGGESTIONS
                is LoadingFooter -> VIEW_TYPE_LOADING_FOOTER
                is PostItem -> when {
                    item.post.isPhotoPost -> VIEW_TYPE_POST_PHOTO
                    item.post.isVideoPost -> VIEW_TYPE_POST_VIDEO
                    item.post.isVoicePost -> VIEW_TYPE_POST_VOICE
                    else -> VIEW_TYPE_POST_TEXT
                }
            }
        }
    }
}