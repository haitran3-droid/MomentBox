package com.ntq.training.momentbox.feature.compose

import com.ntq.training.momentbox.feature.create.SelectedMedia
import com.ntq.training.momentbox.feature.recorder.RecordedAudio

data class ComposeDraft(
    val caption: String = "",
    val media: List<SelectedMedia> = emptyList(),
    val voice: RecordedAudio? = null,
    val privacy: String = "public",
    val location: String = "",
    val allowComments: Boolean = true,
    val saveToJournal: Boolean = false,
) {
    val canPost
        get() =
            caption.length <= 2200 && (caption.isNotBlank() || media.isNotEmpty() || voice != null)

    val hasVideo
        get() = media.any { it.isVideo }

    fun snapshot() = copy(media = media.toList(), saveToJournal = saveToJournal && !hasVideo)

    fun move(from: Int, to: Int): ComposeDraft {
        if (from !in media.indices || to !in media.indices) return this

        val next = media.toMutableList()
        next.add(to, next.removeAt(from))
        return copy(media = next)
    }
}

/** Đếm byte hữu ích từng file, không đếm đôi khi Authenticator ghi lại request body. */
class UploadProgress(private val lengths: List<Long>) {
    private val written = LongArray(lengths.size)
    val total
        get() = lengths.sum()

    val bytes
        get() = written.sum()

    @Synchronized
    fun sent(index: Int, count: Long) {
        written[index] = maxOf(written[index], count.coerceIn(0, lengths[index]))
    }
}
