package com.ntq.training.momentbox.data.local.db

import androidx.room.Embedded
import androidx.room.Relation

data class JournalEntryWithDetails(
    @Embedded
    val entry: JournalEntryEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "entry_id"
    )
    val attachments: List<JournalAttachmentEntity> = emptyList(),
    @Relation(
        parentColumn = "id",
        entityColumn = "entry_id"
    )
    val tags: List<JournalTagEntity> = emptyList()
)

data class DayMood(
    val day: String,
    val mood: Int,
    val createdAt: Long
)

data class TagCount(
    val tag: String,
    val cnt: Int
)
