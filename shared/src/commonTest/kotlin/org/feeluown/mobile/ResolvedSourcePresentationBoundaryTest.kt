package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ResolvedSourcePresentationBoundaryTest {
    @Test
    fun presentationResolverAlwaysReturnsLogicalIdentity() {
        val engineTrack = MusicTrack(
            id = "netease:logical",
            title = "Physical display",
            artists = "Artist",
            album = "Album",
            source = "qqmusic",
            sourceType = TrackSourceType.Provider,
            providerId = "netease:logical",
            isSmartReplacement = true,
            originalId = "netease:logical",
            originalTitle = "Logical title",
            originalArtists = "Artist",
            originalSource = "netease",
            replacementId = "qqmusic:physical",
            replacementTitle = "Physical display",
            replacementArtists = "Artist",
            replacementSource = "qqmusic",
        )

        val resolved = resolvePlaybackPresentationTrack(engineTrack, null)!!

        assertEquals("netease:logical", resolved.id)
        assertEquals("Logical title", resolved.title)
        assertEquals("netease", resolved.source)
        assertFalse(resolved.isSmartReplacement)
    }
}
