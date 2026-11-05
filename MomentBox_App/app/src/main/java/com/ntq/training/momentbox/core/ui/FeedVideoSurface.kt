package com.ntq.training.momentbox.core.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

class FeedVideoSurface @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private var renderer: PlayerView? = null
    val player: Player? get() = renderer?.player

    internal fun attachRenderer(view: PlayerView) {
        if (renderer === view) return
        detachRenderer()
        (view.parent as? ViewGroup)?.removeView(view)
        renderer = view
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    internal fun detachRenderer() {
        renderer?.let { view ->
            view.player = null
            removeView(view)
        }
        renderer = null
    }
}