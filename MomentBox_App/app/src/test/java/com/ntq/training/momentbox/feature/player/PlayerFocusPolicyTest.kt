package com.ntq.training.momentbox.feature.player

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerFocusPolicyTest {
    @Test
    fun resumesOnlyAfterTemporaryLossWhilePlaybackIsRequested() {
        val policy = PlayerFocusPolicy()
        assertTrue(policy.userPlay(true))
        assertEquals(FocusEffect.PAUSE, policy.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, true))
        assertEquals(FocusEffect.PAUSE, policy.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, false))
        assertEquals(FocusEffect.PLAY, policy.change(AudioManager.AUDIOFOCUS_GAIN, false))
        assertEquals(FocusEffect.RESTORE, policy.change(AudioManager.AUDIOFOCUS_GAIN, true))
    }

    @Test
    fun permanentLossOrUserPauseClearsResumeIntent() {
        val policy = PlayerFocusPolicy()
        policy.userPlay(true)
        policy.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, true)
        assertEquals(FocusEffect.PAUSE, policy.change(AudioManager.AUDIOFOCUS_LOSS, false))
        assertFalse(policy.wantsPlay)
        assertEquals(FocusEffect.RESTORE, policy.change(AudioManager.AUDIOFOCUS_GAIN, false))

        policy.userPlay(true)
        assertEquals(FocusEffect.DUCK, policy.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, true))
        policy.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, true)
        policy.userPause()
        assertEquals(FocusEffect.RESTORE, policy.change(AudioManager.AUDIOFOCUS_GAIN, false))
        assertFalse(policy.userPlay(false))
    }
}
