package org.feeluown.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderContentHomeSection(
    controller: FuoPlayerController,
    section: HomeSection,
    modifier: Modifier,
) {
    val title = if (section == HomeSection.Recommend) "推荐" else "探索"
    val sections = controller.contentSectionsFor(section)
    val visibleSections = remember(sections) { sections.filterNot { it.isLoginRequired } }
    val lockedProviders = remember(sections) {
        sections.filter { it.isLoginRequired }
            .map { it.feature }
            .distinctBy { it.providerId }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PullToRefreshBox(
            isRefreshing = controller.isLoading,
            onRefresh = { controller.refreshHomeContent(section) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (sections.isEmpty()) {
                    item {
                        EmptyProviderContentHint(title)
                    }
                } else if (section == HomeSection.Music) {
                    if (visibleSections.isNotEmpty()) {
                        item(key = "header:explore") {
                            ProviderFeatureHeader(
                                feature = visibleSections.first().feature,
                                title = "探索",
                                providerLabel = visibleSections
                                    .map { it.feature.providerName }
                                    .distinct()
                                    .joinToString(" / "),
                            )
                        }
                        item(key = "explore-grid") {
                            ProviderFeatureCoverGrid(
                                features = visibleSections.map { it.feature },
                                onClick = controller::openFeature,
                            )
                        }
                    }
                } else {
                    val forYouSections = visibleSections.filter {
                        it.feature.isDailySongs() ||
                            it.feature.isPrivateFm() ||
                            it.feature.isBilibiliRecommendedVideos()
                    }
                    val otherSections = visibleSections.filterNot {
                        it.feature.isDailySongs() ||
                            it.feature.isPrivateFm() ||
                            it.feature.isBilibiliRecommendedVideos()
                    }
                    if (forYouSections.isNotEmpty()) {
                        item(key = "header:for-you") {
                            ProviderFeatureHeader(
                                feature = forYouSections.first().feature,
                                title = "为你推荐",
                                providerLabel = forYouSections
                                    .map { it.feature.providerName }
                                    .distinct()
                                    .joinToString(" / "),
                            )
                        }
                        item(key = "for-you-grid") {
                            ForYouRecommendGrid(
                                sections = forYouSections,
                                enabled = !controller.isLoading,
                                onFeatureClick = controller::openFeature,
                                onPrivateFmClick = { section -> controller.playAllFromFeature(section.feature.id) },
                            )
                        }
                    }
                    otherSections.forEach { contentSection ->
                        item(key = "header:${contentSection.feature.id}") {
                            ProviderFeatureHeader(
                                feature = contentSection.feature,
                                onPlayAll = if (contentSection.tracks.isNotEmpty()) {
                                    { controller.playAllFromFeature(contentSection.feature.id) }
                                } else {
                                    null
                                },
                            )
                        }
                        when {
                            contentSection.errorMessage != null -> item(key = "error:${contentSection.feature.id}") {
                                ProviderContentMessage(contentSection.errorMessage)
                            }
                            contentSection.tracks.isNotEmpty() -> {
                                itemsIndexed(
                                    contentSection.tracks,
                                    key = { _, item -> "${contentSection.feature.id}:${item.id}" },
                                ) { index, track ->
                                    TrackRow(
                                        track = track,
                                        downloadState = controller.downloadStates[track.id],
                                        onClick = { controller.playFromFeature(contentSection.feature.id, index) },
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
                            contentSection.playlists.isNotEmpty() -> {
                                item(key = "playlists:${contentSection.feature.id}") {
                                    ProviderPlaylistGrid(
                                        playlists = contentSection.playlists,
                                        onClick = { controller.openPlaylist(it, contentSection.feature.category) },
                                    )
                                }
                            }
                            contentSection.mediaItems.isNotEmpty() -> {
                                item(key = "media-items:${contentSection.feature.id}") {
                                    ProviderMediaItemGrid(
                                        items = contentSection.mediaItems,
                                        onClick = controller::openMediaItem,
                                    )
                                }
                            }
                            contentSection.videos.isNotEmpty() -> {
                                item(key = "videos:${contentSection.feature.id}") {
                                    ProviderVideoList(
                                        videos = contentSection.videos,
                                        onClick = controller::openVideo,
                                    )
                                }
                            }
                            else -> item(key = "empty:${contentSection.feature.id}") {
                                ProviderContentMessage("暂无内容")
                            }
                        }
                    }
                    if (lockedProviders.isNotEmpty()) {
                        item(key = "locked-providers:${section.name}") {
                            ProviderLockedSummary(
                                providers = lockedProviders,
                                onClick = { controller.openSettings(it.providerId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProviderVideoList(videos: List<ProviderVideo>, onClick: (ProviderVideo) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        videos.forEach { video ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick(video) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlatformCoverArt(
                    title = video.title,
                    imageUrl = video.coverUrl,
                    modifier = Modifier.size(48.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(video.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        video.artists.ifBlank { video.providerName },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Filled.PlayArrow, contentDescription = "播放视频")
            }
        }
    }
}

@Composable
fun ForYouRecommendGrid(
    sections: List<ProviderContentSection>,
    enabled: Boolean,
    onFeatureClick: (ProviderFeature) -> Unit,
    onPrivateFmClick: (ProviderContentSection) -> Unit,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val columns = layoutInfo.gridColumns
    val spacing = if (layoutInfo.useWideLayout) 8.dp else 12.dp
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        sections.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                row.forEach { section ->
                    when {
                        section.feature.isPrivateFm() -> {
                            PrivateFmButton(
                                section = section,
                                enabled = enabled,
                                onClick = { onPrivateFmClick(section) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        section.feature.isDailySongs() -> {
                            DailyRecommendationButton(
                                feature = section.feature,
                                enabled = enabled,
                                onClick = { onFeatureClick(section.feature) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        section.feature.isBilibiliRecommendedVideos() -> {
                            RecommendationEntryButton(
                                feature = section.feature,
                                enabled = enabled,
                                onClick = { onFeatureClick(section.feature) },
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

@Composable
fun ProviderFeatureCoverGrid(
    features: List<ProviderFeature>,
    onClick: (ProviderFeature) -> Unit,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val columns = layoutInfo.gridColumns
    val spacing = if (layoutInfo.useWideLayout) 8.dp else 12.dp
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        features.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                row.forEach { feature ->
                    ProviderFeatureCoverCard(
                        feature = feature,
                        onClick = { onClick(feature) },
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

@Composable
fun ProviderFeatureCoverCard(
    feature: ProviderFeature,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = if (isWideLayout) 2.dp else 6.dp),
    ) {
        CoverBox(
            track = feature.toDisplayTrack(),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(if (isWideLayout) 4.dp else 8.dp))
        Text(
            text = feature.title.ifBlank { "推荐" },
            style = MaterialTheme.typography.titleSmall,
            maxLines = if (isWideLayout) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = feature.providerName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PrivateFmGrid(
    sections: List<ProviderContentSection>,
    enabled: Boolean,
    onClick: (ProviderContentSection) -> Unit,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val columns = layoutInfo.gridColumns
    val spacing = if (layoutInfo.useWideLayout) 8.dp else 12.dp
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        sections.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                row.forEach { section ->
                    PrivateFmButton(
                        section = section,
                        enabled = enabled,
                        onClick = { onClick(section) },
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

@Composable
fun PrivateFmButton(
    section: ProviderContentSection,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    RecommendationButton(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = if (isWideLayout) 2.dp else 6.dp),
        title = "私人 FM",
        providerName = section.feature.providerName,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "播放${section.feature.providerName}私人 FM",
            modifier = Modifier.size(if (isWideLayout) 28.dp else 32.dp),
        )
    }
}

@Composable
fun DailyRecommendationButton(
    feature: ProviderFeature,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    RecommendationButton(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = if (isWideLayout) 2.dp else 6.dp),
        title = feature.title.ifBlank { "每日推荐" },
        providerName = feature.providerName,
    ) {
        Text(
            text = "Daily",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun RecommendationEntryButton(
    feature: ProviderFeature,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    RecommendationButton(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = if (isWideLayout) 2.dp else 6.dp),
        title = feature.title.ifBlank { "推荐视频" },
        providerName = feature.providerName,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "打开${feature.providerName}${feature.title}",
            modifier = Modifier.size(if (isWideLayout) 28.dp else 32.dp),
        )
    }
}

@Composable
fun RecommendationButton(
    title: String,
    providerName: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    Column(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(8.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                content()
            }
        }
        Spacer(Modifier.height(if (isWideLayout) 4.dp else 8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = if (isWideLayout) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = providerName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ProviderPlaylistGrid(
    playlists: List<ProviderPlaylist>,
    onClick: (ProviderPlaylist) -> Unit,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val columns = layoutInfo.gridColumns
    val spacing = if (layoutInfo.useWideLayout) 8.dp else 12.dp
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        playlists.chunked(columns).forEach { row ->
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

@Composable
fun ProviderPlaylistCard(
    playlist: ProviderPlaylist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = if (isWideLayout) 2.dp else 6.dp),
    ) {
        CoverBox(
            track = playlist.toDisplayTrack(),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
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
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val columns = layoutInfo.gridColumns
    val spacing = if (layoutInfo.useWideLayout) 8.dp else 12.dp
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        items.chunked(columns).forEachIndexed { rowIndex, row ->
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

@Composable
fun ProviderMediaItemCard(
    item: ProviderMediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = if (isWideLayout) 2.dp else 6.dp),
    ) {
        CoverBox(
            track = item.toDisplayTrack(),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
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

@Composable
fun ProviderFeatureHeader(
    feature: ProviderFeature,
    title: String = feature.title,
    providerLabel: String = feature.providerName,
    onPlayAll: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = providerLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onPlayAll != null) {
                PlayAllButton(onClick = onPlayAll)
            }
        }
    }
}

@Composable
fun PlayAllButton(onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text("播放全部")
    }
}

@Composable
fun ShareTextButton(payload: SharePayload?) {
    val onShare = LocalShareHandler.current
    TextButton(
        onClick = { if (payload != null) onShare(payload) },
        enabled = payload != null,
    ) {
        Icon(
            Icons.Filled.Share,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text("分享")
    }
}

@Composable
fun ProviderDetailHeader(
    track: MusicTrack,
    title: String,
    subtitle: String,
    description: String,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CoverBox(
            track = track,
            modifier = Modifier.size(112.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            action?.invoke()
        }
    }
}

fun ProviderFeature.isPrivateFm(): Boolean {
    return id.endsWith("_radio")
}

fun ProviderFeature.isDailySongs(): Boolean {
    return id.endsWith("_daily_songs")
}

fun ProviderFeature.isBilibiliRecommendedVideos(): Boolean {
    return providerId == "bilibili" && id == "bilibili_recommended_videos"
}

fun ProviderFeature.toDisplayTrack(): MusicTrack {
    return MusicTrack(
        id = id,
        title = title,
        artists = providerName,
        album = "",
        source = providerId,
        sourceType = TrackSourceType.Provider,
        providerName = providerName,
    )
}

fun ProviderPlaylist.toDisplayTrack(): MusicTrack {
    return MusicTrack(
        id = id,
        title = title,
        artists = providerName,
        album = "",
        source = providerId,
        sourceType = TrackSourceType.Provider,
        coverUrl = coverUrl,
        providerName = providerName,
        providerUrl = providerUrl,
    )
}

fun ProviderMediaItem.toDisplayTrack(): MusicTrack {
    return MusicTrack(
        id = id,
        title = title,
        artists = providerName,
        album = if (type == ProviderMediaItemType.Artist) "歌手" else "专辑",
        source = providerId,
        sourceType = TrackSourceType.Provider,
        coverUrl = coverUrl,
        providerName = providerName,
        providerUrl = providerUrl,
    )
}

@Composable
fun ProviderLockedSummary(providers: List<ProviderFeature>, onClick: (ProviderFeature) -> Unit) {
    val label = providers.joinToString("、") { it.providerName }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = "登录后显示 $label 的个性化内容",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = { providers.firstOrNull()?.let(onClick) }) {
            Text("登录")
        }
    }
}

@Composable
fun ProviderContentMessage(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            modifier = Modifier.padding(16.dp),
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EmptyProviderContentHint(title: String) {
    ProviderContentMessage("$title 暂无内容")
}
