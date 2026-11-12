package com.ntq.training.momentbox.feature.journal.stats

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ntq.training.momentbox.data.repository.JournalRepository
import java.time.Clock
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

data class JournalStatsUiState(
    val period: StatsPeriod = StatsPeriod.MONTH,
    val anchorDate: LocalDate = LocalDate.now(),
    val statsResult: JournalStatsResult? = null,
    val isLoading: Boolean = false,
    val error: Boolean = false,
    val canNavigateNext: Boolean = false
)

class JournalStatsViewModel(
    private val journalRepository: JournalRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val anchorDate: LocalDate = LocalDate.now(zoneId),
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val clock: Clock = Clock.system(zoneId),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val calculationDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val calculator = JournalStatsCalculator(zoneId)

    private var currentAnchorDate: LocalDate =
        savedState.get<String>("anchor")?.let(LocalDate::parse) ?: anchorDate
    private var loadJob: Job? = null
    private var requestRevision: Long = 0L

    private val _uiState =
        MutableStateFlow(JournalStatsUiState(period = StatsPeriod.MONTH, anchorDate = anchorDate))
    val uiState: StateFlow<JournalStatsUiState> = _uiState.asStateFlow()

    init {
        loadStatsForPeriod(
            savedState.get<String>("period")?.let(StatsPeriod::valueOf) ?: StatsPeriod.MONTH,
            currentAnchorDate
        )
    }

    fun selectPeriod(period: StatsPeriod) {
        if (_uiState.value.period == period && _uiState.value.statsResult != null && !_uiState.value.error) return
        loadStatsForPeriod(period, currentAnchorDate)
    }

    fun retry() {
        loadStatsForPeriod(_uiState.value.period, currentAnchorDate)
    }

    fun navigatePreviousPeriod() {
        val newAnchor = when (_uiState.value.period) {
            StatsPeriod.WEEK -> currentAnchorDate.minusWeeks(1)
            StatsPeriod.MONTH -> currentAnchorDate.minusMonths(1)
            StatsPeriod.YEAR -> currentAnchorDate.minusYears(1)
        }
        currentAnchorDate = newAnchor
        loadStatsForPeriod(_uiState.value.period, currentAnchorDate)
    }

    fun navigateNextPeriod() {
        val today = LocalDate.now(clock)
        val newAnchor = when (_uiState.value.period) {
            StatsPeriod.WEEK -> currentAnchorDate.plusWeeks(1)
            StatsPeriod.MONTH -> currentAnchorDate.plusMonths(1)
            StatsPeriod.YEAR -> currentAnchorDate.plusYears(1)
        }
        if (startOfPeriod(
                _uiState.value.period,
                newAnchor
            ).isAfter(startOfPeriod(_uiState.value.period, today))
        ) return
        currentAnchorDate = newAnchor
        loadStatsForPeriod(_uiState.value.period, currentAnchorDate)
    }

    private fun startOfPeriod(period: StatsPeriod, date: LocalDate): LocalDate = when (period) {
        StatsPeriod.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        StatsPeriod.MONTH -> date.withDayOfMonth(1)
        StatsPeriod.YEAR -> date.withDayOfYear(1)
    }

    private fun loadStatsForPeriod(period: StatsPeriod, anchor: LocalDate) {
        loadJob?.cancel()
        val currentRev = ++requestRevision
        currentAnchorDate = anchor
        savedState["anchor"] = anchor.toString()
        savedState["period"] = period.name
        _uiState.update {
            it.copy(
                isLoading = true, period = period, anchorDate = anchor, error = false,
                canNavigateNext = startOfPeriod(period, anchor) < startOfPeriod(
                    period,
                    LocalDate.now(clock)
                )
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val entries = withContext(ioDispatcher) { journalRepository.getEntriesForStats() }
                val result = withContext(calculationDispatcher) {
                    calculator.calculateStats(
                        entries,
                        period,
                        anchor,
                        LocalDate.now(clock)
                    )
                }
                if (currentRev == requestRevision) _uiState.update {
                    it.copy(
                        statsResult = result,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (currentRev == requestRevision) _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = true
                    )
                }
            }
        }
    }

    companion object {
        fun provideFactory(
            journalRepository: JournalRepository,
            zoneId: ZoneId = ZoneId.systemDefault(),
            anchorDate: LocalDate = LocalDate.now(zoneId),
            clock: Clock = Clock.system(zoneId)
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                JournalStatsViewModel(
                    journalRepository,
                    zoneId,
                    anchorDate,
                    extras.createSavedStateHandle(),
                    clock
                ) as T

            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return JournalStatsViewModel(journalRepository, zoneId, anchorDate) as T
            }
        }
    }
}
