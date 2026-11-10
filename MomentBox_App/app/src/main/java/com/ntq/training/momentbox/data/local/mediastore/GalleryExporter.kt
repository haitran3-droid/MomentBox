package com.ntq.training.momentbox.data.local.mediastore

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class GalleryExporter(private val context: Context) {
    suspend fun export(bitmap: Bitmap): Uri {
        val published = AtomicReference<Uri?>(null)
        var legacyFile: File? = null
        var delivered = false
        val resolver = context.contentResolver
        try {
            val output =
                withContext(Dispatchers.IO) {
                    val name = "momentbox-${UUID.randomUUID()}.jpg"
                    if (Build.VERSION.SDK_INT >= 29) {
                        val uri =
                            resolver.insert(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                ContentValues().apply {
                                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MomentBox")
                                    put(MediaStore.Images.Media.IS_PENDING, 1)
                                },
                            ) ?: error("MediaStore insert failed")
                        published.set(uri)
                        resolver.openOutputStream(uri)!!.use {
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
                        }
                        currentCoroutineContext().ensureActive()
                        check(
                            resolver.update(
                                uri,
                                ContentValues().apply {
                                    put(MediaStore.Images.Media.IS_PENDING, 0)
                                },
                                null,
                                null,
                            ) == 1
                        )
                        uri
                    } else {
                        @Suppress("DEPRECATION")
                        val folder =
                            File(
                                    Environment.getExternalStoragePublicDirectory(
                                        Environment.DIRECTORY_PICTURES
                                    ),
                                    "MomentBox",
                                )
                                .apply { check(mkdirs() || isDirectory) }
                        val file = File(folder, name)
                        legacyFile = file
                        file.outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
                        }
                        currentCoroutineContext().ensureActive()
                        suspendCancellableCoroutine { continuation ->
                            MediaScannerConnection.scanFile(
                                context,
                                arrayOf(file.absolutePath),
                                arrayOf("image/jpeg"),
                            ) { _, uri ->
                                if (uri == null) {
                                    if (continuation.isActive)
                                        continuation.resumeWithException(
                                            IllegalStateException("Media scan failed")
                                        )
                                } else {
                                    published.set(uri)
                                    if (continuation.isActive) {
                                        continuation.resume(uri)
                                    } else if (published.compareAndSet(uri, null)) {
                                        runCatching { resolver.delete(uri, null, null) }
                                    }
                                }
                            }
                        }
                    }
                }
            delivered = true
            return output
        } finally {
            if (!delivered)
                withContext(NonCancellable + Dispatchers.IO) {
                    published.getAndSet(null)?.let {
                        runCatching { resolver.delete(it, null, null) }
                    }
                    legacyFile?.delete()
                }
        }
    }
}
