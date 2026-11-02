package com.example.momentbox_app.domain.mapper

import com.example.momentbox_app.core.base.Mapper
import com.example.momentbox_app.data.remote.dto.StoryDto
import com.example.momentbox_app.domain.model.Author
import com.example.momentbox_app.domain.model.Story
import com.example.momentbox_app.domain.model.StoryMediaItem

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