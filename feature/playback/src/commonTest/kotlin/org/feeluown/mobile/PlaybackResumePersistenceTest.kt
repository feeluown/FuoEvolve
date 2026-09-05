package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackResumePersistenceTest {
    @Test
    fun resumeSnapshotRoundTripsLogicalTrackPartsAndQueueEntryIdentity() {
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
            currentQueueEntryId = 42L,
        )

        val decoded = PlaybackResumeCodec.decode(PlaybackResumeCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
        assertEquals(PlayerStatus.Paused, decoded?.toPlaybackState()?.status)
        assertEquals(42_000L, decoded?.toPlaybackState()?.positionMs)
        assertEquals(42L, decoded?.toPlaybackState()?.playbackQueueEntryId)
    }

    @Test
    fun legacyResumePayloadWithoutQueueEntryIdentityStillDecodes() {
        val track = MusicTrack(
            id = "song:legacy",
            title = "Legacy",
            artists = "Artist",
            album = "Album",
            source = "netease",
            sourceType = TrackSourceType.Provider,
            providerId = "song:legacy",
        )
        val encoded = PlaybackResumeCodec.encode(
            PlaybackResumeSnapshot(
                currentTrack = track,
                positionMs = 1_000L,
                durationMs = 2_000L,
            )
        )
        val legacy = encoded.replace("1000\t2000\t-1\t\n", "1000\t2000\t-1\n")

        val decoded = PlaybackResumeCodec.decode(legacy)

        assertEquals(track, decoded?.currentTrack)
        assertNull(decoded?.currentQueueEntryId)
    }

    @Test
    fun invalidResumePayloadIsIgnored() {
        assertNull(PlaybackResumeCodec.decode("broken"))
    }
}