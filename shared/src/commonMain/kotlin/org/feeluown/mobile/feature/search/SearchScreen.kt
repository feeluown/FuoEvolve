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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val MAX_SEARCH_HISTORY_ITEMS = 20

@Composable
fun SearchScreen(
    controller: FuoPlayerController,
    onOpenRecognition: () -> Unit,
) {
    val uiState by controller.searchUiState.collectAsStateWithLifecycle()
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
            controller.onQueryChange(keyword)
        }
        if (normalized.isNotBlank()) {
            recordSearch(normalized)
        }
        controller.search()
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
                            onQueryChange = controller::onQueryChange,
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
                            SearchScopeChip(controller, uiState, SearchScope.All, "全部")
                            SearchScopeChip(controller, uiState, SearchScope.Local, "本地")
                            controller.providers.forEach { provider ->
                                SearchProviderChip(controller, uiState, provider)
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
                        onClick = controller::closeSearch,
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
                    ProviderSearchTabs(controller, uiState)
                    SearchResultList(
                        controller = controller,
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
                            IconButton(onClick = controller::closeSearch) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                            FuoSearchField(
                                modifier = Modifier.weight(1f),
                                query = uiState.query,
                                onQueryChange = controller::onQueryChange,
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
                            SearchScopeChip(controller, uiState, SearchScope.All, "全部")
                            SearchScopeChip(controller, uiState, SearchScope.Local, "本地")
                            controller.providers.forEach { provider ->
                                SearchProviderChip(controller, uiState, provider)
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
                ProviderSearchTabs(controller, uiState)
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
                    searchResultItems(controller, uiState)
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
fun SearchResultList(
    controller: FuoPlayerController,
    uiState: SearchUiState,
    modifier: Modifier,
) {
    LazyColumn(modifier = modifier) {
        searchResultItems(controller, uiState, compactTop = true)
    }
}

@Composable
private fun ProviderSearchTabs(
    controller: FuoPlayerController,
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
                onClick = { controller.onProviderSearchTabChange(tab) },
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
    controller: FuoPlayerController,
    uiState: SearchUiState,
    compactTop: Boolean = false,
) {
    when (uiState.providerSearchTab.takeIf { uiState.searchScope != SearchScope.Local } ?: ProviderSearchTab.Songs) {
        ProviderSearchTab.Songs -> {
            if (uiState.searchResults.isEmpty()) {
                item { EmptySearchHint(uiState.query, compactTop = compactTop) }
            } else {
                itemsIndexed(uiState.searchResults, key = { _, item -> item.id }) { index, track ->
                    TrackRow(
                        track = track,
                        downloadState = controller.downloadStates[track.id],
                        onClick = { controller.playFromSearch(index) },
                        onAddToUpNext = { controller.addToUpNext(track) },
                        onDownload = { controller.download(track) },
                        onDeleteDownload = { controller.deleteDownload(track) },
                        onOpenArtist = { controller.openTrackArtist(track) },
                        onOpenAlbum = { controller.openTrackAlbum(track) },
                        onOpenDetail = trackDetailAction(controller, track),
                        onAddToPlaylist = addToPlaylistAction(controller, track),
                    )
                    HorizontalDivider()
                }
            }
        }
        ProviderSearchTab.Artists -> mediaItems(uiState.providerSearchResults.artists, "没有歌手结果", controller)
        ProviderSearchTab.Albums -> mediaItems(uiState.providerSearchResults.albums, "没有专辑结果", controller)
        ProviderSearchTab.Playlists -> playlists(uiState.providerSearchResults.playlists, controller)
        ProviderSearchTab.Videos -> videos(uiState.providerSearchResults.videos, controller)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.mediaItems(
    items: List<ProviderMediaItem>,
    emptyMessage: String,
    controller: FuoPlayerController,
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
                onClick = { controller.openMediaItem(item) },
            )
            HorizontalDivider()
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.playlists(
    playlists: List<ProviderPlaylist>,
    controller: FuoPlayerController,
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
                onClick = { controller.openPlaylist(playlist) },
            )
            HorizontalDivider()
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.videos(
    videos: List<ProviderVideo>,
    controller: FuoPlayerController,
) {
    if (videos.isEmpty()) {
        item { ProviderContentMessage("没有视频结果") }
    } else {
        itemsIndexed(videos, key = { _, item -> item.id }) { _, video ->
            ProviderSearchRow(
                title = video.title,
                subtitle = listOf(video.artists, video.providerName).filter { it.isNotBlank() }.joinToString(" · "),
                coverUrl = video.coverUrl,
                onClick = { controller.openVideo(video) },
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
    ProviderSearchTab.Songs -> "歌曲 ${uiState.searchResults.size}"
    ProviderSearchTab.Artists -> "歌手 ${uiState.providerSearchResults.artists.size}"
    ProviderSearchTab.Albums -> "专辑 ${uiState.providerSearchResults.albums.size}"
    ProviderSearchTab.Playlists -> "歌单 ${uiState.providerSearchResults.playlists.size}"
    ProviderSearchTab.Videos -> "视频 ${uiState.providerSearchResults.videos.size}"
}

@Composable
fun EmptySearchHint(query: String, compactTop: Boolean = false) {
    FuoEmptyState(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (compactTop) 0.dp else 24.dp),
        title = if (query.isBlank()) "输入关键词查找音乐" else "没有结果",
    )
}

@Composable
fun SearchScopeChip(
    controller: FuoPlayerController,
    uiState: SearchUiState,
    scope: SearchScope,
    label: String,
) {
    FilterChip(
        selected = uiState.searchScope == scope && scope != SearchScope.Provider,
        onClick = { controller.onSearchScopeChange(scope) },
        label = { Text(label) },
    )
}

@Composable
fun SearchProviderChip(
    controller: FuoPlayerController,
    uiState: SearchUiState,
    provider: ProviderInfo,
) {
    FilterChip(
        selected = uiState.searchScope == SearchScope.Provider &&
            uiState.selectedSearchProviderId == provider.providerId,
        onClick = { controller.onSearchProviderChange(provider.providerId) },
        label = { Text(provider.providerName) },
    )
}
