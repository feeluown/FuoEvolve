package org.feeluown.mobile.playback.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackRuntimeLoadingPauseTest {
    @Test
    fun pauseIsForwardedWhileLoadingAndRemainsIdempotentWhenPaused() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val track = track("a")
        val engine = LoadingPauseFakeEngine(
            PlaybackRuntimeEngineState(
                status = PlaybackSessionStatus.Loading,
                currentTrack = track,
            ),
        )
        val overlay = MutableStateFlow(PlaybackRuntimeOverlay(currentTrack = track))
        val runtime = DefaultPlaybackRuntime(engine, overlay, LoadingPauseQueueActions, scope)

        runtime.pause()
        assertEquals(1, engine.pauseCalls)

        engine.mutableState.value = engine.mutableState.value.copy(status = PlaybackSessionStatus.Paused)
        runtime.pause()
        assertEquals(2, engine.pauseCalls)

        scope.cancel()
    }

    private fun track(id: String) = TrackRef(
        id = id,
        title = "Track $id",
        artists = "Artist",
        album = "Album",
        source = "test",
    )
}

private class LoadingPauseFakeEngine(initial: PlaybackRuntimeEngineState) : PlaybackRuntimeEngine {
    val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<PlaybackRuntimeEngineState> = mutableState
    var pauseCalls = 0

    override fun pause() {
        pauseCalls += 1
    }

    override fun resume() = Unit
}

private object LoadingPauseQueueActions : PlaybackRuntimeQueueActions {
    override fun startCurrent() = Unit
    override fun previous() = Unit
    override fun next() = Unit
}
