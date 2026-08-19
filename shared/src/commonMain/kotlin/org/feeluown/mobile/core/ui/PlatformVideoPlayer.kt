package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

data class PlatformVideoPlaybackState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val errorMessage: String? = null,
) {
    val hasKnownOrientation: Boolean
        get() = videoWidth > 0 && videoHeight > 0

    val isLandscapeVideo: Boolean
        get() = videoWidth >= videoHeight
}

interface PlatformVideoController {
    val state: StateFlow<PlatformVideoPlaybackState>

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)
}

@Composable
expect fun rememberPlatformVideoController(): PlatformVideoController

@Composable
expect fun PlatformVideoPlayer(
    payload: VideoPlaybackPayload?,
    controller: PlatformVideoController,
    modifier: Modifier,
)

@Composable
expect fun PlatformVideoFullscreenEffect(
    isFullscreen: Boolean,
    isLandscapeVideo: Boolean,
)
