package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackResolvedSourceSelectionTest {
    @Test
    fun selectedReplacementIsPhysicalEvenWhenPayloadOmitsSmartFlag() {
        val logical = MusicTrack(
            id = "netease:1",
            title = "Song",
            artists = "Artist",
            album = "Album",
            source = "netease",
            sourceType = TrackSourceType.Provider,
            providerId = "netease:1",
        )
        val resolverInput = logical.copy(
            isSmartReplacement = true,
            originalId = logical.id,
            originalTitle = logical.title,
            originalArtists = logical.artists,
            originalSource = logical.source,
            replacementId = "qqmusic:1",
            replacementTitle = "Song",
            replacementArtists = "Artist",
            replacementSource = "qqmusic",
        )
        val payload = PlaybackPayload(
            url = "https://example.test/song.mp3",
            title = "Song",
            artists = "Artist",
            album = "Album",
            source = "qqmusic",
            replacementId = "qqmusic:1",
            replacementSource = "qqmusic",
        )

        val source = payload.toResolvedPlaybackSource(logical, resolverInput)

        assertTrue(source.isReplacement)
        assertEquals("qqmusic:1", source.trackId)
    }
}
