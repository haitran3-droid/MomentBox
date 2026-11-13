package com.ntq.training.momentbox.debug

import android.content.Context
import android.content.Intent
import com.ntq.training.momentbox.BuildConfig

object DebugToolsEntry {
    const val EXTRA_SOURCE_ONLY = "com.ntq.training.momentbox.debug.SOURCE_ONLY"

    fun intent(context: Context, sourceOnly: Boolean = false): Intent? =
        if (BuildConfig.DEBUG) {
            Intent().setClassName(context, "com.ntq.training.momentbox.debug.DevToolsActivity")
                .putExtra(EXTRA_SOURCE_ONLY, sourceOnly)
        } else {
            null
        }
}
