package org.feeluown.mobile

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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView

@Composable
actual fun PlatformVideoPlayer(
    payload: VideoPlaybackPayload?,
    modifier: Modifier,
) {
    if (payload == null) {
        VideoPlaceholder("正在加载视频", modifier)
        return
    }
    val playable = payload.url.isNotBlank() || (payload.videoUrl.isNotBlank() && payload.audioUrl.isNotBlank())
    if (!playable) {
        VideoPlaceholder("视频地址不可用", modifier)
        return
    }
    val context = LocalContext.current
    var playbackError by remember { mutableStateOf<String?>(null) }
    val player = remember(context) {
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .build()
            .also { exoPlayer ->
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        playbackError = "视频播放失败：${error.errorCodeName}"
                    }
                })
            }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    LaunchedEffect(payload.url, payload.videoUrl, payload.audioUrl, payload.headers) {
        playbackError = null
        player.setMediaSource(payload.toMediaSource(context))
        player.prepare()
        player.playWhenReady = true
    }
    if (playbackError != null) {
        VideoPlaceholder(playbackError.orEmpty(), modifier)
    } else {
        AndroidView(
            modifier = modifier,
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                }
            },
            update = { it.player = player },
        )
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

@OptIn(UnstableApi::class)
private fun VideoPlaybackPayload.toMediaSource(context: android.content.Context) =
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

private fun dataSourceFactory(context: android.content.Context, headers: Map<String, String>): DefaultDataSource.Factory {
    val httpFactory = DefaultHttpDataSource.Factory()
        .setDefaultRequestProperties(headers)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
        .setAllowCrossProtocolRedirects(true)
    return DefaultDataSource.Factory(context, httpFactory)
}
