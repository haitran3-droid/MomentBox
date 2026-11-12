package com.ntq.training.momentbox.feature.settings

import androidx.room.withTransaction
import android.app.NotificationManager
import android.content.Intent
import android.util.Log
import com.ntq.training.momentbox.core.media.AndroidPlaybackAudio
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.data.repository.ChatSession
import com.ntq.training.momentbox.feature.auth.AuthActivity
import com.ntq.training.momentbox.feature.recorder.RecordingService
import com.ntq.training.momentbox.feature.recorder.RecordPhase
import com.ntq.training.momentbox.feature.reminder.ReminderActivity
import com.ntq.training.momentbox.feature.reminder.ReminderRingingService
import com.ntq.training.momentbox.MomentBoxApp
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class LogoutPhase { IDLE, RUNNING, COMPLETE, ERROR }

/** Đây là thao tác cấp Application: rotation không hủy logout giữa token fence và cleanup.
 * Xác nhận xóa nhật ký nằm ở DialogFragment, không mặc định xóa. Không cancelAll notifications
 * hoặc xóa toàn filesDir: chat chỉ owner/backend cũ, reminder chỉ namespace của reminder. */
class LogoutCoordinator(private val app: MomentBoxApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(LogoutPhase.IDLE)
    val phase = mutable.asStateFlow()

    /** Chỉ mã bước, không có token, đường dẫn hay nội dung người dùng. Debug/test đọc để
     * biết cleanup nào thất bại; lỗi một bước không ngăn các bước hủy phiên khác chạy. */
    private val mutableFailures = MutableStateFlow<Set<String>>(emptySet())
    val failureSteps = mutableFailures.asStateFlow()
    private var operation: Job? = null
    fun start(keepJournal: Boolean, nextSource: String? = null): Job {
        require(
            nextSource == null || nextSource in setOf(
                SettingsDataStore.SOURCE_MB_SERVER,
                SettingsDataStore.SOURCE_DUMMY_JSON,
                SettingsDataStore.SOURCE_OFFLINE
            )
        )
        operation?.takeIf { it.isActive }?.let { return it }
        mutableFailures.value = emptySet()
        mutable.value = LogoutPhase.RUNNING
        return scope.launch {
            var failed = false
            var journalSealed = false
            var session: ChatSession? = null
            suspend fun attempt(step: String, block: suspend () -> Unit) {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failed = true
                    mutableFailures.update { it + step }
                    Log.w("MomentBoxLogout", "$step: ${error.javaClass.simpleName}")
                }
            }
            // Cleanup độc lập: lỗi đọc journal không được bỏ qua token/socket/player ở bước sau.
            attempt("CAPTURE_SCOPE") {
                val before = app.tokenStore.snapshot()
                val user = app.authRepository.getSavedUser()
                val backend = app.settingsDataStore.getDataSource()
                session = app.chatRuntime.current() ?: user?.let {
                    ChatSession(
                        it.id.toString(),
                        backend + "|" + app.socketManager.resolveSocketOrigin(),
                        before.generation
                    )
                }
            }
            attempt("JOURNAL_SEAL") {
                app.journalRepository.prepareForLogout()
                journalSealed = true
            }
            attempt("AUTH") { app.authRepository.logout().getOrThrow() }
            // Snapshot RAM cũng kiểm được khi server logout lỗi; local clear là điều kiện bắt buộc.
            if (app.tokenStore.snapshot().accessToken != null) attempt("AUTH_FALLBACK") {
                app.tokenStore.clear()
                app.settingsDataStore.setSavedUserJson(
                null
            )
            }
            attempt("SOCKET") { app.socketManager.disconnect() }
            attempt("CHAT_SENDS") { app.chatRuntime.cancelForLogout() }
            attempt("REPLIES") { app.notificationReplySender.cancelForLogout() }
            attempt("CHAT_MEDIA") { app.chatMediaRepository.cancelForLogout() }
            attempt("POST_UPLOAD") { app.uploadRepository.cancelForLogout() }
            attempt("LOCAL_AUDIO") {
                AndroidPlaybackAudio.get(
                    app
                ).interruptForRemotePlayer()
            }
            attempt("RECORDER") {
                val recording = RecordingService.state.value
                if (recording.phase in listOf(
                        RecordPhase.PREPARING,
                        RecordPhase.RECORDING,
                        RecordPhase.PAUSED,
                        RecordPhase.STOPPING
                    )
                ) {
                    app.startService(
                        RecordingService.intent(
                            app,
                            RecordingService.CANCEL,
                            recording.session
                        )
                    )
                    check(withTimeoutOrNull(5000) {
                        RecordingService.state.first {
                            it.phase !in listOf(
                                RecordPhase.PREPARING,
                                RecordPhase.RECORDING,
                                RecordPhase.PAUSED,
                                RecordPhase.STOPPING
                            )
                        }
                        true
                    } == true)
                }
            }
            attempt("PLAYER_IPC") { check(app.playerAudioBridge.closeForLogout()) }
            attempt("REMINDER") {
                app.reminderStore.snapshot().active?.let { app.reminderStore.finish(it) }
                app.stopService(Intent(app, ReminderRingingService::class.java))
                val notices = app.getSystemService(NotificationManager::class.java)
                notices.activeNotifications.filter {
                    it.id == ReminderActivity.NOTIFICATION_ID &&
                            (it.tag?.startsWith("m5-reminder:") == true || it.notification.extras.getString(
                                "m5_reminder_event"
                            )?.startsWith("m5-reminder:") == true)
                }.forEach { notices.cancel(it.tag, it.id) }
            }
            session?.let { old ->
                attempt("CHAT_CACHE") {
                    app.chatNotifications.cancel(old)
                    withContext(Dispatchers.IO) {
                        app.database.withTransaction {
                            val dao = app.database.chatDao()
                            dao.clearMessages(
                            old.ownerId,
                            old.source
                        )
                        dao.clearConversations(
                            old.ownerId,
                            old.source
                        )
                        dao.clearUsers(old.ownerId, old.source)
                        }
                        // Chỉ file trực tiếp trong đúng thư mục SHA256 của owner/backend cũ, không xóa đệ quy.
                        val key = MessageDigest.getInstance("SHA-256")
                            .digest((old.ownerId + "|" + old.source).toByteArray())
                            .joinToString("") { "%02x".format(it) }
                        val root = File(app.filesDir, "chat-media").canonicalFile
                        val folder = File(root, key).canonicalFile
                        check(folder.parentFile == root)
                        folder.listFiles()
                            ?.filter { it.canonicalFile.parentFile == folder && it.isFile }
                            ?.forEach { check(it.delete() || !it.exists()) }
                        if (folder.exists()) check(folder.delete())
                    }
                }
            }
            if (journalSealed) attempt("JOURNAL_FINISH") {
                app.journalRepository.finishLogout(
                    keepJournal
                )
            }
            // Đổi backend chỉ sau khi vô hiệu hóa và dọn session cũ; không mang token sang nguồn khác.
            nextSource?.let { source ->
                attempt("DATA_SOURCE") {
                    app.authRepository.changeDataSource(
                        source
                    )
                }
            }
            mutable.value = if (failed) LogoutPhase.ERROR else LogoutPhase.COMPLETE
            app.startActivity(
                Intent(
                    app,
                    AuthActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra("logoutCleanupFailed", failed)
            )
        }.also { operation = it }
    }
}
