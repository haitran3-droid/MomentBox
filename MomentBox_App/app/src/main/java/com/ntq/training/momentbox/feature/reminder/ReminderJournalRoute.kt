package com.ntq.training.momentbox.feature.reminder

import android.content.Intent
import java.time.Instant
import java.time.ZoneId

object ReminderJournalRoute {
    const val MOOD_UNSELECTED = 0
    private const val MOOD = "m5_reminder_write_mood"
    private const val DATE = "m5_reminder_write_date"

    data class Entry(val mood: Int, val date: Long)

    fun isValidMood(mood: Int): Boolean = mood == MOOD_UNSELECTED || mood in 1..5

    fun put(intent: Intent, mood: Int, date: Long): Intent {
        require(isValidMood(mood) && date > 0)
        return intent.putExtra(MOOD, mood).putExtra(DATE, date)
    }

    fun read(intent: Intent): Entry? = runCatching {
        if (!intent.hasExtra(MOOD) || !intent.hasExtra(DATE)) return@runCatching null
        val mood = intent.getIntExtra(MOOD, -1)
        val date = intent.getLongExtra(DATE, 0)
        val now = Instant.now()
        val start =
            now.atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
        if (!isValidMood(mood) || date < start || date > now.toEpochMilli() + 60000) null else Entry(
            mood,
            date
        )
    }.getOrNull()

    fun copy(from: Intent, to: Intent) {
        read(from)?.let { put(to, it.mood, it.date) }
    }

    fun take(intent: Intent): Entry? {
        val value = read(intent)
        intent.removeExtra(MOOD)
        intent.removeExtra(DATE)
        return value
    }
}