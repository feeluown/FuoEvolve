package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Restores the dedicated Bilibili weekly-must-watch presentation removed during P2 extraction.
 * Every other provider feature continues through the existing generic detail route unchanged.
 */
@Composable
fun ProviderFeatureParityDetailRoute(feature: ProviderFeature) {
    if (!feature.isBilibiliWeeklyMustWatch()) {
        ProviderFeatureDetailRoute(feature)
        return
    }
    BilibiliWeeklyParityDetailRoute(feature)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BilibiliWeeklyParityDetailRoute(feature: ProviderFeature) {
    val graph = LocalProviderDetailUiGraph.current
    val owner = graph.owners.feature
    val state by owner.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(feature.id) { owner.activate(feature) }
    val displayFeature = state.feature ?: feature
    val playlists = state.content?.playlists.orEmpty()
    var selectedNumber by remember(displayFeature.id) { mutableStateOf(displayFeature.bilibiliWeeklyNumber()) }
    LaunchedEffect(displayFeature.id) {
        displayFeature.bilibiliWeeklyNumber()?.let { selectedNumber = it }
    }
    LaunchedEffect(playlists) {
        if (selectedNumber == null) selectedNumber = playlists.firstOrNull()?.bilibiliWeeklyNumber()
    }
    val selectedIndex = playlists.indexOfFirst { it.bilibiliWeeklyNumber() == selectedNumber }
        .takeIf { it >= 0 }
        ?: 0
    val selectedPlaylist = playlists.getOrNull(selectedIndex)
    val baseFeatureId = ProviderFeatureFilterCodec.requestId(displayFeature.id).substringBefore('|')

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(displayFeature.title.ifBlank { "每周必看" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = owner::close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = { if (graph.playbackQueue.currentQueueTrack != null) PlaybackMiniPlayer() },
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.md),
        ) {
            if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.errorMessage?.let { ProviderContentMessage(it) }
            if (playlists.isNotEmpty()) {
                PrimaryScrollableTabRow(
                    modifier = Modifier.fillMaxWidth(),
                    selectedTabIndex = selectedIndex,
                    edgePadding = 0.dp,
                ) {
                    playlists.forEachIndexed { index, playlist ->
                        val number = playlist.bilibiliWeeklyNumber()
                        Tab(
                            selected = index == selectedIndex,
                            onClick = {
                                if (index != selectedIndex && number != null) {
                                    selectedNumber = number
                                    owner.activate(displayFeature.copy(id = "$baseFeatureId|number=$number"))
                                }
                            },
                            text = {
                                Text(
                                    number?.let { "第${it}期" } ?: playlist.title.ifBlank { "第${index + 1}期" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
                selectedPlaylist?.let { playlist ->
                    FuoSectionCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            playlist.title.ifBlank { "每周必看" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (playlist.description.isNotBlank()) {
                            Text(
                                playlist.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            "${state.tracks.size} 个视频",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            BilibiliWeeklyTrackList(
                tracks = state.tracks,
                loading = state.isLoading,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onClick = owner::play,
                onItemVisible = owner::prefetchIfNeeded,
            )
            if (state.hasMore) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = owner::loadMore) { Text("加载更多期") }
                }
            }
        }
    }
}

@Composable
private fun BilibiliWeeklyTrackList(
    tracks: List<MusicTrack>,
    loading: Boolean,
    modifier: Modifier,
    onClick: (Int) -> Unit,
    onItemVisible: (Int) -> Unit,
) {
    val graph = LocalProviderDetailUiGraph.current
    if (tracks.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!loading) Text("本期暂无内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = modifier) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            LaunchedEffect(index, tracks.size) { onItemVisible(index) }
            TrackRow(
                track = track,
                downloadState = graph.downloads.downloadStates[track.id],
                onClick = { onClick(index) },
                onAddToUpNext = { graph.playbackQueue.addToUpNext(track) },
                onDownload = { graph.downloads.download(track) },
                onDeleteDownload = { graph.downloads.deleteDownload(track) },
                onOpenArtist = { graph.providerTrackActions.openTrackArtist(track) },
                onOpenAlbum = { graph.providerTrackActions.openTrackAlbum(track) },
                onOpenDetail = { graph.owners.track.open(track) },
                onAddToPlaylist = if (graph.playlists.canAddTrackToPlaylist(track)) {
                    { graph.playlists.openPlaylistTargetPicker(track) }
                } else null,
                onSetDisliked = if (graph.providerTrackActions.canSetSongDisliked(track)) {
                    { graph.providerTrackActions.setSongDisliked(track) }
                } else null,
                onEditLocalMetadata = null,
            )
        }
    }
}

internal fun ProviderFeature.bilibiliWeeklyNumber(): String? =
    ProviderFeatureFilterCodec.requestId(id).substringAfter("|number=", "").takeIf { it.isNotBlank() }

internal fun ProviderPlaylist.bilibiliWeeklyNumber(): String? = id
    .substringAfterLast(':')
    .removePrefix("weekly_")
    .takeIf { it.isNotBlank() }
