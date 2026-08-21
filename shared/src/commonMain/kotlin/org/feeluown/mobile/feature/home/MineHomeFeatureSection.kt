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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MineHomeSection(
    home: HomeFeatureController,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    hasImagePermission: Boolean,
    onRequestImagePermission: () -> Unit,
    modifier: Modifier,
) {
    val graph = LocalHomeFeatureUiGraph.current
    val state = home.uiState.collectAsStateWithLifecycle().value
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (isWideLayout) 6.dp else 12.dp),
    ) {
        MineFeatureSectionChips(home = home, includeSecondary = isWideLayout)
        PullToRefreshBox(
            isRefreshing = state.isLoading ||
                (state.mineSection == MineSection.LocalMusic && graph.localMusic.uiState.value.isLoading),
            onRefresh = home::refreshMine,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when (state.mineSection) {
                MineSection.Playlists,
                MineSection.Songs -> MineFeaturePlaylistsSection(
                    home = home,
                    showFilter = !isWideLayout,
                    modifier = Modifier.fillMaxSize(),
                )
                MineSection.Artists -> MineFeatureMediaItemsSection(
                    home = home,
                    contentType = ProviderContentType.Artists,
                    title = "歌手",
                    modifier = Modifier.fillMaxSize(),
                )
                MineSection.Albums -> MineFeatureMediaItemsSection(
                    home = home,
                    contentType = ProviderContentType.Albums,
                    title = "专辑",
                    modifier = Modifier.fillMaxSize(),
                )
                MineSection.LocalMusic -> LocalMusicSection(
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
private fun MineFeatureSectionChips(home: HomeFeatureController, includeSecondary: Boolean) {
    val state = home.uiState.collectAsStateWithLifecycle().value
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HomeCompactFilterChip(state.mineSection == MineSection.Playlists, { home.setMineSection(MineSection.Playlists) }, "歌单")
        HomeCompactFilterChip(state.mineSection == MineSection.Artists, { home.setMineSection(MineSection.Artists) }, "歌手")
        HomeCompactFilterChip(state.mineSection == MineSection.Albums, { home.setMineSection(MineSection.Albums) }, "专辑")
        HomeCompactFilterChip(state.mineSection == MineSection.LocalMusic, { home.setMineSection(MineSection.LocalMusic) }, "本地")
        if (includeSecondary) {
            when (state.mineSection) {
                MineSection.Playlists,
                MineSection.Songs -> {
                    Spacer(Modifier.width(12.dp))
                    HomePlaylistFilterChips(home)
                }
                MineSection.LocalMusic -> {
                    Spacer(Modifier.width(12.dp))
                    LocalMusicViewModeTabs()
                }
                MineSection.Artists,
                MineSection.Albums -> Unit
            }
        }
    }
}

@Composable
private fun HomeCompactFilterChip(selected: Boolean, onClick: () -> Unit, label: String) {
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
private fun HomePlaylistFilterChips(home: HomeFeatureController) {
    val filter = home.uiState.collectAsStateWithLifecycle().value.playlistFilter
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeCompactFilterChip(filter == PlaylistFilter.UserPlaylists, { home.setPlaylistFilter(PlaylistFilter.UserPlaylists) }, "用户")
        HomeCompactFilterChip(filter == PlaylistFilter.FavoritePlaylists, { home.setPlaylistFilter(PlaylistFilter.FavoritePlaylists) }, "收藏")
        HomeCompactFilterChip(filter == PlaylistFilter.Local, { home.setPlaylistFilter(PlaylistFilter.Local) }, "本地")
    }
}

@Composable
private fun MineFeaturePlaylistsSection(
    home: HomeFeatureController,
    showFilter: Boolean,
    modifier: Modifier,
) {
    val graph = LocalHomeFeatureUiGraph.current
    val homeState = home.uiState.collectAsStateWithLifecycle().value
    val localState = graph.localPlaylist.uiState.collectAsStateWithLifecycle().value
    val catalog = graph.providerCatalog.uiState.collectAsStateWithLifecycle().value
    val fileActions = LocalLocalPlaylistFileActions.current
    var showProviderCreateDialog by remember { mutableStateOf(false) }
    var createProviderId by remember { mutableStateOf<String?>(null) }
    var showLocalCreateDialog by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }

    val filter = homeState.playlistFilter
    val providerSections = when (filter) {
        PlaylistFilter.UserPlaylists, PlaylistFilter.All -> homeState.minePlaylistSections
        PlaylistFilter.FavoritePlaylists -> homeState.mineFavoritePlaylistSections
        PlaylistFilter.Local -> emptyList()
    }
    val visibleSections = remember(providerSections) { providerSections.filterNot { it.isLoginRequired } }
    val lockedProviders = remember(providerSections) {
        providerSections.filter { it.isLoginRequired }.map { it.feature }.distinctBy { it.providerId }
    }
    val frequentPlaylists = remember(
        homeState.minePlaylistSections,
        homeState.mineFavoritePlaylistSections,
        homeState.playlistPlaybackStats,
    ) {
        frequentlyPlayedMinePlaylists(
            playlists = (homeState.minePlaylistSections + homeState.mineFavoritePlaylistSections)
                .filterNot { it.isLoginRequired }
                .flatMap { it.playlists },
            playbackStats = homeState.playlistPlaybackStats,
        )
    }
    val selectedMineProviderIds = remember(catalog.mineProviderIds, catalog.enabledProviderIds, catalog.availableProviders) {
        val configured = catalog.mineProviderIds
        (if (configured.isEmpty()) catalog.availableProviders.mapTo(linkedSetOf()) { it.providerId } else configured)
            .intersect(catalog.enabledProviderIds)
    }
    val songEntryFeatures = remember(catalog.features, selectedMineProviderIds) {
        catalog.features.filter {
            it.category == ProviderFeatureCategory.Mine &&
                it.contentType == ProviderContentType.Songs &&
                it.providerId in selectedMineProviderIds
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showFilter) HomePlaylistFilterChips(home)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (filter == PlaylistFilter.UserPlaylists && frequentPlaylists.isNotEmpty()) {
                item(key = "playlists:frequent") {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f), RoundedCornerShape(16.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("我的常听", style = MaterialTheme.typography.titleMedium)
                        ProviderPlaylistGrid(
                            playlists = frequentPlaylists,
                            onClick = { home.openPlaylist(it, home.categoryForMinePlaylist(it)) },
                            maxRows = 2,
                        )
                    }
                }
            }

            if (filter == PlaylistFilter.Local) {
                item(key = "header:local-playlists") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("本地歌单", style = MaterialTheme.typography.titleMedium)
                        Row {
                            TextButton(onClick = { showLocalCreateDialog = true }) { Text("新建") }
                            TextButton(onClick = { fileActions.importFile?.invoke() }, enabled = fileActions.importFile != null) {
                                Text("导入")
                            }
                        }
                    }
                }
                if (localState.playlists.isEmpty()) {
                    item(key = "empty:local-playlists") { ProviderContentMessage("暂无本地歌单，可新建或导入 .fuo 文件") }
                } else {
                    item(key = "playlists:local") {
                        ProviderPlaylistGrid(
                            playlists = localState.playlists.map { playlist ->
                                ProviderPlaylist(
                                    id = playlist.id,
                                    title = playlist.title,
                                    providerId = "local",
                                    providerName = "本地 · ${playlist.tracks.size} 首",
                                    description = playlist.description,
                                    trackCount = playlist.tracks.size,
                                )
                            },
                            onClick = { card -> localState.playlists.firstOrNull { it.id == card.id }?.let(graph.localPlaylist::open) },
                        )
                    }
                }
            }

            visibleSections.forEach { contentSection ->
                item(key = "header:${contentSection.feature.id}") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ProviderFeatureHeader(feature = contentSection.feature)
                        if (home.creatablePlaylistProviders().any { it.providerId == contentSection.feature.providerId }) {
                            TextButton(onClick = {
                                createProviderId = contentSection.feature.providerId
                                showProviderCreateDialog = true
                            }) { Text("新建") }
                        }
                    }
                }
                when {
                    contentSection.errorMessage != null -> item(key = "error:${contentSection.feature.id}") {
                        ProviderContentMessage(contentSection.errorMessage)
                    }
                    contentSection.playlists.isNotEmpty() -> item(key = "playlists:${contentSection.feature.id}") {
                        ProviderPlaylistGrid(
                            playlists = sortMinePlaylistsByStats(contentSection.playlists, homeState.playlistPlaybackStats),
                            onClick = { home.openPlaylist(it, contentSection.feature.category) },
                        )
                    }
                    else -> item(key = "empty:${contentSection.feature.id}") { ProviderContentMessage("暂无歌单") }
                }
            }

            if (lockedProviders.isNotEmpty()) {
                item(key = "locked-providers:mine-playlists") {
                    ProviderLockedSummary(lockedProviders) { home.openSettings(it.providerId) }
                }
            }
            if (filter == PlaylistFilter.UserPlaylists && songEntryFeatures.isNotEmpty()) {
                item(key = "header:mine-song-entries") { Text("我的歌曲", style = MaterialTheme.typography.titleMedium) }
                item(key = "mine-song-entries") {
                    ProviderFeatureCoverGrid(songEntryFeatures, home::openFeature)
                }
            }
        }
    }

    if (showProviderCreateDialog) {
        val provider = home.creatablePlaylistProviders().firstOrNull { it.providerId == createProviderId }
        AlertDialog(
            onDismissRequest = { showProviderCreateDialog = false },
            title = { Text("新建歌单") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将在 ${provider?.providerName.orEmpty()} 创建")
                    OutlinedTextField(playlistName, { playlistName = it }, label = { Text("歌单名称") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = provider != null && playlistName.isNotBlank(),
                    onClick = {
                        provider?.let { home.createProviderPlaylist(it.providerId, playlistName) }
                        playlistName = ""
                        showProviderCreateDialog = false
                    },
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showProviderCreateDialog = false }) { Text("取消") } },
        )
    }

    if (showLocalCreateDialog) {
        AlertDialog(
            onDismissRequest = { showLocalCreateDialog = false },
            title = { Text("新建本地歌单") },
            text = {
                OutlinedTextField(playlistName, { playlistName = it }, label = { Text("歌单名称") }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    enabled = playlistName.isNotBlank(),
                    onClick = {
                        graph.localPlaylist.create(playlistName)
                        playlistName = ""
                        showLocalCreateDialog = false
                    },
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showLocalCreateDialog = false }) { Text("取消") } },
        )
    }

    localState.importPreview?.let { preview ->
        HomeLocalPlaylistImportDialog(graph.localPlaylist, preview)
    }
}

@Composable
private fun HomeLocalPlaylistImportDialog(actions: LocalPlaylistUiActions, preview: LocalPlaylistImportPreview) {
    val existing = actions.existingForImport(preview)
    AlertDialog(
        onDismissRequest = actions::cancelImport,
        title = { Text("导入本地歌单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("《${preview.title}》 · ${preview.tracks.size} 首")
                if (preview.description.isNotBlank()) Text(preview.description)
                if (preview.skippedLineCount > 0) Text("已跳过 ${preview.skippedLineCount} 行无法识别的数据")
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { actions.importPlaylist(LocalPlaylistImportMode.New) }) { Text("新建导入") }
                if (existing != null) {
                    TextButton(onClick = { actions.importPlaylist(LocalPlaylistImportMode.Replace, existing.id) }) { Text("替换同名") }
                }
            }
        },
        dismissButton = { TextButton(onClick = actions::cancelImport) { Text("取消") } },
    )
}

@Composable
private fun MineFeatureMediaItemsSection(
    home: HomeFeatureController,
    contentType: ProviderContentType,
    title: String,
    modifier: Modifier,
) {
    val state = home.uiState.collectAsStateWithLifecycle().value
    val sections = remember(state.mineSections, contentType) {
        state.mineSections.filter { it.feature.contentType == contentType }
    }
    val visible = remember(sections) { sections.filterNot { it.isLoginRequired } }
    val locked = remember(sections) { sections.filter { it.isLoginRequired }.map { it.feature }.distinctBy { it.providerId } }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sections.isEmpty()) item { EmptyProviderContentHint(title) }
        visible.forEach { section ->
            item(key = "header:${section.feature.id}") { ProviderFeatureHeader(feature = section.feature) }
            when {
                section.errorMessage != null -> item(key = "error:${section.feature.id}") {
                    ProviderContentMessage(section.errorMessage)
                }
                section.mediaItems.isNotEmpty() -> item(key = "items:${section.feature.id}") {
                    ProviderMediaItemGrid(section.mediaItems, home::openMediaItem)
                }
                else -> item(key = "empty:${section.feature.id}") { ProviderContentMessage("暂无$title") }
            }
        }
        if (locked.isNotEmpty()) {
            item(key = "locked:$title") { ProviderLockedSummary(locked) { home.openSettings(it.providerId) } }
        }
    }
}

private fun sortMinePlaylistsByStats(
    playlists: List<ProviderPlaylist>,
    stats: Map<String, PlaylistPlaybackStat>,
): List<ProviderPlaylist> = playlists.withIndex().sortedWith(
    compareByDescending<IndexedValue<ProviderPlaylist>> {
        stats["${it.value.providerId}::${it.value.id}"]?.lastPlayedAtMillis ?: 0L
    }.thenBy { it.index }
).map { it.value }
