package com.ntq.training.momentbox.feature.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ntq.training.momentbox.BuildConfig
import com.ntq.training.momentbox.MainActivity
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.base.BaseActivity
import com.ntq.training.momentbox.databinding.ActivitySplashBinding
import com.ntq.training.momentbox.feature.auth.AuthActivity
import com.ntq.training.momentbox.feature.onboarding.OnboardingActivity
import kotlinx.coroutines.launch

// Màn S01 yêu cầu splash thương hiệu riêng trong lúc đồng bộ cấu hình ban đầu.
@SuppressLint("CustomSplashScreen")
class SplashActivity : BaseActivity<ActivitySplashBinding>(ActivitySplashBinding::inflate) {

    /** Khởi tạo [SplashViewModel] thông qua Factory để tiêm thủ công các Repository & Storage */
    private val viewModel: SplashViewModel by viewModels {
        val app = MomentBoxApp.instance
        SplashViewModel.Factory(
            configRepository = app.configRepository,
            tokenStore = app.tokenStore,
            settingsDataStore = app.settingsDataStore
        )
    }

    private var updateRequiredDialog: AlertDialog? = null
    private var hasNavigated: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        // Gỡ bỏ màn hình khởi động của OS ngay lập tức để hiển thị Custom Splash theo Mockup
        splashScreen.setOnExitAnimationListener { it.remove() }
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    override fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.splashRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun initViews() {
        val versionText = if (BuildConfig.DEBUG) {
            getString(
                R.string.splash_version_format,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE
            )
        } else {
            getString(
                R.string.splash_version_release,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE
            )
        }
        binding.tvSplashVersion.text = versionText
    }

    override fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.showLoadingSpinner.collect { show ->
                        binding.pbSplashLoading.isVisible = show
                    }
                }

                launch {
                    viewModel.destination.collect { destination ->
                        when (destination) {
                            is SplashDestination.Loading -> {
                                // Keep waiting
                            }

                            is SplashDestination.UpdateRequired -> {
                                showUpdateRequiredDialog()
                            }

                            is SplashDestination.Onboarding -> {
                                navigateTo(OnboardingActivity::class.java)
                            }

                            is SplashDestination.Auth -> {
                                navigateTo(AuthActivity::class.java)
                            }

                            is SplashDestination.Main -> {
                                navigateTo(MainActivity::class.java)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showUpdateRequiredDialog() {
        if (isFinishing || isDestroyed) return
        if (updateRequiredDialog?.isShowing == true) return

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.splash_update_required_title)
            .setMessage(R.string.splash_update_required_message)
            .setCancelable(false)
            .setPositiveButton(R.string.splash_update_button) { _, _ ->
                finish()
            }
            .create()
        dialog.setOnDismissListener {
            if (updateRequiredDialog === dialog) {
                updateRequiredDialog = null
            }
        }
        updateRequiredDialog = dialog
        dialog.show()
    }

    private fun navigateTo(destinationClass: Class<*>) {
        if (hasNavigated || isFinishing) return
        hasNavigated = true
        val intent = Intent(this, destinationClass)
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        updateRequiredDialog?.dismiss()
        updateRequiredDialog = null
        super.onDestroy()
    }
}
