package com.ntq.training.momentbox.feature.reminder

import android.content.Intent
import java.time.Instant
import java.time.ZoneId

object ReminderJournalRoute {
    private const val MOOD = "m5_reminder_write_mood"
    private const val DATE = "m5_reminder_write_date"

    data class Entry(val mood: Int, val date: Long)

    fun put(intent: Intent, mood: Int, date: Long): Intent {
        require(mood in 1..5 && date > 0)
        return intent.putExtra(MOOD, mood).putExtra(DATE, date)
    }

    fun read(intent: Intent): Entry? = runCatching {
        val mood = intent.getIntExtra(MOOD, 0)
        val date = intent.getLongExtra(DATE, 0)
        val now = Instant.now()
        val start =
            now.atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
        if (mood !in 1..5 || date < start || date > now.toEpochMilli() + 60000) null else Entry(
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