package com.ntq.training.momentbox.feature.reminder

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.databinding.ActivityReminderBinding
import com.ntq.training.momentbox.MainActivity
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ReminderActivity : AppCompatActivity() {
    companion object {
        private const val KIND = "s20_kind"
        private const val REVISION = "s20_revision"
        private const val DUE = "s20_due"
        const val NOTIFICATION_ID = 5202
        fun intent(context: Context, event: ReminderEvent) =
            Intent(context, ReminderActivity::class.java)
                .putExtra(KIND, event.kind.name).putExtra(REVISION, event.revision)
                .putExtra(DUE, event.dueEpochMillis)

        private fun event(intent: Intent): ReminderEvent? = runCatching {
            ReminderEvent(
                ReminderKind.valueOf(intent.getStringExtra(KIND) ?: return null),
                intent.getLongExtra(REVISION, -1),
                intent.getLongExtra(DUE, 0)
            )
        }.getOrNull()
    }

    private lateinit var binding: ActivityReminderBinding
    private val app get() = application as MomentBoxApp
    private val keyguard get() = getSystemService(KeyguardManager::class.java)
    private var entry: ReminderEvent? = null
    private var pendingMood: Int? = null
    private var busy = false
    private var routed = false
    private val credential =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (!keyguard.isDeviceLocked) routeAfterUnlock()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        binding = ActivityReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                bars.bottom
            )
            insets
        }
        entry = event(intent)
        pendingMood = savedInstanceState?.getInt("pendingMood", 0)?.takeIf { it in 1..5 }
        binding.root.visibility = View.INVISIBLE
        val expected = entry ?: run {
            finishAndRemoveTask()
            return
        }
        listOf(
            binding.reminderMood1,
            binding.reminderMood2,
            binding.reminderMood3,
            binding.reminderMood4,
            binding.reminderMood5
        ).forEachIndexed { index, view ->
            view.setOnClickListener { requestWrite(index + 1) }
        }
        binding.reminderWrite.setOnClickListener { requestWrite(pendingMood ?: 1) }
        binding.reminderSnooze.setOnClickListener {
            performAction {
                app.reminderScheduler.snooze(
                    expected
                )
            }
        }
        binding.reminderSkip.setOnClickListener {
            performAction {
                app.reminderScheduler.skipToday(
                    expected
                )
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                performAction { app.reminderStore.finish(expected) }
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.reminderStore.snapshots.collect { state ->
                        if (!busy && (state.active != expected || !state.settings.enabled)) finishAndRemoveTask()
                        else binding.root.visibility = View.VISIBLE
                    }
                }
                launch {
                    while (isActive) {
                        val now = ZonedDateTime.now()
                        val locale = resources.configuration.locales[0]
                        binding.reminderClock.text =
                            now.format(DateTimeFormatter.ofPattern("HH:mm", locale))
                        binding.reminderDate.text = now.format(
                            DateTimeFormatter.ofPattern(
                                DateFormat.getBestDateTimePattern(
                                    locale,
                                    "EEEEdMMMM"
                                ), locale
                            )
                        )
                        delay(1000)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingMood != null && !keyguard.isDeviceLocked) routeAfterUnlock()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Heads-up/full-screen có thể gửi lại cùng event khi PIN đang mở. Không recreate và
        // làm mất mood đang chờ unlock; chỉ thay instance khi danh tính nhắc thực sự đổi.
        if (event(intent) == entry) return
        setIntent(intent)
        pendingMood = null
        recreate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingMood?.let { outState.putInt("pendingMood", it) }
        super.onSaveInstanceState(outState)
    }

    private fun requestWrite(mood: Int) {
        if (busy || routed) return
        pendingMood = mood
        entry?.let { ReminderRingingService.silence(this, it) }
        lifecycleScope.launch { cancelNotification() }
        if (!keyguard.isKeyguardLocked) {
            routeAfterUnlock()
            return
        }
        if (Build.VERSION.SDK_INT >= 26) keyguard.requestDismissKeyguard(this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    if (!isDestroyed) routeAfterUnlock()
                }
            }) else {
            @Suppress("DEPRECATION")
            val unlock =
                keyguard.createConfirmDeviceCredentialIntent(getString(R.string.s20_write), null)
            if (unlock != null) credential.launch(unlock)
            else {
                @Suppress("DEPRECATION")
                window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
                binding.root.postDelayed(
                    { if (!isDestroyed && !keyguard.isKeyguardLocked) routeAfterUnlock() },
                    300
                )
            }
        }
    }

    private fun routeAfterUnlock() {
        // isKeyguardLocked chỉ nói màn khóa còn hiển thị/đang bị S20 che, có thể vẫn true
        // sau PIN đúng. isDeviceLocked mới quyết định còn cần xác thực người dùng hay không.
        // Chỉ đường callback unlock/onResume tới đây; không tự xóa PIN hoặc bỏ qua OS.
        if (busy || routed || keyguard.isDeviceLocked) return
        val mood = pendingMood ?: return
        val expected = entry ?: return
        busy = true
        lifecycleScope.launch {
            try {
                if (app.reminderStore.finish(expected)) {
                    routed = true
                    cancelNotification()
                    val main = ReminderJournalRoute.put(
                        Intent(
                            this@ReminderActivity,
                            MainActivity::class.java
                        ), mood, System.currentTimeMillis()
                    )
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(main)
                    finishAndRemoveTask()
                } else {
                    busy = false
                    finishAndRemoveTask()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                busy = false
                Snackbar.make(
                    binding.root,
                    R.string.s20_action_error,
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun performAction(action: suspend () -> Boolean) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                if (action()) {
                    cancelNotification()
                    finishAndRemoveTask()
                } else {
                    busy = false
                    finishAndRemoveTask()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                busy = false
                Snackbar.make(
                    binding.root,
                    R.string.s20_action_error,
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun cancelNotification() {
        entry?.let { ReminderNotifications.cancel(this, it) }
    }
}
