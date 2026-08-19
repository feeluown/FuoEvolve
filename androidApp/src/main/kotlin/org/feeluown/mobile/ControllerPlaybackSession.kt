package org.feeluown.mobile

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.feeluown.mobile.core.model.TrackRef
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionState
import org.feeluown.mobile.playback.api.PlaybackSessionStatus

/**
 * Compatibility adapter while playback ownership is moved out of [FuoPlayerController].
 *
 * Platform integrations consume [PlaybackSession], so the controller can be replaced by a
 * dedicated playback runtime without changing service/media-session integrations again.
 */
fun createPlaybackSession(
    controller: FuoPlayerController,
    scope: CoroutineScope,
): PlaybackSession = ControllerPlaybackSession(controller, scope)

private class ControllerPlaybackSession(
    private val controller: FuoPlayerController,
    scope: CoroutineScope,
) : PlaybackSession {
    private val mutableState = MutableStateFlow(controller.playbackState.toPlaybackSessionState())
    override val state: StateFlow<PlaybackSessionState> = mutableState.asStateFlow()

    init {
        scope.launch {
            snapshotFlow { controller.playbackState }
                .distinctUntilChanged()
                .collect { playbackState ->
                    mutableState.value = playbackState.toPlaybackSessionState()
                }
        }
    }

    override fun toggle() {
        controller.toggle()
    }

    override fun play() {
        if (controller.playbackState.status != PlayerStatus.Playing) {
            controller.toggle()
        }
    }

    override fun pause() {
        if (controller.playbackState.status == PlayerStatus.Playing) {
            controller.toggle()
        }
    }

    override fun previous() {
        controller.previous()
    }

    override fun next() {
        controller.next()
    }
}

private fun PlaybackState.toPlaybackSessionState(): PlaybackSessionState = PlaybackSessionState(
    status = status.toPlaybackSessionStatus(),
    currentTrack = currentTrack?.toTrackRef(),
    positionMs = positionMs,
    durationMs = durationMs,
    bufferedMs = bufferedMs,
    lyrics = lyrics,
    queueTrackIds = queue.map(MusicTrack::id),
    queueIndex = queueIndex,
    errorMessage = errorMessage,
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
