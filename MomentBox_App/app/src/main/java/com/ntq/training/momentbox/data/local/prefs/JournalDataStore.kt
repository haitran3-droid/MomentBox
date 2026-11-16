package com.ntq.training.momentbox.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    val revision: Long = 0L,
    val pendingCommitId: Long? = null,
    val cleanupPaths: List<String>? = emptyList()
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

class JournalDataStore(
    private val context: Context,
    private val store: DataStore<Preferences> = context.dataStore
) {
    private val gson = Gson()
    private val draftMapType = object : TypeToken<Map<String, JournalDraft>>() {}.type

    companion object {
        private val KEY_LEGACY_JOURNAL_DRAFT = stringPreferencesKey("journal_draft")
        private val KEY_JOURNAL_DRAFTS = stringPreferencesKey("journal_drafts")
        val KEY_JOURNAL_FILTER = stringPreferencesKey("journal_filter")
    }

    private fun draftKey(entryId: Long?): String = entryId?.toString() ?: "new"

    private fun readDrafts(prefs: Preferences): Map<String, JournalDraft> {
        val drafts = runCatching {
            gson.fromJson<Map<String, JournalDraft>>(prefs[KEY_JOURNAL_DRAFTS], draftMapType)
        }.getOrNull().orEmpty()
        val legacy = runCatching {
            gson.fromJson(prefs[KEY_LEGACY_JOURNAL_DRAFT], JournalDraft::class.java)
        }.getOrNull() ?: return drafts
        return mapOf(draftKey(legacy.entryId) to legacy) + drafts
    }
    val filterFlow: Flow<JournalFilterState> = store.data.map { prefs ->
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
        store.edit { prefs ->
            val drafts = readDrafts(prefs) + (draftKey(draft.entryId) to draft)
            prefs[KEY_JOURNAL_DRAFTS] = gson.toJson(drafts)
            prefs.remove(KEY_LEGACY_JOURNAL_DRAFT)
        }
    }

    suspend fun getDraft(entryId: Long?): JournalDraft? {
        val drafts = readDrafts(store.data.first())
        return if (entryId != null) drafts[draftKey(entryId)]
        else drafts[draftKey(null)] ?: drafts.values.maxByOrNull { it.savedAtEpochMs }
    }

    suspend fun getAllDrafts(): Collection<JournalDraft> =
        readDrafts(store.data.first()).values

    suspend fun clearDraft(entryId: Long?) {
        store.edit { prefs ->
            val drafts = readDrafts(prefs) - draftKey(entryId)
            if (drafts.isEmpty()) prefs.remove(KEY_JOURNAL_DRAFTS)
            else prefs[KEY_JOURNAL_DRAFTS] = gson.toJson(drafts)
            prefs.remove(KEY_LEGACY_JOURNAL_DRAFT)
        }
    }

    suspend fun clearAllDrafts() {
        store.edit { prefs ->
            prefs.remove(KEY_JOURNAL_DRAFTS)
            prefs.remove(KEY_LEGACY_JOURNAL_DRAFT)
        }
    }

    suspend fun saveFilter(filter: JournalFilterState) {
        val json = gson.toJson(filter)
        store.edit { prefs ->
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
