package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackResumePersistenceTest {
    @Test
    fun resumeSnapshotRoundTripsLogicalTrackAndParts() {
        val track = MusicTrack(
            id = "bilibili:BV1",
            title = "Title",
            artists = "Artist",
            album = "Album",
            source = "bilibili",
            sourceType = TrackSourceType.Provider,
            coverUrl = "https://example.invalid/cover.jpg",
            durationMs = 180_000L,
            providerId = "bilibili:BV1",
            providerName = "哔哩哔哩",
        )
        val snapshot = PlaybackResumeSnapshot(
            currentTrack = track,
            positionMs = 42_000L,
            durationMs = 180_000L,
            playbackParts = listOf(
                PlaybackPart("part-1", "第一段", 90_000L),
                PlaybackPart("part-2", "第二段\tLive", 90_000L),
            ),
            currentPartIndex = 1,
        )

        val decoded = PlaybackResumeCodec.decode(PlaybackResumeCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
        assertEquals(PlayerStatus.Paused, decoded?.toPlaybackState()?.status)
        assertEquals(42_000L, decoded?.toPlaybackState()?.positionMs)
    }

    @Test
    fun invalidResumePayloadIsIgnored() {
        assertNull(PlaybackResumeCodec.decode("broken"))
    }
}
