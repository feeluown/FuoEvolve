package org.feeluown.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun MiniPlayer(controller: FuoPlayerController) {
    val state = controller.playbackState
    val isLoadingAudio = state.status == PlayerStatus.Loading
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(220))
            .clickable(onClick = controller::openFullPlayer),
        tonalElevation = 3.dp,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = if (isWideLayout) 6.dp else 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.currentTrack?.let {
                    CoverBox(it, modifier = Modifier.size(if (isWideLayout) 40.dp else 48.dp))
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
                }
                PlayerControls(
                    state = state,
                    onPrevious = controller::previous,
                    onToggle = controller::toggle,
                    onNext = controller::next,
                    compact = true,
                )
            }
            if (isLoadingAudio) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun FullPlayer(controller: FuoPlayerController) {
    val state = controller.playbackState
    val currentTrack = state.currentTrack
    val pagerState = rememberPagerState(
        initialPage = PlayerVisualTab.Cover.ordinal,
        pageCount = { PlayerVisualTab.entries.size },
    )
    val scope = rememberCoroutineScope()
    LaunchedEffect(currentTrack?.id) {
        if (pagerState.currentPage != PlayerVisualTab.Cover.ordinal) {
            pagerState.scrollToPage(PlayerVisualTab.Cover.ordinal)
        }
    }
    if (LocalAppLayoutInfo.current.useWideLayout) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
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
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PrimaryTabRow(
                                selectedTabIndex = pagerState.currentPage.coerceIn(
                                    0,
                                    PlayerVisualTab.entries.lastIndex,
                                ),
                            ) {
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
                                    .weight(1f)
                                    .fillMaxWidth(),
                                pageSpacing = 16.dp,
                            ) { page ->
                                when (PlayerVisualTab.entries[page]) {
                                    PlayerVisualTab.Cover -> PlayerCoverPage(currentTrack)
                                    PlayerVisualTab.Lyrics -> LyricsPanel(
                                        state = state,
                                        fontSize = controller.lyricFontSize,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                        Column(
                            modifier = Modifier
                                .width(360.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PlayerTitleBlock(currentTrack, state.audioQuality, currentPlaybackPartLabel(state))
                            Text(
                                text = currentTrack?.let(::artistAlbumLabel).orEmpty(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
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
                                repeatMode = controller.repeatMode,
                                shuffleAvailable = !controller.isFmQueueActive,
                                onShuffle = controller::toggleShuffle,
                                onRepeat = controller::toggleRepeat,
                            )
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
            val visualHeight = (maxHeight * 0.42f).coerceAtMost(360.dp)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 36.dp),
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
                        .height(visualHeight),
                    pageSpacing = 16.dp,
                ) { page ->
                    when (PlayerVisualTab.entries[page]) {
                        PlayerVisualTab.Cover -> PlayerCoverPage(currentTrack)
                        PlayerVisualTab.Lyrics -> LyricsPanel(
                            state = state,
                            fontSize = controller.lyricFontSize,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                PlayerTitleBlock(currentTrack, state.audioQuality, currentPlaybackPartLabel(state))
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
                    repeatMode = controller.repeatMode,
                    shuffleAvailable = !controller.isFmQueueActive,
                    onShuffle = controller::toggleShuffle,
                    onRepeat = controller::toggleRepeat,
                )
            }
            QueueBottomSheet(controller)
        }
    }
}

@Composable
fun PlayerCoverPage(track: MusicTrack?) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CoverBox(
            track = track ?: emptyDisplayTrack(),
            modifier = Modifier.size(minOf(maxWidth, maxHeight)),
        )
    }
}

enum class PlayerVisualTab(val title: String) {
    Cover("封面"),
    Lyrics("歌词"),
}

@Composable
fun PlayerTitleBlock(track: MusicTrack?, audioQuality: String?, partLabel: String?) {
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
        PlayerInfoTags(track, audioQuality)
    }
}

@Composable
fun PlayerInfoTags(track: MusicTrack?, audioQuality: String?) {
    var replacementInfoTrack by remember(track?.id) { mutableStateOf<MusicTrack?>(null) }
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
            InfoTag(it.uppercase())
        }
    }
    replacementInfoTrack?.let { infoTrack ->
        ReplacementInfoDialog(
            track = infoTrack,
            onDismiss = { replacementInfoTrack = null },
        )
    }
}

@Composable
fun InfoTag(text: String, onClick: (() -> Unit)? = null) {
    Surface(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ReplacementInfoDialog(track: MusicTrack, onDismiss: () -> Unit) {
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
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
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

@Composable
fun QueueBottomSheet(controller: FuoPlayerController) {
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
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
                    .clickable(onClick = controller::toggleQueue),
            )
        }
        AnimatedVisibility(
            visible = controller.isQueueOpen,
            modifier = Modifier.align(if (isWideLayout) Alignment.CenterEnd else Alignment.BottomCenter),
            enter = if (isWideLayout) {
                slideInHorizontally(animationSpec = tween(220)) { it } + fadeIn(tween(160))
            } else {
                slideInVertically(animationSpec = tween(220)) { it } + fadeIn(tween(160))
            },
            exit = if (isWideLayout) {
                slideOutHorizontally(animationSpec = tween(180)) { it } + fadeOut(tween(140))
            } else {
                slideOutVertically(animationSpec = tween(180)) { it } + fadeOut(tween(140))
            },
        ) {
            QueueBottomSheetContent(controller, sidePanel = isWideLayout)
        }
    }
}

@Composable
fun QueueBottomSheetContent(controller: FuoPlayerController, sidePanel: Boolean = false) {
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
                .navigationBarsPadding()
                .clickable { }
        },
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shape = if (sidePanel) {
            RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)
        } else {
            RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                size = 44.dp,
                iconSize = 24.dp,
                selected = shuffleEnabled,
                enabled = shuffleAvailable,
            )
        }
        RoundControlButton(
            imageVector = Icons.Filled.SkipPrevious,
            contentDescription = "上一首",
            onClick = onPrevious,
            size = if (compact) 44.dp else 48.dp,
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
            size = if (compact) 44.dp else 48.dp,
            iconSize = if (compact) 24.dp else 26.dp,
        )
        if (!compact && onRepeat != null) {
            val repeatIcon = when (repeatMode) {
                RepeatMode.OFF -> Icons.Filled.Repeat
                RepeatMode.QUEUE -> Icons.Filled.Repeat
                RepeatMode.SINGLE -> Icons.Filled.RepeatOne
            }
            RoundControlButton(
                imageVector = repeatIcon,
                contentDescription = repeatMode.label,
                onClick = onRepeat,
                size = 44.dp,
                iconSize = 24.dp,
                selected = repeatMode != RepeatMode.OFF,
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
    Surface(
        modifier = Modifier
            .size(size)
            .clickable(enabled = enabled, onClick = onClick),
        color = when {
            prominent || selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = when {
            prominent || selected -> MaterialTheme.colorScheme.onPrimary
            enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        },
        tonalElevation = if (prominent) 3.dp else 1.dp,
        shape = RoundedCornerShape(50),
    ) {
        Box(contentAlignment = Alignment.Center) {
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
            modifier = Modifier.size(size),
            color = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            tonalElevation = if (prominent) 3.dp else 1.dp,
            shape = RoundedCornerShape(50),
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
    Slider(
        value = state.positionMs.coerceIn(0, duration).toFloat(),
        onValueChange = { onSeek(it.toLong()) },
        valueRange = 0f..duration.toFloat(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(formatMs(state.positionMs), style = MaterialTheme.typography.labelMedium)
        Text(formatMs(state.durationMs), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun LyricsPanel(state: PlaybackState, fontSize: LyricFontSize, modifier: Modifier) {
    val lines = remember(state.lyrics) { parseLrc(state.lyrics) }
    val listState = rememberLazyListState()
    val currentIndex = currentLyricIndex(lines, state.positionMs)
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
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(16.dp),
        ) {
            val displayLines = lines.takeIf { it.isNotEmpty() } ?: listOf(LyricLine(0, "暂无歌词"))
            itemsIndexed(displayLines) { index, line ->
                val active = index == currentIndex
                Text(
                    text = line.text,
                    style = if (active) activeStyle else inactiveStyle,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = linePadding),
                )
            }
        }
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
        itemsIndexed(queue, key = { _, item -> item.id }) { index, track ->
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
                    .clickable(enabled = !isUnavailable) { controller.playQueueIndex(index) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverBox(track, modifier = Modifier.size(44.dp))
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
                    .clickable { onPartClick(index) },
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
                shape = RoundedCornerShape(6.dp),
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
        is DownloadState.Failed -> "下载失败"
        DownloadState.Queued -> "等待下载"
        else -> null
    }
    return listOfNotNull(
        when (track.sourceType) {
            TrackSourceType.Provider -> track.providerName ?: track.source.ifBlank { "音源" }
            TrackSourceType.LocalMediaStore -> "本地"
            TrackSourceType.Downloaded -> track.providerName ?: "FeelUOwn"
        },
        "智能替换".takeIf { track.isSmartReplacement },
        "不可用".takeIf { track.isUnavailable },
        state,
    ).joinToString(" · ")
}

fun replacementSourceLabel(track: MusicTrack): String {
    val source = replacementProviderLabel(track).ifBlank { "音源" }
    return "替换来源：$source"
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

data class LyricLine(
    val timeMs: Long,
    val text: String,
)

fun parseLrc(raw: String?): List<LyricLine> {
    if (raw.isNullOrBlank()) return emptyList()
    val timedLines = mutableListOf<LyricLine>()
    raw.lines().forEach { line ->
        val matches = lrcTimeRegex.findAll(line).toList()
        val text = line.replace(lrcTimeRegex, "").trim()
        if (text.isBlank()) return@forEach
        if (matches.isEmpty()) {
            timedLines += LyricLine(Long.MAX_VALUE, text)
        } else {
            matches.forEach { match ->
                timedLines += LyricLine(parseLrcTime(match.groupValues[1]), text)
            }
        }
    }
    return timedLines.sortedWith(compareBy<LyricLine> { it.timeMs }.thenBy { it.text })
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

val lrcTimeRegex = Regex("""\[(\d{1,3}:\d{1,2}(?:\.\d{1,3})?)]""")
