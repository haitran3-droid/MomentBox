package com.ntq.training.momentbox.feature.settings

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.ntq.training.momentbox.databinding.ItemSettingsRowBinding
import com.ntq.training.momentbox.feature.auth.AuthActivity
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import kotlinx.coroutines.launch

class PermissionsActivity : AppCompatActivity() {
    private val rows = mutableMapOf<String, ItemSettingsRowBinding>()
    private val app get() = application as MomentBoxApp
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        enableEdgeToEdge()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(
            ContextCompat.getColor(
                this@PermissionsActivity,
                R.color.background
            )
        )
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(
            ContextCompat.getColor(
                this@PermissionsActivity,
                R.color.background
            )
        )
        }
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_s22_arrow_back)
            imageTintList =
            ColorStateList.valueOf(
                ContextCompat.getColor(
                    context,
                    R.color.text_primary
                )
            )
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription =
            getString(R.string.s22_back)
            setPadding(
            dp(16),
            dp(16),
            dp(16),
            dp(16)
        )
        setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        bar.addView(TextView(this).apply {
            setText(R.string.s22_permissions)
            textSize = 19f
            typeface =
            ResourcesCompat.getFont(
                context,
                R.font.be_vietnam_pro_semibold
            )
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })
        root.addView(bar)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
            dp(16),
            dp(16),
            dp(16),
            dp(24)
        )
        }
        content.addView(TextView(this).apply {
            setText(R.string.s22_permission_help)
            textSize = 13f
            setTextColor(
            ContextCompat.getColor(
                context,
                R.color.text_secondary
            )
        )
        setPadding(0, 0, 0, dp(16))
        })
        val items = listOf(
            Triple(
                "notification",
                R.string.s22_notifications,
                R.drawable.ic_s22_notifications
            ),
            Triple("camera", R.string.s22_camera, R.drawable.ic_s22_photo_camera),
            Triple("mic", R.string.s22_microphone, R.drawable.ic_s22_mic),
            Triple("media", R.string.s22_media, R.drawable.ic_s22_collections),
            Triple("exact", R.string.s22_exact, R.drawable.ic_s22_schedule),
            Triple("fullscreen", R.string.s22_fullscreen, R.drawable.ic_s22_fullscreen)
        )
        items.forEach { (key, title, icon) ->
            val row = ItemSettingsRowBinding.inflate(layoutInflater, content, false)
            rows[key] =
            row
            row.title.setText(title)
            row.icon.setImageResource(icon)
            row.subtitle.isVisible =
            true
            row.value.isVisible = false
            row.root.tag = "permission.$key"
            row.root.setBackgroundColor(
            ContextCompat.getColor(
                this,
                R.color.surface
            )
        )
        row.root.setOnClickListener { open(key) }
            content.addView(row.root)
            content.addView(View(this).apply {
            setBackgroundColor(
                ContextCompat.getColor(context, R.color.divider)
            )
        }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        root.addView(
            NestedScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
            bars.left,
            bars.top,
            bars.right,
            bars.bottom
        )
        insets
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        lifecycleScope.launch {
            if (!app.authRepository.isLoggedIn()) lifecycle.withResumed {
                startActivity(
                    Intent(
                        this@PermissionsActivity,
                        AuthActivity::class.java
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun render() {
        fun state(key: String, allowed: Boolean) {
            rows.getValue(key).subtitle.setText(if (allowed) R.string.s22_granted else R.string.s22_denied)
        }
        state(
            "notification",
            NotificationManagerCompat.from(this)
                .areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
        )
        state("camera", granted(Manifest.permission.CAMERA))
        state(
            "mic",
            granted(Manifest.permission.RECORD_AUDIO)
        )
        val images =
            if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) else granted(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        val videos =
            if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_VIDEO) else images
        val partial =
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        rows.getValue("media").subtitle.text = when {
            images && videos -> getString(R.string.s22_granted)
            images || videos -> getString(
                R.string.s22_media_mixed,
                getString(if (images) R.string.s22_granted else if (partial) R.string.s22_partial else R.string.s22_denied),
                getString(if (videos) R.string.s22_granted else if (partial) R.string.s22_partial else R.string.s22_denied)
            )

            partial -> getString(R.string.s22_partial)
            else -> getString(R.string.s22_denied)
        }
        if (Build.VERSION.SDK_INT >= 31) state(
            "exact",
            getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        ) else unavailable("exact")
        if (Build.VERSION.SDK_INT >= 34) state(
            "fullscreen",
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) else unavailable("fullscreen")
    }

    private fun unavailable(key: String) {
        rows.getValue(key).apply {
            subtitle.setText(R.string.s22_not_required)
            root.isEnabled = false
            chevron.isVisible =
            false
        }
    }

    private fun open(key: String) {
        val uri = Uri.parse("package:$packageName")
        val intent = when {
            key == "notification" && Build.VERSION.SDK_INT >= 26 -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                Settings.EXTRA_APP_PACKAGE,
                packageName
            )

            key == "exact" && Build.VERSION.SDK_INT >= 31 -> Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                uri
            )

            key == "fullscreen" && Build.VERSION.SDK_INT >= 34 -> Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                uri
            )

            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
