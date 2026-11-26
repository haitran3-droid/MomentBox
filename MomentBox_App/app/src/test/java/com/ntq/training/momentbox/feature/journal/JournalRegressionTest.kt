package com.ntq.training.momentbox.feature.journal

import com.ntq.training.momentbox.data.local.db.DayMood
import com.ntq.training.momentbox.data.local.db.JournalAttachmentEntity
import com.ntq.training.momentbox.data.local.db.JournalEntryEntity
import com.ntq.training.momentbox.data.local.db.JournalEntryWithDetails
import com.ntq.training.momentbox.data.local.db.JournalTagEntity
import com.ntq.training.momentbox.data.local.db.TagCount
import com.ntq.training.momentbox.data.local.prefs.JournalAttachmentDraft
import com.ntq.training.momentbox.data.local.prefs.JournalDraft
import com.ntq.training.momentbox.data.local.prefs.JournalFilterState
import com.ntq.training.momentbox.data.repository.JournalRepository
import com.ntq.training.momentbox.data.repository.JournalStatsSnapshot
import com.ntq.training.momentbox.feature.journal.editor.EditorError
import com.ntq.training.momentbox.feature.journal.editor.JournalEditorUiState
import com.ntq.training.momentbox.feature.journal.editor.JournalEditorViewModel
import com.ntq.training.momentbox.feature.journal.stats.JournalStatsCalculator
import com.ntq.training.momentbox.feature.journal.view.MoodCalendarView
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JournalRegressionTest {

    // === M17: STREAK CALCULATION ===

    @Test
    fun currentStreakResetsToZeroWhenMostRecentEntryIsBeforeYesterday() {
        val today = LocalDate.of(2026, 11, 21)
        val writtenDates = listOf(
            today.minusDays(6),
            today.minusDays(5),
            today.minusDays(4),
            today.minusDays(3),
            today.minusDays(2)
        )

        assertEquals(0, JournalStatsCalculator.calculateCurrentStreak(writtenDates, today))
        assertEquals(5, JournalStatsCalculator.calculateLongestStreak(writtenDates, today))
    }

    @Test
    fun currentStreakCountsConsecutiveDaysEndingToday() {
        val today = LocalDate.of(2026, 11, 21)
        val writtenDates = listOf(
            today,
            today.minusDays(1),
            today.minusDays(2),
            today.minusDays(4)
        )

        assertEquals(3, JournalStatsCalculator.calculateCurrentStreak(writtenDates, today))
        assertEquals(3, JournalStatsCalculator.calculateLongestStreak(writtenDates, today))
    }

    @Test
    fun currentStreakRemainsActiveWhenLastEntryWasYesterday() {
        val today = LocalDate.of(2026, 11, 21)
        val writtenDates = listOf(
            today.minusDays(1),
            today.minusDays(2),
            today.minusDays(3),
            today.minusDays(4)
        )

        assertEquals(4, JournalStatsCalculator.calculateCurrentStreak(writtenDates, today))
        assertEquals(4, JournalStatsCalculator.calculateLongestStreak(writtenDates, today))
    }

    @Test
    fun currentStreakIgnoresFutureDatesAndKeepsLongestHistoricalStreakIntact() {
        val today = LocalDate.of(2026, 11, 21)
        val datesWithBrokenCurrentAndFuture = listOf(
            today.plusDays(1),
            today.minusDays(2),
            today.minusDays(3),
            today.minusDays(4)
        )
        assertEquals(
            0,
            JournalStatsCalculator.calculateCurrentStreak(datesWithBrokenCurrentAndFuture, today)
        )
        assertEquals(
            3,
            JournalStatsCalculator.calculateLongestStreak(datesWithBrokenCurrentAndFuture, today)
        )

        val datesWithShorterActiveStreak = listOf(
            today,
            today.minusDays(1),
            today.minusDays(10),
            today.minusDays(11),
            today.minusDays(12),
            today.minusDays(13),
            today.minusDays(14)
        )
        assertEquals(
            2,
            JournalStatsCalculator.calculateCurrentStreak(datesWithShorterActiveStreak, today)
        )
        assertEquals(
            5,
            JournalStatsCalculator.calculateLongestStreak(datesWithShorterActiveStreak, today)
        )
    }

    @Test
    fun calculateCurrentStreakFromEntriesDeduplicatesSameDayAndRespectsZoneId() {
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        val today = LocalDate.of(2026, 11, 21)
        val calculator = JournalStatsCalculator(zone)

        fun entryAt(date: LocalDate, hour: Int): JournalEntryWithDetails {
            val epochMs = ZonedDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, 0, 0, 0, zone)
                .toInstant()
                .toEpochMilli()
            return JournalEntryWithDetails(
                entry = JournalEntryEntity(
                    id = epochMs,
                    title = "Entry",
                    content = "Body",
                    mood = 2,
                    createdAt = epochMs,
                    updatedAt = epochMs
                )
            )
        }

        val entries = listOf(
            entryAt(today, 9),
            entryAt(today, 21),
            entryAt(today.minusDays(1), 18),
            entryAt(today.plusDays(1), 8)
        )
        assertEquals(2, calculator.calculateCurrentStreak(entries, today))
        assertEquals(2, calculator.calculateLongestStreak(entries, today))
        assertEquals(0, calculator.calculateCurrentStreak(emptyList(), today))
    }

    // === H10 & M17: RESTORE DRAFT SINGLE-EMIT, ATOMIC CLEAR & ORPHAN CLEANUP ===

    @Test
    fun loadingSavedDraftEmitsRestoreDialogPromptExactlyOnce() = runTest {
        val repo = FakeJournalRepository().apply {
            savedDraft = JournalDraft(
                entryId = null,
                title = "Draft title",
                content = "Draft content",
                mood = 2,
                savedAtEpochMs = 1_000L
            )
        }

        withEditorViewModel(repo) { viewModel ->
            val emissions = mutableListOf<JournalEditorUiState>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect { emissions += it }
            }

            runCurrent()
            val promptEmissions = emissions.filter { it.showRestoreDraftDialog }
            assertEquals(1, promptEmissions.size)
            assertFalse(promptEmissions.single().isLoading)
            assertEquals("Draft title", promptEmissions.single().restoredDraft?.title)

            collector.cancel()
        }
    }

    @Test
    fun restoreExistingEntryDraftClearsDialogFlagImmediatelyBeforeDbLookupFinishes() = runTest {
        val lookupDeferred = CompletableDeferred<JournalEntryWithDetails?>()
        val draft = JournalDraft(
            entryId = 42L,
            title = "Recovered title",
            content = "Recovered body words",
            mood = 4,
            tags = listOf("calm"),
            attachments = listOf(
                JournalAttachmentDraft("image", "/files/journal/draft_photo.jpg", 0L)
            ),
            savedAtEpochMs = 2_000L
        )
        val repo = FakeJournalRepository().apply {
            savedDraft = draft
            onGetEntryById = { id ->
                if (id == 42L) lookupDeferred.await() else null
            }
        }

        withEditorViewModel(repo) { viewModel ->
            runCurrent()
            assertTrue(viewModel.uiState.value.showRestoreDraftDialog)

            viewModel.restoreDraft()
            // Even before DB lookup completes, dialog flag & draft payload must already be cleared.
            assertFalse(viewModel.uiState.value.showRestoreDraftDialog)
            assertNull(viewModel.uiState.value.restoredDraft)
            assertTrue(viewModel.uiState.value.isLoading)

            // Duplicate tap while loading must be ignored.
            viewModel.restoreDraft()

            lookupDeferred.complete(
                JournalEntryWithDetails(
                    entry = JournalEntryEntity(
                        id = 42L,
                        title = "Old title",
                        content = "Old body",
                        mood = 2,
                        isPinned = true
                    )
                )
            )
            runCurrent()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertFalse(state.showRestoreDraftDialog)
            assertNull(state.restoredDraft)
            assertEquals(42L, state.entryId)
            assertEquals("Recovered title", state.title)
            assertEquals("Recovered body words", state.content)
            assertEquals(4, state.mood)
            assertEquals(listOf("calm"), state.tags)
            assertEquals(listOf("/files/journal/draft_photo.jpg"), state.attachments.map { it.localPath })
            assertTrue(state.isDirty)
        }
    }

    @Test
    fun dismissRestoreDraftDialogClearsFlagImmediatelyAndDeletesOnlyOrphanFiles() = runTest {
        val clearGate = CompletableDeferred<Unit>()
        val existingEntry = JournalEntryWithDetails(
            entry = JournalEntryEntity(
                id = 10L,
                title = "Persisted",
                content = "Persisted body",
                mood = 1
            ),
            attachments = listOf(
                JournalAttachmentEntity(
                    id = 1L,
                    entryId = 10L,
                    type = "image",
                    localPath = "/files/journal/persisted.jpg"
                )
            ),
            tags = listOf(JournalTagEntity(10L, "daily"))
        )
        val draft = JournalDraft(
            entryId = 10L,
            title = "Edited draft",
            content = "Draft body",
            attachments = listOf(
                JournalAttachmentDraft("image", "/files/journal/persisted.jpg", 0L),
                JournalAttachmentDraft("image", "/files/journal/draft_only.jpg", 0L),
                JournalAttachmentDraft("audio", "/files/recordings/draft_voice.m4a", 5_000L)
            ),
            cleanupPaths = listOf(
                "/files/journal/persisted.jpg",
                "/files/journal/removed_draft_photo.jpg"
            )
        )
        val repo = FakeJournalRepository().apply {
            entries[10L] = existingEntry
            savedDraft = draft
            onClearDraft = { clearGate.await() }
        }

        withEditorViewModel(repo, entryId = 10L) { viewModel ->
            runCurrent()
            assertTrue(viewModel.uiState.value.showRestoreDraftDialog)

            viewModel.dismissRestoreDraftDialog()
            // Dialog flag clears immediately and atomically before clearDraft finishes.
            assertFalse(viewModel.uiState.value.showRestoreDraftDialog)
            assertNull(viewModel.uiState.value.restoredDraft)

            // Duplicate tap must not trigger a second clear or cleanup.
            viewModel.dismissRestoreDraftDialog()
            runCurrent()
            assertTrue(repo.deletedPathsCalls.isEmpty())

            clearGate.complete(Unit)
            runCurrent()

            assertEquals(listOf(10L), repo.clearedDraftIds)
            assertEquals(1, repo.deletedPathsCalls.size)
            assertEquals(
                setOf(
                    "/files/journal/draft_only.jpg",
                    "/files/recordings/draft_voice.m4a",
                    "/files/journal/removed_draft_photo.jpg"
                ),
                repo.deletedPathsCalls.single().toSet()
            )
            assertFalse("/files/journal/persisted.jpg" in repo.deletedPathsCalls.single())
        }
    }

    @Test
    fun dismissRestoreDraftDialogDoesNotReemitPromptWhenClearDraftFails() = runTest {
        val repo = FakeJournalRepository().apply {
            savedDraft = JournalDraft(entryId = null, title = "Temp", content = "Temp body")
            onClearDraft = { throw IOException("Disk full") }
        }

        withEditorViewModel(repo) { viewModel ->
            runCurrent()
            assertTrue(viewModel.uiState.value.showRestoreDraftDialog)

            viewModel.dismissRestoreDraftDialog()
            runCurrent()

            assertFalse(viewModel.uiState.value.showRestoreDraftDialog)
            assertNull(viewModel.uiState.value.restoredDraft)
            assertEquals(EditorError.DRAFT, viewModel.uiState.value.error)
            assertTrue(repo.deletedPathsCalls.isEmpty())
        }
    }

    @Test
    fun dismissRestoreDraftDialogOpenedAsNewEntryProtectsTargetEntryAttachments() = runTest {
        val targetEntry = JournalEntryWithDetails(
            entry = JournalEntryEntity(
                id = 77L,
                title = "Existing",
                content = "Existing body",
                mood = 3
            ),
            attachments = listOf(
                JournalAttachmentEntity(
                    id = 2L,
                    entryId = 77L,
                    type = "image",
                    localPath = "/files/journal/existing_77.jpg"
                )
            )
        )
        val repo = FakeJournalRepository().apply {
            entries[77L] = targetEntry
            savedDraft = JournalDraft(
                entryId = 77L,
                title = "Draft for 77",
                content = "Edited",
                attachments = listOf(
                    JournalAttachmentDraft("image", "/files/journal/existing_77.jpg", 0L),
                    JournalAttachmentDraft("image", "/files/journal/new_draft_77.jpg", 0L)
                )
            )
        }

        withEditorViewModel(repo, entryId = -1L) { viewModel ->
            runCurrent()
            assertTrue(viewModel.uiState.value.showRestoreDraftDialog)

            viewModel.dismissRestoreDraftDialog()
            runCurrent()

            assertEquals(listOf(77L), repo.clearedDraftIds)
            assertEquals(
                listOf(listOf("/files/journal/new_draft_77.jpg")),
                repo.deletedPathsCalls
            )
        }
    }

    // === M17: STICKY HEADER PUSH-OUT OFFSET ===

    @Test
    fun stickyHeaderOffsetRemainsZeroWhenNoNextHeaderOrBelowHeaderHeight() {
        assertEquals(0f, JournalStickyHeader.computeStickyHeaderOffset(80, null), 0.001f)
        assertEquals(0f, JournalStickyHeader.computeStickyHeaderOffset(80, 120), 0.001f)
        assertEquals(0f, JournalStickyHeader.computeStickyHeaderOffset(80, 80), 0.001f)
        assertEquals(0f, JournalStickyHeader.computeStickyHeaderOffset(0, 20), 0.001f)
    }

    @Test
    fun stickyHeaderOffsetSmoothlyPushesOutWithoutOverlap() {
        assertEquals(-20f, JournalStickyHeader.computeStickyHeaderOffset(80, 60), 0.001f)
        assertEquals(-55f, JournalStickyHeader.computeStickyHeaderOffset(80, 25), 0.001f)
        assertEquals(-80f, JournalStickyHeader.computeStickyHeaderOffset(80, 0), 0.001f)
        // Clamped so it never snaps back to 0f if a child briefly goes negative during layout/animation.
        assertEquals(-80f, JournalStickyHeader.computeStickyHeaderOffset(80, -12), 0.001f)
        // Respects RecyclerView paddingTop.
        assertEquals(-30f, JournalStickyHeader.computeStickyHeaderOffset(80, 66, parentPaddingTop = 16), 0.001f)
    }

    // === H21: MOOD CALENDAR MONTH FILTERING ON RESTORATION ===

    @Test
    fun moodCalendarFilteringRetainsMoodsForRestoredMonthWithoutDiscarding() {
        val september = YearMonth.of(2026, 9)
        val november = YearMonth.of(2026, 11)
        val moodsForSeptember = listOf(
            DayMood(day = "2026-09-05", mood = 4, createdAt = 100L),
            DayMood(day = "2026-09-05", mood = 1, createdAt = 200L),
            DayMood(day = "2026-09-18", mood = 2, createdAt = 300L),
            DayMood(day = "invalid-date", mood = 3, createdAt = 400L)
        )

        // Before state restoration, view defaults to current month (Nov): no moods match Nov.
        assertTrue(MoodCalendarView.filterMoodsForMonth(moodsForSeptember, november).isEmpty())

        // After state restoration to Sep, re-filtering the cached mood list restores all Sep moods.
        val restoredMap = MoodCalendarView.filterMoodsForMonth(moodsForSeptember, september)
        assertEquals(2, restoredMap.size)
        assertEquals(1, restoredMap[5]?.mood)
        assertEquals(2, restoredMap[18]?.mood)
    }

    // === K18: S20 "VIET NGAY" OPENS S08 WITH UNSELECTED MOOD ===

    @Test
    fun editorStartedWithUnselectedMoodRequiresMoodSelectionBeforeSaving() = runTest {
        val repo = FakeJournalRepository()
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = JournalEditorViewModel(
                journalRepository = repo,
                initialEntryId = -1L,
                initialDateEpochMs = 1_700_000_000_000L,
                initialMood = JournalEditorViewModel.MOOD_UNSELECTED,
                ioDispatcher = dispatcher
            )
            runCurrent()

            assertEquals(JournalEditorViewModel.MOOD_UNSELECTED, viewModel.uiState.value.mood)
            viewModel.onTitleChanged("Ghi chú từ nhắc nhở")
            assertFalse(viewModel.uiState.value.canSave)

            viewModel.onMoodChanged(3)
            assertEquals(3, viewModel.uiState.value.mood)
            assertTrue(viewModel.uiState.value.canSave)
            advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun editorNormalizesInitialMoodPreservingUnselectedAndValidValues() {
        assertEquals(
            JournalEditorViewModel.MOOD_UNSELECTED,
            JournalEditorViewModel.normalizeInitialMood(JournalEditorViewModel.MOOD_UNSELECTED)
        )
        assertEquals(4, JournalEditorViewModel.normalizeInitialMood(4))
        assertEquals(1, JournalEditorViewModel.normalizeInitialMood(-1))
        assertEquals(1, JournalEditorViewModel.normalizeInitialMood(9))
    }

    // === TEST HARNESS ===

    private suspend fun TestScope.withEditorViewModel(
        repo: FakeJournalRepository,
        entryId: Long = -1L,
        block: suspend TestScope.(JournalEditorViewModel) -> Unit
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = JournalEditorViewModel(
                journalRepository = repo,
                initialEntryId = entryId,
                initialDateEpochMs = 1_700_000_000_000L,
                initialMood = 1,
                ioDispatcher = dispatcher
            )
            block(viewModel)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeJournalRepository : JournalRepository {
        val entries = mutableMapOf<Long, JournalEntryWithDetails>()
        var savedDraft: JournalDraft? = null
        val clearedDraftIds = mutableListOf<Long?>()
        val deletedPathsCalls = mutableListOf<List<String>>()
        var onGetEntryById: (suspend (Long) -> JournalEntryWithDetails?)? = null
        var onClearDraft: (suspend (Long?) -> Unit)? = null
        private val filterFlow = MutableStateFlow(JournalFilterState.DEFAULT)

        override fun observeEntries(
            query: String?,
            filter: JournalFilterState,
            limit: Int,
            offset: Int
        ): Flow<List<JournalEntryWithDetails>> = flowOf(emptyList())

        override fun countEntries(query: String?, filter: JournalFilterState): Flow<Int> = flowOf(0)

        override fun observeMoodByDay(from: Long, to: Long): Flow<List<DayMood>> = flowOf(emptyList())

        override fun observeTopTags(limit: Int): Flow<List<TagCount>> = flowOf(emptyList())

        override fun observeDistinctTags(): Flow<List<String>> = flowOf(emptyList())

        override suspend fun getEntryById(id: Long): JournalEntryWithDetails? {
            return onGetEntryById?.invoke(id) ?: entries[id]
        }

        override suspend fun saveEntry(
            entry: JournalEntryEntity,
            attachments: List<JournalAttachmentEntity>,
            tags: List<String>,
            stagedFilesToKeep: List<String>,
            oldFilesToDeleteAfterCommit: List<String>
        ): Result<Long> = Result.success(entry.id)

        override suspend fun togglePin(id: Long): Result<Unit> = Result.success(Unit)

        override suspend fun softDelete(id: Long): Result<Unit> = Result.success(Unit)

        override suspend fun undoDelete(id: Long): Result<Unit> = Result.success(Unit)

        override suspend fun hardDelete(id: Long): Result<Unit> = Result.success(Unit)

        override suspend fun purgeExpiredDeleted(thresholdEpochMs: Long): Result<Int> = Result.success(0)

        override suspend fun getEntriesForStats(
            from: Long?,
            to: Long?
        ): List<JournalEntryWithDetails> = entries.values.toList()

        override suspend fun getStatsSnapshot(
            previousStart: LocalDate,
            currentStart: LocalDate,
            currentEnd: LocalDate,
            today: LocalDate,
            zone: ZoneId
        ): JournalStatsSnapshot = JournalStatsSnapshot(emptyList(), 0, 0)

        override suspend fun getDraft(entryId: Long?): JournalDraft? = savedDraft

        override suspend fun saveDraft(draft: JournalDraft) {
            savedDraft = draft
        }

        override suspend fun clearDraft(entryId: Long?) {
            onClearDraft?.invoke(entryId)
            clearedDraftIds += entryId
            savedDraft = null
        }

        override fun observeFilter(): Flow<JournalFilterState> = filterFlow

        override suspend fun getFilter(): JournalFilterState = filterFlow.value

        override suspend fun saveFilter(filter: JournalFilterState) {
            filterFlow.value = filter
        }

        override suspend fun resetFilter() {
            filterFlow.value = JournalFilterState.DEFAULT
        }

        override suspend fun deleteUnreferencedFiles(paths: List<String>) {
            deletedPathsCalls += paths
        }

        override suspend fun stageAttachmentFile(
            sourcePathOrUri: String,
            isAudio: Boolean
        ): Result<String> = Result.success(sourcePathOrUri)
    }
}
