package org.feeluown.mobile.playback.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultPlaybackRuntimeTest {
    @Test
    fun mergesEngineAndQueuePresentationIntoSessionState() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = FakeEngine(
            PlaybackRuntimeEngineState(
                status = PlaybackSessionStatus.Playing,
                positionMs = 12_000,
                durationMs = 180_000,
                bufferedMs = 30_000,
            ),
        )
        val overlay = MutableStateFlow(
            PlaybackRuntimeOverlay(
                currentTrack = track("a"),
                lyrics = "merged lyrics",
                lyricsAlignmentOffsetMs = 1_250,
                queueTrackIds = listOf("a", "b"),
                queueIndex = 0,
            ),
        )
        val runtime = DefaultPlaybackRuntime(engine, overlay, FakeQueueActions(), scope)

        assertEquals(PlaybackSessionStatus.Playing, runtime.state.value.status)
        assertEquals("a", runtime.state.value.currentTrack?.id)
        assertEquals(12_000, runtime.state.value.positionMs)
        assertEquals(10_750, runtime.state.value.lyricsPositionMs)
        assertEquals(1_250, runtime.state.value.lyricsAlignmentOffsetMs)
        assertEquals(180_000, runtime.state.value.durationMs)
        assertEquals("merged lyrics", runtime.state.value.lyrics)
        assertEquals(listOf("a", "b"), runtime.state.value.queueTrackIds)

        overlay.value = overlay.value.copy(
            currentTrack = track("b"),
            lyrics = null,
            lyricsAlignmentOffsetMs = -500,
            queueTrackIds = listOf("b"),
        )

        assertEquals("b", runtime.state.value.currentTrack?.id)
        assertEquals(12_500, runtime.state.value.lyricsPositionMs)
        assertEquals(-500, runtime.state.value.lyricsAlignmentOffsetMs)
        assertEquals(null, runtime.state.value.lyrics)
        assertEquals(listOf("b"), runtime.state.value.queueTrackIds)
        scope.cancel()
    }

    @Test
    fun ownsTransportPolicyAndLeavesOnlyQueueTransitionsToBridge() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = FakeEngine(
            PlaybackRuntimeEngineState(status = PlaybackSessionStatus.Playing),
        )
        val overlay = MutableStateFlow(
            PlaybackRuntimeOverlay(currentTrack = track("a")),
        )
        val queueActions = FakeQueueActions()
        val runtime = DefaultPlaybackRuntime(engine, overlay, queueActions, scope)

        runtime.toggle()
        assertEquals(1, engine.pauseCalls)

        engine.mutableState.value = PlaybackRuntimeEngineState(status = PlaybackSessionStatus.Paused)
        runtime.play()
        assertEquals(1, engine.resumeCalls)

        engine.mutableState.value = PlaybackRuntimeEngineState(status = PlaybackSessionStatus.Idle)
        runtime.play()
        assertEquals(1, queueActions.startCurrentCalls)

        runtime.previous()
        runtime.next()
        assertEquals(1, queueActions.previousCalls)
        assertEquals(1, queueActions.nextCalls)
        scope.cancel()
    }

    private fun track(id: String) = TrackRef(
        id = id,
        title = "Track $id",
        artists = "Artist",
        album = "Album",
        source = "test",
    )

    private class FakeEngine(initial: PlaybackRuntimeEngineState) : PlaybackRuntimeEngine {
        val mutableState = MutableStateFlow(initial)
        override val state = mutableState
        var pauseCalls = 0
        var resumeCalls = 0

        override fun pause() {
            pauseCalls += 1
        }

        override fun resume() {
            resumeCalls += 1
        }
    }

    private class FakeQueueActions : PlaybackRuntimeQueueActions {
        var startCurrentCalls = 0
        var previousCalls = 0
        var nextCalls = 0

        override fun startCurrent() {
            startCurrentCalls += 1
        }

        override fun previous() {
            previousCalls += 1
        }

        override fun next() {
            nextCalls += 1
        }
    }
}
