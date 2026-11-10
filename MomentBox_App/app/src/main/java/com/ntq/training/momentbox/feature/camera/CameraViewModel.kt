package com.ntq.training.momentbox.feature.camera

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

class CameraViewModel(private val state: SavedStateHandle) : ViewModel() {
    var front: Boolean
        get() = state["front"] ?: false
        set(value) {
            state["front"] = value
        }

    var video: Boolean
        get() = state["video"] ?: false
        set(value) {
            state["video"] = value
        }

    var ratio: Int
        get() = state["ratio"] ?: 0
        set(value) {
            state["ratio"] = value
        }

    var timer: Int
        get() = state["timer"] ?: 0
        set(value) {
            state["timer"] = value
        }

    var flash: Int
        get() = state["flash"] ?: androidx.camera.core.ImageCapture.FLASH_MODE_AUTO
        set(value) {
            state["flash"] = value
        }

    var filter: Int
        get() = state["filter"] ?: 0
        set(value) {
            state["filter"] = value
        }
}
