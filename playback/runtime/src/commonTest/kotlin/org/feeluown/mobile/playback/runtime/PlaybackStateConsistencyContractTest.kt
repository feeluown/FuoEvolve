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

/** Business invariants that must hold regardless of platform playback implementation. */
class PlaybackStateConsistencyContractTest {
    @Test
    fun sessionNeverPublishesLyricsForAWhileEnginePlaysB() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = ContractEngine(
            PlaybackRuntimeEngineState(
                status = PlaybackSessionStatus.Playing,
                currentTrack = track("b"),
                positionMs = 9_000,
            ),
        )
        val overlay = MutableStateFlow(
            PlaybackRuntimeOverlay(
                currentTrack = track("a"),
                lyrics = "A lyrics",
                queueTrackIds = listOf("a", "b"),
                queueIndex = 0,
            ),
        )
        val runtime = DefaultPlaybackRuntime(engine, overlay, ContractQueueActions, scope)

        assertEquals("b", runtime.state.value.currentTrack?.id)
        assertEquals(null, runtime.state.value.lyrics)
        assertEquals(listOf("b"), runtime.state.value.queueTrackIds)

        overlay.value = PlaybackRuntimeOverlay(
            currentTrack = track("b"),
            lyrics = "B lyrics",
            queueTrackIds = listOf("b"),
            queueIndex = 0,
        )

        assertEquals("b", runtime.state.value.currentTrack?.id)
        assertEquals("B lyrics", runtime.state.value.lyrics)
        scope.cancel()
    }

    private fun track(id: String) = TrackRef(
        id = id,
        title = "Track $id",
        artists = "Artist",
        album = "Album",
        source = "test",
    )

    private class ContractEngine(initial: PlaybackRuntimeEngineState) : PlaybackRuntimeEngine {
        override val state = MutableStateFlow(initial)
        override fun pause() = Unit
        override fun resume() = Unit
    }

    private object ContractQueueActions : PlaybackRuntimeQueueActions {
        override fun startCurrent() = Unit
        override fun previous() = Unit
        override fun next() = Unit
    }
}
