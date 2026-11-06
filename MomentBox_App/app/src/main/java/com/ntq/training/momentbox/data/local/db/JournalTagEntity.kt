package com.ntq.training.momentbox.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "journal_tags",
    primaryKeys = ["entry_id", "tag"],
    foreignKeys = [
        ForeignKey(
            entity = JournalEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["entry_id"], name = "index_journal_tags_entry_id"),
        Index(value = ["tag"], name = "index_journal_tags_tag")
    ]
)
data class JournalTagEntity(
    @ColumnInfo(name = "entry_id")
    val entryId: Long,

    @ColumnInfo(name = "tag")
    val tag: String
)
