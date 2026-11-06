package com.ntq.training.momentbox.data.local.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class JournalAttachmentDraft(
    val type: String,
    val localPath: String,
    val durationMs: Long,
    val waveform: List<Int> = emptyList(),
    val sortOrder: Int = 0
)

data class JournalDraft(
    val entryId: Long? = null,
    val dateEpochMs: Long = System.currentTimeMillis(),
    val mood: Int = 1,
    val title: String = "",
    val content: String = "",
    val tags: List<String> = emptyList(),
    val attachments: List<JournalAttachmentDraft> = emptyList(),
    val savedAtEpochMs: Long = System.currentTimeMillis(),
    val revision: Long = 0L
)

data class JournalFilterState(
    val sort: String = SORT_NEWEST,
    val groupBy: String = GROUP_WEEK,
    val selectedMoods: List<Int> = emptyList(),
    val hasPhoto: Boolean = false,
    val hasAudio: Boolean = false,
    val fromDateEpochMs: Long? = null,
    val toDateEpochMs: Long? = null
) {
    val sortBy: String get() = sort
    val moods: Set<Int> get() = selectedMoods.toSet()
    val isNonDefault: Boolean get() = !isDefault()

    fun copyWith(
        sortBy: String = this.sort,
        groupBy: String = this.groupBy,
        moods: Set<Int> = this.moods,
        hasPhoto: Boolean = this.hasPhoto,
        hasAudio: Boolean = this.hasAudio,
        fromDateEpochMs: Long? = this.fromDateEpochMs,
        toDateEpochMs: Long? = this.toDateEpochMs
    ): JournalFilterState {
        return copy(
            sort = sortBy,
            groupBy = groupBy,
            selectedMoods = moods.toList().sorted(),
            hasPhoto = hasPhoto,
            hasAudio = hasAudio,
            fromDateEpochMs = fromDateEpochMs,
            toDateEpochMs = toDateEpochMs
        )
    }

    fun isDefault(): Boolean {
        return sort == SORT_NEWEST &&
                groupBy == GROUP_WEEK &&
                selectedMoods.isEmpty() &&
                !hasPhoto &&
                !hasAudio &&
                fromDateEpochMs == null &&
                toDateEpochMs == null
    }

    companion object {
        const val SORT_NEWEST = "NEWEST"
        const val SORT_OLDEST = "OLDEST"
        const val SORT_MOOD = "MOOD"

        const val GROUP_DAY = "DAY"
        const val GROUP_WEEK = "WEEK"
        const val GROUP_MONTH = "MONTH"

        val DEFAULT = JournalFilterState()
    }
}

class JournalDataStore(private val context: Context) {
    private val gson = Gson()

    companion object {
        val KEY_JOURNAL_DRAFT = stringPreferencesKey("journal_draft")
        val KEY_JOURNAL_FILTER = stringPreferencesKey("journal_filter")
    }

    val draftFlow: Flow<JournalDraft?> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_JOURNAL_DRAFT]
        if (json.isNullOrBlank()) null else {
            try {
                gson.fromJson(json, JournalDraft::class.java)
            } catch (_: Exception) {
                null
            }
        }
    }
    val filterFlow: Flow<JournalFilterState> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_JOURNAL_FILTER]
        if (json.isNullOrBlank()) JournalFilterState.DEFAULT else {
            try {
                gson.fromJson(json, JournalFilterState::class.java) ?: JournalFilterState.DEFAULT
            } catch (_: Exception) {
                JournalFilterState.DEFAULT
            }
        }
    }

    suspend fun saveDraft(draft: JournalDraft) {
        val json = gson.toJson(draft)
        context.dataStore.edit { prefs ->
            prefs[KEY_JOURNAL_DRAFT] = json
        }
    }

    suspend fun getDraft(): JournalDraft? {
        return draftFlow.first()
    }

    suspend fun clearDraft() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_JOURNAL_DRAFT)
        }
    }

    suspend fun saveFilter(filter: JournalFilterState) {
        val json = gson.toJson(filter)
        context.dataStore.edit { prefs ->
            prefs[KEY_JOURNAL_FILTER] = json
        }
    }

    suspend fun getFilter(): JournalFilterState {
        return filterFlow.first()
    }

    suspend fun resetFilter() {
        saveFilter(JournalFilterState.DEFAULT)
    }
}