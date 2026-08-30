package org.feeluown.mobile

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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val state by home.uiState.collectAsStateWithLifecycle()
    val localMusicState by graph.localMusic.uiState.collectAsStateWithLifecycle()
    val wide = LocalAppLayoutInfo.current.useWideLayout

    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (wide) 6.dp else 12.dp)) {
        MineOwnerChips(
            home = home,
            includeSecondary = wide,
        )
        PullToRefreshBox(
            isRefreshing = state.isLoading || (state.mineSection == MineSection.LocalMusic && localMusicState.isLoading),
            onRefresh = home::refreshMine,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when (state.mineSection) {
                MineSection.Playlists, MineSection.Songs -> MineOwnerPlaylists(home, !wide, Modifier.fillMaxSize())
                MineSection.Artists -> MineOwnerMediaItems(home, ProviderContentType.Artists, "歌手", Modifier.fillMaxSize())
                MineSection.Albums -> MineOwnerMediaItems(home, ProviderContentType.Albums, "专辑", Modifier.fillMaxSize())
                MineSection.LocalMusic -> LocalMusicSection(
                    hasAudioPermission = hasAudioPermission,
                    onRequestAudioPermission = onRequestAudioPermission,
                    hasImagePermission = hasImagePermission,
                    onRequestImagePermission = onRequestImagePermission,
                    showModeFilter = !wide,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun MineOwnerChips(
    home: HomeFeatureController,
    includeSecondary: Boolean,
) {
    val state by home.uiState.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MineChip(state.mineSection == MineSection.Playlists, { home.setMineSection(MineSection.Playlists) }, "歌单")
            MineChip(state.mineSection == MineSection.Artists, { home.setMineSection(MineSection.Artists) }, "歌手")
            MineChip(state.mineSection == MineSection.Albums, { home.setMineSection(MineSection.Albums) }, "专辑")
            MineChip(state.mineSection == MineSection.LocalMusic, { home.setMineSection(MineSection.LocalMusic) }, "本地")
            if (includeSecondary) {
                Spacer(Modifier.width(12.dp))
                if (state.mineSection == MineSection.LocalMusic) LocalMusicViewModeTabs()
                else if (state.mineSection == MineSection.Playlists || state.mineSection == MineSection.Songs) MineFilterChips(home)
            }
        }
        TextButton(onClick = home::openPlaybackHistory) {
            Text(
                text = "播放记录",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MineChip(selected: Boolean, onClick: () -> Unit, label: String) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = Modifier.height(32.dp))
}

@Composable
private fun MineFilterChips(home: HomeFeatureController) {
    val filter by home.uiState.collectAsStateWithLifecycle().let { state -> mutableStateOf(state.value.playlistFilter) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MineChip(filter == PlaylistFilter.UserPlaylists, { home.setPlaylistFilter(PlaylistFilter.UserPlaylists) }, "用户")
        MineChip(filter == PlaylistFilter.FavoritePlaylists, { home.setPlaylistFilter(PlaylistFilter.FavoritePlaylists) }, "收藏")
        MineChip(filter == PlaylistFilter.Local, { home.setPlaylistFilter(PlaylistFilter.Local) }, "本地")
    }
}

@Composable
private fun MineOwnerPlaylists(home: HomeFeatureController, showFilter: Boolean, modifier: Modifier) {
    val graph = LocalHomeFeatureUiGraph.current
    val state by home.uiState.collectAsStateWithLifecycle()
    val local by graph.localPlaylist.uiState.collectAsStateWithLifecycle()
    val catalog by graph.providerCatalog.uiState.collectAsStateWithLifecycle()
    val fileActions = LocalLocalPlaylistFileActions.current
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    var initialPlaylistLoadPending by rememberSaveable {
        mutableStateOf(
            state.isLoading ||
                (state.minePlaylistSections.isEmpty() && state.mineFavoritePlaylistSections.isEmpty()),
        )
    }
    var initialPlaylistLoadStarted by rememberSaveable { mutableStateOf(false) }
    var createLocal by remember { mutableStateOf(false) }
    var createProvider by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var playlistHistoryStats by remember { mutableStateOf<List<ListeningResourceStat>>(emptyList()) }

    val sections = when (state.playlistFilter) {
        PlaylistFilter.UserPlaylists, PlaylistFilter.All -> state.minePlaylistSections
        PlaylistFilter.FavoritePlaylists -> state.mineFavoritePlaylistSections
        PlaylistFilter.Local -> emptyList()
    }
    val visible = sections.filterNot { it.isLoginRequired }
    val locked = sections.filter { it.isLoginRequired }.map { it.feature }.distinctBy { it.providerId }
    val frequent = mineFrequentPlaylists(state, playlistHistoryStats)
    val selectedMineIds = (catalog.mineProviderIds.ifEmpty { catalog.availableProviders.mapTo(linkedSetOf()) { it.providerId } })
        .intersect(catalog.enabledProviderIds)
    val songEntries = catalog.features.filter {
        it.category == ProviderFeatureCategory.Mine && it.contentType == ProviderContentType.Songs && it.providerId in selectedMineIds
    }

    // The legacy settings map is kept as a downgrade-compatible shadow only. It is a refresh signal
    // here; ordering/ranking data is read exclusively from the new listening-history repository.
    LaunchedEffect(state.minePlaylistSections, state.mineFavoritePlaylistSections, state.playlistPlaybackStats) {
        runCatching {
            graph.listeningHistory.topResources(
                resourceType = ListeningResourceType.Playlist,
                range = ListeningTimeRange.All,
                limit = 500,
            )
        }.onSuccess { stats ->
            playlistHistoryStats = stats
        }
    }

    LaunchedEffect(state.homeSection, state.mineSection, state.isLoading) {
        if (!initialPlaylistLoadPending || state.homeSection != HomeSection.Mine ||
            (state.mineSection != MineSection.Playlists && state.mineSection != MineSection.Songs)
        ) {
            return@LaunchedEffect
        }
        if (state.isLoading) {
            initialPlaylistLoadStarted = true
            return@LaunchedEffect
        }
        if (!initialPlaylistLoadStarted) return@LaunchedEffect

        if (state.playlistFilter != PlaylistFilter.Local) {
            listState.scrollToItem(0)
        }
        initialPlaylistLoadPending = false
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showFilter) MineFilterChips(home)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.playlistFilter == PlaylistFilter.UserPlaylists) {
                item("mine-frequent") {
                    if (frequent.isNotEmpty()) {
                        Text("我的常听", style = MaterialTheme.typography.titleMedium)
                        ProviderPlaylistGrid(
                            frequent,
                            { home.openPlaylist(it, home.categoryForMinePlaylist(it)) },
                            maxRows = 2,
                        )
                    }
                }
            }
            if (state.playlistFilter == PlaylistFilter.Local) {
                item("local-header") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("本地歌单", style = MaterialTheme.typography.titleMedium)
                        Row {
                            TextButton(onClick = { createLocal = true }) { Text("新建") }
                            TextButton(onClick = { fileActions.importFile?.invoke() }, enabled = fileActions.importFile != null) { Text("导入") }
                        }
                    }
                }
                if (local.playlists.isEmpty()) item("local-empty") { ProviderContentMessage("暂无本地歌单，可新建或导入 .fuo 文件") }
                else item("local-grid") {
                    ProviderPlaylistGrid(
                        playlists = local.playlists.map { p ->
                            ProviderPlaylist(p.id, p.title, "local", "本地 · ${p.tracks.size} 首", description = p.description, trackCount = p.tracks.size)
                        },
                        onClick = { card -> local.playlists.firstOrNull { it.id == card.id }?.let(graph.localPlaylist::open) },
                    )
                }
            }
            visible.forEach { section ->
                item("head:${section.feature.id}") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ProviderFeatureHeader(section.feature)
                        if (home.creatablePlaylistProviders().any { it.providerId == section.feature.providerId }) {
                            TextButton(onClick = { createProvider = section.feature.providerId }) { Text("新建") }
                        }
                    }
                }
                val errorMessage = section.errorMessage
                if (errorMessage != null) item("err:${section.feature.id}") { ProviderContentMessage(errorMessage) }
                else if (section.playlists.isNotEmpty()) item("grid:${section.feature.id}") {
                    ProviderPlaylistGrid(
                        mineSortPlaylists(section.playlists, playlistHistoryStats),
                        { home.openPlaylist(it, section.feature.category) },
                    )
                }
            }
            if (locked.isNotEmpty()) item("mine-locked") { ProviderLockedSummary(locked) { home.openSettings(it.providerId) } }
            if (state.playlistFilter == PlaylistFilter.UserPlaylists && songEntries.isNotEmpty()) {
                item("mine-songs-head") { Text("我的歌曲", style = MaterialTheme.typography.titleMedium) }
                item("mine-songs") { ProviderFeatureCoverGrid(songEntries, home::openFeature) }
            }
        }
    }

    if (createLocal) PlaylistNameDialog("新建本地歌单", name, { name = it }, {
        graph.localPlaylist.create(name); name = ""; createLocal = false
    }) { createLocal = false }

    createProvider?.let { providerId ->
        val provider = home.creatablePlaylistProviders().firstOrNull { it.providerId == providerId }
        PlaylistNameDialog("在 ${provider?.providerName.orEmpty()} 新建歌单", name, { name = it }, {
            provider?.let { home.createProviderPlaylist(it.providerId, name) }; name = ""; createProvider = null
        }) { createProvider = null }
    }

    local.importPreview?.let { preview ->
        val existing = graph.localPlaylist.existingForImport(preview)
        AlertDialog(
            onDismissRequest = graph.localPlaylist::cancelImport,
            title = { Text("导入本地歌单") },
            text = { Text("《${preview.title}》 · ${preview.tracks.size} 首") },
            confirmButton = {
                Row {
                    TextButton(onClick = { graph.localPlaylist.importPlaylist(LocalPlaylistImportMode.CreateNew) }) { Text("新建导入") }
                    if (existing != null) TextButton(onClick = {
                        graph.localPlaylist.importPlaylist(LocalPlaylistImportMode.Replace, existing.id)
                    }) { Text("替换同名") }
                }
            },
            dismissButton = { TextButton(onClick = graph.localPlaylist::cancelImport) { Text("取消") } },
        )
    }
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, onValueChange, label = { Text("歌单名称") }, singleLine = true) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = value.isNotBlank()) { Text("创建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MineOwnerMediaItems(home: HomeFeatureController, type: ProviderContentType, title: String, modifier: Modifier) {
    val state by home.uiState.collectAsStateWithLifecycle()
    val sections = state.mineSections.filter { it.feature.contentType == type }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sections.isEmpty()) item { EmptyProviderContentHint(title) }
        sections.filterNot { it.isLoginRequired }.forEach { section ->
            item("head:${section.feature.id}") { ProviderFeatureHeader(section.feature) }
            val errorMessage = section.errorMessage
            if (errorMessage != null) item("err:${section.feature.id}") { ProviderContentMessage(errorMessage) }
            else if (section.mediaItems.isNotEmpty()) item("items:${section.feature.id}") {
                ProviderMediaItemGrid(section.mediaItems, home::openMediaItem)
            }
        }
        val locked = sections.filter { it.isLoginRequired }.map { it.feature }.distinctBy { it.providerId }
        if (locked.isNotEmpty()) item("locked:$title") { ProviderLockedSummary(locked) { home.openSettings(it.providerId) } }
    }
}

private fun mineKey(playlist: ProviderPlaylist) = "${playlist.providerId}::${playlist.id}"

private fun mineHistoryStat(
    playlist: ProviderPlaylist,
    stats: List<ListeningResourceStat>,
): ListeningResourceStat? {
    stats.firstOrNull { stat ->
        stat.resource.sourceId == playlist.providerId && stat.resource.sourceResourceId == playlist.id
    }?.let { return it }
    // Early preview history used the source id "context" for playlist relations. Use an id-only
    // fallback only when it is unambiguous, so those events remain useful without cross-provider joins.
    return stats.filter { it.resource.sourceResourceId == playlist.id }.singleOrNull()
}

private fun mineSortPlaylists(
    playlists: List<ProviderPlaylist>,
    stats: List<ListeningResourceStat>,
) = playlists.withIndex().sortedWith(
    compareByDescending<IndexedValue<ProviderPlaylist>> {
        mineHistoryStat(it.value, stats)?.lastPlayedAtMillis ?: 0L
    }.thenBy { it.index },
).map { it.value }

private fun mineFrequentPlaylists(
    state: HomeFeatureUiState,
    stats: List<ListeningResourceStat>,
): List<ProviderPlaylist> =
    (state.minePlaylistSections + state.mineFavoritePlaylistSections)
        .filterNot { it.isLoginRequired }
        .flatMap { it.playlists }
        .distinctBy(::mineKey)
        .mapIndexedNotNull { index, playlist ->
            val stat = mineHistoryStat(playlist, stats)?.takeIf { it.contextSessionCount > 0L }
                ?: return@mapIndexedNotNull null
            MineListeningPlaylistRank(playlist, stat, index)
        }
        .sortedWith(
            compareByDescending<MineListeningPlaylistRank> { it.stat.contextSessionCount }
                .thenByDescending { it.stat.qualifiedPlayCount }
                .thenByDescending { it.stat.playedMs }
                .thenByDescending { it.stat.lastPlayedAtMillis }
                .thenBy { it.originalIndex },
        )
        .map { it.playlist }

private data class MineListeningPlaylistRank(
    val playlist: ProviderPlaylist,
    val stat: ListeningResourceStat,
    val originalIndex: Int,
)
