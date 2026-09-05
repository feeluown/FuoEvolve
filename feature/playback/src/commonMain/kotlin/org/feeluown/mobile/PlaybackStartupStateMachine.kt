package org.feeluown.mobile

/** Describes whether a playback start should replace or resume the restored platform session. */
enum class PlaybackStartupMode {
    Fresh,
    ResumeRestored,
}

/** Action a platform engine should take for one state emitted by its playback service/runtime. */
enum class PlaybackServiceStateAction {
    Accept,
    Ignore,
    RepublishRestored,
}

sealed interface PlaybackStartupPhase {
    data object Idle : PlaybackStartupPhase

    data class Restored(
        val trackId: String,
        val generation: Long,
    ) : PlaybackStartupPhase

    data class Prepared(
        val trackId: String,
        val mode: PlaybackStartupMode,
    ) : PlaybackStartupPhase

    data class Starting(
        val trackId: String,
        val generation: Long,
        val mode: PlaybackStartupMode,
    ) : PlaybackStartupPhase

    data class Active(
        val trackId: String,
        val generation: Long,
    ) : PlaybackStartupPhase

    data object Stopped : PlaybackStartupPhase
}

/**
 * Single bootstrap/startup state machine for platform playback engines.
 *
 * Persisted-session restore and a new explicit playback transaction are mutually exclusive states.
 * Service/runtime emissions are accepted only when they belong to the active startup generation,
 * preventing a stale paused Media3 session from overwriting a new user selection.
 */
class PlaybackStartupStateMachine(
    restoredTrackId: String? = null,
    restoredGeneration: Long = 0L,
) {
    var phase: PlaybackStartupPhase = restoredTrackId
        ?.let { PlaybackStartupPhase.Restored(it, restoredGeneration) }
        ?: PlaybackStartupPhase.Idle
        private set

    fun prepare(
        trackId: String,
        reason: PlaybackStartReason,
        canResumeRestoredSession: Boolean,
    ): PlaybackStartupMode {
        val mode = if (reason.mayResumePausedSession && canResumeRestoredSession) {
            PlaybackStartupMode.ResumeRestored
        } else {
            PlaybackStartupMode.Fresh
        }
        phase = PlaybackStartupPhase.Prepared(trackId, mode)
        return mode
    }

    fun beginStart(trackId: String, generation: Long): PlaybackStartupMode {
        val mode = (phase as? PlaybackStartupPhase.Prepared)
            ?.takeIf { it.trackId == trackId }
            ?.mode
            ?: PlaybackStartupMode.Fresh
        phase = PlaybackStartupPhase.Starting(trackId, generation, mode)
        return mode
    }

    fun beginFreshStart(trackId: String, generation: Long) {
        phase = PlaybackStartupPhase.Starting(trackId, generation, PlaybackStartupMode.Fresh)
    }

    fun markRestored(trackId: String, generation: Long) {
        phase = PlaybackStartupPhase.Restored(trackId, generation)
    }

    fun markActive(trackId: String, generation: Long) {
        phase = PlaybackStartupPhase.Active(trackId, generation)
    }

    fun markIdle() {
        phase = PlaybackStartupPhase.Idle
    }

    fun stop() {
        phase = PlaybackStartupPhase.Stopped
    }

    fun canRepublishRestoredState(): Boolean = when (val current = phase) {
        is PlaybackStartupPhase.Restored -> true
        is PlaybackStartupPhase.Prepared -> current.mode == PlaybackStartupMode.ResumeRestored
        else -> false
    }

    fun onServiceState(
        serviceTrackId: String?,
        serviceGeneration: Long,
        isEmptyIdleState: Boolean,
    ): PlaybackServiceStateAction = when (val current = phase) {
        PlaybackStartupPhase.Idle -> PlaybackServiceStateAction.Accept

        is PlaybackStartupPhase.Active -> when {
            isEmptyIdleState -> PlaybackServiceStateAction.RepublishRestored
            serviceGeneration != current.generation -> PlaybackServiceStateAction.Ignore
            else -> {
                phase = PlaybackStartupPhase.Active(
                    trackId = serviceTrackId ?: current.trackId,
                    generation = current.generation,
                )
                PlaybackServiceStateAction.Accept
            }
        }

        is PlaybackStartupPhase.Restored -> when {
            isEmptyIdleState -> PlaybackServiceStateAction.RepublishRestored
            serviceTrackId != null && serviceTrackId != current.trackId -> PlaybackServiceStateAction.Ignore
            else -> {
                phase = PlaybackStartupPhase.Active(
                    trackId = serviceTrackId ?: current.trackId,
                    generation = serviceGeneration,
                )
                PlaybackServiceStateAction.Accept
            }
        }

        is PlaybackStartupPhase.Prepared -> {
            if (current.mode == PlaybackStartupMode.Fresh) {
                PlaybackServiceStateAction.Ignore
            } else if (isEmptyIdleState) {
                PlaybackServiceStateAction.RepublishRestored
            } else if (serviceTrackId != null && serviceTrackId != current.trackId) {
                PlaybackServiceStateAction.Ignore
            } else {
                // Keep Prepared(ResumeRestored) until beginStart() binds the new transaction generation.
                PlaybackServiceStateAction.Accept
            }
        }

        is PlaybackStartupPhase.Starting -> {
            if (isEmptyIdleState) {
                PlaybackServiceStateAction.Ignore
            } else {
                val trackMatches = serviceTrackId == null || serviceTrackId == current.trackId
                val generationMatches = serviceGeneration == current.generation
                if (!trackMatches || !generationMatches) {
                    PlaybackServiceStateAction.Ignore
                } else {
                    phase = PlaybackStartupPhase.Active(current.trackId, current.generation)
                    PlaybackServiceStateAction.Accept
                }
            }
        }

        PlaybackStartupPhase.Stopped -> {
            if (isEmptyIdleState) {
                phase = PlaybackStartupPhase.Idle
                PlaybackServiceStateAction.Accept
            } else {
                PlaybackServiceStateAction.Ignore
            }
        }
    }
}
