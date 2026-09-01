package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackResolvedSourceMultipartTest {
    @Test
    fun replacementPartsDoNotChangeLogicalQueueTrack() {
        val logical = logicalTrack()
        val controller = PlaybackQueueController().apply {
            mainQueue = listOf(logical)
            mainQueueIndex = 0
        }
        val payload = replacementPayload()

        val source = payload.toResolvedPlaybackSource(logical)

        assertTrue(source.isReplacement)
        assertEquals("bilibili:video", source.trackId)
        assertEquals("netease:logical", controller.currentTrack()?.id)
        assertFalse(controller.currentTrack()?.isSmartReplacement == true)
    }

    @Test
    fun selectedReplacementPartUsesPartSpecificPhysicalIdentity() {
        val logical = logicalTrack()
        val resolverInput = logical.withReplacementSelection(
            SmartReplacementSelection(
                replacementId = "bilibili:video",
                replacementTitle = "Physical",
                replacementArtists = "Artist",
                replacementSource = "bilibili",
            )
        ).copy(
            id = "bilibili:video:p2",
            title = "P2",
            providerId = "bilibili:video:p2",
        )

        val source = replacementPayload().toResolvedPlaybackSource(logical, resolverInput)

        assertTrue(source.isReplacement)
        assertEquals("bilibili:video:p2", source.trackId)
        assertEquals("P2", source.title)
        assertEquals("bilibili", source.source)
    }

    private fun logicalTrack(): MusicTrack = MusicTrack(
        id = "netease:logical",
        title = "Logical",
        artists = "Artist",
        album = "Album",
        source = "netease",
        sourceType = TrackSourceType.Provider,
        providerId = "netease:logical",
    )

    private fun replacementPayload(): PlaybackPayload = PlaybackPayload(
        url = "https://example.test/p1.mp3",
        title = "Physical",
        artists = "Artist",
        album = "Physical album",
        source = "bilibili",
        isSmartReplacement = true,
        replacementId = "bilibili:video",
        replacementTitle = "Physical",
        replacementArtists = "Artist",
        replacementSource = "bilibili",
        parts = listOf(
            PlaybackPart(id = "bilibili:video:p1", title = "P1"),
            PlaybackPart(id = "bilibili:video:p2", title = "P2"),
        ),
    )
}
