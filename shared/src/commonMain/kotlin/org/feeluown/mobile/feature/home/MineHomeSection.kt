package org.feeluown.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val MINE_PLAYLIST_STATS_KEY_SEPARATOR = "::"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MineHomeSection(
    controller: FuoPlayerController,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    hasImagePermission: Boolean,
    onRequestImagePermission: () -> Unit,
    modifier: Modifier,
) {
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    LaunchedEffect(controller.mineSection, controller.playlistFilter) {
        if (controller.mineSection == MineSection.Songs) {
            controller.onMineSectionChange(MineSection.Playlists)
        }
        if (controller.playlistFilter == PlaylistFilter.All) {
            controller.onPlaylistFilterChange(PlaylistFilter.UserPlaylists)
        }
    }
    val refreshMineContent = {
        when (controller.mineSection) {
            MineSection.Playlists,
            MineSection.Songs -> {
                controller.refreshLocalPlaylists()
                controller.refreshMinePlaylistContent()
            }
            MineSection.Artists,
            MineSection.Albums -> controller.refreshMineContent()
            MineSection.LocalMusic -> if (hasAudioPermission) controller.refreshLocalMusic() else Unit
        }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (isWideLayout) 6.dp else 12.dp),
    ) {
        MineSectionChips(
            controller = controller,
            includeSecondary = isWideLayout,
        )
        PullToRefreshBox(
            isRefreshing = controller.isLoading,
            onRefresh = refreshMineContent,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (controller.mineSection) {
                MineSection.Playlists,
                MineSection.Songs -> MinePlaylistsSection(
                    controller = controller,
                    showFilter = !isWideLayout,
                    modifier = Modifier.fillMaxSize(),
                )
                MineSection.Artists -> MineMediaItemsSection(
                    controller = controller,
                    contentType = ProviderContentType.Artists,
                    title = "歌手",
                    modifier = Modifier.fillMaxSize(),
                )
                MineSection.Albums -> MineMediaItemsSection(
                    controller = controller,
                    contentType = ProviderContentType.Albums,
                    title = "专辑",
                    modifier = Modifier.fillMaxSize(),
                )
                MineSection.LocalMusic -> LocalMusicSection(
                    controller = controller,
                    hasAudioPermission = hasAudioPermission,
                    onRequestAudioPermission = onRequestAudioPermission,
                    hasImagePermission = hasImagePermission,
                    onRequestImagePermission = onRequestImagePermission,
                    showModeFilter = !isWideLayout,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
fun MineSectionChips(
    controller: FuoPlayerController,
    includeSecondary: Boolean,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompactFilterChip(
            selected = controller.mineSection == MineSection.Playlists || controller.mineSection == MineSection.Songs,
            onClick = { controller.onMineSectionChange(MineSection.Playlists) },
            label = "歌单",
        )
        CompactFilterChip(
            selected = controller.mineSection == MineSection.Artists,
            onClick = { controller.onMineSectionChange(MineSection.Artists) },
            label = "歌手",
        )
        CompactFilterChip(
            selected = controller.mineSection == MineSection.Albums,
            onClick = { controller.onMineSectionChange(MineSection.Albums) },
            label = "专辑",
        )
        CompactFilterChip(
            selected = controller.mineSection == MineSection.LocalMusic,
            onClick = { controller.onMineSectionChange(MineSection.LocalMusic) },
            label = "本地",
        )
        if (includeSecondary) {
            when (controller.mineSection) {
                MineSection.Playlists,
                MineSection.Songs -> {
                    Spacer(Modifier.width(12.dp))
                    PlaylistFilterChips(controller)
                }
                MineSection.LocalMusic -> {
                    Spacer(Modifier.width(12.dp))
                    LocalMusicViewModeTabs(controller)
                }
                MineSection.Artists,
                MineSection.Albums -> Unit
            }
        }
    }
}

@Composable
fun CompactFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = Modifier.height(32.dp),
        shape = CircleShape,
    )
}

@Composable
fun MinePlaylistsSection(
    controller: FuoPlayerController,
    showFilter: Boolean,
    modifier: Modifier,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var selectedCreateProviderId by remember { mutableStateOf<String?>(null) }
    var showCreateLocalDialog by remember { mutableStateOf(false) }
    val fileActions = LocalLocalPlaylistFileActions.current
    val userSections = controller.minePlaylistSections
    val favoriteSections = controller.mineFavoritePlaylistSections
    val playbackStats = controller.playlistPlaybackStats
    val playlistFilter = if (controller.playlistFilter == PlaylistFilter.All) {
        PlaylistFilter.UserPlaylists
    } else {
        controller.playlistFilter
    }
    val selectedMineProviderIds = remember(
        controller.mineProviderIds,
        controller.enabledProviderIds,
        controller.availableProviders,
    ) {
        val configured = controller.mineProviderIds
        val selected = if (configured.isEmpty()) {
            controller.availableProviders.map { it.providerId }.toSet()
        } else {
            configured
        }
        selected.intersect(controller.enabledProviderIds)
    }
    val songEntryFeatures = remember(controller.providerFeatures, selectedMineProviderIds) {
        controller.providerFeatures.filter { feature ->
            feature.category == ProviderFeatureCategory.Mine &&
                feature.contentType == ProviderContentType.Songs &&
                feature.providerId in selectedMineProviderIds
        }
    }
    val showSongEntries = playlistFilter == PlaylistFilter.UserPlaylists && songEntryFeatures.isNotEmpty()
    val sections = when (playlistFilter) {
        PlaylistFilter.All,
        PlaylistFilter.UserPlaylists -> userSections
        PlaylistFilter.FavoritePlaylists -> favoriteSections
        PlaylistFilter.Local -> emptyList()
    }
    val showLocalPlaylists = playlistFilter == PlaylistFilter.Local
    val visibleSections = remember(sections) { sections.filterNot { it.isLoginRequired } }
    val frequentPlaylists = remember(userSections, favoriteSections, playbackStats) {
        frequentlyPlayedMinePlaylists(
            playlists = (userSections + favoriteSections)
                .filterNot { it.isLoginRequired }
                .flatMap { it.playlists },
            playbackStats = playbackStats,
        )
    }
    val lockedProviders = remember(sections) {
        sections.filter { it.isLoginRequired }
            .map { it.feature }
            .distinctBy { it.providerId }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showFilter) {
            PlaylistFilterChips(controller)
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (
                sections.isEmpty() &&
                !showLocalPlaylists &&
                !showSongEntries &&
                frequentPlaylists.isEmpty()
            ) {
                item {
                    EmptyProviderContentHint(playlistFilter.emptyTitle())
                }
            } else {
                if (frequentPlaylists.isNotEmpty()) {
                    item(key = "playlists:frequent") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
                                    shape = RoundedCornerShape(16.dp),
                                )
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("我的常听", style = MaterialTheme.typography.titleMedium)
                            ProviderPlaylistGrid(
                                playlists = frequentPlaylists,
                                onClick = { controller.openPlaylist(it, controller.categoryForMinePlaylist(it)) },
                                maxRows = 2,
                            )
                        }
                    }
                }
                if (showLocalPlaylists) {
                    localPlaylistSectionItems(
                        controller = controller,
                        playlists = controller.localPlaylists,
                        onCreatePlaylist = { showCreateLocalDialog = true },
                        onImportPlaylist = fileActions.importFile,
                    )
                }
                visibleSections.forEach { contentSection ->
                    playlistSectionItems(
                        controller = controller,
                        contentSection = contentSection,
                        playbackStats = playbackStats,
                        onCreatePlaylist = { providerId ->
                            selectedCreateProviderId = providerId
                            showCreateDialog = true
                        },
                    )
                }
                if (lockedProviders.isNotEmpty()) {
                    item(key = "locked-providers:mine-playlists") {
                        ProviderLockedSummary(
                            providers = lockedProviders,
                            onClick = { controller.openSettings(it.providerId) },
                        )
                    }
                }
                if (showSongEntries) {
                    item(key = "header:mine-song-entries") {
                        Text("我的歌曲", style = MaterialTheme.typography.titleMedium)
                    }
                    item(key = "mine-song-entries") {
                        ProviderFeatureCoverGrid(
                            features = songEntryFeatures,
                            onClick = controller::openFeature,
                        )
                    }
                }
            }
        }
    }
    if (showCreateDialog) {
        val selectedCreateProvider = controller.creatablePlaylistProviders().firstOrNull {
            it.providerId == selectedCreateProviderId
        }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建歌单") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将在 ${selectedCreateProvider?.providerName.orEmpty()} 创建")
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        label = { Text("歌单名称") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = playlistName.isNotBlank() && selectedCreateProvider != null,
                    onClick = {
                        selectedCreateProvider?.let { provider ->
                            controller.createPlaylist(provider.providerId, playlistName)
                            playlistName = ""
                            showCreateDialog = false
                        }
                    },
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("取消") } },
        )
    }
    if (showCreateLocalDialog) {
        AlertDialog(
            onDismissRequest = { showCreateLocalDialog = false },
            title = { Text("新建本地歌单") },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("歌单名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = playlistName.isNotBlank(),
                    onClick = {
                        controller.createLocalPlaylist(playlistName)
                        playlistName = ""
                        showCreateLocalDialog = false
                    },
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateLocalDialog = false }) { Text("取消") }
            },
        )
    }
    controller.localPlaylistImportPreview?.let { preview ->
        LocalPlaylistImportDialog(controller, preview)
    }
}

@Composable
fun PlaylistFilterChips(controller: FuoPlayerController) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompactFilterChip(
            selected = controller.playlistFilter == PlaylistFilter.UserPlaylists ||
                controller.playlistFilter == PlaylistFilter.All,
            onClick = { controller.onPlaylistFilterChange(PlaylistFilter.UserPlaylists) },
            label = "用户",
        )
        CompactFilterChip(
            selected = controller.playlistFilter == PlaylistFilter.FavoritePlaylists,
            onClick = { controller.onPlaylistFilterChange(PlaylistFilter.FavoritePlaylists) },
            label = "收藏",
        )
        CompactFilterChip(
            selected = controller.playlistFilter == PlaylistFilter.Local,
            onClick = { controller.onPlaylistFilterChange(PlaylistFilter.Local) },
            label = "本地",
        )
    }
}

fun PlaylistFilter.emptyTitle(): String {
    return when (this) {
        PlaylistFilter.All -> "歌单"
        PlaylistFilter.UserPlaylists -> "用户歌单"
        PlaylistFilter.FavoritePlaylists -> "收藏歌单"
        PlaylistFilter.Local -> "本地歌单"
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.localPlaylistSectionItems(
    controller: FuoPlayerController,
    playlists: List<LocalPlaylist>,
    onCreatePlaylist: () -> Unit,
    onImportPlaylist: (() -> Unit)?,
) {
    item(key = "header:local-playlists") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "本地歌单",
                style = MaterialTheme.typography.titleMedium,
            )
            Row {
                TextButton(onClick = onCreatePlaylist) { Text("新建") }
                TextButton(
                    onClick = { onImportPlaylist?.invoke() },
                    enabled = onImportPlaylist != null,
                ) { Text("导入") }
            }
        }
    }
    if (playlists.isEmpty()) {
        item(key = "empty:local-playlists") {
            ProviderContentMessage("暂无本地歌单，可新建或导入 .fuo 文件")
        }
    } else {
        item(key = "playlists:local") {
            ProviderPlaylistGrid(
                playlists = playlists.map { localPlaylist ->
                    ProviderPlaylist(
                        id = localPlaylist.id,
                        title = localPlaylist.title,
                        providerId = "local",
                        providerName = "本地 · ${localPlaylist.tracks.size} 首",
                        description = localPlaylist.description,
                        trackCount = localPlaylist.tracks.size,
                    )
                },
                onClick = { card ->
                    playlists.firstOrNull { it.id == card.id }?.let(controller::openLocalPlaylist)
                },
            )
        }
    }
}

@Composable
fun LocalPlaylistImportDialog(
    controller: FuoPlayerController,
    preview: LocalPlaylistImportPreview,
) {
    val existing = controller.existingLocalPlaylistForImport(preview)
    AlertDialog(
        onDismissRequest = controller::cancelLocalPlaylistImport,
        title = { Text("导入本地歌单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("《${preview.title}》 · ${preview.tracks.size} 首")
                if (preview.description.isNotBlank()) {
                    Text(
                        text = preview.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (preview.skippedLineCount > 0) {
                    Text(
                        text = "将跳过 ${preview.skippedLineCount} 行不支持内容",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (existing != null) {
                    Text("已存在同名歌单，请选择覆盖或新建。")
                }
            }
        },
        confirmButton = {
            if (existing != null) {
                TextButton(onClick = {
                    controller.importLocalPlaylist(LocalPlaylistImportMode.Replace, existing.id)
                }) { Text("覆盖") }
            } else {
                TextButton(onClick = {
                    controller.importLocalPlaylist(LocalPlaylistImportMode.CreateNew)
                }) { Text("导入") }
            }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = {
                        controller.importLocalPlaylist(LocalPlaylistImportMode.CreateNew)
                    }) { Text("新建") }
                }
                TextButton(onClick = controller::cancelLocalPlaylistImport) { Text("取消") }
            }
        },
    )
}

fun androidx.compose.foundation.lazy.LazyListScope.playlistSectionItems(
    controller: FuoPlayerController,
    contentSection: ProviderContentSection,
    playbackStats: Map<String, PlaylistPlaybackStat>,
    onCreatePlaylist: (String) -> Unit,
) {
    item(key = "header:${contentSection.feature.id}") {
        ProviderFeatureHeader(
            feature = contentSection.feature,
            action = if (contentSection.feature.category == ProviderFeatureCategory.MinePlaylists) {
                controller.creatablePlaylistProviders()
                    .firstOrNull { it.providerId == contentSection.feature.providerId }
                    ?.let { provider -> { onCreatePlaylist(provider.providerId) } }
            } else {
                null
            },
            actionLabel = "新建歌单",
        )
    }
    when {
        contentSection.errorMessage != null -> item(key = "error:${contentSection.feature.id}") {
            ProviderContentMessage(contentSection.errorMessage)
        }
        contentSection.playlists.isNotEmpty() -> {
            item(key = "playlists:${contentSection.feature.id}") {
                ProviderPlaylistGrid(
                    playlists = sortedMinePlaylistsSnapshot(contentSection.playlists, playbackStats),
                    onClick = { controller.openPlaylist(it, contentSection.feature.category) },
                    onMore = { controller.openFeature(contentSection.feature) },
                )
            }
        }
        else -> item(key = "empty:${contentSection.feature.id}") {
            ProviderContentMessage("暂无内容")
        }
    }
}

@Composable
fun MineMediaItemsSection(
    controller: FuoPlayerController,
    contentType: ProviderContentType,
    title: String,
    modifier: Modifier,
) {
    val sections = controller.mineSections.filter { it.feature.contentType == contentType }
    val visibleSections = remember(sections) {
        sections.filterNot { it.isLoginRequired }
    }
    val lockedProviders = remember(sections) {
        sections.filter { it.isLoginRequired }
            .map { it.feature }
            .distinctBy { it.providerId }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (sections.isEmpty()) {
            item {
                EmptyProviderContentHint(title)
            }
        } else {
            visibleSections.forEach { contentSection ->
                item(key = "header:${contentSection.feature.id}") {
                    ProviderFeatureHeader(feature = contentSection.feature)
                }
                when {
                    contentSection.errorMessage != null -> item(key = "error:${contentSection.feature.id}") {
                        ProviderContentMessage(contentSection.errorMessage)
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
            if (lockedProviders.isNotEmpty()) {
                item(key = "locked-providers:${contentType.name}") {
                    ProviderLockedSummary(
                        providers = lockedProviders,
                        onClick = { controller.openSettings(it.providerId) },
                    )
                }
            }
        }
    }
}

internal fun minePlaylistPlaybackStatsKey(playlist: ProviderPlaylist): String =
    "${playlist.providerId}$MINE_PLAYLIST_STATS_KEY_SEPARATOR${playlist.id}"

internal fun sortedMinePlaylistsSnapshot(
    playlists: List<ProviderPlaylist>,
    playbackStats: Map<String, PlaylistPlaybackStat>,
): List<ProviderPlaylist> = playlists
    .mapIndexed { index, playlist ->
        RankedMinePlaylist(
            playlist = playlist,
            stat = playbackStats[minePlaylistPlaybackStatsKey(playlist)],
            originalIndex = index,
        )
    }
    .sortedWith(
        compareByDescending<RankedMinePlaylist> { it.stat?.lastPlayedAtMillis ?: 0L }
            .thenBy { it.originalIndex },
    )
    .map { it.playlist }

internal fun frequentlyPlayedMinePlaylists(
    playlists: List<ProviderPlaylist>,
    playbackStats: Map<String, PlaylistPlaybackStat>,
): List<ProviderPlaylist> = playlists
    .distinctBy(::minePlaylistPlaybackStatsKey)
    .mapIndexedNotNull { index, playlist ->
        val stat = playbackStats[minePlaylistPlaybackStatsKey(playlist)]
            ?.takeIf { it.playCount > 0 }
            ?: return@mapIndexedNotNull null
        RankedMinePlaylist(
            playlist = playlist,
            stat = stat,
            originalIndex = index,
        )
    }
    .sortedWith(
        compareByDescending<RankedMinePlaylist> { it.stat?.playCount ?: 0L }
            .thenByDescending { it.stat?.lastPlayedAtMillis ?: 0L }
            .thenBy { it.originalIndex },
    )
    .map { it.playlist }

private data class RankedMinePlaylist(
    val playlist: ProviderPlaylist,
    val stat: PlaylistPlaybackStat?,
    val originalIndex: Int,
)
