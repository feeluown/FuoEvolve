package org.feeluown.mobile

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun SearchScreen(controller: FuoPlayerController) {
    if (LocalAppLayoutInfo.current.useWideLayout) {
        Scaffold { paddingValues ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 48.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = controller.query,
                            onValueChange = controller::onQueryChange,
                            singleLine = true,
                            placeholder = { Text("歌曲、歌手或专辑") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { controller.search() }),
                        )
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !controller.isLoading,
                            onClick = controller::search,
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索")
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SearchScopeChip(controller, SearchScope.All, "全部")
                            SearchScopeChip(controller, SearchScope.Local, "本地")
                            controller.providers.forEach { provider ->
                                SearchProviderChip(controller, provider)
                            }
                        }
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
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LoadingIndicator(controller.isLoading)
                    ProviderSearchTabs(controller)
                    SearchResultList(
                        controller = controller,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
            }
        }
        return
    }
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
            ProviderSearchTabs(controller)
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                searchResultItems(controller)
            }
        }
    }
}

@Composable
fun SearchResultList(controller: FuoPlayerController, modifier: Modifier) {
    LazyColumn(modifier = modifier) {
        searchResultItems(controller, compactTop = true)
    }
}

@Composable
private fun ProviderSearchTabs(controller: FuoPlayerController) {
    if (controller.searchScope == SearchScope.Local) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProviderSearchTab.entries.forEach { tab ->
            FilterChip(
                selected = controller.providerSearchTab == tab,
                onClick = { controller.onProviderSearchTabChange(tab) },
                label = { Text(tab.label(controller)) },
            )
        }
    }
    controller.providerSearchResults.errorMessage?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.searchResultItems(
    controller: FuoPlayerController,
    compactTop: Boolean = false,
) {
    when (controller.providerSearchTab.takeIf { controller.searchScope != SearchScope.Local } ?: ProviderSearchTab.Songs) {
        ProviderSearchTab.Songs -> {
            if (controller.searchResults.isEmpty()) {
                item { EmptySearchHint(controller.query, compactTop = compactTop) }
            } else {
                itemsIndexed(controller.searchResults, key = { _, item -> item.id }) { index, track ->
                    TrackRow(
                        track = track,
                        downloadState = controller.downloadStates[track.id],
                        onClick = { controller.playFromSearch(index) },
                        onAddToUpNext = { controller.addToUpNext(track) },
                        onDownload = { controller.download(track) },
                        onDeleteDownload = { controller.deleteDownload(track) },
                        onOpenArtist = { controller.openTrackArtist(track) },
                        onOpenAlbum = { controller.openTrackAlbum(track) },
                        onAddToProviderPlaylist = addToProviderPlaylistAction(controller, track),
                    )
                    HorizontalDivider()
                }
            }
        }
        ProviderSearchTab.Artists -> mediaItems(controller.providerSearchResults.artists, "没有歌手结果", controller)
        ProviderSearchTab.Albums -> mediaItems(controller.providerSearchResults.albums, "没有专辑结果", controller)
        ProviderSearchTab.Playlists -> playlists(controller.providerSearchResults.playlists, controller)
        ProviderSearchTab.Videos -> videos(controller.providerSearchResults.videos, controller)
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
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { "未命名" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

private fun ProviderSearchTab.label(controller: FuoPlayerController): String = when (this) {
    ProviderSearchTab.Songs -> "歌曲 ${controller.searchResults.size}"
    ProviderSearchTab.Artists -> "歌手 ${controller.providerSearchResults.artists.size}"
    ProviderSearchTab.Albums -> "专辑 ${controller.providerSearchResults.albums.size}"
    ProviderSearchTab.Playlists -> "歌单 ${controller.providerSearchResults.playlists.size}"
    ProviderSearchTab.Videos -> "视频 ${controller.providerSearchResults.videos.size}"
}

@Composable
fun EmptySearchHint(query: String, compactTop: Boolean = false) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (compactTop) 0.dp else 24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            modifier = Modifier.padding(16.dp),
            text = if (query.isBlank()) "输入关键词查找音乐" else "没有结果",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SearchScopeChip(controller: FuoPlayerController, scope: SearchScope, label: String) {
    FilterChip(
        selected = controller.searchScope == scope && scope != SearchScope.Provider,
        onClick = { controller.onSearchScopeChange(scope) },
        label = { Text(label) },
    )
}

@Composable
fun SearchProviderChip(controller: FuoPlayerController, provider: ProviderInfo) {
    FilterChip(
        selected = controller.searchScope == SearchScope.Provider &&
            controller.selectedSearchProviderId == provider.providerId,
        onClick = { controller.onSearchProviderChange(provider.providerId) },
        label = { Text(provider.providerName) },
    )
}
