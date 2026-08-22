package org.feeluown.mobile.feature.providerdetail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val DETAIL_TIMEOUT_MS = 30_000L
private const val VIDEO_TIMEOUT_MS = 25_000L
private const val LIST_PREFETCH_REMAINING = 8
private const val PLAYLIST_BACKGROUND_PAGE_INTERVAL_MS = 3_000L

data class ProviderFeatureDetailFeatureState<Feature, Content, Track>(
    val feature: Feature? = null,
    val content: Content? = null,
    val tracks: List<Track> = emptyList(),
    val nextOffset: Int = 0,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

interface ProviderFeatureDetailPort<Feature, Content, Track> {
    suspend fun loadPage(feature: Feature, offset: Int): Content
    fun featureId(feature: Feature): String
    fun featureTitle(feature: Feature): String
    fun featureProviderId(feature: Feature): String?
    fun isDynamicQueueFeature(feature: Feature): Boolean
    fun contentTracks(content: Content): List<Track>
    fun contentNextOffset(content: Content): Int
    fun contentHasMore(content: Content): Boolean
    fun contentIsLoginRequired(content: Content): Boolean
    fun contentErrorMessage(content: Content): String?
    fun contentProviderName(content: Content): String
    fun contentCount(content: Content): Int
    fun mergeContent(current: Content?, page: Content): Content
    fun errorMessage(throwable: Throwable, fallback: String, providerId: String?): String
    fun open(feature: Feature)
    fun close()
    fun playFeatureTracks(tracks: List<Track>, index: Int, feature: Feature)
    fun playTracks(tracks: List<Track>, index: Int)
}

interface ProviderFeatureDetailFeatureOwner<Feature, Content, Track> {
    val state: StateFlow<ProviderFeatureDetailFeatureState<Feature, Content, Track>>
    fun open(feature: Feature)
    fun activate(feature: Feature)
    fun close()
    fun refresh()
    fun loadMore()
    fun prefetchIfNeeded(visibleIndex: Int)
    fun play(index: Int)
    fun playAll()
}

fun <Feature, Content, Track> createProviderFeatureDetailFeatureOwner(
    port: ProviderFeatureDetailPort<Feature, Content, Track>,
    scope: CoroutineScope,
): ProviderFeatureDetailFeatureOwner<Feature, Content, Track> =
    DefaultProviderFeatureDetailFeatureOwner(port, scope)

private class DefaultProviderFeatureDetailFeatureOwner<Feature, Content, Track>(
    private val port: ProviderFeatureDetailPort<Feature, Content, Track>,
    private val scope: CoroutineScope,
) : ProviderFeatureDetailFeatureOwner<Feature, Content, Track> {
    private val mutableState = MutableStateFlow(ProviderFeatureDetailFeatureState<Feature, Content, Track>())
    override val state: StateFlow<ProviderFeatureDetailFeatureState<Feature, Content, Track>> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    override fun open(feature: Feature) {
        port.open(feature)
        activate(feature)
    }

    override fun activate(feature: Feature) {
        val current = state.value
        if (current.feature?.let(port::featureId) == port.featureId(feature) &&
            (current.content != null || current.isLoading)
        ) return
        load(feature, reset = true)
    }

    override fun close() {
        loadJob?.cancel()
        mutableState.value = ProviderFeatureDetailFeatureState()
        port.close()
    }

    override fun refresh() {
        state.value.feature?.let { load(it, reset = true) }
    }

    override fun loadMore() {
        val current = state.value
        val feature = current.feature ?: return
        if (current.isLoading || !current.hasMore || port.isDynamicQueueFeature(feature)) return
        load(feature, reset = false)
    }

    override fun prefetchIfNeeded(visibleIndex: Int) {
        val current = state.value
        val count = current.content?.let(port::contentCount) ?: current.tracks.size
        if (count - visibleIndex <= LIST_PREFETCH_REMAINING) loadMore()
    }

    override fun play(index: Int) {
        val current = state.value
        val feature = current.feature ?: return
        if (index !in current.tracks.indices) return
        port.playFeatureTracks(current.tracks, index, feature)
    }

    override fun playAll() {
        val current = state.value
        val feature = current.feature ?: return
        if (current.tracks.isEmpty()) return
        if (port.isDynamicQueueFeature(feature)) {
            port.playFeatureTracks(current.tracks, 0, feature)
            return
        }
        scope.launch {
            ensureAllPages()
            val latest = state.value
            if (latest.feature?.let(port::featureId) == port.featureId(feature) && latest.tracks.isNotEmpty()) {
                port.playTracks(latest.tracks, 0)
            }
        }
    }

    private fun load(feature: Feature, reset: Boolean) {
        if (reset) loadJob?.cancel()
        loadJob = scope.launch {
            val before = state.value
            val offset = if (reset) 0 else before.nextOffset
            mutableState.value = if (reset) {
                ProviderFeatureDetailFeatureState(
                    feature = feature,
                    isLoading = true,
                    message = "正在加载：${port.featureTitle(feature)}",
                )
            } else {
                before.copy(isLoading = true, errorMessage = null)
            }
            runCatching {
                withTimeout(DETAIL_TIMEOUT_MS) { port.loadPage(feature, offset) }
            }.onSuccess { page ->
                if (state.value.feature?.let(port::featureId) != port.featureId(feature)) return@onSuccess
                val merged = port.mergeContent(if (reset) null else state.value.content, page)
                val tracks = port.contentTracks(merged)
                val loginRequired = port.contentIsLoginRequired(merged)
                val contentError = port.contentErrorMessage(merged)
                mutableState.value = state.value.copy(
                    content = merged,
                    tracks = tracks,
                    nextOffset = port.contentNextOffset(merged),
                    hasMore = port.contentHasMore(merged),
                    isLoading = false,
                    message = when {
                        loginRequired -> "登录后显示 ${port.contentProviderName(merged)} 的个性化内容"
                        contentError != null -> contentError
                        port.contentCount(merged) == 0 -> "${port.featureTitle(feature)} 暂无内容"
                        else -> "${port.featureTitle(feature)} · ${port.contentCount(merged)} 项"
                    },
                    errorMessage = if (loginRequired) {
                        "登录后显示 ${port.contentProviderName(merged)} 的个性化内容"
                    } else {
                        contentError
                    },
                )
            }.onFailure { throwable ->
                if (state.value.feature?.let(port::featureId) == port.featureId(feature)) {
                    val error = port.errorMessage(throwable, "加载失败", port.featureProviderId(feature))
                    mutableState.value = state.value.copy(
                        isLoading = false,
                        message = error,
                        errorMessage = error,
                    )
                }
            }
        }
    }

    private suspend fun ensureAllPages() {
        while (state.value.hasMore) {
            val before = state.value
            val feature = before.feature ?: return
            if (port.isDynamicQueueFeature(feature)) return
            val offset = before.nextOffset
            val page = runCatching {
                withTimeout(DETAIL_TIMEOUT_MS) { port.loadPage(feature, offset) }
            }.getOrElse {
                mutableState.value = before.copy(
                    errorMessage = port.errorMessage(it, "加载失败", port.featureProviderId(feature)),
                )
                return
            }
            if (state.value.feature?.let(port::featureId) != port.featureId(feature)) return
            val merged = port.mergeContent(state.value.content, page)
            mutableState.value = state.value.copy(
                content = merged,
                tracks = port.contentTracks(merged),
                nextOffset = port.contentNextOffset(merged),
                hasMore = port.contentHasMore(merged),
            )
            if (port.contentNextOffset(merged) == offset && port.contentCount(page) == 0) return
        }
    }
}

data class ProviderPlaylistDetailPage<Playlist, Track>(
    val playlist: Playlist,
    val tracks: List<Track>,
    val nextOffset: Int,
    val hasMore: Boolean,
)

data class ProviderDetailMutationResult(
    val success: Boolean,
    val message: String = "",
)

data class ProviderPlaylistDetailFeatureState<Playlist, Category, Track>(
    val playlist: Playlist? = null,
    val category: Category? = null,
    val tracks: List<Track> = emptyList(),
    val nextOffset: Int = 0,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

interface ProviderPlaylistDetailPort<Playlist, Category, Track> {
    suspend fun loadPage(playlist: Playlist, offset: Int): ProviderPlaylistDetailPage<Playlist, Track>
    suspend fun removeTrack(playlist: Playlist, track: Track): ProviderDetailMutationResult
    suspend fun deletePlaylist(playlist: Playlist): ProviderDetailMutationResult
    suspend fun recordPlayback(playlist: Playlist)
    fun playlistId(playlist: Playlist): String
    fun playlistTitle(playlist: Playlist): String
    fun playlistProviderId(playlist: Playlist): String
    fun trackId(track: Track): String
    fun trackTitle(track: Track): String
    fun trackBelongsToProvider(track: Track, providerId: String): Boolean
    fun isMinePlaylistCategory(category: Category?): Boolean
    fun isLoggedIn(providerId: String): Boolean
    fun canRemoveSongFromPlaylist(providerId: String): Boolean
    fun canDeletePlaylist(providerId: String): Boolean
    fun errorMessage(throwable: Throwable, fallback: String, providerId: String?): String
    fun open(playlist: Playlist, category: Category?)
    fun close()
    fun playPlaylistTracks(tracks: List<Track>, index: Int, playlistId: String)
    fun playAllPlaylistTracks(tracks: List<Track>, playlistId: String)
    fun appendPlaylistTracks(playlistId: String, tracks: List<Track>)
    fun onProviderMutation(providerId: String)
}

interface ProviderPlaylistDetailFeatureOwner<Playlist, Category, Track> {
    val state: StateFlow<ProviderPlaylistDetailFeatureState<Playlist, Category, Track>>
    fun open(playlist: Playlist, category: Category? = null)
    fun activate(playlist: Playlist, category: Category? = null)
    fun close()
    fun refresh()
    fun loadMore()
    fun prefetchIfNeeded(visibleIndex: Int)
    fun play(index: Int)
    fun playAll()
    fun canRemove(track: Track): Boolean
    fun remove(track: Track)
    fun canDelete(): Boolean
    fun delete()
}

fun <Playlist, Category, Track> createProviderPlaylistDetailFeatureOwner(
    port: ProviderPlaylistDetailPort<Playlist, Category, Track>,
    scope: CoroutineScope,
): ProviderPlaylistDetailFeatureOwner<Playlist, Category, Track> =
    DefaultProviderPlaylistDetailFeatureOwner(port, scope)

private class DefaultProviderPlaylistDetailFeatureOwner<Playlist, Category, Track>(
    private val port: ProviderPlaylistDetailPort<Playlist, Category, Track>,
    private val scope: CoroutineScope,
) : ProviderPlaylistDetailFeatureOwner<Playlist, Category, Track> {
    private val mutableState = MutableStateFlow(ProviderPlaylistDetailFeatureState<Playlist, Category, Track>())
    override val state: StateFlow<ProviderPlaylistDetailFeatureState<Playlist, Category, Track>> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var playbackPaginationJob: Job? = null

    override fun open(playlist: Playlist, category: Category?) {
        port.open(playlist, category)
        activate(playlist, category)
    }

    override fun activate(playlist: Playlist, category: Category?) {
        val current = state.value
        if (current.playlist?.let(port::playlistId) == port.playlistId(playlist) &&
            current.category == category &&
            (current.tracks.isNotEmpty() || current.isLoading)
        ) return
        load(playlist, category, reset = true)
    }

    override fun close() {
        loadJob?.cancel()
        playbackPaginationJob?.cancel()
        mutableState.value = ProviderPlaylistDetailFeatureState()
        port.close()
    }

    override fun refresh() {
        val current = state.value
        current.playlist?.let { load(it, current.category, reset = true) }
    }

    override fun loadMore() {
        val current = state.value
        val playlist = current.playlist ?: return
        if (current.isLoading || !current.hasMore) return
        load(playlist, current.category, reset = false)
    }

    override fun prefetchIfNeeded(visibleIndex: Int) {
        val current = state.value
        if (current.tracks.size - visibleIndex <= LIST_PREFETCH_REMAINING) loadMore()
    }

    override fun play(index: Int) {
        val current = state.value
        val playlist = current.playlist ?: return
        if (index !in current.tracks.indices) return
        scope.launch { port.recordPlayback(playlist) }
        port.playPlaylistTracks(current.tracks, index, port.playlistId(playlist))
        startPlaybackPagination(playlist)
    }

    override fun playAll() {
        val current = state.value
        val playlist = current.playlist ?: return
        if (current.tracks.isEmpty()) return
        scope.launch { port.recordPlayback(playlist) }
        port.playAllPlaylistTracks(current.tracks, port.playlistId(playlist))
        startPlaybackPagination(playlist)
    }

    override fun canRemove(track: Track): Boolean {
        val current = state.value
        val playlist = current.playlist ?: return false
        val providerId = port.playlistProviderId(playlist)
        return port.isMinePlaylistCategory(current.category) &&
            port.trackBelongsToProvider(track, providerId) &&
            port.isLoggedIn(providerId) &&
            port.canRemoveSongFromPlaylist(providerId)
    }

    override fun remove(track: Track) {
        val playlist = state.value.playlist ?: return
        if (!canRemove(track)) return
        scope.launch {
            runCatching { port.removeTrack(playlist, track) }
                .onSuccess { result ->
                    if (result.success) {
                        mutableState.value = state.value.copy(
                            tracks = state.value.tracks.filterNot { port.trackId(it) == port.trackId(track) },
                            message = result.message.ifBlank { "已从歌单移除：${port.trackTitle(track)}" },
                            errorMessage = null,
                        )
                        port.onProviderMutation(port.playlistProviderId(playlist))
                    } else {
                        mutableState.value = state.value.copy(errorMessage = result.message.ifBlank { "移除失败" })
                    }
                }
                .onFailure {
                    mutableState.value = state.value.copy(
                        errorMessage = port.errorMessage(it, "移除失败", port.playlistProviderId(playlist)),
                    )
                }
        }
    }

    override fun canDelete(): Boolean {
        val current = state.value
        val playlist = current.playlist ?: return false
        val providerId = port.playlistProviderId(playlist)
        return port.isMinePlaylistCategory(current.category) &&
            port.isLoggedIn(providerId) &&
            port.canDeletePlaylist(providerId)
    }

    override fun delete() {
        val playlist = state.value.playlist ?: return
        if (!canDelete()) return
        scope.launch {
            mutableState.value = state.value.copy(isLoading = true, message = "正在删除歌单", errorMessage = null)
            runCatching { port.deletePlaylist(playlist) }
                .onSuccess { result ->
                    if (result.success) {
                        port.onProviderMutation(port.playlistProviderId(playlist))
                        close()
                    } else {
                        val error = result.message.ifBlank { "删除歌单失败" }
                        mutableState.value = state.value.copy(isLoading = false, message = error, errorMessage = error)
                    }
                }
                .onFailure {
                    mutableState.value = state.value.copy(
                        isLoading = false,
                        errorMessage = port.errorMessage(it, "删除歌单失败", port.playlistProviderId(playlist)),
                    )
                }
        }
    }

    private fun load(playlist: Playlist, category: Category?, reset: Boolean) {
        if (reset) loadJob?.cancel()
        loadJob = scope.launch {
            val before = state.value
            val offset = if (reset) 0 else before.nextOffset
            mutableState.value = if (reset) {
                ProviderPlaylistDetailFeatureState(
                    playlist = playlist,
                    category = category,
                    isLoading = true,
                    message = "正在加载：${port.playlistTitle(playlist)}",
                )
            } else {
                before.copy(isLoading = true, errorMessage = null)
            }
            runCatching {
                withTimeout(DETAIL_TIMEOUT_MS) { port.loadPage(playlist, offset) }
            }.onSuccess { page ->
                if (state.value.playlist?.let(port::playlistId) != port.playlistId(playlist)) return@onSuccess
                val tracks = if (reset) page.tracks else mergeById(state.value.tracks, page.tracks, port::trackId)
                mutableState.value = state.value.copy(
                    playlist = page.playlist,
                    tracks = tracks,
                    nextOffset = page.nextOffset,
                    hasMore = page.hasMore,
                    isLoading = false,
                    message = if (tracks.isEmpty()) "歌单暂无歌曲" else "${port.playlistTitle(page.playlist)} · ${tracks.size} 首",
                    errorMessage = null,
                )
            }.onFailure { throwable ->
                if (state.value.playlist?.let(port::playlistId) == port.playlistId(playlist)) {
                    mutableState.value = state.value.copy(
                        isLoading = false,
                        errorMessage = port.errorMessage(throwable, "加载失败", port.playlistProviderId(playlist)),
                    )
                }
            }
        }
    }

    private fun startPlaybackPagination(playlist: Playlist) {
        playbackPaginationJob?.cancel()
        if (!state.value.hasMore) return
        playbackPaginationJob = scope.launch {
            while (state.value.playlist?.let(port::playlistId) == port.playlistId(playlist) && state.value.hasMore) {
                delay(PLAYLIST_BACKGROUND_PAGE_INTERVAL_MS)
                val before = state.value
                val offset = before.nextOffset
                val page = runCatching {
                    withTimeout(DETAIL_TIMEOUT_MS) { port.loadPage(playlist, offset) }
                }.getOrElse { return@launch }
                if (state.value.playlist?.let(port::playlistId) != port.playlistId(playlist)) return@launch
                val existingIds = before.tracks.mapTo(mutableSetOf(), port::trackId)
                val newTracks = page.tracks.filter { existingIds.add(port.trackId(it)) }
                val tracks = before.tracks + newTracks
                mutableState.value = before.copy(
                    playlist = page.playlist,
                    tracks = tracks,
                    nextOffset = page.nextOffset,
                    hasMore = page.hasMore,
                )
                port.appendPlaylistTracks(port.playlistId(playlist), newTracks)
                if (page.nextOffset == offset && newTracks.isEmpty()) return@launch
            }
        }
    }
}

data class ProviderTrackDetailFeatureState<Track, Comment, Video>(
    val track: Track? = null,
    val similarTracks: List<Track> = emptyList(),
    val comments: List<Comment> = emptyList(),
    val video: Video? = null,
    val relatedErrorMessage: String? = null,
    val isLoading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

interface ProviderTrackDetailPort<Track, Comment, Video> {
    suspend fun loadTrackDetail(track: Track): Track
    suspend fun similarTracks(track: Track): List<Track>
    suspend fun hotComments(track: Track): List<Comment>
    suspend fun trackVideo(track: Track): Video?
    fun isProviderTrack(track: Track): Boolean
    fun trackId(track: Track): String
    fun trackTitle(track: Track): String
    fun trackDetailId(track: Track): String
    fun trackProviderId(track: Track): String?
    fun errorMessage(throwable: Throwable, fallback: String, providerId: String?): String
    fun open(track: Track)
    fun close()
    fun playTracks(tracks: List<Track>, index: Int)
    fun openVideo(video: Video)
}

interface ProviderTrackDetailFeatureOwner<Track, Comment, Video> {
    val state: StateFlow<ProviderTrackDetailFeatureState<Track, Comment, Video>>
    fun open(track: Track)
    fun activate(track: Track)
    fun close()
    fun refresh()
    fun play()
    fun playSimilar(index: Int)
    fun openVideo()
}

fun <Track, Comment, Video> createProviderTrackDetailFeatureOwner(
    port: ProviderTrackDetailPort<Track, Comment, Video>,
    scope: CoroutineScope,
): ProviderTrackDetailFeatureOwner<Track, Comment, Video> = DefaultProviderTrackDetailFeatureOwner(port, scope)

private class DefaultProviderTrackDetailFeatureOwner<Track, Comment, Video>(
    private val port: ProviderTrackDetailPort<Track, Comment, Video>,
    private val scope: CoroutineScope,
) : ProviderTrackDetailFeatureOwner<Track, Comment, Video> {
    private val mutableState = MutableStateFlow(ProviderTrackDetailFeatureState<Track, Comment, Video>())
    override val state: StateFlow<ProviderTrackDetailFeatureState<Track, Comment, Video>> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    override fun open(track: Track) {
        if (!port.isProviderTrack(track)) return
        port.open(track)
        activate(track)
    }

    override fun activate(track: Track) {
        if (!port.isProviderTrack(track)) return
        val current = state.value
        if (current.track?.let(port::trackId) == port.trackId(track) &&
            (current.similarTracks.isNotEmpty() || current.isLoading)
        ) return
        mutableState.value = ProviderTrackDetailFeatureState(track = track)
        refresh()
    }

    override fun close() {
        loadJob?.cancel()
        mutableState.value = ProviderTrackDetailFeatureState()
        port.close()
    }

    override fun refresh() {
        val baseTrack = state.value.track ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            mutableState.value = state.value.copy(isLoading = true, message = "正在加载：${port.trackTitle(baseTrack)}")
            val detailTrack = runCatching {
                withTimeout(DETAIL_TIMEOUT_MS) { port.loadTrackDetail(baseTrack) }
            }.getOrDefault(baseTrack)
            if (state.value.track?.let(port::trackId) != port.trackId(baseTrack)) return@launch
            mutableState.value = state.value.copy(track = detailTrack)

            val similar = runCatching { port.similarTracks(detailTrack) }
            val comments = runCatching { port.hotComments(detailTrack) }
            val video = runCatching { port.trackVideo(detailTrack) }
            val currentId = state.value.track?.let(port::trackId)
            if (currentId != port.trackId(detailTrack) && currentId != port.trackId(baseTrack)) return@launch
            val relatedFailures = listOf(similar.exceptionOrNull(), comments.exceptionOrNull(), video.exceptionOrNull())
                .filterNotNull()
            mutableState.value = state.value.copy(
                track = detailTrack,
                similarTracks = similar.getOrDefault(emptyList()),
                comments = comments.getOrDefault(emptyList()),
                video = video.getOrNull(),
                relatedErrorMessage = relatedFailures.firstOrNull()?.let {
                    port.errorMessage(it, "相关内容加载失败", port.trackProviderId(detailTrack))
                },
                isLoading = false,
                message = port.trackTitle(detailTrack).ifBlank { "歌曲已加载" },
                errorMessage = null,
            )
        }
    }

    override fun play() {
        state.value.track?.let { port.playTracks(listOf(it), 0) }
    }

    override fun playSimilar(index: Int) {
        val tracks = state.value.similarTracks
        if (index in tracks.indices) port.playTracks(tracks, index)
    }

    override fun openVideo() {
        state.value.video?.let(port::openVideo)
    }
}

data class ProviderMediaItemDetailPage<Item, Track>(
    val item: Item,
    val tracks: List<Track>,
    val albums: List<Item>,
    val tracksNextOffset: Int,
    val tracksHasMore: Boolean,
    val albumsNextOffset: Int,
    val albumsHasMore: Boolean,
)

data class ProviderMediaItemDetailFeatureState<Item, Track>(
    val item: Item? = null,
    val tracks: List<Track> = emptyList(),
    val albums: List<Item> = emptyList(),
    val tracksNextOffset: Int = 0,
    val tracksHasMore: Boolean = false,
    val albumsNextOffset: Int = 0,
    val albumsHasMore: Boolean = false,
    val isLoading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

interface ProviderMediaItemDetailPort<Item, Track> {
    suspend fun loadPage(item: Item, tracksOffset: Int, albumsOffset: Int): ProviderMediaItemDetailPage<Item, Track>
    fun itemId(item: Item): String
    fun itemTitle(item: Item): String
    fun itemProviderId(item: Item): String?
    fun trackId(track: Track): String
    fun errorMessage(throwable: Throwable, fallback: String, providerId: String?): String
    fun open(item: Item)
    fun close()
    fun playTracks(tracks: List<Track>, index: Int)
}

interface ProviderMediaItemDetailFeatureOwner<Item, Track> {
    val state: StateFlow<ProviderMediaItemDetailFeatureState<Item, Track>>
    fun open(item: Item)
    fun activate(item: Item)
    fun close()
    fun refresh()
    fun prefetchTracksIfNeeded(visibleIndex: Int)
    fun prefetchAlbumsIfNeeded(visibleIndex: Int)
    fun play(index: Int)
    fun playAll()
}

fun <Item, Track> createProviderMediaItemDetailFeatureOwner(
    port: ProviderMediaItemDetailPort<Item, Track>,
    scope: CoroutineScope,
): ProviderMediaItemDetailFeatureOwner<Item, Track> = DefaultProviderMediaItemDetailFeatureOwner(port, scope)

private class DefaultProviderMediaItemDetailFeatureOwner<Item, Track>(
    private val port: ProviderMediaItemDetailPort<Item, Track>,
    private val scope: CoroutineScope,
) : ProviderMediaItemDetailFeatureOwner<Item, Track> {
    private val mutableState = MutableStateFlow(ProviderMediaItemDetailFeatureState<Item, Track>())
    override val state: StateFlow<ProviderMediaItemDetailFeatureState<Item, Track>> = mutableState.asStateFlow()
    private var tracksJob: Job? = null
    private var albumsJob: Job? = null

    override fun open(item: Item) {
        port.open(item)
        activate(item)
    }

    override fun activate(item: Item) {
        val current = state.value
        if (current.item?.let(port::itemId) == port.itemId(item) &&
            (current.tracks.isNotEmpty() || current.albums.isNotEmpty() || current.isLoading)
        ) return
        loadInitial(item)
    }

    override fun close() {
        tracksJob?.cancel()
        albumsJob?.cancel()
        mutableState.value = ProviderMediaItemDetailFeatureState()
        port.close()
    }

    override fun refresh() {
        state.value.item?.let(::loadInitial)
    }

    override fun prefetchTracksIfNeeded(visibleIndex: Int) {
        val current = state.value
        if (current.tracks.size - visibleIndex <= LIST_PREFETCH_REMAINING) loadMoreTracks()
    }

    override fun prefetchAlbumsIfNeeded(visibleIndex: Int) {
        val current = state.value
        if (current.albums.size - visibleIndex <= LIST_PREFETCH_REMAINING) loadMoreAlbums()
    }

    override fun play(index: Int) {
        val tracks = state.value.tracks
        if (index in tracks.indices) port.playTracks(tracks, index)
    }

    override fun playAll() {
        val item = state.value.item ?: return
        scope.launch {
            ensureAllTracks(item)
            val current = state.value
            if (current.item?.let(port::itemId) == port.itemId(item) && current.tracks.isNotEmpty()) {
                port.playTracks(current.tracks, 0)
            }
        }
    }

    private fun loadInitial(item: Item) {
        tracksJob?.cancel()
        albumsJob?.cancel()
        tracksJob = scope.launch {
            mutableState.value = ProviderMediaItemDetailFeatureState(
                item = item,
                isLoading = true,
                message = "正在加载：${port.itemTitle(item)}",
            )
            runCatching {
                withTimeout(DETAIL_TIMEOUT_MS) { port.loadPage(item, tracksOffset = 0, albumsOffset = 0) }
            }.onSuccess { page ->
                if (state.value.item?.let(port::itemId) != port.itemId(item)) return@onSuccess
                mutableState.value = ProviderMediaItemDetailFeatureState(
                    item = page.item,
                    tracks = page.tracks,
                    albums = page.albums,
                    tracksNextOffset = page.tracksNextOffset,
                    tracksHasMore = page.tracksHasMore,
                    albumsNextOffset = page.albumsNextOffset,
                    albumsHasMore = page.albumsHasMore,
                    isLoading = false,
                    message = buildList {
                        if (page.tracks.isNotEmpty()) add("${page.tracks.size} 首")
                        if (page.albums.isNotEmpty()) add("${page.albums.size} 张专辑")
                    }.joinToString(" · ").ifBlank { "${port.itemTitle(page.item)} 暂无内容" },
                )
            }.onFailure {
                if (state.value.item?.let(port::itemId) == port.itemId(item)) {
                    mutableState.value = state.value.copy(
                        isLoading = false,
                        errorMessage = port.errorMessage(it, "加载失败", port.itemProviderId(item)),
                    )
                }
            }
        }
    }

    private fun loadMoreTracks() {
        val before = state.value
        val item = before.item ?: return
        if (!before.tracksHasMore || tracksJob?.isActive == true) return
        tracksJob = scope.launch {
            val offset = before.tracksNextOffset
            val page = runCatching {
                withTimeout(DETAIL_TIMEOUT_MS) { port.loadPage(item, offset, before.albumsNextOffset) }
            }.getOrElse {
                mutableState.value = state.value.copy(
                    errorMessage = port.errorMessage(it, "加载失败", port.itemProviderId(item)),
                )
                return@launch
            }
            if (state.value.item?.let(port::itemId) != port.itemId(item)) return@launch
            mutableState.value = state.value.copy(
                item = page.item,
                tracks = mergeById(state.value.tracks, page.tracks, port::trackId),
                tracksNextOffset = page.tracksNextOffset,
                tracksHasMore = page.tracksHasMore,
                errorMessage = null,
            )
        }
    }

    private fun loadMoreAlbums() {
        val before = state.value
        val item = before.item ?: return
        if (!before.albumsHasMore || albumsJob?.isActive == true) return
        albumsJob = scope.launch {
            val offset = before.albumsNextOffset
            val page = runCatching {
                withTimeout(DETAIL_TIMEOUT_MS) { port.loadPage(item, before.tracksNextOffset, offset) }
            }.getOrElse {
                mutableState.value = state.value.copy(
                    errorMessage = port.errorMessage(it, "加载失败", port.itemProviderId(item)),
                )
                return@launch
            }
            if (state.value.item?.let(port::itemId) != port.itemId(item)) return@launch
            mutableState.value = state.value.copy(
                item = page.item,
                albums = mergeById(state.value.albums, page.albums, port::itemId),
                albumsNextOffset = page.albumsNextOffset,
                albumsHasMore = page.albumsHasMore,
                errorMessage = null,
            )
        }
    }

    private suspend fun ensureAllTracks(item: Item) {
        tracksJob?.join()
        while (state.value.item?.let(port::itemId) == port.itemId(item) && state.value.tracksHasMore) {
            val before = state.value
            val offset = before.tracksNextOffset
            val page = runCatching {
                withTimeout(DETAIL_TIMEOUT_MS) { port.loadPage(item, offset, before.albumsNextOffset) }
            }.getOrElse { return }
            val tracks = mergeById(before.tracks, page.tracks, port::trackId)
            mutableState.value = before.copy(
                item = page.item,
                tracks = tracks,
                tracksNextOffset = page.tracksNextOffset,
                tracksHasMore = page.tracksHasMore,
            )
            if (page.tracksNextOffset == offset && tracks.size == before.tracks.size) return
        }
    }
}

data class ProviderVideoDetailFeatureState<Video, Payload>(
    val video: Video? = null,
    val payload: Payload? = null,
    val isFullscreen: Boolean = false,
    val isLoading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

data class ProviderVideoPlaybackResult<Video, Payload>(
    val video: Video,
    val payload: Payload,
)

interface ProviderVideoDetailPort<Video, Payload> {
    suspend fun loadPlayback(video: Video): ProviderVideoPlaybackResult<Video, Payload>
    fun videoId(video: Video): String
    fun videoTitle(video: Video): String
    fun videoProviderId(video: Video): String?
    fun errorMessage(throwable: Throwable, fallback: String, providerId: String?): String
    fun open(video: Video)
    fun close()
}

interface ProviderVideoDetailFeatureOwner<Video, Payload> {
    val state: StateFlow<ProviderVideoDetailFeatureState<Video, Payload>>
    fun open(video: Video)
    fun activate(video: Video)
    fun close()
    fun refresh()
    fun toggleFullscreen()
}

fun <Video, Payload> createProviderVideoDetailFeatureOwner(
    port: ProviderVideoDetailPort<Video, Payload>,
    scope: CoroutineScope,
): ProviderVideoDetailFeatureOwner<Video, Payload> = DefaultProviderVideoDetailFeatureOwner(port, scope)

private class DefaultProviderVideoDetailFeatureOwner<Video, Payload>(
    private val port: ProviderVideoDetailPort<Video, Payload>,
    private val scope: CoroutineScope,
) : ProviderVideoDetailFeatureOwner<Video, Payload> {
    private val mutableState = MutableStateFlow(ProviderVideoDetailFeatureState<Video, Payload>())
    override val state: StateFlow<ProviderVideoDetailFeatureState<Video, Payload>> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    override fun open(video: Video) {
        port.open(video)
        activate(video)
    }

    override fun activate(video: Video) {
        val current = state.value
        if (current.video?.let(port::videoId) == port.videoId(video) &&
            (current.payload != null || current.isLoading)
        ) return
        mutableState.value = ProviderVideoDetailFeatureState(video = video)
        refresh()
    }

    override fun close() {
        loadJob?.cancel()
        mutableState.value = ProviderVideoDetailFeatureState()
        port.close()
    }

    override fun refresh() {
        val video = state.value.video ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            mutableState.value = state.value.copy(
                isLoading = true,
                message = "正在加载视频：${port.videoTitle(video)}",
                errorMessage = null,
            )
            runCatching {
                withTimeout(VIDEO_TIMEOUT_MS) { port.loadPlayback(video) }
            }.onSuccess { result ->
                if (state.value.video?.let(port::videoId) != port.videoId(video)) return@onSuccess
                mutableState.value = state.value.copy(
                    video = result.video,
                    payload = result.payload,
                    isLoading = false,
                    message = "正在播放视频：${port.videoTitle(result.video)}",
                    errorMessage = null,
                )
            }.onFailure {
                if (state.value.video?.let(port::videoId) == port.videoId(video)) {
                    mutableState.value = state.value.copy(
                        isLoading = false,
                        message = "视频加载失败",
                        errorMessage = port.errorMessage(it, "视频加载失败", port.videoProviderId(video)),
                    )
                }
            }
        }
    }

    override fun toggleFullscreen() {
        mutableState.value = state.value.copy(isFullscreen = !state.value.isFullscreen)
    }
}

private fun <T> mergeById(current: List<T>, next: List<T>, id: (T) -> String): List<T> {
    val seen = current.mapTo(mutableSetOf(), id)
    return current + next.filter { seen.add(id(it)) }
}
