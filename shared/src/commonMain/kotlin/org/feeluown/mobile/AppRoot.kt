package org.feeluown.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    controller: FuoPlayerController,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
) {
    MaterialTheme {
        val destination = appDestination(controller)
        val currentFeature = controller.selectedFeature
        val currentPlaylist = controller.selectedPlaylist
        val currentMediaItem = controller.selectedMediaItem
        var lastFeature by remember { mutableStateOf<ProviderFeature?>(null) }
        var lastPlaylist by remember { mutableStateOf<ProviderPlaylist?>(null) }
        var lastMediaItem by remember { mutableStateOf<ProviderMediaItem?>(null) }

        LaunchedEffect(currentFeature) {
            if (currentFeature != null) {
                lastFeature = currentFeature
            }
        }
        LaunchedEffect(currentPlaylist) {
            if (currentPlaylist != null) {
                lastPlaylist = currentPlaylist
            }
        }
        LaunchedEffect(currentMediaItem) {
            if (currentMediaItem != null) {
                lastMediaItem = currentMediaItem
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = destination,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                    (slideInHorizontally(animationSpec = tween(220)) { it / 5 * direction } + fadeIn(tween(180)))
                        .togetherWith(
                            slideOutHorizontally(animationSpec = tween(180)) { -it / 6 * direction } +
                                fadeOut(tween(160)),
                        )
                        .using(SizeTransform(clip = false))
                },
            ) { target ->
                when (target) {
                    AppDestination.Home -> HomeScreen(
                        controller = controller,
                        hasAudioPermission = hasAudioPermission,
                        onRequestAudioPermission = onRequestAudioPermission,
                    )
                    AppDestination.DebugLogs -> DebugLogScreen(controller)
                    AppDestination.Settings -> SettingsScreen(controller, onOpenProviderWebLogin, onLogoutProvider)
                    AppDestination.Search -> SearchScreen(controller)
                    AppDestination.Feature -> ProviderFeatureScreen(controller, currentFeature ?: lastFeature)
                    AppDestination.Playlist -> ProviderPlaylistScreen(controller, currentPlaylist ?: lastPlaylist)
                    AppDestination.MediaItem -> ProviderMediaItemScreen(controller, currentMediaItem ?: lastMediaItem)
                }
            }
            AnimatedVisibility(
                visible = controller.isFullPlayerOpen,
                modifier = Modifier.fillMaxSize(),
                enter = slideInVertically(animationSpec = tween(260)) { it / 2 } + fadeIn(tween(180)),
                exit = slideOutVertically(animationSpec = tween(220)) { it / 2 } + fadeOut(tween(160)),
            ) {
                FullPlayer(controller)
            }
        }
    }
}

private enum class AppDestination {
    Home,
    Feature,
    Playlist,
    Search,
    Settings,
    DebugLogs,
    MediaItem,
}

private fun appDestination(controller: FuoPlayerController): AppDestination {
    return when {
        controller.isDebugLogOpen -> AppDestination.DebugLogs
        controller.isSettingsOpen -> AppDestination.Settings
        controller.isSearchOpen -> AppDestination.Search
        controller.selectedFeature != null -> AppDestination.Feature
        controller.selectedMediaItem != null -> AppDestination.MediaItem
        controller.selectedPlaylist != null -> AppDestination.Playlist
        else -> AppDestination.Home
    }
}

@Composable
private fun LoadingIndicator(visible: Boolean) {
    AnimatedVisibility(visible = visible) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    controller: FuoPlayerController,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
) {
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
            LoadingIndicator(controller.isLoading)
            HomeSectionPager(controller, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HomeSectionPager(controller: FuoPlayerController, modifier: Modifier) {
    val sections = listOf(
        HomeSection.Recommend to "推荐",
        HomeSection.Music to "探索",
        HomeSection.Mine to "我的",
    )
    val selectedIndex = sections.indexOfFirst { it.first == controller.homeSection }.coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = selectedIndex,
        pageCount = { sections.size },
    )
    val scope = rememberCoroutineScope()

    LaunchedEffect(controller.homeSection) {
        val page = sections.indexOfFirst { it.first == controller.homeSection }
        if (page >= 0 && page != pagerState.currentPage) {
            pagerState.animateScrollToPage(page)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val section = sections.getOrNull(page)?.first
                if (section != null && section != controller.homeSection) {
                    controller.onHomeSectionChange(section)
                }
            }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeSectionTabs(
            sections = sections,
            selectedIndex = pagerState.currentPage.coerceIn(0, sections.lastIndex),
            indicatorOffsetFraction = pagerState.currentPageOffsetFraction,
            onClick = { index, section ->
                if (section != controller.homeSection || index != pagerState.currentPage) {
                    scope.launch { pagerState.animateScrollToPage(index) }
                }
            },
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            pageSpacing = 16.dp,
        ) { page ->
            when (sections[page].first) {
                HomeSection.Recommend -> ProviderContentHomeSection(
                    controller = controller,
                    section = HomeSection.Recommend,
                    modifier = Modifier.fillMaxSize(),
                )
                HomeSection.Music -> ProviderContentHomeSection(
                    controller = controller,
                    section = HomeSection.Music,
                    modifier = Modifier.fillMaxSize(),
                )
                HomeSection.Mine -> MineHomeSection(
                    controller = controller,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun HomeSectionTabs(
    sections: List<Pair<HomeSection, String>>,
    selectedIndex: Int,
    indicatorOffsetFraction: Float,
    onClick: (Int, HomeSection) -> Unit,
) {
    TabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        indicator = { tabPositions ->
            val current = tabPositions.getOrNull(selectedIndex) ?: return@TabRow
            val targetIndex = when {
                indicatorOffsetFraction > 0f -> (selectedIndex + 1).coerceAtMost(tabPositions.lastIndex)
                indicatorOffsetFraction < 0f -> (selectedIndex - 1).coerceAtLeast(0)
                else -> selectedIndex
            }
            val target = tabPositions[targetIndex]
            val progress = indicatorOffsetFraction.absoluteValue.coerceIn(0f, 1f)
            val indicatorLeft = lerp(current.left, target.left, progress)
            val indicatorWidth = lerp(current.width, target.width, progress)
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentSize(Alignment.BottomStart)
                    .offset { IntOffset(indicatorLeft.roundToPx(), 0) }
                    .width(indicatorWidth),
            )
        },
    ) {
        sections.forEachIndexed { index, (section, label) ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onClick(index, section) },
                text = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (index == selectedIndex) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
            )
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
private fun ProviderContentHomeSection(
    controller: FuoPlayerController,
    section: HomeSection,
    modifier: Modifier,
) {
    val title = if (section == HomeSection.Recommend) "推荐" else "探索"
    val sections = controller.contentSectionsFor(section)
    val visibleSections = remember(sections) { sections.filterNot { it.isLoginRequired } }
    val lockedProviderNames = remember(sections) {
        sections.filter { it.isLoginRequired }
            .map { it.feature.providerName }
            .distinct()
    }
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
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = { controller.refreshHomeContent(section) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新$title")
            }
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (sections.isEmpty()) {
                item {
                    EmptyProviderContentHint(title)
                }
            } else {
                val dailySongSections = visibleSections.filter { it.feature.isDailySongs() }
                val privateFmSections = visibleSections.filter { it.feature.isPrivateFm() }
                val otherSections = visibleSections.filterNot {
                    it.feature.isDailySongs() || it.feature.isPrivateFm()
                }
                if (dailySongSections.isNotEmpty()) {
                    item(key = "header:daily-songs") {
                        ProviderFeatureHeader(
                            feature = dailySongSections.first().feature,
                            providerLabel = dailySongSections
                                .map { it.feature.providerName }
                                .distinct()
                                .joinToString(" / "),
                        )
                    }
                    item(key = "daily-songs-grid") {
                        ProviderFeatureCoverGrid(
                            features = dailySongSections.map { it.feature },
                            onClick = controller::openFeature,
                        )
                    }
                }
                if (privateFmSections.isNotEmpty()) {
                    item(key = "header:private-fm") {
                        ProviderFeatureHeader(
                            feature = privateFmSections.first().feature,
                            providerLabel = privateFmSections
                                .map { it.feature.providerName }
                                .distinct()
                                .joinToString(" / "),
                        )
                    }
                    item(key = "private-fm-grid") {
                        PrivateFmGrid(
                            sections = privateFmSections,
                            enabled = !controller.isLoading,
                            onClick = { section -> controller.playAllFromFeature(section.feature.id) },
                        )
                    }
                }
                otherSections.forEach { contentSection ->
                    item(key = "header:${contentSection.feature.id}") {
                        ProviderFeatureHeader(
                            feature = contentSection.feature,
                            onPlayAll = if (contentSection.tracks.isNotEmpty()) {
                                { controller.playAllFromFeature(contentSection.feature.id) }
                            } else {
                                null
                            },
                        )
                    }
                    when {
                        contentSection.errorMessage != null -> item(key = "error:${contentSection.feature.id}") {
                            ProviderContentMessage(contentSection.errorMessage)
                        }
                        contentSection.tracks.isNotEmpty() -> {
                            itemsIndexed(
                                contentSection.tracks,
                                key = { _, item -> "${contentSection.feature.id}:${item.id}" },
                            ) { index, track ->
                                TrackRow(
                                    track = track,
                                    downloadState = controller.downloadStates[track.id],
                                    onClick = { controller.playFromFeature(contentSection.feature.id, index) },
                                    onDownload = { controller.download(track) },
                                    onDeleteDownload = { controller.deleteDownload(track) },
                                )
                                HorizontalDivider()
                            }
                        }
                        contentSection.playlists.isNotEmpty() -> {
                            item(key = "playlists:${contentSection.feature.id}") {
                                ProviderPlaylistGrid(
                                    playlists = contentSection.playlists,
                                    onClick = controller::openPlaylist,
                                )
                            }
                        }
                        else -> item(key = "empty:${contentSection.feature.id}") {
                            ProviderContentMessage("暂无内容")
                        }
                    }
                }
                if (lockedProviderNames.isNotEmpty()) {
                    item(key = "locked-providers:${section.name}") {
                        ProviderLockedSummary(
                            providerNames = lockedProviderNames,
                            onClick = controller::openSettings,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderFeatureCoverGrid(
    features: List<ProviderFeature>,
    onClick: (ProviderFeature) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        features.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { feature ->
                    ProviderFeatureCoverCard(
                        feature = feature,
                        onClick = { onClick(feature) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProviderFeatureCoverCard(
    feature: ProviderFeature,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        CoverBox(
            track = feature.toDisplayTrack(),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = feature.title.ifBlank { "推荐" },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = feature.providerName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PrivateFmGrid(
    sections: List<ProviderContentSection>,
    enabled: Boolean,
    onClick: (ProviderContentSection) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        sections.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { section ->
                    PrivateFmButton(
                        section = section,
                        enabled = enabled,
                        onClick = { onClick(section) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PrivateFmButton(
    section: ProviderContentSection,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "播放${section.feature.providerName}私人 FM",
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = section.feature.providerName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "私人 FM",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProviderPlaylistGrid(
    playlists: List<ProviderPlaylist>,
    onClick: (ProviderPlaylist) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        playlists.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { playlist ->
                    ProviderPlaylistCard(
                        playlist = playlist,
                        onClick = { onClick(playlist) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProviderPlaylistCard(
    playlist: ProviderPlaylist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        CoverBox(
            track = playlist.toDisplayTrack(),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = playlist.title.ifBlank { "未命名歌单" },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOf(
                playlist.providerName,
                playlist.playCount?.let { formatPlayCount(it) },
            ).filterNotNull().joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProviderMediaItemGrid(
    items: List<ProviderMediaItem>,
    onClick: (ProviderMediaItem) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { item ->
                    ProviderMediaItemCard(
                        item = item,
                        onClick = { onClick(item) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProviderMediaItemCard(
    item: ProviderMediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        CoverBox(
            track = item.toDisplayTrack(),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title.ifBlank { if (item.type == ProviderMediaItemType.Artist) "未知歌手" else "未知专辑" },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOf(
                item.providerName,
                if (item.type == ProviderMediaItemType.Artist) "歌手" else "专辑",
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProviderFeatureHeader(
    feature: ProviderFeature,
    providerLabel: String = feature.providerName,
    onPlayAll: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = feature.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = providerLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onPlayAll != null) {
                PlayAllButton(onClick = onPlayAll)
            }
        }
    }
}

@Composable
private fun PlayAllButton(onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text("播放全部")
    }
}

private fun ProviderFeature.isPrivateFm(): Boolean {
    return id.endsWith("_radio")
}

private fun ProviderFeature.isDailySongs(): Boolean {
    return id.endsWith("_daily_songs")
}

private fun ProviderFeature.toDisplayTrack(): MusicTrack {
    return MusicTrack(
        id = id,
        title = title,
        artists = providerName,
        album = "",
        source = providerId,
        sourceType = TrackSourceType.Provider,
        providerName = providerName,
    )
}

private fun ProviderPlaylist.toDisplayTrack(): MusicTrack {
    return MusicTrack(
        id = id,
        title = title,
        artists = providerName,
        album = "",
        source = providerId,
        sourceType = TrackSourceType.Provider,
        coverUrl = coverUrl,
        providerName = providerName,
    )
}

private fun ProviderMediaItem.toDisplayTrack(): MusicTrack {
    return MusicTrack(
        id = id,
        title = title,
        artists = providerName,
        album = if (type == ProviderMediaItemType.Artist) "歌手" else "专辑",
        source = providerId,
        sourceType = TrackSourceType.Provider,
        coverUrl = coverUrl,
        providerName = providerName,
    )
}

@Composable
private fun ProviderLockedSummary(providerNames: List<String>, onClick: () -> Unit) {
    val label = providerNames.joinToString("、")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = "登录后显示 $label 的个性化内容",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onClick) {
            Text("登录")
        }
    }
}

@Composable
private fun ProviderContentMessage(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            modifier = Modifier.padding(16.dp),
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyProviderContentHint(title: String) {
    ProviderContentMessage("$title 暂无内容")
}

@Composable
private fun MineHomeSection(controller: FuoPlayerController, modifier: Modifier) {
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
                text = "我的",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(
                onClick = {
                    when (controller.mineSection) {
                        MineSection.Playlists -> controller.refreshMinePlaylistContent()
                        MineSection.Favorites -> controller.refreshMineContent()
                        MineSection.LocalMusic -> controller.refreshLocalMusic()
                    }
                },
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新我的")
            }
        }
        MineSectionChips(controller)
        when (controller.mineSection) {
            MineSection.Playlists -> MinePlaylistsSection(
                controller = controller,
                modifier = Modifier.weight(1f),
            )
            MineSection.Favorites -> MineFavoritesSection(
                controller = controller,
                modifier = Modifier.weight(1f),
            )
            MineSection.LocalMusic -> LocalMusicSection(
                controller = controller,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MineSectionChips(controller: FuoPlayerController) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = controller.mineSection == MineSection.Playlists,
            onClick = { controller.onMineSectionChange(MineSection.Playlists) },
            label = { Text("歌单") },
        )
        FilterChip(
            selected = controller.mineSection == MineSection.Favorites,
            onClick = { controller.onMineSectionChange(MineSection.Favorites) },
            label = { Text("收藏") },
        )
        FilterChip(
            selected = controller.mineSection == MineSection.LocalMusic,
            onClick = { controller.onMineSectionChange(MineSection.LocalMusic) },
            label = { Text("本地") },
        )
    }
}

@Composable
private fun MinePlaylistsSection(controller: FuoPlayerController, modifier: Modifier) {
    val userSections = controller.minePlaylistSections
    val favoriteSections = controller.mineFavoritePlaylistSections
    val sections = userSections + favoriteSections
    val visibleSections = remember(sections) { sections.filterNot { it.isLoginRequired } }
    val lockedProviderNames = remember(sections) {
        sections.filter { it.isLoginRequired }
            .map { it.feature.providerName }
            .distinct()
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (sections.isEmpty()) {
            item {
                EmptyProviderContentHint("歌单")
            }
        } else {
            visibleSections.filter { it.feature.category == ProviderFeatureCategory.MinePlaylists }
                .forEach { contentSection ->
                    playlistSectionItems(controller, contentSection)
                }
            visibleSections.filter { it.feature.category == ProviderFeatureCategory.MineFavoritePlaylists }
                .forEach { contentSection ->
                    playlistSectionItems(controller, contentSection)
                }
            if (lockedProviderNames.isNotEmpty()) {
                item(key = "locked-providers:mine-playlists") {
                    ProviderLockedSummary(
                        providerNames = lockedProviderNames,
                        onClick = controller::openSettings,
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.playlistSectionItems(
    controller: FuoPlayerController,
    contentSection: ProviderContentSection,
) {
    item(key = "header:${contentSection.feature.id}") {
        ProviderFeatureHeader(feature = contentSection.feature)
    }
    when {
        contentSection.errorMessage != null -> item(key = "error:${contentSection.feature.id}") {
            ProviderContentMessage(contentSection.errorMessage)
        }
        contentSection.playlists.isNotEmpty() -> {
            item(key = "playlists:${contentSection.feature.id}") {
                ProviderPlaylistGrid(
                    playlists = contentSection.playlists,
                    onClick = controller::openPlaylist,
                )
            }
        }
        else -> item(key = "empty:${contentSection.feature.id}") {
            ProviderContentMessage("暂无内容")
        }
    }
}

@Composable
private fun MineFavoritesSection(controller: FuoPlayerController, modifier: Modifier) {
    val sections = controller.mineSections
    val visibleSections = remember(sections) {
        sections.filterNot { it.isLoginRequired || it.feature.contentType == ProviderContentType.Playlists }
    }
    val lockedProviderNames = remember(sections) {
        sections.filter { it.isLoginRequired }
            .map { it.feature.providerName }
            .distinct()
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (sections.isEmpty()) {
            item {
                EmptyProviderContentHint("我的收藏")
            }
        } else {
            visibleSections.forEach { contentSection ->
                item(key = "header:${contentSection.feature.id}") {
                    ProviderFeatureHeader(
                        feature = contentSection.feature,
                        onPlayAll = if (contentSection.tracks.isNotEmpty()) {
                            { controller.playAllFromFeature(contentSection.feature.id) }
                        } else {
                            null
                        },
                    )
                }
                when {
                    contentSection.errorMessage != null -> item(key = "error:${contentSection.feature.id}") {
                        ProviderContentMessage(contentSection.errorMessage)
                    }
                    contentSection.tracks.isNotEmpty() -> {
                        itemsIndexed(
                            contentSection.tracks,
                            key = { _, item -> "${contentSection.feature.id}:${item.id}" },
                        ) { index, track ->
                            TrackRow(
                                track = track,
                                downloadState = controller.downloadStates[track.id],
                                onClick = { controller.playFromFeature(contentSection.feature.id, index) },
                                onDownload = { controller.download(track) },
                                onDeleteDownload = { controller.deleteDownload(track) },
                            )
                            HorizontalDivider()
                        }
                    }
                    contentSection.playlists.isNotEmpty() -> {
                        item(key = "playlists:${contentSection.feature.id}") {
                            ProviderPlaylistGrid(
                                playlists = contentSection.playlists,
                                onClick = controller::openPlaylist,
                            )
                        }
                    }
                    contentSection.mediaItems.isNotEmpty() -> {
                        item(key = "media-items:${contentSection.feature.id}") {
                            ProviderMediaItemGrid(
                                items = contentSection.mediaItems,
                                onClick = controller::openMediaItem,
                            )
                        }
                    }
                    else -> item(key = "empty:${contentSection.feature.id}") {
                        ProviderContentMessage("暂无内容")
                    }
                }
            }
            if (lockedProviderNames.isNotEmpty()) {
                item(key = "locked-providers:mine") {
                    ProviderLockedSummary(
                        providerNames = lockedProviderNames,
                        onClick = controller::openSettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalMusicSection(controller: FuoPlayerController, modifier: Modifier) {
    val displayTracks = remember(controller.localTracks, controller.localMusicViewMode) {
        when (controller.localMusicViewMode) {
            LocalMusicViewMode.All -> controller.localTracks.sortedWith(
                compareBy<MusicTrack> { localTitleSectionOrder(localTitleSection(it.title)) }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.artists.lowercase() },
            )
            LocalMusicViewMode.Artist -> controller.localTracks.sortedWith(
                compareBy<MusicTrack> { normalizedGroupName(it.artists, "未知歌手").lowercase() }
                    .thenBy { it.album.lowercase() }
                    .thenBy { it.title.lowercase() },
            )
            LocalMusicViewMode.Album -> controller.localTracks.sortedWith(
                compareBy<MusicTrack> { normalizedGroupName(it.album, "未知专辑").lowercase() }
                    .thenBy { it.artists.lowercase() }
                    .thenBy { it.title.lowercase() },
            )
        }
    }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (displayTracks.isNotEmpty()) {
                    PlayAllButton(onClick = { controller.playAllLocalTracks(displayTracks) })
                }
                IconButton(onClick = controller::refreshLocalMusic) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新本地音乐")
                }
            }
        }
        LocalMusicViewModeTabs(controller)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (displayTracks.isEmpty()) {
                item {
                    EmptyLocalMusicHint()
                }
            } else {
                val groups = when (controller.localMusicViewMode) {
                    LocalMusicViewMode.All -> displayTracks.groupBy { localTitleSection(it.title) }
                    LocalMusicViewMode.Artist -> displayTracks.groupBy { normalizedGroupName(it.artists, "未知歌手") }
                    LocalMusicViewMode.Album -> displayTracks.groupBy { normalizedGroupName(it.album, "未知专辑") }
                }
                groups.forEach { (section, tracks) ->
                    item(key = "section:$section") {
                        LocalMusicSectionHeader(title = section, count = tracks.size)
                    }
                    itemsIndexed(tracks, key = { _, item -> item.id }) { _, track ->
                        TrackRow(
                            track = track,
                            downloadState = controller.downloadStates[track.id],
                            onClick = { controller.playLocalTrack(track, displayTracks) },
                            onDownload = { controller.download(track) },
                            onDeleteDownload = { controller.deleteDownload(track) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalMusicViewModeTabs(controller: FuoPlayerController) {
    val modes = listOf(
        LocalMusicViewMode.All to "全部",
        LocalMusicViewMode.Artist to "歌手",
        LocalMusicViewMode.Album to "专辑",
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = controller.localMusicViewMode == mode,
                onClick = { controller.onLocalMusicViewModeChange(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun LocalMusicSectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "$count 首",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyLocalMusicHint() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            modifier = Modifier.padding(16.dp),
            text = "未发现本地音乐",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    controller: FuoPlayerController,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
) {
    var loginProviderId by remember { mutableStateOf<String?>(null) }
    val loginProvider = controller.orderedProviders().firstOrNull { it.providerId == loginProviderId }
    LaunchedEffect(loginProviderId, controller.providers) {
        if (loginProviderId != null && loginProvider == null) {
            loginProviderId = null
        }
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(loginProvider?.providerName ?: "设置") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (loginProvider != null) {
                                loginProviderId = null
                            } else {
                                controller.closeSettings()
                            }
                        },
                    ) {
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
            if (loginProvider != null) {
                ProviderLoginPanel(
                    controller = controller,
                    provider = loginProvider,
                    onOpenProviderWebLogin = onOpenProviderWebLogin,
                    onLogoutProvider = onLogoutProvider,
                )
            } else {
                ProviderSwitchPanel(
                    controller = controller,
                    onOpenProviderLogin = { provider -> loginProviderId = provider.providerId },
                )
                AudioQualitySettingsPanel(controller)
                PlaybackPolicySettingsPanel(controller)
                LocalMusicScanSettingsPanel(controller)
                CacheSettingsPanel(controller)
                if (controller.isDebugLogViewerAvailable) {
                    DebugSettingsPanel(controller)
                }
            }
        }
    }
}

@Composable
private fun ProviderSwitchPanel(
    controller: FuoPlayerController,
    onOpenProviderLogin: (ProviderInfo) -> Unit,
) {
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
                text = "音源",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (controller.availableProviders.isEmpty()) {
                ProviderContentMessage("音源正在初始化")
            } else {
                val orderedProviders = controller.orderedAvailableProviders()
                orderedProviders.forEachIndexed { index, provider ->
                    val isEnabled = controller.isProviderEnabled(provider.providerId)
                    val authState = controller.authStateFor(provider)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = provider.providerName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = providerStatusText(isEnabled, authState),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                enabled = !controller.isLoading && index > 0,
                                onClick = { controller.moveProvider(provider.providerId, -1) },
                            ) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移${provider.providerName}")
                            }
                            IconButton(
                                enabled = !controller.isLoading && index < orderedProviders.lastIndex,
                                onClick = { controller.moveProvider(provider.providerId, 1) },
                            ) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移${provider.providerName}")
                            }
                            TextButton(
                                enabled = !controller.isLoading && isEnabled,
                                onClick = { onOpenProviderLogin(provider) },
                            ) {
                                Text(if (authState.isLoggedIn) "管理" else "登录")
                            }
                        }
                        Checkbox(
                            checked = isEnabled,
                            enabled = !controller.isLoading &&
                                (isEnabled && controller.enabledProviderIds.size > 1 || !isEnabled),
                            onCheckedChange = { controller.onProviderEnabledChange(provider.providerId, it) },
                        )
                    }
                }
            }
        }
    }
}

private fun providerStatusText(isEnabled: Boolean, authState: ProviderAuthState): String {
    val enabledText = if (isEnabled) "已启用" else "未启用"
    val loginText = if (isEnabled && authState.isLoggedIn) "已登录" else "未登录"
    return "$enabledText · $loginText"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderLoginPanel(
    controller: FuoPlayerController,
    provider: ProviderInfo,
    onOpenProviderWebLogin: (ProviderInfo) -> Unit,
    onLogoutProvider: (ProviderInfo) -> Unit,
) {
    val authState = controller.authStateFor(provider)
    val supportedLoginModes = listOf(
        ProviderLoginMode.WebView,
        ProviderLoginMode.Cookie,
        ProviderLoginMode.Headers,
    ).filter { it in provider.supportedLoginModes }
    val activeLoginMode = supportedLoginModes
        .firstOrNull { it == controller.providerLoginMode }
        ?: supportedLoginModes.firstOrNull()
        ?: ProviderLoginMode.Cookie
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
                text = provider.providerName,
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
            if (authState.isLoggedIn) {
                Button(
                    enabled = !controller.isLoading,
                    onClick = { onLogoutProvider(provider) },
                ) {
                    Text(if (controller.isLoading) "退出中" else "退出登录")
                }
                return@Column
            }
            if (supportedLoginModes.size > 1) {
                SingleChoiceSegmentedButtonRow {
                    supportedLoginModes.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = activeLoginMode == mode,
                            onClick = { controller.onProviderLoginModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = supportedLoginModes.size),
                        ) {
                            Text(mode.label())
                        }
                    }
                }
            }
            when (activeLoginMode) {
                ProviderLoginMode.WebView -> {
                    Button(
                        enabled = !controller.isLoading && provider.loginConfig != null,
                        onClick = { onOpenProviderWebLogin(provider) },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (controller.isLoading) "登录中" else "WebView 登录")
                    }
                }
                ProviderLoginMode.Cookie -> {
                    TextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        value = controller.cookieInputFor(provider.providerId),
                        onValueChange = { controller.onProviderCookiesChange(provider.providerId, it) },
                        placeholder = { Text("""{"MUSIC_U":"...","__csrf":"..."}""") },
                        minLines = 4,
                        maxLines = 8,
                    )
                    Button(
                        enabled = !controller.isLoading,
                        onClick = {
                            controller.loginProviderWithCookies(
                                provider.providerId,
                                controller.cookieInputFor(provider.providerId),
                            )
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (controller.isLoading) "登录中" else "登录")
                    }
                }
                ProviderLoginMode.Headers -> {
                    val headerInput = controller.providerHeaderInputFor(provider.providerId)
                    TextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = headerInput.authorization,
                        onValueChange = { controller.onProviderHeaderAuthorizationChange(provider.providerId, it) },
                        placeholder = { Text("Authorization") },
                        singleLine = true,
                    )
                    TextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        value = headerInput.cookie,
                        onValueChange = { controller.onProviderHeaderCookieChange(provider.providerId, it) },
                        placeholder = { Text("Cookie") },
                        minLines = 4,
                        maxLines = 8,
                    )
                    Button(
                        enabled = !controller.isLoading,
                        onClick = { controller.loginProviderWithHeaders(provider.providerId) },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (controller.isLoading) "登录中" else "登录")
                    }
                }
            }
        }
    }
}

private fun ProviderLoginMode.label(): String = when (this) {
    ProviderLoginMode.WebView -> "WebView"
    ProviderLoginMode.Cookie -> "复制 Cookie"
    ProviderLoginMode.Headers -> "Headers"
}

@Composable
private fun PlaybackPolicySettingsPanel(controller: FuoPlayerController) {
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
                text = "播放策略",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "资源不可用时",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                UnavailablePlaybackPolicy.entries.forEachIndexed { index, policy ->
                    SegmentedButton(
                        selected = controller.unavailablePlaybackPolicy == policy,
                        onClick = { controller.onUnavailablePlaybackPolicyChange(policy) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = UnavailablePlaybackPolicy.entries.size,
                        ),
                    ) {
                        Text(policy.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioQualitySettingsPanel(controller: FuoPlayerController) {
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
                text = "音质",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            AudioQualityRow(
                title = "WiFi",
                selected = controller.wifiAudioQualityPolicy,
                onSelect = controller::onWifiAudioQualityPolicyChange,
            )
            AudioQualityRow(
                title = "蜂窝网络",
                selected = controller.cellularAudioQualityPolicy,
                onSelect = controller::onCellularAudioQualityPolicyChange,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioQualityRow(
    title: String,
    selected: AudioQualityPolicy,
    onSelect: (AudioQualityPolicy) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AudioQualityPolicy.entries.forEachIndexed { index, policy ->
                SegmentedButton(
                    selected = selected == policy,
                    onClick = { onSelect(policy) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = AudioQualityPolicy.entries.size),
                ) {
                    Text(policy.label)
                }
            }
        }
    }
}

@Composable
private fun LocalMusicScanSettingsPanel(controller: FuoPlayerController) {
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
                text = "本地音乐扫描",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "目录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (controller.localMusicDirectories.isEmpty()) {
                Text(
                    text = "授权并扫描本地音乐后显示媒体库目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                controller.localMusicDirectories.forEach { directory ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                controller.onLocalMusicDirectoryEnabledChange(
                                    directory.id,
                                    directory.id in controller.excludedLocalMusicDirectoryIds,
                                )
                            },
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = directory.id !in controller.excludedLocalMusicDirectoryIds,
                            onCheckedChange = {
                                controller.onLocalMusicDirectoryEnabledChange(directory.id, it)
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = directory.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${directory.trackCount} 首",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            LocalMusicDurationFilterRow(
                selected = controller.localMusicMinDurationSeconds,
                onSelect = controller::onLocalMusicMinDurationChange,
            )
        }
    }
}

@Composable
private fun LocalMusicDurationFilterRow(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val options = listOf(0, 10, 30, 60, 90, 120)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (selected > 0) "过滤短音频：小于 ${selected} 秒" else "过滤短音频：不过滤",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { seconds ->
                FilterChip(
                    selected = selected == seconds,
                    onClick = { onSelect(seconds) },
                    label = { Text(if (seconds == 0) "不过滤" else "${seconds} 秒") },
                )
            }
        }
    }
}

@Composable
private fun CacheSettingsPanel(controller: FuoPlayerController) {
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
                text = "缓存",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "音乐 ${formatBytes(controller.cacheUsage.audioBytes)} · 图片 ${formatBytes(controller.cacheUsage.imageBytes)} · 总计 ${formatBytes(controller.cacheUsage.totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CacheLimitRow(
                title = "音乐缓存",
                selected = controller.audioCacheLimitMb,
                options = listOf(128, 256, 512, 1024, 2048),
                onSelect = controller::onAudioCacheLimitChange,
            )
            CacheLimitRow(
                title = "图片缓存",
                selected = controller.imageCacheLimitMb,
                options = listOf(32, 64, 128, 256, 512),
                onSelect = controller::onImageCacheLimitChange,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = controller::refreshResourceCacheUsage) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("刷新")
                }
                Button(
                    enabled = !controller.isLoading,
                    onClick = controller::clearResourceCache,
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("清空缓存")
                }
            }
        }
    }
}

@Composable
private fun DebugSettingsPanel(controller: FuoPlayerController) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = controller::openDebugLogs),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.BugReport, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "应用日志",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "当前应用 info 及以上日志",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugLogScreen(controller: FuoPlayerController) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("应用日志") },
                navigationIcon = {
                    IconButton(onClick = controller::closeDebugLogs) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        enabled = !controller.isLoading,
                        onClick = controller::refreshDebugLogs,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LoadingIndicator(controller.isLoading)
            controller.debugLogError?.let { ProviderContentMessage(it) }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (controller.debugLogLines.isEmpty() && !controller.isLoading && controller.debugLogError == null) {
                    item {
                        ProviderContentMessage("暂无日志")
                    }
                } else {
                    itemsIndexed(controller.debugLogLines) { _, line ->
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun CacheLimitRow(
    title: String,
    selected: Int,
    options: List<Int>,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "$title：${formatCacheLimit(selected)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { value ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(formatCacheLimit(value)) },
                )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderFeatureScreen(controller: FuoPlayerController, feature: ProviderFeature?) {
    feature ?: return
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = feature.title.ifBlank { "推荐" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = controller::closeFeature) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
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
            LoadingIndicator(controller.isLoading)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = listOf(feature.providerName, "${controller.selectedFeatureTracks.size} 首")
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (controller.selectedFeatureTracks.isNotEmpty()) {
                    PlayAllButton(onClick = controller::playAllFromSelectedFeature)
                }
            }
            if (controller.selectedFeatureError != null) {
                ProviderContentMessage(controller.selectedFeatureError.orEmpty())
            } else if (controller.isLoading && controller.selectedFeatureTracks.isEmpty()) {
                Text(
                    text = controller.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (controller.selectedFeatureTracks.isEmpty() && !controller.isLoading && controller.selectedFeatureError == null) {
                    item {
                        ProviderContentMessage("暂无歌曲")
                    }
                } else {
                    itemsIndexed(controller.selectedFeatureTracks, key = { _, item -> item.id }) { index, track ->
                        TrackRow(
                            track = track,
                            downloadState = controller.downloadStates[track.id],
                            onClick = { controller.playFromSelectedFeature(index) },
                            onDownload = { controller.download(track) },
                            onDeleteDownload = { controller.deleteDownload(track) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPlaylistScreen(controller: FuoPlayerController, playlist: ProviderPlaylist?) {
    playlist ?: return
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = playlist.title.ifBlank { "歌单" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = controller::closePlaylist) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
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
            LoadingIndicator(controller.isLoading)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = listOf(playlist.providerName, "${controller.selectedPlaylistTracks.size} 首")
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (controller.selectedPlaylistTracks.isNotEmpty()) {
                    PlayAllButton(onClick = controller::playAllFromSelectedPlaylist)
                }
            }
            if (playlist.description.isNotBlank()) {
                Text(
                    text = playlist.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (controller.selectedPlaylistError != null) {
                ProviderContentMessage(controller.selectedPlaylistError.orEmpty())
            } else if (controller.isLoading && controller.selectedPlaylistTracks.isEmpty()) {
                Text(
                    text = controller.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (controller.selectedPlaylistTracks.isEmpty() && !controller.isLoading && controller.selectedPlaylistError == null) {
                    item {
                        ProviderContentMessage("歌单暂无歌曲")
                    }
                } else {
                    itemsIndexed(controller.selectedPlaylistTracks, key = { _, item -> item.id }) { index, track ->
                        TrackRow(
                            track = track,
                            downloadState = controller.downloadStates[track.id],
                            onClick = { controller.playFromSelectedPlaylist(index) },
                            onDownload = { controller.download(track) },
                            onDeleteDownload = { controller.deleteDownload(track) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderMediaItemScreen(controller: FuoPlayerController, item: ProviderMediaItem?) {
    item ?: return
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = item.title.ifBlank {
                            if (item.type == ProviderMediaItemType.Artist) "歌手" else "专辑"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = controller::closeMediaItem) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
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
            LoadingIndicator(controller.isLoading)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = listOf(
                        item.providerName,
                        if (item.type == ProviderMediaItemType.Artist) "歌手" else "专辑",
                        "${controller.selectedMediaItemTracks.size} 首",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (controller.selectedMediaItemTracks.isNotEmpty()) {
                    PlayAllButton(onClick = controller::playAllFromSelectedMediaItem)
                }
            }
            if (item.description.isNotBlank()) {
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (controller.selectedMediaItemError != null) {
                ProviderContentMessage(controller.selectedMediaItemError.orEmpty())
            } else if (controller.isLoading && controller.selectedMediaItemTracks.isEmpty()) {
                Text(
                    text = controller.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (controller.selectedMediaItemTracks.isEmpty() && !controller.isLoading && controller.selectedMediaItemError == null) {
                    item {
                        ProviderContentMessage("暂无歌曲")
                    }
                } else {
                    itemsIndexed(controller.selectedMediaItemTracks, key = { _, track -> track.id }) { index, track ->
                        TrackRow(
                            track = track,
                            downloadState = controller.downloadStates[track.id],
                            onClick = { controller.playFromSelectedMediaItem(index) },
                            onDownload = { controller.download(track) },
                            onDeleteDownload = { controller.deleteDownload(track) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(controller: FuoPlayerController) {
    Scaffold(
        topBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
                tonalElevation = 3.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = controller::closeSearch) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                        TextField(
                            modifier = Modifier.weight(1f),
                            value = controller.query,
                            onValueChange = controller::onQueryChange,
                            singleLine = true,
                            placeholder = { Text("歌曲、歌手或专辑") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { controller.search() }),
                        )
                        IconButton(
                            enabled = !controller.isLoading,
                            onClick = controller::search,
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索")
                        }
                    }
                    Row(
                        modifier = Modifier
                            .padding(start = 56.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SearchScopeChip(controller, SearchScope.All, "全部")
                        SearchScopeChip(controller, SearchScope.Local, "本地")
                        controller.providers.forEach { provider ->
                            SearchProviderChip(controller, provider)
                        }
                    }
                }
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
            LoadingIndicator(controller.isLoading)
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (controller.searchResults.isEmpty()) {
                    item {
                        EmptySearchHint(controller.query)
                    }
                } else {
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
        }
    }
}

@Composable
private fun EmptySearchHint(query: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            modifier = Modifier.padding(16.dp),
            text = if (query.isBlank()) "输入关键词搜索音乐" else "没有搜索结果",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchScopeChip(controller: FuoPlayerController, scope: SearchScope, label: String) {
    FilterChip(
        selected = controller.searchScope == scope && scope != SearchScope.Provider,
        onClick = { controller.onSearchScopeChange(scope) },
        label = { Text(label) },
    )
}

@Composable
private fun SearchProviderChip(controller: FuoPlayerController, provider: ProviderInfo) {
    FilterChip(
        selected = controller.searchScope == SearchScope.Provider &&
            controller.selectedSearchProviderId == provider.providerId,
        onClick = { controller.onSearchProviderChange(provider.providerId) },
        label = { Text(provider.providerName) },
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
            .animateContentSize(animationSpec = tween(220))
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
            PlayerControls(
                state = state,
                onPrevious = controller::previous,
                onToggle = controller::toggle,
                onNext = controller::next,
                compact = true,
            )
        }
    }
}

@Composable
private fun FullPlayer(controller: FuoPlayerController) {
    val state = controller.playbackState
    val currentTrack = state.currentTrack
    var visualTab by remember(currentTrack?.id) { mutableStateOf(PlayerVisualTab.Cover) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
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
                TabRow(selectedTabIndex = visualTab.ordinal) {
                    PlayerVisualTab.entries.forEach { tab ->
                        Tab(
                            selected = visualTab == tab,
                            onClick = { visualTab = tab },
                            text = { Text(tab.title) },
                        )
                    }
                }
                AnimatedContent(
                    targetState = visualTab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    transitionSpec = {
                        (fadeIn(tween(160)) + slideInHorizontally(tween(180)) { it / 8 })
                            .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(160)) { -it / 8 })
                            .using(SizeTransform(clip = false))
                    },
                ) { tab ->
                    when (tab) {
                        PlayerVisualTab.Cover -> CoverBox(
                            track = currentTrack ?: emptyDisplayTrack(),
                            modifier = Modifier.fillMaxSize(),
                        )
                        PlayerVisualTab.Lyrics -> LyricsPanel(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                PlayerTitleRow(currentTrack, state.audioQuality)
                Text(
                    text = currentTrack?.artists ?: "",
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
                )
            }
            QueueBottomSheet(controller)
        }
    }
}

private enum class PlayerVisualTab(val title: String) {
    Cover("封面"),
    Lyrics("歌词"),
}

@Composable
private fun PlayerTitleRow(track: MusicTrack?, audioQuality: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = track?.title ?: "未播放",
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        PlayerInfoTags(track, audioQuality)
    }
}

@Composable
private fun PlayerInfoTags(track: MusicTrack?, audioQuality: String?) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (track != null) {
            InfoTag(sourceLabel(track, null))
            if (track.isSmartReplacement) {
                track.originalProviderName?.takeIf { it.isNotBlank() }?.let {
                    InfoTag("原：$it")
                }
            }
        }
        audioQuality?.takeIf { it.isNotBlank() }?.let {
            InfoTag(it.uppercase())
        }
    }
}

@Composable
private fun InfoTag(text: String) {
    Surface(
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
private fun QueueBottomSheet(controller: FuoPlayerController) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
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
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(animationSpec = tween(220)) { it } + fadeIn(tween(160)),
            exit = slideOutVertically(animationSpec = tween(180)) { it } + fadeOut(tween(140)),
        ) {
            QueueBottomSheetContent(controller)
        }
    }
}

@Composable
private fun QueueBottomSheetContent(controller: FuoPlayerController) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .navigationBarsPadding()
            .clickable { },
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
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
                Text(
                    text = "${controller.playbackState.queue.size} 首",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            QueueList(
                controller = controller,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
            )
        }
    }
}

private fun emptyDisplayTrack() = MusicTrack(
    id = "empty",
    title = "FeelUOwn",
    artists = "",
    album = "",
    source = "",
    sourceType = TrackSourceType.LocalMediaStore,
)

@Composable
private fun PlayerControls(
    state: PlaybackState,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier.animateContentSize(animationSpec = tween(220)),
        horizontalArrangement = if (compact) Arrangement.spacedBy(8.dp) else Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundControlButton(
            imageVector = Icons.Filled.SkipPrevious,
            contentDescription = "上一首",
            onClick = onPrevious,
            size = if (compact) 44.dp else 48.dp,
            iconSize = if (compact) 24.dp else 26.dp,
        )
        PlayPauseButton(
            isPlaying = state.status == PlayerStatus.Playing,
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
    }
}

@Composable
private fun RoundControlButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    iconSize: androidx.compose.ui.unit.Dp = 26.dp,
    prominent: Boolean = false,
) {
    Surface(
        modifier = Modifier
            .size(size)
            .clickable(onClick = onClick),
        color = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 52.dp,
    iconSize: androidx.compose.ui.unit.Dp = 28.dp,
    prominent: Boolean = false,
) {
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
            TrackSourceType.Provider -> track.providerName ?: track.source.ifBlank { "音源" }
            TrackSourceType.LocalMediaStore -> "本地"
            TrackSourceType.Downloaded -> track.providerName ?: "FeelUOwn"
        },
        "智能替换".takeIf { track.isSmartReplacement },
        state,
    ).joinToString(" · ")
}

private fun formatPlayCount(value: Long): String {
    return when {
        value >= 100_000_000 -> "${value / 100_000_000} 亿次播放"
        value >= 10_000 -> "${value / 10_000} 万次播放"
        else -> "$value 次播放"
    }
}

private fun formatBytes(value: Long): String {
    return when {
        value >= 1024L * 1024L * 1024L -> "${value / (1024L * 1024L * 1024L)} GB"
        value >= 1024L * 1024L -> "${value / (1024L * 1024L)} MB"
        value >= 1024L -> "${value / 1024L} KB"
        else -> "$value B"
    }
}

private fun formatCacheLimit(value: Int): String {
    return if (value >= 1024 && value % 1024 == 0) {
        "${value / 1024}GB"
    } else {
        "${value}MB"
    }
}

private fun localTitleSection(title: String): String {
    val first = title.trim().firstOrNull()?.uppercaseChar() ?: return "#"
    return if (first in 'A'..'Z') first.toString() else "#"
}

private fun localTitleSectionOrder(section: String): Int {
    val first = section.firstOrNull() ?: return 26
    return if (first in 'A'..'Z') first - 'A' else 26
}

private fun normalizedGroupName(value: String, fallback: String): String {
    return value.trim().ifBlank { fallback }
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
