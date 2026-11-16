package com.ntq.training.momentbox.feature.reminder

import android.content.BroadcastReceiver
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

internal object ReminderBroadcastWork {
    fun launch(pending: BroadcastReceiver.PendingResult, work: suspend () -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val main = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        fun finish() {
            if (finished.compareAndSet(false, true)) pending.finish()
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Log.w(
                    "MomentBoxReminder",
                    "Broadcast work failed; reminder not confirmed"
                )
            } finally {
                finish()
            }
        }
        val deadline = Runnable {
            job.cancel()
            finish()
        }
        job.invokeOnCompletion {
            main.removeCallbacks(deadline)
            scope.cancel()
        }
        main.postDelayed(deadline, 8500)
        job.start()
    }
}
