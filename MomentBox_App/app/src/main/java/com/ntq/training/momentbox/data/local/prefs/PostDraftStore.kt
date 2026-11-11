package com.ntq.training.momentbox.data.local.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.ntq.training.momentbox.domain.model.Post
import com.ntq.training.momentbox.feature.compose.ComposeDraft
import kotlin.jvm.java
import kotlinx.coroutines.flow.first

/** DataStore riêng của S15, không đụng journal_draft của M3. */
private val Context.postDraftDataStore by preferencesDataStore(name = "post_draft")

class PostDraftStore(context: Context) {
    private val store = context.applicationContext.postDraftDataStore
    private val key = stringPreferencesKey("draft_json")

    suspend fun load(): ComposeDraft? {
        val json = store.data.first()[key] ?: return null
        return Gson().fromJson(json, ComposeDraft::class.java)
    }

    suspend fun save(draft: ComposeDraft) {
        val json = Gson().toJson(draft)
        store.edit { it[key] = json }
    }

    suspend fun clear() {
        store.edit { it.remove(key) }
    }

    private fun receiptKey(source: String, userId: Long) =
        stringPreferencesKey("journal_receipt_${source}_$userId")

    suspend fun loadReceipt(source: String, userId: Long): PendingJournalReceipt? {
        val json = store.data.first()[receiptKey(source, userId)] ?: return null
        return Gson().fromJson(json, PendingJournalReceipt::class.java)
    }

    suspend fun saveReceipt(receipt: PendingJournalReceipt) {
        val json = Gson().toJson(receipt)
        store.edit { it[receiptKey(receipt.source, receipt.post.author.id)] = json }
    }

    suspend fun clearReceipt(receipt: PendingJournalReceipt) {
        store.edit { prefs ->
            val key = receiptKey(receipt.source, receipt.post.author.id)

            val old = prefs[key]?.let { Gson().fromJson(it, PendingJournalReceipt::class.java) }
            if (old?.post?.id == receipt.post.id) prefs.remove(key)
        }
    }

    /** Không xóa nháp khác nếu một form mới đã tạo trong lúc request cũ đang chờ phản hồi. */
    suspend fun clearMatching(expected: ComposeDraft) {
        store.edit { prefs ->
            val old =
                prefs[key]?.let {
                    runCatching { Gson().fromJson(it, ComposeDraft::class.java) }.getOrNull()
                }
            if (old?.snapshot() == expected.snapshot()) prefs.remove(key)
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
