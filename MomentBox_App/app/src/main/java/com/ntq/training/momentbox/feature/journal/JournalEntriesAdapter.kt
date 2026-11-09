package com.ntq.training.momentbox.feature.journal

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.data.local.db.JournalEntryWithDetails
import com.ntq.training.momentbox.databinding.ItemJournalEntryBinding
import com.ntq.training.momentbox.databinding.ItemJournalHeaderBinding
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface JournalListItem {
    data class Header(val id: String, val title: String, val count: Int) : JournalListItem
    data class Entry(val data: JournalEntryWithDetails) : JournalListItem
}

class JournalEntriesAdapter(
    private val onEntryClick: (Long) -> Unit,
    private val onPinClick: (Long) -> Unit,
    private val onDeleteClick: (Long) -> Unit
) : ListAdapter<JournalListItem, RecyclerView.ViewHolder>(DiffCallback) {

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is JournalListItem.Header -> VIEW_TYPE_HEADER
            is JournalListItem.Entry -> VIEW_TYPE_ENTRY
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            val binding = ItemJournalHeaderBinding.inflate(inflater, parent, false)
            HeaderViewHolder(binding)
        } else {
            val binding = ItemJournalEntryBinding.inflate(inflater, parent, false)
            EntryViewHolder(binding, onEntryClick, onPinClick, onDeleteClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is JournalListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is JournalListItem.Entry -> (holder as EntryViewHolder).bind(item.data)
        }
    }

    class HeaderViewHolder(
        private val binding: ItemJournalHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(header: JournalListItem.Header) {
            binding.tvHeaderTitle.text = localizedHeader(binding.root.context, header)
            binding.tvHeaderCount.text = binding.root.context.getString(R.string.journal_entries_count, header.count)
        }
    }

    class EntryViewHolder(
        val binding: ItemJournalEntryBinding,
        private val onEntryClick: (Long) -> Unit,
        private val onPinClick: (Long) -> Unit,
        private val onDeleteClick: (Long) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private val context: Context get() = binding.root.context
        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

        fun bind(entryDetails: JournalEntryWithDetails) {
            binding.cardForeground.animate().cancel()
            binding.cardForeground.translationX = 0f
            binding.layoutUnderlayActions.visibility = View.INVISIBLE
            val entry = entryDetails.entry
            val zone = ZoneId.systemDefault()
            val dateTime = Instant.ofEpochMilli(entry.createdAt).atZone(zone)
            val date = dateTime.toLocalDate()

            // 1. Badge ngày & thứ
            binding.tvBadgeDay.text = date.dayOfMonth.toString()
            binding.tvBadgeDayOfWeek.text = formatDayOfWeek(date.dayOfWeek.value)

            val badgeBgColor = getMoodLightColor(entry.mood)
            val badgeTextColor = getMoodTextColor(entry.mood)

            val badgeDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * context.resources.displayMetrics.density
                setColor(badgeBgColor)
            }
            binding.layoutDateBadge.background = badgeDrawable
            binding.tvBadgeDay.setTextColor(badgeTextColor)
            binding.tvBadgeDayOfWeek.setTextColor(badgeTextColor)

            // 2. Tiêu đề & Ghim
            binding.tvEntryTitle.text = entry.title.ifBlank {
                context.getString(R.string.editor_title_new)
            }
            binding.ivPinnedIcon.visibility = if (entry.isPinned) View.VISIBLE else View.GONE
            binding.tvUnderlayPin.text = if (entry.isPinned) {
                context.getString(R.string.journal_action_unpin)
            } else {
                context.getString(R.string.journal_action_pin)
            }

            // 3. Trích đoạn nội dung
            binding.tvEntryContent.text = entry.content
            binding.tvEntryContent.visibility = if (entry.content.isBlank()) View.GONE else View.VISIBLE

            // 4. Cảm xúc & thời gian
            binding.tvMoodEmojiAndLabel.text = getMoodEmojiAndLabel(entry.mood)
            binding.tvEntryTime.text = dateTime.format(timeFormatter)

            // 5. Đính kèm ảnh & ghi âm
            val photoCount = entry.photoCount
            if (photoCount > 0) {
                binding.layoutPhotoCount.visibility = View.VISIBLE
                binding.tvPhotoCount.text = photoCount.toString()
            } else {
                binding.layoutPhotoCount.visibility = View.GONE
            }

            val audioCount = entry.audioCount
            if (audioCount > 0) {
                binding.layoutAudioCount.visibility = View.VISIBLE
                binding.tvAudioCount.text = audioCount.toString()
            } else {
                binding.layoutAudioCount.visibility = View.GONE
            }

            // 6. Xử lý sự kiện click
            binding.cardForeground.setOnClickListener {
                onEntryClick(entry.id)
            }
            binding.btnUnderlayPin.setOnClickListener {
                onPinClick(entry.id)
            }
            binding.btnUnderlayDelete.setOnClickListener {
                onDeleteClick(entry.id)
            }
        }

        private fun formatDayOfWeek(dayOfWeekValue: Int): String {
            return context.resources.getStringArray(R.array.journal_weekdays_short).getOrElse(dayOfWeekValue - 1) { "" }
        }

        private fun getMoodEmojiAndLabel(mood: Int): String {
            return when (mood) {
                1 -> "😄 ${context.getString(R.string.mood_1_name)}"
                2 -> "🙂 ${context.getString(R.string.mood_2_name)}"
                3 -> "😐 ${context.getString(R.string.mood_3_name)}"
                4 -> "😢 ${context.getString(R.string.mood_4_name)}"
                5 -> "😡 ${context.getString(R.string.mood_5_name)}"
                else -> "😐 ${context.getString(R.string.mood_3_name)}"
            }
        }

        private fun getMoodLightColor(mood: Int): Int {
            return when (mood) {
                1 -> context.getColor(R.color.mood_1_great_light)
                2 -> context.getColor(R.color.mood_2_good_light)
                3 -> context.getColor(R.color.mood_3_okay_light)
                4 -> context.getColor(R.color.mood_4_sad_light)
                5 -> context.getColor(R.color.mood_5_terrible_light)
                else -> context.getColor(R.color.mood_3_okay_light)
            }
        }

        private fun getMoodTextColor(mood: Int): Int {
            return when (mood) {
                1 -> context.getColor(R.color.mood_1_text)
                2 -> context.getColor(R.color.mood_2_text)
                3 -> context.getColor(R.color.mood_3_text)
                4 -> context.getColor(R.color.mood_4_text)
                5 -> context.getColor(R.color.mood_5_text)
                else -> context.getColor(R.color.mood_3_text)
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<JournalListItem>() {
        override fun areItemsTheSame(oldItem: JournalListItem, newItem: JournalListItem): Boolean {
            return when {
                oldItem is JournalListItem.Header && newItem is JournalListItem.Header -> oldItem.id == newItem.id
                oldItem is JournalListItem.Entry && newItem is JournalListItem.Entry -> oldItem.data.entry.id == newItem.data.entry.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: JournalListItem, newItem: JournalListItem): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        fun localizedHeader(context: Context, header: JournalListItem.Header): String {
            val clock = (context.applicationContext as? com.ntq.training.momentbox.MomentBoxApp)?.journalClock ?: java.time.Clock.systemDefaultZone()
            val today = java.time.LocalDate.now(clock)
            val locale = context.resources.configuration.locales[0]
            if (header.id.startsWith("header_day_")) {
                val day = java.time.LocalDate.parse(header.id.removePrefix("header_day_"))
                return when (day) {
                    today -> context.getString(R.string.journal_header_today)
                    today.minusDays(1) -> context.getString(R.string.journal_header_yesterday)
                    else -> day.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", locale))
                }
            }
            if (header.id.startsWith("header_month_")) return java.time.YearMonth.parse(header.id.removePrefix("header_month_"))
                .atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).uppercase(locale)
            val parts = header.id.removePrefix("header_week_").split("_")
            val year = parts.getOrNull(0)?.toIntOrNull() ?: return header.title
            val week = parts.getOrNull(1)?.toIntOrNull() ?: return header.title
            val fields = java.time.temporal.WeekFields.ISO
            fun same(date: java.time.LocalDate) = date.get(fields.weekBasedYear()) == year && date.get(fields.weekOfWeekBasedYear()) == week
            return when {
                same(today) -> context.getString(R.string.journal_header_this_week)
                same(today.minusWeeks(1)) -> context.getString(R.string.journal_header_previous_week)
                else -> context.getString(R.string.journal_header_week_format, week, year)
            }
        }
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ENTRY = 1
    }
}