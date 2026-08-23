package org.feeluown.mobile

data class ProviderSearchResults(
    val tracks: List<MusicTrack> = emptyList(),
    val playlists: List<ProviderPlaylist> = emptyList(),
    val artists: List<ProviderMediaItem> = emptyList(),
    val albums: List<ProviderMediaItem> = emptyList(),
    val videos: List<ProviderVideo> = emptyList(),
    val errorMessage: String? = null,
)

data class ProviderContentSection(
    val feature: ProviderFeature,
    val tracks: List<MusicTrack> = emptyList(),
    val playlists: List<ProviderPlaylist> = emptyList(),
    val mediaItems: List<ProviderMediaItem> = emptyList(),
    val videos: List<ProviderVideo> = emptyList(),
    val isLoginRequired: Boolean = false,
    val errorMessage: String? = null,
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
    val item: ProviderMediaItem,
    val tracks: List<MusicTrack> = emptyList(),
    val albums: List<ProviderMediaItem> = emptyList(),
    val tracksNextOffset: Int = 0,
    val tracksHasMore: Boolean = false,
    val albumsNextOffset: Int = 0,
    val albumsHasMore: Boolean = false,
)

interface ProviderMusicRepository {
    suspend fun initialize()
    suspend fun availableProviders(): List<ProviderInfo> = providers()
    suspend fun updateEnabledProviders(providerIds: Set<String>) = Unit
    suspend fun providers(): List<ProviderInfo>
    suspend fun search(keyword: String, providerId: String? = null): List<MusicTrack>
    suspend fun searchAll(keyword: String, providerId: String? = null): ProviderSearchResults =
        ProviderSearchResults(tracks = search(keyword, providerId))
    suspend fun trackDetail(trackId: String): MusicTrack {
        throw UnsupportedOperationException("provider does not support track detail: $trackId")
    }
    suspend fun replacementCandidates(
        track: MusicTrack,
        smartReplacementProviderIds: Set<String> = emptySet(),
        smartReplacementMinScore: Double = DEFAULT_SMART_REPLACEMENT_MIN_SCORE,
    ): List<ReplacementCandidate> = emptyList()
    suspend fun resolve(
        track: MusicTrack,
        unavailablePolicy: UnavailablePlaybackPolicy = DEFAULT_UNAVAILABLE_PLAYBACK_POLICY,
        smartReplacementProviderIds: Set<String> = emptySet(),
        smartReplacementMinScore: Double = DEFAULT_SMART_REPLACEMENT_MIN_SCORE,
        smartReplacementUseOriginalMetadata: Boolean = true,
        smartReplacementUseOriginalLyrics: Boolean = true,
    ): PlaybackPayload
    suspend fun resolveSelectedReplacement(
        track: MusicTrack,
        smartReplacementUseOriginalMetadata: Boolean = true,
        smartReplacementUseOriginalLyrics: Boolean = true,
        smartReplacementProviderIds: Set<String> = emptySet(),
    ): PlaybackPayload = resolve(
        track = track,
        unavailablePolicy = UnavailablePlaybackPolicy.Skip,
        smartReplacementProviderIds = smartReplacementProviderIds,
        smartReplacementUseOriginalMetadata = smartReplacementUseOriginalMetadata,
        smartReplacementUseOriginalLyrics = smartReplacementUseOriginalLyrics,
    )
    suspend fun lyrics(track: MusicTrack): String? = null
    suspend fun authState(providerId: String): ProviderAuthState
    suspend fun refreshAuthState(providerId: String): ProviderAuthState = authState(providerId)
    suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState
    suspend fun loginWithHeaders(providerId: String, authorization: String, cookie: String): ProviderAuthState {
        throw UnsupportedOperationException("provider does not support header login: $providerId")
    }
    suspend fun loginWithYtmusicHeaderFile(headerFileJson: String): ProviderAuthState {
        throw UnsupportedOperationException("provider does not support YTMusic header file login")
    }
    suspend fun beginYtmusicOAuth(clientId: String, clientSecret: String): org.feeluown.mobile.provider.ytmusic.YtMusicDeviceAuthCode {
        throw UnsupportedOperationException("provider does not support YTMusic OAuth")
    }
    suspend fun pollYtmusicOAuth(
        deviceCode: String,
        clientId: String,
        clientSecret: String,
    ): org.feeluown.mobile.provider.ytmusic.YtMusicOAuthPollResult {
        throw UnsupportedOperationException("provider does not support YTMusic OAuth")
    }
    suspend fun loginWithYtmusicOAuth(
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long?,
        scope: String?,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState {
        throw UnsupportedOperationException("provider does not support YTMusic OAuth")
    }
    suspend fun loginWithYtmusicOAuthJson(
        oauthJson: String,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState {
        throw UnsupportedOperationException("provider does not support YTMusic OAuth")
    }
    suspend fun logout(providerId: String): ProviderAuthState
    suspend fun updateAudioQualityPolicies(wifiPolicy: AudioQualityPolicy, cellularPolicy: AudioQualityPolicy)
    suspend fun providerCapabilities(): List<ProviderCapabilities> = emptyList()
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
    suspend fun playlistOperationTargets(track: MusicTrack): List<ProviderPlaylist> = emptyList()
    suspend fun addTrackToPlaylist(playlist: ProviderPlaylist, track: MusicTrack): ProviderMutationResult =
        ProviderMutationResult(false, "provider does not support playlist add song")
    suspend fun removeTrackFromPlaylist(playlist: ProviderPlaylist, track: MusicTrack): ProviderMutationResult =
        ProviderMutationResult(false, "provider does not support playlist remove song")
    suspend fun createPlaylist(providerId: String, name: String): ProviderMutationResult =
        ProviderMutationResult(false, "provider does not support playlist create")
    suspend fun deletePlaylist(playlist: ProviderPlaylist): ProviderMutationResult =
        ProviderMutationResult(false, "provider does not support playlist delete")
    suspend fun setSongDisliked(track: MusicTrack, disliked: Boolean): ProviderMutationResult =
        ProviderMutationResult(false, "provider does not support dislike operation")
    suspend fun mediaItemDetail(item: ProviderMediaItem): ProviderMediaItemDetail =
        ProviderMediaItemDetail(item, mediaItemTracks(item))
    suspend fun mediaItemDetailPage(
        item: ProviderMediaItem,
        tracksOffset: Int,
        albumsOffset: Int,
        limit: Int = PROVIDER_PAGE_SIZE,
    ): ProviderMediaItemDetail = mediaItemDetail(item)
    suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack>
    suspend fun similarTracks(track: MusicTrack): List<MusicTrack> = emptyList()
    suspend fun hotComments(track: MusicTrack): List<ProviderComment> = emptyList()
    suspend fun trackVideo(track: MusicTrack): ProviderVideo? = null
    suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload {
        throw UnsupportedOperationException("provider does not support video playback: ${video.id}")
    }
    suspend fun resourceState(resourceType: String, resourceId: String): ProviderResourceState =
        ProviderResourceState(providerId = "", resourceId = resourceId)
    suspend fun setResourceFavorite(resourceType: String, resourceId: String, favorite: Boolean): ProviderMutationResult =
        ProviderMutationResult(false, "provider does not support favorite operation")
}
