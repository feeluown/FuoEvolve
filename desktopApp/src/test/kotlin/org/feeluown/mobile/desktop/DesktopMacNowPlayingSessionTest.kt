package org.feeluown.mobile.desktop

import org.feeluown.mobile.RepeatMode
import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSessionState
import org.feeluown.mobile.playback.api.PlaybackSessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMacNowPlayingSessionTest {
    @Test
    fun projectsPlaybackMetadataProgressQueueAndModes() {
        val projected = macNowPlayingProjection(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Playing,
                currentTrack = track("a"),
                positionMs = 12_000,
                durationMs = 180_000,
                queueTrackIds = listOf("a", "future"),
                queueIndex = 0,
                canonicalQueueTracks = listOf(track("past"), track("a"), track("up-next"), track("future")),
                canonicalQueueIndex = 1,
                canGoNext = true,
                canGoPrevious = true,
                repeatMode = RepeatMode.QUEUE,
                shuffleEnabled = true,
            ),
        )

        assertEquals(MacNowPlayingNative.STATUS_PLAYING, projected.status)
        assertEquals(12_000, projected.positionMs)
        assertEquals(180_000, projected.durationMs)
        assertEquals(1, projected.queueIndex)
        assertEquals(4, projected.queueCount)
        assertTrue(projected.hasTrack)
        assertTrue(projected.canPlay)
        assertTrue(projected.canPause)
        assertTrue(projected.canNext)
        assertTrue(projected.canPrevious)
        assertEquals(MacNowPlayingNative.REPEAT_ALL, projected.repeatMode)
        assertTrue(projected.shuffleEnabled)
        assertTrue(projected.canChangePlaybackMode)
        assertEquals("a", projected.metadata?.trackId)
        assertEquals("Track a", projected.metadata?.title)
        assertEquals("Artist", projected.metadata?.artist)
        assertEquals("Album", projected.metadata?.album)
        assertEquals("https://example.com/a.jpg", projected.metadata?.artworkUrl)
    }

    @Test
    fun fallsBackToTrackDurationAndClearsMetadataWhenIdle() {
        val paused = macNowPlayingProjection(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Paused,
                currentTrack = track("a", durationMs = 90_000),
                durationMs = 0,
                repeatMode = RepeatMode.SINGLE,
                canChangePlaybackMode = false,
            ),
        )
        assertEquals(90_000, paused.durationMs)
        assertEquals(MacNowPlayingNative.STATUS_PAUSED, paused.status)
        assertEquals(MacNowPlayingNative.REPEAT_ONE, paused.repeatMode)
        assertFalse(paused.canPause)
        assertFalse(paused.canChangePlaybackMode)

        val idle = macNowPlayingProjection(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Idle,
                currentTrack = null,
                queueTrackIds = emptyList(),
            ),
        )
        assertEquals(MacNowPlayingNative.STATUS_STOPPED, idle.status)
        assertFalse(idle.hasTrack)
        assertFalse(idle.canPlay)
        assertFalse(idle.canPause)
        assertEquals(null, idle.metadata)
    }

    @Test
    fun preservesLoadingAsSeparateNativeStatus() {
        val projected = macNowPlayingProjection(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Loading,
                currentTrack = track("a"),
            ),
        )
        assertEquals(MacNowPlayingNative.STATUS_LOADING, projected.status)
        assertFalse(projected.canPause)
    }

    private fun track(id: String, durationMs: Long = 180_000) = TrackRef(
        id = id,
        title = "Track $id",
        artists = "Artist",
        album = "Album",
        source = "test",
        coverUrl = "https://example.com/$id.jpg",
        durationMs = durationMs,
    )
}
