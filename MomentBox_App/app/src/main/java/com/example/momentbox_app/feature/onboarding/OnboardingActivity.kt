package com.example.momentbox_app.feature.onboarding
import com.example.momentbox_app.MomentBoxApp
import com.example.momentbox_app.feature.auth.AuthActivity
import com.example.momentbox_app.feature.language.LanguageDialogFragment
import com.ntq.training.momentbox.R
import java.util.Locale
import kotlinx.coroutines.launch
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import com.example.momentbox_app.core.base.BaseActivity
import com.ntq.training.momentbox.databinding.ActivityOnboardingBinding

class OnboardingActivity : BaseActivity<ActivityOnboardingBinding>(ActivityOnboardingBinding::inflate) {

    private lateinit var pagerAdapter: OnboardingPagerAdapter
    private var currentPage: Int = 0

    companion object {
        /** Khóa lưu vị trí trang hiện tại khi Activity bị tạo lại do xoay màn hình */
        private const val KEY_CURRENT_PAGE = "key_current_page"
    }

    override fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.onboardingRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    override fun initViews() {
        pagerAdapter = OnboardingPagerAdapter(this)
        binding.viewPagerOnboarding.adapter = pagerAdapter
        binding.pageIndicator.setPageCount(pagerAdapter.count)

        binding.viewPagerOnboarding.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                binding.pageIndicator.onPageScrolled(position, positionOffset)
            }

            override fun onPageSelected(position: Int) {
                currentPage = position
                updatePageUi(position)
            }

            override fun onPageScrollStateChanged(state: Int) {}
        })

        updateLanguageChipLabel()
        updatePageUi(currentPage)

        binding.chipLanguage.setOnClickListener {
            LanguageDialogFragment.newInstance().show(supportFragmentManager, LanguageDialogFragment.TAG)
        }

        binding.btnSkip.setOnClickListener {
            completeOnboarding()
        }

        binding.btnNext.setOnClickListener {
            if (binding.viewPagerOnboarding.currentItem < pagerAdapter.count - 1) {
                binding.viewPagerOnboarding.setCurrentItem(binding.viewPagerOnboarding.currentItem + 1, true)
            } else {
                completeOnboarding()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.viewPagerOnboarding.currentItem > 0) {
                    binding.viewPagerOnboarding.setCurrentItem(binding.viewPagerOnboarding.currentItem - 1, true)
                } else {
                    finish()
                }
            }
        })
    }

    private fun updatePageUi(position: Int) {
        val isLastPage = position == pagerAdapter.count - 1
        binding.btnSkip.isInvisible = isLastPage

        if (isLastPage) {
            binding.tvBtnNextText.setText(R.string.onboarding_start)
            binding.ivBtnNextIcon.isGone = true
        } else {
            binding.tvBtnNextText.setText(R.string.onboarding_next)
            binding.ivBtnNextIcon.isGone = false
        }
    }

    private fun updateLanguageChipLabel() {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val language = if (!appLocales.isEmpty) {
            appLocales.get(0)?.language ?: "vi"
        } else {
            Locale.getDefault().language.ifBlank { "vi" }
        }

        val label = when {
            language.startsWith("en", ignoreCase = true) -> getString(R.string.language_en)
            language.startsWith("ja", ignoreCase = true) -> getString(R.string.language_ja)
            else -> getString(R.string.language_vi)
        }
        binding.tvCurrentLanguage.text = label
    }

    private fun completeOnboarding() {
        lifecycleScope.launch {
            val app = MomentBoxApp.instance
            app.settingsDataStore.setOnboardingDone(true)

            val intent = Intent(this@OnboardingActivity, AuthActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_PAGE, binding.viewPagerOnboarding.currentItem)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        currentPage = savedInstanceState.getInt(KEY_CURRENT_PAGE, 0)
        binding.viewPagerOnboarding.currentItem = currentPage
        updatePageUi(currentPage)
    }
}