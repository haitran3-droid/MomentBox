package com.ntq.training.momentbox.feature.journal.stats

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.base.BaseActivity
import com.ntq.training.momentbox.databinding.ActivityJournalStatsBinding
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

class JournalStatsActivity : BaseActivity<ActivityJournalStatsBinding>(
    ActivityJournalStatsBinding::inflate
) {

    private val viewModel: JournalStatsViewModel by viewModels {
        val app = application as MomentBoxApp
        JournalStatsViewModel.provideFactory(
            app.journalRepository,
            app.journalClock.zone,
            LocalDate.now(app.journalClock),
            app.journalClock
        )
    }

    private val numberFormatter = NumberFormat.getInstance(Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            insets
        }
    }

    override fun initViews() {
        setupToolbar()
        setupPeriodToggle()
        binding.btnPreviousPeriod.setOnClickListener { viewModel.navigatePreviousPeriod() }
        binding.btnNextPeriod.setOnClickListener { viewModel.navigateNextPeriod() }
        val gestures = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    vx: Float,
                    vy: Float
                ): Boolean {
                    val dx = e2.x - (e1?.x ?: e2.x)
                    if (kotlin.math.abs(dx) > 80 * resources.displayMetrics.density && kotlin.math.abs(
                            vx
                        ) > kotlin.math.abs(vy)
                    ) {
                        if (dx < 0) viewModel.navigateNextPeriod() else viewModel.navigatePreviousPeriod()
                        return true
                    }
                    return false
                }
            })
        binding.tvPeriodRange.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }
        binding.lineChartView.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }
        binding.tvTrendDateRange.setOnClickListener {
            binding.layoutPeriodNavigation.visibility =
                if (binding.layoutPeriodNavigation.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    override fun observeData() {
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnShare.setOnClickListener {
            shareStatsSummary()
        }
    }

    private fun setupPeriodToggle() {
        val currentMonth = YearMonth.now().monthValue
        binding.btnPeriodMonth.text = getString(R.string.stats_period_month, currentMonth)

        binding.tgPeriod.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnPeriodWeek -> viewModel.selectPeriod(StatsPeriod.WEEK)
                    R.id.btnPeriodMonth -> viewModel.selectPeriod(StatsPeriod.MONTH)
                    R.id.btnPeriodYear -> viewModel.selectPeriod(StatsPeriod.YEAR)
                }
            }
        }
        binding.tgPeriod.check(R.id.btnPeriodMonth)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.btnPeriodMonth.text =
                        getString(R.string.stats_period_month, state.anchorDate.monthValue)
                    binding.btnNextPeriod.isEnabled = state.canNavigateNext
                    binding.tgPeriod.check(
                        when (state.period) {
                            StatsPeriod.WEEK -> R.id.btnPeriodWeek; StatsPeriod.MONTH -> R.id.btnPeriodMonth; StatsPeriod.YEAR -> R.id.btnPeriodYear
                        }
                    )
                    if (state.error) {
                        listOf(
                            binding.tvEntriesCount,
                            binding.tvStreakCount,
                            binding.tvWordsCount,
                            binding.tvAudioMinutes
                        ).forEach { it.text = "—" }
                        binding.donutChartView.setData(
                            emptyList(),
                            0,
                            getString(R.string.stats_card_entries)
                        )
                        binding.lineChartView.setData(emptyList(), 1)
                        binding.tvInsightText.setText(R.string.journal_storage_error)
                        Snackbar.make(
                            binding.root,
                            R.string.journal_storage_error,
                            Snackbar.LENGTH_INDEFINITE
                        )
                            .setAction(R.string.journal_retry) { viewModel.retry() }.show()
                        return@collect
                    }
                    // Trong lúc chờ kỳ mới, giữ nguyên snapshot đang vẽ; không bind lại card/tag cũ thêm một lần.
                    if (state.isLoading) return@collect
                    val result = state.statsResult ?: return@collect
                    binding.tvPeriodRange.text =
                        result.dateRangeText + " · " + state.anchorDate.year
                    bindStatsResult(result)
                }
            }
        }
    }

    private fun bindStatsResult(stats: JournalStatsResult) {
        // 1. Thẻ Bài viết
        binding.tvEntriesCount.text = stats.totalEntries.toString()
        val delta = stats.deltaEntriesVsPreviousPeriod
        if (delta >= 0) {
            binding.tvEntriesDelta.text = getString(R.string.stats_delta_up, delta)
            binding.tvEntriesDelta.setTextColor(getColor(R.color.success))
        } else {
            binding.tvEntriesDelta.text = getString(R.string.stats_delta_down, -delta)
            binding.tvEntriesDelta.setTextColor(getColor(R.color.error))
        }

        // 2. Thẻ Chuỗi hiện tại
        binding.tvStreakCount.text = stats.currentStreakDays.toString()

        // 3. Thẻ Số từ
        binding.tvWordsCount.text = numberFormatter.format(stats.totalWords)

        // 4. Thẻ Ghi âm
        binding.tvAudioMinutes.text = stats.totalAudioMinutes.toString()

        // 5. Biểu đồ tròn Phân bố cảm xúc (Donut Chart)
        binding.donutChartView.setData(
            distribution = stats.moodDistribution,
            totalCount = stats.totalEntries,
            subText = getString(R.string.stats_card_entries)
        )

        // Bảng % 5 mức cảm xúc
        val moodMap = stats.moodDistribution.associateBy { it.mood }
        binding.tvMood1Pct.text =
            getString(R.string.stats_percentage_format, moodMap[1]?.percentage ?: 0)
        binding.tvMood2Pct.text =
            getString(R.string.stats_percentage_format, moodMap[2]?.percentage ?: 0)
        binding.tvMood3Pct.text =
            getString(R.string.stats_percentage_format, moodMap[3]?.percentage ?: 0)
        binding.tvMood4Pct.text =
            getString(R.string.stats_percentage_format, moodMap[4]?.percentage ?: 0)
        binding.tvMood5Pct.text =
            getString(R.string.stats_percentage_format, moodMap[5]?.percentage ?: 0)

        // 6. Biểu đồ đường Xu hướng cảm xúc (Line Chart)
        val daysInPeriod =
            ChronoUnit.DAYS.between(stats.periodStart, stats.periodEnd)
                .toInt() + 1
        binding.tvTrendDateRange.text = stats.dateRangeText
        binding.lineChartView.setData(stats.trendPoints, daysInPeriod, stats.periodStart)

        // 7. Top Nhãn
        binding.cgStatsTags.removeAllViews()
        for ((index, tagItem) in stats.topTags.withIndex()) {
            binding.cgStatsTags.addView(
                makeStatsTag(
                    getString(
                        R.string.stats_tag_item_format,
                        tagItem.tag,
                        tagItem.count
                    ), selected = index == 0
                )
            )
        }
        if (stats.remainingTagsCount > 0) {
            binding.cgStatsTags.addView(
                makeStatsTag(
                    getString(
                        R.string.stats_tag_more,
                        stats.remainingTagsCount
                    ), true
                )
            )
        }

        // 8. Đúc kết thông minh (Smart Insight)
        binding.tvInsightText.text = styledInsightText(stats.insight)
    }

    private fun makeStatsTag(label: String, more: Boolean = false, selected: Boolean = false) =
        TextView(this).apply {
            val density = resources.displayMetrics.density
            text = label
            textSize = 12f
            typeface = ResourcesCompat.getFont(
                context,
                if (selected) R.font.be_vietnam_pro_semibold else R.font.be_vietnam_pro_medium
            )
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding((9 * density).toInt(), 0, (9 * density).toInt(), 0)
            setTextColor(
                getColor(
                    when {
                        selected -> R.color.on_primary_container; more -> R.color.text_secondary; else -> R.color.text_primary
                    }
                )
            )
            background = GradientDrawable().apply {
                cornerRadius = 13 * density
                setColor(getColor(if (selected) R.color.primary_container else R.color.surface))
                if (!selected) setStroke(
                    density.toInt().coerceAtLeast(1),
                    getColor(R.color.outline)
                )
            }
            layoutParams = ChipGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (26 * density).toInt()
            )
        }

    private fun insightText(insight: JournalInsight): String {
        if (insight.empty) return getString(R.string.journal_insight_empty)
        val day = DayOfWeek.of(insight.frequentDay)
            .getDisplayName(TextStyle.FULL, resources.configuration.locales[0])
        return if (insight.improvementPct != null) getString(
            R.string.journal_insight_weekend,
            insight.improvementPct,
            day
        )
        else getString(R.string.journal_insight_habit, day, insight.count)
    }

    private fun styledInsightText(insight: JournalInsight): CharSequence {
        if (insight.empty) return getText(R.string.journal_insight_empty)
        val day = DayOfWeek.of(insight.frequentDay)
            .getDisplayName(TextStyle.FULL, resources.configuration.locales[0])
        return if (insight.improvementPct != null) TextUtils.expandTemplate(
            getText(R.string.journal_insight_weekend_styled), insight.improvementPct.toString(), day
        )
        else TextUtils.expandTemplate(
            getText(R.string.journal_insight_habit_styled),
            day,
            insight.count.toString()
        )
    }

    private fun shareStatsSummary() {
        val stats = viewModel.uiState.value.statsResult ?: return
        val summary = getString(
            R.string.stats_share_summary_format,
            stats.dateRangeText,
            stats.totalEntries,
            stats.currentStreakDays,
            numberFormatter.format(stats.totalWords),
            stats.totalAudioMinutes,
            insightText(stats.insight)
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.stats_share_subject))
            putExtra(Intent.EXTRA_TEXT, summary)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.action_share)))
    }
}