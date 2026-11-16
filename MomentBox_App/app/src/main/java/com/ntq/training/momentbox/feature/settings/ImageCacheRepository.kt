package com.ntq.training.momentbox.feature.settings

import android.content.Context
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImageCacheRepository(context: Context) {
    private val app = context.applicationContext
    suspend fun diskBytes(): Long = withContext(Dispatchers.IO) {
        Glide.getPhotoCacheDir(app)?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() }
            ?: 0L
    }

    suspend fun clear(): Long {
        withContext(Dispatchers.IO) { Glide.get(app).clearDiskCache() }
        withContext(Dispatchers.Main.immediate) { Glide.get(app).clearMemory() }
        return diskBytes()
    }
}