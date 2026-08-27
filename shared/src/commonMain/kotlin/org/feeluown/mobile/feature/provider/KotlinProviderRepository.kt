package org.feeluown.mobile

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderDeviceAuthorizationCapability
import org.feeluown.mobile.provider.core.splitResourceId
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderPersistentCache

/**
 * Concrete application-side implementation of the provider capability interfaces. It intentionally
 * does not expose an aggregate repository contract; composition roots pass the narrow capability a
 * consumer needs. Smart-replacement policy is owned by :feature:playback.
 */
class KotlinProviderRepository :
    ProviderRegistryRepository,
    ProviderSearchRepository,
    ProviderAuthRepository,
    ProviderCatalogRepository,
    ProviderLibraryRepository,
    PlaybackProviderSourcePort,
    ProviderAudioQualityPort {
    private val stateMutex = Mutex()
    private val http: ProviderHttpClient
    private val credentials: ProviderCredentialStore
    private val isCellularConnection: () -> Boolean
    private val providerMap: Map<String, KotlinMusicProvider>
    private var enabledProviderIds: Set<String> = DEFAULT_ENABLED_PROVIDER_IDS
    private var initialized = false
    private var wifiAudioQualityPolicy = DEFAULT_WIFI_AUDIO_QUALITY_POLICY
    private var cellularAudioQualityPolicy = DEFAULT_CELLULAR_AUDIO_QUALITY_POLICY

    constructor() {
        http = ProviderHttpClient()
        credentials = InMemoryProviderCredentialStore()
        isCellularConnection = { false }
        providerMap = ProviderComposition.createProviders(http, credentials)
    }

    internal constructor(
        http: ProviderHttpClient,
        credentials: ProviderCredentialStore,
        isCellularConnection: () -> Boolean = { false },
    ) {
        this.http = http
        this.credentials = credentials
        this.isCellularConnection = isCellularConnection
        providerMap = ProviderComposition.createProviders(http, credentials)
    }

    override suspend fun initialize() {
        stateMutex.withLock {
            if (initialized) return
            credentials.migrateLegacyIfNeeded()
            enabledProviderIds.forEach { providerMap[it]?.initialize() }
            initialized = true
        }
    }

    override suspend fun availableProviders(): List<ProviderInfo> = providerMap.values.map { it.info }

    override suspend fun updateEnabledProviders(providerIds: Set<String>) {
        val next = providerIds
            .intersect(providerMap.keys)
            .ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS.intersect(providerMap.keys) }
        stateMutex.withLock {
            enabledProviderIds = next
            initialized = false
        }
        initialize()
    }

    override suspend fun providers(): List<ProviderInfo> {
        initialize()
        return enabledProviderIds.mapNotNull { providerMap[it]?.info }
    }

    override suspend fun providerCapabilities(): List<ProviderCapabilities> {
        initialize()
        return enabledProviderIds.mapNotNull { providerMap[it]?.capabilities }
    }

    override suspend fun search(keyword: String, providerId: String?): List<MusicTrack> =
        searchAll(keyword, providerId).tracks

    override suspend fun searchAll(keyword: String, providerId: String?): ProviderSearchResults {
        initialize()
        val results = selectedProviders(providerId).map { it.search(keyword) }
        return ProviderSearchResults(
            tracks = results.flatMap { it.tracks }.distinctBy { it.id },
            playlists = results.flatMap { it.playlists }.distinctBy { it.id },
            artists = results.flatMap { it.artists }.distinctBy { it.id },
            albums = results.flatMap { it.albums }.distinctBy { it.id },
            videos = results.flatMap { it.videos }.distinctBy { it.id },
            bestMatches = results.flatMap { it.bestMatches }.distinctBy(::searchHitKey),
            errorMessage = results.mapNotNull { it.errorMessage }.firstOrNull(),
        )
    }

    override suspend fun trackDetail(trackId: String): MusicTrack {
        initialize()
        val (providerId, identifier) = splitResourceId(trackId)
        return requireProvider(providerId).trackDetail(identifier)?.copy(
            id = trackId,
            providerId = trackId,
        ) ?: error("track not found: $trackId")
    }

    override suspend fun resolveTrack(track: MusicTrack): PlaybackPayload? {
        initialize()
        val quality = if (isCellularConnection()) cellularAudioQualityPolicy.policy else wifiAudioQualityPolicy.policy
        val providerId = track.source.ifBlank {
            splitResourceId(track.providerId ?: track.id).first
        }
        return providerMap[providerId]?.resolve(track, quality)
    }

    override suspend fun lyrics(track: MusicTrack): String? {
        initialize()
        track.lyrics?.takeIf { it.isNotBlank() }?.let { return it }
        val providerId = track.source.ifBlank {
            splitResourceId(track.providerId ?: track.id).first
        }
        return providerMap[providerId]?.lyrics(track)?.takeIf { it.isNotBlank() }
    }

    override suspend fun lyricsSearchKeyword(track: MusicTrack): String? {
        initialize()
        val providerId = track.source.ifBlank {
            splitResourceId(track.providerId ?: track.id).first
        }
        return providerMap[providerId]?.lyricsSearchKeyword(track)?.takeIf { it.isNotBlank() }
    }

    override suspend fun authState(providerId: String): ProviderAuthState = requireProvider(providerId).authState()

    override suspend fun refreshAuthState(providerId: String): ProviderAuthState = requireProvider(providerId).authState()

    override suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState =
        requireProvider(providerId).loginWithCookies(cookiesJson)

    override suspend fun loginWithHeaders(
        providerId: String,
        authorization: String,
        cookie: String,
    ): ProviderAuthState = requireProvider(providerId).loginWithHeaders(authorization, cookie)

    override suspend fun loginWithHeaderFile(providerId: String, headerFileJson: String): ProviderAuthState =
        requireProvider(providerId).loginWithHeaderFile(headerFileJson)

    override suspend fun beginDeviceAuthorization(
        providerId: String,
        clientId: String,
        clientSecret: String,
    ): ProviderDeviceAuthorization = requireDeviceAuthorizationProvider(providerId)
        .beginDeviceAuthorization(clientId, clientSecret)

    override suspend fun pollDeviceAuthorization(
        providerId: String,
        deviceCode: String,
        clientId: String,
        clientSecret: String,
    ): ProviderDeviceAuthorizationPollResult = requireDeviceAuthorizationProvider(providerId)
        .pollDeviceAuthorization(deviceCode, clientId, clientSecret)

    override suspend fun loginWithOAuth(
        providerId: String,
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long?,
        scope: String?,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState = requireProvider(providerId).loginWithOAuth(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtMillis = expiresAtMillis,
        scope = scope,
        clientId = clientId,
        clientSecret = clientSecret,
    )

    override suspend fun loginWithOAuthJson(
        providerId: String,
        oauthJson: String,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState = requireDeviceAuthorizationProvider(providerId)
        .loginWithOAuthJson(oauthJson, clientId, clientSecret)

    override suspend fun logout(providerId: String): ProviderAuthState = requireProvider(providerId).logout()

    override suspend fun updateAudioQualityPolicies(wifiPolicy: AudioQualityPolicy, cellularPolicy: AudioQualityPolicy) {
        wifiAudioQualityPolicy = wifiPolicy
        cellularAudioQualityPolicy = cellularPolicy
    }

    override suspend fun features(): List<ProviderFeature> {
        initialize()
        return enabledProviderIds
            .flatMap { providerMap[it]?.features.orEmpty() }
            .filterNot { feature ->
                feature.providerId == NETEASE_PROVIDER_ID && feature.id == NETEASE_LEGACY_TOP_ARTISTS_FEATURE_ID
            }
    }

    override suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection =
        loadFeaturePage(feature, 0, PROVIDER_PAGE_SIZE)

    override suspend fun loadFeaturePage(feature: ProviderFeature, offset: Int, limit: Int): ProviderContentSection =
        requireProvider(feature.providerId).loadFeature(feature, offset, limit)

    override suspend fun loadMoreFeatureTracks(feature: ProviderFeature): List<MusicTrack> =
        loadFeature(feature).tracks

    override suspend fun playlistDetail(playlist: ProviderPlaylist): ProviderPlaylistDetail =
        playlistDetailPage(playlist, 0, PROVIDER_PAGE_SIZE)

    override suspend fun playlistDetailPage(
        playlist: ProviderPlaylist,
        offset: Int,
        limit: Int,
    ): ProviderPlaylistDetail = requireProvider(playlist.providerId).playlistDetail(playlist, offset, limit)

    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> =
        requireProvider(playlist.providerId).playlistTracks(playlist)

    override suspend fun mediaItemDetail(item: ProviderMediaItem): ProviderMediaItemDetail =
        mediaItemDetailPage(item, 0, 0, PROVIDER_PAGE_SIZE)

    override suspend fun mediaItemDetailPage(
        item: ProviderMediaItem,
        tracksOffset: Int,
        albumsOffset: Int,
        limit: Int,
    ): ProviderMediaItemDetail = requireProvider(item.providerId)
        .mediaItemDetail(item, tracksOffset, albumsOffset, limit)

    override suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack> =
        requireProvider(item.providerId).mediaItemTracks(item)

    override suspend fun similarTracks(track: MusicTrack): List<MusicTrack> =
        requireProvider(track.source).similarTracks(track)

    override suspend fun hotComments(track: MusicTrack): List<ProviderComment> =
        requireProvider(track.source).hotComments(track)

    override suspend fun trackVideo(track: MusicTrack): ProviderVideo? =
        requireProvider(track.source).trackVideo(track)

    override suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload =
        requireProvider(video.providerId).videoPlaybackPayload(video)

    override suspend fun playlistOperationTargets(track: MusicTrack): List<ProviderPlaylist> =
        requireProvider(track.source).playlistOperationTargets(track)

    override suspend fun addTrackToPlaylist(playlist: ProviderPlaylist, track: MusicTrack): ProviderMutationResult =
        requireProvider(playlist.providerId).addTrackToPlaylist(playlist, track)

    override suspend fun removeTrackFromPlaylist(playlist: ProviderPlaylist, track: MusicTrack): ProviderMutationResult =
        requireProvider(playlist.providerId).removeTrackFromPlaylist(playlist, track)

    override suspend fun createPlaylist(providerId: String, name: String): ProviderMutationResult =
        requireProvider(providerId).createPlaylist(name)

    override suspend fun deletePlaylist(playlist: ProviderPlaylist): ProviderMutationResult =
        requireProvider(playlist.providerId).deletePlaylist(playlist)

    override suspend fun setSongDisliked(track: MusicTrack, disliked: Boolean): ProviderMutationResult =
        requireProvider(track.source).setSongDisliked(track, disliked)

    override suspend fun resourceState(resourceType: String, resourceId: String): ProviderResourceState {
        val providerId = providerIdForResource(resourceType, resourceId)
        return providerMap[providerId]?.resourceState(resourceType, resourceId)
            ?: ProviderResourceState(providerId = providerId, resourceId = resourceId)
    }

    override suspend fun setResourceFavorite(
        resourceType: String,
        resourceId: String,
        favorite: Boolean,
    ): ProviderMutationResult {
        val providerId = providerIdForResource(resourceType, resourceId)
        return providerMap[providerId]?.setResourceFavorite(resourceType, resourceId, favorite)
            ?: ProviderMutationResult(false, "当前音源不支持该收藏操作")
    }

    private fun selectedProviders(providerId: String?): List<KotlinMusicProvider> {
        if (!providerId.isNullOrBlank()) return listOf(requireProvider(providerId))
        return enabledProviderIds.mapNotNull { providerMap[it] }
    }

    private fun requireProvider(providerId: String): KotlinMusicProvider =
        providerMap[providerId] ?: error("unknown provider: $providerId")

    private fun requireDeviceAuthorizationProvider(providerId: String): ProviderDeviceAuthorizationCapability =
        requireProvider(providerId) as? ProviderDeviceAuthorizationCapability
            ?: throw UnsupportedOperationException("provider does not support device authorization: $providerId")

    private fun providerIdForResource(resourceType: String, resourceId: String): String {
        val expectedPrefix = when (resourceType.lowercase()) {
            "playlist", "playlists" -> "playlist"
            "video", "videos" -> "video"
            "artist", "artists" -> "artist"
            "album", "albums" -> "album"
            else -> null
        }
        return splitResourceId(resourceId, expectedPrefix).first
    }
}

private fun searchHitKey(hit: ProviderSearchHit): String = when (hit) {
    is ProviderSearchHit.Track -> "track:${hit.value.id}"
    is ProviderSearchHit.Artist -> "artist:${hit.value.id}"
    is ProviderSearchHit.Album -> "album:${hit.value.id}"
    is ProviderSearchHit.Playlist -> "playlist:${hit.value.id}"
    is ProviderSearchHit.Video -> "video:${hit.value.id}"
}

private const val NETEASE_PROVIDER_ID = "netease"
private const val NETEASE_LEGACY_TOP_ARTISTS_FEATURE_ID = "netease_top_artists"

fun createKotlinProviderRepository(
    credentials: ProviderCredentialStore,
    persistentCache: ProviderPersistentCache? = null,
    isCellularConnection: () -> Boolean = { false },
): KotlinProviderRepository = KotlinProviderRepository(
    http = ProviderHttpClient(persistentCache = persistentCache),
    credentials = credentials,
    isCellularConnection = isCellularConnection,
)