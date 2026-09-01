package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackStateLogicalIdentityTest {
    @Test
    fun resolvedSourceCanChangeWithoutChangingLogicalTrack() {
        val logical = MusicTrack(
            id = "netease:logical",
            title = "Logical",
            artists = "Artist",
            album = "Album",
            source = "netease",
            sourceType = TrackSourceType.Provider,
            providerId = "netease:logical",
        )
        val state = PlaybackState(
            currentTrack = logical,
            resolvedSource = ResolvedPlaybackSource(
                trackId = "bilibili:physical",
                title = "Physical",
                artists = "Artist",
                album = "",
                source = "bilibili",
                sourceType = TrackSourceType.Provider,
                isReplacement = true,
            ),
        )

        assertEquals("netease:logical", state.currentTrack?.id)
        assertFalse(state.currentTrack?.isSmartReplacement == true)
        assertEquals("bilibili:physical", state.resolvedSource?.trackId)
        assertTrue(state.resolvedSource?.isReplacement == true)
    }
}
