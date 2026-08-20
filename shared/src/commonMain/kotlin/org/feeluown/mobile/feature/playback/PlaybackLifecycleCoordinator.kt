package org.feeluown.mobile

internal enum class PlaybackEndAction {
    None,
    CompleteSleepTimer,
    AutoAdvance,
}

internal interface PlaybackEndSleepTimer {
    fun onTrackChanged(trackId: String?)
    fun shouldCompleteEndOfTrack(trackId: String, isFinalPlaybackPart: Boolean): Boolean
    fun completeEndOfTrack()
}

/**
 * Owns the state machine that turns engine Playing/Ended transitions into either sleep-timer
 * completion or queue auto-advance. The caller publishes the engine snapshot first, then executes
 * the returned action, preserving the existing playback-state ordering without keeping lifecycle
 * policy in the app controller.
 */
internal class PlaybackLifecycleCoordinator(
    private val sleepTimer: PlaybackEndSleepTimer,
    private val fallbackPlaybackParts: () -> List<PlaybackPart>,
    private val fallbackCurrentPartIndex: () -> Int,
    private val autoAdvance: () -> Unit,
) {
    private var lastEndedTrackId: String? = null
    private var autoAdvanceEligibleTrackId: String? = null

    fun evaluate(
        engineState: PlaybackState,
        currentQueueTrackId: String?,
    ): PlaybackEndAction {
        sleepTimer.onTrackChanged(currentQueueTrackId)
        return when (engineState.status) {
            PlayerStatus.Playing -> {
                autoAdvanceEligibleTrackId = currentQueueTrackId ?: engineState.currentTrack?.id
                lastEndedTrackId = null
                PlaybackEndAction.None
            }
            PlayerStatus.Ended -> evaluateEnded(engineState, currentQueueTrackId)
            else -> {
                lastEndedTrackId = null
                PlaybackEndAction.None
            }
        }
    }

    fun execute(action: PlaybackEndAction) {
        when (action) {
            PlaybackEndAction.None -> Unit
            PlaybackEndAction.CompleteSleepTimer -> sleepTimer.completeEndOfTrack()
            PlaybackEndAction.AutoAdvance -> autoAdvance()
        }
    }

    private fun evaluateEnded(
        engineState: PlaybackState,
        currentQueueTrackId: String?,
    ): PlaybackEndAction {
        val endedTrackId = currentQueueTrackId ?: return PlaybackEndAction.None
        if (
            endedTrackId != autoAdvanceEligibleTrackId ||
            endedTrackId == lastEndedTrackId
        ) {
            return PlaybackEndAction.None
        }
        autoAdvanceEligibleTrackId = null
        lastEndedTrackId = endedTrackId
        val activeParts = engineState.playbackParts.ifEmpty(fallbackPlaybackParts)
        val activePartIndex = engineState.currentPartIndex
            .takeIf { it >= 0 }
            ?: fallbackCurrentPartIndex()
        val isFinalPlaybackPart = activeParts.isEmpty() ||
            activePartIndex !in activeParts.indices ||
            activePartIndex >= activeParts.lastIndex
        return if (
            sleepTimer.shouldCompleteEndOfTrack(
                trackId = endedTrackId,
                isFinalPlaybackPart = isFinalPlaybackPart,
            )
        ) {
            PlaybackEndAction.CompleteSleepTimer
        } else {
            PlaybackEndAction.AutoAdvance
        }
    }
}
