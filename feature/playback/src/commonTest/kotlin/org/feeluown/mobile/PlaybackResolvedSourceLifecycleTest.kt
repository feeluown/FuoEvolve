package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertNull

class PlaybackResolvedSourceLifecycleTest {
    @Test
    fun newLogicalStartMayClearResolvedSourceBeforeResolution() {
        val previous = PlaybackState(
            resolvedSource = ResolvedPlaybackSource(
                trackId = "qqmusic:old",
                title = "Old",
                artists = "",
                album = "",
                source = "qqmusic",
                sourceType = TrackSourceType.Provider,
                isReplacement = true,
            ),
        )

        val loading = previous.copy(resolvedSource = null)

        assertNull(loading.resolvedSource)
    }
}
