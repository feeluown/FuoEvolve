package org.feeluown.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.AVKit.AVPlayerViewController
import platform.UIKit.UIViewController

internal object IosVideoOutputHolder {
    var output: IosVideoOutput? = null
}

private class IosPlatformVideoController(
    private val output: IosVideoOutput?,
) : PlatformVideoController {
    private val _state = MutableStateFlow(PlatformVideoPlaybackState())
    override val state: StateFlow<PlatformVideoPlaybackState> = _state

    private var viewController: UIViewController? = null

    fun attach(viewController: UIViewController) {
        this.viewController = viewController
        refresh()
    }

    fun detach(viewController: UIViewController) {
        if (this.viewController === viewController) {
            this.viewController = null
            _state.value = PlatformVideoPlaybackState()
        }
    }

    fun refresh() {
        val viewController = viewController ?: return
        val output = output ?: return
        _state.value = PlatformVideoPlaybackState(
            isPlaying = output.isPlaying(viewController),
            positionMs = output.positionMs(viewController).coerceAtLeast(0),
            durationMs = output.durationMs(viewController).coerceAtLeast(0),
            bufferedMs = output.bufferedMs(viewController).coerceAtLeast(0),
            videoWidth = output.videoWidth(viewController).coerceAtLeast(0),
            videoHeight = output.videoHeight(viewController).coerceAtLeast(0),
        )
    }

    override fun play() {
        val viewController = viewController ?: return
        val output = output ?: return
        val duration = output.durationMs(viewController)
        if (duration > 0 && output.positionMs(viewController) >= duration - 500) {
            output.seekTo(viewController, 0)
        }
        output.play(viewController)
        refresh()
    }

    override fun pause() {
        val viewController = viewController ?: return
        output?.pause(viewController)
        refresh()
    }

    override fun seekTo(positionMs: Long) {
        val viewController = viewController ?: return
        output?.seekTo(viewController, positionMs.coerceAtLeast(0))
        refresh()
    }
}

@Composable
actual fun rememberPlatformVideoController(): PlatformVideoController {
    val output = IosVideoOutputHolder.output
    val controller = remember(output) { IosPlatformVideoController(output) }
    LaunchedEffect(controller) {
        while (true) {
            controller.refresh()
            delay(250)
        }
    }
    return controller
}

@Composable
actual fun PlatformVideoPlayer(
    payload: VideoPlaybackPayload?,
    controller: PlatformVideoController,
    modifier: Modifier,
) {
    val output = IosVideoOutputHolder.output
    val iosController = controller as? IosPlatformVideoController
    if (payload == null) {
        VideoPlaceholder("正在加载视频", modifier)
        return
    }
    if (output == null || iosController == null || !payload.isPlayable()) {
        VideoPlaceholder("视频地址不可用", modifier)
        return
    }
    UIKitViewController(
        factory = {
            output.makePlayer(payload.url, payload.videoUrl, payload.audioUrl, payload.headers).also { viewController ->
                (viewController as? AVPlayerViewController)?.showsPlaybackControls = false
                iosController.attach(viewController)
            }
        },
        modifier = modifier,
        update = { viewController ->
            (viewController as? AVPlayerViewController)?.showsPlaybackControls = false
            output.updatePlayer(viewController, payload.url, payload.videoUrl, payload.audioUrl, payload.headers)
            iosController.attach(viewController)
        },
        onRelease = { viewController ->
            iosController.detach(viewController)
            output.releasePlayer(viewController)
        },
    )
}

@Composable
actual fun PlatformVideoFullscreenEffect(
    isFullscreen: Boolean,
    isLandscapeVideo: Boolean,
) {
    val output = IosVideoOutputHolder.output
    LaunchedEffect(output, isFullscreen, isLandscapeVideo) {
        output?.setFullscreenOrientation(isFullscreen, isLandscapeVideo)
    }
    DisposableEffect(output) {
        onDispose {
            output?.setFullscreenOrientation(false, false)
        }
    }
}

@Composable
private fun VideoPlaceholder(text: String, modifier: Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun VideoPlaybackPayload.isPlayable(): Boolean =
    url.isNotBlank() || (videoUrl.isNotBlank() && audioUrl.isNotBlank())
