package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-layer regression contract for Android service auto-advance identity.
 *
 * A PlaybackPlan generation may legitimately move across multiple tracks. Once startup is active,
 * same-generation service transitions must remain observable so the shared playback owner can align
 * its queue and lyrics with the platform's actual media item.
 */
class PlaybackAutoAdvanceContractTest {
    @Test
    fun sameGenerationAutoAdvanceRemainsObservableAcrossMultipleTracks() {
        val machine = PlaybackStartupStateMachine()
        machine.beginFreshStart("track-a", generation = 11L)

        assertEquals(
            PlaybackServiceStateAction.Accept,
            machine.onServiceState("track-a", 11L, isEmptyIdleState = false),
        )
        assertEquals(PlaybackStartupPhase.Active("track-a", 11L), machine.phase)

        assertEquals(
            PlaybackServiceStateAction.Accept,
            machine.onServiceState("track-b", 11L, isEmptyIdleState = false),
        )
        assertEquals(PlaybackStartupPhase.Active("track-b", 11L), machine.phase)

        assertEquals(
            PlaybackServiceStateAction.Accept,
            machine.onServiceState("track-c", 11L, isEmptyIdleState = false),
        )
        assertEquals(PlaybackStartupPhase.Active("track-c", 11L), machine.phase)
    }

    @Test
    fun staleGenerationCannotReplaceCurrentAutoAdvancedTrack() {
        val machine = PlaybackStartupStateMachine()
        machine.beginFreshStart("track-a", generation = 11L)
        machine.onServiceState("track-a", 11L, isEmptyIdleState = false)
        machine.onServiceState("track-b", 11L, isEmptyIdleState = false)

        assertEquals(
            PlaybackServiceStateAction.Ignore,
            machine.onServiceState("track-a", 10L, isEmptyIdleState = false),
        )
        assertEquals(PlaybackStartupPhase.Active("track-b", 11L), machine.phase)
    }
}
