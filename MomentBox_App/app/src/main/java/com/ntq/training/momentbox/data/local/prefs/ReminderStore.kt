package com.ntq.training.momentbox.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ntq.training.momentbox.feature.reminder.ReminderEvent
import com.ntq.training.momentbox.feature.reminder.ReminderKind
import com.ntq.training.momentbox.feature.reminder.ReminderPolicy
import com.ntq.training.momentbox.feature.reminder.ReminderPreferences
import com.ntq.training.momentbox.feature.reminder.ReminderSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ReminderStore(private val disk: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    val snapshots = disk.data.map(::decode)
    suspend fun snapshot(): ReminderSnapshot = snapshots.first()

    suspend fun configure(enabled: Boolean, minuteOfDay: Int): ReminderSnapshot {
        require(minuteOfDay in 0 until 1440)
        var result: ReminderSnapshot? = null
        disk.edit { prefs ->
            val current = decode(prefs)
            if (current.settings.enabled != enabled || current.settings.minuteOfDay != minuteOfDay) {
                writeSettings(
                    prefs, current.settings.copy(
                        enabled = enabled, minuteOfDay = minuteOfDay,
                        revision = Math.addExact(current.settings.revision, 1L)
                    )
                )
                clearPending(prefs)
                clearActive(prefs)
            }
            result = decode(prefs)
        }
        return requireNotNull(result)
    }

    /** Scheduler ghi đúng revision nó vừa đọc; không resurrect alarm của cấu hình đã tắt. */
    suspend fun schedule(event: ReminderEvent): Boolean {
        var accepted = false
        disk.edit { prefs ->
            val current = decode(prefs)
            if (current.settings.enabled && current.settings.revision == event.revision) {
                prefs[dueKey(event.kind)] = event.dueEpochMillis
                accepted = true
            }
        }
        return accepted
    }

    /** Dọn đúng due đã quan sát; tác vụ cũ không được xóa lịch snooze mới hơn. */
    suspend fun unschedule(event: ReminderEvent): Boolean {
        var accepted = false
        disk.edit { prefs ->
            if (decode(prefs).settings.revision == event.revision && prefs[dueKey(event.kind)] == event.dueEpochMillis) {
                prefs.remove(dueKey(event.kind))
                accepted = true
            }
        }
        return accepted
    }

    /** BOOT/time/timezone/package reschedule bỏ delivery cũ nhưng giữ lựa chọn enabled/time. */
    suspend fun invalidateSchedule(): ReminderSnapshot {
        var result: ReminderSnapshot? = null
        disk.edit { prefs ->
            val current = decode(prefs)
            writeSettings(
                prefs,
                current.settings.copy(revision = Math.addExact(current.settings.revision, 1L))
            )
            clearPending(prefs)
            clearActive(prefs)
            result = decode(prefs)
        }
        return requireNotNull(result)
    }

    /** Chỉ claim đúng due đã lưu, chưa sớm, còn trong ngày địa phương của alarm.
     * Receiver vẫn phải reschedule kể cả claim bị từ chối hoặc Room đã có nhật ký hôm nay. */
    suspend fun claim(event: ReminderEvent, now: Instant, zone: ZoneId): Boolean {
        var accepted = false
        disk.edit { prefs ->
            val current = decode(prefs)
            val day = now.atZone(zone).toLocalDate()
            val dueDay = Instant.ofEpochMilli(event.dueEpochMillis).atZone(zone).toLocalDate()
            val sameSchedule =
                current.settings.enabled && current.settings.revision == event.revision &&
                        prefs[dueKey(event.kind)] == event.dueEpochMillis
            if (sameSchedule && !now.isBefore(Instant.ofEpochMilli(event.dueEpochMillis)) && day == dueDay &&
                current.settings.skipDate != day && (event.kind != ReminderKind.DAILY || current.lastDailyDate != day)
            ) {
                prefs.remove(dueKey(event.kind))
                if (event.kind == ReminderKind.DAILY) prefs[LAST_DAILY] = day.toString()
                prefs[ACTIVE_KIND] = event.kind.name
                prefs[ACTIVE_REVISION] = event.revision
                prefs[ACTIVE_DUE] = event.dueEpochMillis
                accepted = true
            }
        }
        return accepted
    }

    /** Callback kết thúc cũ không được tắt phiên nhắc mới đang hoạt động. */
    suspend fun finish(event: ReminderEvent): Boolean {
        var accepted = false
        disk.edit { prefs ->
            if (decode(prefs).active == event) {
                clearActive(prefs)
                accepted = true
            }
        }
        return accepted
    }

    suspend fun snooze(event: ReminderEvent, now: Instant): ReminderEvent? {
        var result: ReminderEvent? = null
        disk.edit { prefs ->
            val current = decode(prefs)
            if (current.settings.enabled && current.settings.revision == event.revision && current.active == event) {
                val next = ReminderEvent(
                    ReminderKind.SNOOZE,
                    event.revision,
                    ReminderPolicy.snooze(now).toEpochMilli()
                )
                // Chỉ đổi slot snooze, giữ nguyên dailyDue của ngày mai. Xóa active làm action idempotent.
                prefs[SNOOZE_DUE] = next.dueEpochMillis
                clearActive(prefs)
                result = next
            }
        }
        return result
    }

    suspend fun skipToday(event: ReminderEvent, now: Instant, zone: ZoneId): Boolean {
        var accepted = false
        disk.edit { prefs ->
            val current = decode(prefs)
            if (current.active == event && current.settings.enabled && current.settings.revision == event.revision) {
                writeSettings(
                    prefs, current.settings.copy(
                        skipDate = now.atZone(zone).toLocalDate(),
                        revision = Math.addExact(current.settings.revision, 1L)
                    )
                )
                clearPending(prefs)
                clearActive(prefs)
                accepted = true
            }
        }
        return accepted
    }

    private fun decode(prefs: Preferences): ReminderSnapshot {
        val minute = prefs[MINUTE] ?: 1260
        val revision = prefs[REVISION] ?: 0L
        // Preference lỗi không được âm thầm biến thành alarm21:00 đang bật.
        val settings = if (minute !in 0 until 1440 || revision < 0L) ReminderPreferences()
        else ReminderPreferences(prefs[ENABLED] ?: false, minute, date(prefs[SKIP]), revision)
        val active = runCatching {
            val kind = prefs[ACTIVE_KIND] ?: return@runCatching null
            val rev = prefs[ACTIVE_REVISION] ?: return@runCatching null
            val due = prefs[ACTIVE_DUE] ?: return@runCatching null
            ReminderEvent(
                ReminderKind.valueOf(kind),
                rev,
                due
            ).takeIf { it.revision == settings.revision && settings.enabled }
        }.getOrNull()
        return ReminderSnapshot(
            settings,
            prefs[DAILY_DUE]?.takeIf { it > 0 },
            prefs[SNOOZE_DUE]?.takeIf { it > 0 },
            date(prefs[LAST_DAILY]),
            active
        )
    }

    private fun date(value: String?): LocalDate? =
        value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun dueKey(kind: ReminderKind) =
        if (kind == ReminderKind.DAILY) DAILY_DUE else SNOOZE_DUE

    private fun writeSettings(prefs: MutablePreferences, settings: ReminderPreferences) {
        prefs[ENABLED] = settings.enabled
        prefs[MINUTE] = settings.minuteOfDay
        prefs[REVISION] =
            settings.revision
        if (settings.skipDate == null) prefs.remove(SKIP) else prefs[SKIP] =
            settings.skipDate.toString()
    }

    private fun clearPending(prefs: MutablePreferences) {
        prefs.remove(DAILY_DUE)
        prefs.remove(SNOOZE_DUE)
    }

    private fun clearActive(prefs: MutablePreferences) {
        prefs.remove(ACTIVE_KIND)
        prefs.remove(ACTIVE_REVISION)
        prefs.remove(ACTIVE_DUE)
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("m5_reminder_enabled")
        val MINUTE = intPreferencesKey("m5_reminder_minute")
        val REVISION = longPreferencesKey("m5_reminder_revision")
        val SKIP = stringPreferencesKey("m5_reminder_skip_date")
        val DAILY_DUE = longPreferencesKey("m5_reminder_daily_due")
        val SNOOZE_DUE = longPreferencesKey("m5_reminder_snooze_due")
        val LAST_DAILY = stringPreferencesKey("m5_reminder_last_daily_date")
        val ACTIVE_KIND = stringPreferencesKey("m5_reminder_active_kind")
        val ACTIVE_REVISION = longPreferencesKey("m5_reminder_active_revision")
        val ACTIVE_DUE = longPreferencesKey("m5_reminder_active_due")
    }
}
