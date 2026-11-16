package com.ntq.training.momentbox.feature.player

import android.media.AudioManager

enum class FocusEffect { PLAY, RESTORE, PAUSE, DUCK, NONE }

class PlayerFocusPolicy {
    var wantsPlay = false
    private set
    private var resumeAfterTransient = false
    fun userPlay(granted: Boolean): Boolean {
        wantsPlay = granted
        resumeAfterTransient = false
        return granted
    }

    fun userPause() {
        wantsPlay = false
        resumeAfterTransient = false
    }

    fun change(change: Int, playing: Boolean): FocusEffect = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> {
            val resume = resumeAfterTransient && wantsPlay
            resumeAfterTransient = false
            if (resume) FocusEffect.PLAY else FocusEffect.RESTORE
        }

        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
            resumeAfterTransient = resumeAfterTransient || (wantsPlay && playing)
            FocusEffect.PAUSE
        }

        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
            if (playing && wantsPlay) FocusEffect.DUCK else FocusEffect.NONE

        AudioManager.AUDIOFOCUS_LOSS -> {
            wantsPlay = false
            resumeAfterTransient = false
            FocusEffect.PAUSE
        }

        else -> FocusEffect.NONE
    }
}
