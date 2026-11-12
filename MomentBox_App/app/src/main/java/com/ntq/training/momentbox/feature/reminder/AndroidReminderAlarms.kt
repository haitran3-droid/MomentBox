package com.ntq.training.momentbox.feature.reminder

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ntq.training.momentbox.core.notification.NotificationChannels

class AndroidReminderAlarms(context: Context) : ReminderAlarmBackend {
    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)

    override fun capabilities(): ReminderCapabilities {
        val post = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            app,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        return ReminderCapabilities(
            post && NotificationManagerCompat.from(app)
                .areNotificationsEnabled() && NotificationChannels.channelEnabled(
                app,
                NotificationChannels.REMINDER
            ),
            Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms(),
            Build.VERSION.SDK_INT < 34 || app.getSystemService(NotificationManager::class.java)
                .canUseFullScreenIntent()
        )
    }

    fun intent(event: ReminderEvent, timing: ReminderTiming) =
        ReminderAlarmPayload.intent(app, event, timing)

    override fun replace(event: ReminderEvent, preferred: ReminderTiming): ReminderTiming {
        if (preferred == ReminderTiming.EXACT && (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())) {
            try {
                alarms.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    event.dueEpochMillis,
                    operation(event, ReminderTiming.EXACT)
                )
                return ReminderTiming.EXACT
            } catch (_: SecurityException) {
                // Quyền có thể bị thu hồi giữa check và Binder call. Ghi lại đúng INEXACT vào
                // cùng PendingIntent trước khi đặt fallback; không báo UI đã đặt exact thành công.
            }
        }
        alarms.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            event.dueEpochMillis,
            operation(event, ReminderTiming.INEXACT)
        )
        return ReminderTiming.INEXACT
    }

    private fun operation(event: ReminderEvent, timing: ReminderTiming): PendingIntent =
        PendingIntent.getBroadcast(
            app,
            requestCode(event.kind),
            intent(event, timing),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** NO_CREATE chỉ tra cứu token có sẵn, không vô tình tạo alarm khi cancel hoặc kiểm tra. */
    fun existing(kind: ReminderKind): PendingIntent? = PendingIntent.getBroadcast(
        app,
        requestCode(kind),
        intent(ReminderEvent(kind, 0, 1), ReminderTiming.INEXACT),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )

    override fun cancel(kind: ReminderKind) {
        existing(kind)?.let { operation -> alarms.cancel(operation)
        operation.cancel() }
    }

    private fun requestCode(kind: ReminderKind) = if (kind == ReminderKind.DAILY) 5200 else 5201
}