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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ProviderVideoList(videos: List<ProviderVideo>, onClick: (ProviderVideo) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        videos.forEach { video ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fuoInteractive()
                    .clickable(role = Role.Button) { onClick(video) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlatformCoverArt(
                    title = video.title,
                    imageUrl = video.coverUrl,
                    modifier = Modifier.size(48.dp),
                    placeholder = CoverPlaceholder.Song,
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
                        section.feature.isBilibiliRecommendedVideos() ||
                            section.feature.isBilibiliDynamicVideos() ||
                            section.feature.isRecommendedNewSongs() -> {
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
            .fuoInteractive()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = if (isWideLayout) 2.dp else 6.dp),
    ) {
        CoverBox(
            track = feature.toDisplayTrack(),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            placeholder = if (feature.isDailySongs()) {
                CoverPlaceholder.DailyRecommendation
            } else {
                CoverPlaceholder.Song
            },
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
            .fuoInteractive()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = if (isWideLayout) 2.dp else 6.dp),
        title = "私人 FM",
        providerName = section.feature.providerName,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Icon(
            imageVector = Icons.Filled.Radio,
            contentDescription = "播放${section.feature.providerName}私人 FM",
            modifier = Modifier.size(if (isWideLayout) 40.dp else 44.dp),
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
            .fuoInteractive()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = if (isWideLayout) 2.dp else 6.dp),
        title = feature.title.ifBlank { "每日推荐" },
        providerName = feature.providerName,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Icon(
            imageVector = Icons.Filled.CalendarMonth,
            contentDescription = "${feature.providerName}每日推荐",
            modifier = Modifier.size(if (isWideLayout) 40.dp else 44.dp),
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
            .fuoInteractive()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = if (isWideLayout) 2.dp else 6.dp),
        title = feature.title.ifBlank { "推荐视频" },
        providerName = feature.providerName,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        val icon = providerFeatureIcon(feature.id) ?: Icons.Filled.PlayArrow
        Icon(
            imageVector = icon,
            contentDescription = "打开${feature.providerName}${feature.title}",
            modifier = Modifier.size(if (isWideLayout) 40.dp else 44.dp),
        )
    }
}

@Composable
fun RecommendationButton(
    title: String,
    providerName: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    Column(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            color = containerColor,
            contentColor = contentColor,
            shape = MaterialTheme.shapes.medium,
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
