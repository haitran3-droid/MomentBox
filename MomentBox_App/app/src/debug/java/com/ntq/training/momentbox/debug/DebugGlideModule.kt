@file:Suppress("DEPRECATION")

package com.ntq.training.momentbox.debug

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.engine.cache.MemoryCache
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator
import com.bumptech.glide.module.GlideModule

/** Keeps the default Glide cache configuration and exposes its usage to Developer tools. */
class DebugGlideModule : GlideModule {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        val sizes = MemorySizeCalculator.Builder(context).build()
        memoryCache = LruResourceCache(sizes.memoryCacheSize.toLong())
        builder.setMemorySizeCalculator(sizes).setMemoryCache(memoryCache)
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) = Unit

    companion object {
        internal lateinit var memoryCache: MemoryCache
            private set
    }
}
