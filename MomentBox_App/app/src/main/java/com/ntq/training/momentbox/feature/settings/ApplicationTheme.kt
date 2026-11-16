package com.ntq.training.momentbox.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import com.ntq.training.momentbox.data.local.prefs.SettingsStorage
import com.ntq.training.momentbox.data.local.prefs.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

object ApplicationTheme {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    fun observe(settings: SettingsStorage) {
        observer?.cancel()
        observer = scope.launch {
            settings.themeModeFlow.distinctUntilChanged().collect { mode ->
                val night = when (mode) {
                    ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                }
                if (AppCompatDelegate.getDefaultNightMode() != night) AppCompatDelegate.setDefaultNightMode(
                    night
                )
            }
        }
    }
}
