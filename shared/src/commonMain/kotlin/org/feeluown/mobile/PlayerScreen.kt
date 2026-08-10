package org.feeluown.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun MiniPlayer(controller: FuoPlayerController) {
    MiniPlayerContent(controller)
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun MiniPlayerContent(controller: FuoPlayerController) {
    val state = controller.playbackState
    val isLoadingAudio = state.status == PlayerStatus.Loading
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(220))
            .fuoInteractive()
            .clickable(role = Role.Button, onClick = controller::openFullPlayer),
        shape = if (isWideLayout) MaterialTheme.shapes.medium else MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = if (isWideLayout) 8.dp else 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.currentTrack?.let {
                    PlayerSharedCover(
                        track = it,
                        heroEnabled = !controller.isFullPlayerOpen,
                        transitionDirection = controller.trackChangeDirection,
                        isLoading = isLoadingAudio,
                        cornerRadius = if (isWideLayout) 10.dp else 12.dp,
                        modifier = Modifier.size(if (isWideLayout) 44.dp else 56.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.currentTrack?.title ?: "未播放",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (isLoadingAudio) {
                            "正在加载音频"
                        } else {
                            state.currentTrack?.let(::artistAlbumLabel) ?: "选择一首音乐开始播放"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isLoadingAudio) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MiniPlayerLyricLine(state)
                }
                PlayerControls(
                    state = state,
                    onPrevious = controller::previous,
                    onToggle = controller::toggle,
                    onNext = controller::next,
                    compact = true,
                )
            }
            MiniPlayerProgress(state, isLoadingAudio)
        }
    }
}

@Composable
private fun MiniPlayerProgress(state: PlaybackState, isLoadingAudio: Boolean) {
    val duration = state.durationMs.takeIf { it > 0 }
    if (isLoadingAudio || duration != null) {
        val targetProgress = duration?.let {
            state.positionMs.coerceIn(0, it).toFloat() / it
        } ?: 0f
        val progress by animateFloatAsState(
            targetValue = targetProgress,
            animationSpec = tween(FuoMotion.progressAnimationMillis),
            label = "mini player progress",
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        )
    }
}

@Composable
private fun MiniPlayerLyricLine(state: PlaybackState) {
    val lines = remember(state.lyrics) { parseLyrics(state.lyrics) }
    val currentIndex = currentLyricIndex(lines, state.positionMs)
    val currentLine = lines.getOrNull(currentIndex)?.text?.takeIf { it.isNotBlank() } ?: return

    AnimatedContent(
        targetState = MiniPlayerLyricState(currentIndex, currentLine),
        transitionSpec = {
            val direction = if (targetState.index >= initialState.index) 1 else -1
            val enterOffset: (Int) -> Int = { height -> if (direction > 0) height else -height }
            val exitOffset: (Int) -> Int = { height -> if (direction > 0) -height else height }
            (slideInVertically(
                animationSpec = tween(180),
                initialOffsetY = enterOffset,
            ) + fadeIn(animationSpec = tween(180))) togetherWith
                (slideOutVertically(
                    animationSpec = tween(180),
                    targetOffsetY = exitOffset,
                ) + fadeOut(animationSpec = tween(180)))
        },
        modifier = Modifier.fillMaxWidth(),
        label = "mini player lyric line",
    ) { lyric ->
        Text(
            text = lyric.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class MiniPlayerLyricState(
    val index: Int,
    val text: String,
)

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun PlayingProgressIndicator(
    progress: () -> Float,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    LinearWavyProgressIndicator(
        progress = progress,
        modifier = modifier,
        amplitude = { value ->
            if (isPlaying) {
                WavyProgressIndicatorDefaults.indicatorAmplitude(value)
            } else {
                0f
            }
        },
    )
}

private fun playerCoverTransition(direction: TrackChangeDirection): ContentTransform {
    val directionMultiplier = if (direction == TrackChangeDirection.Next) 1 else -1
    return (
        slideInHorizontally(
            initialOffsetX = { width -> width * directionMultiplier },
            animationSpec = tween(FuoMotion.coverTransitionMillis),
        ) + fadeIn(animationSpec = tween(FuoMotion.coverFadeMillis))
        ) togetherWith (
        slideOutHorizontally(
            targetOffsetX = { width -> -width * directionMultiplier },
            animationSpec = tween(FuoMotion.coverTransitionMillis),
        ) + fadeOut(animationSpec = tween(FuoMotion.coverFadeMillis))
        )
}

@Composable
fun FullPlayer(controller: FuoPlayerController) {
    PlayerDynamicColorTheme(
        themeMode = controller.themeMode,
        dynamicCoverColorEnabled = controller.dynamicCoverColorEnabled,
        coverImageUrl = controller.playbackState.currentTrack?.coverUrl,
        isLoading = controller.playbackState.status == PlayerStatus.Loading,
    ) {
        FullPlayerContent(controller)
    }
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun FullPlayerContent(controller: FuoPlayerController) {
    val state = controller.playbackState
    val currentTrack = state.currentTrack
    val pagerState = rememberPagerState(
        initialPage = PlayerVisualTab.Cover.ordinal,
        pageCount = { PlayerVisualTab.entries.size },
    )
    val scope = rememberCoroutineScope()
    if (LocalAppLayoutInfo.current.isLandscape) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 68.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = controller::closeFullPlayer) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起播放器")
                        }
                        Text(
                            text = "正在播放",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        IconButton(onClick = controller::toggleQueue) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "播放队列")
                        }
                    }
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        val lyricsPaneWidth = (maxWidth * 0.46f)
                            .coerceAtLeast(240.dp)
                            .coerceAtMost(maxWidth * 0.52f)
                        Row(
                            modifier = Modifier
                                .fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .width(lyricsPaneWidth)
                                    .fillMaxHeight(),
                            ) {
                                LyricsPanel(
                                    state = state,
                                    fontSize = controller.lyricFontSize,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    PlayerCoverPage(
                                        track = currentTrack,
                                        controller = controller,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 260.dp),
                                    )
                                    PlayerTitleBlock(
                                        currentTrack,
                                        state.audioQuality,
                                        currentPlaybackPartLabel(state),
                                        state.audioFormatInfo,
                                        state.audioDecoderInfo,
                                        onOpenReplacementDetail = controller::openReplacementTrackDetail,
                                    )
                                    Text(
                                        text = currentTrack?.let(::artistAlbumLabel).orEmpty(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                ProgressBlock(state, controller::seekTo)
                                PlayerControls(
                                    state = state,
                                    modifier = Modifier.fillMaxWidth(),
                                    onPrevious = controller::previous,
                                    onToggle = controller::toggle,
                                    onNext = controller::next,
                                    shuffleEnabled = controller.isShuffleEnabled,
                                    shuffleAvailable = !controller.isFmQueueActive,
                                    onShuffle = controller::toggleShuffle,
                                    extraAction = currentTrack?.let { track ->
                                        {
                                            NowPlayingTrackAction(controller = controller, track = track)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                QueueBottomSheet(controller)
            }
        }
        return
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 82.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = controller::closeFullPlayer) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起播放器")
                    }
                    Text(
                        text = "正在播放",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = controller::toggleQueue) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "播放队列")
                    }
                }
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage.coerceIn(0, PlayerVisualTab.entries.lastIndex)) {
                    PlayerVisualTab.entries.forEach { tab ->
                        Tab(
                            selected = pagerState.currentPage == tab.ordinal,
                            onClick = { scope.launch { pagerState.animateScrollToPage(tab.ordinal) } },
                            text = { Text(tab.title) },
                        )
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    pageSpacing = 16.dp,
                ) { page ->
                    when (PlayerVisualTab.entries[page]) {
                        PlayerVisualTab.Cover -> PlayerCoverPage(currentTrack, controller)
                        PlayerVisualTab.Lyrics -> LyricsPanel(
                            state = state,
                            fontSize = controller.lyricFontSize,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                PlayerTitleBlock(
                    currentTrack,
                    state.audioQuality,
                    currentPlaybackPartLabel(state),
                    state.audioFormatInfo,
                    state.audioDecoderInfo,
                    onOpenReplacementDetail = controller::openReplacementTrackDetail,
                )
                Text(
                    text = currentTrack?.let(::artistAlbumLabel).orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ProgressBlock(state, controller::seekTo)
                PlayerControls(
                    state = state,
                    modifier = Modifier.fillMaxWidth(),
                    onPrevious = controller::previous,
                    onToggle = controller::toggle,
                    onNext = controller::next,
                    shuffleEnabled = controller.isShuffleEnabled,
                    shuffleAvailable = !controller.isFmQueueActive,
                    onShuffle = controller::toggleShuffle,
                    extraAction = currentTrack?.let { track ->
                        {
                            NowPlayingTrackAction(controller = controller, track = track)
                        }
                    },
                )
            }
            QueueBottomSheet(controller)
        }
    }
}

@Composable
fun NowPlayingTrackAction(controller: FuoPlayerController, track: MusicTrack) {
    val onShare = LocalShareHandler.current
    val sharePayload = track.toSharePayload()
    TrackAction(
        track = track,
        downloadState = controller.downloadStates[track.id],
        onAddToUpNext = { controller.addToUpNext(track) },
        onDownload = { controller.download(track) },
        onDeleteDownload = { controller.deleteDownload(track) },
        onOpenArtist = { controller.openTrackArtist(track) },
        onOpenAlbum = { controller.openTrackAlbum(track) },
        onOpenDetail = if (track.sourceType == TrackSourceType.Provider) {
            { controller.openOriginalTrackDetail(track) }
        } else {
            null
        },
        onEditLocalMetadata = if (track.sourceType == TrackSourceType.LocalMediaStore) {
            { controller.openLocalMetadataEditor(track) }
        } else {
            null
        },
        onAddToPlaylist = addToPlaylistAction(controller, track),
        onRemoveFromProviderPlaylist = removeFromSelectedPlaylistAction(controller, track),
        onSetDisliked = controller.canSetSongDisliked(track, true).takeIf { it }?.let {
            { controller.setSongDisliked(track, true) }
        },
        dislikedActionLabel = "不喜欢",
        onShare = sharePayload?.let { payload -> { onShare(payload) } },
        roundButton = true,
    )
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun PlayerCoverPage(
    track: MusicTrack?,
    controller: FuoPlayerController,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    BoxWithConstraints(modifier = modifier) {
        val coverSize = minOf(maxWidth, maxHeight * 0.82f)
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlayerSharedCover(
                track = track ?: emptyDisplayTrack(),
                heroEnabled = controller.isFullPlayerOpen,
                transitionDirection = controller.trackChangeDirection,
                isLoading = controller.playbackState.status == PlayerStatus.Loading,
                cornerRadius = 22.dp,
                modifier = Modifier.size(coverSize),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun PlayerSharedCover(
    track: MusicTrack,
    heroEnabled: Boolean,
    transitionDirection: TrackChangeDirection = TrackChangeDirection.Next,
    isLoading: Boolean = false,
    cornerRadius: androidx.compose.ui.unit.Dp = 8.dp,
    modifier: Modifier = Modifier,
) {
    val targetCoverImage = rememberPlatformCoverImage(track.coverUrl)
    val hasCoverUrl = !track.coverUrl.isNullOrBlank()
    var displayedTrack by remember { mutableStateOf(track) }
    LaunchedEffect(track.id, track.coverUrl, isLoading, targetCoverImage) {
        if (!isLoading || (hasCoverUrl && targetCoverImage != null)) {
            displayedTrack = track
        }
    }
    val sharedTransitionScope = LocalPlayerSharedTransitionScope.current
    val sharedModifier = if (!heroEnabled || sharedTransitionScope == null) {
        modifier
    } else {
        with(sharedTransitionScope) {
            modifier.sharedElementWithCallerManagedVisibility(
                sharedContentState = rememberSharedContentState("player-cover:${track.id}"),
                visible = true,
            )
        }
    }
    Box(modifier = sharedModifier) {
        AnimatedContent(
            targetState = displayedTrack,
            transitionSpec = { playerCoverTransition(transitionDirection) },
            modifier = Modifier.fillMaxSize(),
            contentKey = { it.coverUrl },
            label = "player cover transition",
        ) { animatedTrack ->
            CoverBox(
                track = animatedTrack,
                cornerRadius = cornerRadius,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

enum class PlayerVisualTab(val title: String) {
    Cover("封面"),
    Lyrics("歌词"),
}

@Composable
fun PlayerTitleBlock(
    track: MusicTrack?,
    audioQuality: String?,
    partLabel: String?,
    audioFormatInfo: AudioFormatInfo?,
    audioDecoderInfo: AudioDecoderInfo?,
    onOpenReplacementDetail: ((MusicTrack) -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = track?.title ?: "未播放",
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        partLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PlayerInfoTags(
            track = track,
            audioQuality = audioQuality,
            audioFormatInfo = audioFormatInfo,
            audioDecoderInfo = audioDecoderInfo,
            onOpenReplacementDetail = onOpenReplacementDetail,
        )
    }
}

@Composable
fun PlayerInfoTags(
    track: MusicTrack?,
    audioQuality: String?,
    audioFormatInfo: AudioFormatInfo?,
    audioDecoderInfo: AudioDecoderInfo?,
    onOpenReplacementDetail: ((MusicTrack) -> Unit)? = null,
) {
    var replacementInfoTrack by remember(track?.id) { mutableStateOf<MusicTrack?>(null) }
    var showAudioFormatInfo by remember(track?.id) { mutableStateOf(false) }
    val canShowAudioInfo = audioFormatInfo?.hasDisplayableValue() == true || audioDecoderInfo != null
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (track != null) {
            if (track.isSmartReplacement) {
                InfoTag(
                    text = replacementSourceLabel(track),
                    onClick = { replacementInfoTrack = track },
                )
            } else {
                InfoTag(sourceLabel(track, null))
            }
        }
        audioQuality?.takeIf { it.isNotBlank() }?.let {
            InfoTag(
                text = it.uppercase(),
                onClick = if (canShowAudioInfo) ({ showAudioFormatInfo = true }) else null,
            )
        }
    }
    replacementInfoTrack?.let { infoTrack ->
        ReplacementInfoDialog(
            track = infoTrack,
            onDismiss = { replacementInfoTrack = null },
            onOpenDetail = onOpenReplacementDetail
                ?.takeIf { infoTrack.replacementId?.isNotBlank() == true }
                ?.let { openDetail -> { openDetail(infoTrack) } },
        )
    }
    if (showAudioFormatInfo) {
        AudioFormatInfoDialog(
            info = audioFormatInfo,
            decoderInfo = audioDecoderInfo,
            onDismiss = { showAudioFormatInfo = false },
        )
    }
}

@Composable
fun AudioFormatInfoDialog(
    info: AudioFormatInfo?,
    decoderInfo: AudioDecoderInfo? = null,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("音频信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                info?.format?.takeIf { it.isNotBlank() }?.let { ReplacementInfoLine("当前格式", it) }
                info?.codec?.takeIf { it.isNotBlank() }?.let { ReplacementInfoLine("编码", it) }
                formatAudioBitrate(info?.averageBitrate)?.let { ReplacementInfoLine("平均比特率", it) }
                formatAudioBitrate(info?.peakBitrate)?.let { ReplacementInfoLine("峰值比特率", it) }
                decoderInfo?.let { decoder ->
                    ReplacementInfoLine(
                        "解码方式",
                        if (decoder.type == AudioDecoderType.Software) "软件解码" else "硬件解码",
                    )
                    decoder.name.takeIf { it.isNotBlank() }?.let {
                        ReplacementInfoLine("解码器", it)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

fun AudioFormatInfo.hasDisplayableValue(): Boolean {
    return !format.isNullOrBlank() ||
        !codec.isNullOrBlank() ||
        formatAudioBitrate(averageBitrate) != null ||
        formatAudioBitrate(peakBitrate) != null
}

fun formatAudioBitrate(value: Long?): String? {
    if (value == null || value <= 0) return null
    return "${(value / 1_000.0).roundToInt()} kbps"
}

@Composable
fun InfoTag(text: String, onClick: (() -> Unit)? = null) {
    FuoMetadataChip(label = text, onClick = onClick)
}

@Composable
fun ReplacementInfoDialog(
    track: MusicTrack,
    onDismiss: () -> Unit,
    onOpenDetail: (() -> Unit)? = null,
) {
    val detailAction = onOpenDetail?.takeIf { track.replacementId?.isNotBlank() == true }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("替换音频") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReplacementInfoLine("标题", track.replacementTitle ?: track.title)
                ReplacementInfoLine("歌手", track.replacementArtists ?: track.artists)
                replacementProviderLabel(track).takeIf { it.isNotBlank() }?.let {
                    ReplacementInfoLine("来源", it)
                }
                track.replacementStrategy?.let {
                    ReplacementInfoLine("策略", it)
                }
                track.replacementScore?.let {
                    ReplacementInfoLine("匹配度", formatSmartReplacementScore(it))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    if (detailAction != null) detailAction()
                },
            ) {
                Text(if (detailAction != null) "歌曲详情" else "关闭")
            }
        },
        dismissButton = if (detailAction != null) {
            {
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        } else {
            null
        },
    )
}

@Composable
fun ReplacementInfoLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(controller: FuoPlayerController) {
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    if (!isWideLayout) {
        if (controller.isQueueOpen) {
            ModalBottomSheet(
                onDismissRequest = controller::toggleQueue,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                QueueBottomSheetContent(controller, sidePanel = false, embedded = true)
            }
        }
        return
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = if (isWideLayout) Alignment.CenterEnd else Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = controller.isQueueOpen,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(140)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .clickable(role = Role.Button, onClick = controller::toggleQueue),
            )
        }
        AnimatedVisibility(
            visible = controller.isQueueOpen,
            modifier = Modifier.align(if (isWideLayout) Alignment.CenterEnd else Alignment.BottomCenter),
            enter = if (isWideLayout) {
                slideInHorizontally(animationSpec = tween(FuoMotion.overlayEnterMillis)) { it } + fadeIn(tween(FuoMotion.overlayFadeMillis))
            } else {
                slideInVertically(animationSpec = tween(FuoMotion.overlayEnterMillis)) { it } + fadeIn(tween(FuoMotion.overlayFadeMillis))
            },
            exit = if (isWideLayout) {
                slideOutHorizontally(animationSpec = tween(FuoMotion.overlayExitMillis)) { it } + fadeOut(tween(FuoMotion.overlayFadeMillis))
            } else {
                slideOutVertically(animationSpec = tween(FuoMotion.overlayExitMillis)) { it } + fadeOut(tween(FuoMotion.overlayFadeMillis))
            },
        ) {
            QueueBottomSheetContent(controller, sidePanel = true)
        }
    }
}

@Composable
fun QueueBottomSheetContent(
    controller: FuoPlayerController,
    sidePanel: Boolean = false,
    embedded: Boolean = false,
) {
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    val queueSize = controller.playbackState.queue.size

    Surface(
        modifier = if (sidePanel) {
            Modifier
                .width(380.dp)
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
                .clickable { }
        } else {
            Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .then(if (embedded) Modifier else Modifier.navigationBarsPadding())
        },
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 8.dp,
        shape = if (sidePanel) {
            MaterialTheme.shapes.large
        } else {
            MaterialTheme.shapes.large
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QueueRepeatModeHeader(
                isFmQueue = controller.isFmQueueActive,
                repeatMode = controller.repeatMode,
                onRepeat = controller::toggleRepeat,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "播放队列",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$queueSize 首",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (queueSize > 1) {
                        IconButton(onClick = { showClearConfirmDialog = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "清空队列")
                        }
                    }
                }
            }
            QueueList(
                controller = controller,
                modifier = if (sidePanel) {
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                },
            )
        }
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("清空播放队列") },
            text = { Text("确定要清空播放队列吗？当前播放的歌曲将保留。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirmDialog = false
                    controller.clearQueue()
                }) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
fun QueueRepeatModeHeader(
    isFmQueue: Boolean,
    repeatMode: RepeatMode,
    onRepeat: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "播放模式",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isFmQueue) {
                FmModeBadge()
            } else {
                RepeatModeTextButton(
                    repeatMode = repeatMode,
                    onRepeat = onRepeat,
                )
            }
        }
    }
}

@Composable
fun FmModeBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Text(
            text = "FM",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

@Composable
fun RepeatModeTextButton(
    repeatMode: RepeatMode,
    onRepeat: () -> Unit,
) {
    val repeatIcon = when (repeatMode) {
        RepeatMode.OFF -> Icons.Filled.Repeat
        RepeatMode.QUEUE -> Icons.Filled.Repeat
        RepeatMode.SINGLE -> Icons.Filled.RepeatOne
    }
    FilterChip(
        selected = repeatMode != RepeatMode.OFF,
        onClick = onRepeat,
        label = { Text(repeatMode.label, maxLines = 1) },
        leadingIcon = {
            Icon(
                imageVector = repeatIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

fun emptyDisplayTrack() = MusicTrack(
    id = "empty",
    title = "FeelUOwn",
    artists = "",
    album = "",
    source = "",
    sourceType = TrackSourceType.LocalMediaStore,
)

@Composable
fun PlayerControls(
    state: PlaybackState,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    shuffleEnabled: Boolean = false,
    repeatMode: RepeatMode = RepeatMode.QUEUE,
    shuffleAvailable: Boolean = true,
    onShuffle: (() -> Unit)? = null,
    onRepeat: (() -> Unit)? = null,
    extraAction: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.animateContentSize(animationSpec = tween(220)),
        horizontalArrangement = if (compact) Arrangement.spacedBy(8.dp) else Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!compact && onShuffle != null) {
            RoundControlButton(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = if (shuffleAvailable) "随机播放" else "私人 FM 使用顺序播放",
                onClick = onShuffle,
                size = 48.dp,
                iconSize = 24.dp,
                selected = shuffleEnabled,
                enabled = shuffleAvailable,
            )
        }
        RoundControlButton(
            imageVector = Icons.Filled.SkipPrevious,
            contentDescription = "上一首",
            onClick = onPrevious,
            size = 48.dp,
            iconSize = if (compact) 24.dp else 26.dp,
        )
        PlayPauseButton(
            isPlaying = state.status == PlayerStatus.Playing,
            isLoading = state.status == PlayerStatus.Loading,
            onClick = onToggle,
            size = if (compact) 48.dp else 64.dp,
            iconSize = if (compact) 26.dp else 34.dp,
            prominent = !compact,
        )
        RoundControlButton(
            imageVector = Icons.Filled.SkipNext,
            contentDescription = "下一首",
            onClick = onNext,
            size = 48.dp,
            iconSize = if (compact) 24.dp else 26.dp,
        )
        when {
            !compact && extraAction != null -> extraAction()
            !compact && onRepeat != null -> RepeatModeTextButton(
                repeatMode = repeatMode,
                onRepeat = onRepeat,
            )
        }
    }
}

@Composable
fun RoundControlButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    iconSize: androidx.compose.ui.unit.Dp = 26.dp,
    prominent: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val buttonSize = if (size < 48.dp) 48.dp else size
    val buttonModifier = Modifier.size(buttonSize)
    if (prominent || selected) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    } else {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
        ) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 52.dp,
    iconSize: androidx.compose.ui.unit.Dp = 28.dp,
    prominent: Boolean = false,
) {
    if (isLoading) {
        Surface(
            modifier = Modifier.size(if (size < 48.dp) 48.dp else size),
            color = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = if (prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            tonalElevation = if (prominent) 3.dp else 1.dp,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(iconSize),
                    strokeWidth = 2.dp,
                    color = if (prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                )
            }
        }
        return
    }
    RoundControlButton(
        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
        contentDescription = if (isPlaying) "暂停" else "播放",
        onClick = onClick,
        size = size,
        iconSize = iconSize,
        prominent = prominent,
    )
}

@Composable
fun ProgressBlock(state: PlaybackState, onSeek: (Long) -> Unit) {
    val duration = state.durationMs.takeIf { it > 0 } ?: 1L
    val canSeek = state.currentTrack != null &&
        state.durationMs > 0 &&
        state.status != PlayerStatus.Idle &&
        state.status != PlayerStatus.Loading &&
        state.status != PlayerStatus.Error
    var isSeeking by remember(state.currentTrack?.id) { mutableStateOf(false) }
    var seekPosition by remember(state.currentTrack?.id) {
        mutableStateOf(state.positionMs.coerceIn(0, duration).toFloat())
    }
    val animatedSeekPosition by animateFloatAsState(
        targetValue = seekPosition.coerceIn(0f, duration.toFloat()),
        animationSpec = tween(FuoMotion.progressAnimationMillis),
        label = "player progress",
    )
    val displayedSeekPosition = if (isSeeking) seekPosition else animatedSeekPosition

    LaunchedEffect(state.positionMs, duration, isSeeking, canSeek) {
        if (!isSeeking || !canSeek) {
            seekPosition = state.positionMs.coerceIn(0, duration).toFloat()
            if (!canSeek) isSeeking = false
        }
    }

    Slider(
        value = displayedSeekPosition.coerceIn(0f, duration.toFloat()),
        onValueChange = {
            isSeeking = true
            seekPosition = it
        },
        onValueChangeFinished = {
            if (isSeeking && canSeek) {
                onSeek(seekPosition.toLong().coerceIn(0, duration))
                isSeeking = false
            }
        },
        enabled = canSeek,
        valueRange = 0f..duration.toFloat(),
        track = { sliderState ->
            PlayingProgressIndicator(
                progress = { sliderState.value / duration.toFloat() },
                isPlaying = canSeek && state.status == PlayerStatus.Playing,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            formatMs(if (isSeeking) seekPosition.toLong() else state.positionMs),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(formatMs(state.durationMs), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun LyricsPanel(state: PlaybackState, fontSize: LyricFontSize, modifier: Modifier) {
    val lines = remember(state.lyrics) { parseLyrics(state.lyrics) }
    val listState = rememberLazyListState()
    val renderPositionMs = rememberKaraokePositionMs(
        positionMs = state.positionMs,
        isPlaying = state.status == PlayerStatus.Playing,
    )
    val currentIndex by remember(lines, renderPositionMs) {
        androidx.compose.runtime.derivedStateOf {
            currentLyricIndex(lines, renderPositionMs.value)
        }
    }
    val activeStyle = when (fontSize) {
        LyricFontSize.Small -> MaterialTheme.typography.titleMedium
        LyricFontSize.Medium -> MaterialTheme.typography.titleLarge
        LyricFontSize.Large -> MaterialTheme.typography.headlineSmall
    }
    val inactiveStyle = when (fontSize) {
        LyricFontSize.Small -> MaterialTheme.typography.bodyMedium
        LyricFontSize.Medium -> MaterialTheme.typography.bodyLarge
        LyricFontSize.Large -> MaterialTheme.typography.titleMedium
    }
    val translationStyle = when (fontSize) {
        LyricFontSize.Small -> MaterialTheme.typography.bodySmall
        LyricFontSize.Medium -> MaterialTheme.typography.bodyMedium
        LyricFontSize.Large -> MaterialTheme.typography.bodyLarge
    }
    val linePadding = when (fontSize) {
        LyricFontSize.Small -> 6.dp
        LyricFontSize.Medium -> 7.dp
        LyricFontSize.Large -> 8.dp
    }

    LaunchedEffect(currentIndex, lines.size) {
        if (currentIndex >= 0) {
            listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
        }
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(FuoSpacing.lg),
        ) {
            val displayLines = lines.takeIf { it.isNotEmpty() } ?: listOf(LyricLine(0, "暂无歌词"))
            itemsIndexed(displayLines) { index, line ->
                val active = index == currentIndex
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = linePadding),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (active && !line.words.isNullOrEmpty()) {
                        KaraokeLyricText(
                            words = line.words,
                            positionMs = renderPositionMs,
                            style = activeStyle,
                            activeColor = MaterialTheme.colorScheme.primary,
                            // Keep the unsung portion clearly muted so primary fill pops.
                            inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = line.text,
                            style = if (active) activeStyle else inactiveStyle,
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f)
                            },
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    line.translation?.takeIf { it.isNotBlank() }?.let { translation ->
                        Text(
                            text = translation,
                            style = translationStyle,
                            color = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (active) 0.52f else 0.38f,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberKaraokePositionMs(
    positionMs: Long,
    isPlaying: Boolean,
): androidx.compose.runtime.State<Long> {
    val renderPositionMs = remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, isPlaying) {
        renderPositionMs.longValue = positionMs
        if (!isPlaying) return@LaunchedEffect
        val anchorPosition = positionMs
        val anchorFrame = withFrameNanos { it }
        while (true) {
            withFrameNanos { frame ->
                val elapsedMs = (frame - anchorFrame) / 1_000_000L
                renderPositionMs.longValue = anchorPosition + elapsedMs
            }
        }
    }
    return renderPositionMs
}

@Composable
private fun KaraokeLyricText(
    words: List<LyricWord>,
    positionMs: androidx.compose.runtime.State<Long>,
    style: TextStyle,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
) {
    val text = remember(words) { words.joinToString("") { it.text } }
    val textMeasurer = rememberTextMeasurer()
    val fontSize = style.fontSize
    val wordWidths = remember(words, fontSize, textMeasurer) {
        val measureStyle = style.copy(fontWeight = FontWeight.SemiBold)
        words.map { word ->
            textMeasurer.measure(
                text = word.text,
                style = measureStyle,
                constraints = Constraints(),
            ).size.width.toFloat()
        }
    }
    val progress = karaokeFillProgress(words, positionMs.value, wordWidths)
    val textStyle = style.copy(fontWeight = FontWeight.SemiBold)

    BoxWithConstraints(modifier = modifier) {
        val layoutResult = remember(text, textStyle, constraints.maxWidth, textMeasurer) {
            textMeasurer.measure(
                text = text,
                style = textStyle,
                constraints = Constraints(maxWidth = constraints.maxWidth),
            )
        }
        val totalVisualWidth = remember(layoutResult) {
            (0 until layoutResult.lineCount).sumOf { lineIndex ->
                (layoutResult.getLineRight(lineIndex) - layoutResult.getLineLeft(lineIndex))
                    .coerceAtLeast(0f)
                    .toDouble()
            }.toFloat()
        }

        Text(
            text = text,
            style = textStyle,
            color = inactiveColor,
            softWrap = true,
        )
        Text(
            text = text,
            style = textStyle,
            color = activeColor,
            softWrap = true,
            modifier = Modifier.drawWithContent {
                var remainingWidth = totalVisualWidth * progress.coerceIn(0f, 1f)
                if (remainingWidth <= 0f || !remainingWidth.isFinite()) return@drawWithContent

                for (lineIndex in 0 until layoutResult.lineCount) {
                    val lineLeft = layoutResult.getLineLeft(lineIndex)
                    val lineRight = layoutResult.getLineRight(lineIndex)
                    val lineWidth = (lineRight - lineLeft).coerceAtLeast(0f)
                    if (lineWidth <= 0f) continue

                    val lineFillWidth = minOf(remainingWidth, lineWidth)
                    if (lineFillWidth > 0f) {
                        clipRect(
                            left = lineLeft,
                            top = layoutResult.getLineTop(lineIndex),
                            right = lineLeft + lineFillWidth,
                            bottom = layoutResult.getLineBottom(lineIndex),
                        ) {
                            this@drawWithContent.drawContent()
                        }
                    }
                    remainingWidth -= lineWidth
                    if (remainingWidth <= 0f) break
                }
            },
        )
    }
}

@Composable
fun QueueList(controller: FuoPlayerController, modifier: Modifier) {
    val queue = controller.playbackState.queue
    val playbackParts = controller.playbackState.playbackParts
    val currentPartIndex = controller.playbackState.currentPartIndex
    val currentCount = if (controller.playbackState.queueIndex == 0 && queue.isNotEmpty()) 1 else 0
    val upNextCount = controller.displayUpNextCount
    LazyColumn(
        modifier = modifier,
    ) {
        itemsIndexed(queue) { index, track ->
            if (index == 0 && currentCount == 1) {
                Text(
                    text = "当前播放",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
            }
            if (index == currentCount && upNextCount > 0) {
                Text(
                    text = "接下来播放",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
            }
            if (index == currentCount + upNextCount && index < queue.size && index > 0) {
                Text(
                    text = "队列后续",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
            }
            val isCurrent = index == controller.playbackState.queueIndex
            val isUnavailable = track.isUnavailable
            val titleColor = when {
                isUnavailable -> MaterialTheme.colorScheme.onSurfaceVariant
                isCurrent -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fuoInteractive()
                    .clickable(
                        enabled = !isUnavailable,
                        role = Role.Button,
                    ) { controller.playQueueIndex(index) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverBox(track, modifier = Modifier.size(48.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${index + 1}. ${track.title.ifBlank { "未知歌曲" }}",
                        style = MaterialTheme.typography.titleMedium,
                        color = titleColor,
                        fontWeight = if (isCurrent && !isUnavailable) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = listOf(track.artists, track.album).filter { it.isNotBlank() }.joinToString(" · ")
                            .ifBlank { "未知歌手" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = sourceLabel(track, null),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { controller.removeFromQueue(track) }) {
                    Icon(Icons.Filled.RemoveCircleOutline, contentDescription = "从队列移除")
                }
            }
            if (isCurrent && playbackParts.isNotEmpty()) {
                PlaybackPartList(
                    parts = playbackParts,
                    currentPartIndex = currentPartIndex,
                    onPartClick = controller::playPlaybackPart,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun PlaybackPartList(
    parts: List<PlaybackPart>,
    currentPartIndex: Int,
    onPartClick: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "分 P 列表",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        parts.forEachIndexed { index, part ->
            val selected = index == currentPartIndex
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fuoInteractive()
                    .clickable(role = Role.Button) { onPartClick(index) },
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "P${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        text = part.title.ifBlank { "未命名分段" },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    part.durationMs?.takeIf { it > 0 }?.let {
                        Text(
                            text = formatMs(it),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

fun currentPlaybackPartLabel(state: PlaybackState): String? {
    val part = state.playbackParts.getOrNull(state.currentPartIndex) ?: return null
    return "第 ${state.currentPartIndex + 1}P · ${part.title.ifBlank { "未命名分段" }}"
}

fun sourceLabel(track: MusicTrack, downloadState: DownloadState?): String {
    val state = when (downloadState) {
        is DownloadState.Downloaded -> "已下载"
        is DownloadState.Downloading -> "下载中"
        DownloadState.Paused -> "下载已暂停"
        is DownloadState.Failed -> "下载失败"
        DownloadState.Queued -> "等待下载"
        else -> null
    }
    return listOfNotNull(
        when (track.sourceType) {
            TrackSourceType.Provider -> track.providerName ?: track.source.ifBlank { "音源" }
            TrackSourceType.LocalMediaStore -> "本地"
            TrackSourceType.Downloaded -> "本地"
        },
        "智能替换".takeIf { track.isSmartReplacement },
        "不可用".takeIf { track.isUnavailable },
        state,
    ).joinToString(" · ")
}

fun replacementSourceLabel(track: MusicTrack): String {
    val source = track.replacementProviderName?.takeIf { it.isNotBlank() }
        ?: track.providerName?.takeIf { it.isNotBlank() }
        ?: track.replacementSource?.takeIf { it.isNotBlank() }
        ?: "音源"
    return "换源 • $source"
}

fun replacementProviderLabel(track: MusicTrack): String {
    return listOfNotNull(
        track.replacementProviderName ?: track.providerName,
        track.replacementSource?.takeIf { it != track.replacementProviderName },
    )
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" · ")
}

fun artistAlbumLabel(track: MusicTrack): String {
    return listOf(track.artists, track.album)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

fun formatPlayCount(value: Long): String {
    return when {
        value >= 100_000_000 -> "${value / 100_000_000} 亿次播放"
        value >= 10_000 -> "${value / 10_000} 万次播放"
        else -> "$value 次播放"
    }
}

fun formatBytes(value: Long): String {
    return when {
        value >= 1024L * 1024L * 1024L -> "${value / (1024L * 1024L * 1024L)} GB"
        value >= 1024L * 1024L -> "${value / (1024L * 1024L)} MB"
        value >= 1024L -> "${value / 1024L} KB"
        else -> "$value B"
    }
}

fun formatCacheLimit(value: Int): String {
    return if (value >= 1024 && value % 1024 == 0) {
        "${value / 1024}GB"
    } else {
        "${value}MB"
    }
}

fun formatSmartReplacementScore(value: Double): String {
    val hundred = (value.coerceIn(0.0, 1.0) * 100).roundToInt()
    return "${hundred / 100}.${(hundred % 100).toString().padStart(2, '0')}"
}

fun roundSmartReplacementScore(value: Double): Double {
    return (value.coerceIn(0.0, 1.0) * 20).roundToInt() / 20.0
}

fun localTitleSection(title: String): String {
    val first = title.trim().firstOrNull()?.uppercaseChar() ?: return "#"
    return if (first in 'A'..'Z') first.toString() else "#"
}

fun localTitleSectionOrder(section: String): Int {
    val first = section.firstOrNull() ?: return 26
    return if (first in 'A'..'Z') first - 'A' else 26
}

fun normalizedGroupName(value: String, fallback: String): String {
    return value.trim().ifBlank { fallback }
}

fun formatMs(value: Long): String {
    val totalSeconds = (value / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

data class LyricWord(
    val startMs: Long,
    val durationMs: Long,
    val text: String,
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    val words: List<LyricWord>? = null,
)

private data class RawLyricLine(
    val timeMs: Long,
    val text: String,
    val order: Int,
)

const val LYRIC_TRANSLATION_MARKER = "\n__FUO_LYRIC_TRANSLATION__\n"

fun composeLyricsWithTranslation(main: String, translation: String?): String {
    val trimmedMain = main.trimEnd()
    val trimmedTranslation = translation?.trim()?.takeIf { it.isNotBlank() } ?: return trimmedMain
    return trimmedMain + LYRIC_TRANSLATION_MARKER + trimmedTranslation
}

fun parseLyrics(raw: String?): List<LyricLine> {
    if (raw.isNullOrBlank()) return emptyList()
    val parts = raw.split(LYRIC_TRANSLATION_MARKER, limit = 2)
    val main = parts[0]
    val translationRaw = parts.getOrNull(1)
    val lines = if (main.lineSequence().any { yrcLineHeaderRegex.containsMatchIn(it.trim()) }) {
        parseYrc(main)
    } else {
        parseLrc(main)
    }
    return attachLyricTranslations(lines, translationRaw)
}

fun attachLyricTranslations(lines: List<LyricLine>, translationRaw: String?): List<LyricLine> {
    if (translationRaw.isNullOrBlank() || lines.isEmpty()) return lines
    val translationLines = parseLrc(translationRaw).filter { it.timeMs != Long.MAX_VALUE }
    if (translationLines.isEmpty()) return lines
    val byTime = translationLines
        .groupBy { it.timeMs }
        .mapValues { (_, value) ->
            value.flatMap { secondary ->
                listOfNotNull(secondary.text, secondary.translation)
            }
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString("\n")
        }
    return lines.map { line ->
        if (line.timeMs == Long.MAX_VALUE || !line.translation.isNullOrBlank()) return@map line
        val exact = byTime[line.timeMs]
        val nearestTime = byTime.keys
            .minByOrNull { kotlin.math.abs(it - line.timeMs) }
            ?.takeIf { kotlin.math.abs(it - line.timeMs) <= 50 }
        line.copy(translation = exact ?: nearestTime?.let(byTime::get))
    }
}

fun parseYrc(raw: String?): List<LyricLine> {
    if (raw.isNullOrBlank()) return emptyList()
    val lines = mutableListOf<LyricLine>()
    raw.lineSequence().forEach { rawLine ->
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty() || trimmed.startsWith('{')) return@forEach
        val headerMatch = yrcLineHeaderRegex.find(trimmed) ?: return@forEach
        val startMs = headerMatch.groupValues[1].toLongOrNull() ?: return@forEach
        val body = trimmed.substring(headerMatch.range.last + 1)
        val words = yrcWordRegex.findAll(body).mapNotNull { match ->
            val wordStart = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val duration = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val text = match.groupValues[3]
            if (text.isEmpty()) null else LyricWord(wordStart, duration, text)
        }.toList()
        val text = words.joinToString("") { it.text }.ifBlank {
            body.replace(yrcWordRegex, "").trim()
        }
        if (text.isBlank()) return@forEach
        lines += LyricLine(
            timeMs = startMs,
            text = text,
            words = words.takeIf { it.isNotEmpty() },
        )
    }
    return lines.sortedBy { it.timeMs }
}

fun parseLrc(raw: String?): List<LyricLine> {
    if (raw.isNullOrBlank()) return emptyList()
    val parsedLines = mutableListOf<RawLyricLine>()
    raw.lines().forEachIndexed { order, line ->
        val matches = lrcTimeRegex.findAll(line).toList()
        val text = line.replace(lrcTimeRegex, "").trim()
        if (text.isBlank()) return@forEachIndexed
        if (matches.isEmpty()) {
            if (!lrcMetadataRegex.matches(line.trim())) {
                parsedLines += RawLyricLine(Long.MAX_VALUE, text, order)
            }
        } else {
            matches.forEach { match ->
                parsedLines += RawLyricLine(parseLrcTime(match.groupValues[1]), text, order)
            }
        }
    }

    val timedLines = parsedLines
        .filter { it.timeMs != Long.MAX_VALUE }
        .sortedWith(compareBy<RawLyricLine> { it.timeMs }.thenBy { it.order })
    val mergedLines = timedLines
        .groupBy { it.timeMs }
        .values
        .flatMap { sameTimeLines ->
            if (sameTimeLines.size == 2) {
                val original = sameTimeLines[0]
                val translation = sameTimeLines[1].text.takeIf { it != original.text }
                listOf(LyricLine(original.timeMs, original.text, translation))
            } else {
                sameTimeLines.map { LyricLine(it.timeMs, it.text) }
            }
        }
    val untimedLines = parsedLines
        .filter { it.timeMs == Long.MAX_VALUE }
        .sortedBy { it.order }
        .map { LyricLine(it.timeMs, it.text) }
    return mergedLines + untimedLines
}

fun parseLrcTime(value: String): Long {
    val minuteAndRest = value.split(':', limit = 2)
    if (minuteAndRest.size != 2) return 0
    val minutes = minuteAndRest[0].toLongOrNull() ?: return 0
    val secondAndFraction = minuteAndRest[1].split('.', limit = 2)
    val seconds = secondAndFraction[0].toLongOrNull() ?: 0
    val fraction = secondAndFraction.getOrNull(1)
        ?.padEnd(3, '0')
        ?.take(3)
        ?.toLongOrNull()
        ?: 0
    return minutes * 60_000 + seconds * 1_000 + fraction
}

fun currentLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    val timedLines = lines.takeWhile { it.timeMs != Long.MAX_VALUE }
    if (timedLines.isEmpty()) return -1
    val index = timedLines.indexOfLast { it.timeMs <= positionMs }
    return index.coerceAtLeast(0)
}

fun karaokeFillProgress(
    words: List<LyricWord>,
    positionMs: Long,
    wordWidths: List<Float>,
): Float {
    if (words.isEmpty() || wordWidths.isEmpty()) return 0f
    val totalWidth = wordWidths.sum()
    if (totalWidth <= 0f) return 0f
    var filledWidth = 0f
    val count = minOf(words.size, wordWidths.size)
    for (index in 0 until count) {
        val word = words[index]
        val width = wordWidths[index]
        val durationMs = word.durationMs.coerceAtLeast(1L)
        when {
            positionMs < word.startMs -> return (filledWidth / totalWidth).coerceIn(0f, 1f)
            positionMs >= word.startMs + durationMs -> filledWidth += width
            else -> {
                val fraction = ((positionMs - word.startMs).toFloat() / durationMs).coerceIn(0f, 1f)
                filledWidth += width * fraction
                return (filledWidth / totalWidth).coerceIn(0f, 1f)
            }
        }
    }
    return (filledWidth / totalWidth).coerceIn(0f, 1f)
}

val lrcTimeRegex = Regex("""\[(\d{1,3}:\d{1,2}(?:\.\d{1,3})?)]""")
val lrcMetadataRegex = Regex("""^\[[A-Za-z]+:.*]$""")
val yrcLineHeaderRegex = Regex("""^\[(\d+),(\d+)]""")
val yrcWordRegex = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")
