package com.ntq.training.momentbox.feature.feed

import com.ntq.training.momentbox.domain.model.Author
import com.ntq.training.momentbox.domain.model.MediaItem
import com.ntq.training.momentbox.domain.model.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostMediaSelectionTest {
    private val author = Author(1, "minhanh", "Nguyễn Minh Anh", null)

    @Test
    fun primaryAudioSelectsAudioEntryEvenWhenPhotosComeFirst() {
        val photo = MediaItem(type = "image", url = "http://127.0.0.1:3000/uploads/photo.jpg")
        val audio = MediaItem(
            type = "audio",
            url = "http://127.0.0.1:3000/uploads/voice.m4a",
            durationMs = 5689L
        )
        val post = Post(
            id = 1,
            type = "VOICE",
            author = author,
            caption = "Buổi chiều làm việc cuối tuần",
            media = listOf(photo, audio),
            createdAt = "2026-11-27T07:26:18.120Z"
        )

        assertEquals(audio, post.primaryAudio)
        assertNull(post.primaryVideo)
    }

    @Test
    fun primaryVideoSelectsVideoEntryEvenWhenPhotosComeFirst() {
        val photo = MediaItem(type = "image", url = "http://127.0.0.1:3000/uploads/photo.jpg")
        val video = MediaItem(
            type = "video",
            url = "http://127.0.0.1:3000/uploads/clip.mp4",
            durationMs = 15000L
        )
        val post = Post(
            id = 2,
            type = "VIDEO",
            author = author,
            caption = "Mixed video",
            media = listOf(photo, video),
            createdAt = "2026-11-27T07:26:18.120Z"
        )

        assertEquals(video, post.primaryVideo)
        assertNull(post.primaryAudio)
    }
}
