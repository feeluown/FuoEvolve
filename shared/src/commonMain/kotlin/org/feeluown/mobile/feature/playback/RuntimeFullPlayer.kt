package org.feeluown.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionState
import org.feeluown.mobile.playback.api.PlaybackSessionStatus

/** Controller-free FullPlayer/queue/lyrics entry point for P1-C2. */
@Composable
fun RuntimeFullPlayer() {
    val playbackSession = LocalPlaybackSession.current
    val uiPort = LocalPlaybackUiPort.current
    val sessionState by playbackSession.state.collectAsStateWithLifecycle()
    val state = sessionState.toPlayerRenderState(uiPort)

    PlayerDynamicColorTheme(
        themeMode = uiPort.themeMode,
        dynamicCoverColorEnabled = uiPort.dynamicCoverColorEnabled,
        coverImageUrl = uiPort.currentTrack?.coverUrl,
        isLoading = sessionState.status == PlaybackSessionStatus.Loading,
    ) {
        RuntimeFullPlayerContent(
            playbackSession = playbackSession,
            uiPort = uiPort,
            state = state,
        )
    }
}

private fun PlaybackSessionState.toPlayerRenderState(uiPort: PlaybackUiPort): PlaybackState =
    PlaybackState(
        status = status.toPlayerStatus(),
        currentTrack = uiPort.currentTrack,
        positionMs = positionMs,
        durationMs = durationMs,
        bufferedMs = bufferedMs,
        queue = uiPort.queue,
        queueIndex = queueIndex,
        lyrics = lyrics,
        audioQuality = uiPort.audioQuality,
        audioFormatInfo = uiPort.audioFormatInfo,
        audioDecoderInfo = uiPort.audioDecoderInfo,
        playbackParts = uiPort.playbackParts,
        currentPartIndex = uiPort.currentPartIndex,
        errorMessage = errorMessage,
    )

private fun PlaybackSessionStatus.toPlayerStatus(): PlayerStatus = when (this) {
    PlaybackSessionStatus.Idle -> PlayerStatus.Idle
    PlaybackSessionStatus.Loading -> PlayerStatus.Loading
    PlaybackSessionStatus.Playing -> PlayerStatus.Playing
    PlaybackSessionStatus.Paused -> PlayerStatus.Paused
    PlaybackSessionStatus.Error -> PlayerStatus.Error
    PlaybackSessionStatus.Ended -> PlayerStatus.Ended
}

@Composable
private fun RuntimeFullPlayerContent(
    playbackSession: PlaybackSession,
    uiPort: PlaybackUiPort,
    state: PlaybackState,
) {
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
                    RuntimePlayerHeader(uiPort, currentTrack)
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        val lyricsPaneWidth = (maxWidth * 0.46f)
                            .coerceAtLeast(240.dp)
                            .coerceAtMost(maxWidth * 0.52f)
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .width(lyricsPaneWidth)
                                    .fillMaxHeight(),
                            ) {
                                LyricsPanel(
                                    state = state,
                                    fontSize = uiPort.lyricFontSize,
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
                                    RuntimePlayerCoverPage(
                                        track = currentTrack,
                                        uiPort = uiPort,
                                        isLoading = state.status == PlayerStatus.Loading,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 260.dp),
                                    )
                                    RuntimePlayerTitleBlock(
                                        track = currentTrack,
                                        uiPort = uiPort,
                                        partLabel = currentPlaybackPartLabel(state),
                                    )
                                    Text(
                                        text = currentTrack?.let(::artistAlbumLabel).orEmpty(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                RuntimePlayerTransport(playbackSession, uiPort, state, dense = false)
                            }
                        }
                    }
                }
                RuntimeQueueBottomSheet(uiPort, state)
            }
        }
        return
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compactPortrait = maxHeight < 720.dp || maxHeight < maxWidth * 1.55f
            val portraitSpacing = if (compactPortrait) 8.dp else 14.dp
            val portraitBottomPadding = if (compactPortrait) 28.dp else 82.dp
            val portraitHorizontalPadding = if (compactPortrait) 16.dp else 20.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = portraitHorizontalPadding)
                    .padding(bottom = portraitBottomPadding),
                verticalArrangement = Arrangement.spacedBy(portraitSpacing),
            ) {
                RuntimePlayerHeader(uiPort, currentTrack)
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
                        PlayerVisualTab.Cover -> RuntimePlayerCoverPage(
                            track = currentTrack,
                            uiPort = uiPort,
                            isLoading = state.status == PlayerStatus.Loading,
                        )
                        PlayerVisualTab.Lyrics -> LyricsPanel(
                            state = state,
                            fontSize = uiPort.lyricFontSize,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                RuntimePlayerTitleBlock(
                    track = currentTrack,
                    uiPort = uiPort,
                    partLabel = currentPlaybackPartLabel(state),
                )
                Text(
                    text = currentTrack?.let(::artistAlbumLabel).orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RuntimePlayerTransport(playbackSession, uiPort, state, dense = compactPortrait)
            }
            RuntimeQueueBottomSheet(uiPort, state)
        }
    }
}

@Composable
private fun RuntimePlayerHeader(uiPort: PlaybackUiPort, currentTrack: MusicTrack?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = uiPort::closeFullPlayer) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起播放器")
        }
        Text(
            text = "正在播放",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = uiPort::toggleQueue) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "播放队列")
            }
            currentTrack?.let { RuntimeNowPlayingTrackAction(uiPort, it) }
        }
    }
}

@Composable
private fun RuntimePlayerTransport(
    playbackSession: PlaybackSession,
    uiPort: PlaybackUiPort,
    state: PlaybackState,
    dense: Boolean,
) {
    ProgressBlock(state, uiPort::seekTo)
    PlayerControls(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        onPrevious = playbackSession::previous,
        onToggle = playbackSession::toggle,
        onNext = playbackSession::next,
        dense = dense,
        shuffleEnabled = uiPort.isShuffleEnabled,
        shuffleAvailable = !uiPort.isFmQueueActive,
        onShuffle = uiPort::toggleShuffle,
        sleepTimerAction = { RuntimeSleepTimerAction(uiPort) },
    )
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun RuntimePlayerCoverPage(
    track: MusicTrack?,
    uiPort: PlaybackUiPort,
    isLoading: Boolean,
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
                heroEnabled = uiPort.isFullPlayerOpen,
                transitionDirection = uiPort.trackChangeDirection,
                isLoading = isLoading,
                cornerRadius = 22.dp,
                modifier = Modifier.size(coverSize),
            )
        }
    }
}

@Composable
private fun RuntimePlayerTitleBlock(
    track: MusicTrack?,
    uiPort: PlaybackUiPort,
    partLabel: String?,
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
        RuntimePlayerInfoTags(track, uiPort)
    }
}

@Composable
private fun RuntimePlayerInfoTags(track: MusicTrack?, uiPort: PlaybackUiPort) {
    var replacementInfoTrack by remember(track?.id) { mutableStateOf<MusicTrack?>(null) }
    var showAudioFormatInfo by remember(track?.id) { mutableStateOf(false) }
    val canShowAudioInfo = uiPort.audioFormatInfo?.hasDisplayableValue() == true || uiPort.audioDecoderInfo != null

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (track != null) {
            if (track.isSmartReplacement) {
                InfoTag(
                    text = replacementSourceLabel(track),
                    onClick = {
                        replacementInfoTrack = track
                        uiPort.loadReplacementCandidates(track)
                    },
                )
            } else {
                InfoTag(sourceLabel(track, null))
            }
        }
        uiPort.audioQuality?.takeIf { it.isNotBlank() }?.let {
            InfoTag(
                text = it.uppercase(),
                onClick = if (canShowAudioInfo) ({ showAudioFormatInfo = true }) else null,
            )
        }
    }

    replacementInfoTrack?.let { infoTrack ->
        ReplacementInfoDialog(
            track = infoTrack,
            candidateState = uiPort.replacementCandidateState
                .takeIf { it.trackId == (infoTrack.originalId ?: infoTrack.id) }
                ?: ReplacementCandidateState(),
            onDismiss = { replacementInfoTrack = null },
            onRetry = { uiPort.loadReplacementCandidates(infoTrack) },
            onSelectCandidate = { candidate ->
                uiPort.selectReplacementCandidate(infoTrack, candidate)
                replacementInfoTrack = null
            },
            onOpenDetail = if (infoTrack.replacementId?.isNotBlank() == true) {
                { uiPort.openReplacementTrackDetail(infoTrack) }
            } else {
                null
            },
        )
    }
    if (showAudioFormatInfo) {
        AudioFormatInfoDialog(
            info = uiPort.audioFormatInfo,
            decoderInfo = uiPort.audioDecoderInfo,
            onDismiss = { showAudioFormatInfo = false },
        )
    }
}

@Composable
private fun RuntimeNowPlayingTrackAction(uiPort: PlaybackUiPort, track: MusicTrack) {
    val onShare = LocalShareHandler.current
    val sharePayload = track.toSharePayload()
    TrackAction(
        track = track,
        downloadState = uiPort.downloadStates[track.id],
        onAddToUpNext = { uiPort.addToUpNext(track) },
        onDownload = { uiPort.download(track) },
        onDeleteDownload = { uiPort.deleteDownload(track) },
        onOpenArtist = { uiPort.openTrackArtist(track) },
        onOpenAlbum = { uiPort.openTrackAlbum(track) },
        onOpenDetail = if (track.sourceType == TrackSourceType.Provider) {
            { uiPort.openOriginalTrackDetail(track) }
        } else {
            null
        },
        onEditLocalMetadata = if (track.sourceType == TrackSourceType.LocalMediaStore) {
            { uiPort.openLocalMetadataEditor(track) }
        } else {
            null
        },
        onAddToPlaylist = if (uiPort.canAddTrackToPlaylist(track)) {
            { uiPort.openPlaylistTargetPicker(track) }
        } else {
            null
        },
        onRemoveFromProviderPlaylist = if (uiPort.canRemoveTrackFromSelectedPlaylist(track)) {
            { uiPort.removeTrackFromSelectedPlaylist(track) }
        } else {
            null
        },
        onSetDisliked = if (uiPort.canSetSongDisliked(track)) {
            { uiPort.setSongDisliked(track) }
        } else {
            null
        },
        dislikedActionLabel = "不喜欢",
        onShare = sharePayload?.let { payload -> { onShare(payload) } },
        roundButton = false,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuntimeQueueBottomSheet(uiPort: PlaybackUiPort, state: PlaybackState) {
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    if (!isWideLayout) {
        if (uiPort.isQueueOpen) {
            ModalBottomSheet(
                onDismissRequest = uiPort::toggleQueue,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                RuntimeQueueContent(uiPort, state, sidePanel = false, embedded = true)
            }
        }
        return
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = if (isWideLayout) Alignment.CenterEnd else Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = uiPort.isQueueOpen,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(140)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .clickable(role = Role.Button, onClick = uiPort::toggleQueue),
            )
        }
        AnimatedVisibility(
            visible = uiPort.isQueueOpen,
            modifier = Modifier.align(if (isWideLayout) Alignment.CenterEnd else Alignment.BottomCenter),
            enter = if (isWideLayout) {
                slideInHorizontally(animationSpec = tween(FuoMotion.overlayEnterMillis)) { it } +
                    fadeIn(tween(FuoMotion.overlayFadeMillis))
            } else {
                error("Wide layout required for side-panel queue")
            },
            exit = if (isWideLayout) {
                slideOutHorizontally(animationSpec = tween(FuoMotion.overlayExitMillis)) { it } +
                    fadeOut(tween(FuoMotion.overlayFadeMillis))
            } else {
                error("Wide layout required for side-panel queue")
            },
        ) {
            RuntimeQueueContent(uiPort, state, sidePanel = true)
        }
    }
}

@Composable
private fun RuntimeQueueContent(
    uiPort: PlaybackUiPort,
    state: PlaybackState,
    sidePanel: Boolean = false,
    embedded: Boolean = false,
) {
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    val queueSize = state.queue.size

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
                isFmQueue = uiPort.isFmQueueActive,
                repeatMode = uiPort.repeatMode,
                onRepeat = uiPort::toggleRepeat,
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
            RuntimeQueueList(
                uiPort = uiPort,
                state = state,
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
                    uiPort.clearQueue()
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
private fun RuntimeQueueList(
    uiPort: PlaybackUiPort,
    state: PlaybackState,
    modifier: Modifier,
) {
    val queue = state.queue
    val playbackParts = uiPort.playbackParts
    val currentPartIndex = uiPort.currentPartIndex
    val currentCount = if (state.queueIndex == 0 && queue.isNotEmpty()) 1 else 0
    val upNextCount = uiPort.displayUpNextCount
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
            val isCurrent = index == state.queueIndex
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
                    ) { uiPort.playQueueIndex(index) }
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
                IconButton(onClick = { uiPort.removeFromQueue(track) }) {
                    Icon(Icons.Filled.RemoveCircleOutline, contentDescription = "从队列移除")
                }
            }
            if (isCurrent && playbackParts.isNotEmpty()) {
                PlaybackPartList(
                    parts = playbackParts,
                    currentPartIndex = currentPartIndex,
                    onPartClick = uiPort::playPlaybackPart,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun RuntimeSleepTimerAction(uiPort: PlaybackUiPort) {
    var showSheet by remember { mutableStateOf(false) }
    val timerState = uiPort.sleepTimerState
    val isActive = timerState.mode != SleepTimerMode.Off
    val contentDescription = when (timerState.mode) {
        SleepTimerMode.Off -> "睡眠定时"
        SleepTimerMode.Duration -> "睡眠定时，剩余 ${runtimeFormatSleepTimerRemaining(timerState.remainingMs ?: 0L)}"
        SleepTimerMode.EndOfTrack -> "当前曲目结束后暂停"
    }
    Box {
        RoundControlButton(
            imageVector = Icons.Filled.Timer,
            contentDescription = contentDescription,
            onClick = { showSheet = true },
            selected = isActive,
        )
        if (showSheet) {
            RuntimeSleepTimerBottomSheet(
                uiPort = uiPort,
                onDismiss = { showSheet = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuntimeSleepTimerBottomSheet(
    uiPort: PlaybackUiPort,
    onDismiss: () -> Unit,
) {
    val timerState = uiPort.sleepTimerState
    var showCustomDurationDialog by remember { mutableStateOf(false) }
    var customMinutesText by remember { mutableStateOf("") }
    val customMinutes = customMinutesText.toIntOrNull()
    val customMinutesValid = customMinutes?.let {
        it in SLEEP_TIMER_MIN_MINUTES..SLEEP_TIMER_MAX_MINUTES
    } == true
    val presetMinutes = SLEEP_TIMER_PRESET_MINUTES.filter { it <= 90 }
    val firstPresetRow = presetMinutes.take(3)
    val secondPresetRow = presetMinutes.drop(3)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "睡眠定时",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "选择自动暂停播放的时间",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (timerState.mode != SleepTimerMode.Off) {
                RuntimeSleepTimerActiveStatus(
                    timerState = timerState,
                    onExtend = if (timerState.mode == SleepTimerMode.Duration) {
                        {
                            uiPort.setSleepTimerDurationMinutes(
                                runtimeSleepTimerExtendedMinutes(timerState.remainingMs ?: 0L, 5),
                            )
                        }
                    } else {
                        null
                    },
                    onClear = {
                        uiPort.clearSleepTimer()
                        onDismiss()
                    },
                )
            }
            RuntimeSleepTimerSectionLabel("按时长")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                firstPresetRow.forEach { minutes ->
                    RuntimeSleepTimerPresetOption(
                        minutes = minutes,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            uiPort.setSleepTimerDurationMinutes(minutes)
                            onDismiss()
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                secondPresetRow.forEach { minutes ->
                    RuntimeSleepTimerPresetOption(
                        minutes = minutes,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            uiPort.setSleepTimerDurationMinutes(minutes)
                            onDismiss()
                        },
                    )
                }
                RuntimeSleepTimerCustomOption(
                    modifier = Modifier.weight(1f),
                    onClick = { showCustomDurationDialog = true },
                )
            }

            RuntimeSleepTimerSectionLabel("播放结束")
            RuntimeSleepTimerEndOfTrackOption(
                selected = timerState.mode == SleepTimerMode.EndOfTrack,
                onClick = {
                    uiPort.setSleepTimerToEndOfTrack()
                    onDismiss()
                },
            )
        }
    }

    if (showCustomDurationDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDurationDialog = false },
            title = { Text("自定义睡眠定时") },
            text = {
                OutlinedTextField(
                    value = customMinutesText,
                    onValueChange = { value ->
                        customMinutesText = value.filter(Char::isDigit)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = customMinutesText.isNotBlank() && !customMinutesValid,
                    label = { Text("时长（分钟）") },
                    placeholder = { Text("例如 45") },
                    supportingText = if (customMinutesText.isNotBlank() && !customMinutesValid) {
                        {
                            Text("请输入 $SLEEP_TIMER_MIN_MINUTES–$SLEEP_TIMER_MAX_MINUTES 分钟")
                        }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = customMinutesValid,
                    onClick = {
                        customMinutes?.let(uiPort::setSleepTimerDurationMinutes)
                        showCustomDurationDialog = false
                        onDismiss()
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDurationDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun RuntimeSleepTimerActiveStatus(
    timerState: SleepTimerState,
    onExtend: (() -> Unit)?,
    onClear: () -> Unit,
) {
    val isDuration = timerState.mode == SleepTimerMode.Duration
    val canExtend = isDuration &&
        (timerState.remainingMs ?: 0L) < SLEEP_TIMER_MAX_MINUTES * 60_000L
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isDuration) Icons.Filled.Timer else Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (isDuration) {
                        "还有 ${runtimeFormatSleepTimerRemaining(timerState.remainingMs ?: 0L)}"
                    } else {
                        "当前曲目结束后暂停"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (isDuration) {
                        "时间到后自动暂停播放"
                    } else {
                        "播放完当前曲目的全部内容后暂停"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onExtend != null) {
                TextButton(
                    enabled = canExtend,
                    onClick = onExtend,
                ) {
                    Text("+5 分钟")
                }
            }
            TextButton(onClick = onClear) {
                Text("关闭定时")
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun RuntimeSleepTimerSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RuntimeSleepTimerPresetOption(
    minutes: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        modifier = modifier,
        label = {
            Text(
                text = "$minutes 分钟",
                maxLines = 1,
            )
        },
    )
}

@Composable
private fun RuntimeSleepTimerCustomOption(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        modifier = modifier,
        label = { Text("自定义", maxLines = 1) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@Composable
private fun RuntimeSleepTimerEndOfTrackOption(
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
        ),
        leadingContent = {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        },
        headlineContent = {
            Text(
                text = "当前曲目结束后暂停",
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = {
            Text(
                text = "包含当前曲目的全部多段内容",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = if (selected) {
            {
                Text(
                    text = "已选",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        } else {
            null
        },
    )
}

private fun runtimeSleepTimerExtendedMinutes(remainingMs: Long, extraMinutes: Int): Int {
    val roundedRemainingMinutes = ((remainingMs.coerceAtLeast(0L) + 30_000L) / 60_000L)
        .coerceAtLeast(1L)
    return (roundedRemainingMinutes + extraMinutes)
        .coerceAtMost(SLEEP_TIMER_MAX_MINUTES.toLong())
        .toInt()
}

private fun runtimeFormatSleepTimerRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return when {
        minutes >= 60L -> "${minutes / 60L}小时${minutes % 60L}分"
        minutes > 0L -> "${minutes}分${seconds}秒"
        else -> "${seconds}秒"
    }
}
