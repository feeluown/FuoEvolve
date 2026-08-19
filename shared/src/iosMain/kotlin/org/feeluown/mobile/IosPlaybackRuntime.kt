package org.feeluown.mobile

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionStatus
import org.feeluown.mobile.playback.runtime.DefaultPlaybackRuntime
import org.feeluown.mobile.playback.runtime.PlaybackRuntimeEngine
import org.feeluown.mobile.playback.runtime.PlaybackRuntimeEngineState
import org.feeluown.mobile.playback.runtime.PlaybackRuntimeOverlay
import org.feeluown.mobile.playback.runtime.PlaybackRuntimeQueueActions

/** iOS composition-edge adapter for the shared playback runtime. */
internal fun createIosPlaybackRuntimeSession(
    controller: FuoPlayerController,
    playbackEngine: PlaybackEngine,
    scope: CoroutineScope,
): PlaybackSession {
    val overlay = snapshotFlow { controller.playbackState.toPlaybackRuntimeOverlay() }
        .distinctUntilChanged()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = controller.playbackState.toPlaybackRuntimeOverlay(),
        )

    return DefaultPlaybackRuntime(
        engine = LegacyPlaybackRuntimeEngine(playbackEngine, controller, scope),
        overlay = overlay,
        queueActions = LegacyPlaybackQueueActions(controller),
        scope = scope,
    )
}

private class LegacyPlaybackRuntimeEngine(
    private val playbackEngine: PlaybackEngine,
    controller: FuoPlayerController,
    scope: CoroutineScope,
) : PlaybackRuntimeEngine {
    override val state: StateFlow<PlaybackRuntimeEngineState> = combine(
        playbackEngine.state,
        snapshotFlow { controller.playbackState }.distinctUntilChanged(),
    ) { engineState, coordinatorState ->
        mergeIosPlaybackRuntimeEngineState(engineState, coordinatorState)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = mergeIosPlaybackRuntimeEngineState(
            engineState = playbackEngine.state.value,
            coordinatorState = controller.playbackState,
        ),
    )

    override fun pause() = playbackEngine.pause()

    override fun resume() = playbackEngine.resume()
}

private class LegacyPlaybackQueueActions(
    private val controller: FuoPlayerController,
) : PlaybackRuntimeQueueActions {
    override fun startCurrent() = controller.toggle()

    override fun previous() = controller.previous()

    override fun next() = controller.next()
}

/**
 * iOS still resolves provider resources in the legacy coordinator before the native engine starts.
 * During that window the engine is already Loading, so a resolution failure exists only on the
 * coordinator state. Bridge that one transitional error into the session until resource resolution
 * moves behind the playback runtime boundary in C2.
 */
internal fun mergeIosPlaybackRuntimeEngineState(
    engineState: PlaybackState,
    coordinatorState: PlaybackState,
): PlaybackRuntimeEngineState {
    val useCoordinatorResolutionError =
        engineState.status == PlayerStatus.Loading &&
            coordinatorState.status == PlayerStatus.Error &&
            engineState.currentTrack?.id != null &&
            engineState.currentTrack?.id == coordinatorState.currentTrack?.id

    return PlaybackRuntimeEngineState(
        status = if (useCoordinatorResolutionError) {
            PlaybackSessionStatus.Error
        } else {
            engineState.status.toPlaybackSessionStatus()
        },
        positionMs = engineState.positionMs,
        durationMs = engineState.durationMs,
        bufferedMs = engineState.bufferedMs,
        errorMessage = if (useCoordinatorResolutionError) {
            coordinatorState.errorMessage
        } else {
            engineState.errorMessage
        },
    )
}

private fun PlaybackState.toPlaybackRuntimeOverlay(): PlaybackRuntimeOverlay =
    PlaybackRuntimeOverlay(
        currentTrack = currentTrack?.toTrackRef(),
        lyrics = lyrics,
        queueTrackIds = queue.map(MusicTrack::id),
        queueIndex = queueIndex,
    )

private fun PlayerStatus.toPlaybackSessionStatus(): PlaybackSessionStatus = when (this) {
    PlayerStatus.Idle -> PlaybackSessionStatus.Idle
    PlayerStatus.Loading -> PlaybackSessionStatus.Loading
    PlayerStatus.Playing -> PlaybackSessionStatus.Playing
    PlayerStatus.Paused -> PlaybackSessionStatus.Paused
    PlayerStatus.Error -> PlaybackSessionStatus.Error
    PlayerStatus.Ended -> PlaybackSessionStatus.Ended
}

private fun MusicTrack.toTrackRef(): TrackRef = TrackRef(
    id = id,
    title = title,
    artists = artists,
    album = album,
    source = source,
    coverUrl = coverUrl,
    durationMs = durationMs,
)
