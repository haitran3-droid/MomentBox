package com.ntq.training.momentbox.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.view.descendants
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.snackbar.Snackbar
import com.ntq.training.momentbox.BuildConfig
import com.ntq.training.momentbox.core.ui.MediaImageModel
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.databinding.ActivitySettingsBinding
import com.ntq.training.momentbox.databinding.ItemSettingsRowBinding
import com.ntq.training.momentbox.domain.model.User
import com.ntq.training.momentbox.feature.auth.AuthActivity
import com.ntq.training.momentbox.feature.language.LanguageDialogFragment
import com.ntq.training.momentbox.feature.profile.ProfileActivity
import com.ntq.training.momentbox.feature.reminder.ReminderScheduleStatus
import com.ntq.training.momentbox.feature.reminder.ReminderSchedulingState
import com.ntq.training.momentbox.feature.reminder.ReminderTiming
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import java.util.Calendar
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val app get() = application as MomentBoxApp
    private val model: SettingsViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(type: Class<T>): T = SettingsViewModel(app) as T
        }
    }
    private lateinit var images: RequestManager
    private var previousUser: User? = null
    private var rendering = false
    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let(model::export)
        }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        enableEdgeToEdge()
        binding =
            ActivitySettingsBinding.inflate(layoutInflater)
            setContentView(binding.root)
        images = Glide.with(this)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
            bars.left,
            bars.top,
            bars.right,
            bars.bottom
        )
        insets
        }
        binding.settingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        row(
            binding.settingsLanguage,
            R.string.s22_language,
            R.drawable.ic_s22_language
        ) { showChoiceLanguage() }
        row(
            binding.settingsTheme,
            R.string.s22_theme,
            R.drawable.ic_s22_dark_mode
        ) { choice("theme", model.state.value.theme.ordinal) }
        row(binding.settingsReminder, R.string.s22_reminder, R.drawable.ic_s22_alarm) {
            if (supportFragmentManager.findFragmentByTag(ReminderTimeDialog.TAG) == null) ReminderTimeDialog.create(
                app.reminderScheduler.state.value.settings.minuteOfDay
            ).show(supportFragmentManager, ReminderTimeDialog.TAG)
        }
        binding.settingsReminder.apply {
            value.isVisible = false
            chevron.isVisible = false
            toggle.isVisible = true
            root.setPadding(dp(14), dp(2), dp(14), dp(2))
            toggle.setOnCheckedChangeListener { _, checked ->
                if (!rendering) model.reminder(
                    checked,
                    app.reminderScheduler.state.value.settings.minuteOfDay
                )
            }
        }
        row(
            binding.settingsCache,
            R.string.s22_cache,
            R.drawable.ic_s22_cleaning_services
        ) { model.clearCache() }
        binding.settingsCache.apply {
            value.isVisible = true
            value.minHeight = dp(26)
            value.gravity =
            Gravity.CENTER
            value.typeface =
            ResourcesCompat.getFont(
                this@SettingsActivity,
                R.font.be_vietnam_pro_medium
            )
            value.setText(R.string.s22_clear)
            value.setTextColor(
            ContextCompat.getColor(
                this@SettingsActivity,
                R.color.text_primary
            )
        )
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        value.setPadding(
            dp(9),
            dp(4),
            dp(9),
            dp(4)
        )
        value.setBackgroundResource(R.drawable.bg_s22_clear)
        chevron.isVisible = false
        }
        row(
            binding.settingsExport,
            R.string.s22_export,
            R.drawable.ic_s22_download
        ) {
            if (supportFragmentManager.findFragmentByTag(JournalExportConfirmDialog.TAG) == null) JournalExportConfirmDialog().show(
                supportFragmentManager,
                JournalExportConfirmDialog.TAG
            )
        }
        binding.settingsExport.subtitle.apply {
            isVisible = true
            setText(R.string.s22_export_location)
        }
        row(
            binding.settingsAutoplay,
            R.string.s22_autoplay,
            R.drawable.ic_s22_smart_display
        ) { choice("autoplay", autoplayIndex(model.state.value.autoplay)) }
        row(
            binding.settingsPermissions,
            R.string.s22_permissions,
            R.drawable.ic_s22_shield
        ) { startActivity(Intent(this, PermissionsActivity::class.java)) }
        binding.settingsPermissions.subtitle.apply {
            isVisible = true
            setText(R.string.s22_permission_hint)
        }
        row(binding.settingsDeveloper, R.string.s22_developer, R.drawable.ic_s22_bug_report) {
            com.ntq.training.momentbox.debug.DebugToolsEntry.intent(this)?.let { intent ->
                if (intent.resolveActivity(packageManager) != null) startActivity(intent) else snack(
                    R.string.s22_failed
                )
            }
        }
        binding.settingsDeveloper.iconBox.setBackgroundResource(R.drawable.bg_s22_developer)
        binding.settingsDeveloper.icon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                R.color.s22_developer_icon
            )
        )
        binding.settingsDeveloper.root.isVisible = BuildConfig.DEBUG
        val version = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.settingsDeveloper.subtitle.apply {
            isVisible = true
            text = getString(R.string.s22_debug_version, version)
        }
        binding.settingsVersion.text = version
        binding.settingsVersion.isVisible = !BuildConfig.DEBUG
        // CSS border-box: đường phân cách nằm trong52dp, không thêm1dp layout giữa các row.
        listOf(
            binding.settingsLanguage,
            binding.settingsTheme,
            binding.settingsCache,
            binding.settingsExport
        ).forEach {
            it.root.foreground =
                ContextCompat.getDrawable(this, R.drawable.bg_s22_row_divider)
        }
        if (BuildConfig.DEBUG) binding.settingsPermissions.root.foreground =
            ContextCompat.getDrawable(this, R.drawable.bg_s22_row_divider)
        listOf(
            binding.settingsLanguage,
            binding.settingsTheme,
            binding.settingsAutoplay
        ).forEach { it.value.isVisible = true }
        binding.settingsAccount.setOnClickListener {
            model.state.value.user?.let {
                startActivity(
                    ProfileActivity.intent(this, it.id).putExtra("editProfile", true)
                )
            }
        }
        binding.settingsLogout.setOnClickListener {
            if (supportFragmentManager.findFragmentByTag(
                    LogoutConfirmDialog.TAG
                ) == null
            ) LogoutConfirmDialog().show(supportFragmentManager, LogoutConfirmDialog.TAG)
        }
        supportFragmentManager.setFragmentResultListener(
            JournalExportConfirmDialog.REQUEST,
            this
        ) { _, _ -> if (Build.VERSION.SDK_INT >= 29) model.export() else createDocument.launch(model.exportName()) }
        supportFragmentManager.setFragmentResultListener(
            SettingsChoiceDialog.REQUEST,
            this
        ) { _, b ->
            if (b.getString("kind") == "theme") model.theme(b.getInt("selected")) else model.autoplay(
                b.getInt("selected")
            )
        }
        supportFragmentManager.setFragmentResultListener(
            ReminderTimeDialog.REQUEST,
            this
        ) { _, b ->
            model.reminder(
                app.reminderScheduler.state.value.settings.enabled,
                b.getInt("minute")
            )
        }
        supportFragmentManager.setFragmentResultListener(
            LogoutConfirmDialog.REQUEST,
            this
        ) { _, b -> app.logoutCoordinator.start(b.getBoolean("keepJournal", true)) }
        supportFragmentManager.setFragmentResultListener(
            LanguageDialogFragment.REQUEST_KEY,
            this
        ) { _, _ -> render(model.state.value) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.state.collect(::render) }
                launch { app.reminderScheduler.state.collect(::reminder) }
                launch {
                    app.logoutCoordinator.phase.collect { phase ->
                        val running = phase == LogoutPhase.RUNNING
                        binding.settingsLogout.isEnabled = !running
                        binding.settingsScroll.descendants.forEach { it.isEnabled = !running }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
        lifecycleScope.launch {
            if (!app.authRepository.isLoggedIn() && app.logoutCoordinator.phase.value != LogoutPhase.RUNNING) lifecycle.withResumed {
                startActivity(
                    Intent(
                        this@SettingsActivity,
                        AuthActivity::class.java
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
        }
    }

    private fun row(row: ItemSettingsRowBinding, title: Int, icon: Int, click: () -> Unit) {
        row.title.setText(title)
        row.icon.setImageResource(icon)
        row.root.setOnClickListener { if (!model.state.value.busy && app.logoutCoordinator.phase.value != LogoutPhase.RUNNING) click() }
    }

    private fun choice(kind: String, selected: Int) {
        if (supportFragmentManager.findFragmentByTag(SettingsChoiceDialog.TAG) == null) SettingsChoiceDialog.create(
            kind,
            selected
        ).show(supportFragmentManager, SettingsChoiceDialog.TAG)
    }

    private fun showChoiceLanguage() {
        if (supportFragmentManager.findFragmentByTag(LanguageDialogFragment.TAG) == null) LanguageDialogFragment.newInstance()
            .show(supportFragmentManager, LanguageDialogFragment.TAG)
    }

    private fun autoplayIndex(value: String) = when (value) {
        SettingsDataStore.AUTOPLAY_ALWAYS -> 0
        SettingsDataStore.AUTOPLAY_NEVER -> 2
        else -> 1
    }

    private fun render(state: SettingsScreenState) {
        binding.settingsName.text = state.user?.displayName.orEmpty()
        binding.settingsEmail.text =
            state.user?.email.orEmpty()
        binding.settingsAccount.isEnabled = state.user != null
        if (previousUser != state.user) {
            previousUser = state.user
            binding.settingsInitials.text =
                state.user?.displayName?.split(' ')?.filter { it.isNotBlank() }?.takeLast(2)
                    ?.mapNotNull { it.firstOrNull() }?.joinToString("").orEmpty()
            state.user?.let { user ->
                images.load(user.avatarUrl?.let {
                    MediaImageModel.resolve(
                        this,
                        it
                    )
                }).signature(ObjectKey(user.avatarVersion)).circleCrop()
                    .into(binding.settingsAvatar)
            } ?: images.clear(binding.settingsAvatar)
        }
        val language = AppCompatDelegate.getApplicationLocales().get(0)?.language
            ?: resources.configuration.locales.get(0).language
        binding.settingsLanguage.value.text = when (language) {
            "ja" -> "日本語"
            "en" -> "English"
            else -> "Tiếng Việt"
        }
        binding.settingsTheme.value.text =
            resources.getStringArray(R.array.s22_theme_options)[state.theme.ordinal]
        binding.settingsAutoplay.value.text =
            resources.getStringArray(R.array.s22_autoplay_options)[autoplayIndex(state.autoplay)]
        binding.settingsCache.subtitle.apply {
            isVisible = true
            text =
            state.cacheBytes?.let { Formatter.formatFileSize(this@SettingsActivity, it) }
                ?: getString(R.string.s22_working)
        }
        binding.settingsCache.root.isEnabled = !state.busy
        binding.settingsExport.root.isEnabled =
            !state.busy
        state.notice?.let { notice ->
            val label = when (notice.kind) {
                "cache" -> R.string.s22_cache_cleared
                "export" -> R.string.s22_exported
                else -> R.string.s22_failed
            }
            Snackbar.make(binding.root, label, Snackbar.LENGTH_LONG).apply {
                notice.uri?.let { uri ->
                    setAction(R.string.s22_open) {
                        openJournalExport(uri)
                    }
                }
            }.show()
            model.acknowledge(notice.id)
        }
    }

    private fun openJournalExport(uri: Uri) {
        val viewFile = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/json")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(viewFile)
        } catch (_: ActivityNotFoundException) {
            // Android không luôn có app đọc JSON. Xem trước đúng URI vừa xuất trong Activity private.
            val tag = JournalExportPreviewDialog.TAG
            if (!supportFragmentManager.isStateSaved && supportFragmentManager.findFragmentByTag(tag) == null) {
                JournalExportPreviewDialog.forExport(uri).show(supportFragmentManager, tag)
            }
        }
    }

    private fun reminder(state: ReminderSchedulingState) {
        rendering = true
        binding.settingsReminder.toggle.isChecked =
            state.settings.enabled
            rendering = false
        val date = Calendar.getInstance().apply {
            set(
                Calendar.HOUR_OF_DAY,
                state.settings.minuteOfDay / 60
            )
            set(Calendar.MINUTE, state.settings.minuteOfDay % 60)
        }.time
        val time = DateFormat.getTimeFormat(this).format(date)
        val detail = when {
            state.status == ReminderScheduleStatus.NOTIFICATIONS_BLOCKED -> getString(R.string.s22_reminder_blocked)
            state.settings.enabled -> getString(if (state.daily?.timing == ReminderTiming.EXACT) R.string.s22_reminder_exact else R.string.s22_reminder_fallback)
            else -> null
        }
        binding.settingsReminder.subtitle.apply {
            isVisible = true
            text = getString(
            if (state.settings.enabled) R.string.s22_daily else R.string.s22_reminder_off,
            time
        ) + (detail?.let { "\n$it" } ?: "")
        }
    }

    private fun snack(message: Int) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
