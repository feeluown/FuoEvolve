package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackQueueCodecSmartReplacementTest {
    @Test
    fun persistedSmartReplacementKeepsOriginalIdentityForRestartResolution() {
        val track = MusicTrack(
            id = "netease:123",
            title = "原始标题",
            artists = "原始歌手",
            album = "原始专辑",
            source = "qqmusic",
            sourceType = TrackSourceType.Provider,
            providerId = "netease:123",
            providerName = "QQ 音乐",
            isSmartReplacement = true,
            originalId = "netease:123",
            originalTitle = "原始标题",
            originalArtists = "原始歌手",
            originalAlbum = "原始专辑",
            originalSource = "netease",
            originalProviderName = "网易云音乐",
            replacementId = "qqmusic:456",
            replacementSource = "qqmusic",
            replacementProviderName = "QQ 音乐",
        )
        val snapshot = PlaybackQueueSnapshot(
            mainQueue = listOf(track),
            originalMainQueue = listOf(track),
            queueIndex = 0,
        )

        val restored = PlaybackQueueCodec.decode(PlaybackQueueCodec.encode(snapshot)).mainQueue.single()
        val resolutionTrack = restored.forPlaybackResolution()

        assertTrue(restored.isSmartReplacement)
        assertEquals("qqmusic", restored.source)
        assertEquals("netease", resolutionTrack.source)
        assertEquals("netease:123", resolutionTrack.id)
        assertEquals("netease:123", resolutionTrack.providerId)
    }
}
