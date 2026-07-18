package org.feeluown.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun TrackRow(
    track: MusicTrack,
    downloadState: DownloadState?,
    onClick: () -> Unit,
    onAddToUpNext: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onOpenArtist: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenDetail: (() -> Unit)? = null,
    onEditLocalMetadata: (() -> Unit)? = null,
    onAddToProviderPlaylist: (() -> Unit)? = null,
    onRemoveFromProviderPlaylist: (() -> Unit)? = null,
    onSetDisliked: (() -> Unit)? = null,
    dislikedActionLabel: String = "不喜欢",
) {
    val onShare = LocalShareHandler.current
    val sharePayload = track.toSharePayload()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverBox(track)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title.ifBlank { "未知歌曲" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = listOf(track.artists, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sourceLabel(track, downloadState),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TrackAction(
            track = track,
            downloadState = downloadState,
            onAddToUpNext = onAddToUpNext,
            onDownload = onDownload,
            onDeleteDownload = onDeleteDownload,
            onOpenArtist = onOpenArtist,
            onOpenAlbum = onOpenAlbum,
            onOpenDetail = onOpenDetail,
            onEditLocalMetadata = onEditLocalMetadata,
            onAddToProviderPlaylist = onAddToProviderPlaylist,
            onRemoveFromProviderPlaylist = onRemoveFromProviderPlaylist,
            onSetDisliked = onSetDisliked,
            dislikedActionLabel = dislikedActionLabel,
            onShare = sharePayload?.let { payload -> { onShare(payload) } },
        )
    }
}

@Composable
fun CoverBox(
    track: MusicTrack,
    cornerRadius: androidx.compose.ui.unit.Dp = 8.dp,
    modifier: Modifier = Modifier.size(48.dp),
    placeholder: CoverPlaceholder = CoverPlaceholder.Song,
) {
    PlatformCoverArt(
        title = track.title,
        imageUrl = track.coverUrl,
        modifier = Modifier
            .then(modifier)
            .clip(RoundedCornerShape(cornerRadius)),
        placeholder = placeholder,
    )
}

@Composable
fun TrackAction(
    track: MusicTrack,
    downloadState: DownloadState?,
    onAddToUpNext: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onOpenArtist: () -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenDetail: (() -> Unit)? = null,
    onEditLocalMetadata: (() -> Unit)?,
    onAddToProviderPlaylist: (() -> Unit)?,
    onRemoveFromProviderPlaylist: (() -> Unit)?,
    onSetDisliked: (() -> Unit)?,
    dislikedActionLabel: String,
    onShare: (() -> Unit)?,
    roundButton: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        if (roundButton) {
            RoundControlButton(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "更多操作",
                onClick = { expanded = true },
                size = 44.dp,
                iconSize = 24.dp,
            )
        } else {
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (onOpenDetail != null) {
                DropdownMenuItem(
                    text = { Text("歌曲详情") },
                    leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onOpenDetail()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("接下来播放") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                onClick = {
                    expanded = false
                    onAddToUpNext()
                },
            )
            when {
                track.sourceType == TrackSourceType.Provider && downloadState is DownloadState.Downloading -> {
                    DropdownMenuItem(
                        text = { Text("下载中") },
                        leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                        enabled = false,
                        onClick = {},
                    )
                }
                track.sourceType == TrackSourceType.Provider && downloadState is DownloadState.Paused -> {
                    DropdownMenuItem(
                        text = { Text("继续下载") },
                        leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onDownload()
                        },
                    )
                }
                (track.sourceType == TrackSourceType.Provider && downloadState is DownloadState.Downloaded) ||
                    track.sourceType == TrackSourceType.Downloaded -> {
                    DropdownMenuItem(
                        text = { Text("删除下载") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onDeleteDownload()
                        },
                    )
                }
                track.sourceType == TrackSourceType.Provider -> {
                    DropdownMenuItem(
                        text = { Text("下载") },
                        leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onDownload()
                        },
                    )
                }
            }
            if (onEditLocalMetadata != null) {
                DropdownMenuItem(
                    text = { Text("修改元信息") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onEditLocalMetadata()
                    },
                )
            }
            if (onAddToProviderPlaylist != null) {
                DropdownMenuItem(
                    text = { Text("添加到歌单") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onAddToProviderPlaylist()
                    },
                )
            }
            if (onRemoveFromProviderPlaylist != null) {
                DropdownMenuItem(
                    text = { Text("从当前歌单移除") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onRemoveFromProviderPlaylist()
                    },
                )
            }
            if (onSetDisliked != null) {
                DropdownMenuItem(
                    text = { Text(dislikedActionLabel) },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onSetDisliked()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("查看歌手") },
                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                enabled = track.artists.isNotBlank(),
                onClick = {
                    expanded = false
                    onOpenArtist()
                },
            )
            DropdownMenuItem(
                text = { Text("查看专辑") },
                leadingIcon = { Icon(Icons.Filled.Album, contentDescription = null) },
                enabled = track.album.isNotBlank(),
                onClick = {
                    expanded = false
                    onOpenAlbum()
                },
            )
            if (onShare != null) {
                DropdownMenuItem(
                    text = { Text("分享") },
                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onShare()
                    },
                )
            }
        }
    }
}

fun addToProviderPlaylistAction(controller: FuoPlayerController, track: MusicTrack): (() -> Unit)? {
    return if (controller.canAddTrackToProviderPlaylist(track)) {
        { controller.openPlaylistTargetPicker(track) }
    } else {
        null
    }
}

fun removeFromSelectedPlaylistAction(controller: FuoPlayerController, track: MusicTrack): (() -> Unit)? {
    return if (controller.canRemoveTrackFromSelectedPlaylist(track)) {
        { controller.removeTrackFromSelectedPlaylist(track) }
    } else {
        null
    }
}

fun trackDetailAction(controller: FuoPlayerController, track: MusicTrack): (() -> Unit)? {
    return if (track.sourceType == TrackSourceType.Provider) {
        { controller.openTrackDetail(track) }
    } else {
        null
    }
}
