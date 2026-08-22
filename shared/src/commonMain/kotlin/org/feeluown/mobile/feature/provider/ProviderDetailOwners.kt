package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import org.feeluown.mobile.feature.providerdetail.ProviderDetailMutationResult
import org.feeluown.mobile.feature.providerdetail.ProviderFeatureDetailFeatureOwner as CoreFeatureOwner
import org.feeluown.mobile.feature.providerdetail.ProviderFeatureDetailFeatureState as CoreFeatureState
import org.feeluown.mobile.feature.providerdetail.ProviderFeatureDetailPort as CoreFeaturePort
import org.feeluown.mobile.feature.providerdetail.ProviderMediaItemDetailFeatureOwner as CoreMediaItemOwner
import org.feeluown.mobile.feature.providerdetail.ProviderMediaItemDetailFeatureState as CoreMediaItemState
import org.feeluown.mobile.feature.providerdetail.ProviderMediaItemDetailPage as CoreMediaItemPage
import org.feeluown.mobile.feature.providerdetail.ProviderMediaItemDetailPort as CoreMediaItemPort
import org.feeluown.mobile.feature.providerdetail.ProviderPlaylistDetailFeatureOwner as CorePlaylistOwner
import org.feeluown.mobile.feature.providerdetail.ProviderPlaylistDetailFeatureState as CorePlaylistState
import org.feeluown.mobile.feature.providerdetail.ProviderPlaylistDetailPage as CorePlaylistPage
import org.feeluown.mobile.feature.providerdetail.ProviderPlaylistDetailPort as CorePlaylistPort
import org.feeluown.mobile.feature.providerdetail.ProviderTrackDetailFeatureOwner as CoreTrackOwner
import org.feeluown.mobile.feature.providerdetail.ProviderTrackDetailFeatureState as CoreTrackState
import org.feeluown.mobile.feature.providerdetail.ProviderTrackDetailPort as CoreTrackPort
import org.feeluown.mobile.feature.providerdetail.ProviderVideoDetailFeatureOwner as CoreVideoOwner
import org.feeluown.mobile.feature.providerdetail.ProviderVideoDetailFeatureState as CoreVideoState
import org.feeluown.mobile.feature.providerdetail.ProviderVideoDetailPort as CoreVideoPort
import org.feeluown.mobile.feature.providerdetail.ProviderVideoPlaybackResult
import org.feeluown.mobile.feature.providerdetail.createProviderFeatureDetailFeatureOwner
import org.feeluown.mobile.feature.providerdetail.createProviderMediaItemDetailFeatureOwner
import org.feeluown.mobile.feature.providerdetail.createProviderPlaylistDetailFeatureOwner
import org.feeluown.mobile.feature.providerdetail.createProviderTrackDetailFeatureOwner
import org.feeluown.mobile.feature.providerdetail.createProviderVideoDetailFeatureOwner
import org.feeluown.mobile.provider.core.network.currentTimeMillis

private const val MAX_PROVIDER_PLAYLIST_PLAYBACK_STATS = 500
private const val PROVIDER_PLAYLIST_PLAYBACK_STATS_VERSION = 1

data class ProviderFeatureDetailUiState(
    val feature: ProviderFeature? = null,
    val content: ProviderContentSection? = null,
    val tracks: List<MusicTrack> = emptyList(),
    val nextOffset: Int = 0,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

interface ProviderFeatureDetailController {
    val uiState: StateFlow<ProviderFeatureDetailUiState>
    fun open(feature: ProviderFeature)
    fun activate(feature: ProviderFeature)
    fun close()
    fun refresh()
    fun loadMore()
    fun prefetchIfNeeded(visibleIndex: Int)
    fun play(index: Int)
    fun playAll()
}

data class ProviderPlaylistDetailUiState(
    val playlist: ProviderPlaylist? = null,
    val category: ProviderFeatureCategory? = null,
    val tracks: List<MusicTrack> = emptyList(),
    val nextOffset: Int = 0,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

interface ProviderPlaylistDetailController {
    val uiState: StateFlow<ProviderPlaylistDetailUiState>
    fun open(playlist: ProviderPlaylist, category: ProviderFeatureCategory? = null)
    fun activate(playlist: ProviderPlaylist, category: ProviderFeatureCategory? = null)
    fun close()
    fun refresh()
    fun loadMore()
    fun prefetchIfNeeded(visibleIndex: Int)
    fun play(index: Int)
    fun playAll()
    fun canRemove(track: MusicTrack): Boolean
    fun remove(track: MusicTrack)
    fun canDelete(): Boolean
    fun delete()
}

data class ProviderTrackDetailUiState(
    val track: MusicTrack? = null,
    val similarTracks: List<MusicTrack> = emptyList(),
    val comments: List<ProviderComment> = emptyList(),
    val video: ProviderVideo? = null,
    val relatedErrorMessage: String? = null,
    val isLoading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

interface ProviderTrackDetailController {
    val uiState: StateFlow<ProviderTrackDetailUiState>
    fun open(track: MusicTrack)
    fun activate(track: MusicTrack)
    fun close()
    fun refresh()
    fun play()
    fun playSimilar(index: Int)
    fun openVideo()
}

data class ProviderMediaItemDetailUiState(
    val item: ProviderMediaItem? = null,
    val tracks: List<MusicTrack> = emptyList(),
    val albums: List<ProviderMediaItem> = emptyList(),
    val tracksNextOffset: Int = 0,
    val tracksHasMore: Boolean = false,
    val albumsNextOffset: Int = 0,
    val albumsHasMore: Boolean = false,
    val isLoading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

interface ProviderMediaItemDetailController {
    val uiState: StateFlow<ProviderMediaItemDetailUiState>
    fun open(item: ProviderMediaItem)
    fun activate(item: ProviderMediaItem)
    fun close()
    fun refresh()
    fun prefetchTracksIfNeeded(visibleIndex: Int)
    fun prefetchAlbumsIfNeeded(visibleIndex: Int)
    fun play(index: Int)
    fun playAll()
}

data class ProviderVideoDetailUiState(
    val video: ProviderVideo? = null,
    val payload: VideoPlaybackPayload? = null,
    val isFullscreen: Boolean = false,
    val isLoading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

interface ProviderVideoDetailController {
    val uiState: StateFlow<ProviderVideoDetailUiState>
    fun open(video: ProviderVideo)
    fun activate(video: ProviderVideo)
    fun close()
    fun refresh()
    fun toggleFullscreen()
}

data class ProviderDetailOwners(
    val feature: ProviderFeatureDetailController,
    val playlist: ProviderPlaylistDetailController,
    val track: ProviderTrackDetailController,
    val mediaItem: ProviderMediaItemDetailController,
    val video: ProviderVideoDetailController,
)

fun createProviderDetailOwners(
    providerRepository: ProviderMusicRepository,
    playbackQueue: PlaybackQueueUiPort,
    settingsRepository: AppSettingsRepository,
    providerCatalog: ProviderCatalogFeatureController,
    navigator: AppNavigator,
    scope: CoroutineScope,
    onProviderMutation: (String) -> Unit = {},
    nowMillis: () -> Long = ::currentTimeMillis,
): ProviderDetailOwners {
    val videoCore = createProviderVideoDetailFeatureOwner(
        port = BoundProviderVideoDetailPort(providerRepository, navigator),
        scope = scope,
    )
    val video = BoundProviderVideoDetailController(videoCore)
    return ProviderDetailOwners(
        feature = BoundProviderFeatureDetailController(
            createProviderFeatureDetailFeatureOwner(
                port = BoundProviderFeatureDetailPort(providerRepository, playbackQueue, navigator),
                scope = scope,
            ),
        ),
        playlist = BoundProviderPlaylistDetailController(
            createProviderPlaylistDetailFeatureOwner(
                port = BoundProviderPlaylistDetailPort(
                    providerRepository = providerRepository,
                    playbackQueue = playbackQueue,
                    settingsRepository = settingsRepository,
                    providerCatalog = providerCatalog,
                    navigator = navigator,
                    onProviderMutation = onProviderMutation,
                    nowMillis = nowMillis,
                ),
                scope = scope,
            ),
        ),
        track = BoundProviderTrackDetailController(
            createProviderTrackDetailFeatureOwner(
                port = BoundProviderTrackDetailPort(
                    providerRepository = providerRepository,
                    playbackQueue = playbackQueue,
                    videoController = video,
                    navigator = navigator,
                ),
                scope = scope,
            ),
        ),
        mediaItem = BoundProviderMediaItemDetailController(
            createProviderMediaItemDetailFeatureOwner(
                port = BoundProviderMediaItemDetailPort(providerRepository, playbackQueue, navigator),
                scope = scope,
            ),
        ),
        video = video,
    )
}

private class BoundProviderFeatureDetailController(
    private val owner: CoreFeatureOwner<ProviderFeature, ProviderContentSection, MusicTrack>,
) : ProviderFeatureDetailController {
    override val uiState = owner.state.mapState(CoreFeatureState<ProviderFeature, ProviderContentSection, MusicTrack>::toUiState)
    override fun open(feature: ProviderFeature) = owner.open(feature)
    override fun activate(feature: ProviderFeature) = owner.activate(feature)
    override fun close() = owner.close()
    override fun refresh() = owner.refresh()
    override fun loadMore() = owner.loadMore()
    override fun prefetchIfNeeded(visibleIndex: Int) = owner.prefetchIfNeeded(visibleIndex)
    override fun play(index: Int) = owner.play(index)
    override fun playAll() = owner.playAll()
}

private class BoundProviderPlaylistDetailController(
    private val owner: CorePlaylistOwner<ProviderPlaylist, ProviderFeatureCategory, MusicTrack>,
) : ProviderPlaylistDetailController {
    override val uiState = owner.state.mapState(CorePlaylistState<ProviderPlaylist, ProviderFeatureCategory, MusicTrack>::toUiState)
    override fun open(playlist: ProviderPlaylist, category: ProviderFeatureCategory?) = owner.open(playlist, category)
    override fun activate(playlist: ProviderPlaylist, category: ProviderFeatureCategory?) = owner.activate(playlist, category)
    override fun close() = owner.close()
    override fun refresh() = owner.refresh()
    override fun loadMore() = owner.loadMore()
    override fun prefetchIfNeeded(visibleIndex: Int) = owner.prefetchIfNeeded(visibleIndex)
    override fun play(index: Int) = owner.play(index)
    override fun playAll() = owner.playAll()
    override fun canRemove(track: MusicTrack) = owner.canRemove(track)
    override fun remove(track: MusicTrack) = owner.remove(track)
    override fun canDelete() = owner.canDelete()
    override fun delete() = owner.delete()
}

private class BoundProviderTrackDetailController(
    private val owner: CoreTrackOwner<MusicTrack, ProviderComment, ProviderVideo>,
) : ProviderTrackDetailController {
    override val uiState = owner.state.mapState(CoreTrackState<MusicTrack, ProviderComment, ProviderVideo>::toUiState)
    override fun open(track: MusicTrack) = owner.open(track)
    override fun activate(track: MusicTrack) = owner.activate(track)
    override fun close() = owner.close()
    override fun refresh() = owner.refresh()
    override fun play() = owner.play()
    override fun playSimilar(index: Int) = owner.playSimilar(index)
    override fun openVideo() = owner.openVideo()
}

private class BoundProviderMediaItemDetailController(
    private val owner: CoreMediaItemOwner<ProviderMediaItem, MusicTrack>,
) : ProviderMediaItemDetailController {
    override val uiState = owner.state.mapState(CoreMediaItemState<ProviderMediaItem, MusicTrack>::toUiState)
    override fun open(item: ProviderMediaItem) = owner.open(item)
    override fun activate(item: ProviderMediaItem) = owner.activate(item)
    override fun close() = owner.close()
    override fun refresh() = owner.refresh()
    override fun prefetchTracksIfNeeded(visibleIndex: Int) = owner.prefetchTracksIfNeeded(visibleIndex)
    override fun prefetchAlbumsIfNeeded(visibleIndex: Int) = owner.prefetchAlbumsIfNeeded(visibleIndex)
    override fun play(index: Int) = owner.play(index)
    override fun playAll() = owner.playAll()
}

private class BoundProviderVideoDetailController(
    private val owner: CoreVideoOwner<ProviderVideo, VideoPlaybackPayload>,
) : ProviderVideoDetailController {
    override val uiState = owner.state.mapState(CoreVideoState<ProviderVideo, VideoPlaybackPayload>::toUiState)
    override fun open(video: ProviderVideo) = owner.open(video)
    override fun activate(video: ProviderVideo) = owner.activate(video)
    override fun close() = owner.close()
    override fun refresh() = owner.refresh()
    override fun toggleFullscreen() = owner.toggleFullscreen()
}

private class BoundProviderFeatureDetailPort(
    private val providerRepository: ProviderMusicRepository,
    private val playbackQueue: PlaybackQueueUiPort,
    private val navigator: AppNavigator,
) : CoreFeaturePort<ProviderFeature, ProviderContentSection, MusicTrack> {
    override suspend fun loadPage(feature: ProviderFeature, offset: Int) = providerRepository.loadFeaturePage(feature, offset)
    override fun featureId(feature: ProviderFeature) = feature.id
    override fun featureTitle(feature: ProviderFeature) = feature.title
    override fun featureProviderId(feature: ProviderFeature) = feature.providerId
    override fun isDynamicQueueFeature(feature: ProviderFeature) = feature.isDynamicQueueFeature()
    override fun contentTracks(content: ProviderContentSection) = content.tracks
    override fun contentNextOffset(content: ProviderContentSection) = content.nextOffset
    override fun contentHasMore(content: ProviderContentSection) = content.hasMore
    override fun contentIsLoginRequired(content: ProviderContentSection) = content.isLoginRequired
    override fun contentErrorMessage(content: ProviderContentSection) = content.errorMessage
    override fun contentProviderName(content: ProviderContentSection) = content.feature.providerName
    override fun contentCount(content: ProviderContentSection) = content.contentCountSnapshot()
    override fun mergeContent(current: ProviderContentSection?, page: ProviderContentSection) = mergeFeaturePage(current, page)
    override fun errorMessage(throwable: Throwable, fallback: String, providerId: String?) =
        providerDetailError(throwable, fallback, providerId)
    override fun open(feature: ProviderFeature) = navigator.navigate(AppRoute.FeatureDetail(feature.toNavigationFeature()))
    override fun close() {
        navigator.pop(AppRoute.Feature)
    }
    override fun playFeatureTracks(tracks: List<MusicTrack>, index: Int, feature: ProviderFeature) =
        playbackQueue.playFeatureTracks(tracks, index, feature)
    override fun playTracks(tracks: List<MusicTrack>, index: Int) = playbackQueue.playTracks(tracks, index)
}

private class BoundProviderPlaylistDetailPort(
    private val providerRepository: ProviderMusicRepository,
    private val playbackQueue: PlaybackQueueUiPort,
    private val settingsRepository: AppSettingsRepository,
    private val providerCatalog: ProviderCatalogFeatureController,
    private val navigator: AppNavigator,
    private val onProviderMutation: (String) -> Unit,
    private val nowMillis: () -> Long,
) : CorePlaylistPort<ProviderPlaylist, ProviderFeatureCategory, MusicTrack> {
    override suspend fun loadPage(playlist: ProviderPlaylist, offset: Int): CorePlaylistPage<ProviderPlaylist, MusicTrack> {
        val detail = providerRepository.playlistDetailPage(playlist, offset)
        return CorePlaylistPage(
            playlist = detail.playlist,
            tracks = detail.tracks,
            nextOffset = detail.tracksNextOffset,
            hasMore = detail.tracksHasMore,
        )
    }

    override suspend fun removeTrack(playlist: ProviderPlaylist, track: MusicTrack): ProviderDetailMutationResult =
        providerRepository.removeTrackFromPlaylist(playlist, track).let { ProviderDetailMutationResult(it.success, it.message) }

    override suspend fun deletePlaylist(playlist: ProviderPlaylist): ProviderDetailMutationResult =
        providerRepository.deletePlaylist(playlist).let { ProviderDetailMutationResult(it.success, it.message) }

    override suspend fun recordPlayback(playlist: ProviderPlaylist) {
        settingsRepository.update { settings ->
            val key = minePlaylistPlaybackStatsKey(playlist)
            val previous = settings.playlistPlaybackStats[key] ?: PlaylistPlaybackStat()
            val updated = (settings.playlistPlaybackStats + (
                key to previous.copy(
                    playCount = (previous.playCount + 1).coerceAtMost(1_000_000_000L),
                    lastPlayedAtMillis = nowMillis(),
                )
            )).entries
                .sortedByDescending { it.value.lastPlayedAtMillis }
                .take(MAX_PROVIDER_PLAYLIST_PLAYBACK_STATS)
                .associate { it.toPair() }
            settings.copy(
                playlistPlaybackStatsVersion = PROVIDER_PLAYLIST_PLAYBACK_STATS_VERSION,
                playlistPlaybackStats = updated,
            )
        }
    }

    override fun playlistId(playlist: ProviderPlaylist) = playlist.id
    override fun playlistTitle(playlist: ProviderPlaylist) = playlist.title
    override fun playlistProviderId(playlist: ProviderPlaylist) = playlist.providerId
    override fun trackId(track: MusicTrack) = track.id
    override fun trackTitle(track: MusicTrack) = track.title
    override fun trackBelongsToProvider(track: MusicTrack, providerId: String) =
        track.sourceType == TrackSourceType.Provider && track.source == providerId
    override fun isMinePlaylistCategory(category: ProviderFeatureCategory?) = category == ProviderFeatureCategory.MinePlaylists
    override fun isLoggedIn(providerId: String) = providerCatalog.uiState.value.sessions.authStates[providerId]?.isLoggedIn == true
    override fun canRemoveSongFromPlaylist(providerId: String) =
        providerCatalog.uiState.value.capabilities[providerId]?.canRemoveSongFromPlaylist == true
    override fun canDeletePlaylist(providerId: String) =
        providerCatalog.uiState.value.capabilities[providerId]?.canDeletePlaylist == true
    override fun errorMessage(throwable: Throwable, fallback: String, providerId: String?) =
        providerDetailError(throwable, fallback, providerId)
    override fun open(playlist: ProviderPlaylist, category: ProviderFeatureCategory?) = navigator.navigate(
        AppRoute.PlaylistDetail(
            playlist = playlist.toNavigationPlaylist(),
            category = category?.name,
        )
    )
    override fun close() {
        navigator.pop(AppRoute.Playlist)
    }
    override fun playPlaylistTracks(tracks: List<MusicTrack>, index: Int, playlistId: String) =
        playbackQueue.playPlaylistTracks(tracks, index, playlistId)
    override fun playAllPlaylistTracks(tracks: List<MusicTrack>, playlistId: String) =
        playbackQueue.playAllPlaylistTracks(tracks, playlistId)
    override fun appendPlaylistTracks(playlistId: String, tracks: List<MusicTrack>) =
        playbackQueue.appendPlaylistTracks(playlistId, tracks)
    override fun onProviderMutation(providerId: String) = onProviderMutation.invoke(providerId)
}

private class BoundProviderTrackDetailPort(
    private val providerRepository: ProviderMusicRepository,
    private val playbackQueue: PlaybackQueueUiPort,
    private val videoController: ProviderVideoDetailController,
    private val navigator: AppNavigator,
) : CoreTrackPort<MusicTrack, ProviderComment, ProviderVideo> {
    override suspend fun loadTrackDetail(track: MusicTrack) =
        providerRepository.trackDetail(track.providerId?.takeIf { it.isNotBlank() } ?: track.id)
    override suspend fun similarTracks(track: MusicTrack) = providerRepository.similarTracks(track)
    override suspend fun hotComments(track: MusicTrack) = providerRepository.hotComments(track)
    override suspend fun trackVideo(track: MusicTrack) = providerRepository.trackVideo(track)
    override fun isProviderTrack(track: MusicTrack) = track.sourceType == TrackSourceType.Provider
    override fun trackId(track: MusicTrack) = track.id
    override fun trackTitle(track: MusicTrack) = track.title
    override fun trackDetailId(track: MusicTrack) = track.providerId?.takeIf { it.isNotBlank() } ?: track.id
    override fun trackProviderId(track: MusicTrack) = track.source
    override fun errorMessage(throwable: Throwable, fallback: String, providerId: String?) =
        providerDetailError(throwable, fallback, providerId)
    override fun open(track: MusicTrack) = navigator.navigate(AppRoute.TrackDetail(track.toNavigationTrack()))
    override fun close() {
        navigator.pop(AppRoute.Track)
    }
    override fun playTracks(tracks: List<MusicTrack>, index: Int) = playbackQueue.playTracks(tracks, index)
    override fun openVideo(video: ProviderVideo) = videoController.open(video)
}

private class BoundProviderMediaItemDetailPort(
    private val providerRepository: ProviderMusicRepository,
    private val playbackQueue: PlaybackQueueUiPort,
    private val navigator: AppNavigator,
) : CoreMediaItemPort<ProviderMediaItem, MusicTrack> {
    override suspend fun loadPage(
        item: ProviderMediaItem,
        tracksOffset: Int,
        albumsOffset: Int,
    ): CoreMediaItemPage<ProviderMediaItem, MusicTrack> {
        val detail = providerRepository.mediaItemDetailPage(item, tracksOffset, albumsOffset)
        return CoreMediaItemPage(
            item = detail.item,
            tracks = detail.tracks,
            albums = detail.albums,
            tracksNextOffset = detail.tracksNextOffset,
            tracksHasMore = detail.tracksHasMore,
            albumsNextOffset = detail.albumsNextOffset,
            albumsHasMore = detail.albumsHasMore,
        )
    }

    override fun itemId(item: ProviderMediaItem) = item.id
    override fun itemTitle(item: ProviderMediaItem) = item.title
    override fun itemProviderId(item: ProviderMediaItem) = item.providerId
    override fun trackId(track: MusicTrack) = track.id
    override fun errorMessage(throwable: Throwable, fallback: String, providerId: String?) =
        providerDetailError(throwable, fallback, providerId)
    override fun open(item: ProviderMediaItem) = navigator.navigate(AppRoute.MediaItemDetail(item.toNavigationMediaItem()))
    override fun close() {
        navigator.pop(AppRoute.MediaItem)
    }
    override fun playTracks(tracks: List<MusicTrack>, index: Int) = playbackQueue.playTracks(tracks, index)
}

private class BoundProviderVideoDetailPort(
    private val providerRepository: ProviderMusicRepository,
    private val navigator: AppNavigator,
) : CoreVideoPort<ProviderVideo, VideoPlaybackPayload> {
    override suspend fun loadPlayback(video: ProviderVideo): ProviderVideoPlaybackResult<ProviderVideo, VideoPlaybackPayload> {
        val payload = providerRepository.videoPlaybackPayload(video)
        return ProviderVideoPlaybackResult(payload.video, payload)
    }
    override fun videoId(video: ProviderVideo) = video.id
    override fun videoTitle(video: ProviderVideo) = video.title
    override fun videoProviderId(video: ProviderVideo) = video.providerId
    override fun errorMessage(throwable: Throwable, fallback: String, providerId: String?) =
        providerDetailError(throwable, fallback, providerId)
    override fun open(video: ProviderVideo) = navigator.navigate(AppRoute.VideoDetail(video.toNavigationVideo()))
    override fun close() {
        navigator.pop(AppRoute.Video)
    }
}

private fun CoreFeatureState<ProviderFeature, ProviderContentSection, MusicTrack>.toUiState() = ProviderFeatureDetailUiState(
    feature = feature,
    content = content,
    tracks = tracks,
    nextOffset = nextOffset,
    hasMore = hasMore,
    isLoading = isLoading,
    message = message,
    errorMessage = errorMessage,
)

private fun CorePlaylistState<ProviderPlaylist, ProviderFeatureCategory, MusicTrack>.toUiState() = ProviderPlaylistDetailUiState(
    playlist = playlist,
    category = category,
    tracks = tracks,
    nextOffset = nextOffset,
    hasMore = hasMore,
    isLoading = isLoading,
    message = message,
    errorMessage = errorMessage,
)

private fun CoreTrackState<MusicTrack, ProviderComment, ProviderVideo>.toUiState() = ProviderTrackDetailUiState(
    track = track,
    similarTracks = similarTracks,
    comments = comments,
    video = video,
    relatedErrorMessage = relatedErrorMessage,
    isLoading = isLoading,
    message = message,
    errorMessage = errorMessage,
)

private fun CoreMediaItemState<ProviderMediaItem, MusicTrack>.toUiState() = ProviderMediaItemDetailUiState(
    item = item,
    tracks = tracks,
    albums = albums,
    tracksNextOffset = tracksNextOffset,
    tracksHasMore = tracksHasMore,
    albumsNextOffset = albumsNextOffset,
    albumsHasMore = albumsHasMore,
    isLoading = isLoading,
    message = message,
    errorMessage = errorMessage,
)

private fun CoreVideoState<ProviderVideo, VideoPlaybackPayload>.toUiState() = ProviderVideoDetailUiState(
    video = video,
    payload = payload,
    isFullscreen = isFullscreen,
    isLoading = isLoading,
    message = message,
    errorMessage = errorMessage,
)

private class MappedStateFlow<Source, Target>(
    private val source: StateFlow<Source>,
    private val transform: (Source) -> Target,
) : StateFlow<Target> {
    override val value: Target
        get() = transform(source.value)
    override val replayCache: List<Target>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<Target>): Nothing = source.collect(
        object : FlowCollector<Source> {
            override suspend fun emit(value: Source) {
                collector.emit(transform(value))
            }
        },
    )
}

private fun <Source, Target> StateFlow<Source>.mapState(transform: (Source) -> Target): StateFlow<Target> =
    MappedStateFlow(this, transform)

private fun mergeFeaturePage(current: ProviderContentSection?, page: ProviderContentSection): ProviderContentSection {
    if (current == null || current.feature.id != page.feature.id) return page
    return page.copy(
        tracks = mergeTracks(current.tracks, page.tracks),
        playlists = mergePlaylists(current.playlists, page.playlists),
        mediaItems = mergeMediaItems(current.mediaItems, page.mediaItems),
        videos = mergeVideos(current.videos, page.videos),
    )
}

private fun mergeTracks(current: List<MusicTrack>, next: List<MusicTrack>): List<MusicTrack> {
    val seen = current.mapTo(mutableSetOf()) { it.id }
    return current + next.filter { seen.add(it.id) }
}

private fun mergePlaylists(current: List<ProviderPlaylist>, next: List<ProviderPlaylist>): List<ProviderPlaylist> {
    val seen = current.mapTo(mutableSetOf()) { it.id }
    return current + next.filter { seen.add(it.id) }
}

private fun mergeMediaItems(current: List<ProviderMediaItem>, next: List<ProviderMediaItem>): List<ProviderMediaItem> {
    val seen = current.mapTo(mutableSetOf()) { it.id }
    return current + next.filter { seen.add(it.id) }
}

private fun mergeVideos(current: List<ProviderVideo>, next: List<ProviderVideo>): List<ProviderVideo> {
    val seen = current.mapTo(mutableSetOf()) { it.id }
    return current + next.filter { seen.add(it.id) }
}

private fun ProviderContentSection.contentCountSnapshot(): Int =
    maxOf(tracks.size, playlists.size, mediaItems.size, videos.size)

private fun providerDetailError(throwable: Throwable, fallback: String, providerId: String?): String =
    throwable.providerFailureOrNull(providerId)?.userMessage
        ?: throwable.message
        ?: throwable::class.simpleName.orEmpty().ifBlank { fallback }
