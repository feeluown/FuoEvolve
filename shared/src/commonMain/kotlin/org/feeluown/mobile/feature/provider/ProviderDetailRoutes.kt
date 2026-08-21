package org.feeluown.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

data class ProviderDetailUiGraph(
    val owners: ProviderDetailOwners,
    val playbackQueue: PlaybackQueueUiPort,
    val downloads: DownloadActionPort,
    val playlists: PlaylistActionPort,
    val providerTrackActions: ProviderTrackActionPort,
)

val LocalProviderDetailUiGraph = staticCompositionLocalOf<ProviderDetailUiGraph> {
    error("ProviderDetailUiGraph is not installed")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderFeatureDetailRoute(feature: ProviderFeature) {
    val graph = LocalProviderDetailUiGraph.current
    val owner = graph.owners.feature
    val state by owner.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(feature.id) { owner.activate(feature) }
    val displayFeature = state.feature ?: feature
    val content = state.content
    val contentCount = content?.let { maxOf(it.tracks.size, it.playlists.size, it.mediaItems.size, it.videos.size) }
        ?: state.tracks.size

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(displayFeature.title.ifBlank { "推荐" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = owner::close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = { if (graph.playbackQueue.currentQueueTrack != null) PlaybackMiniPlayer() },
    ) { paddingValues ->
        val body = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp)
        if (LocalAppLayoutInfo.current.useWideLayout) {
            Row(
                modifier = body.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(
                    modifier = Modifier.weight(0.36f).widthIn(min = 240.dp, max = 360.dp).fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!displayFeature.isBilibiliWeeklyFeature()) {
                        CoverBox(
                            track = displayFeature.toDisplayTrack(),
                            modifier = Modifier.size(160.dp),
                            placeholder = if (displayFeature.isDailySongsFeature()) {
                                CoverPlaceholder.DailyRecommendation
                            } else {
                                CoverPlaceholder.Song
                            },
                        )
                    }
                    Text(displayFeature.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${displayFeature.providerName} · $contentCount 项",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.tracks.isNotEmpty()) PlayAllButton(onClick = owner::playAll)
                }
                ProviderFeatureDetailBody(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    feature = displayFeature,
                    state = state,
                )
            }
        } else {
            Column(modifier = body, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LoadingIndicator(state.isLoading)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = "${displayFeature.providerName} · $contentCount 项",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.tracks.isNotEmpty()) PlayAllButton(onClick = owner::playAll)
                }
                ProviderFeatureFilterBar(displayFeature, content, owner::open)
                state.errorMessage?.let { ProviderContentMessage(it) }
                ProviderFeatureDetailContent(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    feature = displayFeature,
                    state = state,
                )
            }
        }
    }
}

@Composable
private fun ProviderFeatureDetailBody(
    modifier: Modifier,
    feature: ProviderFeature,
    state: ProviderFeatureDetailUiState,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LoadingIndicator(state.isLoading)
        state.errorMessage?.let { ProviderContentMessage(it) }
        ProviderFeatureFilterBar(feature, state.content, LocalProviderDetailUiGraph.current.owners.feature::open)
        ProviderFeatureDetailContent(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            feature = feature,
            state = state,
        )
    }
}

@Composable
private fun ProviderFeatureDetailContent(
    modifier: Modifier,
    feature: ProviderFeature,
    state: ProviderFeatureDetailUiState,
) {
    val graph = LocalProviderDetailUiGraph.current
    val content = state.content
    when {
        content?.playlists?.isNotEmpty() == true -> LazyColumn(modifier = modifier) {
            item {
                ProviderPlaylistGrid(
                    playlists = content.playlists,
                    onClick = { graph.owners.playlist.open(it, content.feature.category) },
                )
            }
            if (state.hasMore) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = graph.owners.feature::loadMore) { Text("加载更多") }
                }
            }
        }
        content?.mediaItems?.isNotEmpty() == true -> LazyColumn(modifier = modifier) {
            item {
                ProviderMediaItemGrid(
                    items = content.mediaItems,
                    onClick = graph.owners.mediaItem::open,
                )
            }
            if (state.hasMore) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = graph.owners.feature::loadMore) { Text("加载更多") }
                }
            }
        }
        content?.videos?.isNotEmpty() == true -> LazyColumn(modifier = modifier) {
            item { ProviderVideoList(videos = content.videos, onClick = graph.owners.video::open) }
            if (state.hasMore) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = graph.owners.feature::loadMore) { Text("加载更多") }
                }
            }
        }
        else -> ProviderDetailTrackList(
            tracks = state.tracks,
            emptyMessage = if (feature.isBilibiliWeeklyFeature()) "本期暂无内容" else "暂无内容",
            showEmpty = !state.isLoading && state.errorMessage == null,
            modifier = modifier,
            onClick = graph.owners.feature::play,
            onItemVisible = graph.owners.feature::prefetchIfNeeded,
        )
    }
}

@Composable
private fun ProviderFeatureFilterBar(
    feature: ProviderFeature,
    content: ProviderContentSection?,
    onSelect: (ProviderFeature) -> Unit,
) {
    val filters = content?.let { ProviderFeatureFilterCodec.filters(it.feature.id) }.orEmpty()
    if (filters.isEmpty()) return
    val activeRequestId = ProviderFeatureFilterCodec.requestId(feature.id)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        filters.forEach { filter ->
            Text(filter.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filter.options.forEach { option ->
                    val selected = ProviderFeatureFilterCodec.requestId(option.featureId) == activeRequestId
                    FilterChip(
                        selected = selected,
                        onClick = { if (!selected && option.featureId.isNotBlank()) onSelect(feature.copy(id = option.featureId)) },
                        label = { Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderPlaylistDetailRoute(playlist: ProviderPlaylist, category: ProviderFeatureCategory?) {
    val graph = LocalProviderDetailUiGraph.current
    val owner = graph.owners.playlist
    val state by owner.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(playlist.id, category) { owner.activate(playlist, category) }
    val displayPlaylist = state.playlist ?: playlist
    val sharePayload = displayPlaylist.toSharePayload()
    var showDeleteDialog by remember(displayPlaylist.id) { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(displayPlaylist.title.ifBlank { "歌单" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = owner::close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (owner.canDelete()) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除歌单")
                        }
                    }
                    val onShare = LocalShareHandler.current
                    IconButton(onClick = { sharePayload?.let(onShare) }, enabled = sharePayload != null) {
                        Icon(Icons.Filled.Share, contentDescription = "分享")
                    }
                },
            )
        },
        bottomBar = { if (graph.playbackQueue.currentQueueTrack != null) PlaybackMiniPlayer() },
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LoadingIndicator(state.isLoading)
            ProviderDetailHeader(
                track = displayPlaylist.toDisplayTrack(),
                title = displayPlaylist.title.ifBlank { "未命名歌单" },
                subtitle = buildList {
                    add(displayPlaylist.providerName)
                    displayPlaylist.playCount?.let { add(formatPlayCount(it)) }
                    displayPlaylist.trackCount?.let { add("$it 首") }
                }.joinToString(" · "),
                description = displayPlaylist.description,
                placeholder = CoverPlaceholder.Playlist,
                action = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.tracks.isNotEmpty()) PlayAllButton(onClick = owner::playAll)
                        ShareTextButton(sharePayload)
                    }
                },
            )
            state.errorMessage?.let { ProviderContentMessage(it) }
            ProviderDetailTrackList(
                tracks = state.tracks,
                emptyMessage = "歌单暂无歌曲",
                showEmpty = !state.isLoading && state.errorMessage == null,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onClick = owner::play,
                onItemVisible = owner::prefetchIfNeeded,
                canRemove = owner::canRemove,
                onRemove = owner::remove,
            )
        }
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除歌单？") },
            text = { Text("将删除《${displayPlaylist.title}》，此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    owner.delete()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderTrackDetailRoute(track: MusicTrack) {
    val graph = LocalProviderDetailUiGraph.current
    val owner = graph.owners.track
    val state by owner.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(track.id) { owner.activate(track) }
    val displayTrack = state.track ?: track
    val sharePayload = displayTrack.toSharePayload()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(displayTrack.title.ifBlank { "歌曲" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = owner::close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val onShare = LocalShareHandler.current
                    IconButton(onClick = { sharePayload?.let(onShare) }, enabled = sharePayload != null) {
                        Icon(Icons.Filled.Share, contentDescription = "分享")
                    }
                },
            )
        },
        bottomBar = { if (graph.playbackQueue.currentQueueTrack != null) PlaybackMiniPlayer() },
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LoadingIndicator(state.isLoading)
            ProviderDetailHeader(
                track = displayTrack,
                title = displayTrack.title.ifBlank { "未知歌曲" },
                subtitle = buildList {
                    if (displayTrack.artists.isNotBlank()) add(displayTrack.artists)
                    if (displayTrack.album.isNotBlank()) add("《${displayTrack.album}》")
                    add(displayTrack.providerName ?: displayTrack.source)
                }.joinToString(" · "),
                description = "",
                action = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = owner::play) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("播放")
                        }
                        if (state.video != null) {
                            TextButton(onClick = owner::openVideo) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("播放 MV")
                            }
                        }
                        if (graph.playlists.canAddTrackToPlaylist(displayTrack)) {
                            TextButton(onClick = { graph.playlists.openPlaylistTargetPicker(displayTrack) }) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null)
                                Spacer(Modifier.size(4.dp))
                                Text("添加到歌单")
                            }
                        }
                        ShareTextButton(sharePayload)
                    }
                },
            )
            state.errorMessage?.let { ProviderContentMessage(it) }
            state.relatedErrorMessage?.let { ProviderContentMessage(it) }
            if (state.similarTracks.isNotEmpty()) {
                Text("相似歌曲", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                state.similarTracks.take(6).forEachIndexed { index, related ->
                    ProviderDetailTrackRow(related, onClick = { owner.playSimilar(index) })
                    HorizontalDivider()
                }
            }
            if (state.comments.isNotEmpty()) {
                Text("热评", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                state.comments.take(5).forEach { comment ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(comment.userName.ifBlank { "匿名用户" }, color = MaterialTheme.colorScheme.primary)
                        Text(comment.content, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderMediaItemDetailRoute(item: ProviderMediaItem) {
    val graph = LocalProviderDetailUiGraph.current
    val owner = graph.owners.mediaItem
    val state by owner.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(item.id) { owner.activate(item) }
    val displayItem = state.item ?: item
    val isArtist = displayItem.type == ProviderMediaItemType.Artist
    val sharePayload = displayItem.toSharePayload()
    var selectedTabIndex by remember(displayItem.id) { mutableStateOf(0) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(displayItem.title.ifBlank { if (isArtist) "歌手" else "专辑" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = owner::close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val onShare = LocalShareHandler.current
                    IconButton(onClick = { sharePayload?.let(onShare) }, enabled = sharePayload != null) {
                        Icon(Icons.Filled.Share, contentDescription = "分享")
                    }
                },
            )
        },
        bottomBar = { if (graph.playbackQueue.currentQueueTrack != null) PlaybackMiniPlayer() },
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LoadingIndicator(state.isLoading)
            ProviderDetailHeader(
                track = displayItem.toDisplayTrack(),
                title = displayItem.title.ifBlank { if (isArtist) "未知歌手" else "未知专辑" },
                subtitle = buildList {
                    add(displayItem.providerName)
                    add(if (isArtist) "歌手" else "专辑")
                    displayItem.trackCount?.let { add("$it 首") }
                    if (isArtist) displayItem.albumCount?.let { add("$it 张专辑") }
                }.joinToString(" · "),
                description = displayItem.description,
                placeholder = if (isArtist) CoverPlaceholder.Artist else CoverPlaceholder.Album,
                action = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.tracks.isNotEmpty()) PlayAllButton(onClick = owner::playAll)
                        ShareTextButton(sharePayload)
                    }
                },
            )
            state.errorMessage?.let { ProviderContentMessage(it) }
            if (isArtist) {
                PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                    listOf("歌曲", "专辑").forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(title) },
                        )
                    }
                }
            }
            if (isArtist && selectedTabIndex == 1) {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (state.albums.isEmpty() && !state.isLoading && state.errorMessage == null) {
                        item { ProviderContentMessage("暂无专辑") }
                    } else {
                        item {
                            ProviderMediaItemGrid(
                                items = state.albums,
                                onClick = owner::open,
                                onItemVisible = owner::prefetchAlbumsIfNeeded,
                            )
                        }
                    }
                }
            } else {
                ProviderDetailTrackList(
                    tracks = state.tracks,
                    emptyMessage = "暂无歌曲",
                    showEmpty = !state.isLoading && state.errorMessage == null,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onClick = owner::play,
                    onItemVisible = owner::prefetchTracksIfNeeded,
                )
            }
        }
    }
}

@Composable
private fun ProviderDetailTrackList(
    tracks: List<MusicTrack>,
    emptyMessage: String,
    showEmpty: Boolean,
    modifier: Modifier,
    onClick: (Int) -> Unit,
    onItemVisible: ((Int) -> Unit)? = null,
    canRemove: (MusicTrack) -> Boolean = { false },
    onRemove: (MusicTrack) -> Unit = {},
) {
    if (LocalAppLayoutInfo.current.useWideLayout) {
        val indexedTracks = remember(tracks) { tracks.mapIndexed { index, track -> index to track } }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (tracks.isEmpty() && showEmpty) {
                item(span = { GridItemSpan(maxLineSpan) }) { ProviderContentMessage(emptyMessage) }
            } else {
                items(indexedTracks, key = { it.second.id }) { (index, track) ->
                    if (onItemVisible != null) LaunchedEffect(index, tracks.size) { onItemVisible(index) }
                    ProviderDetailTrackRow(
                        track = track,
                        onClick = { onClick(index) },
                        onRemove = if (canRemove(track)) ({ onRemove(track) }) else null,
                    )
                }
            }
        }
        return
    }
    LazyColumn(modifier = modifier) {
        if (tracks.isEmpty() && showEmpty) {
            item { ProviderContentMessage(emptyMessage) }
        } else {
            itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                if (onItemVisible != null) LaunchedEffect(index, tracks.size) { onItemVisible(index) }
                ProviderDetailTrackRow(
                    track = track,
                    onClick = { onClick(index) },
                    onRemove = if (canRemove(track)) ({ onRemove(track) }) else null,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ProviderDetailTrackRow(
    track: MusicTrack,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    val graph = LocalProviderDetailUiGraph.current
    TrackRow(
        track = track,
        downloadState = graph.downloads.downloadStates[track.id],
        onClick = onClick,
        onAddToUpNext = { graph.playbackQueue.addToUpNext(track) },
        onDownload = { graph.downloads.download(track) },
        onDeleteDownload = { graph.downloads.deleteDownload(track) },
        onOpenArtist = { graph.providerTrackActions.openTrackArtist(track) },
        onOpenAlbum = { graph.providerTrackActions.openTrackAlbum(track) },
        onOpenDetail = if (track.sourceType == TrackSourceType.Provider) {
            { graph.owners.track.open(track) }
        } else null,
        onAddToPlaylist = if (graph.playlists.canAddTrackToPlaylist(track)) {
            { graph.playlists.openPlaylistTargetPicker(track) }
        } else null,
        onRemoveFromProviderPlaylist = onRemove,
    )
}

private fun ProviderFeature.isBilibiliWeeklyFeature(): Boolean =
    providerId == "bilibili" && id.substringBefore('|').contains("weekly", ignoreCase = true)

private fun ProviderFeature.isDailySongsFeature(): Boolean =
    id.contains("daily", ignoreCase = true) && contentType == ProviderContentType.Songs
