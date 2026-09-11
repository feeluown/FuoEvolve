package org.feeluown.mobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Desktop-only bridge implemented by the active desktop host/runtime. */
interface DesktopPlatformVideoController : PlatformVideoController, AutoCloseable {
    val frame: StateFlow<ImageBitmap?>
    fun setPayload(payload: VideoPlaybackPayload?)
    fun setViewportSize(width: Int, height: Int)
}

/**
 * Optional host-owned video surface. Native desktop hosts can render directly into their GPU
 * scene while the legacy JVM host keeps consuming [DesktopPlatformVideoController.frame].
 */
interface DesktopPlatformVideoSurface {
    @Composable
    fun Content(
        controller: DesktopPlatformVideoController,
        payload: VideoPlaybackPayload?,
        modifier: Modifier,
    )
}

private var desktopPlatformVideoControllerFactory: () -> DesktopPlatformVideoController = {
    UnsupportedDesktopPlatformVideoController("桌面视频播放组件未初始化")
}

private var desktopPlatformVideoSurface: DesktopPlatformVideoSurface? = null

fun installDesktopPlatformVideoControllerFactory(factory: () -> DesktopPlatformVideoController) {
    desktopPlatformVideoControllerFactory = factory
}

fun installDesktopPlatformVideoSurface(surface: DesktopPlatformVideoSurface?) {
    desktopPlatformVideoSurface = surface
}

private class UnsupportedDesktopPlatformVideoController(
    message: String,
) : DesktopPlatformVideoController {
    private val mutableState = MutableStateFlow(PlatformVideoPlaybackState(errorMessage = message))
    override val state: StateFlow<PlatformVideoPlaybackState> = mutableState.asStateFlow()
    private val mutableFrame = MutableStateFlow<ImageBitmap?>(null)
    override val frame: StateFlow<ImageBitmap?> = mutableFrame.asStateFlow()
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setPayload(payload: VideoPlaybackPayload?) = Unit
    override fun setViewportSize(width: Int, height: Int) = Unit
    override fun close() = Unit
}

@Composable
actual fun rememberPlatformVideoController(): PlatformVideoController {
    val controller = remember {
        runCatching(desktopPlatformVideoControllerFactory).getOrElse { throwable ->
            UnsupportedDesktopPlatformVideoController(
                throwable.message?.takeIf(String::isNotBlank) ?: "桌面视频播放组件初始化失败",
            )
        }
    }
    DisposableEffect(controller) {
        onDispose(controller::close)
    }
    return controller
}

@Composable
actual fun PlatformVideoPlayer(
    payload: VideoPlaybackPayload?,
    controller: PlatformVideoController,
    modifier: Modifier,
) {
    val desktopController = controller as? DesktopPlatformVideoController
    LaunchedEffect(desktopController, payload) {
        desktopController?.setPayload(payload)
    }

    Box(
        modifier = modifier.onSizeChanged { size ->
            desktopController?.setViewportSize(size.width, size.height)
        },
        contentAlignment = Alignment.Center,
    ) {
        val hostSurface = desktopPlatformVideoSurface
        if (desktopController != null && hostSurface != null) {
            hostSurface.Content(
                controller = desktopController,
                payload = payload,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val frame = if (desktopController != null) {
                val value by desktopController.frame.collectAsState()
                value
            } else {
                null
            }
            frame?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = payload?.video?.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
actual fun PlatformVideoFullscreenEffect(
    isFullscreen: Boolean,
    isLandscapeVideo: Boolean,
) = Unit
