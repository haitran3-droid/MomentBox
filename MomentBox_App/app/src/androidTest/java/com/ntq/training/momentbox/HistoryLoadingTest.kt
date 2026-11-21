package com.ntq.training.momentbox

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ntq.training.momentbox.core.network.ApiResponse
import com.ntq.training.momentbox.core.network.PageMeta
import com.ntq.training.momentbox.core.network.SessionRequest
import com.ntq.training.momentbox.data.local.db.AppDatabase
import com.ntq.training.momentbox.data.local.db.JournalEntryEntity
import com.ntq.training.momentbox.data.remote.api.ChatApi
import com.ntq.training.momentbox.data.remote.dto.ChatMessageDto
import com.ntq.training.momentbox.data.remote.dto.CreateConversationBody
import com.ntq.training.momentbox.data.repository.ChatRepository
import com.ntq.training.momentbox.data.repository.ChatSession
import com.ntq.training.momentbox.feature.journal.stats.JournalStatsCalculator
import com.ntq.training.momentbox.feature.journal.stats.StatsPeriod
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryLoadingTest {
    private val db = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java,
    ).build()

    @After
    fun closeDatabase() = db.close()

    @Test
    fun statsLoadOnlyTheSelectedPeriodAndKeepTheGlobalStreak() = runBlocking {
        val dao = db.journalDao()
        val zone = ZoneId.of("America/New_York")
        val today = LocalDate.of(2026, 11, 4)
        val lastWritten = today.minusDays(1)
        suspend fun write(date: LocalDate, deleted: Boolean = false) {
            val millis = date.atStartOfDay(zone).toInstant().toEpochMilli()
            dao.insertEntry(JournalEntryEntity(title = "", content = "two words", mood = 2,
                wordCount = 2, createdAt = millis, deletedAt = if (deleted) millis else null))
        }
        db.withTransaction {
            repeat(1000) { write(LocalDate.of(2018, 1, 1).plusDays(it.toLong())) }
            repeat(40) { write(lastWritten.minusDays(it.toLong())) }
            write(lastWritten) // Two entries on one local day.
            write(lastWritten.minusDays(40), deleted = true)
            write(today.plusDays(1))
        }
        val snapshot = dao.readStatsSnapshot(
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 31), today, zone,
        )
        assertEquals(31, snapshot.entries.size)
        val result = JournalStatsCalculator(zone).calculateStats(snapshot, StatsPeriod.MONTH,
            LocalDate.of(2026, 10, 15), today)
        assertEquals(31, result.totalEntries)
        assertEquals(25, result.deltaEntriesVsPreviousPeriod)
        assertEquals(62, result.totalWords)
        assertEquals(40, result.currentStreakDays)
    }

    @Test
    fun coldRoomsFetchOnePageEachAndReconnectFillsTheGap() = runBlocking {
        val requests = mutableListOf<Pair<Long, Long?>>()
        var newest = 300
        val api = object : ChatApi {
            override suspend fun conversations(session: SessionRequest): Nothing = error("unused")
            override suspend fun following(session: SessionRequest): Nothing = error("unused")
            override suspend fun create(body: CreateConversationBody, session: SessionRequest): Nothing = error("unused")
            override suspend fun messages(id: Long, before: Long?, limit: Int, session: SessionRequest):
                ApiResponse<List<ChatMessageDto>> {
                requests += id to before
                val offset = id * 1000
                val end = before?.minus(1) ?: (offset + newest)
                val start = maxOf(offset + 1, end - limit + 1)
                val messages = (end downTo start).map { n ->
                    ChatMessageDto(n, null, id, 9, "text", "message $n", null, null,
                        "SENT", Instant.ofEpochSecond(n).toString())
                }
                return ApiResponse(true, messages, PageMeta(hasMore = start > offset + 1, nextBefore = start))
            }
        }
        val session = ChatSession("1", "test", 1)
        val repo = ChatRepository(db, api) { session }
        for (room in 1L..3L) repo.catchUp(session, room, null)
        assertEquals(listOf(1L to null, 2L to null, 3L to null), requests)
        val dao = db.chatDao()
        assertEquals(30, dao.messages("1", "test", 1L, 1000).first().size)

        val (cursor, _) = repo.page(session, 1L, null)
        repo.page(session, 1L, cursor)
        assertEquals(60, dao.messages("1", "test", 1L, 1000).first().size)
        requests.clear()
        newest += 75
        repo.catchUp(session, 1L, 1300L)
        assertEquals(listOf(1L to null, 1L to 1346L, 1L to 1316L), requests)
        assertEquals((1241L..1375L).toList(), dao.messages("1", "test", 1L, 1000)
            .first().mapNotNull { it.serverId }.sorted())
    }
}
