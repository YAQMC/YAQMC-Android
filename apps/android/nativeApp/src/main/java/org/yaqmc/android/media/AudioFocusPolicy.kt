package org.yaqmc.android.media

internal enum class FocusChange {
    GAIN,
    LOSS,
    LOSS_TRANSIENT,
    LOSS_TRANSIENT_CAN_DUCK,
}

internal enum class FocusAction {
    NONE,
    PAUSE,
    RESUME,
}

/** Pure policy state; Android callbacks only translate constants into these events. */
internal class AudioFocusPolicy {
    private var userPaused = false
    private var transientPause = false
    private var resumable = true
    var allowConcurrentPlayback = false
        private set
    private var callActive = false
    private var callPause = false

    val awaitingFocusGain: Boolean get() = transientPause && resumable && !userPaused
    val awaitingCallEnd: Boolean get() = callPause && resumable && !userPaused

    fun setAllowConcurrentPlayback(enabled: Boolean) {
        if (allowConcurrentPlayback == enabled) return
        allowConcurrentPlayback = enabled
        transientPause = false
    }

    fun onUserPlay() {
        userPaused = false
        transientPause = false
        callPause = false
        callActive = false
        resumable = true
    }

    fun onUserPause() {
        userPaused = true
        transientPause = false
        callPause = false
    }

    fun onCallStateChanged(active: Boolean, isPlaying: Boolean): FocusAction {
        if (active == callActive) return FocusAction.NONE
        callActive = active
        if (active) {
            callPause = isPlaying
            transientPause = false
            return if (isPlaying) FocusAction.PAUSE else FocusAction.NONE
        }
        val resume = awaitingCallEnd
        callPause = false
        return if (resume) FocusAction.RESUME else FocusAction.NONE
    }

    fun onFocusChange(change: FocusChange, isPlaying: Boolean): FocusAction {
        if (allowConcurrentPlayback || callActive) return FocusAction.NONE
        return when (change) {
            FocusChange.LOSS -> {
                resumable = false
                transientPause = false
                if (isPlaying) FocusAction.PAUSE else FocusAction.NONE
            }
            FocusChange.LOSS_TRANSIENT,
            FocusChange.LOSS_TRANSIENT_CAN_DUCK,
            -> {
                if (isPlaying) transientPause = true
                if (isPlaying) FocusAction.PAUSE else FocusAction.NONE
            }
            FocusChange.GAIN -> {
                val resume = transientPause && resumable && !userPaused
                transientPause = false
                if (resume) FocusAction.RESUME else FocusAction.NONE
            }
        }
    }

    fun onBecomingNoisy(isPlaying: Boolean): FocusAction {
        resumable = false
        transientPause = false
        callPause = false
        return if (isPlaying) FocusAction.PAUSE else FocusAction.NONE
    }
}
