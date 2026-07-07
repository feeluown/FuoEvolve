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
        }
    }
}

@Composable
fun SearchResultList(controller: FuoPlayerController, modifier: Modifier) {
    LazyColumn(modifier = modifier) {
        if (controller.searchResults.isEmpty()) {
            item {
                EmptySearchHint(controller.query, compactTop = true)
            }
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
