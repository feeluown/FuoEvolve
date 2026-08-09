package org.feeluown.mobile

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@OptIn(UnstableApi::class)
private class AndroidPlatformVideoController(context: Context) : PlatformVideoController {
    private val _state = MutableStateFlow(PlatformVideoPlaybackState())
    override val state: StateFlow<PlatformVideoPlaybackState> = _state

    private var playbackError: String? = null
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    private var activePayload: VideoPlaybackPayload? = null

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true),
        )
        .build()
        .also { exoPlayer ->
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackError = "视频播放失败：${error.errorCodeName}"
                    publishState()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    publishState()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    publishState()
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                    publishState()
                }
            })
        }

    fun setPayload(context: Context, payload: VideoPlaybackPayload?) {
        if (payload == null || !payload.isPlayable()) {
            clear()
            return
        }
        if (payload == activePayload) return
        activePayload = payload
        playbackError = null
        videoWidth = 0
        videoHeight = 0
        player.setMediaSource(payload.toMediaSource(context))
        player.prepare()
        player.playWhenReady = true
        publishState()
    }

    fun refreshProgress() {
        publishState()
    }

    override fun play() {
        val duration = player.duration.takeIf { it > 0 } ?: 0
        if (duration > 0 && player.currentPosition >= duration - 500) {
            player.seekTo(0)
        }
        player.play()
        publishState()
    }

    override fun pause() {
        player.pause()
        publishState()
    }

    override fun seekTo(positionMs: Long) {
        val duration = player.duration.takeIf { it > 0 } ?: 0
        val target = if (duration > 0) {
            positionMs.coerceIn(0, duration)
        } else {
            positionMs.coerceAtLeast(0)
        }
        player.seekTo(target)
        publishState()
    }

    fun clear() {
        player.stop()
        player.clearMediaItems()
        activePayload = null
        playbackError = null
        videoWidth = 0
        videoHeight = 0
        _state.value = PlatformVideoPlaybackState()
    }

    fun release() {
        player.release()
    }

    private fun publishState() {
        val duration = player.duration.takeIf { it > 0 } ?: 0
        _state.value = PlatformVideoPlaybackState(
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            bufferedMs = player.bufferedPosition.coerceAtLeast(0),
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            errorMessage = playbackError,
        )
    }
}

@Composable
actual fun rememberPlatformVideoController(): PlatformVideoController {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val controller = remember(appContext) { AndroidPlatformVideoController(appContext) }
    LaunchedEffect(controller) {
        while (true) {
            controller.refreshProgress()
            delay(250)
        }
    }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    return controller
}

@OptIn(UnstableApi::class)
@Composable
actual fun PlatformVideoPlayer(
    payload: VideoPlaybackPayload?,
    controller: PlatformVideoController,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val androidController = controller as? AndroidPlatformVideoController
    if (payload == null) {
        LaunchedEffect(androidController, context) {
            androidController?.setPayload(context.applicationContext, null)
        }
        VideoPlaceholder("正在加载视频", modifier)
        return
    }
    if (!payload.isPlayable() || androidController == null) {
        VideoPlaceholder("视频地址不可用", modifier)
        return
    }
    LaunchedEffect(payload.url, payload.videoUrl, payload.audioUrl, payload.headers) {
        androidController.setPayload(context.applicationContext, payload)
    }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                player = androidController.player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(Color.BLACK)
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            }
        },
        update = { it.player = androidController.player },
    )
}

@Composable
actual fun PlatformVideoFullscreenEffect(
    isFullscreen: Boolean,
    isLandscapeVideo: Boolean,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var previousOrientation by remember(activity) { mutableStateOf<Int?>(null) }

    LaunchedEffect(activity, isFullscreen, isLandscapeVideo) {
        activity ?: return@LaunchedEffect
        val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (isFullscreen) {
            if (previousOrientation == null) {
                previousOrientation = activity.requestedOrientation
            }
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = if (isLandscapeVideo) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            previousOrientation?.let { activity.requestedOrientation = it }
            previousOrientation = null
        }
    }

    DisposableEffect(activity) {
        onDispose {
            activity ?: return@onDispose
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
            previousOrientation?.let { orientation ->
                activity.requestedOrientation = orientation
            }
        }
    }
}

@Composable
private fun VideoPlaceholder(text: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun VideoPlaybackPayload.isPlayable(): Boolean =
    url.isNotBlank() || (videoUrl.isNotBlank() && audioUrl.isNotBlank())

@OptIn(UnstableApi::class)
private fun VideoPlaybackPayload.toMediaSource(context: Context) =
    if (url.isNotBlank()) {
        ProgressiveMediaSource.Factory(dataSourceFactory(context, headers))
            .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
    } else {
        MergingMediaSource(
            ProgressiveMediaSource.Factory(dataSourceFactory(context, headers))
                .createMediaSource(MediaItem.fromUri(Uri.parse(videoUrl))),
            ProgressiveMediaSource.Factory(dataSourceFactory(context, headers))
                .createMediaSource(MediaItem.fromUri(Uri.parse(audioUrl))),
        )
    }

@OptIn(UnstableApi::class)
private fun dataSourceFactory(context: Context, headers: Map<String, String>): DefaultDataSource.Factory {
    val httpFactory = DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(headers)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
        .setAllowCrossProtocolRedirects(true)
    return DefaultDataSource.Factory(context, httpFactory)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
