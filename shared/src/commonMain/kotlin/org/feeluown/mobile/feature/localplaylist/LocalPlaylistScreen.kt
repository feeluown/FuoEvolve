package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    controller: FuoPlayerController,
    playlist: LocalPlaylist?,
) {
    val displayPlaylist = controller.selectedLocalPlaylist ?: playlist ?: return
    val fileActions = LocalLocalPlaylistFileActions.current
    var showDeleteDialog by remember(displayPlaylist.id) { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = displayPlaylist.title.ifBlank { "本地歌单" },
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = controller::closeLocalPlaylist) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        enabled = controller.canDeleteSelectedLocalPlaylist(),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除歌单")
                    }
                    IconButton(
                        onClick = {
                            controller.exportSelectedLocalPlaylist { file ->
                                fileActions.exportFile?.invoke(file.fileName, file.content)
                            }
                        },
                        enabled = fileActions.exportFile != null,
                    ) {
                        Icon(Icons.Filled.FileUpload, contentDescription = "导出歌单")
                    }
                    IconButton(
                        onClick = {
                            controller.exportSelectedLocalPlaylist { file ->
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
            if (controller.playbackState.currentTrack != null) MiniPlayer(controller)
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
                subtitle = "本地文件 · ${controller.selectedLocalPlaylistTracks.size} 首",
                description = displayPlaylist.description,
                placeholder = CoverPlaceholder.Playlist,
                action = {
                    PlayAllButton(
                        onClick = controller::playAllFromSelectedLocalPlaylist,
                        enabled = controller.selectedLocalPlaylistTracks.isNotEmpty(),
                    )
                },
            )
            LoadingIndicator(controller.isLoading)
            controller.selectedLocalPlaylistError?.let { ProviderContentMessage(it) }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (controller.selectedLocalPlaylistTracks.isEmpty() && !controller.isLoading) {
                    item { ProviderContentMessage("歌单暂无歌曲") }
                } else {
                    itemsIndexed(
                        controller.selectedLocalPlaylistTracks,
                        key = { _, track -> track.id },
                    ) { index, track ->
                        TrackRow(
                            track = track,
                            downloadState = controller.downloadStates[track.id],
                            onClick = { controller.playFromSelectedLocalPlaylist(index) },
                            onAddToUpNext = { controller.addToUpNext(track) },
                            onDownload = { controller.download(track) },
                            onDeleteDownload = { controller.deleteDownload(track) },
                            onOpenArtist = { controller.openTrackArtist(track) },
                            onOpenAlbum = { controller.openTrackAlbum(track) },
                            onOpenDetail = trackDetailAction(controller, track),
                            onAddToPlaylist = addToPlaylistAction(controller, track),
                            onRemoveFromProviderPlaylist = removeFromSelectedLocalPlaylistAction(controller, track),
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
                        controller.deleteSelectedLocalPlaylist()
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }
}
