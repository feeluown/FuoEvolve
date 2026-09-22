package org.feeluown.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shared editorial heading for recommendation, collection and library sections. */
@Composable
fun ProviderFeatureHeader(
    feature: ProviderFeature,
    title: String = feature.title,
    providerLabel: String = feature.providerName,
    onPlayAll: (() -> Unit)? = null,
    action: (() -> Unit)? = null,
    actionLabel: String = "",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = FuoSpacing.lg, bottom = FuoSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.xs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (providerLabel.isNotBlank()) {
                Text(
                    text = providerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onPlayAll != null) PlayAllButton(onClick = onPlayAll)
        if (action != null) TextButton(onClick = action) { Text(actionLabel) }
    }
}

@Composable
fun PlayAllButton(onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(FuoSpacing.xs))
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
        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(FuoSpacing.xs))
        Text("分享")
    }
}

/** One detail header for provider playlists, artists, albums and other collection pages. */
@Composable
internal fun ProviderDetailHeader(
    track: MusicTrack,
    title: String,
    subtitle: String,
    description: String,
    placeholder: CoverPlaceholder = CoverPlaceholder.Song,
    heroKey: ResourceCoverHeroKey? = null,
    stacked: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val descriptionExpanded = remember(description) { mutableStateOf(false) }
    val descriptionOverflows = remember(description) { mutableStateOf(false) }
    val resolvedHeroKey = heroKey ?: track.detailCoverHeroKey(placeholder)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = if (resolvedHeroKey != null) FuoSpacing.xl else FuoSpacing.sm,
                bottom = FuoSpacing.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(FuoSpacing.lg),
    ) {
        if (stacked) {
            CoverBox(
                track = track,
                cornerRadius = 12.dp,
                modifier = Modifier.size(192.dp).fuoNavigationHero(resolvedHeroKey),
                placeholder = placeholder,
            )
            Column(verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FuoSpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverBox(
                    track = track,
                    cornerRadius = 12.dp,
                    modifier = Modifier.size(120.dp).fuoNavigationHero(resolvedHeroKey),
                    placeholder = placeholder,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (description.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = FuoSurfaceColors.section(MaterialTheme.colorScheme),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = FuoVisualTokens.content,
                tonalElevation = FuoElevation.content,
            ) {
                Column(
                    modifier = Modifier.padding(FuoSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(FuoSpacing.xs),
                ) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (descriptionExpanded.value) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { result ->
                            if (!descriptionExpanded.value) descriptionOverflows.value = result.hasVisualOverflow
                        },
                    )
                    if (descriptionOverflows.value || descriptionExpanded.value) {
                        TextButton(onClick = { descriptionExpanded.value = !descriptionExpanded.value }) {
                            Text(if (descriptionExpanded.value) "收起简介" else "展开简介")
                        }
                    }
                }
            }
        }
        if (action != null) {
            Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                action()
            }
        }
    }
}

fun ProviderFeature.isPrivateFm(): Boolean = id.endsWith("_radio")

fun ProviderFeature.isDailySongs(): Boolean = id.endsWith("_daily_songs")

fun ProviderFeature.isBilibiliRecommendedVideos(): Boolean =
    providerId == "bilibili" && id == "bilibili_recommended_videos"

fun ProviderFeature.isBilibiliDynamicVideos(): Boolean =
    providerId == "bilibili" && id == "bilibili_dynamic_videos"

fun ProviderFeature.isBilibiliWeeklyMustWatch(): Boolean =
    providerId == "bilibili" && id.substringBefore('|') == "bilibili_weekly_must_watch"

fun ProviderFeature.isRecommendedNewSongs(): Boolean =
    providerId == "netease" && id == "netease_recommended_new_songs"

fun ProviderFeature.toDisplayTrack(): MusicTrack = MusicTrack(
    id = id,
    title = title,
    artists = providerName,
    album = "",
    source = providerId,
    sourceType = TrackSourceType.Provider,
    providerName = providerName,
)

fun ProviderPlaylist.toDisplayTrack(): MusicTrack = MusicTrack(
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

fun ProviderMediaItem.toDisplayTrack(): MusicTrack = MusicTrack(
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

@Composable
fun ProviderLockedSummary(providers: List<ProviderFeature>, onClick: (ProviderFeature) -> Unit) {
    val label = providers.joinToString("、") { it.providerName }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = FuoSpacing.sm),
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
        TextButton(onClick = { providers.firstOrNull()?.let(onClick) }) { Text("登录") }
    }
}

@Composable
fun ProviderContentMessage(message: String) {
    FuoSectionCard(modifier = Modifier.fillMaxWidth()) {
        Text(
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
