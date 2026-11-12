package com.ntq.training.momentbox.feature.settings

import android.provider.DocumentsContract
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class JournalDownloadWriter(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    suspend fun write(
        document: JournalExportDocument,
        fileName: String,
        destination: Uri? = null
    ): Uri = withContext(Dispatchers.IO) {
        require(fileName.startsWith("momentbox_journal_") && fileName.endsWith(".json") && '/' !in fileName)
        val scoped = Build.VERSION.SDK_INT >= 29 && destination == null
        val uri = if (scoped) resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    fileName
                )
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(
                MediaStore.MediaColumns.IS_PENDING,
                1
            )
            })
            ?: error("EXPORT_CREATE_FAILED") else requireNotNull(destination) { "EXPORT_SAF_REQUIRED" }
        try {
            ensureActive()
            val json = JournalExportCodec.encode(document)
            resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)
                ?.use { it.write(json) } ?: error("EXPORT_OPEN_FAILED")
            ensureActive()
            if (scoped) check(
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                ) == 1
            ) { "EXPORT_PUBLISH_FAILED" }
            uri
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    if (scoped) resolver.delete(
                        uri,
                        null,
                        null
                    ) else DocumentsContract.deleteDocument(resolver, uri)
                }
            }
            throw failure
        }
    }
}