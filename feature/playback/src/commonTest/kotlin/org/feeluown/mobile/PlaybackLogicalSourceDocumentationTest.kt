package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackLogicalSourceDocumentationTest {
    @Test
    fun resolverCompatibilityTrackIsExplicitlyDifferentFromLogicalTrack() {
        val logical = MusicTrack(
            id = "netease:1",
            title = "Song",
            artists = "Artist",
            album = "",
            source = "netease",
            sourceType = TrackSourceType.Provider,
            providerId = "netease:1",
        )
        val resolverInput = logical.withReplacementSelection(
            SmartReplacementSelection(
                replacementId = "qqmusic:1",
                replacementTitle = "Song",
                replacementArtists = "Artist",
                replacementSource = "qqmusic",
                replacementScore = 0.9,
            )
        )

        assertTrue(resolverInput.isSmartReplacement)
        assertEquals("qqmusic:1", resolverInput.replacementId)
        val normalized = resolverInput.logicalPlaybackTrack()
        assertEquals(logical.id, normalized.id)
        assertFalse(normalized.isSmartReplacement)
    }
}
