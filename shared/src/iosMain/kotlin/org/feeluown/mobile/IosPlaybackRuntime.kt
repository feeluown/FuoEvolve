package org.feeluown.mobile

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
        engine = LegacyPlaybackRuntimeEngine(playbackEngine, scope),
        overlay = overlay,
        queueActions = LegacyPlaybackQueueActions(controller),
        scope = scope,
    )
}

private class LegacyPlaybackRuntimeEngine(
    private val playbackEngine: PlaybackEngine,
    scope: CoroutineScope,
) : PlaybackRuntimeEngine {
    override val state: StateFlow<PlaybackRuntimeEngineState> = playbackEngine.state
        .map(PlaybackState::toPlaybackRuntimeEngineState)
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = playbackEngine.state.value.toPlaybackRuntimeEngineState(),
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

private fun PlaybackState.toPlaybackRuntimeEngineState(): PlaybackRuntimeEngineState =
    PlaybackRuntimeEngineState(
        status = status.toPlaybackSessionStatus(),
        positionMs = positionMs,
        durationMs = durationMs,
        bufferedMs = bufferedMs,
        errorMessage = errorMessage,
    )

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
