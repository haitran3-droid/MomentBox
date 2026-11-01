package com.example.momentbox_app.feature.splash
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.momentbox_app.data.local.prefs.SettingsStorage
import com.example.momentbox_app.data.local.prefs.TokenStorage
import com.example.momentbox_app.data.repository.ConfigRepository
import com.ntq.training.momentbox.BuildConfig

import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
sealed interface SplashDestination {
    data object Loading : SplashDestination
    data class UpdateRequired(val minVersion: Int) : SplashDestination
    data object Onboarding : SplashDestination
    data object Auth : SplashDestination
    data object Main : SplashDestination
}

class SplashViewModel(
    private val configRepository: ConfigRepository,
    private val tokenStore: TokenStorage,
    private val settingsDataStore: SettingsStorage,
    private val minDurationMs: Long = 800L,
    private val maxDurationMs: Long = 3000L
) : ViewModel() {

    private val _destination = MutableStateFlow<SplashDestination>(SplashDestination.Loading)
    val destination: StateFlow<SplashDestination> = _destination.asStateFlow()

    private val _showLoadingSpinner = MutableStateFlow(false)
    val showLoadingSpinner: StateFlow<Boolean> = _showLoadingSpinner.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        // Tự động khởi động luồng kiểm tra ngay khi ViewModel được tạo
        startStartupFlow()
    }

    private fun startStartupFlow() {
        viewModelScope.launch {
            val startTime = System.currentTimeMillis()

            // Trigger loading indicator after 300ms if not ready yet
            val spinnerJob = launch {
                delay(300)
                if (!_isReady.value) {
                    _showLoadingSpinner.value = true
                }
            }

            safeLog("Startup initiated at $startTime. Starting parallel async reads...")

            var knownConfig = configRepository.cachedConfig()
            var onboardingDone = settingsDataStore.cachedOnboardingDone() ?: false
            var hasToken = !tokenStore.cachedAccessToken().isNullOrBlank()

            // Keep each completed result outside the deadline scope. Cancelling
            // a slow sibling must not discard a known forced-update policy.
            withTimeoutOrNull(maxDurationMs) {
                val configDeferred = async {
                    val t0 = System.currentTimeMillis()
                    try {
                        configRepository.getConfig().getOrNull()?.let { knownConfig = it }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The repository's non-blocking cache remains available.
                    }
                    val t1 = System.currentTimeMillis()
                    safeLog("Config fetch completed in ${t1 - t0}ms")
                }

                val prefsDeferred = async {
                    val t0 = System.currentTimeMillis()
                    try {
                        onboardingDone = settingsDataStore.isOnboardingDone()
                        hasToken = !tokenStore.getAccessToken().isNullOrBlank()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Route using only the values already read successfully.
                    }
                    val t1 = System.currentTimeMillis()
                    safeLog("Local prefs read completed in ${t1 - t0}ms, onboardingDone=$onboardingDone, hasToken=$hasToken")
                }

                configDeferred.await()
                prefsDeferred.await()

                val elapsedTime = System.currentTimeMillis() - startTime
                val remainingMinDelay = (minDurationMs - elapsedTime).coerceAtLeast(0)

                if (remainingMinDelay > 0) {
                    delay(remainingMinDelay)
                }
            }

            spinnerJob.cancel()
            _showLoadingSpinner.value = false
            val appConfig = configRepository.cachedConfig() ?: knownConfig
            _destination.value = when {
                appConfig != null && appConfig.minSupportedVersionCode > BuildConfig.VERSION_CODE ->
                    SplashDestination.UpdateRequired(appConfig.minSupportedVersionCode)
                !onboardingDone -> SplashDestination.Onboarding
                !hasToken -> SplashDestination.Auth
                else -> SplashDestination.Main
            }
            _isReady.value = true
        }
    }

    private fun safeLog(msg: String) {
        try {
            Log.d("MomentBoxSplash", msg)
        } catch (ignored: Throwable) {
            println("[MomentBoxSplash] $msg")
        }
    }

    class Factory(
        private val configRepository: ConfigRepository,
        private val tokenStore: TokenStorage,
        private val settingsDataStore: SettingsStorage
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SplashViewModel(configRepository, tokenStore, settingsDataStore) as T
        }
    }
}