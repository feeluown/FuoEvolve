package org.feeluown.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal data class LocalMusicUiGraph(
    val feature: LocalMusicFeatureController,
    val playbackQueue: PlaybackQueueUiPort,
    val downloads: DownloadActionPort,
    val providerTrackActions: ProviderTrackActionPort,
)

internal val LocalLocalMusicUiGraph = staticCompositionLocalOf<LocalMusicUiGraph> {
    error("LocalMusicUiGraph is not installed")
}

internal data class LocalMusicCollection(
    val key: String,
    val title: String,
    val coverUrl: String?,
    val tracks: List<MusicTrack>,
) {
    val trackCount: Int get() = tracks.size
}

@Composable
fun LocalMusicSection(
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    hasImagePermission: Boolean,
    onRequestImagePermission: () -> Unit,
    showModeFilter: Boolean,
    modifier: Modifier,
) {
    val graph = LocalLocalMusicUiGraph.current
    val uiState by graph.feature.uiState.collectAsStateWithLifecycle()
    var previousImagePermission by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(hasAudioPermission, hasImagePermission) {
        graph.feature.onPermissionChange(hasAudioPermission)
        if (hasAudioPermission) {
            if (previousImagePermission == false && hasImagePermission) {
                graph.feature.refresh()
            } else {
                graph.feature.ensure()
            }
        }
        previousImagePermission = hasImagePermission
    }
    val viewMode = uiState.viewMode
    val collections = remember(
        uiState.tracks,
        uiState.directories,
        uiState.excludedDirectoryIds,
        viewMode,
    ) {
        buildLocalMusicCollections(
            mode = viewMode,
            tracks = uiState.tracks,
            directories = uiState.directories,
            excludedDirectoryIds = uiState.excludedDirectoryIds,
        )
    }
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (isWideLayout) 6.dp else 12.dp),
    ) {
        if (!hasAudioPermission) {
            PermissionPanel(onRequestAudioPermission)
            return@Column
        }
        if (!hasImagePermission) {
            LocalMusicImagePermissionPanel(onRequestImagePermission)
        }
        LoadingIndicator(uiState.isLoading)
        uiState.errorMessage?.let { ProviderContentMessage(it) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showModeFilter) {
                LocalMusicViewModeTabs()
            } else {
                Spacer(Modifier)
            }
        }
        LocalMusicCollectionOverview(
            mode = viewMode,
            collections = collections,
            onClick = { graph.feature.openCollection(viewMode, it.key) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalMusicCollectionScreen() {
    val graph = LocalLocalMusicUiGraph.current
    val uiState by graph.feature.uiState.collectAsStateWithLifecycle()
    val selection = uiState.selectedCollection ?: return
    val collections = remember(
        uiState.tracks,
        uiState.directories,
        uiState.excludedDirectoryIds,
        selection.mode,
    ) {
        buildLocalMusicCollections(
            mode = selection.mode,
            tracks = uiState.tracks,
            directories = uiState.directories,
            excludedDirectoryIds = uiState.excludedDirectoryIds,
        )
    }
    val selectedCollection = collections.firstOrNull { it.key == selection.key }
    LaunchedEffect(selection, selectedCollection, uiState.isLoading) {
        if (!uiState.isLoading && selectedCollection == null) {
            graph.feature.closeCollection()
        }
    }
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    val playbackUiPort = LocalPlaybackUiPort.current
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = selectedCollection?.title ?: selection.key,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = graph.feature::closeCollection) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (playbackUiPort.currentTrack != null) {
                PlaybackMiniPlayer()
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            LoadingIndicator(uiState.isLoading)
            uiState.errorMessage?.let { ProviderContentMessage(it) }
            selectedCollection?.let { collection ->
                LocalMusicCollectionDetail(
                    graph = graph,
                    collection = collection,
                    mode = selection.mode,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(
                            horizontal = if (isWideLayout) 20.dp else 16.dp,
                            vertical = if (isWideLayout) 12.dp else 0.dp,
                        ),
                    isWideLayout = isWideLayout,
                )
            }
        }
    }
}

@Composable
private fun LocalMusicCollectionOverview(
    mode: LocalMusicViewMode,
    collections: List<LocalMusicCollection>,
    onClick: (LocalMusicCollection) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (LocalAppLayoutInfo.current.useWideLayout) 8.dp else 12.dp),
    ) {
        if (collections.isEmpty()) {
            item { EmptyLocalMusicHint() }
        } else {
            item(key = "local-music-collections") {
                when (mode) {
                    LocalMusicViewMode.All -> ProviderPlaylistGrid(
                        playlists = collections.map { collection ->
                            ProviderPlaylist(
                                id = collection.key,
                                title = collection.title,
                                providerId = "local",
                                providerName = "本地 · ${collection.trackCount} 首",
                                coverUrl = collection.coverUrl,
                                trackCount = collection.trackCount,
                            )
                        },
                        onClick = { playlist ->
                            collections.firstOrNull { it.key == playlist.id }?.let(onClick)
                        },
                    )
                    LocalMusicViewMode.Artist,
                    LocalMusicViewMode.Album -> ProviderMediaItemGrid(
                        items = collections.map { collection ->
                            ProviderMediaItem(
                                id = collection.key,
                                title = collection.title,
                                providerId = "local",
                                providerName = "本地",
                                type = if (mode == LocalMusicViewMode.Artist) {
                                    ProviderMediaItemType.Artist
                                } else {
                                    ProviderMediaItemType.Album
                                },
                                coverUrl = collection.coverUrl,
                                trackCount = collection.trackCount,
                            )
                        },
                        onClick = { item ->
                            collections.firstOrNull { it.key == item.id }?.let(onClick)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalMusicCollectionDetail(
    graph: LocalMusicUiGraph,
    collection: LocalMusicCollection,
    mode: LocalMusicViewMode,
    modifier: Modifier,
    isWideLayout: Boolean,
) {
    val displayTrack = collection.toDisplayTrack(mode)
    val placeholder = mode.localMusicCollectionPlaceholder()
    val subtitle = mode.localMusicCollectionSubtitle(collection.trackCount)
    val playAll = {
        if (collection.tracks.isNotEmpty()) {
            graph.playbackQueue.playTracks(collection.tracks, 0)
        }
    }
    if (isWideLayout) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(0.36f)
                    .widthIn(min = 240.dp, max = 360.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CoverBox(
                    track = displayTrack,
                    modifier = Modifier.size(168.dp),
                    placeholder = placeholder,
                )
                Text(
                    text = collection.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (collection.tracks.isNotEmpty()) {
                    PlayAllButton(onClick = playAll)
                }
            }
            LocalMusicTrackList(
                graph = graph,
                tracks = collection.tracks,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                isWideLayout = true,
            )
        }
    } else {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderDetailHeader(
                track = displayTrack,
                title = collection.title,
                subtitle = subtitle,
                description = "",
                placeholder = placeholder,
                action = {
                    PlayAllButton(
                        onClick = playAll,
                        enabled = collection.tracks.isNotEmpty(),
                    )
                },
            )
            LocalMusicTrackList(
                graph = graph,
                tracks = collection.tracks,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                isWideLayout = false,
            )
        }
    }
}

@Composable
private fun LocalMusicTrackList(
    graph: LocalMusicUiGraph,
    tracks: List<MusicTrack>,
    modifier: Modifier,
    isWideLayout: Boolean,
) {
    if (isWideLayout) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (tracks.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ProviderContentMessage("暂无歌曲")
                }
            } else {
                items(tracks, key = { it.id }) { track ->
                    LocalMusicTrackRow(graph, track, tracks)
                }
            }
        }
    } else {
        LazyColumn(modifier = modifier) {
            if (tracks.isEmpty()) {
                item { ProviderContentMessage("暂无歌曲") }
            } else {
                itemsIndexed(tracks, key = { _, item -> item.id }) { _, track ->
                    LocalMusicTrackRow(graph, track, tracks)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LocalMusicTrackRow(
    graph: LocalMusicUiGraph,
    track: MusicTrack,
    queue: List<MusicTrack>,
) {
    TrackRow(
        track = track,
        downloadState = graph.downloads.downloadStates[track.id],
        onClick = {
            val index = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            graph.playbackQueue.playTracks(queue, index)
        },
        onAddToUpNext = { graph.playbackQueue.addToUpNext(track) },
        onDownload = { graph.downloads.download(track) },
        onDeleteDownload = { graph.downloads.deleteDownload(track) },
        onOpenArtist = { graph.providerTrackActions.openTrackArtist(track) },
        onOpenAlbum = { graph.providerTrackActions.openTrackAlbum(track) },
        onEditLocalMetadata = { graph.feature.openMetadataEditor(track) },
    )
}

internal fun buildLocalMusicCollections(
    mode: LocalMusicViewMode,
    tracks: List<MusicTrack>,
    directories: List<LocalMusicDirectory>,
    excludedDirectoryIds: Set<String>,
): List<LocalMusicCollection> {
    val sortedTracks = when (mode) {
        LocalMusicViewMode.All -> tracks.sortedWith(
            compareBy<MusicTrack> { localTitleSectionOrder(localTitleSection(it.title)) }
                .thenBy { it.title.lowercase() }
                .thenBy { it.artists.lowercase() },
        )
        LocalMusicViewMode.Artist -> tracks.sortedWith(
            compareBy<MusicTrack> { normalizedGroupName(it.artists, "未知歌手").lowercase() }
                .thenBy { it.album.lowercase() }
                .thenBy { it.title.lowercase() },
        )
        LocalMusicViewMode.Album -> tracks.sortedWith(
            compareBy<MusicTrack> { normalizedGroupName(it.album, "未知专辑").lowercase() }
                .thenBy { it.artists.lowercase() }
                .thenBy { it.title.lowercase() },
        )
    }
    return when (mode) {
        LocalMusicViewMode.All -> directories
            .filter { directory -> !isLocalMusicDirectoryExcluded(directory.id, excludedDirectoryIds) }
            .map { directory ->
                LocalMusicCollection(
                    key = directory.id,
                    title = directory.name,
                    coverUrl = directory.coverUrl,
                    tracks = sortedTracks.filter { it.localDirectoryId == directory.id },
                )
            }
        LocalMusicViewMode.Artist -> sortedTracks
            .groupBy { normalizedGroupName(it.artists, "未知歌手") }
            .entries
            .map { (name, group) ->
                LocalMusicCollection(
                    key = name,
                    title = name,
                    coverUrl = group.firstNotNullOfOrNull { it.coverUrl },
                    tracks = group,
                )
            }
        LocalMusicViewMode.Album -> sortedTracks
            .groupBy { normalizedGroupName(it.album, "未知专辑") }
            .entries
            .map { (name, group) ->
                LocalMusicCollection(
                    key = name,
                    title = name,
                    coverUrl = group.firstNotNullOfOrNull { it.coverUrl },
                    tracks = group,
                )
            }
    }
}

private fun LocalMusicCollection.toDisplayTrack(mode: LocalMusicViewMode): MusicTrack {
    return MusicTrack(
        id = "local-collection:${mode.name}:$key",
        title = title,
        artists = if (mode == LocalMusicViewMode.Artist) title else "本地",
        album = if (mode == LocalMusicViewMode.Album) title else "",
        source = "local",
        sourceType = TrackSourceType.LocalMediaStore,
        coverUrl = coverUrl,
    )
}

private fun LocalMusicViewMode.localMusicCollectionPlaceholder(): CoverPlaceholder {
    return when (this) {
        LocalMusicViewMode.All -> CoverPlaceholder.Playlist
        LocalMusicViewMode.Artist -> CoverPlaceholder.Artist
        LocalMusicViewMode.Album -> CoverPlaceholder.Album
    }
}

private fun LocalMusicViewMode.localMusicCollectionSubtitle(trackCount: Int): String {
    return when (this) {
        LocalMusicViewMode.All -> "本地文件夹 · $trackCount 首"
        LocalMusicViewMode.Artist -> "本地 · 歌手 · $trackCount 首"
        LocalMusicViewMode.Album -> "本地 · 专辑 · $trackCount 首"
    }
}

@Composable
private fun LocalMusicImagePermissionPanel(onRequestImagePermission: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = "允许读取图片以显示封面",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onRequestImagePermission) {
                Text("授权图片")
            }
        }
    }
}

@Composable
fun LocalMetadataDialog() {
    val feature = LocalLocalMusicUiGraph.current.feature
    val uiState by feature.uiState.collectAsStateWithLifecycle()
    val track = uiState.metadataEditorTrack ?: return
    var title by remember(track.id, track.title) { mutableStateOf(track.title) }
    var artists by remember(track.id, track.artists) { mutableStateOf(track.artists) }
    var album by remember(track.id, track.album) { mutableStateOf(track.album) }
    AlertDialog(
        onDismissRequest = feature::closeMetadataEditor,
        title = { Text("修改元信息") },
        text = {
            Column(
                modifier = if (LocalAppLayoutInfo.current.useWideLayout) {
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                } else {
                    Modifier
                },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = artists,
                    onValueChange = { artists = it },
                    label = { Text("歌手") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("专辑") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (uiState.metadataProviders.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        uiState.metadataProviders.forEach { provider ->
                            FilterChip(
                                selected = uiState.selectedMetadataProviderId == provider.providerId,
                                onClick = { feature.onMetadataProviderChange(provider.providerId) },
                                label = { Text(provider.providerName) },
                            )
                        }
                    }
                    TextButton(
                        enabled = !uiState.isLoading,
                        onClick = { feature.searchMetadata(title, artists, album) },
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("搜索补充")
                    }
                } else {
                    Text(
                        text = "没有可用音源",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                uiState.metadataSearchMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (uiState.metadataSearchResults.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                    ) {
                        itemsIndexed(
                            uiState.metadataSearchResults,
                            key = { _, item -> item.id },
                        ) { _, result ->
                            LocalMetadataSearchResultRow(
                                track = result,
                                onApplyMetadata = {
                                    title = result.title
                                    artists = result.artists
                                    album = result.album
                                    feature.applyProviderMetadata(track, result)
                                },
                                onDownloadLyrics = { feature.downloadLyrics(track, result) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !uiState.isLoading,
                onClick = { feature.saveMetadata(track, title, artists, album) },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = feature::closeMetadataEditor) {
                Text("关闭")
            }
        },
    )
}

@Composable
fun LocalMetadataSearchResultRow(
    track: MusicTrack,
    onApplyMetadata: () -> Unit,
    onDownloadLyrics: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverBox(track, modifier = Modifier.size(48.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title.ifBlank { "未知歌曲" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(track.artists, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onApplyMetadata) {
                Text("使用元信息")
            }
            TextButton(onClick = onDownloadLyrics) {
                Text("下载歌词")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalMusicViewModeTabs() {
    val feature = LocalLocalMusicUiGraph.current.feature
    val uiState by feature.uiState.collectAsStateWithLifecycle()
    val modes = listOf(
        LocalMusicViewMode.All to "全部",
        LocalMusicViewMode.Artist to "歌手",
        LocalMusicViewMode.Album to "专辑",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        modes.forEach { (mode, label) ->
            CompactFilterChip(
                selected = uiState.viewMode == mode,
                onClick = { feature.onViewModeChange(mode) },
                label = label,
            )
        }
    }
}

@Composable
fun EmptyLocalMusicHint() {
    FuoEmptyState(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        title = "未发现本地音乐",
    )
}
