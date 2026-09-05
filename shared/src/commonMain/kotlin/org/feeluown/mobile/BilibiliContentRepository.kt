package org.feeluown.mobile

import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.network.ProviderPersistentCache

/** Adds Bilibili browsing surfaces at the content edge only. */
internal class BilibiliContentRepository(
    private val catalogDelegate: ProviderCatalogRepository,
    private val libraryDelegate: ProviderLibraryRepository,
    private val bilibili: KotlinMusicProvider,
) : ProviderContentRepository,
    ProviderCatalogRepository by catalogDelegate,
    ProviderLibraryRepository by libraryDelegate {
    override suspend fun features(): List<ProviderFeature> {
        val base = catalogDelegate.features()
        if (base.none { it.providerId == BILIBILI_PROVIDER_ID }) return base
        val presentedBilibiliFeatures = bilibili.features.mapNotNull { feature ->
            when (feature.id) {
                BILIBILI_HISTORY_FEATURE_ID -> null
                BILIBILI_WATCH_LATER_FEATURE_ID -> feature.copy(title = "观看记录")
                else -> feature
            }
        }
        return buildList {
            var insertedBilibili = false
            base.forEach { feature ->
                if (feature.providerId == BILIBILI_PROVIDER_ID) {
                    if (!insertedBilibili) {
                        addAll(presentedBilibiliFeatures)
                        insertedBilibili = true
                    }
                } else add(feature)
            }
        }
    }

    override suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection = loadFeaturePage(feature, 0, PROVIDER_PAGE_SIZE)
    override suspend fun loadFeaturePage(feature: ProviderFeature, offset: Int, limit: Int): ProviderContentSection =
        if (feature.providerId == BILIBILI_PROVIDER_ID) {
            val section = bilibili.loadFeature(feature, offset, limit)
            if (feature.id == BILIBILI_WATCH_LATER_FEATURE_ID && offset == 0 && !section.isLoginRequired && section.errorMessage == null) {
                section.copy(playlists = (section.playlists + historyPlaylist()).distinctBy { it.id })
            } else section
        } else catalogDelegate.loadFeaturePage(feature, offset, limit)

    override suspend fun loadMoreFeatureTracks(feature: ProviderFeature): List<MusicTrack> =
        if (feature.providerId == BILIBILI_PROVIDER_ID) bilibili.loadFeature(feature, 0, PROVIDER_PAGE_SIZE).tracks
        else catalogDelegate.loadMoreFeatureTracks(feature)

    override suspend fun playlistDetail(playlist: ProviderPlaylist): ProviderPlaylistDetail = playlistDetailPage(playlist, 0, PROVIDER_PAGE_SIZE)
    override suspend fun playlistDetailPage(playlist: ProviderPlaylist, offset: Int, limit: Int): ProviderPlaylistDetail = when {
        playlist.isBilibiliHistoryPlaylist() -> loadHistoryPlaylistDetail(playlist, offset, limit)
        playlist.providerId == BILIBILI_PROVIDER_ID -> bilibili.playlistDetail(playlist, offset, limit)
        else -> catalogDelegate.playlistDetailPage(playlist, offset, limit)
    }
    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> = when {
        playlist.isBilibiliHistoryPlaylist() -> loadHistoryPlaylistDetail(playlist, 0, PROVIDER_PAGE_SIZE).tracks
        playlist.providerId == BILIBILI_PROVIDER_ID -> bilibili.playlistTracks(playlist)
        else -> catalogDelegate.playlistTracks(playlist)
    }
    override suspend fun playlistOperationTargets(track: MusicTrack): List<ProviderPlaylist> =
        if (track.source == BILIBILI_PROVIDER_ID) bilibili.playlistOperationTargets(track) else libraryDelegate.playlistOperationTargets(track)
    override suspend fun addTrackToPlaylist(playlist: ProviderPlaylist, track: MusicTrack): ProviderMutationResult = when {
        playlist.isBilibiliHistoryPlaylist() -> ProviderMutationResult(false, "历史记录不支持添加歌曲")
        playlist.providerId == BILIBILI_PROVIDER_ID -> bilibili.addTrackToPlaylist(playlist, track)
        else -> libraryDelegate.addTrackToPlaylist(playlist, track)
    }
    override suspend fun removeTrackFromPlaylist(playlist: ProviderPlaylist, track: MusicTrack): ProviderMutationResult = when {
        playlist.isBilibiliHistoryPlaylist() -> ProviderMutationResult(false, "历史记录不支持移除歌曲")
        playlist.providerId == BILIBILI_PROVIDER_ID -> bilibili.removeTrackFromPlaylist(playlist, track)
        else -> libraryDelegate.removeTrackFromPlaylist(playlist, track)
    }
    override suspend fun mediaItemDetail(item: ProviderMediaItem): ProviderMediaItemDetail =
        if (item.providerId == BILIBILI_PROVIDER_ID) bilibili.mediaItemDetail(item, 0, 0, PROVIDER_PAGE_SIZE)
        else catalogDelegate.mediaItemDetail(item)
    override suspend fun mediaItemDetailPage(item: ProviderMediaItem, tracksOffset: Int, albumsOffset: Int, limit: Int): ProviderMediaItemDetail =
        if (item.providerId == BILIBILI_PROVIDER_ID) bilibili.mediaItemDetail(item, tracksOffset, albumsOffset, limit)
        else catalogDelegate.mediaItemDetailPage(item, tracksOffset, albumsOffset, limit)
    override suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack> =
        if (item.providerId == BILIBILI_PROVIDER_ID) bilibili.mediaItemTracks(item) else catalogDelegate.mediaItemTracks(item)

    private suspend fun loadHistoryPlaylistDetail(playlist: ProviderPlaylist, offset: Int, limit: Int): ProviderPlaylistDetail {
        val feature = bilibili.features.first { it.id == BILIBILI_HISTORY_FEATURE_ID }
        val section = bilibili.loadFeature(feature, offset, limit)
        return ProviderPlaylistDetail(
            playlist = playlist.copy(coverUrl = section.tracks.firstOrNull()?.coverUrl ?: playlist.coverUrl),
            tracks = section.tracks,
            tracksNextOffset = section.nextOffset,
            tracksHasMore = section.hasMore,
        )
    }

    private fun historyPlaylist() = ProviderPlaylist(
        id = BILIBILI_HISTORY_PLAYLIST_ID,
        title = "历史记录",
        providerId = BILIBILI_PROVIDER_ID,
        providerName = BILIBILI_PROVIDER_NAME,
        description = "哔哩哔哩观看历史",
        providerUrl = "https://www.bilibili.com/account/history",
    )
    private fun ProviderPlaylist.isBilibiliHistoryPlaylist() = id == BILIBILI_HISTORY_PLAYLIST_ID

    private companion object {
        const val BILIBILI_PROVIDER_ID = "bilibili"
        const val BILIBILI_PROVIDER_NAME = "哔哩哔哩"
        const val BILIBILI_WATCH_LATER_FEATURE_ID = "bilibili_watch_later"
        const val BILIBILI_HISTORY_FEATURE_ID = "bilibili_history"
        const val BILIBILI_HISTORY_PLAYLIST_ID = "playlist:bilibili:history"
    }
}

/** Composition-only holder; only platform composition roots should see this type. */
class FuoProviderGraph internal constructor(
    val registry: ProviderRegistryRepository,
    val search: ProviderSearchRepository,
    val auth: ProviderAuthRepository,
    val content: ProviderContentRepository,
    val playbackSource: PlaybackProviderSourcePort,
    val audioQuality: ProviderAudioQualityPort,
)

fun createFuoProviderGraph(
    credentials: ProviderCredentialStore,
    persistentCache: ProviderPersistentCache? = null,
    isCellularConnection: () -> Boolean = { false },
): FuoProviderGraph {
    val base = createKotlinProviderRepository(credentials, persistentCache, isCellularConnection)
    val bilibili = ProviderComposition.createBilibiliContentProvider(credentials, persistentCache)
    val content = BilibiliContentRepository(base, base, bilibili)
    return FuoProviderGraph(
        registry = base,
        search = base,
        auth = base,
        content = content,
        playbackSource = base,
        audioQuality = base,
    )
}