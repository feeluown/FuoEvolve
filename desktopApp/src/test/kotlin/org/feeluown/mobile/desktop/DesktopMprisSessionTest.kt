package org.feeluown.mobile.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionState
import org.feeluown.mobile.playback.api.PlaybackSessionStatus
import org.freedesktop.dbus.DBusPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMprisSessionTest {
    @Test
    fun publishesPlaybackMetadataAndCapabilities() {
        val session = FakePlaybackSession(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Playing,
                currentTrack = track("track-a"),
                positionMs = 12_000,
                durationMs = 180_000,
                queueTrackIds = listOf("track-a", "track-b"),
                queueIndex = 0,
            ),
        )
        val objectUnderTest = LinuxMprisObject(session) { }

        assertEquals("Playing", objectUnderTest.getPlaybackStatus())
        assertEquals(12_000_000L, objectUnderTest.getPosition())
        assertEquals("Track track-a", objectUnderTest.getMetadata().getValue("xesam:title").value)
        assertEquals(mprisTrackPath("track-a"), objectUnderTest.getMetadata().getValue("mpris:trackid").value)
        assertTrue(objectUnderTest.getCanGoNext())
        assertFalse(objectUnderTest.getCanGoPrevious())
        assertTrue(objectUnderTest.getCanPlay())
        assertTrue(objectUnderTest.getCanPause())
        assertTrue(objectUnderTest.getCanSeek())
    }

    @Test
    fun routesTransportAndSeekThroughPlaybackSession() {
        val session = FakePlaybackSession(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Paused,
                currentTrack = track("track-a"),
                positionMs = 10_000,
                durationMs = 100_000,
                queueTrackIds = listOf("track-a", "track-b"),
                queueIndex = 0,
            ),
        )
        val seeked = mutableListOf<Long>()
        val objectUnderTest = LinuxMprisObject(session, seeked::add)

        objectUnderTest.Play()
        objectUnderTest.Pause()
        objectUnderTest.PlayPause()
        objectUnderTest.Stop()
        objectUnderTest.Next()
        objectUnderTest.Previous()
        objectUnderTest.Seek(5_000_000L)
        objectUnderTest.SetPosition(mprisTrackPath("track-a"), 42_000_000L)
        objectUnderTest.SetPosition(DBusPath("/stale/track"), 55_000_000L)

        assertEquals(1, session.playCalls)
        assertEquals(1, session.pauseCalls)
        assertEquals(1, session.toggleCalls)
        assertEquals(1, session.stopCalls)
        assertEquals(1, session.nextCalls)
        assertEquals(1, session.previousCalls)
        assertEquals(listOf(15_000L, 42_000L), session.seekPositions)
        assertEquals(listOf(15_000_000L, 42_000_000L), seeked)
    }

    @Test
    fun publishesOnlyNonPositionPropertyChanges() {
        val base = PlaybackSessionState(
            status = PlaybackSessionStatus.Playing,
            currentTrack = track("track-a"),
            positionMs = 10_000,
            durationMs = 100_000,
            queueTrackIds = listOf("track-a", "track-b"),
            queueIndex = 0,
        )

        assertTrue(mprisChangedProperties(base, base.copy(positionMs = 20_000)).isEmpty())

        val changed = mprisChangedProperties(
            base,
            base.copy(status = PlaybackSessionStatus.Paused, queueIndex = 1),
        )
        assertEquals("Paused", changed.getValue("PlaybackStatus").value)
        assertTrue("CanGoNext" in changed)
        assertTrue("CanGoPrevious" in changed)
    }

    private fun track(id: String) = TrackRef(
        id = id,
        title = "Track $id",
        artists = "Artist",
        album = "Album",
        source = "test",
        coverUrl = "https://example.com/$id.jpg",
        durationMs = 180_000,
    )

    private class FakePlaybackSession(initial: PlaybackSessionState) : PlaybackSession {
        override val state = MutableStateFlow(initial)
        var playCalls = 0
        var pauseCalls = 0
        var toggleCalls = 0
        var stopCalls = 0
        var previousCalls = 0
        var nextCalls = 0
        val seekPositions = mutableListOf<Long>()

        override fun toggle() { toggleCalls += 1 }
        override fun play() { playCalls += 1 }
        override fun pause() { pauseCalls += 1 }
        override fun stop() { stopCalls += 1 }
        override fun previous() { previousCalls += 1 }
        override fun next() { nextCalls += 1 }
        override fun seekTo(positionMs: Long) { seekPositions += positionMs }
    }
}
