package org.yaqmc.android.media

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioFocusPolicyTest {
    @Test
    fun `concurrent playback ignores other apps but disabling restores focus rules`() {
        val policy = AudioFocusPolicy()
        policy.onUserPlay()
        policy.setAllowConcurrentPlayback(true)
        for (change in FocusChange.entries) assertEquals(FocusAction.NONE, policy.onFocusChange(change, true))
        assertEquals(false, policy.awaitingFocusGain)
        policy.setAllowConcurrentPlayback(false)
        assertEquals(FocusAction.PAUSE, policy.onFocusChange(FocusChange.LOSS_TRANSIENT, true))
        assertEquals(FocusAction.RESUME, policy.onFocusChange(FocusChange.GAIN, false))
    }

    @Test
    fun `changing mode while interrupted never starts paused music`() {
        val policy = AudioFocusPolicy()
        policy.onUserPlay()
        policy.onFocusChange(FocusChange.LOSS_TRANSIENT, true)
        policy.setAllowConcurrentPlayback(true)
        policy.setAllowConcurrentPlayback(false)
        assertEquals(false, policy.awaitingFocusGain)
        assertEquals(FocusAction.NONE, policy.onFocusChange(FocusChange.GAIN, false))
    }

    @Test
    fun `calls pause concurrent playback and resume only when still wanted`() {
        val policy = AudioFocusPolicy()
        policy.setAllowConcurrentPlayback(true)
        policy.onUserPlay()
        assertEquals(FocusAction.PAUSE, policy.onCallStateChanged(true, true))
        assertEquals(true, policy.awaitingCallEnd)
        assertEquals(FocusAction.NONE, policy.onCallStateChanged(true, false))
        assertEquals(FocusAction.RESUME, policy.onCallStateChanged(false, false))
        assertEquals(false, policy.awaitingCallEnd)

        policy.onUserPlay()
        policy.onCallStateChanged(true, true)
        policy.onUserPause()
        assertEquals(FocusAction.NONE, policy.onCallStateChanged(false, false))

        policy.onUserPlay()
        policy.onCallStateChanged(true, true)
        assertEquals(FocusAction.NONE, policy.onBecomingNoisy(false))
        assertEquals(FocusAction.NONE, policy.onCallStateChanged(false, false))
        assertEquals(FocusAction.NONE, policy.onCallStateChanged(true, false))
        assertEquals(FocusAction.NONE, policy.onCallStateChanged(false, false))
    }

    @Test
    fun `headphone disconnection still pauses concurrent playback`() {
        val policy = AudioFocusPolicy()
        policy.setAllowConcurrentPlayback(true)
        policy.onUserPlay()
        assertEquals(FocusAction.PAUSE, policy.onBecomingNoisy(true))
        assertEquals(FocusAction.NONE, policy.onFocusChange(FocusChange.GAIN, false))
    }

    @Test
    fun `temporary loss resumes only without a user pause`() {
        val policy = AudioFocusPolicy()
        policy.onUserPlay()
        assertEquals(FocusAction.PAUSE, policy.onFocusChange(FocusChange.LOSS_TRANSIENT, true))
        assertEquals(true, policy.awaitingFocusGain)
        assertEquals(FocusAction.RESUME, policy.onFocusChange(FocusChange.GAIN, false))
        assertEquals(false, policy.awaitingFocusGain)

        policy.onUserPlay()
        assertEquals(FocusAction.PAUSE, policy.onFocusChange(FocusChange.LOSS_TRANSIENT_CAN_DUCK, true))
        policy.onUserPause()
        assertEquals(false, policy.awaitingFocusGain)
        assertEquals(FocusAction.NONE, policy.onFocusChange(FocusChange.GAIN, false))

        policy.onUserPlay()
        policy.onFocusChange(FocusChange.LOSS_TRANSIENT, true)
        policy.onUserPlay()
        assertEquals(false, policy.awaitingFocusGain)
        assertEquals(FocusAction.NONE, policy.onFocusChange(FocusChange.GAIN, true))
    }

    @Test
    fun `permanent loss and noisy output never auto resume`() {
        val policy = AudioFocusPolicy()
        policy.onUserPlay()
        assertEquals(FocusAction.PAUSE, policy.onFocusChange(FocusChange.LOSS, true))
        assertEquals(FocusAction.NONE, policy.onFocusChange(FocusChange.GAIN, false))

        policy.onUserPlay()
        assertEquals(FocusAction.PAUSE, policy.onBecomingNoisy(true))
        assertEquals(FocusAction.NONE, policy.onFocusChange(FocusChange.GAIN, false))
    }
}
