package com.ntq.training.momentbox.feature.reminder

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.ntq.training.momentbox.MomentBoxApp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull

class ReminderRingingService : Service() {
    companion object {
        private val mutable = MutableStateFlow(ReminderRingState())
        val state = mutable.asStateFlow()

        /** Gọi từ Activity đang hiển thị: im chuông ngay nhưng giữ active cho tới khi OS mở khóa.
         * Không giữ Service reference tĩnh; lệnh private mang đúng event để không dừng nhắc mới. */
        fun silence(context: Context, event: ReminderEvent) {
            if (state.value.event == event && state.value.phase in listOf(
                    ReminderRingPhase.PREPARING,
                    ReminderRingPhase.RINGING
                )
            )
                context.startService(
                    intent(
                        context,
                        event,
                        ReminderTiming.EXACT
                    ).setAction(context.packageName + ".reminder.QUIET")
                )
        }

        fun intent(context: Context, event: ReminderEvent, timing: ReminderTiming) =
            Intent(context, ReminderRingingService::class.java)
                .setAction(context.packageName + ".reminder.RING").putExtra("kind", event.kind.name)
                .putExtra("revision", event.revision).putExtra("due", event.dueEpochMillis)
                .putExtra("timing", timing.name)
    }

    private sealed interface Command {
        data class Start(val event: ReminderEvent?, val timing: ReminderTiming?, val id: Int) :
            Command

        data class End(val event: ReminderEvent, val keepNotice: Boolean = true) : Command
        data class Quiet(val event: ReminderEvent, val id: Int) : Command
    }

    private val app get() = application as MomentBoxApp
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private lateinit var driver: AndroidReminderRingDriver
    private lateinit var controller: ReminderRingController
    private var event: ReminderEvent? = null
    private var timing = ReminderTiming.EXACT
    private var lastSettledStart = 0
    private var promoted = false
    private var observing: Job? = null
    private val deadline = Handler(Looper.getMainLooper())
    private val stopUnpromoted = Runnable { if (!promoted) stopSelf() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        driver = AndroidReminderRingDriver(this, owner)
        controller = ReminderRingController(driver, owner, SystemClock::elapsedRealtime)
        deadline.postDelayed(stopUnpromoted, 4000)
        owner.launch {
            controller.state.collect { current ->
                mutable.value = current
                if (current.event != null && current.phase !in listOf(
                        ReminderRingPhase.IDLE,
                        ReminderRingPhase.PREPARING,
                        ReminderRingPhase.RINGING
                    )
                )
                    commands.send(Command.End(current.event))
            }
        }
        owner.launch {
            for (command in commands) when (command) {
                is Command.Start -> accept(command)
                is Command.End -> end(command.event, command.keepNotice)
                is Command.Quiet -> {
                    lastSettledStart = command.id
                    if (event == command.event) end(
                        command.event,
                        false
                    ) else if (event == null) stopSelfResult(command.id)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val parsed = runCatching {
            require(
                intent?.action in listOf(
                    packageName + ".reminder.RING",
                    packageName + ".reminder.QUIET"
                )
            )
            ReminderEvent(
                ReminderKind.valueOf(requireNotNull(intent?.getStringExtra("kind"))),
                intent.getLongExtra("revision", -1),
                intent.getLongExtra("due", 0)
            ) to
                    ReminderTiming.valueOf(requireNotNull(intent.getStringExtra("timing")))
        }.getOrNull()
        if (intent?.action == packageName + ".reminder.QUIET" && parsed != null) {
            commands.trySend(Command.Quiet(parsed.first, startId))
        } else commands.trySend(Command.Start(parsed?.first, parsed?.second, startId))
        return START_NOT_STICKY
    }

    private suspend fun accept(command: Command.Start) {
        lastSettledStart = command.id
        val incoming = command.event ?: run {
            if (event == null) stopSelfResult(command.id)
            return
        }
        val valid = if (command.timing == ReminderTiming.EXACT) withTimeoutOrNull(1500) {
            val stored = app.reminderStore.snapshot()
            val caps = AndroidReminderAlarms(this@ReminderRingingService).capabilities()
            stored.settings.enabled && stored.active == incoming && caps.notifications && caps.exact && app.reminderNotifications.customSoundChannel()
        } == true else false
        if (!valid) {
            if (event == null) stopSelfResult(command.id)
            return
        }
        if (controller.state.value.event == incoming && controller.state.value.phase !in listOf(
                ReminderRingPhase.IDLE,
                ReminderRingPhase.PREPARING,
                ReminderRingPhase.RINGING
            )
        ) {
            if (event == null) stopSelfResult(command.id)
            return
        }
        if (event == incoming) return // Không reset ramp, timeout hay MediaPlayer khi intent trùng.
        observing?.cancel()
        controller.stop()
        event = incoming
        timing = requireNotNull(command.timing)
        try {
            val notification = app.reminderNotifications.notice(
                requireNotNull(incoming),
                timing,
                foreground = true
            )
            ServiceCompat.startForeground(
                this, ReminderActivity.NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            )
            promoted = true
            deadline.removeCallbacks(stopUnpromoted)
            // startForeground không hỗ trợ tag. Bỏ notice fallback cùng event nếu đã xuất hiện,
            // chỉ xóa đúng tag, không đụng foreground untagged vừa được OS chấp nhận.
            getSystemService(NotificationManager::class.java).cancel(
                ReminderNotifications.tag(
                    incoming
                ), ReminderActivity.NOTIFICATION_ID
            )
            observing = owner.launch {
                while (isActive) {
                    val stored = app.reminderStore.snapshot()
                    val caps = AndroidReminderAlarms(this@ReminderRingingService).capabilities()
                    val sameDate = Instant.ofEpochMilli(incoming.dueEpochMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate() == LocalDate.now()
                    if (stored.active != incoming || !stored.settings.enabled || !caps.notifications || !caps.exact || !app.reminderNotifications.customSoundChannel() || !sameDate) {
                        commands.send(Command.End(incoming))
                        return@launch
                    }
                    delay(200)
                }
            }
            controller.start(incoming)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            controller.stop()
            event = null
            observing?.cancel()
            if (promoted) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                promoted =
                    false
            }
            app.reminderNotifications.ordinary(incoming, timing)
            stopSelfResult(command.id)
        }
    }

    private suspend fun end(expected: ReminderEvent, keepNotice: Boolean) {
        if (event != expected) return
        controller.stop()
        observing?.cancel()
        observing = null
        val stored = app.reminderStore.snapshot()
        // Chỉ chuyển notice sang thường sau khi player/focus đã nhả. Store còn active cho phép
        // người dùng chạm Viết/Nhắc lại sau timeout; cấu hình đã tắt phải dọn notice của chính event.
        if (promoted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            promoted =
                false
            if (!keepNotice || stored.active != expected || !stored.settings.enabled || !AndroidReminderAlarms(
                    this
                ).capabilities().notifications
            )
                ReminderNotifications.cancel(this, expected)
            else getSystemService(NotificationManager::class.java).notify(
                ReminderActivity.NOTIFICATION_ID,
                app.reminderNotifications.notice(expected, timing)
            )
        }
        event = null
        stopSelfResult(lastSettledStart)
    }

    override fun onDestroy() {
        deadline.removeCallbacks(stopUnpromoted)
        controller.stop()
        mutable.value =
            controller.state.value
            driver.release()
            observing?.cancel()
            commands.close()
            owner.cancel()
        // OS có thể hủy service; không launch coroutine từ onDestroy để ghi đè notice event mới.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        super.onDestroy()
    }
}
