package com.ntq.training.momentbox.feature.journal.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ntq.training.momentbox.data.local.db.JournalAttachmentEntity
import com.ntq.training.momentbox.data.local.db.JournalEntryEntity
import com.ntq.training.momentbox.data.local.db.JournalEntryWithDetails
import com.ntq.training.momentbox.data.local.prefs.JournalAttachmentDraft
import com.ntq.training.momentbox.data.local.prefs.JournalDraft
import com.ntq.training.momentbox.data.repository.JournalRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class EditorError { LOAD, IMPORT, DRAFT, SAVE }

sealed interface FlushDraftResult {
    data object Success : FlushDraftResult
    data class Failure(val error: Throwable) : FlushDraftResult
}

sealed interface DraftStatus {
    data object Unsaved : DraftStatus
    data object Saving : DraftStatus
    data class Saved(val savedAtEpochMs: Long) : DraftStatus
}

data class JournalEditorUiState(
    val entryId: Long? = null,
    val dateEpochMs: Long = System.currentTimeMillis(),
    val mood: Int = 1,
    val title: String = "",
    val content: String = "",
    val tags: List<String> = emptyList(),
    val attachments: List<JournalAttachmentEntity> = emptyList(),
    val wordCount: Int = 0,
    val isLengthWarning: Boolean = false,
    val draftStatus: DraftStatus = DraftStatus.Unsaved,
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadFailed: Boolean = false,
    val pendingImports: Int = 0,
    val importFailed: Boolean = false,
    val error: EditorError? = null,
    val showRestoreDraftDialog: Boolean = false,
    val restoredDraft: JournalDraft? = null
) {
    val canSave: Boolean
        get() = (title.trim().isNotEmpty() || content.trim().isNotEmpty()) &&
            mood in 1..5 &&
            !isSaving &&
            !isLoading &&
            !isLoadFailed &&
            pendingImports == 0
}

class JournalEditorViewModel(
    private val journalRepository: JournalRepository,
    private val initialEntryId: Long = -1L,
    initialDateEpochMs: Long = System.currentTimeMillis(),
    initialMood: Int = 1,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        JournalEditorUiState(
            entryId = initialEntryId.takeIf { it > 0L },
            dateEpochMs = initialDateEpochMs,
            mood = initialMood.takeIf { it in 1..5 } ?: 1,
            isLoading = true
        )
    )
    val uiState = _uiState.asStateFlow()

    private val _saveEvents = MutableSharedFlow<Result<Long>>(replay = 1)
    val saveEvents = _saveEvents.asSharedFlow()

    private val _flushDraftEvents = MutableSharedFlow<FlushDraftResult>(replay = 1)
    val flushDraftEvents = _flushDraftEvents.asSharedFlow()

    val suggestedTagsFlow = journalRepository.observeDistinctTags()

    // Mọi lệnh đóng/lưu và autosave dùng chung khóa. Không cho job DataStore cũ ghi sau Clear.
    private val draftMutex = Mutex()
    private var autosaveJob: Job? = null
    private val imports = mutableSetOf<Job>()
    private var pendingPhotos = 0
    private var pendingAudio = false
    private var nextAttachmentOrder = 0
    private var revision = 0L
    private var generation = 0L
    private var hasTerminated = false
    private var hasPromptedDraft = false
    private var originalEntry: JournalEntryWithDetails? = null
    private var pendingCommitId: Long? = null
    private val stagedFilesToKeep = mutableSetOf<String>()
    private val oldFilesToDeleteAfterCommit = mutableSetOf<String>()

    init {
        loadInitialState()
    }

    fun retryLoad() {
        if (_uiState.value.isLoadFailed) loadInitialState()
    }

    private fun loadInitialState() {
        _uiState.update { it.copy(isLoading = true, isLoadFailed = false) }
        viewModelScope.launch {
            var draftToPrompt: JournalDraft? = null
            try {
                if (initialEntryId > 0) {
                    originalEntry = journalRepository.getEntryById(initialEntryId)
                    originalEntry?.let { details ->
                        _uiState.update {
                            it.copy(
                                dateEpochMs = details.entry.createdAt,
                                mood = details.entry.mood,
                                title = details.entry.title,
                                content = details.entry.content,
                                tags = details.tags.map { tag -> tag.tag },
                                attachments = details.attachments,
                                wordCount = calculateWordCount(details.entry.content),
                                isLengthWarning = details.entry.content.length > 5000
                            )
                        }
                    }
                }
                nextAttachmentOrder =
                    (_uiState.value.attachments.maxOfOrNull { it.sortOrder } ?: -1) + 1
                if (!hasPromptedDraft) {
                    val draft = journalRepository.getDraft(initialEntryId.takeIf { it > 0 })
                    if (draft != null) {
                        val committed =
                            draft.pendingCommitId?.let { journalRepository.getEntryById(it) }
                        if (committed != null && matchesCommittedDraft(draft, committed)) {
                            // Room đã commit nhưng lần Clear trước lỗi I/O: không mời khôi phục tạo bài trùng.
                            runCatching {
                                journalRepository.clearDraft(draft.entryId)
                                journalRepository.deleteUnreferencedFiles(draft.cleanupPaths.orEmpty())
                            }.onFailure { if (it is CancellationException) throw it }
                        } else if (
                            draft.title.isNotBlank() ||
                            draft.content.isNotBlank() ||
                            draft.attachments.isNotEmpty() ||
                            draft.tags.isNotEmpty()
                        ) {
                            hasPromptedDraft = true
                            draftToPrompt = draft
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(error = EditorError.LOAD, isLoadFailed = true) }
            } finally {
                _uiState.update {
                    if (draftToPrompt != null) {
                        it.copy(
                            isLoading = false,
                            showRestoreDraftDialog = true,
                            restoredDraft = draftToPrompt
                        )
                    } else {
                        it.copy(isLoading = false)
                    }
                }
            }
        }
    }

    private fun matchesCommittedDraft(d: JournalDraft, row: JournalEntryWithDetails): Boolean =
        row.entry.title == d.title.trim().take(100) &&
            row.entry.content == d.content.trim() &&
            row.entry.mood == d.mood &&
            row.entry.createdAt == d.dateEpochMs &&
            row.tags.map { it.tag }.toSet() == d.tags.toSet() &&
            row.attachments.map { it.localPath } == d.attachments.map { it.localPath }

    fun restoreDraft() {
        var draft: JournalDraft? = null
        _uiState.update { state ->
            val pending = state.restoredDraft
            if (pending == null || !state.showRestoreDraftDialog) {
                draft = null
                state
            } else {
                draft = pending
                state.copy(
                    showRestoreDraftDialog = false,
                    restoredDraft = null,
                    isLoading = pending.entryId != null
                )
            }
        }
        val resolvedDraft = draft ?: return

        fun bindDraft() {
            pendingCommitId = resolvedDraft.pendingCommitId
            val attachments = resolvedDraft.attachments.mapIndexed { i, a ->
                JournalAttachmentEntity(
                    entryId = resolvedDraft.entryId ?: 0,
                    type = a.type,
                    localPath = a.localPath,
                    durationMs = a.durationMs,
                    waveform = a.waveform,
                    sortOrder = i
                )
            }
            val originalPaths = originalEntry?.attachments.orEmpty().map { it.localPath }.toSet()
            stagedFilesToKeep += attachments.map { it.localPath }.filterNot { it in originalPaths }
            oldFilesToDeleteAfterCommit += resolvedDraft.cleanupPaths.orEmpty()
            nextAttachmentOrder = attachments.size
            _uiState.update {
                it.copy(
                    entryId = resolvedDraft.entryId,
                    dateEpochMs = resolvedDraft.dateEpochMs,
                    mood = resolvedDraft.mood,
                    title = resolvedDraft.title,
                    content = resolvedDraft.content,
                    tags = resolvedDraft.tags,
                    attachments = attachments,
                    wordCount = calculateWordCount(resolvedDraft.content),
                    isLengthWarning = resolvedDraft.content.length > 5000,
                    draftStatus = DraftStatus.Saved(resolvedDraft.savedAtEpochMs),
                    isDirty = true,
                    isLoading = false,
                    showRestoreDraftDialog = false,
                    restoredDraft = null
                )
            }
        }

        if (resolvedDraft.entryId == null) {
            bindDraft()
        } else {
            viewModelScope.launch {
                try {
                    // Khôi phục từ nút Viết mới vẫn phải đọc metadata của bài gốc (đặc biệt cờ ghim).
                    originalEntry = journalRepository.getEntryById(resolvedDraft.entryId)
                    bindDraft()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            showRestoreDraftDialog = false,
                            restoredDraft = null,
                            error = EditorError.LOAD
                        )
                    }
                }
            }
        }
    }

    fun dismissRestoreDraftDialog() {
        var draft: JournalDraft? = null
        _uiState.update { state ->
            val pending = state.restoredDraft
            if (pending == null || !state.showRestoreDraftDialog) {
                draft = null
                state
            } else {
                draft = pending
                state.copy(showRestoreDraftDialog = false, restoredDraft = null)
            }
        }
        val discardedDraft = draft ?: return
        viewModelScope.launch {
            try {
                draftMutex.withLock {
                    journalRepository.clearDraft(discardedDraft.entryId)
                }
                val referencedPaths = buildSet {
                    originalEntry?.attachments.orEmpty().forEach { add(it.localPath) }
                    _uiState.value.attachments.forEach { add(it.localPath) }
                    val draftEntryId = discardedDraft.entryId
                    if (draftEntryId != null && originalEntry?.entry?.id != draftEntryId) {
                        journalRepository.getEntryById(draftEntryId)
                            ?.attachments
                            .orEmpty()
                            .forEach { add(it.localPath) }
                    }
                }
                val orphanPaths = (
                    discardedDraft.attachments.map { it.localPath } +
                        discardedDraft.cleanupPaths.orEmpty()
                    ).distinct().filter { it.isNotBlank() && it !in referencedPaths }
                if (orphanPaths.isNotEmpty()) {
                    withContext(ioDispatcher) {
                        journalRepository.deleteUnreferencedFiles(orphanPaths)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(error = EditorError.DRAFT) }
            }
        }
    }

    private fun editable() =
        !hasTerminated &&
            !_uiState.value.isLoading &&
            !_uiState.value.isLoadFailed &&
            !_uiState.value.isSaving

    fun onTitleChanged(value: String) {
        if (!editable() || _uiState.value.title == value) return
        _uiState.update { it.copy(title = value.take(100), isDirty = true) }
        triggerAutosave()
    }

    fun onContentChanged(value: String) {
        if (!editable() || _uiState.value.content == value) return
        _uiState.update {
            it.copy(
                content = value,
                wordCount = calculateWordCount(value),
                isLengthWarning = value.length > 5000,
                isDirty = true
            )
        }
        triggerAutosave()
    }

    fun onMoodChanged(value: Int) {
        if (!editable() || value !in 1..5 || value == _uiState.value.mood) return
        _uiState.update { it.copy(mood = value, isDirty = true) }
        triggerAutosave()
    }

    fun onDateChanged(value: Long) {
        if (!editable()) return
        _uiState.update {
            it.copy(
                dateEpochMs = value.coerceAtMost(System.currentTimeMillis()),
                isDirty = true
            )
        }
        triggerAutosave()
    }

    fun addPhotoAttachment(sourceUriOrPath: String) {
        if (!editable() || _uiState.value.attachments.count { it.type == "image" } + pendingPhotos >= 10) {
            return
        }
        pendingPhotos++
        importAttachment(sourceUriOrPath, false, 0, emptyList())
    }

    fun addAudioAttachment(sourceUriOrPath: String, durationMs: Long, waveform: List<Int>) {
        if (!editable() || pendingAudio) return
        pendingAudio = true
        importAttachment(sourceUriOrPath, true, durationMs, waveform)
    }

    private fun importAttachment(
        source: String,
        audio: Boolean,
        duration: Long,
        waveform: List<Int>
    ) {
        val order = nextAttachmentOrder++ // Đặt thứ tự lúc chọn, không theo thời gian copy xong.
        val acceptedGeneration = generation
        _uiState.update {
            it.copy(
                pendingImports = it.pendingImports + 1,
                isDirty = true,
                importFailed = false
            )
        }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val path = journalRepository.stageAttachmentFile(source, audio).getOrThrow()
                if (acceptedGeneration != generation) {
                    journalRepository.deleteUnreferencedFiles(listOf(path))
                    return@launch
                }
                stagedFilesToKeep += path
                val metadata =
                    if (audio && duration <= 0) journalRepository.readAudioMetadata(path) else null
                val newAttachment = JournalAttachmentEntity(
                    entryId = _uiState.value.entryId ?: 0,
                    type = if (audio) "audio" else "image",
                    localPath = path,
                    durationMs = if (duration > 0) duration else metadata?.durationMs ?: 0,
                    waveform = waveform.ifEmpty { metadata?.waveform.orEmpty() },
                    sortOrder = order
                )
                if (audio) {
                    _uiState.value.attachments
                        .filter { it.type == "audio" }
                        .forEach { oldFilesToDeleteAfterCommit += it.localPath }
                }
                _uiState.update { state ->
                    val retained = if (audio) {
                        state.attachments.filterNot { it.type == "audio" }
                    } else {
                        state.attachments
                    }
                    state.copy(attachments = (retained + newAttachment).sortedBy { it.sortOrder })
                }
                triggerAutosave()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(error = EditorError.IMPORT, importFailed = true) }
            } finally {
                if (audio) pendingAudio = false else pendingPhotos--
                _uiState.update { it.copy(pendingImports = (it.pendingImports - 1).coerceAtLeast(0)) }
            }
        }
        imports += job
        job.invokeOnCompletion { imports.remove(job) }
        job.start()
    }

    fun removePhotoAttachment(attachment: JournalAttachmentEntity) {
        if (!editable()) return
        _uiState.update { it.copy(attachments = it.attachments - attachment, isDirty = true) }
        oldFilesToDeleteAfterCommit += attachment.localPath
        triggerAutosave()
    }

    fun removeAudioAttachment() {
        _uiState.value.attachments.firstOrNull { it.type == "audio" }?.let(::removePhotoAttachment)
    }

    fun addTag(rawTag: String) {
        val clean = rawTag.trim().removePrefix("#").trim()
        if (!editable() || clean.isBlank() || clean in _uiState.value.tags) return
        _uiState.update { it.copy(tags = it.tags + clean, isDirty = true) }
        triggerAutosave()
    }

    fun removeTag(tag: String) {
        if (!editable()) return
        _uiState.update { it.copy(tags = it.tags - tag, isDirty = true) }
        triggerAutosave()
    }

    private fun snapshotDraft(commitId: Long? = pendingCommitId): JournalDraft {
        val s = _uiState.value
        return JournalDraft(
            entryId = s.entryId,
            dateEpochMs = s.dateEpochMs,
            mood = s.mood,
            title = s.title,
            content = s.content,
            tags = s.tags,
            attachments = s.attachments.map {
                JournalAttachmentDraft(
                    type = it.type,
                    localPath = it.localPath,
                    durationMs = it.durationMs,
                    waveform = it.waveform,
                    sortOrder = it.sortOrder
                )
            },
            savedAtEpochMs = System.currentTimeMillis(),
            revision = revision,
            pendingCommitId = commitId,
            cleanupPaths = oldFilesToDeleteAfterCommit.toList()
        )
    }

    private fun triggerAutosave() {
        if (hasTerminated) return
        _uiState.update { it.copy(draftStatus = DraftStatus.Saving) }
        autosaveJob?.cancel()
        val current = ++revision
        autosaveJob = viewModelScope.launch {
            delay(2000)
            draftMutex.withLock {
                if (current != revision || hasTerminated) return@withLock
                val draft = snapshotDraft()
                try {
                    journalRepository.saveDraft(draft)
                    if (current == revision) {
                        _uiState.update {
                            it.copy(draftStatus = DraftStatus.Saved(draft.savedAtEpochMs))
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _uiState.update {
                        it.copy(
                            draftStatus = DraftStatus.Unsaved,
                            error = EditorError.DRAFT
                        )
                    }
                }
            }
        }
    }

    fun saveEntry() {
        if (!editable() || !_uiState.value.canSave) return
        hasTerminated = true
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                autosaveJob?.cancelAndJoin()
                draftMutex.withLock {
                    val s = _uiState.value
                    // Ghi ý định commit trước Room. ID cố định cho phép retry an toàn nếu Clear lỗi hoặc process chết.
                    val id = s.entryId ?: pendingCommitId
                        ?: (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1)
                    pendingCommitId = id
                    journalRepository.saveDraft(snapshotDraft(id))
                    val entry = JournalEntryEntity(
                        id = id,
                        title = s.title.trim().take(100),
                        content = s.content.trim(),
                        mood = s.mood,
                        createdAt = s.dateEpochMs,
                        updatedAt = System.currentTimeMillis(),
                        isPinned = originalEntry?.entry?.isPinned ?: false
                    )
                    val result = journalRepository.saveEntry(
                        entry = entry,
                        attachments = s.attachments,
                        tags = s.tags,
                        stagedFilesToKeep = stagedFilesToKeep.toList(),
                        oldFilesToDeleteAfterCommit = oldFilesToDeleteAfterCommit.toList()
                    )
                    if (result.isSuccess) {
                        _uiState.update { it.copy(entryId = result.getOrThrow(), isDirty = false) }
                        try {
                            journalRepository.clearDraft(s.entryId)
                            journalRepository.deleteUnreferencedFiles(oldFilesToDeleteAfterCommit.toList())
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            // Bản nháp có pendingCommitId; lần mở sau đối chiếu Room thay vì tạo bản sao.
                            _uiState.update { it.copy(error = EditorError.DRAFT) }
                        }
                        _saveEvents.emit(result)
                    } else {
                        throw result.exceptionOrNull()!!
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                hasTerminated = false
                _uiState.update { it.copy(isSaving = false, error = EditorError.SAVE) }
                _saveEvents.emit(Result.failure(e))
            }
        }
    }

    fun flushDraftAndClose() {
        if (!editable()) return
        hasTerminated = true
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                autosaveJob?.cancelAndJoin()
                imports.toList().joinAll()
                if (_uiState.value.importFailed) error("Attachment import failed")
                draftMutex.withLock { journalRepository.saveDraft(snapshotDraft()) }
                _flushDraftEvents.emit(FlushDraftResult.Success)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                hasTerminated = false
                _uiState.update { it.copy(isSaving = false, error = EditorError.DRAFT) }
                _flushDraftEvents.emit(FlushDraftResult.Failure(e))
            }
        }
    }

    fun discardDraft() {
        if (!editable()) return // Save đang commit thì không được chạy Discard song song.
        hasTerminated = true
        generation++
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                autosaveJob?.cancelAndJoin()
                imports.toList().forEach { it.cancelAndJoin() }
                draftMutex.withLock { journalRepository.clearDraft(_uiState.value.entryId) }
                journalRepository.deleteUnreferencedFiles(stagedFilesToKeep.toList())
                _flushDraftEvents.emit(FlushDraftResult.Success)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                hasTerminated = false
                _uiState.update { it.copy(isSaving = false, error = EditorError.DRAFT) }
                _flushDraftEvents.emit(FlushDraftResult.Failure(e))
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun consumeSaveResult() {
        _saveEvents.resetReplayCache()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun consumeFlushResult() {
        _flushDraftEvents.resetReplayCache()
    }

    fun consumeError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun calculateWordCount(text: String) =
        text.trim().split("\\s+".toRegex()).count { it.isNotBlank() }

    companion object {
        fun provideFactory(
            journalRepository: JournalRepository,
            entryId: Long = -1L,
            dateEpochMs: Long = System.currentTimeMillis(),
            mood: Int = 1
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return JournalEditorViewModel(journalRepository, entryId, dateEpochMs, mood) as T
            }
        }
    }
}
