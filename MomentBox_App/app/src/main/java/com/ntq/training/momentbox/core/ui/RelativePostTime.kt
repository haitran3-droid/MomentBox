package com.ntq.training.momentbox.core.ui

import android.content.Context
import com.ntq.training.momentbox.R
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

object RelativePostTime {
    private val ISO_REGEX = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,9}))?(Z|[+-]\\d{2}:?\\d{2})$")
    private val ISO_PARSER = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply { isLenient = false }
    }
    private val parsedTimes = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 512
    }
    internal fun ageMinutes(timestamp: String, nowMillis: Long): Long? {
        val millis = synchronized(parsedTimes) { parsedTimes[timestamp] } ?: parseMillis(timestamp)?.also {
            synchronized(parsedTimes) { parsedTimes[timestamp] = it }
        } ?: return null
        return ((nowMillis - millis).coerceAtLeast(0L)) / 60_000L
    }

    private fun parseMillis(timestamp: String): Long? {
        val match = ISO_REGEX.matchEntire(timestamp) ?: return null
        // Máy chủ có thể gửi nhiều hơn ba chữ số phần giây; chỉ giữ độ chính xác mili giây.
        val fraction = match.groupValues[2].padEnd(3, '0').take(3)
        val zone = match.groupValues[3].let {
            if (it.length == 5 && it != "Z") it.take(3) + ":" + it.takeLast(2) else it
        }
        val normalized = "${match.groupValues[1]}.$fraction$zone"
        val parser = ISO_PARSER.get() ?: SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply { isLenient = false }
        val position = ParsePosition(0)
        val date = parser.parse(normalized, position) ?: return null
        if (position.index != normalized.length) return null
        return date.time
    }

    fun format(
        context: Context,
        isoTimestamp: String,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val minutes = ageMinutes(isoTimestamp, nowMillis) ?: return isoTimestamp
        if (minutes == 0L) return context.getString(R.string.post_just_now)
        val (resource, quantity) = when {
            minutes < 60 -> R.plurals.post_minutes_ago to minutes
            minutes < 1440 -> R.plurals.post_hours_ago to minutes / 60
            else -> R.plurals.post_days_ago to minutes / 1440
        }
        val count = quantity.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return context.resources.getQuantityString(resource, count, count)
    }
}