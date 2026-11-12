package com.ntq.training.momentbox.feature.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ntq.training.momentbox.MomentBoxApp

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = ReminderAlarmPayload.event(intent) ?: return
        val timing = ReminderAlarmPayload.timing(intent) ?: return
        val app = context.applicationContext as MomentBoxApp
        ReminderBroadcastWork.launch(goAsync()) {
            val result = app.reminderDelivery.onAlarm(event, timing)
            Log.i(
                "MomentBoxReminder",
                "Alarm due=${event.dueEpochMillis} timing=$timing result=$result"
            )
        }
    }
}