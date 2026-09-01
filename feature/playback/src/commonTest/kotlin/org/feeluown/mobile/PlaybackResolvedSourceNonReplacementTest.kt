package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlaybackResolvedSourceNonReplacementTest {
    @Test
    fun ordinaryPayloadProducesNonReplacementSource() {
        val logical = MusicTrack(
            id = "netease:1",
            title = "Song",
            artists = "Artist",
            album = "Album",
            source = "netease",
            sourceType = TrackSourceType.Provider,
            providerId = "netease:1",
        )
        val payload = PlaybackPayload(
            url = "https://example.test/song.mp3",
            title = "Song",
            artists = "Artist",
            album = "Album",
            source = "netease",
            providerName = "NetEase Cloud Music",
        )

        val source = payload.toResolvedPlaybackSource(logical)

        assertFalse(source.isReplacement)
        assertEquals(logical.id, source.trackId)
        assertEquals("netease", source.source)
    }
}
