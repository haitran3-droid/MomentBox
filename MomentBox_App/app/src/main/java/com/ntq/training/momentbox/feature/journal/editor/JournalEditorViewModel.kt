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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val showRestoreDraftDialog: Boolean = false,
    val restoredDraft: JournalDraft? = null
) {
    val canSave: Boolean
        get() = (title.trim().isNotEmpty() || content.trim().isNotEmpty()) && mood in 1..5 && !isSaving
}


class JournalEditorViewModel(
    private val journalRepository: JournalRepository,
    initialEntryId: Long = -1L,
    initialDateEpochMs: Long = System.currentTimeMillis(),
    initialMood: Int = 1
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        JournalEditorUiState(
            entryId = if (initialEntryId > 0L) initialEntryId else null,
            dateEpochMs = initialDateEpochMs,
            mood = if (initialMood in 1..5) initialMood else 1
        )
    )
    val uiState: StateFlow<JournalEditorUiState> = _uiState.asStateFlow()

    private val _saveEvents = MutableSharedFlow<Result<Long>>()
    val saveEvents: SharedFlow<Result<Long>> = _saveEvents.asSharedFlow()

    private val stagedFilesToKeep = mutableListOf<String>()
    private val oldFilesToDeleteAfterCommit = mutableListOf<String>()

    private var autosaveJob: Job? = null
    private var revision: Long = 0L
    private var hasTerminated: Boolean = false

    val suggestedTagsFlow = journalRepository.observeDistinctTags()

    init {
        if (initialEntryId > 0L) {
            loadExistingEntry(initialEntryId)
        } else {
            checkForExistingDraft()
        }
    }

    private fun loadExistingEntry(id: Long) {
        viewModelScope.launch {
            val entryDetails = journalRepository.getEntryById(id)
            if (entryDetails != null) {
                _uiState.update { current ->
                    current.copy(
                        entryId = entryDetails.entry.id,
                        dateEpochMs = entryDetails.entry.createdAt,
                        mood = entryDetails.entry.mood,
                        title = entryDetails.entry.title,
                        content = entryDetails.entry.content,
                        tags = entryDetails.tags.map { it.tag },
                        attachments = entryDetails.attachments,
                        wordCount = calculateWordCount(entryDetails.entry.content),
                        isLengthWarning = entryDetails.entry.content.length > 5000,
                        isDirty = false
                    )
                }
            }
        }
    }

    private fun checkForExistingDraft() {
        viewModelScope.launch {
            val draft = journalRepository.getDraft()
            if (draft != null && (draft.title.isNotBlank() || draft.content.isNotBlank() || draft.tags.isNotEmpty() || draft.attachments.isNotEmpty())) {
                _uiState.update { it.copy(showRestoreDraftDialog = true, restoredDraft = draft) }
            }
        }
    }

    fun restoreDraft() {
        val draft = _uiState.value.restoredDraft ?: return
        val restoredAttachments = draft.attachments.mapIndexed { index, att ->
            JournalAttachmentEntity(
                entryId = draft.entryId ?: 0L,
                type = att.type,
                localPath = att.localPath,
                durationMs = att.durationMs,
                waveform = att.waveform,
                sortOrder = att.sortOrder.takeIf { it != 0 } ?: index
            )
        }

        _uiState.update { current ->
            current.copy(
                entryId = draft.entryId,
                dateEpochMs = draft.dateEpochMs,
                mood = draft.mood,
                title = draft.title,
                content = draft.content,
                tags = draft.tags,
                attachments = restoredAttachments,
                wordCount = calculateWordCount(draft.content),
                isLengthWarning = draft.content.length > 5000,
                draftStatus = DraftStatus.Saved(draft.savedAtEpochMs),
                isDirty = true,
                showRestoreDraftDialog = false,
                restoredDraft = null
            )
        }
    }

    fun dismissRestoreDraftDialog() {
        _uiState.update { it.copy(showRestoreDraftDialog = false, restoredDraft = null) }
        viewModelScope.launch {
            journalRepository.clearDraft()
        }
    }

    fun onTitleChanged(newTitle: String) {
        if (_uiState.value.title == newTitle) return
        _uiState.update { it.copy(title = newTitle, isDirty = true) }
        triggerAutosave()
    }

    fun onContentChanged(newContent: String) {
        if (_uiState.value.content == newContent) return
        val count = calculateWordCount(newContent)
        val warning = newContent.length > 5000
        _uiState.update { it.copy(content = newContent, wordCount = count, isLengthWarning = warning, isDirty = true) }
        triggerAutosave()
    }

    fun onMoodChanged(newMood: Int) {
        if (newMood !in 1..5 || _uiState.value.mood == newMood) return
        _uiState.update { it.copy(mood = newMood, isDirty = true) }
        triggerAutosave()
    }

    fun onDateChanged(newDateEpochMs: Long) {
        val now = System.currentTimeMillis()
        // Không cho phép chọn ngày trong tương lai theo BA
        val validDate = if (newDateEpochMs > now) now else newDateEpochMs
        _uiState.update { it.copy(dateEpochMs = validDate, isDirty = true) }
        triggerAutosave()
    }

    fun addPhotoAttachment(sourceUriOrPath: String) {
        if (_uiState.value.attachments.count { it.type.equals("image", ignoreCase = true) } >= 10) {
            return
        }

        viewModelScope.launch {
            val stageResult = journalRepository.stageAttachmentFile(sourceUriOrPath, isAudio = false)
            if (stageResult.isSuccess) {
                val localPath = stageResult.getOrThrow()
                stagedFilesToKeep.add(localPath)

                val newAttachment = JournalAttachmentEntity(
                    entryId = _uiState.value.entryId ?: 0L,
                    type = "image",
                    localPath = localPath,
                    sortOrder = _uiState.value.attachments.size
                )
                _uiState.update { it.copy(attachments = it.attachments + newAttachment, isDirty = true) }
                triggerAutosave()
            }
        }
    }

    fun removePhotoAttachment(attachment: JournalAttachmentEntity) {
        _uiState.update { current ->
            current.copy(
                attachments = current.attachments.filterNot { it == attachment },
                isDirty = true
            )
        }
        oldFilesToDeleteAfterCommit.add(attachment.localPath)
        triggerAutosave()
    }

    fun addAudioAttachment(sourceUriOrPath: String, durationMs: Long, waveform: List<Int>) {
        viewModelScope.launch {
            val stageResult = journalRepository.stageAttachmentFile(sourceUriOrPath, isAudio = true)
            if (stageResult.isSuccess) {
                val localPath = stageResult.getOrThrow()
                stagedFilesToKeep.add(localPath)

                val newAttachment = JournalAttachmentEntity(
                    entryId = _uiState.value.entryId ?: 0L,
                    type = "audio",
                    localPath = localPath,
                    durationMs = durationMs,
                    waveform = waveform,
                    sortOrder = _uiState.value.attachments.size
                )
                _uiState.update { it.copy(attachments = it.attachments + newAttachment, isDirty = true) }
                triggerAutosave()
            }
        }
    }

    fun removeAudioAttachment() {
        val currentAudio = _uiState.value.attachments.find { it.type.equals("audio", ignoreCase = true) }
        if (currentAudio != null) {
            _uiState.update { it.copy(attachments = it.attachments.filterNot { att -> att == currentAudio }, isDirty = true) }
            oldFilesToDeleteAfterCommit.add(currentAudio.localPath)
            triggerAutosave()
        }
    }

    fun addTag(rawTag: String) {
        val cleanTag = rawTag.trim().removePrefix("#").trim()
        if (cleanTag.isBlank() || _uiState.value.tags.contains(cleanTag)) return
        _uiState.update { it.copy(tags = it.tags + cleanTag, isDirty = true) }
        triggerAutosave()
    }

    fun removeTag(tag: String) {
        _uiState.update { it.copy(tags = it.tags.filterNot { t -> t == tag }, isDirty = true) }
        triggerAutosave()
    }

    private fun triggerAutosave() {
        if (hasTerminated) return
        _uiState.update { it.copy(draftStatus = DraftStatus.Saving) }
        autosaveJob?.cancel()
        val currentRev = ++revision

        autosaveJob = viewModelScope.launch {
            delay(2000L) // Debounce 2 giây theo đúng BA
            if (currentRev != revision || hasTerminated) return@launch

            val state = _uiState.value
            val draft = JournalDraft(
                entryId = state.entryId,
                dateEpochMs = state.dateEpochMs,
                mood = state.mood,
                title = state.title,
                content = state.content,
                tags = state.tags,
                attachments = state.attachments.map {
                    JournalAttachmentDraft(
                        type = it.type,
                        localPath = it.localPath,
                        durationMs = it.durationMs,
                        waveform = it.waveform,
                        sortOrder = it.sortOrder
                    )
                },
                savedAtEpochMs = System.currentTimeMillis(),
                revision = currentRev
            )
            journalRepository.saveDraft(draft)
            _uiState.update { it.copy(draftStatus = DraftStatus.Saved(draft.savedAtEpochMs)) }
        }
    }

    fun saveEntry() {
        val state = _uiState.value
        if (!state.canSave || state.isSaving) return

        hasTerminated = true
        autosaveJob?.cancel()
        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            val entryEntity = JournalEntryEntity(
                id = state.entryId ?: 0L,
                title = state.title.trim().take(100),
                content = state.content.trim(),
                mood = state.mood,
                createdAt = state.dateEpochMs,
                updatedAt = System.currentTimeMillis()
            )

            val result = journalRepository.saveEntry(
                entry = entryEntity,
                attachments = state.attachments,
                tags = state.tags,
                stagedFilesToKeep = stagedFilesToKeep,
                oldFilesToDeleteAfterCommit = oldFilesToDeleteAfterCommit
            )

            if (result.isSuccess) {
                // Xóa bản nháp sau khi lưu thành công
                journalRepository.clearDraft()
                _saveEvents.emit(result)
            } else {
                hasTerminated = false
                _uiState.update { it.copy(isSaving = false) }
                _saveEvents.emit(result)
            }
        }
    }

    fun discardDraft() {
        hasTerminated = true
        autosaveJob?.cancel()
        viewModelScope.launch {
            journalRepository.clearDraft()
        }
    }

    private fun calculateWordCount(text: String): Int {
        if (text.isBlank()) return 0
        return text.trim().split("\\s+".toRegex()).count { it.isNotBlank() }
    }

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
