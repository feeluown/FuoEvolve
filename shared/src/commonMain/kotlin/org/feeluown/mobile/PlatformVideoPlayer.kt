package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun PlatformVideoPlayer(
    payload: VideoPlaybackPayload?,
    modifier: Modifier,
)
