package com.ntq.training.momentbox.data.repository

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.ntq.training.momentbox.MomentBoxApp
import com.ntq.training.momentbox.R
import com.ntq.training.momentbox.core.media.OwnedImageFiles
import com.ntq.training.momentbox.core.network.ProgressRequestBody
import com.ntq.training.momentbox.data.local.db.JournalAttachmentEntity
import com.ntq.training.momentbox.data.local.db.JournalEntryEntity
import com.ntq.training.momentbox.data.local.prefs.PendingJournalReceipt
import com.ntq.training.momentbox.data.local.prefs.SettingsDataStore
import com.ntq.training.momentbox.domain.model.Post
import com.ntq.training.momentbox.feature.compose.ComposeDraft
import com.ntq.training.momentbox.feature.compose.UploadProgress
import com.ntq.training.momentbox.feature.postdetail.PostDetailActivity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

enum class UploadPhase {
    IDLE,
    PREPARING,
    UPLOADING,
    AWAITING_SERVER,
    SAVING_JOURNAL,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    JOURNAL_FAILED,
}

data class UploadState(
    val phase: UploadPhase = UploadPhase.IDLE,
    val bytes: Long = 0,
    val total: Long = 0,
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    val post: Post? = null,
    val uncertain: Boolean = false,
)

/**
 * Application scope giữ job qua Activity. Không hứa tiếp tục qua OS kill hoặc remote exactly-once.
 */
class UploadRepository(private val app: MomentBoxApp) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(UploadState())

    val state = mutable.asStateFlow()
    @Volatile
    private var job: Job? = null
    private var snapshot: ComposeDraft? = null

    private var generation = 0L

    private var source = ""
    private var lastNotice = 0L
    private var uploadId = ""
    private var journalEntryId = 0L
    private var receipt: PendingJournalReceipt? = null
    private var loadingReceipt: Job? = null
    private var recoveryEpoch = 0L
    private val subscription =
        app.tokenStore.addSnapshotListener { token ->
            if (busy && token.generation != generation) cancel()
        }
    val postingBlocked
        get() = busy || state.value.phase == UploadPhase.JOURNAL_FAILED

    val busy
        get() = job?.isActive == true

    init {
        if (Build.VERSION.SDK_INT >= 26)
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(
                        "momentbox.upload",
                        app.getString(R.string.m4_upload),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
        scope.launch {
            app.settingsDataStore.dataSourceFlow.drop(1).collect {
                if (busy && it != source) cancel()
            }
        }
        loadingReceipt = scope.launch { restoreReceipt() }
    }

    fun beginCompose() {
        if (!busy) {
            if (state.value.phase == UploadPhase.SUCCEEDED) mutable.value = UploadState()
            loadingReceipt = scope.launch { restoreReceipt() }
        }
    }

    private suspend fun restoreReceipt() {
        val epoch = ++recoveryEpoch
        try {
            val expectedGeneration = app.tokenStore.snapshot().generation
            val backend = app.settingsDataStore.getDataSource()
            val user = app.authRepository.getSavedUser()
            val saved = user?.let { app.postDraftStore.loadReceipt(backend, it.id) }
            // Đọc đĩa có suspend: một lượt khôi phục cũ không được ghi đè chủ/backend mới.
            if (
                epoch != recoveryEpoch ||
                app.settingsDataStore.getDataSource() != backend ||
                app.authRepository.getSavedUser()?.id != user?.id
            )
                return
            app.tokenStore.runIfGenerationMatches(expectedGeneration) {
                if (epoch == recoveryEpoch) {
                    if (saved != null) {
                        receipt = saved
                        snapshot = saved.draft
                        journalEntryId = saved.entryId
                        source = backend
                        generation = expectedGeneration
                        mutable.value = UploadState(UploadPhase.JOURNAL_FAILED, post = saved.post)
                    } else if (state.value.phase == UploadPhase.JOURNAL_FAILED) {
                        // Chỉ bỏ trạng thái RAM của form. Receipt trên đĩa vẫn thuộc chủ cũ và còn
                        // phục hồi được.
                        receipt = null
                        snapshot = null
                        journalEntryId = 0L
                        mutable.value = UploadState()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (epoch == recoveryEpoch)
                mutable.value = UploadState(UploadPhase.FAILED, uncertain = true)
        }
    }

    private suspend fun boundaryMatches() =
        withContext(NonCancellable) {
            app.tokenStore.getSessionGeneration() == generation &&
                    app.settingsDataStore.getDataSource() == source
        }

    private suspend fun sameOwner(post: Post) =
        app.authRepository.getSavedUser()?.id == post.author.id &&
                app.settingsDataStore.getDataSource() == source

    override fun close() {
        subscription.close()
        scope.cancel()
    }

    fun start(draft: ComposeDraft) {
        if (postingBlocked) return
        val input = draft.snapshot()
        if (!input.canPost) return
        snapshot = input
        uploadId = UUID.randomUUID().toString()
        lastNotice = -1000L
        journalEntryId =
            (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1L)
        job =
            scope.launch {
                val temporary = mutableListOf<File>()

                var sentRequest = false

                var accepted: Post? = null
                try {
                    loadingReceipt?.join()
                    if (state.value.phase == UploadPhase.JOURNAL_FAILED) return@launch
                    generation = app.tokenStore.snapshot().generation
                    source = app.settingsDataStore.getDataSource()
                    check(
                        source ==
                                SettingsDataStore
                                    .SOURCE_MB_SERVER
                    ) {
                        "Posting requires MomentBox server"
                    }
                    mutable.value = UploadState(UploadPhase.PREPARING)
                    val prepared = mutableListOf<Pair<File, String>>()
                    for (media in input.media) {
                        currentCoroutineContext().ensureActive()
                        val original = File(Uri.parse(media.uri).path ?: "")
                        check(original.isFile && original.length() in 1..50L * 1024 * 1024)
                        if (media.isVideo) {
                            val duration =
                                withContext(Dispatchers.IO) {
                                    MediaMetadataRetriever().run {
                                        try {
                                            setDataSource(original.absolutePath)
                                            extractMetadata(
                                                MediaMetadataRetriever.METADATA_KEY_DURATION
                                            )
                                                ?.toLongOrNull() ?: 0
                                        } finally {
                                            release()
                                        }
                                    }
                                }
                            check(duration in 1..60_000)
                            prepared.add(original to media.mimeType)
                        } else {
                            val bitmap = OwnedImageFiles.decode(app, Uri.fromFile(original), 2048)
                            val compressed =
                                try {
                                    OwnedImageFiles.jpeg(app, bitmap, 85)
                                } finally {
                                    bitmap.recycle()
                                }
                            temporary.add(compressed)
                            prepared.add(compressed to "image/jpeg")
                        }
                    }
                    val voice = input.voice?.let { File(Uri.parse(it.uri).path ?: "") }
                    if (voice != null) check(voice.isFile && voice.length() in 1..50L * 1024 * 1024)
                    val all = prepared + listOfNotNull(voice?.let { it to "audio/mp4" })
                    val progress = UploadProgress(all.map { it.first.length() })

                    val uploadJob = currentCoroutineContext()[Job]!!
                    // OkHttp ghi body trên worker. Chỉ Main được công bố state/notification,
                    // callback muộn bị bỏ.
                    val updates = Channel<UploadState>(Channel.CONFLATED)
                    val progressJob = launch {
                        for (update in updates) if (
                            uploadJob.isActive &&
                            state.value.phase in
                            listOf(UploadPhase.UPLOADING, UploadPhase.AWAITING_SERVER)
                        ) {
                            mutable.value = update
                            notifyProgress()
                        }
                    }

                    fun part(i: Int, name: String): MultipartBody.Part {
                        val (file, mime) = all[i]
                        return MultipartBody.Part.createFormData(
                            name,
                            file.name,
                            ProgressRequestBody(
                                file,
                                mime.toMediaType(),
                                { !uploadJob.isActive },
                            ) { bytes ->
                                if (uploadJob.isActive) {
                                    progress.sent(i, bytes)
                                    updates.trySend(
                                        UploadState(
                                            if (progress.bytes == progress.total)
                                                UploadPhase.AWAITING_SERVER
                                            else UploadPhase.UPLOADING,
                                            progress.bytes,
                                            progress.total,
                                            i + 1,
                                            all.size,
                                        )
                                    )
                                }
                            },
                        )
                    }

                    val parts = prepared.indices.map { part(it, "media") }

                    val voicePart = voice?.let { part(all.lastIndex, "voice") }
                    val fields =
                        mapOf(
                            "caption" to input.caption,
                            "privacy" to input.privacy,
                            "allowComments" to input.allowComments.toString(),
                            "location" to input.location,
                            "voiceDurationMs" to (input.voice?.durationMs ?: 0).toString(),
                        )
                            .mapValues { it.value.toRequestBody("text/plain".toMediaType()) }
                    mutable.value =
                        UploadState(
                            if (all.isEmpty()) UploadPhase.AWAITING_SERVER
                            else UploadPhase.UPLOADING,
                            total = progress.total,
                            fileCount = all.size,
                        )
                    // Bài TEXT không có callback byte, nhưng vẫn phải có thông báo hủy khi chờ
                    // server.
                    notifyProgress()
                    sentRequest = true
                    val result =
                        try {
                            app.feedRepository.createPost(fields, parts, voicePart)
                        } finally {
                            updates.close()
                            progressJob.cancelAndJoin()
                        }
                    val post = result.getOrThrow()
                    accepted = post
                    // Sau ACK lưu biên nhận trước side effect Room, cancellation không được biến
                    // bài đã đăng thành một POST mới.
                    withContext(NonCancellable) {
                        if (input.saveToJournal) {
                            val record =
                                PendingJournalReceipt(
                                    input,
                                    post,
                                    journalEntryId,
                                    source,
                                )
                            app.postDraftStore.saveReceipt(record)
                            receipt = record
                        }
                        app.postDraftStore.clearMatching(input)
                    }
                    check(
                        app.tokenStore.getSessionGeneration() == generation &&
                                app.settingsDataStore.getDataSource() == source
                    )
                    mutable.value =
                        UploadState(
                            if (input.saveToJournal) UploadPhase.SAVING_JOURNAL
                            else UploadPhase.SUCCEEDED,
                            progress.total,
                            progress.total,
                            all.size,
                            all.size,
                            post,
                        )
                    if (input.saveToJournal) saveJournal(input, post)
                    withContext(NonCancellable) {
                        app.postDraftStore.clearMatching(input)
                        receipt?.let { app.postDraftStore.clearReceipt(it) }
                        receipt = null
                        cleanupOwnedInput(input)
                    }
                    mutable.value = state.value.copy(phase = UploadPhase.SUCCEEDED)
                    notifySuccess(post.id)
                } catch (e: CancellationException) {
                    mutable.value =
                        if (!boundaryMatches()) UploadState()
                        else if (accepted != null)
                            UploadState(
                                if (input.saveToJournal) UploadPhase.JOURNAL_FAILED
                                else UploadPhase.SUCCEEDED,
                                post = accepted,
                            )
                        else UploadState(UploadPhase.CANCELLED, uncertain = sentRequest)
                    throw e
                } catch (_: Exception) {
                    mutable.value =
                        if (!boundaryMatches()) UploadState()
                        else if (accepted != null)
                            UploadState(
                                if (input.saveToJournal) UploadPhase.JOURNAL_FAILED
                                else UploadPhase.SUCCEEDED,
                                post = accepted,
                            )
                        else UploadState(UploadPhase.FAILED, uncertain = sentRequest)
                } finally {
                    temporary.forEach { it.delete() }
                    if (mutable.value.phase != UploadPhase.SUCCEEDED)
                        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                            .cancel(15001)
                }
            }
    }

    suspend fun cancelForLogout() {
        recoveryEpoch++
        loadingReceipt?.cancelAndJoin()
        loadingReceipt = null
        job?.cancelAndJoin()
        job = null
        mutable.value = UploadState()
        snapshot = null
        withContext(Dispatchers.Main.immediate) {
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(
                15001
            )
        }
    }

    fun cancel(expectedId: String? = null) {
        if (expectedId == null || expectedId == uploadId) job?.cancel()
    }

    fun retryJournal() {
        val input = snapshot ?: return

        val post = state.value.post ?: return
        if (busy || state.value.phase != UploadPhase.JOURNAL_FAILED) return
        job =
            scope.launch {
                try {
                    check(sameOwner(post))
                    generation = app.tokenStore.snapshot().generation
                    mutable.value = state.value.copy(phase = UploadPhase.SAVING_JOURNAL)
                    saveJournal(input, post)
                    withContext(NonCancellable) {
                        app.postDraftStore.clearMatching(input)
                        receipt?.let { app.postDraftStore.clearReceipt(it) }
                        receipt = null
                        cleanupOwnedInput(input)
                    }
                    mutable.value = state.value.copy(phase = UploadPhase.SUCCEEDED)
                    notifySuccess(post.id)
                } catch (e: CancellationException) {
                    mutable.value =
                        if (boundaryMatches()) UploadState(UploadPhase.JOURNAL_FAILED, post = post)
                        else UploadState()
                    throw e
                } catch (_: Exception) {
                    mutable.value =
                        state.value.copy(phase = UploadPhase.JOURNAL_FAILED, post = post)
                }
            }
    }

    private suspend fun cleanupOwnedInput(input: ComposeDraft) {
        val live =
            runCatching { app.postDraftStore.load() }
                .getOrElse {
                    return
                }

        val retained =
            (live?.media.orEmpty().map { it.uri } + listOfNotNull(live?.voice?.uri)).toSet()
        withContext(Dispatchers.IO) {
            val folder = File(app.filesDir, "post-drafts").canonicalFile
            (input.media.map { it.uri } + listOfNotNull(input.voice?.uri))
                .filterNot { it in retained }
                .forEach { path ->
                    runCatching {
                        val uri = Uri.parse(path)
                        if (uri.scheme == "file")
                            File(uri.path!!)
                                .canonicalFile
                                .takeIf { it.parentFile == folder }
                                ?.delete()
                    }
                }
        }
    }

    private suspend fun saveJournal(input: ComposeDraft, post: Post) {
        val staged = mutableListOf<String>()
        try {
            val attachments = mutableListOf<JournalAttachmentEntity>()
            input.media.forEachIndexed { i, media ->
                check(!media.isVideo)

                val path = app.journalRepository.stageAttachmentFile(media.uri, false).getOrThrow()
                staged.add(path)
                attachments.add(
                    JournalAttachmentEntity(
                        entryId = 0,
                        type = "image",
                        localPath = path,
                        sortOrder = i,
                    )
                )
            }
            input.voice?.let { audio ->
                val path = app.journalRepository.stageAttachmentFile(audio.uri, true).getOrThrow()
                staged.add(path)
                attachments.add(
                    JournalAttachmentEntity(
                        entryId = 0,
                        type = "audio",
                        localPath = path,
                        durationMs = audio.durationMs,
                        waveform = audio.waveform,
                        sortOrder = attachments.size,
                    )
                )
            }
            // ID dương giữ chỗ trong phiên upload: retry local cập nhật cùng entry, không POST lại.
            val entry =
                JournalEntryEntity(
                    id = journalEntryId,
                    title = input.caption.lineSequence().firstOrNull().orEmpty().take(100),
                    content = input.caption,
                    mood = 3,
                    wordCount = input.caption.split(Regex("""\s+""")).count { it.isNotBlank() },
                )
            val obsolete =
                app.journalRepository.getEntryById(journalEntryId)?.attachments.orEmpty().map {
                    it.localPath
                }
            app.journalRepository
                .saveEntry(entry, attachments, post.tags, staged, obsolete)
                .getOrThrow()
        } catch (e: Exception) {
            withContext(NonCancellable) { app.journalRepository.deleteUnreferencedFiles(staged) }
            mutable.value = state.value.copy(phase = UploadPhase.JOURNAL_FAILED, post = post)
            throw e
        }
    }

    @Synchronized
    private fun notifyProgress() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        )
            return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotice < 1000) return
        lastNotice = now
        val s = mutable.value
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            manager.createNotificationChannel(
                NotificationChannel(
                    "momentbox.upload",
                    app.getString(R.string.m4_upload),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        val cancel =
            PendingIntent.getBroadcast(
                app,
                15001,
                Intent(app, UploadCancelReceiver::class.java)
                    .setData(Uri.parse("momentbox://upload/$uploadId/cancel"))
                    .putExtra("uploadId", uploadId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        manager.notify(
            15001,
            NotificationCompat.Builder(app, "momentbox.upload")
                .setSmallIcon(R.drawable.ic_journal_cloud_done)
                .setContentTitle(app.getString(R.string.m4_upload))
                .setOnlyAlertOnce(true)
                .setProgress(
                    100,
                    if (s.total == 0L) 0 else (s.bytes * 100 / s.total).toInt(),
                    s.total == 0L,
                )
                .addAction(0, app.getString(R.string.cancel), cancel)
                .build(),
        )
    }

    private fun notifySuccess(id: Long) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        )
            return
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val content =
            PendingIntent.getActivity(
                app,
                id.hashCode(),
                Intent(
                    app,
                    PostDetailActivity::class.java,
                )
                    .setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse("momentbox://post/$id")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        manager.notify(
            15001,
            NotificationCompat.Builder(app, "momentbox.upload")
                .setSmallIcon(R.drawable.ic_journal_cloud_done)
                .setContentTitle(app.getString(R.string.m4_posted))
                .setContentIntent(content)
                .setAutoCancel(true)
                .build(),
        )
    }
}

/** Receiver nội bộ, PendingIntent immutable chỉ hủy job đang được repository sở hữu. */
class UploadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        (context.applicationContext as MomentBoxApp)
            .uploadRepository
            .cancel(intent?.getStringExtra("uploadId"))
    }
}
