package com.ntq.training.momentbox.core.ui

import android.content.Context
import android.content.res.Resources
import androidx.core.content.ContextCompat

object MediaImageModel {
    fun resolve(context: Context, url: String): Any?{
        val prefix = "android.resource://${context.packageName}/"
        if (url == null || !url.startsWith(prefix)) return url
        val id = url.removePrefix(prefix).toIntOrNull() ?: return url
        return try {
            if (context.resources.getResourceTypeName(id) !in setOf("drawable", "mipmap")) url
            else ContextCompat.getDrawable(context, id) ?: url
        } catch (_: Resources.NotFoundException) { url }
    }
}