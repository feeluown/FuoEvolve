package org.feeluown.mobile

import org.feeluown.mobile.provider.bilibili.BilibiliContentProvider
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderPersistentCache

/**
 * Adds Bilibili browsing surfaces without changing the core provider repository.
 * Playback/search/auth still go through [delegate]; only Bilibili content
 * discovery and its virtual resources are routed to [bilibili].
 */
internal class BilibiliContentRepository(
    private val delegate: ProviderMusicRepository,
    private val bilibili: BilibiliContentProvider,
) : ProviderMusicRepository by delegate {
    override suspend fun features(): List<ProviderFeature> {
        val base = delegate.features()
        if (base.none { it.providerId == BILIBILI_PROVIDER_ID }) return base
        return base.filterNot { it.providerId == BILIBILI_PROVIDER_ID } + bilibili.features
    }

    override suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection =
        if (feature.providerId == BILIBILI_PROVIDER_ID) {
            bilibili.loadFeature(feature, 0, PROVIDER_PAGE_SIZE)
        } else {
            delegate.loadFeature(feature)
        }

    override suspend fun loadFeaturePage(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection = if (feature.providerId == BILIBILI_PROVIDER_ID) {
        bilibili.loadFeature(feature, offset, limit)
    } else {
        delegate.loadFeaturePage(feature, offset, limit)
    }

    override suspend fun loadMoreFeatureTracks(feature: ProviderFeature): List<MusicTrack> =
        if (feature.providerId == BILIBILI_PROVIDER_ID) {
            bilibili.loadFeature(feature, 0, PROVIDER_PAGE_SIZE).tracks
        } else {
            delegate.loadMoreFeatureTracks(feature)
        }

    override suspend fun playlistDetail(playlist: ProviderPlaylist): ProviderPlaylistDetail =
        if (playlist.providerId == BILIBILI_PROVIDER_ID) {
            bilibili.playlistDetail(playlist, 0, PROVIDER_PAGE_SIZE)
        } else {
            delegate.playlistDetail(playlist)
        }

    override suspend fun playlistDetailPage(
        playlist: ProviderPlaylist,
        offset: Int,
        limit: Int,
    ): ProviderPlaylistDetail = if (playlist.providerId == BILIBILI_PROVIDER_ID) {
        bilibili.playlistDetail(playlist, offset, limit)
    } else {
        delegate.playlistDetailPage(playlist, offset, limit)
    }

    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> =
        if (playlist.providerId == BILIBILI_PROVIDER_ID) {
            bilibili.playlistTracks(playlist)
        } else {
            delegate.playlistTracks(playlist)
        }

    override suspend fun playlistOperationTargets(track: MusicTrack): List<ProviderPlaylist> =
        if (track.source == BILIBILI_PROVIDER_ID) {
            bilibili.playlistOperationTargets(track)
        } else {
            delegate.playlistOperationTargets(track)
        }

    override suspend fun addTrackToPlaylist(
        playlist: ProviderPlaylist,
        track: MusicTrack,
    ): ProviderMutationResult = if (playlist.providerId == BILIBILI_PROVIDER_ID) {
        bilibili.addTrackToPlaylist(playlist, track)
    } else {
        delegate.addTrackToPlaylist(playlist, track)
    }

    override suspend fun removeTrackFromPlaylist(
        playlist: ProviderPlaylist,
        track: MusicTrack,
    ): ProviderMutationResult = if (playlist.providerId == BILIBILI_PROVIDER_ID) {
        bilibili.removeTrackFromPlaylist(playlist, track)
    } else {
        delegate.removeTrackFromPlaylist(playlist, track)
    }

    override suspend fun mediaItemDetail(item: ProviderMediaItem): ProviderMediaItemDetail =
        if (item.providerId == BILIBILI_PROVIDER_ID) {
            bilibili.mediaItemDetail(item, 0, 0, PROVIDER_PAGE_SIZE)
        } else {
            delegate.mediaItemDetail(item)
        }

    override suspend fun mediaItemDetailPage(
        item: ProviderMediaItem,
        tracksOffset: Int,
        albumsOffset: Int,
        limit: Int,
    ): ProviderMediaItemDetail = if (item.providerId == BILIBILI_PROVIDER_ID) {
        bilibili.mediaItemDetail(item, tracksOffset, albumsOffset, limit)
    } else {
        delegate.mediaItemDetailPage(item, tracksOffset, albumsOffset, limit)
    }

    override suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack> =
        if (item.providerId == BILIBILI_PROVIDER_ID) {
            bilibili.mediaItemTracks(item)
        } else {
            delegate.mediaItemTracks(item)
        }

    private companion object {
        const val BILIBILI_PROVIDER_ID = "bilibili"
    }
}

fun createFuoProviderRepository(
    credentials: ProviderCredentialStore,
    persistentCache: ProviderPersistentCache? = null,
    isCellularConnection: () -> Boolean = { false },
): ProviderMusicRepository {
    val delegate = createKotlinProviderRepository(
        credentials = credentials,
        persistentCache = persistentCache,
        isCellularConnection = isCellularConnection,
    )
    val bilibili = BilibiliContentProvider(
        http = ProviderHttpClient(persistentCache = persistentCache),
        credentials = credentials,
    )
    return BilibiliContentRepository(delegate, bilibili)
}
