package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlaybackResolvedSourceLocalTest {
    @Test
    fun downloadedResolverInputDoesNotChangeLogicalProviderIdentity() {
        val logical = MusicTrack(
            id = "netease:1",
            title = "Song",
            artists = "Artist",
            album = "Album",
            source = "netease",
            sourceType = TrackSourceType.Provider,
            providerId = "netease:1",
        )
        val downloaded = logical.copy(
            sourceType = TrackSourceType.Downloaded,
            localUri = "file:///song.mp3",
        )
        val payload = PlaybackPayload(
            url = "file:///song.mp3",
            title = "Song",
            artists = "Artist",
            album = "Album",
            source = "netease",
        )

        val source = payload.toResolvedPlaybackSource(logical, downloaded)

        assertEquals("netease:1", logical.id)
        assertEquals(TrackSourceType.Provider, logical.sourceType)
        assertEquals(TrackSourceType.Downloaded, source.sourceType)
        assertFalse(source.isReplacement)
    }
}
