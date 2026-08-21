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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
            if (action != null) {
                TextButton(onClick = action) { Text(actionLabel) }
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
    placeholder: CoverPlaceholder = CoverPlaceholder.Song,
    action: (@Composable () -> Unit)? = null,
) {
    val descriptionExpanded = remember(description) { mutableStateOf(false) }
    val descriptionOverflows = remember(description) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            CoverBox(
                track = track,
                modifier = Modifier.size(112.dp),
                placeholder = placeholder,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
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
        if (description.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (descriptionExpanded.value) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { result ->
                        if (!descriptionExpanded.value) {
                            descriptionOverflows.value = result.hasVisualOverflow
                        }
                    },
                )
                if (descriptionOverflows.value || descriptionExpanded.value) {
                    TextButton(
                        onClick = { descriptionExpanded.value = !descriptionExpanded.value },
                    ) {
                        Text(if (descriptionExpanded.value) "收起简介" else "展开简介")
                    }
                }
            }
        }
        if (action != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                action()
            }
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

fun ProviderFeature.isBilibiliDynamicVideos(): Boolean {
    return providerId == "bilibili" && id == "bilibili_dynamic_videos"
}

fun ProviderFeature.isBilibiliWeeklyMustWatch(): Boolean {
    return providerId == "bilibili" && id.substringBefore('|') == "bilibili_weekly_must_watch"
}

fun ProviderFeature.isRecommendedNewSongs(): Boolean {
    return providerId == "netease" && id == "netease_recommended_new_songs"
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
