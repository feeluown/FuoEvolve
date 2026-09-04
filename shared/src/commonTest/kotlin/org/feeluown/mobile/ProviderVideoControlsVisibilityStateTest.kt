package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderVideoControlsVisibilityStateTest {
    @Test
    fun playingControlsCanAutoHideAndSurfaceTapRestoresThem() {
        var state = ProviderVideoControlsVisibilityState()

        state = state.afterAutoHide(isPlaying = true)
        assertFalse(state.visible)

        val previousEpoch = state.interactionEpoch
        state = state.afterSurfaceTap()
        assertTrue(state.visible)
        assertEquals(previousEpoch + 1L, state.interactionEpoch)
    }

    @Test
    fun pausedPlaybackKeepsControlsVisible() {
        var state = ProviderVideoControlsVisibilityState(visible = false)

        state = state.afterPlaybackChanged(isPlaying = false)
        assertTrue(state.visible)
        assertEquals(state, state.afterAutoHide(isPlaying = false))
    }

    @Test
    fun controlInteractionRestartsAutoHideEpoch() {
        var state = ProviderVideoControlsVisibilityState()

        state = state.afterInteraction()
        val firstEpoch = state.interactionEpoch
        state = state.afterInteraction()

        assertTrue(state.visible)
        assertEquals(firstEpoch + 1L, state.interactionEpoch)
    }
}
