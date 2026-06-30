package org.feeluown.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    controller: FuoPlayerController,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
) {
    MaterialTheme {
        if (controller.isFullPlayerOpen) {
            FullPlayer(controller)
            return@MaterialTheme
        }
        if (controller.isSettingsOpen) {
            SettingsScreen(controller)
            return@MaterialTheme
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("FeelUOwn") },
                    navigationIcon = {
                        IconButton(onClick = controller::openSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    },
                    actions = {
                        IconButton(onClick = controller::openSearch) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索")
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            bottomBar = {
                if (controller.playbackState.currentTrack != null) {
                    MiniPlayer(controller)
                }
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!hasAudioPermission) {
                    PermissionPanel(onRequestAudioPermission)
                }
                if (controller.isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = controller.message,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HomeSectionTabs(controller)
                when (controller.homeSection) {
                    HomeSection.Recommend -> EmptyHomeSection(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        title = "推荐",
                    )
                    HomeSection.Music -> EmptyHomeSection(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        title = "音乐",
                    )
                    HomeSection.Local -> LocalMusicSection(
                        controller = controller,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
            }
        }

        if (controller.isSearchOpen) {
            SearchDialog(controller)
        }
    }
}

@Composable
private fun HomeSectionTabs(controller: FuoPlayerController) {
    val sections = listOf(
        HomeSection.Recommend to "推荐",
        HomeSection.Music to "音乐",
        HomeSection.Local to "本地音乐",
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        sections.forEachIndexed { index, (section, label) ->
            SegmentedButton(
                selected = controller.homeSection == section,
                onClick = { controller.onHomeSectionChange(section) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = sections.size),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun EmptyHomeSection(modifier: Modifier, title: String) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LocalMusicSection(controller: FuoPlayerController, modifier: Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "本地音乐",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = controller::refreshLocalMusic) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新本地音乐")
            }
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            itemsIndexed(controller.localTracks, key = { _, item -> item.id }) { index, track ->
                TrackRow(
                    track = track,
                    downloadState = controller.downloadStates[track.id],
                    onClick = { controller.playFromLocal(index) },
                    onDownload = { controller.download(track) },
                    onDeleteDownload = { controller.deleteDownload(track) },
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(controller: FuoPlayerController) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = controller::closeSettings) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Provider 登录",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            ProviderLoginCard(controller)
        }
    }
}

@Composable
private fun ProviderLoginCard(controller: FuoPlayerController) {
    val authState = controller.providerAuthState
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = authState.providerName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (authState.isLoggedIn) {
                    "已登录：${authState.userName.orEmpty()}"
                } else {
                    "未登录"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "网易云移动端先支持 cookies 登录。可从已登录网页版复制 Cookie header，或粘贴 JSON 对象。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                value = controller.neteaseCookies,
                onValueChange = controller::onNeteaseCookiesChange,
                placeholder = { Text("""{"MUSIC_U":"...","__csrf":"..."}""") },
                minLines = 4,
                maxLines = 8,
            )
            Button(
                enabled = !controller.isLoading,
                onClick = controller::loginNeteaseWithCookies,
            ) {
                Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (controller.isLoading) "登录中" else "登录网易云")
            }
        }
    }
}

@Composable
private fun PermissionPanel(onRequestAudioPermission: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = "需要音频权限来扫描系统音乐库",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRequestAudioPermission) {
                Text("授权")
            }
        }
    }
}

@Composable
private fun SearchDialog(controller: FuoPlayerController) {
    AlertDialog(
        onDismissRequest = controller::closeSearch,
        title = { Text("搜索音乐") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = controller.query,
                    onValueChange = controller::onQueryChange,
                    singleLine = true,
                    placeholder = { Text("歌曲、歌手或专辑") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { controller.search() }),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchScopeChip(controller, SearchScope.All, "全部")
                    SearchScopeChip(controller, SearchScope.Local, "本地")
                    SearchScopeChip(controller, SearchScope.Provider, "Provider")
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !controller.isLoading,
                    onClick = controller::search,
                ) {
                    if (!controller.isLoading) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (controller.isLoading) "搜索中" else "搜索")
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    itemsIndexed(controller.searchResults, key = { _, item -> item.id }) { index, track ->
                        TrackRow(
                            track = track,
                            downloadState = controller.downloadStates[track.id],
                            onClick = { controller.playFromSearch(index) },
                            onDownload = { controller.download(track) },
                            onDeleteDownload = { controller.deleteDownload(track) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            IconButton(onClick = controller::closeSearch) {
                Icon(Icons.Filled.Close, contentDescription = "关闭搜索")
            }
        },
    )
}

@Composable
private fun SearchScopeChip(controller: FuoPlayerController, scope: SearchScope, label: String) {
    FilterChip(
        selected = controller.searchScope == scope,
        onClick = { controller.onSearchScopeChange(scope) },
        label = { Text(label) },
    )
}

@Composable
private fun TrackRow(
    track: MusicTrack,
    downloadState: DownloadState?,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverBox(track)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title.ifBlank { "未知歌曲" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = listOf(track.artists, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sourceLabel(track, downloadState),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TrackAction(track, downloadState, onDownload, onDeleteDownload)
    }
}

@Composable
private fun CoverBox(
    track: MusicTrack,
    modifier: Modifier = Modifier.size(48.dp),
) {
    PlatformCoverArt(
        title = track.title,
        imageUrl = track.coverUrl,
        modifier = Modifier
            .then(modifier)
            .clip(RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun TrackAction(
    track: MusicTrack,
    downloadState: DownloadState?,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
) {
    when {
        track.sourceType == TrackSourceType.Provider && downloadState is DownloadState.Downloaded -> {
            IconButton(onClick = onDeleteDownload) {
                Icon(Icons.Filled.Delete, contentDescription = "删除下载")
            }
        }
        track.sourceType == TrackSourceType.Provider && downloadState is DownloadState.Downloading -> {
            Text("下载中", style = MaterialTheme.typography.labelMedium)
        }
        track.sourceType == TrackSourceType.Provider -> {
            IconButton(onClick = onDownload) {
                Icon(Icons.Filled.Download, contentDescription = "下载")
            }
        }
        track.sourceType == TrackSourceType.Downloaded -> {
            IconButton(onClick = onDeleteDownload) {
                Icon(Icons.Filled.Delete, contentDescription = "删除下载")
            }
        }
    }
}

@Composable
private fun MiniPlayer(controller: FuoPlayerController) {
    val state = controller.playbackState
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = controller::openFullPlayer),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.currentTrack?.let { CoverBox(it) }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.currentTrack?.title ?: "未播放",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.currentTrack?.artists ?: "选择一首音乐开始播放",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = controller::previous) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首")
            }
            PlayPauseButton(
                isPlaying = state.status == PlayerStatus.Playing,
                onClick = controller::toggle,
            )
            IconButton(onClick = controller::next) {
                Icon(Icons.Filled.SkipNext, contentDescription = "下一首")
            }
        }
    }
}

@Composable
private fun FullPlayer(controller: FuoPlayerController) {
    val state = controller.playbackState
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
            state.currentTrack?.let {
                CoverBox(
                    track = it,
                    modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                )
            } ?: CoverBox(
                track = MusicTrack(
                    id = "empty",
                    title = "FeelUOwn",
                    artists = "",
                    album = "",
                    source = "",
                    sourceType = TrackSourceType.LocalMediaStore,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            )
            Text(
                text = state.currentTrack?.title ?: "未播放",
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.currentTrack?.artists ?: "",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ProgressBlock(state, controller::seekTo)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = controller::previous) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首")
                }
                PlayPauseButton(
                    isPlaying = state.status == PlayerStatus.Playing,
                    onClick = controller::toggle,
                )
                IconButton(onClick = controller::next) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一首")
                }
            }
            if (controller.isQueueOpen) {
                QueueList(
                    controller = controller,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                LyricsPanel(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "暂停" else "播放",
        )
    }
}

@Composable
private fun ProgressBlock(state: PlaybackState, onSeek: (Long) -> Unit) {
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
private fun LyricsPanel(state: PlaybackState, modifier: Modifier) {
    val lines = remember(state.lyrics) { parseLrc(state.lyrics) }
    val listState = rememberLazyListState()
    val currentIndex = currentLyricIndex(lines, state.positionMs)

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
                    style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun QueueList(controller: FuoPlayerController, modifier: Modifier) {
    LazyColumn(
        modifier = modifier,
    ) {
        itemsIndexed(controller.playbackState.queue, key = { _, item -> item.id }) { index, track ->
            val isCurrent = index == controller.playbackState.queueIndex
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { controller.playQueueIndex(index) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverBox(track, modifier = Modifier.size(44.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${index + 1}. ${track.title.ifBlank { "未知歌曲" }}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
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
            HorizontalDivider()
        }
    }
}

private fun sourceLabel(track: MusicTrack, downloadState: DownloadState?): String {
    val state = when (downloadState) {
        is DownloadState.Downloaded -> "已下载"
        is DownloadState.Downloading -> "下载中"
        is DownloadState.Failed -> "下载失败"
        DownloadState.Queued -> "等待下载"
        else -> null
    }
    return listOfNotNull(
        when (track.sourceType) {
            TrackSourceType.Provider -> "Provider"
            TrackSourceType.LocalMediaStore -> "本地"
            TrackSourceType.Downloaded -> "FeelUOwn"
        },
        track.source.takeIf { it.isNotBlank() },
        state,
    ).joinToString(" · ")
}

private fun formatMs(value: Long): String {
    val totalSeconds = (value / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private data class LyricLine(
    val timeMs: Long,
    val text: String,
)

private fun parseLrc(raw: String?): List<LyricLine> {
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

private fun parseLrcTime(value: String): Long {
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

private fun currentLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    val timedLines = lines.takeWhile { it.timeMs != Long.MAX_VALUE }
    if (timedLines.isEmpty()) return -1
    val index = timedLines.indexOfLast { it.timeMs <= positionMs }
    return index.coerceAtLeast(0)
}

private val lrcTimeRegex = Regex("""\[(\d{1,3}:\d{1,2}(?:\.\d{1,3})?)]""")
