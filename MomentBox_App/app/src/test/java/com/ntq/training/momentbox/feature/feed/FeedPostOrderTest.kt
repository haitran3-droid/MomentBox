package com.ntq.training.momentbox.feature.feed

import com.ntq.training.momentbox.domain.model.Author
import com.ntq.training.momentbox.domain.model.FeedItem
import com.ntq.training.momentbox.domain.model.Post
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedPostOrderTest {
    @Test
    fun returningFromComposeKeepsStoriesFirstAndShowsTheNewPostOnce() {
        val old = Post(1, "TEXT", Author(1, "qa", "QA", null), "old", createdAt = "")
        val saved = old.copy(id = 2, caption = "published")
        val stories = FeedItem.StoryBar(emptyList())
        val footer = FeedItem.LoadingFooter()
        val loaded = listOf(stories, FeedItem.PostItem(saved), FeedItem.PostItem(old), footer)

        val latest = old.copy(id = 3, caption = "latest")
        assertEquals(listOf(stories, FeedItem.PostItem(latest), FeedItem.PostItem(saved),
            FeedItem.PostItem(old), footer), withCreatedPosts(loaded, listOf(latest, saved)))
        assertEquals(listOf(FeedItem.PostItem(latest)), withCreatedPosts(emptyList(), listOf(latest)))
    }
}
