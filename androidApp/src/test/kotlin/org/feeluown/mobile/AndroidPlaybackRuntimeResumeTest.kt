package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidPlaybackRuntimeResumeTest {
    @Test
    fun playResumesPausedEngineWithoutRestartingCurrentQueueItem() {
        val fixture = Fixture(PlayerStatus.Paused)
        try {
            fixture.session.play()

            assertEquals(1, fixture.engine.resumeCalls)
            assertEquals(0, fixture.transport.startCurrentCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun toggleResumesPausedEngineWithoutRestartingCurrentQueueItem() {
        val fixture = Fixture(PlayerStatus.Paused)
        try {
            fixture.session.toggle()

            assertEquals(1, fixture.engine.resumeCalls)
            assertEquals(0, fixture.transport.startCurrentCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun playStartsCurrentQueueItemWhenNoSessionIsEstablished() {
        val fixture = Fixture(PlayerStatus.Idle)
        try {
            fixture.session.play()

            assertEquals(0, fixture.engine.resumeCalls)
            assertEquals(1, fixture.transport.startCurrentCalls)
        } finally {
            fixture.close()
        }
    }

    private class Fixture(status: PlayerStatus) {
        val track = MusicTrack(
            id = "provider:track",
            title = "Track",
            artists = "Artist",
            album = "Album",
            source = "provider",
            sourceType = TrackSourceType.Provider,
        )
        val engine = RecordingPlaybackEngine(
            PlaybackState(
                status = status,
                currentTrack = track.takeIf { status != PlayerStatus.Idle },
                positionMs = 42_000L,
                durationMs = 180_000L,
            ),
        )
        val transport = RecordingPlaybackTransport(track)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val playbackState = engine.state
        private val failureSource = object : PlaybackStartFailureSource {
            override val startFailure: StateFlow<PlaybackStartFailure?> = MutableStateFlow(null)
        }
        val session = createPlaybackRuntimeSession(
            playbackState = playbackState,
            playbackEngine = engine,
            transportCoordinator = transport,
            startFailureSource = failureSource,
            scope = scope,
        )

        fun close() = scope.cancel()
    }

    private class RecordingPlaybackEngine(initialState: PlaybackState) : PlaybackEngine {
        override val state: StateFlow<PlaybackState> = MutableStateFlow(initialState)
        var resumeCalls = 0
            private set

        override fun play(track: MusicTrack, payload: PlaybackPayload) = Unit
        override fun pause() = Unit
        override fun resume() {
            resumeCalls += 1
        }
        override fun stop() = Unit
        override fun seekTo(positionMs: Long) = Unit
    }

    private class RecordingPlaybackTransport(
        private val track: MusicTrack,
    ) : PlaybackTransportCoordinator {
        var startCurrentCalls = 0
            private set

        override val currentQueueTrack: MusicTrack = track
        override val queue: List<MusicTrack> = listOf(track)
        override val displayUpNextCount: Int = 0
        override val isShuffleEnabled: Boolean = false
        override val repeatMode: RepeatMode = RepeatMode.QUEUE
        override val isFmQueueActive: Boolean = false
        override val trackChangeDirection: TrackChangeDirection = TrackChangeDirection.Next

        override fun startCurrent() {
            startCurrentCalls += 1
        }
        override fun previous() = Unit
        override fun next() = Unit
        override fun playTracks(tracks: List<MusicTrack>, index: Int) = Unit
        override fun toggleShuffle() = Unit
        override fun toggleRepeat() = Unit
        override fun clearQueue() = Unit
        override fun playQueueIndex(index: Int) = Unit
        override fun removeFromQueue(track: MusicTrack) = Unit
        override fun playPlaybackPart(index: Int) = Unit
        override fun addToUpNext(track: MusicTrack) = Unit
    }
}
