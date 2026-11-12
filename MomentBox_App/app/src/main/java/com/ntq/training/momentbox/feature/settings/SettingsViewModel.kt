package com.ntq.training.momentbox.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.domain.model.User
import com.ntq.training.momentbox.MomentBoxApp
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsScreenState(
    val user: User? = null,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val autoplay: String = SettingsDataStore.AUTOPLAY_WIFI_ONLY,
    val cacheBytes: Long? = null,
    val busy: Boolean = false,
    val notice: SettingsNotice? = null
)

data class SettingsNotice(val id: Long, val kind: String, val uri: Uri? = null)

/** State không giữ Activity. I/O thuộc repository hoặc IO dispatcher; operation sống qua
 * recreate nhờ ViewModel. Chỉ ACK thành công mới đổi notice/value, lỗi không giả đã lưu. */
class SettingsViewModel(private val app: MomentBoxApp) : ViewModel() {
    private val mutable = MutableStateFlow(SettingsScreenState())
    val state = mutable.asStateFlow()
    private val cache = ImageCacheRepository(app)
    private val writer = JournalDownloadWriter(app)
    private var action: Job? = null
    private var scan: Job? = null
    private var eventId = 0L

    init {
        viewModelScope.launch {
            combine(
                app.authRepository.userFlow,
                app.settingsDataStore.themeModeFlow,
                app.settingsDataStore.autoplayVideoFlow
            ) { user, theme, autoplay ->
                Triple(
                    user,
                    theme,
                    autoplay
                )
            }.collect { (user, theme, autoplay) ->
                mutable.update { it.copy(user = user, theme = theme, autoplay = autoplay) }
            }
        }
        viewModelScope.launch { app.authRepository.getMe() }
    }

    fun refresh() {
        if (scan?.isActive == true) return
        scan = viewModelScope.launch {
            try {
                val bytes =
                    cache.diskBytes()
                    mutable.update { it.copy(cacheBytes = bytes) }
                    app.reminderScheduler.reconcile()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice("error")
            }
        }
    }

    private fun notice(kind: String, uri: Uri? = null) {
        mutable.update { it.copy(notice = SettingsNotice(++eventId, kind, uri)) }
    }

    fun acknowledge(id: Long) {
        mutable.update { if (it.notice?.id == id) it.copy(notice = null) else it }
    }

    private fun runAction(block: suspend () -> Unit) {
        if (action?.isActive == true) return
        action = viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice("error")
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun theme(index: Int) {
        ThemeMode.entries.getOrNull(index)
            ?.let { mode -> runAction { app.settingsDataStore.setThemeMode(mode) } }
    }

    fun autoplay(index: Int) {
        listOf(
            SettingsDataStore.AUTOPLAY_ALWAYS,
            SettingsDataStore.AUTOPLAY_WIFI_ONLY,
            SettingsDataStore.AUTOPLAY_NEVER
        ).getOrNull(index)
            ?.let { mode -> runAction { app.settingsDataStore.setAutoplayVideo(mode) } }
    }

    fun reminder(enabled: Boolean, minute: Int) =
        runAction { app.reminderScheduler.configure(enabled, minute) }

    fun clearCache() = runAction {
        scan?.cancelAndJoin()
        val bytes = cache.clear()
        mutable.update { it.copy(cacheBytes = bytes) }
        notice("cache")
    }

    fun exportName() = "momentbox_journal_" + LocalDate.now(app.journalClock)
        .format(DateTimeFormatter.BASIC_ISO_DATE) + ".json"

    fun export(destination: Uri? = null) = runAction {
        val document = withContext(Dispatchers.IO) {
            JournalExportCodec.create(
                app.journalRepository.getEntriesForStats(),
                app.journalClock.instant()
            )
        }
        val uri = writer.write(document, exportName(), destination)
        notice("export", uri)
    }
}
