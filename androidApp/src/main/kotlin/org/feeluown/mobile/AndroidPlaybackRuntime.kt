package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.feeluown.mobile.playback.api.PlaybackSession

/** Android composition-edge adapter. Established sessions resume the native engine directly. */
internal fun createPlaybackRuntimeSession(
    playbackState: StateFlow<PlaybackState>,
    playbackEngine: PlaybackEngine,
    transportCoordinator: PlaybackTransportCoordinator,
    startFailureSource: PlaybackStartFailureSource,
    scope: CoroutineScope,
): PlaybackSession = createSharedPlaybackRuntimeSession(
    playbackState = playbackState,
    playbackEngine = playbackEngine,
    transportCoordinator = transportCoordinator,
    startFailureSource = startFailureSource,
    scope = scope,
    resumePlayback = playbackEngine::resume,
)
