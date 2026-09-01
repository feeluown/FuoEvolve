package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackResolvedSourceAdapterTest {
    @Test
    fun legacyEngineTrackProjectsPhysicalReplacementSource() {
        val engineTrack = MusicTrack(
            id = "netease:logical",
            title = "Logical",
            artists = "Artist",
            album = "",
            source = "netease",
            sourceType = TrackSourceType.Provider,
            providerId = "netease:logical",
            isSmartReplacement = true,
            originalId = "netease:logical",
            originalTitle = "Logical",
            originalArtists = "Artist",
            originalSource = "netease",
            replacementId = "bilibili:physical",
            replacementTitle = "Physical",
            replacementArtists = "Artist",
            replacementSource = "bilibili",
            replacementProviderName = "Bilibili",
        )

        val source = engineTrack.toLegacyResolvedPlaybackSource()

        assertTrue(source.isReplacement)
        assertEquals("bilibili:physical", source.trackId)
        assertEquals("bilibili", source.source)
        assertEquals("Bilibili", source.providerName)
    }
}
