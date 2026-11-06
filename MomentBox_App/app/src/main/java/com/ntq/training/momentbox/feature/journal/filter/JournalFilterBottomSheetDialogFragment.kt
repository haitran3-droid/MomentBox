package com.ntq.training.momentbox.feature.journal.filter

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.gson.Gson
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.datepicker.MaterialDatePicker
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.data.local.prefs.JournalFilterState
import com.ntq.training.momentbox.data.repository.JournalRepository
import com.ntq.training.momentbox.databinding.DialogJournalFilterBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class JournalFilterBottomSheetDialogFragment : BottomSheetDialogFragment() {

    private var _binding: DialogJournalFilterBinding? = null
    private val binding get() = _binding!!

    private val journalRepository: JournalRepository
        get() = (requireActivity().application as MomentBoxApp).journalRepository

    private var pendingFilter = JournalFilterState()
    private var countJob: Job? = null
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogJournalFilterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (savedInstanceState != null) {
            val json = savedInstanceState.getString(KEY_SAVED_FILTER)
            if (json != null) {
                try {
                    pendingFilter = Gson().fromJson(json, JournalFilterState::class.java)
                } catch (_: Exception) {}
                bindFilterStateToViews()
                updateRealtimeCount()
                setupListeners()
                return
            }
        }

        lifecycleScope.launch {
            pendingFilter = journalRepository.getFilter()
            bindFilterStateToViews()
            updateRealtimeCount()
            setupListeners()
        }
    }

    private fun bindFilterStateToViews() {
        // 1. Sắp xếp
        when (pendingFilter.sort) {
            JournalFilterState.SORT_OLDEST -> binding.rbSortOldest.isChecked = true
            JournalFilterState.SORT_MOOD -> binding.rbSortMood.isChecked = true
            else -> binding.rbSortNewest.isChecked = true
        }

        // 2. Nhóm theo
        when (pendingFilter.groupBy) {
            JournalFilterState.GROUP_DAY -> binding.tgGroupBy.check(R.id.btnGroupDay)
            JournalFilterState.GROUP_MONTH -> binding.tgGroupBy.check(R.id.btnGroupMonth)
            else -> binding.tgGroupBy.check(R.id.btnGroupWeek)
        }

        // 3. Cảm xúc (Multi-select)
        binding.chipMood1.isChecked = pendingFilter.moods.contains(1)
        binding.chipMood2.isChecked = pendingFilter.moods.contains(2)
        binding.chipMood3.isChecked = pendingFilter.moods.contains(3)
        binding.chipMood4.isChecked = pendingFilter.moods.contains(4)
        binding.chipMood5.isChecked = pendingFilter.moods.contains(5)

        // 4. Đính kèm
        binding.cbHasPhoto.isChecked = pendingFilter.hasPhoto
        binding.cbHasAudio.isChecked = pendingFilter.hasAudio

        // 5. Khoảng thời gian
        updateDateRangeDisplay()
    }

    private fun setupListeners() {
        // Sắp xếp
        binding.rgSortBy.setOnCheckedChangeListener { _, checkedId ->
            val sort = when (checkedId) {
                R.id.rbSortOldest -> JournalFilterState.SORT_OLDEST
                R.id.rbSortMood -> JournalFilterState.SORT_MOOD
                else -> JournalFilterState.SORT_NEWEST
            }
            pendingFilter = pendingFilter.copyWith(sortBy = sort)
            updateRealtimeCount()
        }

        // Nhóm theo
        binding.tgGroupBy.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val group = when (checkedId) {
                    R.id.btnGroupDay -> JournalFilterState.GROUP_DAY
                    R.id.btnGroupMonth -> JournalFilterState.GROUP_MONTH
                    else -> JournalFilterState.GROUP_WEEK
                }
                pendingFilter = pendingFilter.copyWith(groupBy = group)
                updateRealtimeCount()
            }
        }

        // Cảm xúc
        val moodChips = listOf(
            Pair(binding.chipMood1, 1),
            Pair(binding.chipMood2, 2),
            Pair(binding.chipMood3, 3),
            Pair(binding.chipMood4, 4),
            Pair(binding.chipMood5, 5)
        )
        for ((chip, _) in moodChips) {
            chip.setOnCheckedChangeListener { _, _ ->
                val selectedMoods = moodChips.filter { it.first.isChecked }.map { it.second }.toSet()
                pendingFilter = pendingFilter.copyWith(moods = selectedMoods)
                updateRealtimeCount()
            }
        }

        // Đính kèm
        binding.cbHasPhoto.setOnCheckedChangeListener { _, isChecked ->
            pendingFilter = pendingFilter.copy(hasPhoto = isChecked)
            updateRealtimeCount()
        }
        binding.cbHasAudio.setOnCheckedChangeListener { _, isChecked ->
            pendingFilter = pendingFilter.copy(hasAudio = isChecked)
            updateRealtimeCount()
        }

        // Chọn khoảng ngày
        binding.layoutDateRangePicker.setOnClickListener {
            showDateRangePicker()
        }

        // Xóa khoảng ngày
        binding.btnClearDateRange.setOnClickListener {
            pendingFilter = pendingFilter.copy(fromDateEpochMs = null, toDateEpochMs = null)
            updateDateRangeDisplay()
            updateRealtimeCount()
        }

        // Đặt lại về mặc định
        binding.btnResetFilter.setOnClickListener {
            pendingFilter = JournalFilterState()
            bindFilterStateToViews()
            updateRealtimeCount()
        }

        // Áp dụng bộ lọc
        binding.btnApplyFilter.setOnClickListener {
            lifecycleScope.launch {
                journalRepository.saveFilter(pendingFilter)
                setFragmentResult(REQUEST_KEY, bundleOf(RESULT_FILTER_APPLIED to true))
                dismiss()
            }
        }
    }

    private fun showDateRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.filter_date_range_title)
            .build()

        picker.addOnPositiveButtonClickListener { selection ->
            if (selection != null) {
                val zone = ZoneId.systemDefault()
                // Selection trả về UTC millis cho ngày bắt đầu và ngày kết thúc
                val startUtc = selection.first
                val endUtc = selection.second

                val startDate = Instant.ofEpochMilli(startUtc).atZone(ZoneId.of("UTC")).toLocalDate()
                val endDate = Instant.ofEpochMilli(endUtc).atZone(ZoneId.of("UTC")).toLocalDate()

                val startEpochMs = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
                val endEpochMs = endDate.atTime(23, 59, 59, 999).atZone(zone).toInstant().toEpochMilli()

                pendingFilter = pendingFilter.copy(
                    fromDateEpochMs = startEpochMs,
                    toDateEpochMs = endEpochMs
                )
                updateDateRangeDisplay()
                updateRealtimeCount()
            }
        }

        picker.show(childFragmentManager, "DATE_RANGE_PICKER")
    }

    private fun updateDateRangeDisplay() {
        val from = pendingFilter.fromDateEpochMs
        val to = pendingFilter.toDateEpochMs
        if (from != null && to != null) {
            val zone = ZoneId.systemDefault()
            val fromDate = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
            val toDate = Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
            val rangeStr = "${fromDate.format(dateFormatter)} – ${toDate.format(dateFormatter)}"
            binding.tvDateRangeText.text = rangeStr
            binding.btnClearDateRange.visibility = View.VISIBLE
        } else {
            binding.tvDateRangeText.text = getString(R.string.filter_all_time)
            binding.btnClearDateRange.visibility = View.GONE
        }
    }

    private fun updateRealtimeCount() {
        countJob?.cancel()
        countJob = lifecycleScope.launch {
            journalRepository.countEntries(null, pendingFilter).collect { count ->
                binding.btnApplyFilter.text = getString(R.string.filter_apply_count_format, count)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SAVED_FILTER, Gson().toJson(pendingFilter))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        countJob?.cancel()
        _binding = null
    }

    companion object {
        const val TAG = "JournalFilterBottomSheet"
        const val REQUEST_KEY = "journal_filter"
        const val RESULT_FILTER_APPLIED = "result_filter_applied"
        private const val KEY_SAVED_FILTER = "key_saved_filter"

        fun newInstance(): JournalFilterBottomSheetDialogFragment {
            return JournalFilterBottomSheetDialogFragment()
        }
    }
}
