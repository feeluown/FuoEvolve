package org.feeluown.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun TrackArtistTargetFeatureDialog(actions: ProviderTrackActionPort) {
    val state by actions.artistTargetPickerState.collectAsStateWithLifecycle()
    val track = state.track ?: return
    AlertDialog(
        onDismissRequest = actions::closeArtistTargetPicker,
        title = { Text("查看歌手") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = track.title.ifBlank { "未知歌曲" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                state.targets.forEach { target ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .fuoInteractive()
                            .clickable(role = Role.Button) { actions.openArtistTarget(target) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Person, contentDescription = null)
                        Text(
                            text = target.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = actions::closeArtistTargetPicker) { Text("取消") }
        },
    )
}

@Composable
fun PlaylistTargetFeatureDialog(
    actions: PlaylistActionPort,
    localPlaylist: LocalPlaylistFeatureController,
) {
    val state by actions.targetPickerState.collectAsStateWithLifecycle()
    val localState by localPlaylist.uiState.collectAsStateWithLifecycle()
    val track = state.track ?: return
    val canAddProvider = actions.canAddTrackToProviderPlaylist(track)
    val canAddLocal = actions.canAddTrackToLocalPlaylist(track)

    AlertDialog(
        onDismissRequest = actions::closePlaylistTargetPicker,
        title = { Text("添加到歌单", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = track.title.ifBlank { "未知歌曲" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.showSwitcher && canAddProvider && canAddLocal) {
                    val targetTypes = listOf(
                        PlaylistTargetType.Provider to actions.playlistProviderName(track),
                        PlaylistTargetType.Local to "本地歌单",
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        targetTypes.forEachIndexed { index, (type, label) ->
                            SegmentedButton(
                                selected = state.targetType == type,
                                onClick = { actions.selectPlaylistTargetType(type) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = targetTypes.size),
                            ) {
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                when (state.targetType) {
                    PlaylistTargetType.Provider -> {
                        if (state.providerTargets.isEmpty() && state.providerError == null) {
                            Text(
                                text = "正在加载可添加歌单",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.providerError?.let { ProviderContentMessage(it) }
                        state.providerTargets.forEach { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium)
                                    .fuoInteractive()
                                    .clickable(role = Role.Button) { actions.addTrackToProviderPlaylist(playlist) }
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CoverBox(
                                    track = playlist.toDisplayTrack(),
                                    modifier = Modifier.size(48.dp),
                                    placeholder = CoverPlaceholder.Playlist,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.title.ifBlank { "未命名歌单" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = playlist.providerName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }

                    PlaylistTargetType.Local -> {
                        if (localState.playlists.isEmpty()) {
                            ProviderContentMessage("请先在“我的 → 歌单 → 本地”中新建歌单")
                        } else {
                            state.localError?.let { ProviderContentMessage(it) }
                            localState.playlists.forEach { playlist ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.medium)
                                        .fuoInteractive()
                                        .clickable(role = Role.Button) { actions.addTrackToLocalPlaylist(playlist) }
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CoverBox(
                                        track = playlist.toDisplayTrack(),
                                        modifier = Modifier.size(48.dp),
                                        placeholder = CoverPlaceholder.Playlist,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = playlist.title.ifBlank { "未命名歌单" },
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${playlist.tracks.size} 首",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = actions::closePlaylistTargetPicker) { Text("取消") }
        },
    )
}
