package com.ntq.training.momentbox.feature.tabs

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.base.BaseFragment
import com.ntq.training.momentbox.databinding.FragmentJournalBinding
import com.ntq.training.momentbox.databinding.ItemJournalOverviewBinding
import com.ntq.training.momentbox.feature.journal.JournalEntriesAdapter
import com.ntq.training.momentbox.feature.journal.JournalEvent
import com.ntq.training.momentbox.feature.journal.JournalListItem
import com.ntq.training.momentbox.feature.journal.JournalStickyHeader
import com.ntq.training.momentbox.feature.journal.JournalSwipeCallback
import com.ntq.training.momentbox.feature.journal.JournalViewModel
import com.ntq.training.momentbox.feature.journal.editor.JournalEditorActivity
import com.ntq.training.momentbox.feature.journal.filter.JournalFilterBottomSheetDialogFragment
import com.ntq.training.momentbox.feature.journal.stats.JournalStatsActivity
import com.ntq.training.momentbox.feature.journal.stats.JournalStatsCalculator
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class JournalFragment : BaseFragment<FragmentJournalBinding>(FragmentJournalBinding::inflate) {

    private val viewModel: JournalViewModel by viewModels {
        val app = requireActivity().application as MomentBoxApp
        JournalViewModel.provideFactory(app.journalRepository, app.journalClock)
    }

    private lateinit var overview: ItemJournalOverviewBinding
    private lateinit var swipeCallback: JournalSwipeCallback
    private lateinit var adapter: JournalEntriesAdapter
    private var searchBackCallback: OnBackPressedCallback? = null

    private val monthFormatter = DateTimeFormatter.ofPattern(
        if (Locale.getDefault().language == "vi") "LLLL, yyyy" else "LLLL yyyy",
        Locale.getDefault()
    )

    override fun initViews() {
        setupRecyclerView()
        setupCalendarView()
        setupToolbarAndSearch()
        setupFab()
    }

    override fun onResume() {
        super.onResume()
        updateSearchBackCallbackState()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden && ::swipeCallback.isInitialized) {
            swipeCallback.closeRevealed()
        }
        updateSearchBackCallbackState()
    }

    override fun onDestroyView() {
        searchBackCallback = null
        super.onDestroyView()
    }

    private fun setupRecyclerView() {
        adapter = JournalEntriesAdapter(
            onEntryClick = { entryId ->
                val intent = Intent(requireContext(), JournalEditorActivity::class.java).apply {
                    putExtra(JournalEditorActivity.EXTRA_ENTRY_ID, entryId)
                }
                startActivity(intent)
            },
            onPinClick = { entryId ->
                swipeCallback.closeRevealed()
                viewModel.togglePin(entryId)
            },
            onDeleteClick = { entryId ->
                swipeCallback.closeRevealed()
                viewModel.softDeleteEntry(entryId)
            }
        )

        binding.rvJournalEntries.layoutManager = LinearLayoutManager(requireContext())
        overview = ItemJournalOverviewBinding.inflate(layoutInflater, binding.rvJournalEntries, false)
        val overviewAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 1

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(overview.root) {}

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        binding.rvJournalEntries.adapter = ConcatAdapter(overviewAdapter, adapter)
        binding.rvJournalEntries.addItemDecoration(JournalStickyHeader(adapter, 1))

        // Gắn thao tác vuốt sang trái lộ Ghim/Xóa
        swipeCallback = JournalSwipeCallback(requireContext()) { revealed ->
            // FAB che đúng tâm nút Ghim/Xóa ở cuối viewport: ẩn tạm khi đang reveal, không đổi kích thước list.
            val fab = bindingOrNull?.fabWriteJournal ?: return@JournalSwipeCallback
            val targetVisibility = if (revealed) View.INVISIBLE else View.VISIBLE
            if (fab.visibility != targetVisibility) {
                fab.visibility = targetVisibility
            }
        }
        binding.rvJournalEntries.addOnItemTouchListener(swipeCallback)
        binding.rvJournalEntries.addOnChildAttachStateChangeListener(swipeCallback)
    }

    private fun setupCalendarView() {
        val calendarView = overview.moodCalendarView
        calendarView.restoreCalendarState(
            viewModel.calendarYearMonth.value,
            viewModel.selectedCalendarDate.value
        )
        updateCalendarHeader(calendarView.getYearMonth())

        calendarView.onMonthChangedListener = { yearMonth ->
            updateCalendarHeader(yearMonth)
            viewModel.onMonthChanged(yearMonth)
        }

        calendarView.onDayClickListener = { date, hasEntry ->
            viewModel.onCalendarDateSelected(date)
            if (hasEntry) {
                // Cuộn danh sách đến vị trí bài viết của ngày này
                scrollToDate(date)
            } else {
                // Ngày trống -> Mở S08 với ngày đã chọn
                val zone = ZoneId.systemDefault()
                val epochMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val intent = Intent(requireContext(), JournalEditorActivity::class.java).apply {
                    putExtra(JournalEditorActivity.EXTRA_DATE_EPOCH_MS, epochMs)
                }
                startActivity(intent)
            }
        }

        overview.btnPrevMonth.setOnClickListener {
            calendarView.goToPreviousMonth()
        }

        overview.btnNextMonth.setOnClickListener {
            calendarView.goToNextMonth()
        }
    }

    private fun updateCalendarHeader(yearMonth: YearMonth) {
        val date = yearMonth.atDay(1)
        overview.tvCalendarMonth.text =
            date.format(monthFormatter).replaceFirstChar { it.titlecase(Locale.getDefault()) }

        val canNext = overview.moodCalendarView.canGoToNextMonth()
        overview.btnNextMonth.isEnabled = canNext
        overview.btnNextMonth.alpha = if (canNext) 1.0f else 0.35f
    }

    private fun scrollToDate(date: LocalDate) {
        viewModel.scrollToDate(date)
    }

    private fun applyScrollTarget() {
        val id = viewModel.scrollTarget.value ?: return
        val position =
            adapter.currentList.indexOfFirst { it is JournalListItem.Entry && it.data.entry.id == id }
        if (position >= 0) {
            (binding.rvJournalEntries.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(
                position + 1,
                48
            )
            viewModel.consumeScrollTarget()
        }
    }

    private fun setupToolbarAndSearch() {
        val callback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeSearch()
            }
        }
        searchBackCallback = callback
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)

        binding.etSearchQuery.setText(viewModel.searchQuery.value)
        if (viewModel.searchQuery.value.isNotEmpty()) {
            binding.layoutNormalToolbar.visibility = View.GONE
            binding.layoutSearchInput.visibility = View.VISIBLE
        }
        updateSearchBackCallbackState()

        // Nút mở tìm kiếm
        binding.btnSearch.setOnClickListener { openSearch() }

        // Nút đóng tìm kiếm
        binding.btnCloseSearch.setOnClickListener { closeSearch() }

        binding.etSearchQuery.doAfterTextChanged { text ->
            swipeCallback.closeRevealed()
            viewModel.onSearchQueryChanged(text?.toString().orEmpty())
        }

        // Nút mở Thống kê S09
        binding.btnInsights.setOnClickListener {
            val intent = Intent(requireContext(), JournalStatsActivity::class.java)
            startActivity(intent)
        }

        // Nút mở Bộ lọc S07b
        binding.btnFilter.setOnClickListener {
            swipeCallback.closeRevealed()
            if (
                !isAdded ||
                parentFragmentManager.isStateSaved ||
                parentFragmentManager.findFragmentByTag(JournalFilterBottomSheetDialogFragment.TAG) != null
            ) {
                return@setOnClickListener
            }
            val currentQuery = viewModel.searchQuery.value.trim().ifEmpty { null }
            val filterBottomSheet = JournalFilterBottomSheetDialogFragment.newInstance(currentQuery)
            filterBottomSheet.showNow(
                parentFragmentManager,
                JournalFilterBottomSheetDialogFragment.TAG
            )
        }
    }

    private fun openSearch() {
        swipeCallback.closeRevealed()
        binding.layoutNormalToolbar.visibility = View.GONE
        binding.layoutSearchInput.visibility = View.VISIBLE
        binding.etSearchQuery.requestFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etSearchQuery, InputMethodManager.SHOW_IMPLICIT)
        updateSearchBackCallbackState()
    }

    private fun closeSearch() {
        swipeCallback.closeRevealed()
        binding.etSearchQuery.setText("")
        binding.etSearchQuery.clearFocus()
        binding.layoutSearchInput.visibility = View.GONE
        binding.layoutNormalToolbar.visibility = View.VISIBLE
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearchQuery.windowToken, 0)
        viewModel.onSearchQueryChanged("")
        updateSearchBackCallbackState()
    }

    private fun updateSearchBackCallbackState() {
        val searchVisible = bindingOrNull?.layoutSearchInput?.isVisible == true
        searchBackCallback?.isEnabled = !isHidden && searchVisible
    }

    private fun updateEmptyState(isEmpty: Boolean, hasActiveSearchOrFilter: Boolean) {
        overview.layoutEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        if (!isEmpty) return
        val textViews = (0 until overview.layoutEmptyState.childCount)
            .mapNotNull { overview.layoutEmptyState.getChildAt(it) as? TextView }
        val titleView = textViews.getOrNull(0)
        val descView = textViews.getOrNull(1)
        if (hasActiveSearchOrFilter) {
            titleView?.setText(R.string.journal_empty_search_title)
            descView?.setText(R.string.journal_empty_search_desc)
        } else {
            titleView?.setText(R.string.journal_empty_title)
            descView?.setText(R.string.journal_empty_desc)
        }
    }

    private fun setupFab() {
        binding.fabWriteJournal.setOnClickListener {
            val intent = Intent(requireContext(), JournalEditorActivity::class.java)
            startActivity(intent)
        }

        binding.rvJournalEntries.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) swipeCallback.closeRevealed()
                if (dy > 12) {
                    binding.fabWriteJournal.shrink()
                } else if (dy < -12) {
                    binding.fabWriteJournal.extend()
                }
                val manager = recyclerView.layoutManager as LinearLayoutManager
                if (dy > 0 && manager.findLastVisibleItemPosition() >= manager.itemCount - 5) {
                    viewModel.loadNextPage()
                }
            }
        })
    }

    override fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.entriesFlow.collect { items ->
                        swipeCallback.closeRevealed()
                        adapter.submitList(items) {
                            swipeCallback.closeRevealed()
                            applyScrollTarget()
                        }
                    }
                }

                launch {
                    combine(
                        viewModel.entriesFlow,
                        viewModel.searchQuery,
                        viewModel.filterStateFlow
                    ) { items, query, filter ->
                        Triple(items.isEmpty(), query.isNotBlank(), filter.isNonDefault)
                    }.collect { (isEmpty, hasQuery, hasFilter) ->
                        updateEmptyState(isEmpty, hasQuery || hasFilter)
                    }
                }

                launch {
                    combine(
                        viewModel.calendarYearMonth,
                        viewModel.selectedCalendarDate
                    ) { month, selected -> month to selected }.collect { (month, selected) ->
                        overview.moodCalendarView.restoreCalendarState(month, selected)
                        updateCalendarHeader(month)
                    }
                }

                launch { viewModel.scrollTarget.collect { applyScrollTarget() } }

                launch {
                    viewModel.calendarMoodsFlow.collect { moods ->
                        overview.moodCalendarView.setDayMoods(moods)
                    }
                }

                launch {
                    val app = requireActivity().application as MomentBoxApp
                    app.journalRepository.observeWrittenDays().collect { writtenDays ->
                        val dates = writtenDays.mapNotNull {
                            runCatching { LocalDate.parse(it) }.getOrNull()
                        }
                        val streakDays = JournalStatsCalculator.calculateCurrentStreak(
                            writtenDates = dates,
                            today = LocalDate.now(app.journalClock)
                        )
                        overview.tvCalendarStreak.text = resources.getQuantityString(
                            R.plurals.journal_streak_days,
                            streakDays,
                            streakDays
                        )
                        overview.tvCalendarStreak.visibility =
                            if (streakDays > 0) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.filterStateFlow.collect { filter ->
                        swipeCallback.closeRevealed()
                        binding.viewFilterActiveDot.visibility =
                            if (filter.isNonDefault) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            JournalEvent.CalendarEntriesFilteredOut -> Snackbar.make(
                                binding.root,
                                R.string.journal_calendar_entries_filtered_out,
                                Snackbar.LENGTH_SHORT
                            ).show()

                            is JournalEvent.ShowUndoSnackbar -> {
                                Snackbar.make(
                                    binding.root,
                                    R.string.journal_delete_success,
                                    5000
                                ).setAction(R.string.journal_undo) {
                                    viewModel.undoDelete(event.entryId)
                                }.show()
                            }
                        }
                    }
                }
            }
        }
    }
}
