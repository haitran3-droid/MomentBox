package com.ntq.training.momentbox.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.ntq.training.momentbox.domain.model.Post
import com.ntq.training.momentbox.feature.compose.ComposeDraft
import java.util.UUID
import kotlin.jvm.java
import kotlinx.coroutines.flow.first

/** DataStore riêng của S15, không đụng journal_draft của M3. */
private val Context.postDraftDataStore by preferencesDataStore(name = "post_draft")

class PostDraftStore internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.postDraftDataStore)

    private val key = stringPreferencesKey("draft_json")
    private val operationKey = stringPreferencesKey("post_client_id")
    private val gson = Gson()

    suspend fun load(): ComposeDraft? {
        val json = store.data.first()[key] ?: return null
        return gson.fromJson(json, ComposeDraft::class.java)
    }

    suspend fun save(draft: ComposeDraft) {
        val json = gson.toJson(draft)
        store.edit { prefs ->
            if (prefs[operationKey] != null && prefs[key] != json) {
                val previous = prefs[key]?.let { gson.fromJson(it, ComposeDraft::class.java) }
                // Saving to the local journal is independent of the remote post.
                if (previous?.copy(saveToJournal = draft.saveToJournal) != draft)
                    prefs.remove(operationKey)
            }
            prefs[key] = json
        }
    }

    /** The draft and its operation ID must be on disk before sending the first request. */
    suspend fun postClientId(draft: ComposeDraft): String {
        val prefs = store.edit {
            check(it[key]?.let { json -> gson.fromJson(json, ComposeDraft::class.java) } == draft)
            if (it[operationKey] == null) it[operationKey] = UUID.randomUUID().toString()
        }
        return checkNotNull(prefs[operationKey])
    }

    suspend fun clear() {
        store.edit {
            it.remove(key)
            it.remove(operationKey)
        }
    }

    private fun receiptKey(source: String, userId: Long) =
        stringPreferencesKey("journal_receipt_${source}_$userId")

    suspend fun loadReceipt(source: String, userId: Long): PendingJournalReceipt? {
        val json = store.data.first()[receiptKey(source, userId)] ?: return null
        return gson.fromJson(json, PendingJournalReceipt::class.java)
    }

    suspend fun saveReceipt(receipt: PendingJournalReceipt) {
        val json = gson.toJson(receipt)
        store.edit { it[receiptKey(receipt.source, receipt.post.author.id)] = json }
    }

    suspend fun clearReceipt(receipt: PendingJournalReceipt) {
        store.edit { prefs ->
            val key = receiptKey(receipt.source, receipt.post.author.id)

            val old = prefs[key]?.let { gson.fromJson(it, PendingJournalReceipt::class.java) }
            if (old?.post?.id == receipt.post.id) prefs.remove(key)
        }
    }

    /** Không xóa nháp khác nếu một form mới đã tạo trong lúc request cũ đang chờ phản hồi. */
    suspend fun clearMatching(expected: ComposeDraft) {
        store.edit { prefs ->
            val old =
                prefs[key]?.let {
                    runCatching { gson.fromJson(it, ComposeDraft::class.java) }.getOrNull()
                }
            if (old?.snapshot() == expected.snapshot()) {
                prefs.remove(key)
                prefs.remove(operationKey)
            }
        }
    }
}

/**
 * Biên nhận remote đã commit: chỉ bù Room bằng ID ổn định, tuyệt đối không dùng để POST lại. Không
 * lưu token.
 */
data class PendingJournalReceipt(
    val draft: ComposeDraft,
    val post: Post,
    val entryId: Long,
    val source: String,
)
