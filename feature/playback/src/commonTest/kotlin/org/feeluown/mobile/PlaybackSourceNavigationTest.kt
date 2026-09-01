package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackSourceNavigationTest {
    @Test
    fun resolvedSourceBuildsProviderNativeDetailTrack() {
        val logical = MusicTrack(
            id = "netease:logical",
            title = "Logical",
            artists = "Logical artist",
            album = "Logical album",
            source = "netease",
            sourceType = TrackSourceType.Provider,
            providerId = "netease:logical",
        )
        val source = ResolvedPlaybackSource(
            trackId = "qqmusic:physical",
            title = "Physical",
            artists = "Physical artist",
            album = "Album",
            source = "qqmusic",
            sourceType = TrackSourceType.Provider,
            providerName = "QQ Music",
            isReplacement = true,
        )

        val detail = source.toNavigationTrack(logical)

        assertEquals("qqmusic:physical", detail.id)
        assertEquals("Physical", detail.title)
        assertEquals("qqmusic", detail.source)
        assertEquals("QQ Music", detail.providerName)
    }
}
