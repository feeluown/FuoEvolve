package org.feeluown.mobile.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import org.feeluown.mobile.RepeatMode
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
                canGoNext = true,
                canGoPrevious = true,
                repeatMode = RepeatMode.SINGLE,
                shuffleEnabled = true,
            ),
        )
        val objectUnderTest = LinuxMprisObject(session) { }

        assertEquals("Playing", objectUnderTest.getPlaybackStatus())
        assertEquals("Track", objectUnderTest.getLoopStatus())
        assertTrue(objectUnderTest.getShuffle())
        assertEquals(12_000_000L, objectUnderTest.getPosition())
        assertEquals("Track track-a", objectUnderTest.getMetadata().getValue("xesam:title").value)
        assertEquals(mprisTrackPath("track-a"), objectUnderTest.getMetadata().getValue("mpris:trackid").value)
        assertEquals("https://example.com/track-a.jpg", objectUnderTest.getMetadata().getValue("mpris:artUrl").value)
        assertTrue(objectUnderTest.getCanGoNext())
        assertTrue(objectUnderTest.getCanGoPrevious())
        assertTrue(objectUnderTest.getCanPlay())
        assertTrue(objectUnderTest.getCanPause())
        assertTrue(objectUnderTest.getCanSeek())
    }

    @Test
    fun routesTransportSeekAndPlaybackModesThroughPlaybackSession() {
        val session = FakePlaybackSession(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Paused,
                currentTrack = track("track-a"),
                positionMs = 10_000,
                durationMs = 100_000,
                queueTrackIds = listOf("track-a", "track-b"),
                queueIndex = 0,
                repeatMode = RepeatMode.OFF,
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
        objectUnderTest.setLoopStatus("Playlist")
        objectUnderTest.setShuffle(true)

        assertEquals(1, session.playCalls)
        assertEquals(1, session.pauseCalls)
        assertEquals(1, session.toggleCalls)
        assertEquals(1, session.stopCalls)
        assertEquals(1, session.nextCalls)
        assertEquals(1, session.previousCalls)
        assertEquals(listOf(15_000L, 42_000L), session.seekPositions)
        assertEquals(listOf(15_000_000L, 42_000_000L), seeked)
        assertEquals(listOf(RepeatMode.QUEUE), session.repeatModes)
        assertEquals(listOf(true), session.shuffleValues)
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
            canGoNext = true,
            canGoPrevious = false,
            repeatMode = RepeatMode.OFF,
            shuffleEnabled = false,
        )

        assertTrue(mprisChangedProperties(base, base.copy(positionMs = 20_000)).isEmpty())

        val changed = mprisChangedProperties(
            base,
            base.copy(
                status = PlaybackSessionStatus.Paused,
                canGoNext = false,
                canGoPrevious = true,
                repeatMode = RepeatMode.QUEUE,
                shuffleEnabled = true,
            ),
        )
        assertEquals("Paused", changed.getValue("PlaybackStatus").value)
        assertEquals("Playlist", changed.getValue("LoopStatus").value)
        assertEquals(true, changed.getValue("Shuffle").value)
        assertTrue("CanGoNext" in changed)
        assertTrue("CanGoPrevious" in changed)
        assertTrue("CanPause" in changed)
    }

    @Test
    fun ignoresPlaybackModeWritesWhenQueuePolicyLocksThem() {
        val session = FakePlaybackSession(
            PlaybackSessionState(
                repeatMode = RepeatMode.QUEUE,
                shuffleEnabled = false,
                canChangePlaybackMode = false,
            ),
        )
        val objectUnderTest = LinuxMprisObject(session) { }

        objectUnderTest.setLoopStatus("Track")
        objectUnderTest.setShuffle(true)

        assertTrue(session.repeatModes.isEmpty())
        assertTrue(session.shuffleValues.isEmpty())
    }

    @Test
    fun pauseAndSeekCapabilitiesFollowActivePlaybackStatus() {
        val loading = FakePlaybackSession(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Loading,
                currentTrack = track("track-a"),
                durationMs = 100_000,
            ),
        )
        val loadingObject = LinuxMprisObject(loading) { }
        assertFalse(loadingObject.getCanPause())
        assertFalse(loadingObject.getCanSeek())

        loading.state.value = loading.state.value.copy(status = PlaybackSessionStatus.Paused)
        assertFalse(loadingObject.getCanPause())
        assertTrue(loadingObject.getCanSeek())
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
        val repeatModes = mutableListOf<RepeatMode>()
        val shuffleValues = mutableListOf<Boolean>()

        override fun toggle() { toggleCalls += 1 }
        override fun play() { playCalls += 1 }
        override fun pause() { pauseCalls += 1 }
        override fun stop() { stopCalls += 1 }
        override fun previous() { previousCalls += 1 }
        override fun next() { nextCalls += 1 }
        override fun seekTo(positionMs: Long) { seekPositions += positionMs }
        override fun setRepeatMode(mode: RepeatMode) { repeatModes += mode }
        override fun setShuffleEnabled(enabled: Boolean) { shuffleValues += enabled }
    }
}
