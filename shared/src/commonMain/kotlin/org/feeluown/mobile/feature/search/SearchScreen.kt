package org.feeluown.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val MAX_SEARCH_HISTORY_ITEMS = 20
private const val COMPREHENSIVE_SECTION_LIMIT = 6

/**
 * Search feature UI contract.
 *
 * The screen owns no application-level controller dependency: search-specific actions are
 * dispatched through [SearchAction], while cross-feature operations are represented as narrow
 * callbacks. App-shell composition adapts cross-feature operations without exposing
 * [FuoPlayerController] as the feature state/action contract.
 */
internal data class SearchFeatureActions(
    val dispatch: (SearchAction) -> Unit,
    val onBack: () -> Unit,
    val onPlayResult: (Int) -> Unit,
    val onAddToUpNext: (MusicTrack) -> Unit,
    val onDownload: (MusicTrack) -> Unit,
    val onDeleteDownload: (MusicTrack) -> Unit,
    val onOpenArtist: (MusicTrack) -> Unit,
    val onOpenAlbum: (MusicTrack) -> Unit,
    val onOpenTrackDetail: (MusicTrack) -> (() -> Unit)?,
    val onAddToPlaylist: (MusicTrack) -> (() -> Unit)?,
    val onOpenMediaItem: (ProviderMediaItem) -> Unit,
    val onOpenPlaylist: (ProviderPlaylist) -> Unit,
    val onOpenVideo: (ProviderVideo) -> Unit,
)

@Composable
internal fun SearchFeatureScreen(
    uiState: SearchUiState,
    providers: List<ProviderInfo>,
    downloadStates: Map<String, DownloadState>,
    actions: SearchFeatureActions,
    onOpenRecognition: () -> Unit,
) {
    val searchHistoryStore = rememberSearchHistoryStore()
    var searchHistory by remember(searchHistoryStore) {
        mutableStateOf(searchHistoryStore.load().take(MAX_SEARCH_HISTORY_ITEMS))
    }
    var pendingHistoryDeletion by remember { mutableStateOf<String?>(null) }

    fun persistSearchHistory(updated: List<String>) {
        searchHistory = updated
        searchHistoryStore.save(updated)
    }

    fun recordSearch(keyword: String) {
        val normalized = keyword.trim()
        if (normalized.isBlank()) return
        persistSearchHistory(
            listOf(normalized)
                .plus(searchHistory.filterNot { it.equals(normalized, ignoreCase = true) })
                .take(MAX_SEARCH_HISTORY_ITEMS),
        )
    }

    fun performSearch(keyword: String? = null) {
        val requestedKeyword = keyword ?: uiState.query
        val normalized = requestedKeyword.trim()
        if (keyword != null) {
            actions.dispatch(SearchAction.QueryChanged(keyword))
        }
        if (normalized.isNotBlank()) {
            recordSearch(normalized)
        }
        actions.dispatch(SearchAction.Submit)
    }

    if (LocalAppLayoutInfo.current.useWideLayout) {
        Scaffold { paddingValues ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = FuoSpacing.xl, vertical = FuoSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(FuoSpacing.xl),
            ) {
                Box(
                    modifier = Modifier
                        .weight(0.34f)
                        .widthIn(min = 280.dp, max = 360.dp)
                        .fillMaxHeight(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = FuoSpacing.xxl)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
                    ) {
                        FuoSearchField(
                            modifier = Modifier.fillMaxWidth(),
                            query = uiState.query,
                            onQueryChange = { actions.dispatch(SearchAction.QueryChanged(it)) },
                            onSearch = { performSearch() },
                            enabled = !uiState.isLoading,
                            placeholder = "歌曲、歌手或专辑",
                            trailingContent = {
                                FuoIconButton(
                                    contentDescription = "搜索",
                                    enabled = !uiState.isLoading,
                                    onClick = { performSearch() },
                                ) {
                                    Icon(Icons.Filled.Search, contentDescription = null)
                                }
                            },
                        )
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onOpenRecognition,
                        ) {
                            Icon(Icons.Filled.Mic, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("听歌识曲")
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SearchScopeChip(actions, uiState, SearchScope.All, "全部")
                            SearchScopeChip(actions, uiState, SearchScope.Local, "本地")
                            providers.forEach { provider ->
                                SearchProviderChip(actions, uiState, provider)
                            }
                        }
                        SearchHistoryStrip(
                            history = searchHistory,
                            onSearch = { performSearch(it) },
                            onLongPress = { pendingHistoryDeletion = it },
                        )
                    }
                    TextButton(
                        modifier = Modifier.align(Alignment.BottomStart),
                        onClick = actions.onBack,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(Modifier.size(4.dp))
                        Text("返回")
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(FuoSpacing.md),
                ) {
                    LoadingIndicator(uiState.isLoading)
                    ProviderSearchTabs(actions, uiState)
                    SearchResultList(
                        actions = actions,
                        downloadStates = downloadStates,
                        uiState = uiState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    } else {
        Scaffold(
            topBar = {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = actions.onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                            FuoSearchField(
                                modifier = Modifier.weight(1f),
                                query = uiState.query,
                                onQueryChange = { actions.dispatch(SearchAction.QueryChanged(it)) },
                                onSearch = { performSearch() },
                                enabled = !uiState.isLoading,
                                placeholder = "歌曲、歌手或专辑",
                                trailingContent = {
                                    Row {
                                        FuoIconButton(
                                            contentDescription = "听歌识曲",
                                            onClick = onOpenRecognition,
                                        ) {
                                            Icon(Icons.Filled.Mic, contentDescription = null)
                                        }
                                        FuoIconButton(
                                            contentDescription = "搜索",
                                            enabled = !uiState.isLoading,
                                            onClick = { performSearch() },
                                        ) {
                                            Icon(Icons.Filled.Search, contentDescription = null)
                                        }
                                    }
                                },
                            )
                        }
                        Row(
                            modifier = Modifier
                                .padding(start = 56.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SearchScopeChip(actions, uiState, SearchScope.All, "全部")
                            SearchScopeChip(actions, uiState, SearchScope.Local, "本地")
                            providers.forEach { provider ->
                                SearchProviderChip(actions, uiState, provider)
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
                    .padding(horizontal = FuoSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(FuoSpacing.md),
            ) {
                LoadingIndicator(uiState.isLoading)
                ProviderSearchTabs(actions, uiState)
                SearchHistoryStrip(
                    history = searchHistory,
                    onSearch = { performSearch(it) },
                    onLongPress = { pendingHistoryDeletion = it },
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    searchResultItems(actions, downloadStates, uiState)
                }
            }
        }
    }

    pendingHistoryDeletion?.let { keyword ->
        AlertDialog(
            onDismissRequest = { pendingHistoryDeletion = null },
            title = { Text("删除搜索历史") },
            text = { Text("确定删除“$keyword”吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        persistSearchHistory(searchHistory.filterNot { it == keyword })
                        pendingHistoryDeletion = null
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingHistoryDeletion = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun SearchHistoryStrip(
    history: List<String>,
    onSearch: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    if (history.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FuoSpacing.xs),
    ) {
        Text(
            text = "搜索历史",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            history.forEach { keyword ->
                SearchHistoryChip(
                    keyword = keyword,
                    onClick = { onSearch(keyword) },
                    onLongPress = { onLongPress(keyword) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchHistoryChip(
    keyword: String,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Surface(
        modifier = Modifier.combinedClickable(
            role = Role.Button,
            onClick = onClick,
            onLongClick = onLongPress,
        ),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = keyword,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchResultList(
    actions: SearchFeatureActions,
    downloadStates: Map<String, DownloadState>,
    uiState: SearchUiState,
    modifier: Modifier,
) {
    LazyColumn(modifier = modifier) {
        searchResultItems(actions, downloadStates, uiState, compactTop = true)
    }
}

@Composable
private fun ProviderSearchTabs(
    actions: SearchFeatureActions,
    uiState: SearchUiState,
) {
    if (uiState.searchScope == SearchScope.Local) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProviderSearchTab.entries.forEach { tab ->
            FilterChip(
                selected = uiState.providerSearchTab == tab,
                onClick = { actions.dispatch(SearchAction.ProviderTabChanged(tab)) },
                label = { Text(tab.label(uiState)) },
            )
        }
    }
    uiState.providerSearchResults.errorMessage?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.searchResultItems(
    actions: SearchFeatureActions,
    downloadStates: Map<String, DownloadState>,
    uiState: SearchUiState,
    compactTop: Boolean = false,
) {
    when (uiState.providerSearchTab.takeIf { uiState.searchScope != SearchScope.Local } ?: ProviderSearchTab.Songs) {
        ProviderSearchTab.Comprehensive -> comprehensiveItems(actions, downloadStates, uiState, compactTop)
        ProviderSearchTab.Songs -> songs(uiState.searchResults, actions, downloadStates, uiState.query, compactTop)
        ProviderSearchTab.Artists -> mediaItems(
            uiState.providerSearchResults.artists,
            "没有歌手结果",
            actions,
        )
        ProviderSearchTab.Albums -> mediaItems(
            uiState.providerSearchResults.albums,
            "没有专辑结果",
            actions,
        )
        ProviderSearchTab.Playlists -> playlists(uiState.providerSearchResults.playlists, actions)
        ProviderSearchTab.Videos -> videos(uiState.providerSearchResults.videos, actions)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.comprehensiveItems(
    actions: SearchFeatureActions,
    downloadStates: Map<String, DownloadState>,
    uiState: SearchUiState,
    compactTop: Boolean,
) {
    val providerResults = uiState.providerSearchResults
    val isEmpty = uiState.searchResults.isEmpty() &&
        providerResults.artists.isEmpty() &&
        providerResults.albums.isEmpty() &&
        providerResults.playlists.isEmpty() &&
        providerResults.videos.isEmpty()
    if (isEmpty) {
        item { EmptySearchHint(uiState.query, compactTop = compactTop) }
        return
    }

    if (providerResults.bestMatches.isNotEmpty()) {
        sectionTitle("最佳结果")
        itemsIndexed(
            providerResults.bestMatches,
            key = { _, hit -> "best:${bestMatchKey(hit)}" },
        ) { _, hit ->
            bestMatchRow(hit, uiState, actions, downloadStates)
            HorizontalDivider()
        }
    }

    val tracks = uiState.searchResults.take(COMPREHENSIVE_SECTION_LIMIT)
    if (tracks.isNotEmpty()) {
        sectionTitle("歌曲")
        itemsIndexed(tracks, key = { _, track -> "comprehensive-track:${track.id}" }) { _, track ->
            val index = uiState.searchResults.indexOfFirst { it.id == track.id }
            TrackRow(
                track = track,
                downloadState = downloadStates[track.id],
                onClick = { if (index >= 0) actions.onPlayResult(index) },
                onAddToUpNext = { actions.onAddToUpNext(track) },
                onDownload = { actions.onDownload(track) },
                onDeleteDownload = { actions.onDeleteDownload(track) },
                onOpenArtist = { actions.onOpenArtist(track) },
                onOpenAlbum = { actions.onOpenAlbum(track) },
                onOpenDetail = actions.onOpenTrackDetail(track),
                onAddToPlaylist = actions.onAddToPlaylist(track),
            )
            HorizontalDivider()
        }
    }

    comprehensiveMediaSection(
        title = "歌手",
        items = providerResults.artists,
        type = ProviderMediaItemType.Artist,
        actions = actions,
    )
    comprehensiveMediaSection(
        title = "专辑",
        items = providerResults.albums,
        type = ProviderMediaItemType.Album,
        actions = actions,
    )

    val playlistItems = providerResults.playlists.take(COMPREHENSIVE_SECTION_LIMIT)
    if (playlistItems.isNotEmpty()) {
        sectionTitle("歌单")
        itemsIndexed(playlistItems, key = { _, item -> "comprehensive-playlist:${item.id}" }) { _, playlist ->
            ProviderSearchRow(
                title = playlist.title,
                subtitle = playlist.providerName,
                coverUrl = playlist.coverUrl,
                placeholder = CoverPlaceholder.Playlist,
                onClick = { actions.onOpenPlaylist(playlist) },
            )
            HorizontalDivider()
        }
    }

    val videoItems = providerResults.videos.take(COMPREHENSIVE_SECTION_LIMIT)
    if (videoItems.isNotEmpty()) {
        sectionTitle("视频")
        itemsIndexed(videoItems, key = { _, item -> "comprehensive-video:${item.id}" }) { _, video ->
            ProviderSearchRow(
                title = video.title,
                subtitle = listOf(video.artists, video.providerName).filter { it.isNotBlank() }.joinToString(" · "),
                coverUrl = video.coverUrl,
                onClick = { actions.onOpenVideo(video) },
            )
            HorizontalDivider()
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.comprehensiveMediaSection(
    title: String,
    items: List<ProviderMediaItem>,
    type: ProviderMediaItemType,
    actions: SearchFeatureActions,
) {
    val preview = items.take(COMPREHENSIVE_SECTION_LIMIT)
    if (preview.isEmpty()) return
    sectionTitle(title)
    itemsIndexed(preview, key = { _, item -> "comprehensive-${type.name.lowercase()}:${item.id}" }) { _, item ->
        ProviderSearchRow(
            title = item.title,
            subtitle = item.providerName,
            coverUrl = item.coverUrl,
            placeholder = when (type) {
                ProviderMediaItemType.Artist -> CoverPlaceholder.Artist
                ProviderMediaItemType.Album -> CoverPlaceholder.Album
            },
            onClick = { actions.onOpenMediaItem(item) },
        )
        HorizontalDivider()
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sectionTitle(title: String) {
    item(key = "section:$title") {
        Text(
            text = title,
            modifier = Modifier.padding(top = FuoSpacing.md, bottom = FuoSpacing.xs),
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun bestMatchRow(
    hit: ProviderSearchHit,
    uiState: SearchUiState,
    actions: SearchFeatureActions,
    downloadStates: Map<String, DownloadState>,
) {
    when (hit) {
        is ProviderSearchHit.Track -> {
            val track = hit.value
            val index = uiState.searchResults.indexOfFirst { it.id == track.id }
            TrackRow(
                track = track,
                downloadState = downloadStates[track.id],
                onClick = { if (index >= 0) actions.onPlayResult(index) },
                onAddToUpNext = { actions.onAddToUpNext(track) },
                onDownload = { actions.onDownload(track) },
                onDeleteDownload = { actions.onDeleteDownload(track) },
                onOpenArtist = { actions.onOpenArtist(track) },
                onOpenAlbum = { actions.onOpenAlbum(track) },
                onOpenDetail = actions.onOpenTrackDetail(track),
                onAddToPlaylist = actions.onAddToPlaylist(track),
            )
        }
        is ProviderSearchHit.Artist -> ProviderSearchRow(
            title = hit.value.title,
            subtitle = "歌手 · ${hit.value.providerName}",
            coverUrl = hit.value.coverUrl,
            placeholder = CoverPlaceholder.Artist,
            onClick = { actions.onOpenMediaItem(hit.value) },
        )
        is ProviderSearchHit.Album -> ProviderSearchRow(
            title = hit.value.title,
            subtitle = "专辑 · ${hit.value.providerName}",
            coverUrl = hit.value.coverUrl,
            placeholder = CoverPlaceholder.Album,
            onClick = { actions.onOpenMediaItem(hit.value) },
        )
        is ProviderSearchHit.Playlist -> ProviderSearchRow(
            title = hit.value.title,
            subtitle = "歌单 · ${hit.value.providerName}",
            coverUrl = hit.value.coverUrl,
            placeholder = CoverPlaceholder.Playlist,
            onClick = { actions.onOpenPlaylist(hit.value) },
        )
        is ProviderSearchHit.Video -> ProviderSearchRow(
            title = hit.value.title,
            subtitle = listOf("视频", hit.value.artists, hit.value.providerName)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            coverUrl = hit.value.coverUrl,
            onClick = { actions.onOpenVideo(hit.value) },
        )
    }
}

private fun bestMatchKey(hit: ProviderSearchHit): String = when (hit) {
    is ProviderSearchHit.Track -> "track:${hit.value.id}"
    is ProviderSearchHit.Artist -> "artist:${hit.value.id}"
    is ProviderSearchHit.Album -> "album:${hit.value.id}"
    is ProviderSearchHit.Playlist -> "playlist:${hit.value.id}"
    is ProviderSearchHit.Video -> "video:${hit.value.id}"
}

private fun androidx.compose.foundation.lazy.LazyListScope.songs(
    tracks: List<MusicTrack>,
    actions: SearchFeatureActions,
    downloadStates: Map<String, DownloadState>,
    query: String,
    compactTop: Boolean,
) {
    if (tracks.isEmpty()) {
        item { EmptySearchHint(query, compactTop = compactTop) }
    } else {
        itemsIndexed(tracks, key = { _, item -> item.id }) { index, track ->
            TrackRow(
                track = track,
                downloadState = downloadStates[track.id],
                onClick = { actions.onPlayResult(index) },
                onAddToUpNext = { actions.onAddToUpNext(track) },
                onDownload = { actions.onDownload(track) },
                onDeleteDownload = { actions.onDeleteDownload(track) },
                onOpenArtist = { actions.onOpenArtist(track) },
                onOpenAlbum = { actions.onOpenAlbum(track) },
                onOpenDetail = actions.onOpenTrackDetail(track),
                onAddToPlaylist = actions.onAddToPlaylist(track),
            )
            HorizontalDivider()
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.mediaItems(
    items: List<ProviderMediaItem>,
    emptyMessage: String,
    actions: SearchFeatureActions,
) {
    if (items.isEmpty()) {
        item { ProviderContentMessage(emptyMessage) }
    } else {
        itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
            ProviderSearchRow(
                title = item.title,
                subtitle = listOf(item.type.name, item.providerName).joinToString(" · "),
                coverUrl = item.coverUrl,
                placeholder = when (item.type) {
                    ProviderMediaItemType.Artist -> CoverPlaceholder.Artist
                    ProviderMediaItemType.Album -> CoverPlaceholder.Album
                },
                onClick = { actions.onOpenMediaItem(item) },
            )
            HorizontalDivider()
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.playlists(
    playlists: List<ProviderPlaylist>,
    actions: SearchFeatureActions,
) {
    if (playlists.isEmpty()) {
        item { ProviderContentMessage("没有歌单结果") }
    } else {
        itemsIndexed(playlists, key = { _, item -> item.id }) { _, playlist ->
            ProviderSearchRow(
                title = playlist.title,
                subtitle = playlist.providerName,
                coverUrl = playlist.coverUrl,
                placeholder = CoverPlaceholder.Playlist,
                onClick = { actions.onOpenPlaylist(playlist) },
            )
            HorizontalDivider()
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.videos(
    videos: List<ProviderVideo>,
    actions: SearchFeatureActions,
) {
    if (videos.isEmpty()) {
        item { ProviderContentMessage("没有视频结果") }
    } else {
        itemsIndexed(videos, key = { _, item -> item.id }) { _, video ->
            ProviderSearchRow(
                title = video.title,
                subtitle = listOf(video.artists, video.providerName).filter { it.isNotBlank() }.joinToString(" · "),
                coverUrl = video.coverUrl,
                onClick = { actions.onOpenVideo(video) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ProviderSearchRow(
    title: String,
    subtitle: String,
    coverUrl: String?,
    placeholder: CoverPlaceholder = CoverPlaceholder.Song,
    onClick: () -> Unit,
) {
    FuoListItem(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        leadingContent = {
            CoverBox(
                track = MusicTrack(
                    id = title,
                    title = title,
                    artists = "",
                    album = "",
                    source = "",
                    sourceType = TrackSourceType.Provider,
                    coverUrl = coverUrl,
                ),
                modifier = Modifier.size(48.dp),
                placeholder = placeholder,
            )
        },
        headlineContent = {
            Text(
                text = title.ifBlank { "未命名" },
                maxLines = 1,
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        },
    )
}

private fun ProviderSearchTab.label(uiState: SearchUiState): String = when (this) {
    ProviderSearchTab.Comprehensive -> "综合"
    ProviderSearchTab.Songs -> "歌曲 ${uiState.searchResults.size}"
    ProviderSearchTab.Artists -> "歌手 ${uiState.providerSearchResults.artists.size}"
    ProviderSearchTab.Albums -> "专辑 ${uiState.providerSearchResults.albums.size}"
    ProviderSearchTab.Playlists -> "歌单 ${uiState.providerSearchResults.playlists.size}"
    ProviderSearchTab.Videos -> "视频 ${uiState.providerSearchResults.videos.size}"
}

@Composable
private fun EmptySearchHint(query: String, compactTop: Boolean = false) {
    FuoEmptyState(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (compactTop) 0.dp else 24.dp),
        title = if (query.isBlank()) "输入关键词查找音乐" else "没有结果",
    )
}

@Composable
private fun SearchScopeChip(
    actions: SearchFeatureActions,
    uiState: SearchUiState,
    scope: SearchScope,
    label: String,
) {
    FilterChip(
        selected = uiState.searchScope == scope && scope != SearchScope.Provider,
        onClick = { actions.dispatch(SearchAction.ScopeChanged(scope)) },
        label = { Text(label) },
    )
}

@Composable
private fun SearchProviderChip(
    actions: SearchFeatureActions,
    uiState: SearchUiState,
    provider: ProviderInfo,
) {
    FilterChip(
        selected = uiState.searchScope == SearchScope.Provider &&
            uiState.selectedSearchProviderId == provider.providerId,
        onClick = { actions.dispatch(SearchAction.ProviderChanged(provider.providerId)) },
        label = { Text(provider.providerName) },
    )
}