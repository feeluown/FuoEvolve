package org.feeluown.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun HomeRecentTrackShelf(
    resources: List<ListeningResourceStat>,
    onClick: (ListeningResourceSnapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val wide = LocalAppLayoutInfo.current.useWideLayout
    val cardWidth = if (wide) 164.dp else 144.dp
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (wide) FuoSpacing.md else FuoSpacing.lg),
    ) {
        items(
            items = resources,
            key = { it.resource.resourceKey },
        ) { stat ->
            val resource = stat.resource
            Column(
                modifier = Modifier
                    .width(cardWidth)
                    .fuoInteractive()
                    .clickable(role = Role.Button) { onClick(resource) },
                verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
            ) {
                PlatformCoverArt(
                    title = resource.title,
                    imageUrl = resource.coverUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(FuoVisualTokens.artwork),
                    placeholder = CoverPlaceholder.Song,
                )
                Text(
                    text = resource.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = resource.subtitle.ifBlank { resource.sourceId },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun HomePlaylistShelf(
    playlists: List<ProviderPlaylist>,
    onClick: (ProviderPlaylist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val wide = LocalAppLayoutInfo.current.useWideLayout
    val cardWidth = if (wide) 164.dp else 148.dp
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (wide) FuoSpacing.md else FuoSpacing.lg),
    ) {
        items(
            items = playlists,
            key = { "${it.providerId}:${it.id}" },
        ) { playlist ->
            ProviderPlaylistCard(
                playlist = playlist,
                onClick = { onClick(playlist) },
                modifier = Modifier.width(cardWidth),
            )
        }
    }
}

@Composable
internal fun HomeMediaItemShelf(
    mediaItems: List<ProviderMediaItem>,
    onClick: (ProviderMediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val wide = LocalAppLayoutInfo.current.useWideLayout
    val cardWidth = if (wide) 156.dp else 140.dp
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (wide) FuoSpacing.md else FuoSpacing.lg),
    ) {
        items(
            items = mediaItems,
            key = { "${it.providerId}:${it.id}" },
        ) { item ->
            Column(
                modifier = Modifier
                    .width(cardWidth)
                    .fuoInteractive()
                    .clickable(role = Role.Button) { onClick(item) },
                verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
            ) {
                PlatformCoverArt(
                    title = item.title,
                    imageUrl = item.coverUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(
                            if (item.type == ProviderMediaItemType.Artist) {
                                CircleShape
                            } else {
                                FuoVisualTokens.artwork
                            },
                        ),
                    placeholder = when (item.type) {
                        ProviderMediaItemType.Artist -> CoverPlaceholder.Artist
                        ProviderMediaItemType.Album -> CoverPlaceholder.Album
                    },
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.providerName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun HomeVideoShelf(
    videos: List<ProviderVideo>,
    onClick: (ProviderVideo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val wide = LocalAppLayoutInfo.current.useWideLayout
    val cardWidth = if (wide) 280.dp else 236.dp
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (wide) FuoSpacing.md else FuoSpacing.lg),
    ) {
        items(
            items = videos,
            key = { "${it.providerId}:${it.id}" },
        ) { video ->
            Column(
                modifier = Modifier
                    .width(cardWidth)
                    .fuoInteractive()
                    .clickable(role = Role.Button) { onClick(video) },
                verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
            ) {
                PlatformCoverArt(
                    title = video.title,
                    imageUrl = video.coverUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(FuoVisualTokens.artwork),
                    placeholder = CoverPlaceholder.Song,
                )
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = video.artists.ifBlank { video.providerName },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
