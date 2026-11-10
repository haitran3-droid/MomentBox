package com.ntq.training.momentbox.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object OwnedImageFiles {
    suspend fun decode(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap {
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
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
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
                        if (matrix.isIdentity) bitmap
                        else
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
            if (!delivered) withContext(NonCancellable + Dispatchers.IO) { created?.delete() }
        }
    }
}
