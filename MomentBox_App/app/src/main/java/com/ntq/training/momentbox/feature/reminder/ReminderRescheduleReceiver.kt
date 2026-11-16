package com.ntq.training.momentbox.feature.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ntq.training.momentbox.MomentBoxApp

class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = ReminderSystemEvent.fromAction(intent.action) ?: return
        Log.i("MomentBoxReminder", "System reschedule action=${intent.action}")
        val app = context.applicationContext as MomentBoxApp
        ReminderBroadcastWork.launch(goAsync()) {
            when (event) {
                ReminderSystemEvent.REBUILD -> app.reminderScheduler.forceReschedule()
                ReminderSystemEvent.RECONCILE -> app.reminderScheduler.reconcile()
            }
        }
    }
}
