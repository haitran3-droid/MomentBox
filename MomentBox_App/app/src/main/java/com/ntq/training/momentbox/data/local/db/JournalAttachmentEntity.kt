package com.ntq.training.momentbox.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "journal_attachments",
    foreignKeys = [
        ForeignKey(
            entity = JournalEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["entry_id"], name = "index_journal_attachments_entry_id")
    ]
)
data class JournalAttachmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "entry_id")
    val entryId: Long,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "local_path")
    val localPath: String,

    @ColumnInfo(name = "duration_ms", defaultValue = "0")
    val durationMs: Long = 0,

    @ColumnInfo(name = "waveform", defaultValue = "'[]'")
    val waveform: List<Int> = emptyList(),

    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0
)