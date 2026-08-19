package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalPlaybackUiPort = staticCompositionLocalOf<PlaybackUiPort> {
    error("PlaybackUiPort is not provided")
}

/** Controller-free MiniPlayer entry point used by migrated app screens. */
@Composable
fun PlaybackMiniPlayer() {
    val uiPort = LocalPlaybackUiPort.current
    RuntimeMiniPlayer(
        playbackSession = LocalPlaybackSession.current,
        isFullPlayerOpen = uiPort.isFullPlayerOpen,
        transitionDirection = uiPort.trackChangeDirection,
        onOpenFullPlayer = uiPort::openFullPlayer,
    )
}
