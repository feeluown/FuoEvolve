package org.feeluown.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private class DesktopUnsupportedVideoController : PlatformVideoController {
    private val mutableState = MutableStateFlow(PlatformVideoPlaybackState())
    override val state: StateFlow<PlatformVideoPlaybackState> = mutableState.asStateFlow()

    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
}

@Composable
actual fun rememberPlatformVideoController(): PlatformVideoController = remember {
    DesktopUnsupportedVideoController()
}

@Composable
actual fun PlatformVideoPlayer(
    payload: VideoPlaybackPayload?,
    controller: PlatformVideoController,
    modifier: Modifier,
) {
    Box(modifier)
}

@Composable
actual fun PlatformVideoFullscreenEffect(
    isFullscreen: Boolean,
    isLandscapeVideo: Boolean,
) = Unit
