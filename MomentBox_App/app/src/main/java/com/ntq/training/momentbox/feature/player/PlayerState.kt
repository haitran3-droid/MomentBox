package com.ntq.training.momentbox.feature.player

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PlayerState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val preparing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
    val sleepAtEpochMs: Long = 0,
    val sleepEndTrack: Boolean = false,
    val route: String = "unknown",
    val focus: String = "NONE",
    val error: String? = null,
    val servicePid: Int = 0,
    val revision: Long = 0,
    val sleepDurationMs: Long = 0
) : Parcelable
