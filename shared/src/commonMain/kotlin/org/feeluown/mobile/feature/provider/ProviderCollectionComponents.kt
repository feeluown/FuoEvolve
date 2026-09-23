package org.feeluown.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    val visiblePlaylists = if (hasMore) playlists.take(capacity) else playlists

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        if (hasMore && onMore != null) {
            ProviderCollectionMoreAction(onClick = onMore)
        }
        visiblePlaylists.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                row.forEach { playlist ->

                    ProviderPlaylistCard(
                        playlist = playlist,
                        onClick = { onClick(playlist) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

internal fun LazyListScope.addProviderPlaylistGridRows(
    playlists: List<ProviderPlaylist>,
    columns: Int,
    spacing: Dp,
    keyPrefix: String,
    onClick: (ProviderPlaylist) -> Unit,
    onMore: (() -> Unit)? = null,
    maxRows: Int? = null,
) {
    val normalizedColumns = columns.coerceAtLeast(1)
    val capacity = normalizedColumns * (maxRows ?: 2)
    val isLimited = maxRows != null || onMore != null
    val hasMore = isLimited && playlists.size > capacity
    val visiblePlaylists = if (hasMore) playlists.take(capacity) else playlists

    if (hasMore && onMore != null) {
        item("$keyPrefix:more") { ProviderCollectionMoreAction(onMore) }
    }
    visiblePlaylists.chunked(normalizedColumns).forEachIndexed { rowIndex, row ->
        item("$keyPrefix:row:$rowIndex") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                row.forEach { playlist ->
                    ProviderPlaylistCard(
                        playlist = playlist,
                        onClick = { onClick(playlist) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(normalizedColumns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProviderCollectionMoreAction(onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onClick) {
            Text("查看更多")
        }
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
            .clickable(
                role = Role.Button,
                onClick = onClick,
            )
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
    val visibleItems = if (hasMore) items.take(capacity) else items

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        if (hasMore && onMore != null) {
            ProviderCollectionMoreAction(onClick = onMore)
        }
        visibleItems.chunked(columns).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                row.forEachIndexed { columnIndex, item ->
                    val index = rowIndex * columns + columnIndex
                    if (onItemVisible != null) {
                        LaunchedEffect(index, items.size) {
                            onItemVisible(index)
                        }
                    }

                    ProviderMediaItemCard(
                        item = item,
                        onClick = { onClick(item) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

internal fun LazyListScope.addProviderMediaItemGridRows(
    items: List<ProviderMediaItem>,
    columns: Int,
    spacing: Dp,
    keyPrefix: String,
    onClick: (ProviderMediaItem) -> Unit,
    maxRows: Int? = null,
    onItemVisible: ((Int) -> Unit)? = null,
) {
    val normalizedColumns = columns.coerceAtLeast(1)
    val capacity = normalizedColumns * (maxRows ?: 2)
    val isLimited = maxRows != null
    val hasMore = isLimited && items.size > capacity
    val visibleItems = if (hasMore) items.take(capacity) else items

    visibleItems.chunked(normalizedColumns).forEachIndexed { rowIndex, row ->
        item("$keyPrefix:row:$rowIndex") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                row.forEachIndexed { columnIndex, item ->
                    val index = rowIndex * normalizedColumns + columnIndex
                    if (onItemVisible != null) {
                        LaunchedEffect(index, items.size) {
                            onItemVisible(index)
                        }
                    }
                    ProviderMediaItemCard(
                        item = item,
                        onClick = { onClick(item) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(normalizedColumns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
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
            .clickable(
                role = Role.Button,
                onClick = onClick,
            )
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
