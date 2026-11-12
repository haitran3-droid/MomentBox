package com.ntq.training.momentbox.feature.reminder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.ntq.training.momentbox.BuildConfig
import java.util.Locale

object ReminderAlarmPayload {
    private const val KIND = "reminder_kind"
    private const val REVISION = "reminder_revision"
    private const val DUE = "reminder_due"
    private const val TIMING = "reminder_timing"
    fun intent(context: Context, event: ReminderEvent, timing: ReminderTiming): Intent =
        Intent(context.packageName + ".reminder." + event.kind.name)
            .setComponent(ComponentName(context, ReminderAlarmReceiver::class.java))
            .setData(("momentbox-internal://reminder/" + event.kind.name.lowercase(Locale.ROOT)).toUri())
            .putExtra(KIND, event.kind.name).putExtra(REVISION, event.revision)
            .putExtra(DUE, event.dueEpochMillis).putExtra(TIMING, timing.name)

    fun event(intent: Intent): ReminderEvent? = runCatching {
        val kind = ReminderKind.valueOf(intent.getStringExtra(KIND) ?: return null)
        val component = intent.component ?: return null
        if (component.packageName != BuildConfig.APPLICATION_ID ||
            component.className != component.packageName + ".feature.reminder.ReminderAlarmReceiver" ||
            intent.action != component.packageName + ".reminder." + kind.name ||
            intent.data != ("momentbox-internal://reminder/" + kind.name.lowercase(Locale.ROOT)).toUri()
        ) return null
        ReminderEvent(kind, intent.getLongExtra(REVISION, -1), intent.getLongExtra(DUE, 0))
    }.getOrNull()

    fun timing(intent: Intent): ReminderTiming? = runCatching {
        ReminderTiming.valueOf(intent.getStringExtra(TIMING) ?: return null)
    }.getOrNull()
}
