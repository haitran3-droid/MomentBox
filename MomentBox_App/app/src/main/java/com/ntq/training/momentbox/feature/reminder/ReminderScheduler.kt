package com.ntq.training.momentbox.feature.reminder

import com.ntq.training.momentbox.data.local.prefs.ReminderStore
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ReminderScheduler(
    private val store: ReminderStore,
    private val alarms: ReminderAlarmBackend,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val gate = Mutex()
    private val mutableState = MutableStateFlow(ReminderSchedulingState())
    val state = mutableState.asStateFlow()
    private suspend fun <T> serial(block: suspend () -> T): T =
        withContext(io) { gate.withLock { block() } }

    suspend fun configure(enabled: Boolean, minuteOfDay: Int): ReminderSchedulingState {
        require(minuteOfDay in 0 until 1440)
        return serial { store.configure(enabled, minuteOfDay)
        reconcileLocked() }
    }

    suspend fun reconcile(): ReminderSchedulingState = serial { reconcileLocked() }
    suspend fun forceReschedule(): ReminderSchedulingState = serial {
        store.invalidateSchedule()
        reconcileLocked()
    }

    suspend fun claim(event: ReminderEvent): Boolean = serial {
        val accepted = store.claim(event, clock.instant(), zone())
        // Đặt ngày kế ngay sau claim, trước query Room/decode/âm thanh của receiver.
        reconcileLocked()
        accepted
    }

    suspend fun snooze(event: ReminderEvent): Boolean = serial {
        if (store.snooze(event, clock.instant()) == null) false
        else {
            reconcileLocked()
            true
        }
    }

    suspend fun skipToday(event: ReminderEvent): Boolean = serial {
        val accepted = store.skipToday(event, clock.instant(), zone())
        if (accepted) reconcileLocked()
        accepted
    }

    private suspend fun reconcileLocked(): ReminderSchedulingState {
        try {
            val snapshot = store.snapshot()
            val capabilities = alarms.capabilities()
            mutableState.value =
                ReminderSchedulingState(settings = snapshot.settings, capabilities = capabilities)
            if (!snapshot.settings.enabled || !capabilities.notifications) {
                ReminderKind.entries.forEach { kind ->
                    alarms.cancel(kind)
                    val due =
                        if (kind == ReminderKind.DAILY) snapshot.dailyDue else snapshot.snoozeDue
                    due?.let {
                        store.unschedule(
                            ReminderEvent(
                                kind,
                                snapshot.settings.revision,
                                it
                            )
                        )
                    }
                }
                snapshot.active?.let { store.finish(it) }
                val status =
                    if (snapshot.settings.enabled) ReminderScheduleStatus.NOTIFICATIONS_BLOCKED else ReminderScheduleStatus.OFF
                return ReminderSchedulingState(
                    status,
                    snapshot.settings,
                    capabilities
                ).also { mutableState.value = it }
            }
            val now = clock.instant()
            val currentZone = zone()
            val today = now.atZone(currentZone).toLocalDate()
            val settings = snapshot.settings
            val effective =
                if (snapshot.lastDailyDate == today) settings.copy(skipDate = today) else settings
            val saved = snapshot.dailyDue?.let { Instant.ofEpochMilli(it) }
            val savedDay = saved?.atZone(currentZone)?.toLocalDate()
            val sameWallTime =
                savedDay?.atTime(LocalTime.of(settings.minuteOfDay / 60, settings.minuteOfDay % 60))
                    ?.atZone(currentZone)?.toInstant() == saved
            val retain =
                saved != null && savedDay != null && !savedDay.isBefore(today) && savedDay != effective.skipDate &&
                        savedDay != snapshot.lastDailyDate && sameWallTime
            // App/receiver có thể cùng cold-start đúng lúc alarm đến. Giữ due chưa claim trong ngày,
            // thay vì ghi ngày mai làm broadcast đang tới mất identity. Set lại qua backend vẫn là
            // alarm thật; claim atomic chặn phát lặp. Quá giờ được ghi lateRecovery, không coi đúng giờ.
            val dailyAt = if (retain) requireNotNull(saved) else requireNotNull(
                ReminderPolicy.nextDaily(
                    effective,
                    now,
                    currentZone
                )
            )
            val preferred = if (capabilities.exact) ReminderTiming.EXACT else ReminderTiming.INEXACT
            val daily = register(
                ReminderEvent(
                    ReminderKind.DAILY,
                    settings.revision,
                    dailyAt.toEpochMilli()
                ), preferred
            )
            var snooze: ReminderScheduled? = null
            snapshot.snoozeDue?.let { due ->
                val date = Instant.ofEpochMilli(due).atZone(currentZone).toLocalDate()
                val event = ReminderEvent(ReminderKind.SNOOZE, settings.revision, due)
                if (!date.isBefore(today) && date != settings.skipDate) snooze =
                    register(event, preferred)
                else {
                    alarms.cancel(ReminderKind.SNOOZE)
                    store.unschedule(event)
                }
            } ?: alarms.cancel(ReminderKind.SNOOZE)
            val late =
                daily.event.dueEpochMillis < now.toEpochMilli() || (snooze?.event?.dueEpochMillis?.let { it < now.toEpochMilli() } == true)
            return ReminderSchedulingState(
                ReminderScheduleStatus.SCHEDULED,
                settings,
                capabilities,
                daily,
                snooze,
                late
            )
                .also { mutableState.value = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return mutableState.value.copy(
                status = ReminderScheduleStatus.ERROR,
                daily = null,
                snooze = null,
                error = "REMINDER_SCHEDULE_FAILED"
            )
                .also { mutableState.value = it }
        }
    }

    private suspend fun register(
        event: ReminderEvent,
        preferred: ReminderTiming
    ): ReminderScheduled {
        check(store.schedule(event)) { "Reminder configuration changed during scheduling" }
        try {
            return ReminderScheduled(event, alarms.replace(event, preferred))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            store.unschedule(event)
            runCatching { alarms.cancel(event.kind) }
            throw error
        }
    }
}
