package com.ntq.training.momentbox.feature.videoplayer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

class VideoPlaybackViewModel(private val saved: SavedStateHandle) : ViewModel() {
    var positionMs: Long
        get() = saved["position"] ?: 0L
        set(value) {
            saved["position"] = value
        }
    var intendedPlay: Boolean
        get() = saved["play"] ?: true
        set(value) {
            saved["play"] = value
        }
    var speed: Float
        get() = saved["speed"] ?: 1f
        set(value) {
            saved["speed"] = value
        }
    var locked: Boolean
        get() = saved["locked"] ?: false
        set(value) {
            saved["locked"] = value
        }
    var muted: Boolean
        get() = saved["muted"] ?: false
        set(value) {
            saved["muted"] = value
        }

    fun initialize(position: Long, play: Boolean) {
        if (saved.get<Boolean>("initialized") == true) return
        positionMs = position.coerceAtLeast(0L)
        intendedPlay = play
        saved["initialized"] = true
    }
}