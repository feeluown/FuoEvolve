package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackResolvedSourceModelTest {
    @Test
    fun resolvedSourceCarriesPhysicalMetadata() {
        val source = ResolvedPlaybackSource(
            trackId = "qqmusic:1",
            title = "Song",
            artists = "Artist",
            album = "Album",
            source = "qqmusic",
            sourceType = TrackSourceType.Provider,
            providerName = "QQ Music",
            url = "https://example.test/song.mp3",
            replacementStrategy = "user_selected",
            replacementScore = 0.97,
            isReplacement = true,
        )

        assertEquals("qqmusic:1", source.trackId)
        assertEquals("https://example.test/song.mp3", source.url)
        assertEquals("user_selected", source.replacementStrategy)
    }
}
