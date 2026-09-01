package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResolvedPlaybackSourceTest {
    @Test
    fun legacyReplacementNormalizesBackToLogicalTrack() {
        val decorated = replacementTrack()

        val logical = decorated.logicalPlaybackTrack()

        assertEquals("netease:logical", logical.id)
        assertEquals("Logical title", logical.title)
        assertEquals("netease", logical.source)
        assertFalse(logical.isSmartReplacement)
        assertNull(logical.replacementId)
        assertNull(logical.originalId)
    }

    @Test
    fun queueNeverStoresReplacementDecoratedTrack() {
        val controller = PlaybackQueueController()
        controller.mainQueue = listOf(replacementTrack())
        controller.mainQueueIndex = 0

        val current = controller.currentTrack()!!

        assertEquals("netease:logical", current.id)
        assertFalse(current.isSmartReplacement)
        assertNull(current.replacementId)
        assertEquals(listOf("netease:logical"), controller.snapshot().mainQueue.map { it.id })
    }

    @Test
    fun payloadKeepsPhysicalReplacementOutsideLogicalTrack() {
        val logical = MusicTrack(
            id = "netease:logical",
            title = "Logical title",
            artists = "Logical artist",
            album = "Logical album",
            source = "netease",
            sourceType = TrackSourceType.Provider,
            providerId = "netease:logical",
        )
        val resolveTrack = logical.copy(
            isSmartReplacement = true,
            originalId = logical.id,
            originalTitle = logical.title,
            originalArtists = logical.artists,
            originalSource = logical.source,
            replacementId = "qqmusic:physical",
            replacementTitle = "Physical title",
            replacementArtists = "Physical artist",
            replacementSource = "qqmusic",
            replacementProviderName = "QQ Music",
        )
        val payload = PlaybackPayload(
            url = "https://example.test/audio.mp3",
            title = "Physical title",
            artists = "Physical artist",
            album = "Physical album",
            source = "qqmusic",
            replacementId = "qqmusic:physical",
            replacementTitle = "Physical title",
            replacementArtists = "Physical artist",
            replacementSource = "qqmusic",
            replacementProviderName = "QQ Music",
        )

        val resolved = payload.toResolvedPlaybackSource(logical, resolveTrack)

        assertEquals("netease:logical", logical.id)
        assertFalse(logical.isSmartReplacement)
        assertTrue(resolved.isReplacement)
        assertEquals("qqmusic:physical", resolved.trackId)
        assertEquals("qqmusic", resolved.source)
        assertEquals("QQ Music", resolved.providerName)
    }

    private fun replacementTrack(): MusicTrack = MusicTrack(
        id = "netease:logical",
        title = "Logical title",
        artists = "Logical artist",
        album = "Logical album",
        source = "netease",
        sourceType = TrackSourceType.Provider,
        providerId = "netease:logical",
        providerName = "NetEase Cloud Music",
        isSmartReplacement = true,
        originalId = "netease:logical",
        originalTitle = "Logical title",
        originalArtists = "Logical artist",
        originalSource = "netease",
        originalProviderName = "NetEase Cloud Music",
        replacementId = "qqmusic:physical",
        replacementTitle = "Physical title",
        replacementArtists = "Physical artist",
        replacementSource = "qqmusic",
        replacementProviderName = "QQ Music",
        replacementStrategy = "user_selected",
        replacementScore = 0.98,
    )
}
