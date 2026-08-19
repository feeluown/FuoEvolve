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
    transportCoordinator: PlaybackTransportCoordinator,
    startFailureSource: PlaybackStartFailureSource,
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
        engine = PlaybackRuntimeEngineAdapter(playbackEngine, startFailureSource, scope),
        overlay = overlay,
        queueActions = PlaybackCoordinatorQueueActions(transportCoordinator),
        scope = scope,
    )
}

private class PlaybackRuntimeEngineAdapter(
    private val playbackEngine: PlaybackEngine,
    startFailureSource: PlaybackStartFailureSource,
    scope: CoroutineScope,
) : PlaybackRuntimeEngine {
    override val state: StateFlow<PlaybackRuntimeEngineState> = combine(
        playbackEngine.state,
        startFailureSource.startFailure,
    ) { engineState, startFailure ->
        mergePlaybackStartFailure(engineState, startFailure)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = mergePlaybackStartFailure(
            playbackEngine.state.value,
            startFailureSource.startFailure.value,
        ),
    )

    override fun pause() = playbackEngine.pause()

    override fun resume() = playbackEngine.resume()
}

private class PlaybackCoordinatorQueueActions(
    private val coordinator: PlaybackTransportCoordinator,
) : PlaybackRuntimeQueueActions {
    override fun startCurrent() = coordinator.startCurrent()

    override fun previous() = coordinator.previous()

    override fun next() = coordinator.next()
}

/** Pre-engine resource failures are now published by PlaybackStartCoordinator. */
internal fun mergePlaybackStartFailure(
    engineState: PlaybackState,
    startFailure: PlaybackStartFailure?,
): PlaybackRuntimeEngineState {
    val activeFailure = startFailure?.takeIf { failure ->
        engineState.status == PlayerStatus.Loading && engineState.currentTrack?.id == failure.trackId
    }
    return PlaybackRuntimeEngineState(
        status = if (activeFailure != null) {
            PlaybackSessionStatus.Error
        } else {
            engineState.status.toPlaybackSessionStatus()
        },
        positionMs = engineState.positionMs,
        durationMs = engineState.durationMs,
        bufferedMs = engineState.bufferedMs,
        errorMessage = activeFailure?.message ?: engineState.errorMessage,
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
