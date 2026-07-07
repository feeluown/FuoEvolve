package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderFeatureScreen(controller: FuoPlayerController, feature: ProviderFeature?) {
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
        if (LocalAppLayoutInfo.current.useWideLayout) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .width(280.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CoverBox(
                        track = feature.toDisplayTrack(),
                        modifier = Modifier.size(160.dp),
                    )
                    Text(
                        text = feature.title.ifBlank { "推荐" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOf(feature.providerName, "${controller.selectedFeatureTracks.size} 首")
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (controller.selectedFeatureTracks.isNotEmpty()) {
                        PlayAllButton(onClick = controller::playAllFromSelectedFeature)
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LoadingIndicator(controller.isLoading)
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
                    SelectedFeatureTrackList(
                        controller = controller,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
            }
            return@Scaffold
        }
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
                        LaunchedEffect(index, controller.selectedFeatureTracks.size) {
                            controller.prefetchSelectedFeatureIfNeeded(index)
                        }
                        TrackRow(
                            track = track,
                            downloadState = controller.downloadStates[track.id],
                            onClick = { controller.playFromSelectedFeature(index) },
                            onAddToUpNext = { controller.addToUpNext(track) },
                            onDownload = { controller.download(track) },
                            onDeleteDownload = { controller.deleteDownload(track) },
                            onOpenArtist = { controller.openTrackArtist(track) },
                            onOpenAlbum = { controller.openTrackAlbum(track) },
                            onOpenDetail = trackDetailAction(controller, track),
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
fun SelectedFeatureTrackList(controller: FuoPlayerController, modifier: Modifier) {
    TrackCollectionList(
        controller = controller,
        tracks = controller.selectedFeatureTracks,
        emptyMessage = "暂无歌曲",
        showEmpty = !controller.isLoading && controller.selectedFeatureError == null,
        modifier = modifier,
        onClick = controller::playFromSelectedFeature,
        onItemVisible = controller::prefetchSelectedFeatureIfNeeded,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderTrackScreen(controller: FuoPlayerController, track: MusicTrack?) {
    val displayTrack = controller.selectedTrack ?: track ?: return
    val sharePayload = displayTrack.toSharePayload()
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = displayTrack.title.ifBlank { "歌曲" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = controller::closeTrack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val onShare = LocalShareHandler.current
                    IconButton(
                        onClick = { if (sharePayload != null) onShare(sharePayload) },
                        enabled = sharePayload != null,
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "分享")
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
        if (LocalAppLayoutInfo.current.useWideLayout) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                CoverBox(
                    track = displayTrack,
                    modifier = Modifier.size(220.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LoadingIndicator(controller.isLoading)
                    Text(
                        text = displayTrack.title.ifBlank { "未知歌曲" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildList {
                            if (displayTrack.artists.isNotBlank()) add(displayTrack.artists)
                            if (displayTrack.album.isNotBlank()) add("《${displayTrack.album}》")
                            add(displayTrack.providerName ?: displayTrack.source)
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = controller::playSelectedTrack) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text("播放")
                        }
                        if (controller.selectedTrackVideo != null) {
                            TextButton(onClick = controller::openSelectedTrackVideo) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(4.dp))
                                Text("播放 MV")
                            }
                        }
                        ShareTextButton(sharePayload)
                    }
                    if (controller.selectedTrackError != null) {
                        ProviderContentMessage(controller.selectedTrackError.orEmpty())
                    }
                    TrackRelatedContent(controller)
                }
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LoadingIndicator(controller.isLoading)
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
                        TextButton(onClick = controller::playSelectedTrack) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text("播放")
                        }
                        if (controller.selectedTrackVideo != null) {
                            TextButton(onClick = controller::openSelectedTrackVideo) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(4.dp))
                                Text("播放 MV")
                            }
                        }
                        ShareTextButton(sharePayload)
                    }
                },
            )
            if (controller.selectedTrackError != null) {
                ProviderContentMessage(controller.selectedTrackError.orEmpty())
            }
            TrackRelatedContent(controller)
        }
    }
}

@Composable
private fun TrackRelatedContent(controller: FuoPlayerController) {
    controller.selectedTrackRelatedError?.let {
        ProviderContentMessage(it)
    }
    if (controller.selectedTrackSimilar.isNotEmpty()) {
        Text(
            text = "相似歌曲",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        controller.selectedTrackSimilar.take(6).forEachIndexed { index, track ->
            TrackRow(
                track = track,
                downloadState = controller.downloadStates[track.id],
                onClick = { controller.playSelectedTrackSimilar(index) },
                onAddToUpNext = { controller.addToUpNext(track) },
                onDownload = { controller.download(track) },
                onDeleteDownload = { controller.deleteDownload(track) },
                onOpenArtist = { controller.openTrackArtist(track) },
                onOpenAlbum = { controller.openTrackAlbum(track) },
                onOpenDetail = trackDetailAction(controller, track),
                onAddToProviderPlaylist = addToProviderPlaylistAction(controller, track),
            )
            HorizontalDivider()
        }
    }
    if (controller.selectedTrackComments.isNotEmpty()) {
        Text(
            text = "热评",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        controller.selectedTrackComments.take(5).forEach { comment ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = comment.userName.ifBlank { "匿名用户" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = comment.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderVideoScreen(controller: FuoPlayerController, video: ProviderVideo?) {
    val displayVideo = controller.selectedVideo ?: video ?: return
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = displayVideo.title.ifBlank { "视频" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = controller::closeVideo) {
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlatformVideoPlayer(
                payload = controller.selectedVideoPayload,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )
            Text(
                text = displayVideo.title.ifBlank { "未命名视频" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(displayVideo.artists, displayVideo.providerName, controller.selectedVideoPayload?.quality.orEmpty())
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (controller.isLoading && controller.selectedVideoPayload == null) {
                LoadingIndicator(true)
            }
            controller.selectedVideoError?.let {
                ProviderContentMessage(it)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderPlaylistScreen(controller: FuoPlayerController, playlist: ProviderPlaylist?) {
    val displayPlaylist = controller.selectedPlaylist ?: playlist ?: return
    val sharePayload = displayPlaylist.toSharePayload()
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = displayPlaylist.title.ifBlank { "歌单" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = controller::closePlaylist) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val onShare = LocalShareHandler.current
                    IconButton(
                        onClick = { if (sharePayload != null) onShare(sharePayload) },
                        enabled = sharePayload != null,
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "分享")
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
        if (LocalAppLayoutInfo.current.useWideLayout) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .width(300.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CoverBox(
                        track = displayPlaylist.toDisplayTrack(),
                        modifier = Modifier.size(168.dp),
                    )
                    Text(
                        text = displayPlaylist.title.ifBlank { "未命名歌单" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildList {
                            add(displayPlaylist.providerName)
                            displayPlaylist.playCount?.let { add(formatPlayCount(it)) }
                            displayPlaylist.trackCount?.let { add("$it 首") }
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (displayPlaylist.description.isNotBlank()) {
                        Text(
                            text = displayPlaylist.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (controller.selectedPlaylistTracks.isNotEmpty()) {
                            PlayAllButton(onClick = controller::playAllFromSelectedPlaylist)
                        }
                        ShareTextButton(sharePayload)
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LoadingIndicator(controller.isLoading)
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
                    SelectedPlaylistTrackList(
                        controller = controller,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LoadingIndicator(controller.isLoading)
            ProviderDetailHeader(
                track = displayPlaylist.toDisplayTrack(),
                title = displayPlaylist.title.ifBlank { "未命名歌单" },
                subtitle = buildList {
                    add(displayPlaylist.providerName)
                    displayPlaylist.playCount?.let { add(formatPlayCount(it)) }
                    displayPlaylist.trackCount?.let { add("$it 首") }
                }.joinToString(" · "),
                description = displayPlaylist.description,
                action = if (controller.selectedPlaylistTracks.isNotEmpty()) {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PlayAllButton(onClick = controller::playAllFromSelectedPlaylist)
                            ShareTextButton(sharePayload)
                        }
                    }
                } else {
                    {
                        ShareTextButton(sharePayload)
                    }
                },
            )
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
                        LaunchedEffect(index, controller.selectedPlaylistTracks.size) {
                            controller.prefetchSelectedPlaylistIfNeeded(index)
                        }
                        TrackRow(
                            track = track,
                            downloadState = controller.downloadStates[track.id],
                            onClick = { controller.playFromSelectedPlaylist(index) },
                            onAddToUpNext = { controller.addToUpNext(track) },
                            onDownload = { controller.download(track) },
                            onDeleteDownload = { controller.deleteDownload(track) },
                            onOpenArtist = { controller.openTrackArtist(track) },
                            onOpenAlbum = { controller.openTrackAlbum(track) },
                            onOpenDetail = trackDetailAction(controller, track),
                            onAddToProviderPlaylist = addToProviderPlaylistAction(controller, track),
                            onRemoveFromProviderPlaylist = removeFromSelectedPlaylistAction(controller, track),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun SelectedPlaylistTrackList(controller: FuoPlayerController, modifier: Modifier) {
    TrackCollectionList(
        controller = controller,
        tracks = controller.selectedPlaylistTracks,
        emptyMessage = "歌单暂无歌曲",
        showEmpty = !controller.isLoading && controller.selectedPlaylistError == null,
        modifier = modifier,
        onClick = controller::playFromSelectedPlaylist,
        onItemVisible = controller::prefetchSelectedPlaylistIfNeeded,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderMediaItemScreen(controller: FuoPlayerController, item: ProviderMediaItem?) {
    val displayItem = controller.selectedMediaItem ?: item ?: return
    val isArtist = displayItem.type == ProviderMediaItemType.Artist
    val sharePayload = displayItem.toSharePayload()
    var selectedTabIndex by remember(displayItem.id) { mutableStateOf(0) }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = displayItem.title.ifBlank {
                            if (isArtist) "歌手" else "专辑"
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
                actions = {
                    val onShare = LocalShareHandler.current
                    IconButton(
                        onClick = { if (sharePayload != null) onShare(sharePayload) },
                        enabled = sharePayload != null,
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "分享")
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
        if (LocalAppLayoutInfo.current.useWideLayout) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .width(300.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CoverBox(
                        track = displayItem.toDisplayTrack(),
                        modifier = Modifier.size(168.dp),
                    )
                    Text(
                        text = displayItem.title.ifBlank { if (isArtist) "未知歌手" else "未知专辑" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildList {
                            add(displayItem.providerName)
                            add(if (isArtist) "歌手" else "专辑")
                            displayItem.trackCount?.let { add("$it 首") }
                            if (isArtist) displayItem.albumCount?.let { add("$it 张专辑") }
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (displayItem.description.isNotBlank()) {
                        Text(
                            text = displayItem.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (controller.selectedMediaItemTracks.isNotEmpty()) {
                            PlayAllButton(onClick = controller::playAllFromSelectedMediaItem)
                        }
                        ShareTextButton(sharePayload)
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LoadingIndicator(controller.isLoading)
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
                    if (isArtist) {
                        val tabs = listOf("歌曲", "专辑")
                        PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedTabIndex == index,
                                    onClick = { selectedTabIndex = index },
                                    text = { Text(title) },
                                )
                            }
                        }
                    }
                    SelectedMediaItemContent(
                        controller = controller,
                        isArtist = isArtist,
                        selectedTabIndex = selectedTabIndex,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LoadingIndicator(controller.isLoading)
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
                action = if (controller.selectedMediaItemTracks.isNotEmpty()) {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PlayAllButton(onClick = controller::playAllFromSelectedMediaItem)
                            ShareTextButton(sharePayload)
                        }
                    }
                } else {
                    {
                        ShareTextButton(sharePayload)
                    }
                },
            )
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
            if (isArtist) {
                val tabs = listOf("歌曲", "专辑")
                PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(title) },
                        )
                    }
                }
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (isArtist && selectedTabIndex == 1) {
                    if (controller.selectedMediaItemAlbums.isEmpty() && !controller.isLoading && controller.selectedMediaItemError == null) {
                        item {
                            ProviderContentMessage("暂无专辑")
                        }
                    } else {
                        item {
                            ProviderMediaItemGrid(
                                items = controller.selectedMediaItemAlbums,
                                onClick = controller::openMediaItem,
                                onItemVisible = controller::prefetchSelectedMediaItemAlbumsIfNeeded,
                            )
                        }
                    }
                } else {
                    if (controller.selectedMediaItemTracks.isEmpty() && !controller.isLoading && controller.selectedMediaItemError == null) {
                        item {
                            ProviderContentMessage("暂无歌曲")
                        }
                    } else {
                        itemsIndexed(controller.selectedMediaItemTracks, key = { _, track -> track.id }) { index, track ->
                            LaunchedEffect(index, controller.selectedMediaItemTracks.size) {
                                controller.prefetchSelectedMediaItemTracksIfNeeded(index)
                            }
                            TrackRow(
                                track = track,
                                downloadState = controller.downloadStates[track.id],
                                onClick = { controller.playFromSelectedMediaItem(index) },
                                onAddToUpNext = { controller.addToUpNext(track) },
                                onDownload = { controller.download(track) },
                                onDeleteDownload = { controller.deleteDownload(track) },
                                onOpenArtist = { controller.openTrackArtist(track) },
                                onOpenAlbum = { controller.openTrackAlbum(track) },
                                onOpenDetail = trackDetailAction(controller, track),
                                onAddToProviderPlaylist = addToProviderPlaylistAction(controller, track),
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SelectedMediaItemContent(
    controller: FuoPlayerController,
    isArtist: Boolean,
    selectedTabIndex: Int,
    modifier: Modifier,
) {
    if (isArtist && selectedTabIndex == 1) {
        LazyColumn(modifier = modifier) {
            if (controller.selectedMediaItemAlbums.isEmpty() && !controller.isLoading && controller.selectedMediaItemError == null) {
                item {
                    ProviderContentMessage("暂无专辑")
                }
            } else {
                item {
                    ProviderMediaItemGrid(
                        items = controller.selectedMediaItemAlbums,
                        onClick = controller::openMediaItem,
                        onItemVisible = controller::prefetchSelectedMediaItemAlbumsIfNeeded,
                    )
                }
            }
        }
        return
    }
    TrackCollectionList(
        controller = controller,
        tracks = controller.selectedMediaItemTracks,
        emptyMessage = "暂无歌曲",
        showEmpty = !controller.isLoading && controller.selectedMediaItemError == null,
        modifier = modifier,
        onClick = controller::playFromSelectedMediaItem,
        onItemVisible = controller::prefetchSelectedMediaItemTracksIfNeeded,
    )
}

@Composable
fun TrackCollectionList(
    controller: FuoPlayerController,
    tracks: List<MusicTrack>,
    emptyMessage: String,
    showEmpty: Boolean,
    modifier: Modifier,
    onClick: (Int) -> Unit,
    onItemVisible: ((Int) -> Unit)? = null,
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
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ProviderContentMessage(emptyMessage)
                }
            } else {
                items(indexedTracks, key = { it.second.id }) { (index, track) ->
                    if (onItemVisible != null) {
                        LaunchedEffect(index, tracks.size) {
                            onItemVisible(index)
                        }
                    }
                    TrackRow(
                        track = track,
                        downloadState = controller.downloadStates[track.id],
                        onClick = { onClick(index) },
                        onAddToUpNext = { controller.addToUpNext(track) },
                        onDownload = { controller.download(track) },
                        onDeleteDownload = { controller.deleteDownload(track) },
                        onOpenArtist = { controller.openTrackArtist(track) },
                        onOpenAlbum = { controller.openTrackAlbum(track) },
                        onOpenDetail = trackDetailAction(controller, track),
                        onAddToProviderPlaylist = addToProviderPlaylistAction(controller, track),
                        onRemoveFromProviderPlaylist = removeFromSelectedPlaylistAction(controller, track),
                    )
                }
            }
        }
        return
    }
    LazyColumn(modifier = modifier) {
        if (tracks.isEmpty() && showEmpty) {
            item {
                ProviderContentMessage(emptyMessage)
            }
        } else {
            itemsIndexed(tracks, key = { _, item -> item.id }) { index, track ->
                if (onItemVisible != null) {
                    LaunchedEffect(index, tracks.size) {
                        onItemVisible(index)
                    }
                }
                TrackRow(
                    track = track,
                    downloadState = controller.downloadStates[track.id],
                    onClick = { onClick(index) },
                    onAddToUpNext = { controller.addToUpNext(track) },
                    onDownload = { controller.download(track) },
                    onDeleteDownload = { controller.deleteDownload(track) },
                    onOpenArtist = { controller.openTrackArtist(track) },
                    onOpenAlbum = { controller.openTrackAlbum(track) },
                    onOpenDetail = trackDetailAction(controller, track),
                    onAddToProviderPlaylist = addToProviderPlaylistAction(controller, track),
                    onRemoveFromProviderPlaylist = removeFromSelectedPlaylistAction(controller, track),
                )
                HorizontalDivider()
            }
        }
    }
}
