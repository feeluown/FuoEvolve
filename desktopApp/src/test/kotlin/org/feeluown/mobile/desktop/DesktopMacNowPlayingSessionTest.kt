package org.feeluown.mobile.desktop

import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSessionState
import org.feeluown.mobile.playback.api.PlaybackSessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMacNowPlayingSessionTest {
    @Test
    fun projectsPlaybackMetadataProgressAndQueue() {
        val projected = macNowPlayingProjection(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Playing,
                currentTrack = track("a"),
                positionMs = 12_000,
                durationMs = 180_000,
                queueTrackIds = listOf("a", "b"),
                queueIndex = 0,
            ),
        )

        assertEquals(MacNowPlayingNative.STATUS_PLAYING, projected.status)
        assertEquals(12_000, projected.positionMs)
        assertEquals(180_000, projected.durationMs)
        assertEquals(0, projected.queueIndex)
        assertEquals(2, projected.queueCount)
        assertTrue(projected.hasTrack)
        assertTrue(projected.canPlay)
        assertTrue(projected.canPause)
        assertTrue(projected.canNext)
        assertFalse(projected.canPrevious)
        assertEquals("a", projected.metadata?.trackId)
        assertEquals("Track a", projected.metadata?.title)
        assertEquals("Artist", projected.metadata?.artist)
        assertEquals("Album", projected.metadata?.album)
    }

    @Test
    fun fallsBackToTrackDurationAndClearsMetadataWhenIdle() {
        val paused = macNowPlayingProjection(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Paused,
                currentTrack = track("a", durationMs = 90_000),
                durationMs = 0,
            ),
        )
        assertEquals(90_000, paused.durationMs)
        assertEquals(MacNowPlayingNative.STATUS_PAUSED, paused.status)

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
    }

    private fun track(id: String, durationMs: Long = 180_000) = TrackRef(
        id = id,
        title = "Track $id",
        artists = "Artist",
        album = "Album",
        source = "test",
        durationMs = durationMs,
    )
}
