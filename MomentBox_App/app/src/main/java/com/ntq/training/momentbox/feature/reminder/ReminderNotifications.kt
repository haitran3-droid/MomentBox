package com.ntq.training.momentbox.feature.reminder

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.ntq.training.momentbox.core.notification.NotificationChannels
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ReminderNotifications(private val app: MomentBoxApp) {
    companion object {
        private const val SCOPE = "m5_reminder_event"
        fun tag(event: ReminderEvent) =
            "m5-reminder:${event.kind}:${event.revision}:${event.dueEpochMillis}"

        private fun scope(record: StatusBarNotification) =
            record.notification.extras.getString(SCOPE) ?: record.tag

        /** Main tuần tự hóa với startForeground: cancel action cũ không xóa foreground của event mới. */
        suspend fun cancel(context: Context, event: ReminderEvent) =
            withContext(Dispatchers.Main.immediate) {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.activeNotifications.filter {
                    it.id == ReminderActivity.NOTIFICATION_ID && scope(
                        it
                    ) == tag(event)
                }
                    .forEach { manager.cancel(it.tag, it.id) }
            }
    }

    private val manager = app.getSystemService(NotificationManager::class.java)
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        worker.launch {
            app.reminderStore.snapshots.collect {
                // Chụp danh tính notice TRƯỚC khi đọc Store mới nhất. Sau suspension chỉ xóa đúng
                // danh tính đó, không để snapshot cũ xóa event mới cùng foreground notification ID.
                val observed = withContext(Dispatchers.Main.immediate) {
                    manager.activeNotifications.filter {
                        it.id == ReminderActivity.NOTIFICATION_ID && scope(it)?.startsWith("m5-reminder:") == true
                    }
                }
                val state = app.reminderStore.snapshot()
                val active = state.active?.takeIf {
                    state.settings.enabled && Instant.ofEpochMilli(it.dueEpochMillis)
                        .atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
                }
                withContext(Dispatchers.Main.immediate) {
                    observed.filter { scope(it) != active?.let(::tag) }.forEach { old ->
                        if (manager.activeNotifications.any {
                                it.id == old.id && it.tag == old.tag && scope(
                                    it
                                ) == scope(old)
                            }) manager.cancel(old.tag, old.id)
                    }
                }
                if (active == null) state.active?.let { app.reminderStore.finish(it) }
            }
        }
    }

    internal fun customSoundChannel(): Boolean =
        Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(NotificationChannels.REMINDER)
            ?.let {
                it.sound == null && it.importance >= NotificationManager.IMPORTANCE_DEFAULT
            } == true

    internal fun notice(
        event: ReminderEvent,
        timing: ReminderTiming,
        foreground: Boolean = false
    ): Notification {
        val capabilities = AndroidReminderAlarms(app).capabilities()
        val intent =
            ReminderActivity.intent(app, event).setAction(app.packageName + ".reminder.OPEN")
                .setData(("momentbox-internal://reminder-entry/${event.kind}/${event.revision}/${event.dueEpochMillis}").toUri())
        val content = PendingIntent.getActivity(
            app,
            5202,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(app, NotificationChannels.REMINDER)
            .setSmallIcon(R.drawable.ic_s20_edit_note)
            .setContentTitle(app.getString(R.string.s20_title))
            .setContentText(app.getString(R.string.s20_subtitle).replace('\n', ' '))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(content)
            .setAutoCancel(false).setOnlyAlertOnce(true)
            .setDeleteIntent(ReminderActionReceiver.pending(app, event, "DISMISS"))
            .addAction(
                0,
                app.getString(R.string.s20_snooze),
                ReminderActionReceiver.pending(app, event, "SNOOZE")
            )
            .addAction(
                0,
                app.getString(R.string.s20_skip),
                ReminderActionReceiver.pending(app, event, "SKIP")
            )
            .addExtras(Bundle().apply { putString(SCOPE, tag(event)) })
        if (foreground) builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (capabilities.fullScreen && capabilities.exact && timing == ReminderTiming.EXACT) builder.setFullScreenIntent(
            content,
            true
        )
        // Trước API26 chưa có channel; custom player phát alarm, notification không phát thêm sound.
        if (Build.VERSION.SDK_INT < 26 && customSoundChannel()) builder.setSound(null)
        return builder.build()
    }

    internal suspend fun ordinary(event: ReminderEvent, timing: ReminderTiming): Boolean {
        if (!AndroidReminderAlarms(app).capabilities().notifications || app.reminderStore.snapshot().active != event) return false
        return withContext(Dispatchers.Main.immediate) {
            try {
                manager.notify(
                    tag(event),
                    ReminderActivity.NOTIFICATION_ID,
                    notice(event, timing)
                )
                true
            } catch (_: SecurityException) {
                false
            }
        }
    }

    suspend fun show(event: ReminderEvent, timing: ReminderTiming): Boolean {
        currentCoroutineContext().ensureActive()
        val capabilities = AndroidReminderAlarms(app).capabilities()
        if (!capabilities.notifications || app.reminderStore.snapshot().active != event) return false
        // Exact alarm do người dùng đặt có exemption FGS. Inexact nền không được tự nâng thành
        // exemption; nếu Android từ chối start thì giữ native notice và báo capability thật ở S22.
        if (timing == ReminderTiming.EXACT && capabilities.exact && customSoundChannel()) {
            try {
                ContextCompat.startForegroundService(
                    app,
                    ReminderRingingService.intent(app, event, timing)
                )
                val posted = withTimeoutOrNull(3500) {
                    while (true) {
                        if (manager.activeNotifications.any {
                                it.id == ReminderActivity.NOTIFICATION_ID && scope(
                                    it
                                ) == tag(event)
                            }) return@withTimeoutOrNull true
                        if (app.reminderStore.snapshot().active != event) return@withTimeoutOrNull false
                        delay(50)
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
                if (posted == true) return true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* Fallback là notice, không báo MediaPlayer đã bắt đầu phát. */
            }
        }
        return ordinary(event, timing)
    }
}
