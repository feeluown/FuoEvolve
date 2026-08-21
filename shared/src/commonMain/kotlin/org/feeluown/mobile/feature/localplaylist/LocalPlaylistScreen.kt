package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class LocalPlaylistFileActions(
    val importFile: (() -> Unit)? = null,
    val exportFile: ((String, String) -> Unit)? = null,
    val shareFile: ((String, String) -> Unit)? = null,
)

val LocalLocalPlaylistFileActions = androidx.compose.runtime.staticCompositionLocalOf {
    LocalPlaylistFileActions()
}

fun LocalPlaylist.toDisplayTrack(): MusicTrack = MusicTrack(
    id = id,
    title = title,
    artists = "本地歌单",
    album = "",
    source = "local-playlist",
    sourceType = TrackSourceType.LocalMediaStore,
    providerName = "本地歌单",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPlaylistScreen(
    uiState: LocalPlaylistUiState,
    actions: LocalPlaylistUiActions,
    playlist: LocalPlaylist? = null,
) {
    val displayPlaylist = uiState.selectedPlaylist ?: playlist ?: return
    val fileActions = LocalLocalPlaylistFileActions.current
    val playbackUiPort = LocalPlaybackUiPort.current
    val playbackQueue = LocalPlaybackQueueUiPort.current
    val downloads = LocalDownloadActionPort.current
    val playlistActions = LocalPlaylistActionPort.current
    val providerTrackActions = LocalProviderTrackActionPort.current
    var showDeleteDialog by remember(displayPlaylist.id) { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(displayPlaylist.title.ifBlank { "本地歌单" }, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = actions::close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        enabled = actions.canDeleteSelected(),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除歌单")
                    }
                    IconButton(
                        onClick = {
                            actions.exportSelected { file ->
                                fileActions.exportFile?.invoke(file.fileName, file.content)
                            }
                        },
                        enabled = fileActions.exportFile != null,
                    ) {
                        Icon(Icons.Filled.FileUpload, contentDescription = "导出歌单")
                    }
                    IconButton(
                        onClick = {
                            actions.exportSelected { file ->
                                fileActions.shareFile?.invoke(file.fileName, file.content)
                            }
                        },
                        enabled = fileActions.shareFile != null,
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "分享歌单文件")
                    }
                },
            )
        },
        bottomBar = {
            if (playbackUiPort.currentTrack != null) PlaybackMiniPlayer()
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderDetailHeader(
                track = displayPlaylist.toDisplayTrack(),
                title = displayPlaylist.title.ifBlank { "未命名歌单" },
                subtitle = "本地文件 · ${uiState.selectedTracks.size} 首",
                description = displayPlaylist.description,
                placeholder = CoverPlaceholder.Playlist,
                action = {
                    PlayAllButton(
                        onClick = {
                            if (uiState.selectedTracks.isNotEmpty()) {
                                playbackQueue.playAllPlaylistTracks(uiState.selectedTracks, displayPlaylist.id)
                            }
                        },
                        enabled = uiState.selectedTracks.isNotEmpty(),
                    )
                },
            )
            LoadingIndicator(uiState.isLoading)
            uiState.selectedError?.let { ProviderContentMessage(it) }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (uiState.selectedTracks.isEmpty() && !uiState.isLoading) {
                    item { ProviderContentMessage("歌单暂无歌曲") }
                } else {
                    itemsIndexed(
                        uiState.selectedTracks,
                        key = { _, track -> track.id },
                    ) { index, track ->
                        TrackRow(
                            track = track,
                            downloadState = downloads.downloadStates[track.id],
                            onClick = {
                                playbackQueue.playPlaylistTracks(uiState.selectedTracks, index, displayPlaylist.id)
                            },
                            onAddToUpNext = { playbackQueue.addToUpNext(track) },
                            onDownload = { downloads.download(track) },
                            onDeleteDownload = { downloads.deleteDownload(track) },
                            onOpenArtist = { providerTrackActions.openTrackArtist(track) },
                            onOpenAlbum = { providerTrackActions.openTrackAlbum(track) },
                            onOpenDetail = if (track.sourceType == TrackSourceType.Provider) {
                                { providerTrackActions.openOriginalTrackDetail(track) }
                            } else {
                                null
                            },
                            onAddToPlaylist = if (playlistActions.canAddTrackToPlaylist(track)) {
                                { playlistActions.openPlaylistTargetPicker(track) }
                            } else {
                                null
                            },
                            onRemoveFromProviderPlaylist = if (actions.canRemove(track)) {
                                { actions.remove(track) }
                            } else {
                                null
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除本地歌单？") },
            text = { Text("将删除《${displayPlaylist.title}》，此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        actions.deleteSelected()
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }
}