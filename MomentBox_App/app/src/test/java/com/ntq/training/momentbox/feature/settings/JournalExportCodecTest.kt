package com.ntq.training.momentbox.feature.settings

import com.ntq.training.momentbox.data.local.db.JournalEntryEntity
import com.ntq.training.momentbox.data.local.db.JournalEntryWithDetails
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalExportCodecTest {
    @Test
    fun roundTripsUtf8AndIsoInstantsAndOmitsDeletedEntries() {
        val now = Instant.parse("2026-11-12T08:32:34.125Z")
        val entry = JournalEntryEntity(
            id = 42,
            title = "Nhật ký・日記",
            content = "Một ngày vui",
            mood = 1,
            createdAt = now.minusSeconds(120).toEpochMilli(),
            updatedAt = now.toEpochMilli(),
            wordCount = 3
        )
        val rows = listOf(
            JournalEntryWithDetails(entry, emptyList(), emptyList()),
            JournalEntryWithDetails(entry.copy(id = 43, deletedAt = now.toEpochMilli()), emptyList(), emptyList())
        )

        val document = JournalExportCodec.create(rows, now)
        val json = JournalExportCodec.encode(document)
        val decoded = JournalExportCodec.decode(json)

        assertEquals(document, decoded)
        assertEquals(42L, decoded.entries.single().id)
        assertEquals(entry.title, decoded.entries.single().title)
        assertEquals(now, decoded.entries.single().updatedAt)
        assertTrue(json.contains(now.toString()))
        assertFalse(decoded.includesMediaFiles)
    }
}
