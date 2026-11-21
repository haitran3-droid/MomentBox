package com.ntq.training.momentbox

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ntq.training.momentbox.data.local.prefs.PostDraftStore
import com.ntq.training.momentbox.feature.compose.ComposeDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PostRetryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun lostResponseKeepsThePostIdOnDiskUntilTheDraftChangesOrCompletes() = runBlocking {
        val file = File(folder.root, "post-retry.preferences_pb")
        suspend fun <T> withStore(block: suspend (PostDraftStore) -> T): T {
            val job = Job()
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
            return try { block(PostDraftStore(store)) } finally { job.cancelAndJoin() }
        }

        val original = ComposeDraft(caption = "hello")
        val sent = withStore { store ->
            store.save(original)
            store.postClientId(original)
        }
        // Reopen the actual preferences file as a new process would after losing the ACK.
        withStore { store ->
            val restored = checkNotNull(store.load())
            store.save(restored)
            assertEquals(sent, store.postClientId(restored))
            val journalCopy = restored.copy(saveToJournal = true)
            store.save(journalCopy)
            assertEquals(sent, store.postClientId(journalCopy))
            val edited = restored.copy(caption = "new caption")
            store.save(edited)
            val second = store.postClientId(edited)
            assertNotEquals(sent, second)
            store.clearMatching(edited)
            store.save(edited)
            val third = store.postClientId(edited)
            assertNotEquals(second, third)
            store.clear()
            store.save(edited)
            assertNotEquals(third, store.postClientId(edited))
        }
    }
}
