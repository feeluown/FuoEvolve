package org.feeluown.mobile.provider.qqmusic

import org.feeluown.mobile.MediaRef
import org.feeluown.mobile.MediaRefType
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.ProviderAuthState
import org.feeluown.mobile.ProviderContentSection
import org.feeluown.mobile.ProviderContentType
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderFeatureCategory
import org.feeluown.mobile.ProviderFeatureFilterCodec
import org.feeluown.mobile.ProviderMediaItemDetail
import org.feeluown.mobile.ProviderPlaylist
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies

/**
 * QQ Music module-local composite. Provider-specific discovery, account library and artist
 * enrichment stay inside the concrete provider module instead of leaking helper types to :shared.
 */
internal class QQMusicCompositeProvider(
    dependencies: ProviderRuntimeDependencies,
) : KotlinMusicProvider {
    private val content = QQMusicContentProvider(dependencies.http, dependencies.credentials)
    private val userLibrary = QQMusicUserLibrary(dependencies.http, dependencies.credentials)
    private val artistDetails = QQMusicArtistDetailProvider(dependencies.http, dependencies.credentials)

    override val id: String get() = content.id
    override val name: String get() = content.name
    override val info get() = content.info
    override val capabilities get() = content.capabilities.copy(
        canFavoritePlaylist = true,
        canUnfavoritePlaylist = true,
        canFavoriteArtist = true,
        canUnfavoriteArtist = true,
        canFavoriteAlbum = true,
        canUnfavoriteAlbum = true,
    )
    override val features: List<ProviderFeature> = (content.features + MINE_FEATURES).distinctBy { it.id }

    override suspend fun initialize() = content.initialize()

    override suspend fun search(keyword: String): ProviderSearchResults =
        content.search(keyword).let(::normalizeSearchArtists)

    override suspend fun trackDetail(identifier: String) = content.trackDetail(identifier)
    override suspend fun resolve(track: MusicTrack, qualityPolicy: String) = content.resolve(track, qualityPolicy)
    override suspend fun lyrics(track: MusicTrack) = content.lyrics(track)

    override suspend fun authState(): ProviderAuthState = enrichAuth(content.authState())

    override suspend fun loginWithCookies(cookiesJson: String): ProviderAuthState =
        enrichAuth(content.loginWithCookies(cookiesJson))

    override suspend fun loginWithHeaders(authorization: String, cookie: String): ProviderAuthState =
        enrichAuth(content.loginWithHeaders(authorization, cookie))

    override suspend fun loginWithHeaderFile(headerFileJson: String): ProviderAuthState =
        content.loginWithHeaderFile(headerFileJson)

    override suspend fun loginWithOAuth(
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long?,
        scope: String?,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState = content.loginWithOAuth(
        accessToken,
        refreshToken,
        expiresAtMillis,
        scope,
        clientId,
        clientSecret,
    )

    override suspend fun logout(): ProviderAuthState = content.logout()

    override suspend fun loadFeature(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val featureId = ProviderFeatureFilterCodec.requestId(feature.id)
        val section = if (featureId in USER_LIBRARY_FEATURE_IDS) {
            if (!content.authState().isLoggedIn) {
                ProviderContentSection(feature = feature, isLoginRequired = true)
            } else {
                when (featureId) {
                    USER_PLAYLISTS_FEATURE_ID -> userLibrary.loadPlaylists(feature, offset, limit)
                    FAVORITE_PLAYLISTS_FEATURE_ID -> userLibrary.loadFavoritePlaylists(feature, offset, limit)
                    FOLLOWED_ARTISTS_FEATURE_ID -> userLibrary.loadFollowedArtists(feature, offset, limit)
                    FAVORITE_ALBUMS_FEATURE_ID -> userLibrary.loadFavoriteAlbums(feature, offset, limit)
                    else -> ProviderContentSection(feature = feature)
                }
            }
        } else {
            content.loadFeature(feature, offset, limit)
        }
        return normalizeArtists(normalizePlaylists(section, featureId))
    }

    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> =
        content.playlistTracks(playlist)

    override suspend fun playlistDetail(playlist: ProviderPlaylist, offset: Int, limit: Int) =
        content.playlistDetail(playlist, offset, limit)

    override suspend fun playlistOperationTargets(track: MusicTrack) = content.playlistOperationTargets(track)
    override suspend fun addTrackToPlaylist(playlist: ProviderPlaylist, track: MusicTrack) =
        content.addTrackToPlaylist(playlist, track)
    override suspend fun removeTrackFromPlaylist(playlist: ProviderPlaylist, track: MusicTrack) =
        content.removeTrackFromPlaylist(playlist, track)
    override suspend fun createPlaylist(name: String) = content.createPlaylist(name)
    override suspend fun deletePlaylist(playlist: ProviderPlaylist) = content.deletePlaylist(playlist)
    override suspend fun setSongDisliked(track: MusicTrack, disliked: Boolean) = content.setSongDisliked(track, disliked)

    override suspend fun mediaItemTracks(item: MediaRef): List<MusicTrack> =
        if (item.providerId == ID && item.type == MediaRefType.Artist) {
            artistDetails.loadTracksPage(
                item = artistDetails.normalizeArtist(item),
                offset = 0,
                limit = MAX_ARTIST_TRACKS,
            ).tracks
        } else {
            content.mediaItemTracks(item)
        }

    override suspend fun mediaItemDetail(
        item: MediaRef,
        tracksOffset: Int,
        albumsOffset: Int,
        limit: Int,
    ): ProviderMediaItemDetail {
        if (item.providerId != ID || item.type != MediaRefType.Artist) {
            return content.mediaItemDetail(item, tracksOffset, albumsOffset, limit)
        }
        val normalized = artistDetails.normalizeArtist(item)
        val base = runCatching {
            content.mediaItemDetail(normalized, tracksOffset, albumsOffset, limit)
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

    override suspend fun similarTracks(track: MusicTrack) = content.similarTracks(track)
    override suspend fun hotComments(track: MusicTrack) = content.hotComments(track)
    override suspend fun trackVideo(track: MusicTrack) = content.trackVideo(track)
    override suspend fun videoPlaybackPayload(video: org.feeluown.mobile.ProviderVideo) = content.videoPlaybackPayload(video)
    override suspend fun resourceState(resourceType: String, resourceId: String) =
        userLibrary.resourceState(resourceType, resourceId)
    override suspend fun setResourceFavorite(resourceType: String, resourceId: String, favorite: Boolean) =
        userLibrary.setResourceFavorite(resourceType, resourceId, favorite)

    private suspend fun enrichAuth(base: ProviderAuthState): ProviderAuthState {
        if (!base.isLoggedIn) return base
        val userName = runCatching { userLibrary.userName() }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: return base
        return base.copy(userName = userName)
    }

    private fun normalizeSearchArtists(results: ProviderSearchResults): ProviderSearchResults =
        results.copy(
            artists = results.artists.map { item ->
                if (item.providerId == ID && item.type == MediaRefType.Artist) artistDetails.normalizeArtist(item) else item
            },
        )

    private fun normalizeArtists(section: ProviderContentSection): ProviderContentSection =
        section.copy(
            mediaItems = section.mediaItems.map { item ->
                if (item.providerId == ID && item.type == MediaRefType.Artist) artistDetails.normalizeArtist(item) else item
            },
        )

    private fun normalizePlaylists(section: ProviderContentSection, featureId: String): ProviderContentSection {
        val playlists = when (featureId) {
            USER_PLAYLISTS_FEATURE_ID -> section.playlists.map { playlist ->
                val isFavoriteSongs = playlist.title == FAVORITE_SONGS_TITLE
                playlist.copy(
                    isOwnedByCurrentUser = true,
                    isSubscribed = false,
                    canAddTracks = true,
                    canRemoveTracks = true,
                    canDelete = !isFavoriteSongs,
                )
            }
            FAVORITE_PLAYLISTS_FEATURE_ID -> section.playlists.map { playlist ->
                playlist.copy(
                    isOwnedByCurrentUser = false,
                    isSubscribed = true,
                    canAddTracks = false,
                    canRemoveTracks = false,
                    canDelete = false,
                )
            }
            else -> section.playlists
        }
        return section.copy(playlists = playlists)
    }

    private companion object {
        const val ID = "qqmusic"
        const val USER_PLAYLISTS_FEATURE_ID = "qqmusic_user_playlists"
        const val FAVORITE_PLAYLISTS_FEATURE_ID = "qqmusic_favorite_playlists"
        const val FOLLOWED_ARTISTS_FEATURE_ID = "qqmusic_followed_artists"
        const val FAVORITE_ALBUMS_FEATURE_ID = "qqmusic_favorite_albums"
        const val FAVORITE_SONGS_TITLE = "我喜欢"
        const val MAX_ARTIST_TRACKS = 300

        val USER_LIBRARY_FEATURE_IDS = setOf(
            USER_PLAYLISTS_FEATURE_ID,
            FAVORITE_PLAYLISTS_FEATURE_ID,
            FOLLOWED_ARTISTS_FEATURE_ID,
            FAVORITE_ALBUMS_FEATURE_ID,
        )

        val MINE_FEATURES = listOf(
            ProviderFeature(
                FAVORITE_PLAYLISTS_FEATURE_ID,
                ID,
                "QQ 音乐",
                "收藏歌单",
                ProviderFeatureCategory.MineFavoritePlaylists,
                ProviderContentType.Playlists,
                true,
            ),
            ProviderFeature(
                FOLLOWED_ARTISTS_FEATURE_ID,
                ID,
                "QQ 音乐",
                "关注歌手",
                ProviderFeatureCategory.Mine,
                ProviderContentType.Artists,
                true,
            ),
            ProviderFeature(
                FAVORITE_ALBUMS_FEATURE_ID,
                ID,
                "QQ 音乐",
                "收藏专辑",
                ProviderFeatureCategory.Mine,
                ProviderContentType.Albums,
                true,
            ),
        )
    }
}
