package com.ntq.training.momentbox.feature.settings

import java.time.format.DateTimeParseException
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.ntq.training.momentbox.data.local.db.JournalEntryWithDetails
import java.io.File
import java.time.Instant

data class JournalExportDocument(
    val schemaVersion: Int = 1, val exportedAt: Instant,
    val includesMediaFiles: Boolean = false, val entries: List<JournalExportEntry>
)

data class JournalExportEntry(
    val id: Long,
    val title: String,
    val content: String,
    val mood: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val isPinned: Boolean,
    val wordCount: Int,
    val photoCount: Int,
    val audioCount: Int,
    val tags: List<String>,
    val attachments: List<JournalExportAttachment>
)

data class JournalExportAttachment(
    val id: Long, val type: String, val fileName: String,
    val durationMs: Long, val waveform: List<Int>, val sortOrder: Int
)

/** Gson không tự hiểu java.time.Instant trên mọi Android/JDK. Adapter ghi ISO8601 UTC
 * và parse nghiêm ngặt, tránh biến epoch millisecond thành ngày theo timezone máy chạy. */
private class ExportInstantAdapter : TypeAdapter<Instant>() {
    override fun write(out: JsonWriter, value: Instant) {
        out.value(value.toString())
    }

    override fun read(input: JsonReader): Instant = try {
        Instant.parse(input.nextString())
    } catch (failure: DateTimeParseException) {
        throw JsonParseException(
            "Invalid ISO8601 instant",
            failure
        )
    }
}

object JournalExportCodec {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
        .registerTypeAdapter(Instant::class.java, ExportInstantAdapter().nullSafe()).create()

    fun create(rows: List<JournalEntryWithDetails>, exportedAt: Instant): JournalExportDocument =
        JournalExportDocument(
            exportedAt = exportedAt,
            entries = rows.filter { it.entry.deletedAt == null }
                .sortedWith(compareBy({ it.entry.createdAt }, { it.entry.id })).map { row ->
                    val entry = row.entry
                    JournalExportEntry(
                        entry.id,
                        entry.title,
                        entry.content,
                        entry.mood,
                        Instant.ofEpochMilli(entry.createdAt),
                        Instant.ofEpochMilli(entry.updatedAt),
                        entry.isPinned,
                        entry.wordCount,
                        entry.photoCount,
                        entry.audioCount,
                        row.tags.map { it.tag }.sorted(),
                        row.attachments.sortedWith(compareBy({ it.sortOrder }, { it.id }))
                            .map { att ->
                                JournalExportAttachment(
                                    att.id,
                                    att.type,
                                    File(att.localPath).name,
                                    att.durationMs,
                                    att.waveform.toList(),
                                    att.sortOrder
                                )
                            })
                })

    fun encode(document: JournalExportDocument): String = gson.toJson(document)
    fun decode(json: String): JournalExportDocument {
        val value = gson.fromJson(json, JournalExportDocument::class.java)
            ?: throw JsonParseException("Empty journal document")
        require(value.schemaVersion == 1) { "Unsupported journal export version" }
        require(!value.includesMediaFiles) { "This format contains attachment metadata only" }
        requireNotNull(value.exportedAt)
        requireNotNull(value.entries)
        value.entries.forEach { entry ->
            requireNotNull(entry.createdAt)
            requireNotNull(entry.updatedAt)
            requireNotNull(
            entry.attachments
        )
        requireNotNull(entry.tags)
        }
        return value
    }
}