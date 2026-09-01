package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackResolvedSourceIdentityTest {
    @Test
    fun logicalAndPhysicalIdsMayDifferByDesign() {
        val logical = MusicTrack(
            id = "netease:logical",
            title = "Song",
            artists = "",
            album = "",
            source = "netease",
            sourceType = TrackSourceType.Provider,
        )
        val source = ResolvedPlaybackSource(
            trackId = "qqmusic:physical",
            title = "Song",
            artists = "",
            album = "",
            source = "qqmusic",
            sourceType = TrackSourceType.Provider,
            isReplacement = true,
        )

        assertEquals("netease:logical", logical.id)
        assertEquals("qqmusic:physical", source.trackId)
    }
}
