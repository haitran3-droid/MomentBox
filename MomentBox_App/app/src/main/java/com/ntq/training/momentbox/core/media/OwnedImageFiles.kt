package com.ntq.training.momentbox.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object OwnedImageFiles {
    const val MAX_DECODE_SIDE = 2048

    internal fun computeSampleSize(
        outWidth: Int,
        outHeight: Int,
        requestedMaxSide: Int = MAX_DECODE_SIDE,
    ): Int {
        val effectiveMaxSide = requestedMaxSide.coerceIn(1, MAX_DECODE_SIDE)
        require(outWidth > 0 && outHeight > 0)
        var sample = 1
        while (maxOf(outWidth, outHeight) / sample > effectiveMaxSide) {
            sample *= 2
        }
        return sample
    }

    suspend fun decode(
        context: Context,
        uri: Uri,
        maxSide: Int = MAX_DECODE_SIDE,
    ): Bitmap {
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var delivered = false
        try {
            val output =
                withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)!!.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 && maxSide > 0)
                    val sample = computeSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
                    val orientation =
                        resolver.openInputStream(uri)!!.use {
                            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                        }
                    val bitmap =
                        resolver.openInputStream(uri)!!.use {
                            BitmapFactory.decodeStream(
                                it,
                                null,
                                BitmapFactory.Options().apply { inSampleSize = sample },
                            )
                        } ?: error("Decode failed")
                    decoded = bitmap
                    oriented = bitmap
                    withContext(Dispatchers.Default) {
                        val matrix =
                            Matrix().apply {
                                when (orientation) {
                                    2 -> setScale(-1f, 1f)
                                    3 -> setRotate(180f)
                                    4 -> setScale(1f, -1f)
                                    5 -> {
                                        setRotate(90f)
                                        postScale(-1f, 1f)
                                    }
                                    6 -> setRotate(90f)
                                    7 -> {
                                        setRotate(-90f)
                                        postScale(-1f, 1f)
                                    }
                                    8 -> setRotate(-90f)
                                }
                            }
                        if (matrix.isIdentity) {
                            bitmap
                        } else {
                            Bitmap.createBitmap(
                                bitmap,
                                0,
                                0,
                                bitmap.width,
                                bitmap.height,
                                matrix,
                                true,
                            )
                                .also {
                                    oriented = it
                                    if (it !== bitmap) bitmap.recycle()
                                }
                        }
                    }
                }
            delivered = true
            return output
        } finally {
            if (!delivered) {
                oriented?.takeUnless { it.isRecycled }?.recycle()
                decoded?.takeUnless { it.isRecycled }?.recycle()
            }
        }
    }

    suspend fun jpeg(context: Context, bitmap: Bitmap, quality: Int = 90): File {
        var created: File? = null
        var delivered = false
        try {
            val output =
                withContext(Dispatchers.IO) {
                    val folder =
                        File(context.filesDir, "create-media").apply {
                            check(mkdirs() || isDirectory)
                        }
                    val file = File(folder, "image-${UUID.randomUUID()}.jpg")
                    created = file
                    file.outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it))
                    }
                    currentCoroutineContext().ensureActive()
                    file
                }
            delivered = true
            return output
        } finally {
            if (!delivered) {
                withContext(NonCancellable + Dispatchers.IO) { created?.delete() }
            }
        }
    }

    /**
     * Resolves a file inside `filesDir/create-media/` from either our FileProvider URI or a `file:`
     * URI. Returns null for any external Gallery, draft, or recording URI so non-owned files are
     * never touched.
     */
    fun resolveOwnedMediaFile(context: Context, uriString: String?): File? {
        if (uriString.isNullOrBlank()) return null
        return runCatching {
            val folder = File(context.filesDir, "create-media").canonicalFile
            val uri = uriString.toUri()
            val candidate =
                when {
                    uri.scheme == "content" && uri.authority == "${context.packageName}.media" -> {
                        val segments = uri.pathSegments
                        if (segments.size == 2 && segments[0] == "created") {
                            File(folder, segments[1]).canonicalFile
                        } else {
                            null
                        }
                    }
                    uri.scheme == "file" -> {
                        uri.path?.let { File(it).canonicalFile }
                    }
                    else -> null
                }
            candidate?.takeIf { it.parentFile == folder }
        }
            .getOrNull()
    }

    fun deleteOwnedMediaUri(context: Context, uriString: String?): Boolean {
        val target = resolveOwnedMediaFile(context, uriString) ?: return false
        return runCatching { target.delete() }.getOrDefault(false)
    }

    fun deleteEditorSessionFiles(context: Context, sessionId: String) {
        if (sessionId.isBlank()) return
        runCatching {
            val folder = File(context.filesDir, "editor-sessions").canonicalFile
            listOf("$sessionId.json", "$sessionId.json.bak", "$sessionId.json.corrupt")
                .forEach { name ->
                    val file = File(folder, name).canonicalFile
                    if (file.parentFile == folder && file.exists()) {
                        file.delete()
                    }
                }
        }
    }
}
