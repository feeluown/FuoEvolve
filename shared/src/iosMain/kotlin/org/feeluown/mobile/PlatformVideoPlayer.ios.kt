package org.feeluown.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController

internal object IosVideoOutputHolder {
    var output: IosVideoOutput? = null
}

@Composable
actual fun PlatformVideoPlayer(
    payload: VideoPlaybackPayload?,
    modifier: Modifier,
) {
    val output = IosVideoOutputHolder.output
    if (payload == null) {
        VideoPlaceholder("正在加载视频", modifier)
        return
    }
    if (output == null || (payload.url.isBlank() && payload.videoUrl.isBlank())) {
        VideoPlaceholder("视频地址不可用", modifier)
        return
    }
    UIKitViewController(
        factory = { output.makePlayer(payload.url, payload.videoUrl, payload.audioUrl, payload.headers) },
        modifier = modifier,
        update = { output.updatePlayer(it, payload.url, payload.videoUrl, payload.audioUrl, payload.headers) },
        onRelease = output::releasePlayer,
    )
}

@Composable
private fun VideoPlaceholder(text: String, modifier: Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
