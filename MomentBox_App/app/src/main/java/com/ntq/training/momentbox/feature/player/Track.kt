package com.ntq.training.momentbox.feature.player

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

@Parcelize
data class Track(
    val id: String,
    val uri: String,
    val title: String,
    val source: String,
    val dateEpochMs: Long,
    val coverUri: String? = null,
    val favorite: Boolean = false,
    val waveform: List<Float> = emptyList(),
    val ownerScope: String = ""
) : Parcelable