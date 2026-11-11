package com.ntq.training.momentbox.feature.player

class PlayerFocusPolicy {
    var wantsPlay = false; private set
    private var resumeAfterTransient = false
    fun userPlay(granted: Boolean): Boolean {
        wantsPlay = granted; resumeAfterTransient = false; return granted
    }

    fun userPause() {
        wantsPlay = false; resumeAfterTransient = false
    }

    fun change(change: Int, playing: Boolean): FocusEffect = when (change) {
        1 -> {
            val resume = resumeAfterTransient && wantsPlay; resumeAfterTransient =
                false; if (resume) FocusEffect.PLAY else FocusEffect.RESTORE
        }

        -2 -> {
            resumeAfterTransient = resumeAfterTransient || (wantsPlay && playing); FocusEffect.PAUSE
        }

        -3 -> if (playing && wantsPlay) FocusEffect.DUCK else FocusEffect.NONE
        -1 -> {
            wantsPlay = false; resumeAfterTransient = false; FocusEffect.PAUSE
        }

        else -> FocusEffect.NONE
    }
}