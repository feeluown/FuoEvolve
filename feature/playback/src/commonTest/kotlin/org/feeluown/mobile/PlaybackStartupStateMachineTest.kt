package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackStartupStateMachineTest {
    @Test
    fun resumeBindsRestoredSessionToNewTransactionGeneration() {
        val machine = PlaybackStartupStateMachine(
            restoredTrackId = "track-a",
            restoredGeneration = 4L,
        )

        assertEquals(
            PlaybackStartupMode.ResumeRestored,
            machine.prepare(
                trackId = "track-a",
                reason = PlaybackStartReason.RESUME,
                canResumeRestoredSession = true,
            ),
        )
        assertEquals(
            PlaybackServiceStateAction.RepublishRestored,
            machine.onServiceState(null, 0L, isEmptyIdleState = true),
        )
        assertEquals(PlaybackStartupMode.ResumeRestored, machine.beginStart("track-a", 9L))
        assertEquals(
            PlaybackServiceStateAction.Ignore,
            machine.onServiceState("track-a", 4L, isEmptyIdleState = false),
        )
        assertEquals(
            PlaybackServiceStateAction.Accept,
            machine.onServiceState("track-a", 9L, isEmptyIdleState = false),
        )
        assertEquals(PlaybackStartupPhase.Active("track-a", 9L), machine.phase)
    }

    @Test
    fun activeSelectionNeverResumesSameTrackRestoredSession() {
        val machine = PlaybackStartupStateMachine(
            restoredTrackId = "track-a",
            restoredGeneration = 4L,
        )

        assertEquals(
            PlaybackStartupMode.Fresh,
            machine.prepare(
                trackId = "track-a",
                reason = PlaybackStartReason.USER_SELECTION,
                canResumeRestoredSession = true,
            ),
        )
        assertEquals(
            PlaybackServiceStateAction.Ignore,
            machine.onServiceState("track-a", 4L, isEmptyIdleState = false),
        )
        assertEquals(PlaybackStartupMode.Fresh, machine.beginStart("track-a", 10L))
        assertEquals(
            PlaybackServiceStateAction.Ignore,
            machine.onServiceState("track-a", 4L, isEmptyIdleState = false),
        )
        assertEquals(
            PlaybackServiceStateAction.Accept,
            machine.onServiceState("track-a", 10L, isEmptyIdleState = false),
        )
    }

    @Test
    fun stoppedPhaseRejectsLateServiceStateUntilIdleAcknowledgement() {
        val machine = PlaybackStartupStateMachine()
        machine.beginFreshStart("track-a", 3L)
        machine.stop()

        assertEquals(
            PlaybackServiceStateAction.Ignore,
            machine.onServiceState("track-a", 3L, isEmptyIdleState = false),
        )
        assertEquals(
            PlaybackServiceStateAction.Accept,
            machine.onServiceState(null, 0L, isEmptyIdleState = true),
        )
        assertEquals(PlaybackStartupPhase.Idle, machine.phase)
    }

    @Test
    fun preparedFreshStartRejectsEmptyBootstrapState() {
        val machine = PlaybackStartupStateMachine(
            restoredTrackId = "track-a",
            restoredGeneration = 1L,
        )
        machine.prepare(
            trackId = "track-b",
            reason = PlaybackStartReason.PLAYLIST_REPLACE,
            canResumeRestoredSession = false,
        )

        assertEquals(
            PlaybackServiceStateAction.Ignore,
            machine.onServiceState(null, 0L, isEmptyIdleState = true),
        )
    }

    @Test
    fun restoredAndPreparedResumeRejectDifferentServiceTrack() {
        val machine = PlaybackStartupStateMachine(
            restoredTrackId = "track-a",
            restoredGeneration = 2L,
        )

        assertEquals(
            PlaybackServiceStateAction.Ignore,
            machine.onServiceState("track-b", 2L, isEmptyIdleState = false),
        )
        machine.prepare(
            trackId = "track-a",
            reason = PlaybackStartReason.RESUME,
            canResumeRestoredSession = true,
        )
        assertEquals(
            PlaybackServiceStateAction.Ignore,
            machine.onServiceState("track-b", 2L, isEmptyIdleState = false),
        )
    }

    @Test
    fun activeSessionRepublishesPersistedStateWhenServiceDropsToEmptyIdle() {
        val machine = PlaybackStartupStateMachine()
        machine.beginFreshStart("track-a", 7L)
        assertEquals(
            PlaybackServiceStateAction.Accept,
            machine.onServiceState("track-a", 7L, isEmptyIdleState = false),
        )

        assertEquals(
            PlaybackServiceStateAction.RepublishRestored,
            machine.onServiceState(null, 0L, isEmptyIdleState = true),
        )
    }
}
