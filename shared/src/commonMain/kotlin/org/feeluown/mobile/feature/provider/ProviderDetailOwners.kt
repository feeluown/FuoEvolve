package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.feeluown.mobile.provider.core.network.currentTimeMillis

private const val PROVIDER_DETAIL_TIMEOUT_MS = 30_000L
private const val PROVIDER_VIDEO_TIMEOUT_MS = 25_000L
private const val PROVIDER_LIST_PREFETCH_REMAINING = 8
private const val PROVIDER_PLAYLIST_BACKGROUND_PAGE_INTERVAL_MS = 3_000L
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
    val video = DefaultProviderVideoDetailController(
        providerRepository = providerRepository,
        navigator = navigator,
        scope = scope,
    )
    return ProviderDetailOwners(
        feature = DefaultProviderFeatureDetailController(
            providerRepository = providerRepository,
            playbackQueue = playbackQueue,
            navigator = navigator,
            scope = scope,
        ),
        playlist = DefaultProviderPlaylistDetailController(
            providerRepository = providerRepository,
            playbackQueue = playbackQueue,
            settingsRepository = settingsRepository,
            providerCatalog = providerCatalog,
            navigator = navigator,
            scope = scope,
            onProviderMutation = onProviderMutation,
            nowMillis = nowMillis,
        ),
        track = DefaultProviderTrackDetailController(
            providerRepository = providerRepository,
            playbackQueue = playbackQueue,
            videoController = video,
            navigator = navigator,
            scope = scope,
        ),
        mediaItem = DefaultProviderMediaItemDetailController(
            providerRepository = providerRepository,
            playbackQueue = playbackQueue,
            navigator = navigator,
            scope = scope,
        ),
        video = video,
    )
}

private class DefaultProviderFeatureDetailController(
    private val providerRepository: ProviderMusicRepository,
    private val playbackQueue: PlaybackQueueUiPort,
    private val navigator: AppNavigator,
    private val scope: CoroutineScope,
) : ProviderFeatureDetailController {
    private val mutableUiState = MutableStateFlow(ProviderFeatureDetailUiState())
    override val uiState: StateFlow<ProviderFeatureDetailUiState> = mutableUiState.asStateFlow()
    private var loadJob: Job? = null

    override fun open(feature: ProviderFeature) {
        navigator.navigate(AppRoute.FeatureDetail(feature.toNavigationFeature()))
        activate(feature)
    }

    override fun activate(feature: ProviderFeature) {
        if (uiState.value.feature?.id == feature.id && (uiState.value.content != null || uiState.value.isLoading)) return
        load(feature, reset = true)
    }

    override fun close() {
        loadJob?.cancel()
        mutableUiState.value = ProviderFeatureDetailUiState()
        navigator.pop(AppRoute.Feature)
    }

    override fun refresh() {
        uiState.value.feature?.let { load(it, reset = true) }
    }

    override fun loadMore() {
        val state = uiState.value
        val feature = state.feature ?: return
        if (state.isLoading || !state.hasMore || feature.isDynamicQueueFeature()) return
        load(feature, reset = false)
    }

    override fun prefetchIfNeeded(visibleIndex: Int) {
        val state = uiState.value
        val count = state.content?.contentCountSnapshot() ?: state.tracks.size
        if (count - visibleIndex <= PROVIDER_LIST_PREFETCH_REMAINING) loadMore()
    }

    override fun play(index: Int) {
        val state = uiState.value
        val feature = state.feature ?: return
        if (index !in state.tracks.indices) return
        playbackQueue.playFeatureTracks(state.tracks, index, feature)
    }

    override fun playAll() {
        val state = uiState.value
        val feature = state.feature ?: return
        if (state.tracks.isEmpty()) return
        if (feature.isDynamicQueueFeature()) {
            playbackQueue.playFeatureTracks(state.tracks, 0, feature)
            return
        }
        scope.launch {
            ensureAllPages()
            val current = uiState.value
            if (current.feature?.id == feature.id && current.tracks.isNotEmpty()) {
                playbackQueue.playTracks(current.tracks, 0)
            }
        }
    }

    private fun load(feature: ProviderFeature, reset: Boolean) {
        if (reset) loadJob?.cancel()
        loadJob = scope.launch {
            val before = uiState.value
            val offset = if (reset) 0 else before.nextOffset
            mutableUiState.value = if (reset) {
                ProviderFeatureDetailUiState(
                    feature = feature,
                    isLoading = true,
                    message = "正在加载：${feature.title}",
                )
            } else {
                before.copy(isLoading = true, errorMessage = null)
            }
            runCatching {
                withTimeout(PROVIDER_DETAIL_TIMEOUT_MS) {
                    providerRepository.loadFeaturePage(feature, offset)
                }
            }.onSuccess { page ->
                if (uiState.value.feature?.id != feature.id) return@onSuccess
                val merged = if (reset) page else mergeFeaturePage(uiState.value.content, page)
                mutableUiState.value = uiState.value.copy(
                    content = merged,
                    tracks = merged.tracks,
                    nextOffset = merged.nextOffset,
                    hasMore = merged.hasMore,
                    isLoading = false,
                    message = when {
                        merged.isLoginRequired -> "登录后显示 ${merged.feature.providerName} 的个性化内容"
                        merged.errorMessage != null -> merged.errorMessage
                        merged.contentCountSnapshot() == 0 -> "${feature.title} 暂无内容"
                        else -> "${feature.title} · ${merged.contentCountSnapshot()} 项"
                    },
                    errorMessage = when {
                        merged.isLoginRequired -> "登录后显示 ${merged.feature.providerName} 的个性化内容"
                        else -> merged.errorMessage
                    },
                )
            }.onFailure { throwable ->
                if (uiState.value.feature?.id == feature.id) {
                    mutableUiState.value = uiState.value.copy(
                        isLoading = false,
                        message = providerDetailError(throwable, "加载失败", feature.providerId),
                        errorMessage = providerDetailError(throwable, "加载失败", feature.providerId),
                    )
                }
            }
        }
    }

    private suspend fun ensureAllPages() {
        while (uiState.value.hasMore) {
            val state = uiState.value
            val feature = state.feature ?: return
            if (feature.isDynamicQueueFeature()) return
            val offset = state.nextOffset
            val page = runCatching {
                withTimeout(PROVIDER_DETAIL_TIMEOUT_MS) {
                    providerRepository.loadFeaturePage(feature, offset)
                }
            }.getOrElse {
                mutableUiState.value = state.copy(errorMessage = providerDetailError(it, "加载失败", feature.providerId))
                return
            }
            if (uiState.value.feature?.id != feature.id) return
            val merged = mergeFeaturePage(uiState.value.content, page)
            mutableUiState.value = uiState.value.copy(
                content = merged,
                tracks = merged.tracks,
                nextOffset = merged.nextOffset,
                hasMore = merged.hasMore,
            )
            if (merged.nextOffset == offset && page.contentCountSnapshot() == 0) return
        }
    }
}

private class DefaultProviderPlaylistDetailController(
    private val providerRepository: ProviderMusicRepository,
    private val playbackQueue: PlaybackQueueUiPort,
    private val settingsRepository: AppSettingsRepository,
    private val providerCatalog: ProviderCatalogFeatureController,
    private val navigator: AppNavigator,
    private val scope: CoroutineScope,
    private val onProviderMutation: (String) -> Unit,
    private val nowMillis: () -> Long,
) : ProviderPlaylistDetailController {
    private val mutableUiState = MutableStateFlow(ProviderPlaylistDetailUiState())
    override val uiState: StateFlow<ProviderPlaylistDetailUiState> = mutableUiState.asStateFlow()
    private var loadJob: Job? = null
    private var playbackPaginationJob: Job? = null

    override fun open(playlist: ProviderPlaylist, category: ProviderFeatureCategory?) {
        navigator.navigate(
            AppRoute.PlaylistDetail(
                playlist = playlist.toNavigationPlaylist(),
                category = category?.name,
            )
        )
        activate(playlist, category)
    }

    override fun activate(playlist: ProviderPlaylist, category: ProviderFeatureCategory?) {
        val current = uiState.value
        if (current.playlist?.id == playlist.id && current.category == category && (current.tracks.isNotEmpty() || current.isLoading)) return
        load(playlist, category, reset = true)
    }

    override fun close() {
        loadJob?.cancel()
        playbackPaginationJob?.cancel()
        mutableUiState.value = ProviderPlaylistDetailUiState()
        navigator.pop(AppRoute.Playlist)
    }

    override fun refresh() {
        val state = uiState.value
        state.playlist?.let { load(it, state.category, reset = true) }
    }

    override fun loadMore() {
        val state = uiState.value
        val playlist = state.playlist ?: return
        if (state.isLoading || !state.hasMore) return
        load(playlist, state.category, reset = false)
    }

    override fun prefetchIfNeeded(visibleIndex: Int) {
        val state = uiState.value
        if (state.tracks.size - visibleIndex <= PROVIDER_LIST_PREFETCH_REMAINING) loadMore()
    }

    override fun play(index: Int) {
        val state = uiState.value
        val playlist = state.playlist ?: return
        if (index !in state.tracks.indices) return
        recordPlayback(playlist)
        playbackQueue.playPlaylistTracks(state.tracks, index, playlist.id)
        startPlaybackPagination(playlist)
    }

    override fun playAll() {
        val state = uiState.value
        val playlist = state.playlist ?: return
        if (state.tracks.isEmpty()) return
        recordPlayback(playlist)
        playbackQueue.playAllPlaylistTracks(state.tracks, playlist.id)
        startPlaybackPagination(playlist)
    }

    override fun canRemove(track: MusicTrack): Boolean {
        val state = uiState.value
        val playlist = state.playlist ?: return false
        val catalog = providerCatalog.uiState.value
        return state.category == ProviderFeatureCategory.MinePlaylists &&
            track.sourceType == TrackSourceType.Provider &&
            track.source == playlist.providerId &&
            catalog.sessions.authStates[playlist.providerId]?.isLoggedIn == true &&
            catalog.capabilities[playlist.providerId]?.canRemoveSongFromPlaylist == true
    }

    override fun remove(track: MusicTrack) {
        val playlist = uiState.value.playlist ?: return
        if (!canRemove(track)) return
        scope.launch {
            runCatching { providerRepository.removeTrackFromPlaylist(playlist, track) }
                .onSuccess { result ->
                    if (result.success) {
                        mutableUiState.value = uiState.value.copy(
                            tracks = uiState.value.tracks.filterNot { it.id == track.id },
                            message = result.message.ifBlank { "已从歌单移除：${track.title}" },
                            errorMessage = null,
                        )
                        onProviderMutation(playlist.providerId)
                    } else {
                        mutableUiState.value = uiState.value.copy(errorMessage = result.message.ifBlank { "移除失败" })
                    }
                }
                .onFailure {
                    mutableUiState.value = uiState.value.copy(
                        errorMessage = providerDetailError(it, "移除失败", playlist.providerId),
                    )
                }
        }
    }

    override fun canDelete(): Boolean {
        val state = uiState.value
        val playlist = state.playlist ?: return false
        val catalog = providerCatalog.uiState.value
        return state.category == ProviderFeatureCategory.MinePlaylists &&
            catalog.sessions.authStates[playlist.providerId]?.isLoggedIn == true &&
            catalog.capabilities[playlist.providerId]?.canDeletePlaylist == true
    }

    override fun delete() {
        val playlist = uiState.value.playlist ?: return
        if (!canDelete()) return
        scope.launch {
            mutableUiState.value = uiState.value.copy(isLoading = true, message = "正在删除歌单", errorMessage = null)
            runCatching { providerRepository.deletePlaylist(playlist) }
                .onSuccess { result ->
                    if (result.success) {
                        onProviderMutation(playlist.providerId)
                        close()
                    } else {
                        mutableUiState.value = uiState.value.copy(
                            isLoading = false,
                            message = result.message.ifBlank { "删除歌单失败" },
                            errorMessage = result.message.ifBlank { "删除歌单失败" },
                        )
                    }
                }
                .onFailure {
                    mutableUiState.value = uiState.value.copy(
                        isLoading = false,
                        errorMessage = providerDetailError(it, "删除歌单失败", playlist.providerId),
                    )
                }
        }
    }

    private fun load(playlist: ProviderPlaylist, category: ProviderFeatureCategory?, reset: Boolean) {
        if (reset) loadJob?.cancel()
        loadJob = scope.launch {
            val before = uiState.value
            val offset = if (reset) 0 else before.nextOffset
            mutableUiState.value = if (reset) {
                ProviderPlaylistDetailUiState(
                    playlist = playlist,
                    category = category,
                    isLoading = true,
                    message = "正在加载：${playlist.title}",
                )
            } else before.copy(isLoading = true, errorMessage = null)
            runCatching {
                withTimeout(PROVIDER_DETAIL_TIMEOUT_MS) {
                    providerRepository.playlistDetailPage(playlist, offset)
                }
            }.onSuccess { detail ->
                if (uiState.value.playlist?.id != playlist.id) return@onSuccess
                val tracks = if (reset) detail.tracks else mergeTracks(uiState.value.tracks, detail.tracks)
                mutableUiState.value = uiState.value.copy(
                    playlist = detail.playlist,
                    tracks = tracks,
                    nextOffset = detail.tracksNextOffset,
                    hasMore = detail.tracksHasMore,
                    isLoading = false,
                    message = if (tracks.isEmpty()) "歌单暂无歌曲" else "${detail.playlist.title} · ${tracks.size} 首",
                    errorMessage = null,
                )
            }.onFailure { throwable ->
                if (uiState.value.playlist?.id == playlist.id) {
                    mutableUiState.value = uiState.value.copy(
                        isLoading = false,
                        errorMessage = providerDetailError(throwable, "加载失败", playlist.providerId),
                    )
                }
            }
        }
    }

    private fun startPlaybackPagination(playlist: ProviderPlaylist) {
        playbackPaginationJob?.cancel()
        if (!uiState.value.hasMore) return
        playbackPaginationJob = scope.launch {
            while (uiState.value.playlist?.id == playlist.id && uiState.value.hasMore) {
                delay(PROVIDER_PLAYLIST_BACKGROUND_PAGE_INTERVAL_MS)
                val before = uiState.value
                val offset = before.nextOffset
                val detail = runCatching {
                    withTimeout(PROVIDER_DETAIL_TIMEOUT_MS) {
                        providerRepository.playlistDetailPage(playlist, offset)
                    }
                }.getOrElse { return@launch }
                if (uiState.value.playlist?.id != playlist.id) return@launch
                val newTracks = detail.tracks.filterNot { pageTrack -> before.tracks.any { it.id == pageTrack.id } }
                val tracks = before.tracks + newTracks
                mutableUiState.value = before.copy(
                    playlist = detail.playlist,
                    tracks = tracks,
                    nextOffset = detail.tracksNextOffset,
                    hasMore = detail.tracksHasMore,
                )
                playbackQueue.appendPlaylistTracks(playlist.id, newTracks)
                if (detail.tracksNextOffset == offset && newTracks.isEmpty()) return@launch
            }
        }
    }

    private fun recordPlayback(playlist: ProviderPlaylist) {
        scope.launch {
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
    }
}

private class DefaultProviderTrackDetailController(
    private val providerRepository: ProviderMusicRepository,
    private val playbackQueue: PlaybackQueueUiPort,
    private val videoController: ProviderVideoDetailController,
    private val navigator: AppNavigator,
    private val scope: CoroutineScope,
) : ProviderTrackDetailController {
    private val mutableUiState = MutableStateFlow(ProviderTrackDetailUiState())
    override val uiState: StateFlow<ProviderTrackDetailUiState> = mutableUiState.asStateFlow()
    private var loadJob: Job? = null

    override fun open(track: MusicTrack) {
        if (track.sourceType != TrackSourceType.Provider) return
        navigator.navigate(AppRoute.TrackDetail(track.toNavigationTrack()))
        activate(track)
    }

    override fun activate(track: MusicTrack) {
        if (track.sourceType != TrackSourceType.Provider) return
        if (uiState.value.track?.id == track.id && (uiState.value.similarTracks.isNotEmpty() || uiState.value.isLoading)) return
        mutableUiState.value = ProviderTrackDetailUiState(track = track)
        refresh()
    }

    override fun close() {
        loadJob?.cancel()
        mutableUiState.value = ProviderTrackDetailUiState()
        navigator.pop(AppRoute.Track)
    }

    override fun refresh() {
        val baseTrack = uiState.value.track ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            mutableUiState.value = uiState.value.copy(isLoading = true, message = "正在加载：${baseTrack.title}")
            val detailTrack = runCatching {
                withTimeout(PROVIDER_DETAIL_TIMEOUT_MS) {
                    providerRepository.trackDetail(baseTrack.providerId?.takeIf { it.isNotBlank() } ?: baseTrack.id)
                }
            }.getOrDefault(baseTrack)
            if (uiState.value.track?.id != baseTrack.id) return@launch
            mutableUiState.value = uiState.value.copy(track = detailTrack)

            val similar = runCatching { providerRepository.similarTracks(detailTrack) }
            val comments = runCatching { providerRepository.hotComments(detailTrack) }
            val video = runCatching { providerRepository.trackVideo(detailTrack) }
            if (uiState.value.track?.id != detailTrack.id && uiState.value.track?.id != baseTrack.id) return@launch
            val relatedFailures = listOf(similar, comments, video).mapNotNull { it.exceptionOrNull() }
            mutableUiState.value = uiState.value.copy(
                track = detailTrack,
                similarTracks = similar.getOrDefault(emptyList()),
                comments = comments.getOrDefault(emptyList()),
                video = video.getOrNull(),
                relatedErrorMessage = relatedFailures.firstOrNull()?.let {
                    providerDetailError(it, "相关内容加载失败", detailTrack.source)
                },
                isLoading = false,
                message = detailTrack.title.ifBlank { "歌曲已加载" },
                errorMessage = null,
            )
        }
    }

    override fun play() {
        uiState.value.track?.let { playbackQueue.playTracks(listOf(it), 0) }
    }

    override fun playSimilar(index: Int) {
        val tracks = uiState.value.similarTracks
        if (index in tracks.indices) playbackQueue.playTracks(tracks, index)
    }

    override fun openVideo() {
        uiState.value.video?.let(videoController::open)
    }
}

private class DefaultProviderMediaItemDetailController(
    private val providerRepository: ProviderMusicRepository,
    private val playbackQueue: PlaybackQueueUiPort,
    private val navigator: AppNavigator,
    private val scope: CoroutineScope,
) : ProviderMediaItemDetailController {
    private val mutableUiState = MutableStateFlow(ProviderMediaItemDetailUiState())
    override val uiState: StateFlow<ProviderMediaItemDetailUiState> = mutableUiState.asStateFlow()
    private var tracksJob: Job? = null
    private var albumsJob: Job? = null

    override fun open(item: ProviderMediaItem) {
        navigator.navigate(AppRoute.MediaItemDetail(item.toNavigationMediaItem()))
        activate(item)
    }

    override fun activate(item: ProviderMediaItem) {
        if (uiState.value.item?.id == item.id && (uiState.value.tracks.isNotEmpty() || uiState.value.albums.isNotEmpty() || uiState.value.isLoading)) return
        loadInitial(item)
    }

    override fun close() {
        tracksJob?.cancel()
        albumsJob?.cancel()
        mutableUiState.value = ProviderMediaItemDetailUiState()
        navigator.pop(AppRoute.MediaItem)
    }

    override fun refresh() {
        uiState.value.item?.let(::loadInitial)
    }

    override fun prefetchTracksIfNeeded(visibleIndex: Int) {
        val state = uiState.value
        if (state.tracks.size - visibleIndex <= PROVIDER_LIST_PREFETCH_REMAINING) loadMoreTracks()
    }

    override fun prefetchAlbumsIfNeeded(visibleIndex: Int) {
        val state = uiState.value
        if (state.albums.size - visibleIndex <= PROVIDER_LIST_PREFETCH_REMAINING) loadMoreAlbums()
    }

    override fun play(index: Int) {
        val tracks = uiState.value.tracks
        if (index in tracks.indices) playbackQueue.playTracks(tracks, index)
    }

    override fun playAll() {
        val item = uiState.value.item ?: return
        scope.launch {
            ensureAllTracks(item)
            val state = uiState.value
            if (state.item?.id == item.id && state.tracks.isNotEmpty()) playbackQueue.playTracks(state.tracks, 0)
        }
    }

    private fun loadInitial(item: ProviderMediaItem) {
        tracksJob?.cancel()
        albumsJob?.cancel()
        tracksJob = scope.launch {
            mutableUiState.value = ProviderMediaItemDetailUiState(
                item = item,
                isLoading = true,
                message = "正在加载：${item.title}",
            )
            runCatching {
                withTimeout(PROVIDER_DETAIL_TIMEOUT_MS) {
                    providerRepository.mediaItemDetailPage(item, tracksOffset = 0, albumsOffset = 0)
                }
            }.onSuccess { detail ->
                if (uiState.value.item?.id != item.id) return@onSuccess
                mutableUiState.value = ProviderMediaItemDetailUiState(
                    item = detail.item,
                    tracks = detail.tracks,
                    albums = detail.albums,
                    tracksNextOffset = detail.tracksNextOffset,
                    tracksHasMore = detail.tracksHasMore,
                    albumsNextOffset = detail.albumsNextOffset,
                    albumsHasMore = detail.albumsHasMore,
                    isLoading = false,
                    message = buildList {
                        if (detail.tracks.isNotEmpty()) add("${detail.tracks.size} 首")
                        if (detail.albums.isNotEmpty()) add("${detail.albums.size} 张专辑")
                    }.joinToString(" · ").ifBlank { "${detail.item.title} 暂无内容" },
                )
            }.onFailure {
                if (uiState.value.item?.id == item.id) {
                    mutableUiState.value = uiState.value.copy(
                        isLoading = false,
                        errorMessage = providerDetailError(it, "加载失败", item.providerId),
                    )
                }
            }
        }
    }

    private fun loadMoreTracks() {
        val state = uiState.value
        val item = state.item ?: return
        if (!state.tracksHasMore || tracksJob?.isActive == true) return
        tracksJob = scope.launch {
            val offset = state.tracksNextOffset
            val detail = runCatching {
                withTimeout(PROVIDER_DETAIL_TIMEOUT_MS) {
                    providerRepository.mediaItemDetailPage(item, offset, state.albumsNextOffset)
                }
            }.getOrElse {
                mutableUiState.value = uiState.value.copy(errorMessage = providerDetailError(it, "加载失败", item.providerId))
                return@launch
            }
            if (uiState.value.item?.id != item.id) return@launch
            mutableUiState.value = uiState.value.copy(
                item = detail.item,
                tracks = mergeTracks(uiState.value.tracks, detail.tracks),
                tracksNextOffset = detail.tracksNextOffset,
                tracksHasMore = detail.tracksHasMore,
                errorMessage = null,
            )
        }
    }

    private fun loadMoreAlbums() {
        val state = uiState.value
        val item = state.item ?: return
        if (!state.albumsHasMore || albumsJob?.isActive == true) return
        albumsJob = scope.launch {
            val offset = state.albumsNextOffset
            val detail = runCatching {
                withTimeout(PROVIDER_DETAIL_TIMEOUT_MS) {
                    providerRepository.mediaItemDetailPage(item, state.tracksNextOffset, offset)
                }
            }.getOrElse {
                mutableUiState.value = uiState.value.copy(errorMessage = providerDetailError(it, "加载失败", item.providerId))
                return@launch
            }
            if (uiState.value.item?.id != item.id) return@launch
            mutableUiState.value = uiState.value.copy(
                item = detail.item,
                albums = mergeMediaItems(uiState.value.albums, detail.albums),
                albumsNextOffset = detail.albumsNextOffset,
                albumsHasMore = detail.albumsHasMore,
                errorMessage = null,
            )
        }
    }

    private suspend fun ensureAllTracks(item: ProviderMediaItem) {
        tracksJob?.join()
        while (uiState.value.item?.id == item.id && uiState.value.tracksHasMore) {
            val before = uiState.value
            val offset = before.tracksNextOffset
            val detail = runCatching {
                withTimeout(PROVIDER_DETAIL_TIMEOUT_MS) {
                    providerRepository.mediaItemDetailPage(item, offset, before.albumsNextOffset)
                }
            }.getOrElse { return }
            val tracks = mergeTracks(before.tracks, detail.tracks)
            mutableUiState.value = before.copy(
                item = detail.item,
                tracks = tracks,
                tracksNextOffset = detail.tracksNextOffset,
                tracksHasMore = detail.tracksHasMore,
            )
            if (detail.tracksNextOffset == offset && tracks.size == before.tracks.size) return
        }
    }
}

private class DefaultProviderVideoDetailController(
    private val providerRepository: ProviderMusicRepository,
    private val navigator: AppNavigator,
    private val scope: CoroutineScope,
) : ProviderVideoDetailController {
    private val mutableUiState = MutableStateFlow(ProviderVideoDetailUiState())
    override val uiState: StateFlow<ProviderVideoDetailUiState> = mutableUiState.asStateFlow()
    private var loadJob: Job? = null

    override fun open(video: ProviderVideo) {
        navigator.navigate(AppRoute.VideoDetail(video.toNavigationVideo()))
        activate(video)
    }

    override fun activate(video: ProviderVideo) {
        if (uiState.value.video?.id == video.id && (uiState.value.payload != null || uiState.value.isLoading)) return
        mutableUiState.value = ProviderVideoDetailUiState(video = video)
        refresh()
    }

    override fun close() {
        loadJob?.cancel()
        mutableUiState.value = ProviderVideoDetailUiState()
        navigator.pop(AppRoute.Video)
    }

    override fun refresh() {
        val video = uiState.value.video ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            mutableUiState.value = uiState.value.copy(
                isLoading = true,
                message = "正在加载视频：${video.title}",
                errorMessage = null,
            )
            runCatching {
                withTimeout(PROVIDER_VIDEO_TIMEOUT_MS) {
                    providerRepository.videoPlaybackPayload(video)
                }
            }.onSuccess { payload ->
                if (uiState.value.video?.id != video.id) return@onSuccess
                mutableUiState.value = uiState.value.copy(
                    video = payload.video,
                    payload = payload,
                    isLoading = false,
                    message = "正在播放视频：${payload.video.title}",
                    errorMessage = null,
                )
            }.onFailure {
                if (uiState.value.video?.id == video.id) {
                    mutableUiState.value = uiState.value.copy(
                        isLoading = false,
                        message = "视频加载失败",
                        errorMessage = providerDetailError(it, "视频加载失败", video.providerId),
                    )
                }
            }
        }
    }

    override fun toggleFullscreen() {
        mutableUiState.value = uiState.value.copy(isFullscreen = !uiState.value.isFullscreen)
    }
}

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
