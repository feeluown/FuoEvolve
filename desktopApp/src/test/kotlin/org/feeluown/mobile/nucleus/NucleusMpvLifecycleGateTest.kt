package org.feeluown.mobile.nucleus

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NucleusMpvLifecycleGateTest {
    @Test
    fun staleLifecycleEventsAreRejectedAfterRequestReset() {
        val gate = NucleusMpvLifecycleGate()
        gate.matchStart(10L)
        gate.markActivated(10L)

        gate.reset()

        assertFalse(gate.canAcceptFileLoaded(10L))
        assertFalse(gate.canAcceptPlaybackRestart(10L))
    }

    @Test
    fun fileLoadedRequiresMatchedStartForCurrentEntry() {
        val gate = NucleusMpvLifecycleGate()
        gate.matchStart(20L)

        assertFalse(gate.canAcceptFileLoaded(10L))
        assertTrue(gate.canAcceptFileLoaded(20L))
    }

    @Test
    fun playbackRestartRequiresCurrentEntryToBeActivated() {
        val gate = NucleusMpvLifecycleGate()
        gate.matchStart(20L)

        assertFalse(gate.canAcceptPlaybackRestart(20L))

        gate.markActivated(20L)

        assertFalse(gate.canAcceptPlaybackRestart(10L))
        assertTrue(gate.canAcceptPlaybackRestart(20L))
    }

    @Test
    fun pollingActivationDoesNotInventMatchedStart() {
        val gate = NucleusMpvLifecycleGate()
        gate.markActivated(20L)

        assertFalse(gate.canAcceptPlaybackRestart(20L))
    }
}
