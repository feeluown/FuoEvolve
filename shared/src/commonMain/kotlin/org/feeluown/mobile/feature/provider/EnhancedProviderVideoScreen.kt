package org.feeluown.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val VIDEO_CONTROLS_AUTO_HIDE_MS = 3_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedProviderVideoScreen(controller: FuoPlayerController, video: ProviderVideo?) {
    val displayVideo = controller.selectedVideo ?: video ?: return
    val isFullscreen = controller.isVideoFullscreen
    val useWideLayout = LocalAppLayoutInfo.current.useWideLayout
    val videoController = rememberPlatformVideoController()
    val playbackState by videoController.state.collectAsState()
    var metadata by remember(displayVideo.id) { mutableStateOf<ProviderVideoMetadata?>(null) }
    var metadataLoading by remember(displayVideo.id) { mutableStateOf(false) }

    LaunchedEffect(displayVideo.id) {
        metadataLoading = true
        metadata = ProviderVideoMetadataRepository.load(displayVideo)
        metadataLoading = false
    }

    val metadataWidth = metadata?.width ?: 0
    val metadataHeight = metadata?.height ?: 0
    val isLandscapeVideo = when {
        playbackState.hasKnownOrientation -> playbackState.isLandscapeVideo
        metadataWidth > 0 && metadataHeight > 0 -> metadataWidth >= metadataHeight
        else -> true
    }
    PlatformVideoFullscreenEffect(
        isFullscreen = isFullscreen,
        isLandscapeVideo = isLandscapeVideo,
    )

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = displayVideo.title.ifBlank { "MV" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = controller::closeVideo) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (!isFullscreen && controller.playbackState.currentTrack != null) {
                MiniPlayer(controller)
            }
        },
    ) { paddingValues ->
        val screenModifier = if (isFullscreen) {
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        } else {
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        }
        Column(
            modifier = screenModifier,
            verticalArrangement = if (isFullscreen) Arrangement.Center else Arrangement.spacedBy(16.dp),
        ) {
            VideoPlayerFrame(
                payload = controller.selectedVideoPayload,
                controller = videoController,
                playbackState = playbackState,
                isFullscreen = isFullscreen,
                onToggleFullscreen = controller::toggleVideoFullscreen,
                modifier = if (isFullscreen) {
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(videoAspectRatio(playbackState, metadata))
                },
            )

            if (!isFullscreen) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (useWideLayout) 24.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    controller.selectedVideoError?.let { ProviderContentMessage(it) }
                    playbackState.errorMessage?.let { ProviderContentMessage(it) }
                    VideoInformationPanel(
                        video = displayVideo,
                        metadata = metadata,
                        metadataLoading = metadataLoading,
                        playbackState = playbackState,
                        quality = controller.selectedVideoPayload?.quality,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(12.dp))
                }
            }
        }
    }
}

@Composable
private fun VideoPlayerFrame(
    payload: VideoPlaybackPayload?,
    controller: PlatformVideoController,
    playbackState: PlatformVideoPlaybackState,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionEpoch by remember { mutableLongStateOf(0L) }
    val tapInteractionSource = remember { MutableInteractionSource() }

    fun showControlsForInteraction() {
        controlsVisible = true
        interactionEpoch += 1
    }

    LaunchedEffect(playbackState.isPlaying) {
        if (!playbackState.isPlaying) {
            controlsVisible = true
        }
    }
    LaunchedEffect(controlsVisible, playbackState.isPlaying, interactionEpoch) {
        if (controlsVisible && playbackState.isPlaying) {
            delay(VIDEO_CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        PlatformVideoPlayer(
            payload = payload,
            controller = controller,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = tapInteractionSource,
                    indication = null,
                ) {
                    if (controlsVisible) {
                        controlsVisible = false
                    } else {
                        showControlsForInteraction()
                    }
                },
        )
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            VideoControlBar(
                controller = controller,
                state = playbackState,
                quality = payload?.quality,
                isFullscreen = isFullscreen,
                onInteraction = ::showControlsForInteraction,
                onToggleFullscreen = onToggleFullscreen,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun VideoControlBar(
    controller: PlatformVideoController,
    state: PlatformVideoPlaybackState,
    quality: String?,
    isFullscreen: Boolean,
    onInteraction: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = state.durationMs.coerceAtLeast(0)
    var scrubbingFraction by remember(duration) { mutableStateOf<Float?>(null) }
    val playedFraction = if (duration > 0) {
        (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownFraction = scrubbingFraction ?: playedFraction
    val shownPosition = if (duration > 0) {
        (shownFraction * duration).toLong()
    } else {
        state.positionMs
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Black.copy(alpha = 0.68f),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Slider(
                value = shownFraction,
                onValueChange = {
                    onInteraction()
                    scrubbingFraction = it
                },
                onValueChangeFinished = {
                    val target = scrubbingFraction
                    if (target != null && duration > 0) {
                        controller.seekTo((target * duration).toLong())
                    }
                    scrubbingFraction = null
                    onInteraction()
                },
                enabled = duration > 0,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    onInteraction()
                    if (state.isPlaying) controller.pause() else controller.play()
                }) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "暂停" else "播放",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = {
                    onInteraction()
                    controller.seekTo((state.positionMs - 10_000).coerceAtLeast(0))
                }) {
                    Icon(Icons.Filled.Replay10, contentDescription = "后退 10 秒", tint = Color.White)
                }
                IconButton(onClick = {
                    onInteraction()
                    val target = state.positionMs + 10_000
                    controller.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
                }) {
                    Icon(Icons.Filled.Forward10, contentDescription = "前进 10 秒", tint = Color.White)
                }
                Text(
                    text = "${formatVideoTime(shownPosition)} / ${formatVideoTime(duration)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                Spacer(Modifier.weight(1f))
                val formatLabel = buildList {
                    quality?.takeIf { it.isNotBlank() }?.let(::add)
                    if (state.videoWidth > 0 && state.videoHeight > 0) {
                        add("${state.videoWidth}×${state.videoHeight}")
                    }
                }.joinToString(" · ")
                if (formatLabel.isNotBlank()) {
                    Text(
                        text = formatLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = {
                    onInteraction()
                    onToggleFullscreen()
                }) {
                    Icon(
                        imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = if (isFullscreen) "退出全屏" else "全屏",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoInformationPanel(
    video: ProviderVideo,
    metadata: ProviderVideoMetadata?,
    metadataLoading: Boolean,
    playbackState: PlatformVideoPlaybackState,
    quality: String?,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = video.title.ifBlank { "未命名 MV" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (video.artists.isNotBlank()) {
            Text(
                text = video.artists,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = buildList {
                add(video.providerName)
                quality?.takeIf { it.isNotBlank() }?.let(::add)
                val duration = playbackState.durationMs.takeIf { it > 0 } ?: video.durationMs
                duration?.takeIf { it > 0 }?.let { add(formatVideoTime(it)) }
                metadata?.publishedAt?.takeIf { it.isNotBlank() }?.let { add("发布于 $it") }
            }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (metadataLoading && metadata == null) {
            Text(
                text = "正在加载视频详情…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        metadata?.stats?.takeIf { it.isNotEmpty() }?.let { stats ->
            Text(
                text = "数据",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            VideoStatGrid(stats)
        }
        metadata?.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = "简介",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
            ) {
                Text(
                    text = description,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VideoStatGrid(stats: List<ProviderVideoStat>) {
    val columns = if (LocalAppLayoutInfo.current.useWideLayout) 3 else 2
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.chunked(columns).forEach { rowStats ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowStats.forEach { stat ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = compactVideoCount(stat.value),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stat.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                repeat(columns - rowStats.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private fun videoAspectRatio(
    playbackState: PlatformVideoPlaybackState,
    metadata: ProviderVideoMetadata?,
): Float {
    val width = playbackState.videoWidth.takeIf { it > 0 } ?: metadata?.width?.takeIf { it > 0 }
    val height = playbackState.videoHeight.takeIf { it > 0 } ?: metadata?.height?.takeIf { it > 0 }
    return if (width != null && height != null) {
        (width.toFloat() / height.toFloat()).coerceIn(0.56f, 2.4f)
    } else {
        16f / 9f
    }
}

private fun formatVideoTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0) / 1_000).toInt()
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

private fun compactVideoCount(value: Long): String = when {
    value >= 100_000_000L -> "${oneDecimal(value / 100_000_000.0)} 亿"
    value >= 10_000L -> "${oneDecimal(value / 10_000.0)} 万"
    else -> value.toString()
}

private fun oneDecimal(value: Double): String {
    val rounded = (value * 10).toLong() / 10.0
    return if (rounded.toLong().toDouble() == rounded) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}
