package org.feeluown.mobile.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.NoOpPlaybackResumeStore
import org.feeluown.mobile.PlaybackEngine
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlaybackState
import org.feeluown.mobile.PlayerStatus
import org.feeluown.mobile.TrackSourceType
import kotlin.test.Test
import kotlin.test.assertEquals

class PersistentDesktopPlaybackStartControlTest {
    @Test
    fun pauseWhileLoadingIsReappliedAroundResolvedStart() {
        val delegate = StartControlFakePlaybackEngine()
        val engine = PersistentDesktopPlaybackEngine(delegate, NoOpPlaybackResumeStore)
        val track = track("netease:paused-next")

        engine.prepareLoading(track)
        engine.pause()
        engine.play(track, payload(track))

        assertEquals(1, delegate.playCalls)
        assertEquals(3, delegate.pauseCalls)
        engine.close()
    }

    @Test
    fun stopWhileLoadingCancelsLateResolvedStart() {
        val delegate = StartControlFakePlaybackEngine()
        val engine = PersistentDesktopPlaybackEngine(delegate, NoOpPlaybackResumeStore)
        val track = track("qqmusic:stopped-next")

        engine.prepareLoading(track)
        engine.stop()
        engine.play(track, payload(track))

        assertEquals(0, delegate.playCalls)
        assertEquals(1, delegate.stopCalls)
        engine.close()
    }

    @Test
    fun freshPrepareAfterStopAllowsPlaybackAgain() {
        val delegate = StartControlFakePlaybackEngine()
        val engine = PersistentDesktopPlaybackEngine(delegate, NoOpPlaybackResumeStore)
        val track = track("bilibili:fresh-start")

        engine.prepareLoading(track)
        engine.stop()
        engine.play(track, payload(track))
        assertEquals(0, delegate.playCalls)

        engine.prepareLoading(track)
        engine.play(track, payload(track))

        assertEquals(1, delegate.playCalls)
        engine.close()
    }

    @Test
    fun requiresActualPositiveTimelineAdvanceBeforePublishingPlaying() = runBlocking {
        val delegate = StartControlFakePlaybackEngine()
        val engine = PersistentDesktopPlaybackEngine(delegate, NoOpPlaybackResumeStore)
        val track = track("youtube:buffering")

        engine.prepareLoading(track)
        delegate.emit(
            PlaybackState(
                status = PlayerStatus.Playing,
                currentTrack = track,
                positionMs = 0L,
                durationMs = 100_000L,
            ),
        )
        assertEquals(PlayerStatus.Loading, engine.state.value.status)

        delegate.emit(
            PlaybackState(
                status = PlayerStatus.Playing,
                currentTrack = track,
                positionMs = 10_000L,
                durationMs = 100_000L,
            ),
        )
        val firstPositive = withTimeout(1_000L) {
            engine.state.first { it.positionMs == 10_000L }
        }
        assertEquals(PlayerStatus.Loading, firstPositive.status)

        delegate.emit(
            PlaybackState(
                status = PlayerStatus.Playing,
                currentTrack = track,
                positionMs = 10_250L,
                durationMs = 100_000L,
            ),
        )
        val advanced = withTimeout(1_000L) {
            engine.state.first { it.positionMs == 10_250L }
        }
        assertEquals(PlayerStatus.Playing, advanced.status)
        engine.close()
    }

    private fun payload(track: MusicTrack) = PlaybackPayload(
        url = "https://example.test/${track.id}.mp3",
        title = track.title,
        artists = track.artists,
        album = track.album,
        source = track.source,
        durationMs = track.durationMs,
    )

    private fun track(id: String) = MusicTrack(
        id = id,
        title = "Track $id",
        artists = "Artist",
        album = "Album",
        source = id.substringBefore(':'),
        sourceType = TrackSourceType.Provider,
        durationMs = 100_000L,
        providerId = id,
        providerName = id.substringBefore(':'),
    )
}

private class StartControlFakePlaybackEngine : PlaybackEngine {
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState

    var playCalls = 0
    var pauseCalls = 0
    var stopCalls = 0

    override fun prepareLoading(track: MusicTrack) {
        mutableState.value = PlaybackState(
            status = PlayerStatus.Loading,
            currentTrack = track,
            durationMs = track.durationMs ?: 0L,
        )
    }

    override fun play(track: MusicTrack, payload: PlaybackPayload) {
        playCalls += 1
        mutableState.value = PlaybackState(
            status = PlayerStatus.Loading,
            currentTrack = track,
            durationMs = payload.durationMs ?: track.durationMs ?: 0L,
        )
    }

    override fun pause() {
        pauseCalls += 1
    }

    override fun resume() = Unit

    override fun stop() {
        stopCalls += 1
        mutableState.value = PlaybackState()
    }

    override fun seekTo(positionMs: Long) = Unit

    fun emit(state: PlaybackState) {
        mutableState.value = state
    }
}
