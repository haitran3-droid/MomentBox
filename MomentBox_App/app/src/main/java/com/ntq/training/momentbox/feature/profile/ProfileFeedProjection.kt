package com.ntq.training.momentbox.feature.profile

import com.ntq.training.momentbox.domain.model.Author
import com.ntq.training.momentbox.domain.model.FeedItem
import com.ntq.training.momentbox.domain.model.User

object ProfileFeedProjection {
    fun apply(rows: List<FeedItem>, owner: User?): List<FeedItem> {
        if (owner == null) return rows
        fun author(old: Author) = if (old.id == owner.id) old.copy(
            username = owner.username,
            displayName = owner.displayName,
            avatarUrl = owner.avatarUrl,
            verified = owner.verified,
            avatarVersion = owner.avatarVersion
        ) else old
        return rows.map { row ->
            when (row) {
                is FeedItem.StoryBar -> row.copy(stories = row.stories.map {
                    it.copy(
                        user = author(
                            it.user
                        )
                    )
                })

                is FeedItem.PostItem -> if (row.post.author.id == owner.id) row.copy(
                    post = row.post.copy(
                        author = author(row.post.author)
                    )
                ) else row

                else -> row
            }
        }
    }
}
