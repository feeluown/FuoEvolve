package org.feeluown.mobile

import org.feeluown.mobile.provider.qqmusic.QQMusicArtistDetailProvider
import org.feeluown.mobile.provider.qqmusic.QQMusicContentProvider
import org.feeluown.mobile.provider.qqmusic.QQMusicUserLibrary

/**
 * Adds QQ Music discovery/search surfaces without changing the core provider repository.
 * Playback and mutations still go through [delegate]; QQ-specific discovery and
 * user-library presentation are routed to their dedicated helpers.
 */
internal class QQMusicContentRepository(
    private val delegate: ProviderMusicRepository,
    private val qqmusic: QQMusicContentProvider,
    private val userLibrary: QQMusicUserLibrary,
    private val artistDetails: QQMusicArtistDetailProvider,
) : ProviderMusicRepository by delegate {
    override suspend fun features(): List<ProviderFeature> {
        val base = delegate.features()
        if (base.none { it.providerId == QQMUSIC_PROVIDER_ID }) return base
        val qqFeatures = (qqmusic.features + QQMUSIC_MINE_FEATURES).distinctBy { it.id }
        return buildList {
            var inserted = false
            base.forEach { feature ->
                if (feature.providerId == QQMUSIC_PROVIDER_ID) {
                    if (!inserted) {
                        addAll(qqFeatures)
                        inserted = true
                    }
                } else {
                    add(feature)
                }
            }
        }
    }

    override suspend fun search(keyword: String, providerId: String?): List<MusicTrack> =
        searchAll(keyword, providerId).tracks

    override suspend fun searchAll(keyword: String, providerId: String?): ProviderSearchResults {
        val base = delegate.searchAll(keyword, providerId)
        if (providerId != null && providerId != QQMUSIC_PROVIDER_ID) return base
        val qqEnabled = providerId == QQMUSIC_PROVIDER_ID ||
            delegate.features().any { it.providerId == QQMUSIC_PROVIDER_ID }
        if (!qqEnabled) return base
        val extras = runCatching { qqmusic.searchExtras(keyword) }.getOrElse { return base }
        return base.copy(
            playlists = replaceQQMusic(base.playlists, extras.playlists) { it.providerId },
            artists = replaceQQMusic(
                base.artists,
                extras.artists.map(artistDetails::normalizeArtist),
            ) { it.providerId },
            albums = replaceQQMusic(base.albums, extras.albums) { it.providerId },
            videos = replaceQQMusic(base.videos, extras.videos) { it.providerId },
        )
    }

    override suspend fun authState(providerId: String): ProviderAuthState {
        val base = delegate.authState(providerId)
        return if (providerId == QQMUSIC_PROVIDER_ID) enrichQQMusicAuth(base) else base
    }

    override suspend fun refreshAuthState(providerId: String): ProviderAuthState {
        val base = delegate.refreshAuthState(providerId)
        return if (providerId == QQMUSIC_PROVIDER_ID) enrichQQMusicAuth(base) else base
    }

    override suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState {
        val base = delegate.loginWithCookies(providerId, cookiesJson)
        return if (providerId == QQMUSIC_PROVIDER_ID) enrichQQMusicAuth(base) else base
    }

    override suspend fun loginWithHeaders(
        providerId: String,
        authorization: String,
        cookie: String,
    ): ProviderAuthState {
        val base = delegate.loginWithHeaders(providerId, authorization, cookie)
        return if (providerId == QQMUSIC_PROVIDER_ID) enrichQQMusicAuth(base) else base
    }

    override suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection =
        loadFeaturePage(feature, 0, PROVIDER_PAGE_SIZE)

    override suspend fun loadFeaturePage(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection = if (feature.providerId == QQMUSIC_PROVIDER_ID) {
        val featureId = ProviderFeatureFilterCodec.requestId(feature.id)
        val section = if (featureId in QQMUSIC_USER_LIBRARY_FEATURE_IDS) {
            val auth = delegate.authState(QQMUSIC_PROVIDER_ID)
            if (!auth.isLoggedIn) {
                ProviderContentSection(feature = feature, isLoginRequired = true)
            } else {
                when (featureId) {
                    QQMUSIC_USER_PLAYLISTS_FEATURE_ID -> userLibrary.loadPlaylists(feature, offset, limit)
                    QQMUSIC_FAVORITE_PLAYLISTS_FEATURE_ID -> userLibrary.loadFavoritePlaylists(feature, offset, limit)
                    QQMUSIC_FOLLOWED_ARTISTS_FEATURE_ID -> userLibrary.loadFollowedArtists(feature, offset, limit)
                    QQMUSIC_FAVORITE_ALBUMS_FEATURE_ID -> userLibrary.loadFavoriteAlbums(feature, offset, limit)
                    else -> ProviderContentSection(feature = feature)
                }
            }
        } else {
            qqmusic.loadFeature(feature, offset, limit)
        }
        normalizeQQMusicArtists(section)
    } else {
        delegate.loadFeaturePage(feature, offset, limit)
    }

    override suspend fun loadMoreFeatureTracks(feature: ProviderFeature): List<MusicTrack> =
        if (feature.providerId == QQMUSIC_PROVIDER_ID) {
            qqmusic.loadFeature(feature, 0, PROVIDER_PAGE_SIZE).tracks
        } else {
            delegate.loadMoreFeatureTracks(feature)
        }

    override suspend fun playlistDetail(playlist: ProviderPlaylist): ProviderPlaylistDetail =
        playlistDetailPage(playlist, 0, PROVIDER_PAGE_SIZE)

    override suspend fun playlistDetailPage(
        playlist: ProviderPlaylist,
        offset: Int,
        limit: Int,
    ): ProviderPlaylistDetail = if (playlist.providerId == QQMUSIC_PROVIDER_ID) {
        qqmusic.playlistDetail(playlist, offset, limit)
    } else {
        delegate.playlistDetailPage(playlist, offset, limit)
    }

    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> =
        if (playlist.providerId == QQMUSIC_PROVIDER_ID) {
            qqmusic.playlistTracks(playlist)
        } else {
            delegate.playlistTracks(playlist)
        }

    override suspend fun mediaItemDetail(item: ProviderMediaItem): ProviderMediaItemDetail =
        mediaItemDetailPage(item, 0, 0, PROVIDER_PAGE_SIZE)

    override suspend fun mediaItemDetailPage(
        item: ProviderMediaItem,
        tracksOffset: Int,
        albumsOffset: Int,
        limit: Int,
    ): ProviderMediaItemDetail {
        if (item.providerId != QQMUSIC_PROVIDER_ID || item.type != ProviderMediaItemType.Artist) {
            return delegate.mediaItemDetailPage(item, tracksOffset, albumsOffset, limit)
        }

        val normalized = artistDetails.normalizeArtist(item)
        val base = runCatching {
            delegate.mediaItemDetailPage(normalized, tracksOffset, albumsOffset, limit)
        }.getOrNull()
        val trackPage = artistDetails.loadTracksPage(normalized, tracksOffset, limit)
        val baseItem = base?.item
        val mergedItem = (baseItem ?: trackPage.item).copy(
            id = trackPage.item.id,
            coverUrl = trackPage.item.coverUrl ?: baseItem?.coverUrl,
            providerUrl = trackPage.item.providerUrl,
            trackCount = trackPage.total ?: baseItem?.trackCount ?: trackPage.item.trackCount,
        )
        return ProviderMediaItemDetail(
            item = mergedItem,
            tracks = trackPage.tracks,
            albums = base?.albums.orEmpty(),
            tracksNextOffset = trackPage.nextOffset,
            tracksHasMore = trackPage.hasMore,
            albumsNextOffset = base?.albumsNextOffset ?: albumsOffset,
            albumsHasMore = base?.albumsHasMore ?: false,
        )
    }

    override suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack> =
        if (item.providerId == QQMUSIC_PROVIDER_ID && item.type == ProviderMediaItemType.Artist) {
            artistDetails.loadTracksPage(
                item = artistDetails.normalizeArtist(item),
                offset = 0,
                limit = MAX_ARTIST_TRACKS,
            ).tracks
        } else {
            delegate.mediaItemTracks(item)
        }

    private suspend fun enrichQQMusicAuth(base: ProviderAuthState): ProviderAuthState {
        if (!base.isLoggedIn) return base
        val userName = runCatching { userLibrary.userName() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return base
        return base.copy(userName = userName)
    }

    private fun normalizeQQMusicArtists(section: ProviderContentSection): ProviderContentSection {
        if (section.mediaItems.isEmpty()) return section
        return section.copy(
            mediaItems = section.mediaItems.map { item ->
                if (item.providerId == QQMUSIC_PROVIDER_ID && item.type == ProviderMediaItemType.Artist) {
                    artistDetails.normalizeArtist(item)
                } else {
                    item
                }
            },
        )
    }

    private fun <T> replaceQQMusic(
        base: List<T>,
        qqItems: List<T>,
        providerId: (T) -> String,
    ): List<T> = (base.filterNot { providerId(it) == QQMUSIC_PROVIDER_ID } + qqItems).distinct()

    private companion object {
        const val QQMUSIC_PROVIDER_ID = "qqmusic"
        const val QQMUSIC_USER_PLAYLISTS_FEATURE_ID = "qqmusic_user_playlists"
        const val QQMUSIC_FAVORITE_PLAYLISTS_FEATURE_ID = "qqmusic_favorite_playlists"
        const val QQMUSIC_FOLLOWED_ARTISTS_FEATURE_ID = "qqmusic_followed_artists"
        const val QQMUSIC_FAVORITE_ALBUMS_FEATURE_ID = "qqmusic_favorite_albums"
        const val MAX_ARTIST_TRACKS = 300

        val QQMUSIC_USER_LIBRARY_FEATURE_IDS = setOf(
            QQMUSIC_USER_PLAYLISTS_FEATURE_ID,
            QQMUSIC_FAVORITE_PLAYLISTS_FEATURE_ID,
            QQMUSIC_FOLLOWED_ARTISTS_FEATURE_ID,
            QQMUSIC_FAVORITE_ALBUMS_FEATURE_ID,
        )

        val QQMUSIC_MINE_FEATURES = listOf(
            ProviderFeature(
                QQMUSIC_FAVORITE_PLAYLISTS_FEATURE_ID,
                QQMUSIC_PROVIDER_ID,
                "QQ 音乐",
                "收藏歌单",
                ProviderFeatureCategory.MineFavoritePlaylists,
                ProviderContentType.Playlists,
                true,
            ),
            ProviderFeature(
                QQMUSIC_FOLLOWED_ARTISTS_FEATURE_ID,
                QQMUSIC_PROVIDER_ID,
                "QQ 音乐",
                "关注歌手",
                ProviderFeatureCategory.Mine,
                ProviderContentType.Artists,
                true,
            ),
            ProviderFeature(
                QQMUSIC_FAVORITE_ALBUMS_FEATURE_ID,
                QQMUSIC_PROVIDER_ID,
                "QQ 音乐",
                "收藏专辑",
                ProviderFeatureCategory.Mine,
                ProviderContentType.Albums,
                true,
            ),
        )
    }
}
