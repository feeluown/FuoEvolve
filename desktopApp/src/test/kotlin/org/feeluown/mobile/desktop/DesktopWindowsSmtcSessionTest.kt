package org.feeluown.mobile.desktop

import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSessionState
import org.feeluown.mobile.playback.api.PlaybackSessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWindowsSmtcSessionTest {
    @Test
    fun projectsPlaybackStateAndQueueCapabilities() {
        val projected = windowsSmtcProjection(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Playing,
                currentTrack = track("a"),
                positionMs = 12_000,
                durationMs = 180_000,
                queueTrackIds = listOf("a", "b"),
                queueIndex = 0,
            ),
        )

        assertEquals(WindowsSmtcNative.STATUS_PLAYING, projected.status)
        assertEquals(12_000, projected.positionMs)
        assertEquals(180_000, projected.durationMs)
        assertTrue(projected.hasTrack)
        assertTrue(projected.canPlay)
        assertTrue(projected.canPause)
        assertTrue(projected.canNext)
        assertFalse(projected.canPrevious)
        assertEquals("a", projected.metadata?.trackId)
        assertEquals("Track a", projected.metadata?.title)
    }

    @Test
    fun usesTrackDurationAndClearsMetadataWithoutCurrentTrack() {
        val withTrackDuration = windowsSmtcProjection(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Paused,
                currentTrack = track("a", durationMs = 90_000),
                durationMs = 0,
            ),
        )
        assertEquals(90_000, withTrackDuration.durationMs)
        assertEquals(WindowsSmtcNative.STATUS_PAUSED, withTrackDuration.status)

        val idle = windowsSmtcProjection(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Idle,
                currentTrack = null,
                queueTrackIds = emptyList(),
            ),
        )
        assertEquals(WindowsSmtcNative.STATUS_STOPPED, idle.status)
        assertFalse(idle.hasTrack)
        assertFalse(idle.canPlay)
        assertFalse(idle.canPause)
        assertEquals(null, idle.metadata)
    }

    @Test
    fun exposesLoadingAsChangingForWindowsSystemUi() {
        val projected = windowsSmtcProjection(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Loading,
                currentTrack = track("a"),
            ),
        )
        assertEquals(WindowsSmtcNative.STATUS_CHANGING, projected.status)
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
