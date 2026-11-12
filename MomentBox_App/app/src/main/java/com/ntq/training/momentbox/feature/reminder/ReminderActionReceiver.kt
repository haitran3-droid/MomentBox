package com.ntq.training.momentbox.feature.reminder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.ntq.training.momentbox.MomentBoxApp

class ReminderActionReceiver : BroadcastReceiver() {
    companion object {
        private val actions = listOf("SNOOZE", "SKIP", "DISMISS")
        fun pending(context: Context, event: ReminderEvent, action: String): PendingIntent {
            require(action in actions)
            val intent = Intent(context, ReminderActionReceiver::class.java)
                .setAction(context.packageName + ".reminder." + action)
                .setData(("momentbox-internal://reminder-action/${event.kind}/${event.revision}/${event.dueEpochMillis}/$action").toUri())
                .putExtra("kind", event.kind.name).putExtra("revision", event.revision)
                .putExtra("due", event.dueEpochMillis)
            return PendingIntent.getBroadcast(
                context,
                5210 + actions.indexOf(action),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action =
            actions.firstOrNull { intent.action == context.packageName + ".reminder." + it }
                ?: return
        val event = runCatching {
            ReminderEvent(
                ReminderKind.valueOf(
                    intent.getStringExtra("kind") ?: return
                ), intent.getLongExtra("revision", -1), intent.getLongExtra("due", 0)
            )
        }.getOrNull() ?: return
        val expected =
            ("momentbox-internal://reminder-action/${event.kind}/${event.revision}/${event.dueEpochMillis}/$action").toUri()
        if (intent.data != expected) return
        val app = context.applicationContext as MomentBoxApp
        ReminderBroadcastWork.launch(goAsync()) {
            val accepted = when (action) {
                "SNOOZE" -> app.reminderScheduler.snooze(event)
                "SKIP" -> app.reminderScheduler.skipToday(event)
                else -> app.reminderStore.finish(event)
            }
            if (accepted) ReminderNotifications.cancel(app, event)
        }
    }
}
