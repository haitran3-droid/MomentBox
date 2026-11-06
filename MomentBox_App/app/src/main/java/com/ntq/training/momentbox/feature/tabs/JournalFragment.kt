package com.ntq.training.momentbox.feature.tabs

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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.databinding.FragmentJournalBinding
import com.ntq.training.momentbox.feature.journal.JournalEntriesAdapter
import com.ntq.training.momentbox.feature.journal.JournalEvent
import com.ntq.training.momentbox.feature.journal.JournalListItem
import com.ntq.training.momentbox.feature.journal.JournalSwipeCallback
import com.ntq.training.momentbox.feature.journal.JournalViewModel
import com.ntq.training.momentbox.feature.journal.filter.JournalFilterBottomSheetDialogFragment
import com.ntq.training.momentbox.feature.journal.stats.JournalStatsActivity
import kotlinx.coroutines.launch
import java.time.Instant
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
        JournalViewModel.provideFactory(app.journalRepository)
    }

    private lateinit var adapter: JournalEntriesAdapter
    private val monthFormatter = DateTimeFormatter.ofPattern("'Tháng' M, yyyy", Locale.getDefault())

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
                viewModel.togglePin(entryId)
            },
            onDeleteClick = { entryId ->
                viewModel.softDeleteEntry(entryId)
            }
        )

        binding.rvJournalEntries.layoutManager = LinearLayoutManager(requireContext())
        binding.rvJournalEntries.adapter = adapter

        // Gắn thao tác vuốt sang trái lộ Ghim/Xóa
        val swipeCallback = JournalSwipeCallback(requireContext())
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvJournalEntries)
    }

    private fun setupCalendarView() {
        val calendarView = binding.moodCalendarView
        updateCalendarHeader(calendarView.getYearMonth())

        calendarView.onMonthChangedListener = { yearMonth ->
            updateCalendarHeader(yearMonth)
            viewModel.onMonthChanged(yearMonth)
        }

        calendarView.onDayClickListener = { date, hasEntry ->
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

        binding.btnPrevMonth.setOnClickListener {
            calendarView.goToPreviousMonth()
        }

        binding.btnNextMonth.setOnClickListener {
            calendarView.goToNextMonth()
        }
    }

    private fun updateCalendarHeader(yearMonth: YearMonth) {
        val date = yearMonth.atDay(1)
        binding.tvCalendarMonth.text = date.format(monthFormatter)

        val canNext = binding.moodCalendarView.canGoToNextMonth()
        binding.btnNextMonth.isEnabled = canNext
        binding.btnNextMonth.alpha = if (canNext) 1.0f else 0.35f
    }

    private fun scrollToDate(date: LocalDate) {
        val items = adapter.currentList
        val zone = ZoneId.systemDefault()
        for (index in items.indices) {
            val item = items[index]
            if (item is JournalListItem.Entry) {
                val entryDate = Instant.ofEpochMilli(item.data.entry.createdAt).atZone(zone).toLocalDate()
                if (entryDate.isEqual(date)) {
                    binding.rvJournalEntries.smoothScrollToPosition(index)
                    break
                }
            }
        }
    }

    private fun setupToolbarAndSearch() {
        // Nút mở tìm kiếm
        binding.btnSearch.setOnClickListener {
            binding.layoutNormalToolbar.visibility = View.GONE
            binding.layoutSearchInput.visibility = View.VISIBLE
            binding.etSearchQuery.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.etSearchQuery, InputMethodManager.SHOW_IMPLICIT)
        }

        // Nút đóng tìm kiếm
        binding.btnCloseSearch.setOnClickListener {
            binding.etSearchQuery.setText("")
            binding.layoutSearchInput.visibility = View.GONE
            binding.layoutNormalToolbar.visibility = View.VISIBLE
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
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
            val filterBottomSheet = JournalFilterBottomSheetDialogFragment.newInstance()
            filterBottomSheet.show(parentFragmentManager, JournalFilterBottomSheetDialogFragment.TAG)
        }

        // Nút viết bài khi danh sách trống
        binding.btnEmptyWrite.setOnClickListener {
            val intent = Intent(requireContext(), JournalEditorActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupFab() {
        binding.fabWriteJournal.setOnClickListener {
            val intent = Intent(requireContext(), JournalEditorActivity::class.java)
            startActivity(intent)
        }

        // Thu gọn/mở rộng FAB khi cuộn danh sách
        binding.nestedScrollView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY > oldScrollY + 12 && binding.fabWriteJournal.isExtended) {
                binding.fabWriteJournal.shrink()
            } else if (scrollY < oldScrollY - 12 && !binding.fabWriteJournal.isExtended) {
                binding.fabWriteJournal.extend()
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.entriesFlow.collect { items ->
                        adapter.submitList(items)
                        val isEmpty = items.isEmpty()
                        binding.layoutEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
                        binding.rvJournalEntries.visibility = if (isEmpty) View.GONE else View.VISIBLE
                    }
                }

                launch {
                    viewModel.calendarMoodsFlow.collect { moods ->
                        binding.moodCalendarView.setDayMoods(moods)
                    }
                }

                launch {
                    viewModel.streakDaysFlow.collect { streakDays ->
                        binding.tvCalendarStreak.text = getString(R.string.journal_streak_days, streakDays)
                        binding.tvCalendarStreak.visibility = if (streakDays > 0) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.filterStateFlow.collect { filter ->
                        binding.viewFilterActiveDot.visibility = if (filter.isNonDefault) View.VISIBLE else View.GONE
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
