package org.feeluown.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderVideoDetailRoute(video: ProviderVideo) {
    val graph = LocalProviderDetailUiGraph.current
    val owner = graph.owners.video
    val musicPlaybackSession = LocalPlaybackSession.current
    val state by owner.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(video.id) { owner.activate(video) }
    val displayVideo = state.video ?: video
    val platformController = rememberPlatformVideoController()
    val playbackState by platformController.state.collectAsState()
    var metadata by remember(displayVideo.id) { mutableStateOf<ProviderVideoMetadata?>(null) }

    LaunchedEffect(displayVideo.id) {
        metadata = ProviderVideoMetadataRepository.load(displayVideo)
    }
    LaunchedEffect(playbackState.isPlaying) {
        if (playbackState.isPlaying) musicPlaybackSession.pause()
    }

    val metadataWidth = metadata?.width ?: 0
    val metadataHeight = metadata?.height ?: 0
    val isLandscapeVideo = when {
        playbackState.hasKnownOrientation -> playbackState.isLandscapeVideo
        metadataWidth > 0 && metadataHeight > 0 -> metadataWidth >= metadataHeight
        else -> true
    }
    PlatformVideoFullscreenEffect(state.isFullscreen, isLandscapeVideo)

    Scaffold(
        topBar = {
            if (!state.isFullscreen) {
                CenterAlignedTopAppBar(
                    title = { Text(displayVideo.title.ifBlank { "MV" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = owner::close) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (!state.isFullscreen && graph.playbackQueue.currentQueueTrack != null) PlaybackMiniPlayer()
        },
    ) { paddingValues ->
        val screenModifier = if (state.isFullscreen) {
            Modifier.fillMaxSize().background(Color.Black)
        } else {
            Modifier.fillMaxSize().padding(paddingValues).verticalScroll(rememberScrollState())
        }
        Column(
            modifier = screenModifier,
            verticalArrangement = if (state.isFullscreen) Arrangement.Center else Arrangement.spacedBy(16.dp),
        ) {
            ProviderOwnedVideoFrame(
                payload = state.payload,
                controller = platformController,
                playbackState = playbackState,
                fullscreen = state.isFullscreen,
                onToggleFullscreen = owner::toggleFullscreen,
                modifier = if (state.isFullscreen) {
                    Modifier.fillMaxWidth().weight(1f)
                } else {
                    Modifier.fillMaxWidth().aspectRatio(providerVideoAspectRatio(playbackState, metadata))
                },
            )
            if (!state.isFullscreen) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.errorMessage?.let { ProviderContentMessage(it) }
                    playbackState.errorMessage?.let { ProviderContentMessage(it) }
                    Text(
                        text = displayVideo.title.ifBlank { "未命名视频" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (displayVideo.artists.isNotBlank()) {
                        Text(displayVideo.artists, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val meta = metadata
                    if (meta != null) {
                        val summary = buildList {
                            meta.publishedAt?.takeIf { it.isNotBlank() }?.let(::add)
                            meta.stats.take(3).forEach { stat -> add("${stat.label} ${formatProviderVideoStat(stat.value)}") }
                        }.joinToString(" · ")
                        if (summary.isNotBlank()) {
                            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (meta.description.isNotBlank()) {
                            Text(meta.description, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderOwnedVideoFrame(
    payload: VideoPlaybackPayload?,
    controller: PlatformVideoController,
    playbackState: PlatformVideoPlaybackState,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        PlatformVideoPlayer(payload = payload, controller = controller, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier.fillMaxSize().clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {},
        )
        ProviderOwnedVideoControls(
            controller = controller,
            state = playbackState,
            fullscreen = fullscreen,
            onToggleFullscreen = onToggleFullscreen,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        )
    }
}

@Composable
private fun ProviderOwnedVideoControls(
    controller: PlatformVideoController,
    state: PlatformVideoPlaybackState,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = state.durationMs.coerceAtLeast(0L)
    var scrub by remember(duration) { mutableFloatStateOf(-1f) }
    val playedFraction = if (duration > 0) {
        (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val shownFraction = if (scrub >= 0f) scrub else playedFraction

    Surface(modifier = modifier, color = Color.Black.copy(alpha = 0.68f), contentColor = Color.White) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Slider(
                value = shownFraction,
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    if (scrub >= 0f && duration > 0) controller.seekTo((scrub * duration).toLong())
                    scrub = -1f
                },
                enabled = duration > 0,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (state.isPlaying) controller.pause() else controller.play() }) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "暂停" else "播放",
                    )
                }
                IconButton(onClick = { controller.seekTo((state.positionMs - 10_000L).coerceAtLeast(0L)) }) {
                    Icon(Icons.Filled.Replay10, contentDescription = "后退 10 秒")
                }
                IconButton(onClick = {
                    val target = state.positionMs + 10_000L
                    controller.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
                }) {
                    Icon(Icons.Filled.Forward10, contentDescription = "前进 10 秒")
                }
                Text("${formatProviderVideoTime(state.positionMs)} / ${formatProviderVideoTime(duration)}")
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggleFullscreen) {
                    Icon(
                        if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = if (fullscreen) "退出全屏" else "全屏",
                    )
                }
            }
        }
    }
}

private fun providerVideoAspectRatio(
    state: PlatformVideoPlaybackState,
    metadata: ProviderVideoMetadata?,
): Float {
    val width = state.videoWidth.takeIf { it > 0 } ?: metadata?.width ?: 16
    val height = state.videoHeight.takeIf { it > 0 } ?: metadata?.height ?: 9
    return (width.toFloat() / height.toFloat()).coerceIn(0.5f, 3f)
}

private fun formatProviderVideoTime(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) / 1000L)
    val minutes = seconds / 60L
    val remainingSeconds = seconds % 60L
    return "$minutes:${remainingSeconds.toString().padStart(2, '0')}"
}

private fun formatProviderVideoStat(value: Long): String = when {
    value >= 100_000_000L -> "${value / 100_000_000L}亿"
    value >= 10_000L -> "${value / 10_000L}万"
    else -> value.toString()
}
