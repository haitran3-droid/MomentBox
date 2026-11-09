package com.ntq.training.momentbox.feature.journal.filter

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.gson.Gson
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.data.local.prefs.JournalFilterState
import com.ntq.training.momentbox.data.repository.JournalRepository
import com.ntq.training.momentbox.databinding.DialogJournalFilterBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.google.android.material.R as MaterialR

class JournalFilterBottomSheetDialogFragment : BottomSheetDialogFragment() {

    private var _binding: DialogJournalFilterBinding? = null
    private val binding get() = _binding!!

    private val journalRepository: JournalRepository
        get() = (requireActivity().application as MomentBoxApp).journalRepository

    private var pendingFilter = JournalFilterState()
    private var countJob: Job? = null
    private var initJob: Job? = null
    private var inputReady = false
    private var resizeListener: ViewTreeObserver.OnPreDrawListener? = null
    private var resizeObservedView: View? = null
    private val searchQuery: String?
        get() = arguments?.getString(ARG_SEARCH_QUERY)
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
        // DataStore đọc bất đồng bộ: khóa các control tới khi bind/listener sẵn sàng,
        // tránh thao tác sớm bị giá trị lưu cũ ghi đè (tái hiện trên Realme).
        setInputReady(false)

        if (savedInstanceState != null) {
            val json = savedInstanceState.getString(KEY_SAVED_FILTER)
            if (json != null) {
                try {
                    pendingFilter = Gson().fromJson(json, JournalFilterState::class.java)
                } catch (_: Exception) {
                }
                // MaterialButton từ chối setChecked khi disabled. Mở control trước khi bind,
                // hoàn tất bind/listener trong cùng lượt Main để người dùng chưa thể chạm xen vào.
                setInputReady(true)
                bindFilterStateToViews()
                updateRealtimeCount()
                setupListeners()
                return
            }
        }

        initJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                pendingFilter = journalRepository.getFilter()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_binding != null) com.google.android.material.snackbar.Snackbar.make(
                    binding.root,
                    R.string.journal_storage_error,
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).show()
                return@launch
            }
            if (_binding != null) {
                setInputReady(true)
                bindFilterStateToViews()
                updateRealtimeCount()
                setupListeners()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setDimAmount(0.48f)
        val sheet =
            (dialog as? BottomSheetDialog)?.findViewById<FrameLayout>(MaterialR.id.design_bottom_sheet)
                ?: return
        // Chọn expanded trước lượt layout đầu. post/state animation trước đây giữ targetTop
        // từ height cũ, nên sau resize CTA portrait bị đẩy xuống navigation bar20px.
        BottomSheetBehavior.from(sheet).apply {
            isFitToContents = true
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        resizeObservedView?.viewTreeObserver?.takeIf { it.isAlive }?.let { observer ->
            resizeListener?.let(observer::removeOnPreDrawListener)
        }
        resizeObservedView = binding.root
        binding.root.layoutParams =
            binding.root.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
        resizeListener = ViewTreeObserver.OnPreDrawListener {
            val current = _binding
            if (current == null || !sheet.isAttachedToWindow) return@OnPreDrawListener true
            val root = current.root
            val scroll = current.scrollFilterOptions
            // Đọc kích thước SAU layout, không gọi measure(root) ngay trong callback layout.
            // Body wrap_content giữ chiều cao tự nhiên kể cả khi viewport cuộn nhỏ hơn nó.
            var contentHeight = root.paddingTop + root.paddingBottom
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                if (child.visibility == View.GONE) continue
                val margin = child.layoutParams as? ViewGroup.MarginLayoutParams
                contentHeight += (margin?.topMargin ?: 0) + (margin?.bottomMargin ?: 0)
                contentHeight += if (child === scroll) {
                    scroll.paddingTop + scroll.paddingBottom + (scroll.getChildAt(0)?.measuredHeight
                        ?: 0)
                } else child.measuredHeight
            }
            // WindowVisibleDisplayFrame đã loại system bars/IME; không trừ insets lần nữa.
            val frame = Rect().also(sheet::getWindowVisibleDisplayFrame)
            val limit =
                (frame.height() - (16 * resources.displayMetrics.density).toInt()).coerceAtLeast(1)
            val desired = minOf(contentHeight + sheet.paddingTop + sheet.paddingBottom, limit)
            if (sheet.layoutParams.height != desired) {
                sheet.layoutParams = sheet.layoutParams.apply { height = desired }
                false // Hoãn frame tới sau lượt layout mới để không vẽ CTA/date bị cắt.
            } else true
        }
        binding.root.viewTreeObserver.addOnPreDrawListener(resizeListener)
    }

    private fun setInputReady(ready: Boolean) {
        inputReady = ready
        fun enable(view: View) {
            view.isEnabled = ready
            if (view is ViewGroup) for (index in 0 until view.childCount) enable(
                view.getChildAt(
                    index
                )
            )
        }
        enable(binding.root)
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

        updateMoodTypography()

        // 5. Khoảng thời gian
        updateDateRangeDisplay()
    }

    // Chip selected trong CSS dùng600, còn unchecked500; gán sau khi checked state đổi để Material không ép về400.
    private fun updateMoodTypography() {
        val medium = androidx.core.content.res.ResourcesCompat.getFont(
            requireContext(),
            R.font.be_vietnam_pro_medium
        )
        val semibold = androidx.core.content.res.ResourcesCompat.getFont(
            requireContext(),
            R.font.be_vietnam_pro_semibold
        )
        listOf(
            binding.chipMood1,
            binding.chipMood2,
            binding.chipMood3,
            binding.chipMood4,
            binding.chipMood5
        ).forEach {
            it.typeface = if (it.isChecked) semibold else medium
        }
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
                val selectedMoods =
                    moodChips.filter { it.first.isChecked }.map { it.second }.toSet()
                pendingFilter = pendingFilter.copyWith(moods = selectedMoods)
                updateMoodTypography()
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
            viewLifecycleOwner.lifecycleScope.launch {
                binding.btnApplyFilter.isEnabled = false
                try {
                    journalRepository.saveFilter(pendingFilter)
                    setFragmentResult(REQUEST_KEY, bundleOf(RESULT_FILTER_APPLIED to true))
                    dismiss()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (_binding != null) {
                        binding.btnApplyFilter.isEnabled = true
                        com.google.android.material.snackbar.Snackbar.make(
                            binding.root,
                            R.string.journal_storage_error,
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                        ).show()
                    }
                }
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

                val startDate =
                    Instant.ofEpochMilli(startUtc).atZone(ZoneId.of("UTC")).toLocalDate()
                val endDate = Instant.ofEpochMilli(endUtc).atZone(ZoneId.of("UTC")).toLocalDate()

                val startEpochMs = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
                // [Intern]: Sử dụng 999_999_999 nanoseconds để bao trọn 999ms cuối cùng của ngày (F14)
                val endEpochMs =
                    endDate.atTime(23, 59, 59, 999_999_999).atZone(zone).toInstant().toEpochMilli()

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
        countJob = viewLifecycleOwner.lifecycleScope.launch {
            // [Intern]: Truyền đồng bộ searchQuery từ màn hình S07 để đếm chính xác theo search hiện hành (F15)
            journalRepository.countEntries(searchQuery, pendingFilter).catch { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_binding != null) com.google.android.material.snackbar.Snackbar.make(
                    binding.root,
                    R.string.journal_storage_error,
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).show()
            }.collect { count ->
                if (_binding != null) {
                    binding.btnApplyFilter.text =
                        getString(R.string.filter_apply_count_format, count)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Nếu chưa tải xong, recreate phải đọc lại repository thay vì lưu nhầm filter mặc định.
        if (inputReady) outState.putString(KEY_SAVED_FILTER, Gson().toJson(pendingFilter))
    }

    override fun onDestroyView() {
        // Listener gắn với View của dialog; tháo khi hủy View để không giữ binding/Activity cũ.
        resizeObservedView?.viewTreeObserver?.takeIf { it.isAlive }?.let { observer ->
            resizeListener?.let(observer::removeOnPreDrawListener)
        }
        resizeListener = null
        resizeObservedView = null
        super.onDestroyView()
        initJob?.cancel()
        countJob?.cancel()
        _binding = null
    }

    companion object {
        const val TAG = "JournalFilterBottomSheet"
        const val REQUEST_KEY = "journal_filter"
        const val RESULT_FILTER_APPLIED = "result_filter_applied"
        private const val KEY_SAVED_FILTER = "key_saved_filter"
        private const val ARG_SEARCH_QUERY = "arg_search_query"

        fun newInstance(searchQuery: String? = null): JournalFilterBottomSheetDialogFragment {
            return JournalFilterBottomSheetDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SEARCH_QUERY, searchQuery)
                }
            }
        }
    }
}
