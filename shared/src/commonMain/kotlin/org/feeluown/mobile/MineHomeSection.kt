package org.feeluown.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MineHomeSection(
    controller: FuoPlayerController,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    modifier: Modifier,
) {
    val isWideLayout = LocalAppLayoutInfo.current.useWideLayout
    val refreshMineContent = {
        when (controller.mineSection) {
            MineSection.Playlists -> controller.refreshMinePlaylistContent()
            MineSection.Artists,
            MineSection.Albums -> controller.refreshMineContent()
            MineSection.LocalMusic -> if (hasAudioPermission) controller.refreshLocalMusic() else Unit
        }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (isWideLayout) 6.dp else 12.dp),
    ) {
        MineSectionChips(
            controller = controller,
            includeSecondary = isWideLayout,
        )
        PullToRefreshBox(
            isRefreshing = controller.isLoading,
            onRefresh = refreshMineContent,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (controller.mineSection) {
                MineSection.Playlists -> MinePlaylistsSection(
                    controller = controller,
                    showFilter = !isWideLayout,
                    modifier = Modifier.fillMaxSize(),
                )
                MineSection.Artists -> MineMediaItemsSection(
                    controller = controller,
                    contentType = ProviderContentType.Artists,
                    title = "歌手",
                    modifier = Modifier.fillMaxSize(),
                )
                MineSection.Albums -> MineMediaItemsSection(
                    controller = controller,
                    contentType = ProviderContentType.Albums,
                    title = "专辑",
                    modifier = Modifier.fillMaxSize(),
                )
                MineSection.LocalMusic -> LocalMusicSection(
                    controller = controller,
                    hasAudioPermission = hasAudioPermission,
                    onRequestAudioPermission = onRequestAudioPermission,
                    showModeFilter = !isWideLayout,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
@Composable
fun MineSectionChips(
    controller: FuoPlayerController,
    includeSecondary: Boolean,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompactFilterChip(
            selected = controller.mineSection == MineSection.Playlists,
            onClick = { controller.onMineSectionChange(MineSection.Playlists) },
            label = "歌单",
        )
        CompactFilterChip(
            selected = controller.mineSection == MineSection.Artists,
            onClick = { controller.onMineSectionChange(MineSection.Artists) },
            label = "歌手",
        )
        CompactFilterChip(
            selected = controller.mineSection == MineSection.Albums,
            onClick = { controller.onMineSectionChange(MineSection.Albums) },
            label = "专辑",
        )
        CompactFilterChip(
            selected = controller.mineSection == MineSection.LocalMusic,
            onClick = { controller.onMineSectionChange(MineSection.LocalMusic) },
            label = "本地",
        )
        if (includeSecondary) {
            when (controller.mineSection) {
                MineSection.Playlists -> {
                    Spacer(Modifier.width(12.dp))
                    PlaylistFilterChips(controller)
                }
                MineSection.LocalMusic -> {
                    Spacer(Modifier.width(12.dp))
                    LocalMusicViewModeTabs(controller)
                }
                MineSection.Artists,
                MineSection.Albums -> Unit
            }
        }
    }
}

@Composable
fun CompactFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = Modifier.height(32.dp),
        shape = CircleShape,
    )
}

@Composable
fun MinePlaylistsSection(
    controller: FuoPlayerController,
    showFilter: Boolean,
    modifier: Modifier,
) {
    val userSections = controller.minePlaylistSections
    val favoriteSections = controller.mineFavoritePlaylistSections
    val sections = when (controller.playlistFilter) {
        PlaylistFilter.All -> userSections + favoriteSections
        PlaylistFilter.UserPlaylists -> userSections
        PlaylistFilter.FavoritePlaylists -> favoriteSections
    }
    val visibleSections = remember(sections) { sections.filterNot { it.isLoginRequired } }
    val lockedProviders = remember(sections) {
        sections.filter { it.isLoginRequired }
            .map { it.feature }
            .distinctBy { it.providerId }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showFilter) {
            PlaylistFilterChips(controller)
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (sections.isEmpty()) {
                item {
                    EmptyProviderContentHint(controller.playlistFilter.emptyTitle())
                }
            } else {
                visibleSections.forEach { contentSection ->
                    playlistSectionItems(controller, contentSection)
                }
                if (lockedProviders.isNotEmpty()) {
                    item(key = "locked-providers:mine-playlists") {
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

@Composable
fun PlaylistFilterChips(controller: FuoPlayerController) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompactFilterChip(
            selected = controller.playlistFilter == PlaylistFilter.All,
            onClick = { controller.onPlaylistFilterChange(PlaylistFilter.All) },
            label = "全部",
        )
        CompactFilterChip(
            selected = controller.playlistFilter == PlaylistFilter.UserPlaylists,
            onClick = { controller.onPlaylistFilterChange(PlaylistFilter.UserPlaylists) },
            label = "用户",
        )
        CompactFilterChip(
            selected = controller.playlistFilter == PlaylistFilter.FavoritePlaylists,
            onClick = { controller.onPlaylistFilterChange(PlaylistFilter.FavoritePlaylists) },
            label = "收藏",
        )
    }
}

fun PlaylistFilter.emptyTitle(): String {
    return when (this) {
        PlaylistFilter.All -> "歌单"
        PlaylistFilter.UserPlaylists -> "用户歌单"
        PlaylistFilter.FavoritePlaylists -> "收藏歌单"
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.playlistSectionItems(
    controller: FuoPlayerController,
    contentSection: ProviderContentSection,
) {
    item(key = "header:${contentSection.feature.id}") {
        ProviderFeatureHeader(feature = contentSection.feature)
    }
    when {
        contentSection.errorMessage != null -> item(key = "error:${contentSection.feature.id}") {
            ProviderContentMessage(contentSection.errorMessage)
        }
        contentSection.playlists.isNotEmpty() -> {
            item(key = "playlists:${contentSection.feature.id}") {
                ProviderPlaylistGrid(
                    playlists = contentSection.playlists,
                    onClick = controller::openPlaylist,
                )
            }
        }
        else -> item(key = "empty:${contentSection.feature.id}") {
            ProviderContentMessage("暂无内容")
        }
    }
}

@Composable
fun MineMediaItemsSection(
    controller: FuoPlayerController,
    contentType: ProviderContentType,
    title: String,
    modifier: Modifier,
) {
    val sections = controller.mineSections.filter { it.feature.contentType == contentType }
    val visibleSections = remember(sections) {
        sections.filterNot { it.isLoginRequired }
    }
    val lockedProviders = remember(sections) {
        sections.filter { it.isLoginRequired }
            .map { it.feature }
            .distinctBy { it.providerId }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (sections.isEmpty()) {
            item {
                EmptyProviderContentHint(title)
            }
        } else {
            visibleSections.forEach { contentSection ->
                item(key = "header:${contentSection.feature.id}") {
                    ProviderFeatureHeader(feature = contentSection.feature)
                }
                when {
                    contentSection.errorMessage != null -> item(key = "error:${contentSection.feature.id}") {
                        ProviderContentMessage(contentSection.errorMessage)
                    }
                    contentSection.mediaItems.isNotEmpty() -> {
                        item(key = "media-items:${contentSection.feature.id}") {
                            ProviderMediaItemGrid(
                                items = contentSection.mediaItems,
                                onClick = controller::openMediaItem,
                            )
                        }
                    }
                    else -> item(key = "empty:${contentSection.feature.id}") {
                        ProviderContentMessage("暂无内容")
                    }
                }
            }
            if (lockedProviders.isNotEmpty()) {
                item(key = "locked-providers:${contentType.name}") {
                    ProviderLockedSummary(
                        providers = lockedProviders,
                        onClick = { controller.openSettings(it.providerId) },
                    )
                }
            }
        }
    }
}
