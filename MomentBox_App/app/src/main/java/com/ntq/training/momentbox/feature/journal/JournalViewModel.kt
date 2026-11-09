package com.ntq.training.momentbox.feature.journal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ntq.training.momentbox.data.local.db.DayMood
import com.ntq.training.momentbox.data.local.db.JournalEntryWithDetails
import com.ntq.training.momentbox.data.local.prefs.JournalFilterState
import com.ntq.training.momentbox.data.repository.JournalRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

data class JournalUiState(
    val items: List<JournalListItem> = emptyList(),
    val totalEntriesCount: Int = 0,
    val isFilterActive: Boolean = false,
    val calendarYearMonth: YearMonth = YearMonth.now(),
    val calendarMoods: List<DayMood> = emptyList(),
    val currentStreakDays: Int = 0,
    val isLoading: Boolean = false
)

sealed interface JournalEvent {
    data class ShowUndoSnackbar(val entryId: Long) : JournalEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class JournalViewModel(
    private val journalRepository: JournalRepository,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val clock: java.time.Clock = java.time.Clock.systemDefaultZone()
) : ViewModel() {

    private val _searchQuery = MutableStateFlow(savedState.get<String>("search").orEmpty())
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _calendarYearMonth = MutableStateFlow(
        savedState.get<String>("month")?.let(YearMonth::parse) ?: YearMonth.now(clock)
    )
    val calendarYearMonth: StateFlow<YearMonth> = _calendarYearMonth.asStateFlow()

    private val _events = MutableSharedFlow<JournalEvent>()
    val events: SharedFlow<JournalEvent> = _events.asSharedFlow()

    private val _pageCount = MutableStateFlow(1)
    private var canLoadMore = true
    private var previousFilter = JournalFilterState()
    private var targetDateJob: Job? = null
    private val _scrollTarget = MutableStateFlow<Long?>(null)
    val scrollTarget = _scrollTarget.asStateFlow()
    private val _selectedCalendarDate =
        MutableStateFlow(savedState.get<String>("selected_date")?.let(LocalDate::parse))
    val selectedCalendarDate = _selectedCalendarDate.asStateFlow()
    val filterStateFlow = journalRepository.observeFilter().onEach { filter ->
        if (filter != previousFilter) {
            previousFilter = filter; canLoadMore = true; _pageCount.value = 1
            targetDateJob?.cancel(); _scrollTarget.value = null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, JournalFilterState())

    // Mỗi Flow Room chỉ đọc 20 dòng theo OFFSET. Các trang cùng invalidate khi ghim/sửa/lọc đổi.
    val entriesFlow: StateFlow<List<JournalListItem>> =
        combine(_searchQuery, filterStateFlow, _pageCount) { q, f, pages ->
            Triple(q.trim().ifEmpty { null }, f, pages)
        }.flatMapLatest { (query, filter, pages) ->
            val atomic = journalRepository.observeAtomicPages(query, filter, pages)
            if (atomic != null) return@flatMapLatest atomic.map { snapshot ->
                canLoadMore = snapshot.entries.size >= pages * 20
                groupEntries(snapshot.entries, filter.groupBy, snapshot.dayCounts)
            }
            val windows = (0 until pages).map { page ->
                journalRepository.observeEntries(
                    query,
                    filter,
                    20,
                    page * 20
                )
            }
            combine(windows) { pageRows ->
                canLoadMore = pageRows.lastOrNull()?.size == 20
                pageRows.flatMap { it }.distinctBy { it.entry.id }
            }.combine(journalRepository.observeDayCounts(query, filter)) { rows, counts ->
                groupEntries(rows, filter.groupBy, counts)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Dòng dữ liệu cảm xúc trên lịch theo tháng đang hiển thị
    val calendarMoodsFlow: StateFlow<List<DayMood>> =
        _calendarYearMonth.flatMapLatest { yearMonth ->
            val zone = ZoneId.systemDefault()
            val startOfMonth = yearMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val endOfMonth =
                yearMonth.atEndOfMonth().atTime(23, 59, 59, 999999999).atZone(zone).toInstant()
                    .toEpochMilli()
            journalRepository.observeMoodByDay(startOfMonth, endOfMonth)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val streakDaysFlow: StateFlow<Int> = journalRepository.observeWrittenDays().map { days ->
        val dates =
            days.map(LocalDate::parse).filterNot { it.isAfter(LocalDate.now(clock)) }.toSet()
        var anchor = dates.maxOrNull()
        var count = 0
        while (anchor != null && anchor in dates) {
            count++; anchor = anchor.minusDays(1)
        }
        count
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Repository production sở hữu scheduler ở Application; fake/in-memory dùng scope của VM.
    private val deletionScheduler = if (!journalRepository.handlesDeletionDeadlines)
        com.ntq.training.momentbox.data.repository.JournalDeletionScheduler(
            journalRepository,
            viewModelScope
        ) else null

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        savedState["search"] = query
        canLoadMore = true
        _pageCount.value = 1
    }

    fun loadNextPage() {
        if (canLoadMore) _pageCount.update { it + 1 }
    }

    fun onMonthChanged(yearMonth: YearMonth) {
        _calendarYearMonth.value = yearMonth
        savedState["month"] = yearMonth.toString()
    }

    fun onCalendarDateSelected(date: LocalDate) {
        _selectedCalendarDate.value = date; savedState["selected_date"] = date.toString()
    }

    fun togglePin(id: Long) {
        viewModelScope.launch {
            journalRepository.togglePin(id)
        }
    }

    fun softDeleteEntry(id: Long) {
        viewModelScope.launch {
            val result = journalRepository.softDelete(id)
            if (result.isSuccess) {
                _events.emit(JournalEvent.ShowUndoSnackbar(id))


            }
        }
    }

    fun undoDelete(id: Long) {
        viewModelScope.launch {
            journalRepository.undoDelete(id)
        }
    }

    fun scrollToDate(date: LocalDate) {
        targetDateJob?.cancel()
        targetDateJob = viewModelScope.launch {
            while (true) {
                val expected = _pageCount.value * 20
                val items = entriesFlow.first { list ->
                    list.count { it is JournalListItem.Entry } >= expected || !canLoadMore
                }
                val target = items.filterIsInstance<JournalListItem.Entry>().firstOrNull {
                    Instant.ofEpochMilli(it.data.entry.createdAt).atZone(ZoneId.systemDefault())
                        .toLocalDate() == date
                }
                if (target != null) {
                    _scrollTarget.value = target.data.entry.id; break
                }
                if (!canLoadMore) break
                loadNextPage()
            }
        }
    }

    fun consumeScrollTarget() {
        _scrollTarget.value = null
    }

    /**
     * Phân nhóm bài viết theo Ngày, Tuần, hoặc Tháng.
     */
    private fun groupEntries(
        entries: List<JournalEntryWithDetails>,
        groupBy: String,
        dayCounts: List<com.ntq.training.momentbox.data.repository.JournalDayCount> = emptyList()
    ): List<JournalListItem> {
        if (entries.isEmpty()) return emptyList()

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(clock)
        val result = mutableListOf<JournalListItem>()

        fun key(date: LocalDate): String = when (groupBy.lowercase(Locale.ROOT)) {
            "day" -> date.toString()
            "month" -> YearMonth.from(date).toString()
            else -> date.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toString()
        }

        val counts = dayCounts.groupBy { key(LocalDate.parse(it.day)) }
            .mapValues { (_, values) -> values.sumOf { it.count } }

        fun total(group: List<JournalEntryWithDetails>) = counts[key(
            Instant.ofEpochMilli(group.first().entry.createdAt).atZone(zone).toLocalDate()
        )] ?: group.size
        when (groupBy.lowercase(Locale.ROOT)) {
            "day" -> {
                val grouped = entries.groupBy {
                    Instant.ofEpochMilli(it.entry.createdAt).atZone(zone).toLocalDate()
                }
                for ((date, group) in grouped) {
                    val headerTitle = when (date) {
                        today -> "HÔM NAY"
                        today.minusDays(1) -> "HÔM QUA"
                        else -> date.format(
                            DateTimeFormatter.ofPattern(
                                "dd/MM/yyyy",
                                Locale.getDefault()
                            )
                        ).uppercase(Locale.ROOT)
                    }
                    result.add(
                        JournalListItem.Header(
                            "header_day_$date",
                            headerTitle,
                            total(group)
                        )
                    )
                    result.addAll(group.map { JournalListItem.Entry(it) })
                }
            }

            "month" -> {
                val grouped = entries.groupBy {
                    val date = Instant.ofEpochMilli(it.entry.createdAt).atZone(zone).toLocalDate()
                    YearMonth.from(date)
                }
                for ((yearMonth, group) in grouped) {
                    val headerTitle = "THÁNG ${yearMonth.monthValue}, ${yearMonth.year}"
                    result.add(
                        JournalListItem.Header(
                            "header_month_$yearMonth",
                            headerTitle,
                            total(group)
                        )
                    )
                    result.addAll(group.map { JournalListItem.Entry(it) })
                }
            }

            else -> {
                // Mặc định: Phân nhóm theo Tuần (Week)
                val weekFields = WeekFields.ISO
                val currentWeek = today.get(weekFields.weekOfWeekBasedYear())
                val currentYear = today.get(weekFields.weekBasedYear())

                val grouped = entries.groupBy {
                    val date = Instant.ofEpochMilli(it.entry.createdAt).atZone(zone).toLocalDate()
                    Pair(
                        date.get(weekFields.weekOfWeekBasedYear()),
                        date.get(weekFields.weekBasedYear())
                    )
                }

                for ((weekYearPair, group) in grouped) {
                    val (weekNumber, year) = weekYearPair
                    val headerTitle = if (weekNumber == currentWeek && year == currentYear) {
                        "TUẦN NÀY"
                    } else if (weekNumber == currentWeek - 1 && year == currentYear) {
                        "TUẦN TRƯỚC"
                    } else {
                        "TUẦN $weekNumber ($year)"
                    }
                    result.add(
                        JournalListItem.Header(
                            "header_week_${year}_$weekNumber",
                            headerTitle,
                            total(group)
                        )
                    )
                    result.addAll(group.map { JournalListItem.Entry(it) })
                }
            }
        }

        return result
    }

    companion object {
        fun provideFactory(
            journalRepository: JournalRepository,
            clock: java.time.Clock = java.time.Clock.systemDefaultZone()
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                JournalViewModel(journalRepository, extras.createSavedStateHandle(), clock) as T

            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return JournalViewModel(journalRepository, clock = clock) as T
            }
        }
    }
}
