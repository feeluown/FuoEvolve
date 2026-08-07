package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class KotlinProviderRepositoryPlaybackResolutionTest {
    @Test
    fun smartReplacementRestoresOriginalIdentityBeforeResolving() {
        val replaced = MusicTrack(
            id = "netease:123",
            title = "替换标题",
            artists = "替换歌手",
            album = "替换专辑",
            source = "qqmusic",
            sourceType = TrackSourceType.Provider,
            coverUrl = "https://example.com/replacement.jpg",
            providerId = "netease:123",
            providerName = "QQ 音乐",
            isSmartReplacement = true,
            originalId = "netease:123",
            originalTitle = "原始标题",
            originalArtists = "原始歌手",
            originalAlbum = "原始专辑",
            originalSource = "netease",
            originalProviderName = "网易云音乐",
            originalCoverUrl = "https://example.com/original.jpg",
            replacementId = "qqmusic:456",
            replacementTitle = "替换标题",
            replacementArtists = "替换歌手",
            replacementAlbum = "替换专辑",
            replacementSource = "qqmusic",
            replacementProviderName = "QQ 音乐",
        )

        val restored = replaced.forPlaybackResolution()

        assertEquals("netease:123", restored.id)
        assertEquals("netease:123", restored.providerId)
        assertEquals("netease", restored.source)
        assertEquals("网易云音乐", restored.providerName)
        assertEquals("原始标题", restored.title)
        assertEquals("原始歌手", restored.artists)
        assertEquals("原始专辑", restored.album)
        assertEquals("https://example.com/original.jpg", restored.coverUrl)
        assertFalse(restored.isSmartReplacement)
        assertNull(restored.originalId)
        assertNull(restored.replacementId)
    }

    @Test
    fun regularTrackKeepsItsPlaybackIdentity() {
        val track = MusicTrack(
            id = "qqmusic:456",
            title = "Song",
            artists = "Artist",
            album = "Album",
            source = "qqmusic",
            sourceType = TrackSourceType.Provider,
            providerId = "qqmusic:456",
            providerName = "QQ 音乐",
        )

        assertEquals(track, track.forPlaybackResolution())
    }
}
