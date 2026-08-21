package org.feeluown.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ProviderPlaylistGrid(
    playlists: List<ProviderPlaylist>,
    onClick: (ProviderPlaylist) -> Unit,
    onMore: (() -> Unit)? = null,
    maxRows: Int? = null,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val columns = layoutInfo.gridColumns.coerceAtLeast(1)
    val spacing = if (layoutInfo.useWideLayout) 8.dp else 12.dp
    val capacity = columns * (maxRows ?: 2)
    val isLimited = maxRows != null || onMore != null
    val hasMore = isLimited && playlists.size > capacity
    val visiblePlaylists = when {
        hasMore && onMore != null -> playlists.take(capacity - 1)
        hasMore -> playlists.take(capacity)
        else -> playlists
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        val cells = visiblePlaylists.map<ProviderPlaylist, PlaylistGridCell> { PlaylistGridCell.Playlist(it) } +
            if (hasMore && onMore != null) listOf(PlaylistGridCell.More) else emptyList()
        cells.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                row.forEach { cell ->
                    when (cell) {
                        PlaylistGridCell.More -> {
                            ProviderPlaylistMoreCard(
                                onClick = { onMore?.invoke() },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        is PlaylistGridCell.Playlist -> {
                            ProviderPlaylistCard(
                                playlist = cell.value,
                                onClick = { onClick(cell.value) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private sealed interface PlaylistGridCell {
    data class Playlist(val value: ProviderPlaylist) : PlaylistGridCell
    data object More : PlaylistGridCell
}

@Composable
fun ProviderPlaylistMoreCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ProviderCollectionMoreCard(
        subtitle = "全部歌单",
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun ProviderCollectionMoreCard(
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    Column(
        modifier = modifier
            .fuoInteractive()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = if (isWideLayout) 2.dp else 6.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier.size(if (isWideLayout) 40.dp else 44.dp),
                )
            }
        }
        Spacer(Modifier.height(if (isWideLayout) 4.dp else 8.dp))
        Text(
            text = "查看更多",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ProviderPlaylistCard(
    playlist: ProviderPlaylist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    Column(
        modifier = modifier
            .fuoInteractive()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = if (isWideLayout) 2.dp else 6.dp),
    ) {
        CoverBox(
            track = playlist.toDisplayTrack(),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            placeholder = CoverPlaceholder.Playlist,
        )
        Spacer(Modifier.height(if (isWideLayout) 4.dp else 8.dp))
        Text(
            text = playlist.title.ifBlank { "未命名歌单" },
            style = MaterialTheme.typography.titleSmall,
            maxLines = if (isWideLayout) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOf(
                playlist.providerName,
                playlist.playCount?.let { formatPlayCount(it) },
            ).filterNotNull().joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ProviderMediaItemGrid(
    items: List<ProviderMediaItem>,
    onClick: (ProviderMediaItem) -> Unit,
    onItemVisible: ((Int) -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    maxRows: Int? = null,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val columns = layoutInfo.gridColumns.coerceAtLeast(1)
    val spacing = if (layoutInfo.useWideLayout) 8.dp else 12.dp
    val capacity = columns * (maxRows ?: 2)
    val isLimited = maxRows != null || onMore != null
    val hasMore = isLimited && items.size > capacity
    val visibleItems = when {
        hasMore && onMore != null -> items.take(capacity - 1)
        hasMore -> items.take(capacity)
        else -> items
    }
    val itemType = items.firstOrNull()?.type
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        val cells = visibleItems.map<ProviderMediaItem, MediaItemGridCell> { MediaItemGridCell.Item(it) } +
            if (hasMore && onMore != null) listOf(MediaItemGridCell.More) else emptyList()
        cells.chunked(columns).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                row.forEachIndexed { columnIndex, cell ->
                    when (cell) {
                        MediaItemGridCell.More -> {
                            ProviderCollectionMoreCard(
                                subtitle = when (itemType) {
                                    ProviderMediaItemType.Artist -> "全部歌手"
                                    ProviderMediaItemType.Album -> "全部专辑"
                                    null -> "全部内容"
                                },
                                onClick = { onMore?.invoke() },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        is MediaItemGridCell.Item -> {
                            val index = rowIndex * columns + columnIndex
                            if (onItemVisible != null) {
                                LaunchedEffect(index, items.size) {
                                    onItemVisible(index)
                                }
                            }
                            ProviderMediaItemCard(
                                item = cell.value,
                                onClick = { onClick(cell.value) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private sealed interface MediaItemGridCell {
    data class Item(val value: ProviderMediaItem) : MediaItemGridCell
    data object More : MediaItemGridCell
}

@Composable
fun ProviderMediaItemCard(
    item: ProviderMediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    Column(
        modifier = modifier
            .fuoInteractive()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = if (isWideLayout) 2.dp else 6.dp),
    ) {
        CoverBox(
            track = item.toDisplayTrack(),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            placeholder = when (item.type) {
                ProviderMediaItemType.Artist -> CoverPlaceholder.Artist
                ProviderMediaItemType.Album -> CoverPlaceholder.Album
            },
        )
        Spacer(Modifier.height(if (isWideLayout) 4.dp else 8.dp))
        Text(
            text = item.title.ifBlank { if (item.type == ProviderMediaItemType.Artist) "未知歌手" else "未知专辑" },
            style = MaterialTheme.typography.titleSmall,
            maxLines = if (isWideLayout) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOf(
                item.providerName,
                if (item.type == ProviderMediaItemType.Artist) "歌手" else "专辑",
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
