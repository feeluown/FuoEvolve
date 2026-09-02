package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.feeluown.mobile.playback.api.PlaybackSession

/** iOS composition-edge adapter. AVPlayer can resume the established engine session directly. */
internal fun createIosPlaybackRuntimeSession(
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
