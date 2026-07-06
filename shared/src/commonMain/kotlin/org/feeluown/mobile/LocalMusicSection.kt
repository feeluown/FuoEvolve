package org.feeluown.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun LocalMusicSection(
    controller: FuoPlayerController,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    showModeFilter: Boolean,
    modifier: Modifier,
) {
    LaunchedEffect(hasAudioPermission) {
        controller.onLocalMusicPermissionChange(hasAudioPermission)
        if (hasAudioPermission) {
            controller.ensureLocalMusic()
        }
    }
    val displayTracks = remember(controller.localTracks, controller.localMusicViewMode) {
        when (controller.localMusicViewMode) {
            LocalMusicViewMode.All -> controller.localTracks.sortedWith(
                compareBy<MusicTrack> { localTitleSectionOrder(localTitleSection(it.title)) }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.artists.lowercase() },
            )
            LocalMusicViewMode.Artist -> controller.localTracks.sortedWith(
                compareBy<MusicTrack> { normalizedGroupName(it.artists, "未知歌手").lowercase() }
                    .thenBy { it.album.lowercase() }
                    .thenBy { it.title.lowercase() },
            )
            LocalMusicViewMode.Album -> controller.localTracks.sortedWith(
                compareBy<MusicTrack> { normalizedGroupName(it.album, "未知专辑").lowercase() }
                    .thenBy { it.artists.lowercase() }
                    .thenBy { it.title.lowercase() },
            )
        }
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showModeFilter) {
                LocalMusicViewModeTabs(controller)
            } else {
                Spacer(Modifier)
            }
            if (displayTracks.isNotEmpty()) {
                PlayAllButton(onClick = { controller.playAllLocalTracks(displayTracks) })
            }
        }
        val groups = remember(displayTracks, controller.localMusicViewMode) {
            when (controller.localMusicViewMode) {
                LocalMusicViewMode.All -> displayTracks.groupBy { localTitleSection(it.title) }
                LocalMusicViewMode.Artist -> displayTracks.groupBy { normalizedGroupName(it.artists, "未知歌手") }
                LocalMusicViewMode.Album -> displayTracks.groupBy { normalizedGroupName(it.album, "未知专辑") }
            }
        }
        if (isWideLayout) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (displayTracks.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyLocalMusicHint()
                    }
                } else {
                    groups.forEach { (section, tracks) ->
                        item(key = "section:$section", span = { GridItemSpan(maxLineSpan) }) {
                            LocalMusicSectionHeader(title = section, count = tracks.size)
                        }
                        items(tracks, key = { it.id }) { track ->
                            TrackRow(
                                track = track,
                                downloadState = controller.downloadStates[track.id],
                                onClick = { controller.playLocalTrack(track, displayTracks) },
                                onAddToUpNext = { controller.addToUpNext(track) },
                                onDownload = { controller.download(track) },
                                onDeleteDownload = { controller.deleteDownload(track) },
                                onOpenArtist = { controller.openTrackArtist(track) },
                                onOpenAlbum = { controller.openTrackAlbum(track) },
                                onEditLocalMetadata = { controller.openLocalMetadataEditor(track) },
                            )
                        }
                    }
                }
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (displayTracks.isEmpty()) {
                item {
                    EmptyLocalMusicHint()
                }
            } else {
                groups.forEach { (section, tracks) ->
                    item(key = "section:$section") {
                        LocalMusicSectionHeader(title = section, count = tracks.size)
                    }
                    itemsIndexed(tracks, key = { _, item -> item.id }) { _, track ->
                        TrackRow(
                            track = track,
                            downloadState = controller.downloadStates[track.id],
                            onClick = { controller.playLocalTrack(track, displayTracks) },
                            onAddToUpNext = { controller.addToUpNext(track) },
                            onDownload = { controller.download(track) },
                            onDeleteDownload = { controller.deleteDownload(track) },
                            onOpenArtist = { controller.openTrackArtist(track) },
                            onOpenAlbum = { controller.openTrackAlbum(track) },
                            onEditLocalMetadata = { controller.openLocalMetadataEditor(track) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun LocalMetadataDialog(
    controller: FuoPlayerController,
    track: MusicTrack,
) {
    var title by remember(track.id, track.title) { mutableStateOf(track.title) }
    var artists by remember(track.id, track.artists) { mutableStateOf(track.artists) }
    var album by remember(track.id, track.album) { mutableStateOf(track.album) }
    AlertDialog(
        onDismissRequest = controller::closeLocalMetadataEditor,
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
                if (controller.providers.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        controller.orderedProviders().forEach { provider ->
                            FilterChip(
                                selected = controller.selectedLocalMetadataProviderId == provider.providerId,
                                onClick = { controller.onLocalMetadataProviderChange(provider.providerId) },
                                label = { Text(provider.providerName) },
                            )
                        }
                    }
                    TextButton(
                        enabled = !controller.isLoading,
                        onClick = { controller.searchLocalMetadata(title, artists, album) },
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
                controller.localMetadataSearchMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (controller.localMetadataSearchResults.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                    ) {
                        itemsIndexed(
                            controller.localMetadataSearchResults,
                            key = { _, item -> item.id },
                        ) { _, result ->
                            LocalMetadataSearchResultRow(
                                track = result,
                                onApplyMetadata = {
                                    title = result.title
                                    artists = result.artists
                                    album = result.album
                                    controller.applyProviderMetadata(track, result)
                                },
                                onDownloadLyrics = { controller.downloadLocalLyrics(track, result) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !controller.isLoading,
                onClick = { controller.saveLocalMetadata(track, title, artists, album) },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = controller::closeLocalMetadataEditor) {
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
            CoverBox(track, modifier = Modifier.size(40.dp))
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
fun LocalMusicViewModeTabs(controller: FuoPlayerController) {
    val modes = listOf(
        LocalMusicViewMode.All to "全部",
        LocalMusicViewMode.Artist to "歌手",
        LocalMusicViewMode.Album to "专辑",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        modes.forEach { (mode, label) ->
            CompactFilterChip(
                selected = controller.localMusicViewMode == mode,
                onClick = { controller.onLocalMusicViewModeChange(mode) },
                label = label,
            )
        }
    }
}

@Composable
fun LocalMusicSectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "$count 首",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EmptyLocalMusicHint() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            modifier = Modifier.padding(16.dp),
            text = "未发现本地音乐",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
