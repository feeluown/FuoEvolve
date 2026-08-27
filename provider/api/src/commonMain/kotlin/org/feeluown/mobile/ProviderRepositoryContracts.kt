package org.feeluown.mobile

/** Provider-native best match preserved across the provider-neutral search boundary. */
sealed interface ProviderSearchHit {
    val providerId: String
    val providerName: String

    data class Track(val value: MusicTrack) : ProviderSearchHit {
        override val providerId: String get() = value.source
        override val providerName: String get() = value.providerName.orEmpty()
    }

    data class Artist(val value: MediaRef) : ProviderSearchHit {
        override val providerId: String get() = value.providerId
        override val providerName: String get() = value.providerName
    }

    data class Album(val value: MediaRef) : ProviderSearchHit {
        override val providerId: String get() = value.providerId
        override val providerName: String get() = value.providerName
    }

    data class Playlist(val value: ProviderPlaylist) : ProviderSearchHit {
        override val providerId: String get() = value.providerId
        override val providerName: String get() = value.providerName
    }

    data class Video(val value: ProviderVideo) : ProviderSearchHit {
        override val providerId: String get() = value.providerId
        override val providerName: String get() = value.providerName
    }
}

/** Provider-neutral aggregate search result shared by catalog consumers. */
data class ProviderSearchResults(
    val tracks: List<MusicTrack> = emptyList(),
    val playlists: List<ProviderPlaylist> = emptyList(),
    val artists: List<MediaRef> = emptyList(),
    val albums: List<MediaRef> = emptyList(),
    val videos: List<ProviderVideo> = emptyList(),
    val bestMatches: List<ProviderSearchHit> = emptyList(),
    val failure: ProviderFailure? = null,
)

data class ProviderContentSection(
    val feature: ProviderFeature,
    val tracks: List<MusicTrack> = emptyList(),
    val playlists: List<ProviderPlaylist> = emptyList(),
    val mediaItems: List<MediaRef> = emptyList(),
    val videos: List<ProviderVideo> = emptyList(),
    val isLoginRequired: Boolean = false,
    val failure: ProviderFailure? = null,
    val nextOffset: Int = 0,
    val hasMore: Boolean = false,
)

data class ProviderPlaylistDetail(
    val playlist: ProviderPlaylist,
    val tracks: List<MusicTrack> = emptyList(),
    val tracksNextOffset: Int = 0,
    val tracksHasMore: Boolean = false,
)

data class ProviderMediaItemDetail(
    val item: MediaRef,
    val tracks: List<MusicTrack> = emptyList(),
    val albums: List<MediaRef> = emptyList(),
    val tracksNextOffset: Int = 0,
    val tracksHasMore: Boolean = false,
    val albumsNextOffset: Int = 0,
    val albumsHasMore: Boolean = false,
)

/** Registry/lifecycle capability independent from catalog and playback behavior. */
interface ProviderRegistryRepository {
    suspend fun initialize()
    suspend fun availableProviders(): List<ProviderInfo> = providers()
    suspend fun updateEnabledProviders(providerIds: Set<String>) = Unit
    suspend fun providers(): List<ProviderInfo>
    suspend fun providerCapabilities(): List<ProviderCapabilities> = emptyList()
}

/** Read-only provider surface needed by search consumers. */
interface ProviderSearchRepository {
    suspend fun search(keyword: String, providerId: String? = null): List<MusicTrack>
    suspend fun searchAll(keyword: String, providerId: String? = null): ProviderSearchResults =
        ProviderSearchResults(tracks = search(keyword, providerId))
}

/** Read-only provider catalog/detail surface. */
interface ProviderCatalogRepository {
    suspend fun trackDetail(trackId: String): MusicTrack {
        throw UnsupportedOperationException("provider does not support track detail: $trackId")
    }

    suspend fun features(): List<ProviderFeature>
    suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection
    suspend fun loadFeaturePage(
        feature: ProviderFeature,
        offset: Int,
        limit: Int = PROVIDER_PAGE_SIZE,
    ): ProviderContentSection = loadFeature(feature)

    suspend fun loadMoreFeatureTracks(feature: ProviderFeature): List<MusicTrack> = loadFeature(feature).tracks

    suspend fun playlistDetail(playlist: ProviderPlaylist): ProviderPlaylistDetail =
        ProviderPlaylistDetail(playlist, playlistTracks(playlist))

    suspend fun playlistDetailPage(
        playlist: ProviderPlaylist,
        offset: Int,
        limit: Int = PROVIDER_PAGE_SIZE,
    ): ProviderPlaylistDetail = playlistDetail(playlist)

    suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack>

    suspend fun mediaItemDetail(item: MediaRef): ProviderMediaItemDetail =
        ProviderMediaItemDetail(item, mediaItemTracks(item))

    suspend fun mediaItemDetailPage(
        item: MediaRef,
        tracksOffset: Int,
        albumsOffset: Int,
        limit: Int = PROVIDER_PAGE_SIZE,
    ): ProviderMediaItemDetail = mediaItemDetail(item)

    suspend fun mediaItemTracks(item: MediaRef): List<MusicTrack>
    suspend fun similarTracks(track: MusicTrack): List<MusicTrack> = emptyList()
    suspend fun hotComments(track: MusicTrack): List<ProviderComment> = emptyList()
    suspend fun trackVideo(track: MusicTrack): ProviderVideo? = null
    suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload {
        throw UnsupportedOperationException("provider does not support video playback: ${video.id}")
    }
}

/** Provider library/resource mutation surface. */
interface ProviderLibraryRepository {
    suspend fun playlistOperationTargets(track: MusicTrack): List<ProviderPlaylist> = emptyList()
    suspend fun addTrackToPlaylist(playlist: ProviderPlaylist, track: MusicTrack): ProviderMutationResult =
        ProviderMutationResult(false, "")
    suspend fun removeTrackFromPlaylist(playlist: ProviderPlaylist, track: MusicTrack): ProviderMutationResult =
        ProviderMutationResult(false, "")
    suspend fun createPlaylist(providerId: String, name: String): ProviderMutationResult =
        ProviderMutationResult(false, "")
    suspend fun deletePlaylist(playlist: ProviderPlaylist): ProviderMutationResult =
        ProviderMutationResult(false, "")
    suspend fun setSongDisliked(track: MusicTrack, disliked: Boolean): ProviderMutationResult =
        ProviderMutationResult(false, "")
    suspend fun resourceState(resourceType: String, resourceId: String): ProviderResourceState =
        ProviderResourceState(providerId = "", resourceId = resourceId)
    suspend fun setResourceFavorite(resourceType: String, resourceId: String, favorite: Boolean): ProviderMutationResult =
        ProviderMutationResult(false, "")
}

/** Provider authentication surface independent of any concrete provider implementation. */
interface ProviderAuthRepository {
    suspend fun authState(providerId: String): ProviderAuthState
    suspend fun refreshAuthState(providerId: String): ProviderAuthState = authState(providerId)
    suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState
    suspend fun loginWithHeaders(providerId: String, authorization: String, cookie: String): ProviderAuthState {
        throw UnsupportedOperationException("provider does not support header login: $providerId")
    }
    suspend fun loginWithHeaderFile(providerId: String, headerFileJson: String): ProviderAuthState {
        throw UnsupportedOperationException("provider does not support header file login: $providerId")
    }
    suspend fun beginDeviceAuthorization(
        providerId: String,
        clientId: String,
        clientSecret: String,
    ): ProviderDeviceAuthorization {
        throw UnsupportedOperationException("provider does not support device authorization: $providerId")
    }
    suspend fun pollDeviceAuthorization(
        providerId: String,
        deviceCode: String,
        clientId: String,
        clientSecret: String,
    ): ProviderDeviceAuthorizationPollResult {
        throw UnsupportedOperationException("provider does not support device authorization: $providerId")
    }
    suspend fun loginWithOAuth(
        providerId: String,
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long?,
        scope: String?,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState {
        throw UnsupportedOperationException("provider does not support OAuth login: $providerId")
    }
    suspend fun loginWithOAuthJson(
        providerId: String,
        oauthJson: String,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState {
        throw UnsupportedOperationException("provider does not support OAuth JSON login: $providerId")
    }
    suspend fun logout(providerId: String): ProviderAuthState
}

data class ProviderDeviceAuthorization(
    val providerId: String,
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
) {
    val verificationUrlWithCode: String
        get() = if (verificationUrl.contains("user_code=")) {
            verificationUrl
        } else {
            val separator = if (verificationUrl.contains('?')) '&' else '?'
            "$verificationUrl${separator}user_code=$userCode"
        }
}

data class ProviderOAuthToken(
    val accessToken: String,
    val refreshToken: String,
    val scope: String? = null,
    val expiresAtMillis: Long? = null,
)

sealed interface ProviderDeviceAuthorizationPollResult {
    data class Authorized(val token: ProviderOAuthToken) : ProviderDeviceAuthorizationPollResult
    data object Pending : ProviderDeviceAuthorizationPollResult
    data object SlowDown : ProviderDeviceAuthorizationPollResult
    data class Denied(val message: String) : ProviderDeviceAuthorizationPollResult
}