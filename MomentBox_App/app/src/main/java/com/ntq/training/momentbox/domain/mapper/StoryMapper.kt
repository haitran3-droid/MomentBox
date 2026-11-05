package com.ntq.training.momentbox.domain.mapper

import com.ntq.training.momentbox.core.base.Mapper
import com.ntq.training.momentbox.data.remote.dto.StoryDto
import com.ntq.training.momentbox.domain.model.Author
import com.ntq.training.momentbox.domain.model.Story
import com.ntq.training.momentbox.domain.model.StoryMediaItem

class StoryMapper (private val postMapper: PostMapper = PostMapper()): Mapper<StoryDto, Story> {
    override fun map(input: StoryDto): Story {
        val user = postMapper.mapAuthor(input.user)
        val items = input.items?.map {
            StoryMediaItem(
                id = it.id,
                url = it.url ?: "",
                type = it.type ?: "image"
            )
        } ?: emptyList()
        return Story(
            user = user,
            hasUnseen = input.hasUnseen ?: false,
            items = items,
            isMyStory = false
        )
    }
    fun createMyStory(myAuthor: Author) : Story{
        return Story(
            user = myAuthor,
            hasUnseen =  false,
            items = emptyList(),
            isMyStory = true
        )
    }
}