package org.feeluown.mobile.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import org.feeluown.mobile.RepeatMode
import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionState
import org.feeluown.mobile.playback.api.PlaybackSessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopMprisStatePreservationTest {
    @Test
    fun nextWhilePausedKeepsPlaybackPaused() {
        val session = StatePreservingPlaybackSession(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Paused,
                currentTrack = track("a"),
                durationMs = 100_000L,
                canGoNext = true,
            ),
        )
        val objectUnderTest = LinuxMprisObject(session, onSeeked = {})

        objectUnderTest.Next()

        assertEquals(1, session.nextCalls)
        assertEquals(1, session.pauseCalls)
        assertEquals(0, session.stopCalls)
    }

    @Test
    fun previousWhilePausedKeepsPlaybackPaused() {
        val session = StatePreservingPlaybackSession(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Paused,
                currentTrack = track("b"),
                durationMs = 100_000L,
                canGoPrevious = true,
            ),
        )
        val objectUnderTest = LinuxMprisObject(session, onSeeked = {})

        objectUnderTest.Previous()

        assertEquals(1, session.previousCalls)
        assertEquals(1, session.pauseCalls)
        assertEquals(0, session.stopCalls)
    }

    @Test
    fun nextWhileStoppedKeepsPlaybackStopped() {
        val session = StatePreservingPlaybackSession(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Idle,
                currentTrack = track("a"),
                canGoNext = true,
            ),
        )
        val objectUnderTest = LinuxMprisObject(session, onSeeked = {})

        objectUnderTest.Next()

        assertEquals(1, session.nextCalls)
        assertEquals(0, session.pauseCalls)
        assertEquals(1, session.stopCalls)
    }

    @Test
    fun disabledSkipHasNoEffect() {
        val session = StatePreservingPlaybackSession(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Paused,
                currentTrack = track("a"),
                canGoNext = false,
                canGoPrevious = false,
            ),
        )
        val objectUnderTest = LinuxMprisObject(session, onSeeked = {})

        objectUnderTest.Next()
        objectUnderTest.Previous()

        assertEquals(0, session.nextCalls)
        assertEquals(0, session.previousCalls)
        assertEquals(0, session.pauseCalls)
        assertEquals(0, session.stopCalls)
    }

    @Test
    fun seekPastEndUsesStatePreservingNext() {
        val session = StatePreservingPlaybackSession(
            PlaybackSessionState(
                status = PlaybackSessionStatus.Paused,
                currentTrack = track("a"),
                positionMs = 99_000L,
                durationMs = 100_000L,
                canGoNext = true,
            ),
        )
        val objectUnderTest = LinuxMprisObject(session, onSeeked = {})

        objectUnderTest.Seek(2_000_000L)

        assertEquals(1, session.nextCalls)
        assertEquals(1, session.pauseCalls)
        assertEquals(emptyList(), session.seekPositions)
    }

    private fun track(id: String) = TrackRef(
        id = id,
        title = "Track $id",
        artists = "Artist",
        album = "Album",
        source = "test",
        durationMs = 100_000L,
    )
}

private class StatePreservingPlaybackSession(initial: PlaybackSessionState) : PlaybackSession {
    override val state = MutableStateFlow(initial)

    var toggleCalls = 0
    var playCalls = 0
    var pauseCalls = 0
    var stopCalls = 0
    var previousCalls = 0
    var nextCalls = 0
    val seekPositions = mutableListOf<Long>()
    val repeatModes = mutableListOf<RepeatMode>()
    val shuffleValues = mutableListOf<Boolean>()

    override fun toggle() {
        toggleCalls += 1
    }

    override fun play() {
        playCalls += 1
    }

    override fun pause() {
        pauseCalls += 1
    }

    override fun stop() {
        stopCalls += 1
    }

    override fun previous() {
        previousCalls += 1
    }

    override fun next() {
        nextCalls += 1
    }

    override fun seekTo(positionMs: Long) {
        seekPositions += positionMs
    }

    override fun setRepeatMode(mode: RepeatMode) {
        repeatModes += mode
    }

    override fun setShuffleEnabled(enabled: Boolean) {
        shuffleValues += enabled
    }
}
