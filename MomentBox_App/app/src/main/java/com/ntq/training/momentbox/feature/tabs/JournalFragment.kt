package com.ntq.training.momentbox.feature.tabs

import kotlinx.coroutines.flow.combine
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import com.ntq.training.momentbox.databinding.ItemJournalOverviewBinding
import com.ntq.training.momentbox.feature.journal.JournalStickyHeader
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.databinding.FragmentJournalBinding
import com.ntq.training.momentbox.feature.journal.JournalEntriesAdapter
import com.ntq.training.momentbox.feature.journal.JournalEvent
import com.ntq.training.momentbox.feature.journal.JournalListItem
import com.ntq.training.momentbox.feature.journal.JournalSwipeCallback
import com.ntq.training.momentbox.feature.journal.JournalViewModel
import com.ntq.training.momentbox.feature.journal.editor.JournalEditorActivity
import com.ntq.training.momentbox.feature.journal.filter.JournalFilterBottomSheetDialogFragment
import com.ntq.training.momentbox.feature.journal.stats.JournalStatsActivity
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class JournalFragment : Fragment() {

    private var _binding: FragmentJournalBinding? = null
    private val binding get() = _binding!!

    private val viewModel: JournalViewModel by viewModels {
        val app = requireActivity().application as MomentBoxApp
        JournalViewModel.provideFactory(app.journalRepository, app.journalClock)
    }

    private lateinit var overview: ItemJournalOverviewBinding
    private lateinit var swipeCallback: JournalSwipeCallback
    private lateinit var adapter: JournalEntriesAdapter
    private val monthFormatter = DateTimeFormatter.ofPattern(
        if (Locale.getDefault().language == "vi") "LLLL, yyyy" else "LLLL yyyy",
        Locale.getDefault()
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentJournalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupCalendarView()
        setupToolbarAndSearch()
        setupFab()
        observeViewModel()
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
        overview =
            ItemJournalOverviewBinding.inflate(layoutInflater, binding.rvJournalEntries, false)
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
            binding.fabWriteJournal.visibility = if (revealed) View.INVISIBLE else View.VISIBLE
        }
        binding.rvJournalEntries.addOnItemTouchListener(swipeCallback)
    }

    private fun setupCalendarView() {
        val calendarView = overview.moodCalendarView
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
        binding.etSearchQuery.setText(viewModel.searchQuery.value)
        if (viewModel.searchQuery.value.isNotEmpty()) {
            binding.layoutNormalToolbar.visibility = View.GONE
            binding.layoutSearchInput.visibility = View.VISIBLE
        }
        // Nút mở tìm kiếm
        binding.btnSearch.setOnClickListener {
            binding.layoutNormalToolbar.visibility = View.GONE
            binding.layoutSearchInput.visibility = View.VISIBLE
            binding.etSearchQuery.requestFocus()
            val imm =
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.etSearchQuery, InputMethodManager.SHOW_IMPLICIT)
        }

        // Nút đóng tìm kiếm
        binding.btnCloseSearch.setOnClickListener {
            binding.etSearchQuery.setText("")
            binding.layoutSearchInput.visibility = View.GONE
            binding.layoutNormalToolbar.visibility = View.VISIBLE
            val imm =
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(binding.etSearchQuery.windowToken, 0)
        }

        binding.etSearchQuery.doAfterTextChanged { text ->
            viewModel.onSearchQueryChanged(text?.toString().orEmpty())
        }

        // Nút mở Thống kê S09
        binding.btnInsights.setOnClickListener {
            val intent = Intent(requireContext(), JournalStatsActivity::class.java)
            startActivity(intent)
        }

        // Nút mở Bộ lọc S07b
        binding.btnFilter.setOnClickListener {
            val currentQuery = viewModel.searchQuery.value.trim().ifEmpty { null }
            val filterBottomSheet = JournalFilterBottomSheetDialogFragment.newInstance(currentQuery)
            filterBottomSheet.show(
                parentFragmentManager,
                JournalFilterBottomSheetDialogFragment.TAG
            )
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
                if (dy > 12) binding.fabWriteJournal.shrink() else if (dy < -12) binding.fabWriteJournal.extend()
                val manager = recyclerView.layoutManager as LinearLayoutManager
                if (dy > 0 && manager.findLastVisibleItemPosition() >= manager.itemCount - 5) viewModel.loadNextPage()
            }
        })
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.entriesFlow.collect { items ->
                        adapter.submitList(items) { applyScrollTarget() }
                        val isEmpty = items.isEmpty()
                        overview.layoutEmptyState.visibility =
                            if (isEmpty) View.VISIBLE else View.GONE

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
                    viewModel.streakDaysFlow.collect { streakDays ->
                        overview.tvCalendarStreak.text =
                            getString(R.string.journal_streak_days, streakDays)
                        overview.tvCalendarStreak.visibility =
                            if (streakDays > 0) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.filterStateFlow.collect { filter ->
                        binding.viewFilterActiveDot.visibility =
                            if (filter.isNonDefault) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.events.collect { event ->
                        when (event) {
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
