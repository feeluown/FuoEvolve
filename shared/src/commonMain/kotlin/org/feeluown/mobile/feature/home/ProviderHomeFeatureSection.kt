package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderContentHomeFeatureSection(
    home: HomeFeatureController,
    section: HomeSection,
    modifier: Modifier,
) {
    val state = home.uiState.collectAsStateWithLifecycle().value
    val graph = LocalHomeFeatureUiGraph.current
    val title = if (section == HomeSection.Recommend) "推荐" else "探索"
    val sections = if (section == HomeSection.Recommend) state.recommendSections else state.exploreSections
    val visibleSections = remember(sections) { sections.filterNot { it.isLoginRequired } }
    val lockedProviders = remember(sections) {
        sections.filter { it.isLoginRequired }.map { it.feature }.distinctBy { it.providerId }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PullToRefreshBox(
            isRefreshing = state.isLoading,
            onRefresh = { home.refreshHome(section) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (sections.isEmpty()) {
                    item { EmptyProviderContentHint(title) }
                } else if (section == HomeSection.Music) {
                    val entrySections = visibleSections.filter {
                        it.feature.contentType == ProviderContentType.Songs ||
                            it.feature.contentType == ProviderContentType.Videos ||
                            it.feature.isBilibiliWeeklyMustWatch()
                    }
                    val previewSections = visibleSections.filter {
                        (it.feature.contentType == ProviderContentType.Playlists ||
                            it.feature.contentType == ProviderContentType.Artists ||
                            it.feature.contentType == ProviderContentType.Albums) &&
                            !it.feature.isBilibiliWeeklyMustWatch()
                    }
                    if (visibleSections.isNotEmpty()) {
                        item(key = "header:explore") {
                            ProviderFeatureHeader(
                                feature = visibleSections.first().feature,
                                title = "探索",
                                providerLabel = visibleSections.map { it.feature.providerName }.distinct().joinToString(" / "),
                            )
                        }
                    }
                    if (entrySections.isNotEmpty()) {
                        item(key = "explore-grid") {
                            ProviderFeatureCoverGrid(entrySections.map { it.feature }, home::openFeature)
                        }
                    }
                    previewSections.forEach { contentSection ->
                        item(key = "header:${contentSection.feature.id}") {
                            ProviderFeatureHeader(feature = contentSection.feature)
                        }
                        when {
                            contentSection.errorMessage != null -> item(key = "error:${contentSection.feature.id}") {
                                ProviderContentMessage(contentSection.errorMessage)
                            }
                            contentSection.playlists.isNotEmpty() -> item(key = "playlists:${contentSection.feature.id}") {
                                ProviderPlaylistGrid(
                                    playlists = contentSection.playlists,
                                    onClick = { home.openPlaylist(it, contentSection.feature.category) },
                                    onMore = { home.openFeature(contentSection.feature) },
                                    maxRows = 2,
                                )
                            }
                            contentSection.mediaItems.isNotEmpty() -> item(key = "media-items:${contentSection.feature.id}") {
                                ProviderMediaItemGrid(
                                    items = contentSection.mediaItems,
                                    onClick = home::openMediaItem,
                                    onMore = { home.openFeature(contentSection.feature) },
                                    maxRows = 2,
                                )
                            }
                            else -> item(key = "empty:${contentSection.feature.id}") { ProviderContentMessage("暂无内容") }
                        }
                    }
                } else {
                    val forYouSections = visibleSections.filter {
                        it.feature.isDailySongs() || it.feature.isPrivateFm() ||
                            it.feature.isBilibiliRecommendedVideos() || it.feature.isBilibiliDynamicVideos() ||
                            it.feature.isRecommendedNewSongs()
                    }
                    val otherSections = visibleSections.filterNot {
                        it.feature.isDailySongs() || it.feature.isPrivateFm() ||
                            it.feature.isBilibiliRecommendedVideos() || it.feature.isBilibiliDynamicVideos() ||
                            it.feature.isRecommendedNewSongs()
                    }
                    if (forYouSections.isNotEmpty()) {
                        item(key = "header:for-you") {
                            ProviderFeatureHeader(
                                feature = forYouSections.first().feature,
                                title = "为你推荐",
                                providerLabel = forYouSections.map { it.feature.providerName }.distinct().joinToString(" / "),
                            )
                        }
                        item(key = "for-you-grid") {
                            ForYouRecommendGrid(
                                sections = forYouSections,
                                enabled = !state.isLoading,
                                onFeatureClick = home::openFeature,
                                onPrivateFmClick = home::playAllFeature,
                            )
                        }
                    }
                    otherSections.forEach { contentSection ->
                        item(key = "header:${contentSection.feature.id}") {
                            ProviderFeatureHeader(
                                feature = contentSection.feature,
                                onPlayAll = contentSection.tracks.takeIf { it.isNotEmpty() }?.let {
                                    { home.playAllFeature(contentSection) }
                                },
                            )
                        }
                        when {
                            contentSection.errorMessage != null -> item(key = "error:${contentSection.feature.id}") {
                                ProviderContentMessage(contentSection.errorMessage)
                            }
                            contentSection.tracks.isNotEmpty() -> itemsIndexed(
                                contentSection.tracks,
                                key = { _, item -> "${contentSection.feature.id}:${item.id}" },
                            ) { index, track ->
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
                                HorizontalDivider()
                            }
                            contentSection.playlists.isNotEmpty() -> item(key = "playlists:${contentSection.feature.id}") {
                                ProviderPlaylistGrid(
                                    playlists = contentSection.playlists,
                                    onClick = { home.openPlaylist(it, contentSection.feature.category) },
                                    onMore = { home.openFeature(contentSection.feature) },
                                )
                            }
                            contentSection.mediaItems.isNotEmpty() -> item(key = "media-items:${contentSection.feature.id}") {
                                ProviderMediaItemGrid(contentSection.mediaItems, home::openMediaItem)
                            }
                            contentSection.videos.isNotEmpty() -> item(key = "videos:${contentSection.feature.id}") {
                                ProviderVideoList(contentSection.videos, home::openVideo)
                            }
                            else -> item(key = "empty:${contentSection.feature.id}") { ProviderContentMessage("暂无内容") }
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
}
