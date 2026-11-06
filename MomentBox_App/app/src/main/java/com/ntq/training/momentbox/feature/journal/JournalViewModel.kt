package com.ntq.training.momentbox.feature.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ntq.training.momentbox.data.local.db.DayMood
import com.ntq.training.momentbox.data.local.db.JournalEntryWithDetails
import com.ntq.training.momentbox.data.local.prefs.JournalFilterState
import com.ntq.training.momentbox.data.repository.JournalRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private val journalRepository: JournalRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _calendarYearMonth = MutableStateFlow(YearMonth.now())
    val calendarYearMonth: StateFlow<YearMonth> = _calendarYearMonth.asStateFlow()

    private val _events = MutableSharedFlow<JournalEvent>()
    val events: SharedFlow<JournalEvent> = _events.asSharedFlow()

    val filterStateFlow = journalRepository.observeFilter()
        .stateIn(viewModelScope, SharingStarted.Eagerly, JournalFilterState())

    // Dòng dữ liệu danh sách bài viết đã phân nhóm
    val entriesFlow: StateFlow<List<JournalListItem>> = combine(
        _searchQuery,
        filterStateFlow
    ) { query, filter ->
        Pair(query, filter)
    }.flatMapLatest { (query, filter) ->
        journalRepository.observeEntries(
            query = query.trim().ifEmpty { null },
            filter = filter,
            limit = 100,
            offset = 0
        ).combine(filterStateFlow) { entries, currentFilter ->
            groupEntries(entries, currentFilter.groupBy)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Dòng dữ liệu cảm xúc trên lịch theo tháng đang hiển thị
    val calendarMoodsFlow: StateFlow<List<DayMood>> = _calendarYearMonth.flatMapLatest { yearMonth ->
        val zone = ZoneId.systemDefault()
        val startOfMonth = yearMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endOfMonth = yearMonth.atEndOfMonth().atTime(23, 59, 59, 999999999).atZone(zone).toInstant().toEpochMilli()
        journalRepository.observeMoodByDay(startOfMonth, endOfMonth)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Đếm chuỗi ngày viết liên tục (Streak)
    val streakDaysFlow: StateFlow<Int> = calendarMoodsFlow.combine(entriesFlow) { _, _ ->
        calculateStreak()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var pendingUndoJob: Job? = null
    private var pendingDeleteEntryId: Long? = null

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onMonthChanged(yearMonth: YearMonth) {
        _calendarYearMonth.value = yearMonth
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
                pendingDeleteEntryId = id
                _events.emit(JournalEvent.ShowUndoSnackbar(id))

                pendingUndoJob?.cancel()
                pendingUndoJob = launch {
                    delay(5000L) // 5 giây chờ hoàn tác
                    if (pendingDeleteEntryId == id) {
                        pendingDeleteEntryId = null
                    }
                }
            }
        }
    }

    fun undoDelete(id: Long) {
        pendingUndoJob?.cancel()
        pendingDeleteEntryId = null
        viewModelScope.launch {
            journalRepository.undoDelete(id)
        }
    }

    private suspend fun calculateStreak(): Int {
        val entries = journalRepository.getEntriesForStats()
        if (entries.isEmpty()) return 0

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val writtenDates = entries.map {
            Instant.ofEpochMilli(it.entry.createdAt).atZone(zone).toLocalDate()
        }.toSet()

        var anchor = today
        // Nếu hôm nay chưa viết bài, cho phép neo từ ngày hôm qua để không làm mất chuỗi
        if (!writtenDates.contains(anchor)) {
            anchor = today.minusDays(1)
        }

        var streak = 0
        while (writtenDates.contains(anchor)) {
            streak++
            anchor = anchor.minusDays(1)
        }
        return streak
    }

    /**
     * Phân nhóm bài viết theo Ngày, Tuần, hoặc Tháng.
     */
    private fun groupEntries(
        entries: List<JournalEntryWithDetails>,
        groupBy: String
    ): List<JournalListItem> {
        if (entries.isEmpty()) return emptyList()

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val result = mutableListOf<JournalListItem>()

        when (groupBy.lowercase(Locale.ROOT)) {
            "day" -> {
                val grouped = entries.groupBy {
                    Instant.ofEpochMilli(it.entry.createdAt).atZone(zone).toLocalDate()
                }
                for ((date, group) in grouped) {
                    val headerTitle = when (date) {
                        today -> "HÔM NAY"
                        today.minusDays(1) -> "HÔM QUA"
                        else -> date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault())).uppercase(Locale.ROOT)
                    }
                    result.add(JournalListItem.Header("header_day_$date", headerTitle, group.size))
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
                    result.add(JournalListItem.Header("header_month_$yearMonth", headerTitle, group.size))
                    result.addAll(group.map { JournalListItem.Entry(it) })
                }
            }
            else -> {
                // Mặc định: Phân nhóm theo Tuần (Week)
                val weekFields = WeekFields.of(DayOfWeek.MONDAY, 1)
                val currentWeek = today.get(weekFields.weekOfYear())
                val currentYear = today.year

                val grouped = entries.groupBy {
                    val date = Instant.ofEpochMilli(it.entry.createdAt).atZone(zone).toLocalDate()
                    Pair(date.get(weekFields.weekOfYear()), date.year)
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
                    result.add(JournalListItem.Header("header_week_${year}_$weekNumber", headerTitle, group.size))
                    result.addAll(group.map { JournalListItem.Entry(it) })
                }
            }
        }

        return result
    }

    companion object {
        fun provideFactory(
            journalRepository: JournalRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return JournalViewModel(journalRepository) as T
            }
        }
    }
}