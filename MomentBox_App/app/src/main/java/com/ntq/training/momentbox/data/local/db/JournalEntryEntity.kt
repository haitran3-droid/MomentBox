package com.ntq.training.momentbox.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "journal_entries",
    indices =[
        Index(value = ["created_at"], name = "index_journal_entries_created_at"),
        Index(value = ["mood"], name = "index_journal_entries_mood"),
        Index(value = ["deleted_at"], name = "index_journal_entries_deleted_at"),
        Index(value = ["is_pinned"], name = "index_journal_entries_is_pinned")
    ]
)
data class JournalEntryEntity (
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "mood")
    val mood: Int,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "is_pinned", defaultValue = "0")
    val isPinned: Boolean = false,

    @ColumnInfo(name = "word_count", defaultValue = "0")
    val wordCount: Int = 0,

    @ColumnInfo(name = "photo_count", defaultValue = "0")
    val photoCount: Int = 0,

    @ColumnInfo(name = "audio_count", defaultValue = "0")
    val audioCount: Int = 0,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null
)