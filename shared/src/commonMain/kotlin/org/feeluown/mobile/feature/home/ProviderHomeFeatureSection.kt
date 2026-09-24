package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val HOME_TRACK_PREVIEW_LIMIT = 6
private const val HOME_SHELF_PREVIEW_LIMIT = 12

private data class HomeTrackPreviewEntry(
    val section: ProviderContentSection,
    val index: Int,
    val track: MusicTrack,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderContentHomeFeatureSection(
    home: HomeFeatureController,
    section: HomeSection,
    modifier: Modifier,
) {
    val state = home.uiState.collectAsStateWithLifecycle().value
    val graph = LocalHomeFeatureUiGraph.current
    val catalogState = graph.providerCatalog.uiState.collectAsStateWithLifecycle().value
    val layoutInfo = LocalAppLayoutInfo.current
    val gridColumns = layoutInfo.gridColumns.coerceAtLeast(1)
    val gridSpacing = if (layoutInfo.useWideLayout) FuoSpacing.md else FuoSpacing.lg
    val title = if (section == HomeSection.Recommend) "推荐" else "探索"
    val sections = if (section == HomeSection.Recommend) state.recommendSections else state.exploreSections
    val visibleSections = remember(sections) { sections.filterNot { it.isLoginRequired } }
    val lockedProviders = remember(sections) {
        sections.filter { it.isLoginRequired }.map { it.feature }.distinctBy { it.providerId }
    }
    var refreshRequested by remember(section) { mutableStateOf(false) }
    var recentTracks by remember(section) { mutableStateOf<List<ListeningResourceStat>>(emptyList()) }
    var initialLoadPending by remember(section) { mutableStateOf(sections.isEmpty()) }
    var initialLoadObserved by remember(section) { mutableStateOf(state.isLoading) }
    val isPullRefreshing = refreshRequested && state.isLoading
    val showPageLoading = initialLoadPending

    LaunchedEffect(graph.listeningHistory, section, state.recommendSections, catalogState.enabledProviderIds) {
        if (section == HomeSection.Recommend) {
            runCatching {
                graph.listeningHistory.recentResources(
                    range = ListeningTimeRange.All,
                    limit = HOME_SHELF_PREVIEW_LIMIT,
                    resourceType = ListeningResourceType.Track,
                ).filter { stat ->
                    stat.resource.sourceId in catalogState.enabledProviderIds
                }
            }.onSuccess { recentTracks = it }
        }
    }

    LaunchedEffect(state.isLoading, sections.isEmpty()) {
        if (sections.isNotEmpty()) initialLoadPending = false
        if (state.isLoading) {
            initialLoadObserved = true
        } else {
            if (initialLoadObserved) initialLoadPending = false
            refreshRequested = false
        }
    }

    val pageContent: @Composable () -> Unit = {
        PageLoadingContent(
            loading = showPageLoading,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
            ) {
                if (sections.isEmpty()) {
                    item(key = "empty:${section.name}") { EmptyProviderContentHint(title) }
                } else {
                    item(key = "intro:${section.name}") { RefinedHomeIntro(section) }

                    if (section == HomeSection.Recommend) {
                        if (recentTracks.isNotEmpty() && visibleSections.isNotEmpty()) {
                            item(key = "header:continue-listening") {
                                ProviderFeatureHeader(
                                    feature = visibleSections.first().feature,
                                    title = "继续听",
                                    providerLabel = "来自播放记录",
                                    action = home::openPlaybackHistory,
                                    actionLabel = "查看记录",
                                )
                            }
                            item(key = "shelf:continue-listening") {
                                HomeRecentTrackShelf(
                                    resources = recentTracks,
                                    onClick = { resource ->
                                        graph.playbackQueue.playTracks(
                                            tracks = listOf(
                                                resource.toHomeHistoryTrack(
                                                    catalogState.providers
                                                        .firstOrNull { it.providerId == resource.sourceId }
                                                        ?.providerName,
                                                ),
                                            ),
                                            index = 0,
                                        )
                                    },
                                )
                            }
                        }

                        val actionSections = visibleSections
                            .filter {
                                it.homeContentRole() == HomeContentRole.DailyRecommendation ||
                                    it.homeContentRole() == HomeContentRole.PersonalRadio ||
                                    it.homeContentRole() == HomeContentRole.RecommendedContent
                            }
                            .homeActionSections()
                        val newMusicSections = visibleSections.filter {
                            it.homeContentRole() == HomeContentRole.NewMusic && it.tracks.isNotEmpty()
                        }
                        val playlistSections = visibleSections.filter {
                            it.homeContentRole() == HomeContentRole.PlaylistDiscovery && it.playlists.isNotEmpty()
                        }
                        val videoSections = visibleSections.filter { it.videos.isNotEmpty() }
                        val handledFeatureIds = buildSet {
                            actionSections.forEach { add(it.feature.id) }
                            newMusicSections.forEach { add(it.feature.id) }
                            playlistSections.forEach { add(it.feature.id) }
                            videoSections.forEach { add(it.feature.id) }
                        }

                        if (actionSections.isNotEmpty()) {
                            item(key = "header:listen-now") {
                                ProviderFeatureHeader(
                                    feature = actionSections.first().feature,
                                    title = "现在就听",
                                    providerLabel = actionSections.homeProviderLabel(),
                                )
                            }
                            item(key = "listen-now-grid") {
                                ForYouRecommendGrid(
                                    sections = actionSections,
                                    enabled = !state.isLoading,
                                    onFeatureClick = home::openFeature,
                                    onPrivateFmClick = home::playAllFeature,
                                )
                            }
                        }

                        val newMusicPreview = newMusicSections.toTrackPreview()
                        if (newMusicPreview.isNotEmpty()) {
                            item(key = "header:new-music") {
                                ProviderFeatureHeader(
                                    feature = newMusicSections.first().feature,
                                    title = "发现一些新歌",
                                    providerLabel = newMusicSections.homeProviderLabel(),
                                    onPlayAll = newMusicSections.singleOrNull()?.let { only ->
                                        { home.playAllFeature(only) }
                                    },
                                    action = newMusicSections.singleOrNull()
                                        ?.takeIf { it.hasMore }
                                        ?.let { only -> { home.openFeature(only.feature) } },
                                    actionLabel = "查看更多",
                                )
                            }
                            items(
                                items = newMusicPreview,
                                key = { "${it.section.feature.id}:${it.track.id}" },
                            ) { entry ->
                                TrackRow(
                                    track = entry.track,
                                    downloadState = graph.downloads.downloadStates[entry.track.id],
                                    onClick = { home.playFeature(entry.section, entry.index) },
                                    onAddToUpNext = { graph.playbackQueue.addToUpNext(entry.track) },
                                    onDownload = { graph.downloads.download(entry.track) },
                                    onDeleteDownload = { graph.downloads.deleteDownload(entry.track) },
                                    onOpenArtist = { graph.providerTrackActions.openTrackArtist(entry.track) },
                                    onOpenAlbum = { graph.providerTrackActions.openTrackAlbum(entry.track) },
                                    onOpenDetail = { graph.providerTrackActions.openOriginalTrackDetail(entry.track) },
                                    onAddToPlaylist = if (graph.playlists.canAddTrackToPlaylist(entry.track)) {
                                        { graph.playlists.openPlaylistTargetPicker(entry.track) }
                                    } else null,
                                )
                            }
                        }

                        val recommendedPlaylists = playlistSections
                            .flatMap { it.playlists }
                            .distinctBy { "${it.providerId}:${it.id}" }
                            .take(HOME_SHELF_PREVIEW_LIMIT)
                        if (recommendedPlaylists.isNotEmpty()) {
                            item(key = "header:recommended-playlists") {
                                ProviderFeatureHeader(
                                    feature = playlistSections.first().feature,
                                    title = "可能喜欢的歌单",
                                    providerLabel = playlistSections.homeProviderLabel(),
                                    action = playlistSections.singleOrNull()
                                        ?.takeIf { it.hasMore }
                                        ?.let { only -> { home.openFeature(only.feature) } },
                                    actionLabel = "查看更多",
                                )
                            }
                            item(key = "shelf:recommended-playlists") {
                                HomePlaylistShelf(
                                    playlists = recommendedPlaylists,
                                    onClick = { home.openPlaylist(it, ProviderFeatureCategory.Recommend) },
                                )
                            }
                        }

                        val recommendedVideos = videoSections
                            .flatMap { it.videos }
                            .distinctBy { "${it.providerId}:${it.id}" }
                            .take(HOME_SHELF_PREVIEW_LIMIT)
                        if (recommendedVideos.isNotEmpty()) {
                            item(key = "header:recommended-videos") {
                                ProviderFeatureHeader(
                                    feature = videoSections.first().feature,
                                    title = "视频推荐",
                                    providerLabel = videoSections.homeProviderLabel(),
                                    action = videoSections.singleOrNull()
                                        ?.takeIf { it.hasMore }
                                        ?.let { only -> { home.openFeature(only.feature) } },
                                    actionLabel = "查看更多",
                                )
                            }
                            item(key = "shelf:recommended-videos") {
                                HomeVideoShelf(
                                    videos = recommendedVideos,
                                    onClick = home::openVideo,
                                )
                            }
                        }

                        visibleSections
                            .filterNot { it.feature.id in handledFeatureIds }
                            .forEach { contentSection ->
                                addHomeFallbackSection(
                                    contentSection = contentSection,
                                    home = home,
                                    graph = graph,
                                    category = ProviderFeatureCategory.Recommend,
                                )
                            }
                    } else {
                        val orderedSections = visibleSections.homeExploreEntries()
                        if (orderedSections.isNotEmpty()) {
                            item(key = "header:explore-entries") {
                                ProviderFeatureHeader(
                                    feature = orderedSections.first().feature,
                                    title = "探索入口",
                                    providerLabel = orderedSections.homeProviderLabel(),
                                )
                            }
                            addProviderFeatureCoverRows(
                                features = orderedSections.map { it.feature },
                                columns = gridColumns,
                                spacing = gridSpacing,
                                keyPrefix = "explore-entry-grid",
                                onClick = home::openFeature,
                            )
                        }

                        val charts = visibleSections.filter {
                            it.homeContentRole() == HomeContentRole.Charts && it.playlists.isNotEmpty()
                        }
                        val chartPlaylists = charts
                            .flatMap { it.playlists }
                            .distinctBy { "${it.providerId}:${it.id}" }
                            .take(HOME_SHELF_PREVIEW_LIMIT)
                        if (chartPlaylists.isNotEmpty()) {
                            item(key = "header:charts") {
                                ProviderFeatureHeader(
                                    feature = charts.first().feature,
                                    title = "热门榜单",
                                    providerLabel = charts.homeProviderLabel(),
                                )
                            }
                            item(key = "shelf:charts") {
                                HomePlaylistShelf(
                                    playlists = chartPlaylists,
                                    onClick = { home.openPlaylist(it, ProviderFeatureCategory.Music) },
                                )
                            }
                        }

                        val playlistDiscovery = visibleSections.filter {
                            it.homeContentRole() == HomeContentRole.PlaylistDiscovery && it.playlists.isNotEmpty()
                        }
                        val discoveryPlaylists = playlistDiscovery
                            .flatMap { it.playlists }
                            .distinctBy { "${it.providerId}:${it.id}" }
                            .take(HOME_SHELF_PREVIEW_LIMIT)
                        if (discoveryPlaylists.isNotEmpty()) {
                            item(key = "header:playlist-discovery") {
                                ProviderFeatureHeader(
                                    feature = playlistDiscovery.first().feature,
                                    title = "精选歌单",
                                    providerLabel = playlistDiscovery.homeProviderLabel(),
                                )
                            }
                            item(key = "shelf:playlist-discovery") {
                                HomePlaylistShelf(
                                    playlists = discoveryPlaylists,
                                    onClick = { home.openPlaylist(it, ProviderFeatureCategory.Music) },
                                )
                            }
                        }

                        val newAlbums = visibleSections.filter {
                            it.homeContentRole() == HomeContentRole.NewAlbums && it.mediaItems.isNotEmpty()
                        }
                        val albumItems = newAlbums
                            .flatMap { it.mediaItems }
                            .distinctBy { "${it.providerId}:${it.id}" }
                            .take(HOME_SHELF_PREVIEW_LIMIT)
                        if (albumItems.isNotEmpty()) {
                            item(key = "header:new-albums") {
                                ProviderFeatureHeader(
                                    feature = newAlbums.first().feature,
                                    title = "新碟上架",
                                    providerLabel = newAlbums.homeProviderLabel(),
                                )
                            }
                            item(key = "shelf:new-albums") {
                                HomeMediaItemShelf(
                                    mediaItems = albumItems,
                                    onClick = home::openMediaItem,
                                )
                            }
                        }

                        val artists = visibleSections.filter {
                            it.homeContentRole() == HomeContentRole.Artists && it.mediaItems.isNotEmpty()
                        }
                        val artistItems = artists
                            .flatMap { it.mediaItems }
                            .distinctBy { "${it.providerId}:${it.id}" }
                            .take(HOME_SHELF_PREVIEW_LIMIT)
                        if (artistItems.isNotEmpty()) {
                            item(key = "header:artists") {
                                ProviderFeatureHeader(
                                    feature = artists.first().feature,
                                    title = "热门歌手",
                                    providerLabel = artists.homeProviderLabel(),
                                )
                            }
                            item(key = "shelf:artists") {
                                HomeMediaItemShelf(
                                    mediaItems = artistItems,
                                    onClick = home::openMediaItem,
                                )
                            }
                        }
                    }
                }

                if (lockedProviders.isNotEmpty()) {
                    item(key = "locked-providers:${section.name}") {
                        ProviderLockedSummary(lockedProviders) { home.openSettings(it.providerId) }
                    }
                }
            }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FuoSpacing.md)) {
        if (layoutInfo.useWideLayout) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { pageContent() }
        } else {
            PullToRefreshBox(
                isRefreshing = isPullRefreshing,
                onRefresh = {
                    refreshRequested = true
                    home.refreshHome(section)
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                pageContent()
            }
        }
    }
}

private fun List<ProviderContentSection>.toTrackPreview(): List<HomeTrackPreviewEntry> = buildList {
    for (section in this@toTrackPreview) {
        for ((index, track) in section.tracks.withIndex()) {
            add(HomeTrackPreviewEntry(section, index, track))
            if (size >= HOME_TRACK_PREVIEW_LIMIT) return@buildList
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.addHomeFallbackSection(
    contentSection: ProviderContentSection,
    home: HomeFeatureController,
    graph: HomeFeatureUiGraph,
    category: ProviderFeatureCategory,
) {
    val errorMessage = contentSection.errorMessage
    item(key = "header:fallback:${contentSection.feature.id}") {
        ProviderFeatureHeader(
            feature = contentSection.feature,
            onPlayAll = contentSection.tracks.takeIf { it.isNotEmpty() }?.let {
                { home.playAllFeature(contentSection) }
            },
            action = contentSection.takeIf { it.hasMore }?.let {
                { home.openFeature(contentSection.feature) }
            },
            actionLabel = "查看更多",
        )
    }
    when {
        errorMessage != null -> item(key = "error:fallback:${contentSection.feature.id}") {
            ProviderContentMessage(errorMessage)
        }
        contentSection.tracks.isNotEmpty() -> {
            val entries = contentSection.tracks.take(HOME_TRACK_PREVIEW_LIMIT)
            items(
                items = entries,
                key = { "${contentSection.feature.id}:${it.id}" },
            ) { track ->
                val index = contentSection.tracks.indexOf(track)
                TrackRow(
                    track = track,
                    downloadState = graph.downloads.downloadStates[track.id],
                    onClick = { home.playFeature(contentSection, index) },
                    onAddToUpNext = { graph.playbackQueue.addToUpNext(track) },
                    onDownload = { graph.downloads.download(track) },
                    onDeleteDownload = { graph.downloads.deleteDownload(track) },
                    onOpenArtist = { graph.providerTrackActions.openTrackArtist(track) },
                    onOpenAlbum = { graph.providerTrackActions.openTrackAlbum(track) },
                    onOpenDetail = { graph.providerTrackActions.openOriginalTrackDetail(track) },
                    onAddToPlaylist = if (graph.playlists.canAddTrackToPlaylist(track)) {
                        { graph.playlists.openPlaylistTargetPicker(track) }
                    } else null,
                )
            }
        }
        contentSection.playlists.isNotEmpty() -> item(key = "shelf:fallback:playlists:${contentSection.feature.id}") {
            HomePlaylistShelf(
                playlists = contentSection.playlists.take(HOME_SHELF_PREVIEW_LIMIT),
                onClick = { home.openPlaylist(it, category) },
            )
        }
        contentSection.mediaItems.isNotEmpty() -> item(key = "shelf:fallback:media:${contentSection.feature.id}") {
            HomeMediaItemShelf(
                mediaItems = contentSection.mediaItems.take(HOME_SHELF_PREVIEW_LIMIT),
                onClick = home::openMediaItem,
            )
        }
        contentSection.videos.isNotEmpty() -> item(key = "shelf:fallback:videos:${contentSection.feature.id}") {
            HomeVideoShelf(
                videos = contentSection.videos.take(HOME_SHELF_PREVIEW_LIMIT),
                onClick = home::openVideo,
            )
        }
        else -> item(key = "empty:fallback:${contentSection.feature.id}") {
            ProviderContentMessage("暂无内容")
        }
    }
}


private fun ListeningResourceSnapshot.toHomeHistoryTrack(providerDisplayName: String?): MusicTrack = MusicTrack(
    id = sourceResourceId,
    title = title,
    artists = subtitle,
    album = "",
    source = sourceId,
    sourceType = TrackSourceType.Provider,
    coverUrl = coverUrl,
    providerId = sourceId,
    providerName = providerDisplayName,
)
