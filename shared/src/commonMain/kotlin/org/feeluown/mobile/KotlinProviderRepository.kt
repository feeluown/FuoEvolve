package org.feeluown.mobile

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.feeluown.mobile.provider.bilibili.BilibiliProvider
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentials
import org.feeluown.mobile.provider.core.mediaItemKey
import org.feeluown.mobile.provider.core.playlistKey
import org.feeluown.mobile.provider.core.splitResourceId
import org.feeluown.mobile.provider.core.trackKey
import org.feeluown.mobile.provider.core.videoKey
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderPersistentCache
import org.feeluown.mobile.provider.netease.NeteaseProvider
import org.feeluown.mobile.provider.qqmusic.QQMusicProvider
import org.feeluown.mobile.provider.ytmusic.YtMusicContentProvider
import org.feeluown.mobile.provider.ytmusic.YtMusicProvider

/**
 * The provider boundary used by the app after the legacy runtime migration.
 * The public repository contract intentionally stays unchanged so playback,
 * sharing and local data do not need a second migration.
 */
class KotlinProviderRepository : ProviderMusicRepository {
    private val stateMutex = Mutex()
    private val http: ProviderHttpClient
    private val credentials: ProviderCredentialStore
    private val isCellularConnection: () -> Boolean
    private val providerMap: Map<String, KotlinMusicProvider>
    private val replacementRanker: ReplacementRanker
    private val replacementModelManager: ReplacementModelManager?
    private var enabledProviderIds: Set<String> = DEFAULT_ENABLED_PROVIDER_IDS
    private var initialized = false
    private var wifiAudioQualityPolicy = DEFAULT_WIFI_AUDIO_QUALITY_POLICY
    private var cellularAudioQualityPolicy = DEFAULT_CELLULAR_AUDIO_QUALITY_POLICY

    constructor() {
        http = ProviderHttpClient()
        credentials = InMemoryProviderCredentialStore()
        isCellularConnection = { false }
        providerMap = createProviders(http, credentials)
        replacementRanker = LegacyReplacementRanker
        replacementModelManager = null
    }

    internal constructor(
        http: ProviderHttpClient,
        credentials: ProviderCredentialStore,
        isCellularConnection: () -> Boolean = { false },
        replacementRanker: ReplacementRanker = LegacyReplacementRanker,
        replacementModelManager: ReplacementModelManager? = null,
    ) {
        this.http = http
        this.credentials = credentials
        this.isCellularConnection = isCellularConnection
        this.replacementRanker = replacementRanker
        this.replacementModelManager = replacementModelManager
        providerMap = createProviders(http, credentials)
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

    override suspend fun search(keyword: String, providerId: String?): List<MusicTrack> {
        return searchAll(keyword, providerId).tracks
    }

    override suspend fun searchAll(keyword: String, providerId: String?): ProviderSearchResults {
        initialize()
        val selected = selectedProviders(providerId)
        val results = selected.map { it.search(keyword) }
        return ProviderSearchResults(
            tracks = results.flatMap { it.tracks }.distinctBy { it.id },
            playlists = results.flatMap { it.playlists }.distinctBy { it.id },
            artists = results.flatMap { it.artists }.distinctBy { it.id },
            albums = results.flatMap { it.albums }.distinctBy { it.id },
            videos = results.flatMap { it.videos }.distinctBy { it.id },
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

    override suspend fun replacementCandidates(
        track: MusicTrack,
        smartReplacementProviderIds: Set<String>,
        smartReplacementMinScore: Double,
    ): List<ReplacementCandidate> = replacementCandidates(
        track = track,
        smartReplacementProviderIds = smartReplacementProviderIds,
        smartReplacementMinScore = smartReplacementMinScore,
        replacementRankingMode = ReplacementRankingMode.Legacy,
        smartReplacementStrictness = SmartReplacementStrictness.Balanced,
        replacementModelTier = ReplacementModelTier.Lite,
    )

    override suspend fun replacementCandidates(
        track: MusicTrack,
        smartReplacementProviderIds: Set<String>,
        smartReplacementMinScore: Double,
        replacementRankingMode: ReplacementRankingMode,
        smartReplacementStrictness: SmartReplacementStrictness,
        replacementModelTier: ReplacementModelTier,
    ): List<ReplacementCandidate> {
        initialize()
        val mediaTrack = track.asOriginalMediaTrack()
        val originalProviderId = mediaTrack.source.ifBlank {
            splitResourceId(mediaTrack.providerId ?: mediaTrack.id).first
        }
        val providers = selectedProvidersForReplacement(smartReplacementProviderIds, originalProviderId)
        val providerById = providers.associateBy { it.id }
        val queries = replacementSearchQueries(mediaTrack)
        val candidates = searchReplacementCandidatesByProvider(
            providerIds = providers.map { it.id },
            queries = queries,
            search = { providerId, query -> providerById.getValue(providerId).search(query).tracks },
        )
        val legacyCandidates = replacementRankingPool(candidates.map { candidate ->
            val versionKind = classifyReplacementVersion(candidate)
            val versionCompatibility = replacementVersionCompatibility(
                original = classifyReplacementVersion(mediaTrack),
                candidate = versionKind,
            )
            val legacyScore = replacementScore(mediaTrack, candidate) * versionCompatibility
            val identityEligible = candidate.passesReplacementIdentityGate(mediaTrack) &&
                legacyScore >= MIN_REPLACEMENT_HARD_IDENTITY_SCORE
            ReplacementCandidate(
                track = candidate,
                score = legacyScore,
                legacyScore = legacyScore,
                versionKind = versionKind,
                versionCompatibility = versionCompatibility,
                autoEligible = identityEligible && versionCompatibility > 0.0,
                rankingStrategy = ReplacementRankingStrategy.LegacyRules,
                reasons = if (identityEligible) emptyList() else listOf("未通过歌曲身份规则，仅可手动选择"),
            )
        })
        val ranked = rankReplacementCandidatesWithFallback(
            request = ReplacementRankingRequest(
                original = mediaTrack,
                candidates = legacyCandidates,
                mode = replacementRankingMode,
                strictness = smartReplacementStrictness,
                modelTier = replacementModelTier,
            ),
            ranker = replacementRanker,
            modelManager = replacementModelManager,
        )
        return ranked
            .map { candidate ->
                if (candidate.rankingStrategy.isOnDevice) {
                    candidate
                } else {
                    candidate.copy(
                        autoEligible = candidate.autoEligible &&
                            candidate.score >= smartReplacementMinScore,
                    )
                }
            }
            .sortedWith(
                compareByDescending<ReplacementCandidate> { it.autoEligible }
                    .thenByDescending { it.score },
            )
            .take(MAX_REPLACEMENT_CANDIDATES)
    }

    override suspend fun resolve(
        track: MusicTrack,
        unavailablePolicy: UnavailablePlaybackPolicy,
        smartReplacementProviderIds: Set<String>,
        smartReplacementMinScore: Double,
        smartReplacementUseOriginalMetadata: Boolean,
        smartReplacementUseOriginalLyrics: Boolean,
    ): PlaybackPayload = resolve(
        track = track,
        unavailablePolicy = unavailablePolicy,
        smartReplacementProviderIds = smartReplacementProviderIds,
        smartReplacementMinScore = smartReplacementMinScore,
        smartReplacementUseOriginalMetadata = smartReplacementUseOriginalMetadata,
        smartReplacementUseOriginalLyrics = smartReplacementUseOriginalLyrics,
        replacementRankingMode = ReplacementRankingMode.Legacy,
        smartReplacementStrictness = SmartReplacementStrictness.Balanced,
        replacementModelTier = ReplacementModelTier.Lite,
    )

    override suspend fun resolve(
        track: MusicTrack,
        unavailablePolicy: UnavailablePlaybackPolicy,
        smartReplacementProviderIds: Set<String>,
        smartReplacementMinScore: Double,
        smartReplacementUseOriginalMetadata: Boolean,
        smartReplacementUseOriginalLyrics: Boolean,
        replacementRankingMode: ReplacementRankingMode,
        smartReplacementStrictness: SmartReplacementStrictness,
        replacementModelTier: ReplacementModelTier,
    ): PlaybackPayload {
        initialize()
        val quality = if (isCellularConnection()) {
            cellularAudioQualityPolicy.policy
        } else {
            wifiAudioQualityPolicy.policy
        }
        // Persisted smart-replaced tracks keep original ids but may flip `source` to the
        // replacement provider. Prefer the known replacement, then the original identity.
        var failedKnownReplacementId: String? = null
        if (track.isSmartReplacement) {
            resolveKnownReplacement(
                track = track,
                quality = quality,
                useOriginalMetadata = smartReplacementUseOriginalMetadata,
                useOriginalLyrics = smartReplacementUseOriginalLyrics,
            )?.let { return it }
            failedKnownReplacementId = track.replacementId
        }
        val mediaTrack = track.asOriginalMediaTrack()
        val originalProviderId = mediaTrack.source.ifBlank {
            splitResourceId(mediaTrack.providerId ?: mediaTrack.id).first
        }
        val originalProvider = providerMap[originalProviderId]
        val direct = originalProvider?.resolve(mediaTrack, quality)
        if (direct != null) return direct
        if (unavailablePolicy == UnavailablePlaybackPolicy.Skip) {
            error("media not found: ${mediaTrack.id}")
        }

        val candidates = replacementCandidates(
            track = mediaTrack,
            smartReplacementProviderIds = smartReplacementProviderIds,
            smartReplacementMinScore = smartReplacementMinScore,
            replacementRankingMode = replacementRankingMode,
            smartReplacementStrictness = smartReplacementStrictness,
            replacementModelTier = replacementModelTier,
        ).filterNot { candidate -> candidate.track.id == failedKnownReplacementId }
        val selected = selectRankedReplacementCandidate(
            candidates = candidates,
            resolve = { candidate -> providerMap[candidate.source]?.resolve(candidate, quality) },
        ) ?: error("media not found after smart replacement: ${mediaTrack.id}")
        val (candidate, score, payload) = selected
        return annotateSmartReplacement(
            payload = payload,
            original = mediaTrack,
            candidate = candidate,
            score = score,
            useOriginalMetadata = smartReplacementUseOriginalMetadata,
            useOriginalLyrics = smartReplacementUseOriginalLyrics,
        )
    }

    override suspend fun resolveSelectedReplacement(
        track: MusicTrack,
        smartReplacementUseOriginalMetadata: Boolean,
        smartReplacementUseOriginalLyrics: Boolean,
        smartReplacementProviderIds: Set<String>,
    ): PlaybackPayload {
        initialize()
        val quality = if (isCellularConnection()) {
            cellularAudioQualityPolicy.policy
        } else {
            wifiAudioQualityPolicy.policy
        }
        val replacementTrack = track.asReplacementMediaTrack()
            ?: error("selected replacement is missing: ${track.id}")
        val allowedProviderIds = if (smartReplacementProviderIds.isEmpty()) {
            enabledProviderIds
        } else {
            smartReplacementProviderIds.intersect(enabledProviderIds)
        }
        if (replacementTrack.source !in allowedProviderIds) {
            error("selected replacement source is disabled: ${replacementTrack.source}")
        }
        val payload = providerMap[replacementTrack.source]?.resolve(replacementTrack, quality)
            ?: error("selected replacement is unavailable: ${replacementTrack.id}")
        return annotateSmartReplacement(
            payload = payload,
            original = track.asOriginalMediaTrack(),
            candidate = replacementTrack,
            score = track.replacementScore ?: 1.0,
            useOriginalMetadata = smartReplacementUseOriginalMetadata,
            useOriginalLyrics = smartReplacementUseOriginalLyrics,
        ).copy(replacementStrategy = "user_selected")
    }

    private suspend fun resolveKnownReplacement(
        track: MusicTrack,
        quality: String,
        useOriginalMetadata: Boolean,
        useOriginalLyrics: Boolean,
    ): PlaybackPayload? {
        val replacementTrack = track.asReplacementMediaTrack() ?: return null
        val provider = providerMap[replacementTrack.source] ?: return null
        val payload = provider.resolve(replacementTrack, quality) ?: return null
        val original = track.asOriginalMediaTrack()
        return annotateSmartReplacement(
            payload = payload,
            original = original,
            candidate = replacementTrack,
            score = track.replacementScore ?: 1.0,
            useOriginalMetadata = useOriginalMetadata,
            useOriginalLyrics = useOriginalLyrics,
        ).copy(replacementStrategy = track.replacementStrategy ?: "title_artist_duration")
    }

    private fun annotateSmartReplacement(
        payload: PlaybackPayload,
        original: MusicTrack,
        candidate: MusicTrack,
        score: Double,
        useOriginalMetadata: Boolean,
        useOriginalLyrics: Boolean,
    ): PlaybackPayload = payload.copy(
        isSmartReplacement = true,
        originalId = original.id,
        originalTitle = original.title,
        originalArtists = original.artists,
        originalAlbum = original.album,
        originalSource = original.source,
        originalProviderName = original.providerName,
        originalCoverUrl = original.coverUrl,
        replacementId = candidate.id,
        replacementTitle = candidate.title,
        replacementArtists = candidate.artists,
        replacementAlbum = candidate.album,
        replacementSource = candidate.source,
        replacementProviderName = candidate.providerName,
        replacementCoverUrl = candidate.coverUrl,
        replacementStrategy = "title_artist_duration",
        replacementScore = score,
        title = if (useOriginalMetadata) original.title else candidate.title,
        artists = if (useOriginalMetadata) original.artists else candidate.artists,
        album = if (useOriginalMetadata) original.album else candidate.album,
        coverUrl = if (useOriginalMetadata) original.coverUrl else candidate.coverUrl,
        lyrics = if (useOriginalLyrics) original.lyrics else payload.lyrics,
    )

    override suspend fun lyrics(track: MusicTrack): String? {
        initialize()
        track.lyrics?.takeIf { it.isNotBlank() }?.let { return it }
        val lyricTrack = track.asOriginalMediaTrack()
        val providerId = lyricTrack.source.ifBlank {
            splitResourceId(lyricTrack.providerId ?: lyricTrack.id).first
        }
        return providerMap[providerId]?.lyrics(lyricTrack)?.takeIf { it.isNotBlank() }
    }

    override suspend fun authState(providerId: String): ProviderAuthState = requireProvider(providerId).authState()

    override suspend fun refreshAuthState(providerId: String): ProviderAuthState = requireProvider(providerId).authState()

    override suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState =
        requireProvider(providerId).loginWithCookies(cookiesJson)

    override suspend fun loginWithHeaders(providerId: String, authorization: String, cookie: String): ProviderAuthState =
        requireProvider(providerId).loginWithHeaders(authorization, cookie)

    override suspend fun loginWithYtmusicHeaderFile(headerFileJson: String): ProviderAuthState =
        requireProvider("ytmusic").loginWithHeaderFile(headerFileJson)

    override suspend fun beginYtmusicOAuth(clientId: String, clientSecret: String) =
        requireYtMusicProvider().beginOAuth(clientId, clientSecret)

    override suspend fun pollYtmusicOAuth(
        deviceCode: String,
        clientId: String,
        clientSecret: String,
    ) = requireYtMusicProvider().pollOAuth(deviceCode, clientId, clientSecret)

    override suspend fun loginWithYtmusicOAuth(
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long?,
        scope: String?,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState = requireYtMusicProvider().loginWithOAuth(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtMillis = expiresAtMillis,
        scope = scope,
        clientId = clientId,
        clientSecret = clientSecret,
    )

    override suspend fun loginWithYtmusicOAuthJson(
        oauthJson: String,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState = requireYtMusicProvider().loginWithOAuthJson(oauthJson, clientId, clientSecret)

    override suspend fun logout(providerId: String): ProviderAuthState = requireProvider(providerId).logout()

    override suspend fun updateAudioQualityPolicies(wifiPolicy: AudioQualityPolicy, cellularPolicy: AudioQualityPolicy) {
        wifiAudioQualityPolicy = wifiPolicy
        cellularAudioQualityPolicy = cellularPolicy
    }

    override suspend fun providerCapabilities(): List<ProviderCapabilities> {
        initialize()
        return enabledProviderIds.mapNotNull { providerMap[it]?.capabilities }
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

    override suspend fun playlistDetailPage(playlist: ProviderPlaylist, offset: Int, limit: Int): ProviderPlaylistDetail =
        requireProvider(playlist.providerId).playlistDetail(playlist, offset, limit)

    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> =
        requireProvider(playlist.providerId).playlistTracks(playlist)

    override suspend fun playlistOperationTargets(track: MusicTrack): List<ProviderPlaylist> {
        val provider = requireProvider(track.source)
        return provider.playlistOperationTargets(track)
    }

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

    override suspend fun mediaItemDetail(item: ProviderMediaItem): ProviderMediaItemDetail =
        mediaItemDetailPage(item, 0, 0, PROVIDER_PAGE_SIZE)

    override suspend fun mediaItemDetailPage(item: ProviderMediaItem, tracksOffset: Int, albumsOffset: Int, limit: Int): ProviderMediaItemDetail =
        requireProvider(item.providerId).mediaItemDetail(item, tracksOffset, albumsOffset, limit)

    override suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack> =
        requireProvider(item.providerId).mediaItemTracks(item)

    override suspend fun similarTracks(track: MusicTrack): List<MusicTrack> = requireProvider(track.source).similarTracks(track)

    override suspend fun hotComments(track: MusicTrack): List<ProviderComment> = requireProvider(track.source).hotComments(track)

    override suspend fun trackVideo(track: MusicTrack): ProviderVideo? = requireProvider(track.source).trackVideo(track)

    override suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload =
        requireProvider(video.providerId).videoPlaybackPayload(video)

    override suspend fun resourceState(resourceType: String, resourceId: String): ProviderResourceState {
        val providerId = providerIdForResource(resourceType, resourceId)
        return providerMap[providerId]?.resourceState(resourceType, resourceId)
            ?: ProviderResourceState(providerId = providerId, resourceId = resourceId)
    }

    override suspend fun setResourceFavorite(resourceType: String, resourceId: String, favorite: Boolean): ProviderMutationResult {
        val providerId = providerIdForResource(resourceType, resourceId)
        return providerMap[providerId]?.setResourceFavorite(resourceType, resourceId, favorite)
            ?: ProviderMutationResult(false, "当前音源不支持该收藏操作")
    }

    private fun selectedProviders(providerId: String?): List<KotlinMusicProvider> {
        if (!providerId.isNullOrBlank()) return listOf(requireProvider(providerId))
        return enabledProviderIds.mapNotNull { providerMap[it] }
    }

    private fun selectedProvidersForReplacement(providerIds: Set<String>, originalProviderId: String): List<KotlinMusicProvider> {
        val ids = if (providerIds.isEmpty()) enabledProviderIds else providerIds
        return providerMap.values.filter { provider -> provider.id in ids && provider.id != originalProviderId }
    }

    private fun MusicTrack.asOriginalMediaTrack(): MusicTrack {
        if (!isSmartReplacement) return this
        val originalId = originalId?.takeIf { it.isNotBlank() } ?: id
        val originalSource = originalSource?.takeIf { it.isNotBlank() }
            ?: splitResourceId(originalId).first.takeIf { it.isNotBlank() }
            ?: source
        return copy(
            id = originalId,
            providerId = originalId,
            source = originalSource,
            providerName = originalProviderName ?: providerName,
            title = originalTitle ?: title,
            artists = originalArtists ?: artists,
            album = originalAlbum ?: album,
            coverUrl = originalCoverUrl ?: coverUrl,
            isSmartReplacement = false,
            originalId = null,
            originalTitle = null,
            originalArtists = null,
            originalAlbum = null,
            originalSource = null,
            originalProviderName = null,
            originalCoverUrl = null,
            replacementId = null,
            replacementTitle = null,
            replacementArtists = null,
            replacementAlbum = null,
            replacementSource = null,
            replacementProviderName = null,
            replacementCoverUrl = null,
            replacementStrategy = null,
            replacementScore = null,
        )
    }

    private fun MusicTrack.asReplacementMediaTrack(): MusicTrack? {
        val replacementId = replacementId?.takeIf { it.isNotBlank() } ?: return null
        val replacementSource = replacementSource?.takeIf { it.isNotBlank() }
            ?: splitResourceId(replacementId).first.takeIf { it.isNotBlank() }
            ?: return null
        return copy(
            id = replacementId,
            providerId = replacementId,
            source = replacementSource,
            providerName = replacementProviderName ?: providerName,
            title = replacementTitle ?: title,
            artists = replacementArtists ?: artists,
            album = replacementAlbum ?: album,
            coverUrl = replacementCoverUrl ?: coverUrl,
            isSmartReplacement = false,
            originalId = null,
            originalTitle = null,
            originalArtists = null,
            originalAlbum = null,
            originalSource = null,
            originalProviderName = null,
            originalCoverUrl = null,
            replacementId = null,
            replacementTitle = null,
            replacementArtists = null,
            replacementAlbum = null,
            replacementSource = null,
            replacementProviderName = null,
            replacementCoverUrl = null,
            replacementStrategy = null,
            replacementScore = null,
        )
    }

    private fun requireProvider(providerId: String): KotlinMusicProvider =
        providerMap[providerId] ?: error("unknown provider: $providerId")

    private fun requireYtMusicProvider(): YtMusicContentProvider =
        requireProvider("ytmusic") as? YtMusicContentProvider
            ?: error("ytmusic provider is not available")

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

    private fun replacementScore(origin: MusicTrack, candidate: MusicTrack): Double {
        if (candidate.source == BILIBILI_PROVIDER_ID) {
            return bilibiliReplacementScore(origin, candidate)
        }
        val originTitle = normalizeReplacementText(origin.title)
        val candidateTitle = normalizeReplacementText(candidate.title)
        val titleScore = when {
            originTitle == candidateTitle -> 1.0
            originTitle.contains(candidateTitle) || candidateTitle.contains(originTitle) -> 0.8
            else -> tokenSimilarity(originTitle, candidateTitle)
        }
        val originArtists = normalizeReplacementText(origin.artists)
        val candidateArtists = normalizeReplacementText(candidate.artists)
        val artistScore = when {
            originArtists == candidateArtists -> 1.0
            originArtists.contains(candidateArtists) || candidateArtists.contains(originArtists) -> 0.8
            else -> tokenSimilarity(originArtists, candidateArtists)
        }
        val durationScore = if (origin.durationMs == null || candidate.durationMs == null) {
            0.5
        } else {
            (1.0 - (kotlin.math.abs(origin.durationMs - candidate.durationMs).toDouble() / 30_000.0)).coerceIn(0.0, 1.0)
        }
        return titleScore * 0.55 + artistScore * 0.35 + durationScore * 0.10
    }

    private fun tokenSimilarity(left: String, right: String): Double {
        if (left.isBlank() || right.isBlank()) return 0.0
        val common = left.toSet().intersect(right.toSet()).size.toDouble()
        return common / maxOf(left.toSet().size, right.toSet().size).toDouble()
    }

    private fun createProviders(http: ProviderHttpClient, credentials: ProviderCredentialStore): Map<String, KotlinMusicProvider> {
        val ytmusic = YtMusicProvider(http, credentials)
        return mapOf(
            "netease" to NeteaseProvider(http, credentials),
            "qqmusic" to QQMusicProvider(http, credentials),
            "bilibili" to BilibiliProvider(http, credentials),
            "ytmusic" to YtMusicContentProvider(ytmusic, http, credentials),
        )
    }
}

internal fun rankReplacementCandidates(
    candidates: List<MusicTrack>,
    minScore: Double,
    scoreOf: (MusicTrack) -> Double,
): List<ReplacementCandidate> {
    return candidates
        .map { candidate -> ReplacementCandidate(candidate, scoreOf(candidate)) }
        .filter { candidate -> candidate.score >= minScore }
        .sortedByDescending { candidate -> candidate.score }
        .distinctBy { candidate -> candidate.track.id }
}

internal fun replacementRankingPool(
    candidates: List<ReplacementCandidate>,
): List<ReplacementCandidate> {
    val deduplicated = candidates.distinctBy { candidate -> candidate.track.id }
    val sourceAnchors = deduplicated
        .groupBy { candidate -> candidate.track.source }
        .values
        .flatMap { sourceCandidates ->
            sourceCandidates
                .sortedByDescending { candidate -> candidate.legacyScore }
                .take(REPLACEMENT_SOURCE_ANCHOR_COUNT)
        }
    return (sourceAnchors + deduplicated.sortedByDescending { candidate -> candidate.legacyScore })
        .distinctBy { candidate -> candidate.track.id }
        .take(MAX_REPLACEMENT_CANDIDATES)
        .sortedByDescending { candidate -> candidate.legacyScore }
}

internal suspend fun searchReplacementCandidatesByProvider(
    providerIds: List<String>,
    queries: List<String>,
    search: suspend (providerId: String, query: String) -> List<MusicTrack>,
): List<MusicTrack> = supervisorScope {
    providerIds.map { providerId ->
        async {
            supervisorScope {
                queries.map { query ->
                    async {
                        try {
                            withTimeoutOrNull(REPLACEMENT_PROVIDER_SEARCH_TIMEOUT_MS) {
                                search(providerId, query)
                            }.orEmpty()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            emptyList()
                        }
                    }
                }.awaitAll()
                    .flatten()
                    .distinctBy { candidate -> candidate.id }
                    .take(REPLACEMENT_CANDIDATES_PER_PROVIDER)
            }
        }
    }.awaitAll()
        .flatten()
        .distinctBy { candidate -> candidate.id }
}

internal fun replacementSearchQueries(track: MusicTrack): List<String> {
    val original = "${track.title} ${track.artists}".normalizeReplacementQueryWhitespace()
    val normalizedTitle = track.title
        .replace(REPLACEMENT_QUERY_BRACKETED_TEXT, " ")
        .replace(REPLACEMENT_QUERY_FEAT_SUFFIX, " ")
        .normalizeReplacementQueryWhitespace()
    val normalizedArtists = track.artists
        .replace(REPLACEMENT_QUERY_FEAT_SUFFIX, " ")
        .normalizeReplacementQueryWhitespace()
    val normalized = "$normalizedTitle $normalizedArtists".normalizeReplacementQueryWhitespace()
    return listOf(original, normalized)
        .filter { it.isNotBlank() }
        .distinct()
}

private fun String.normalizeReplacementQueryWhitespace(): String =
    trim().replace(Regex("\\s+"), " ")

private const val REPLACEMENT_PROVIDER_SEARCH_TIMEOUT_MS = 8_000L
private const val REPLACEMENT_SOURCE_ANCHOR_COUNT = 2
private const val MIN_REPLACEMENT_HARD_IDENTITY_SCORE = 0.25
private val REPLACEMENT_QUERY_BRACKETED_TEXT = Regex("[\\(（\\[【][^\\)）\\]】]*[\\)）\\]】]")
private val REPLACEMENT_QUERY_FEAT_SUFFIX = Regex(
    pattern = "(?i)(?:\\s|^)(?:feat\\.?|ft\\.?|featuring)\\s+.*$",
)

internal suspend fun <T> selectRankedReplacementCandidate(
    candidates: List<ReplacementCandidate>,
    resolve: suspend (MusicTrack) -> T?,
): Triple<MusicTrack, Double, T>? {
    for (candidate in candidates) {
        if (!candidate.autoEligible) continue
        val resolved = resolve(candidate.track) ?: continue
        return Triple(candidate.track, candidate.score, resolved)
    }
    return null
}

internal suspend fun <T> selectReplacementCandidate(
    candidates: List<MusicTrack>,
    minScore: Double,
    scoreOf: (MusicTrack) -> Double,
    resolve: suspend (MusicTrack) -> T?,
): Triple<MusicTrack, Double, T>? {
    return selectRankedReplacementCandidate(
        candidates = rankReplacementCandidates(candidates, minScore, scoreOf),
        resolve = resolve,
    )
}

internal fun bilibiliReplacementScore(origin: MusicTrack, candidate: MusicTrack): Double {
    val originTitle = normalizeReplacementText(origin.title)
    val candidateTitle = normalizeReplacementText(candidate.title)
    val candidateArtists = normalizeReplacementText(candidate.artists)
    if (originTitle.isBlank() || candidateTitle.isBlank()) return 0.0

    var score = 0.0
    if (originTitle in candidateTitle) {
        score += 0.40
    }
    if (
        replacementArtistMatchTexts(origin.artists).any { artist ->
            artist in candidateTitle || artist in candidateArtists
        }
    ) {
        score += 0.20
    }
    if (BILIBILI_REPLACEMENT_BONUS_KEYWORDS.any { keyword -> keyword in candidateTitle }) {
        score += 0.10
    }
    if (
        BILIBILI_REPLACEMENT_PENALTY_KEYWORDS.none { keyword -> keyword in originTitle } &&
        BILIBILI_REPLACEMENT_PENALTY_KEYWORDS.any { keyword -> keyword in candidateTitle }
    ) {
        score -= 0.20
    }
    return applyReplacementDurationPenalty(score, origin, candidate)
}

private fun applyReplacementDurationPenalty(score: Double, origin: MusicTrack, candidate: MusicTrack): Double {
    val originDuration = origin.durationMs
    val candidateDuration = candidate.durationMs
    if (originDuration != null && originDuration > 0 && candidateDuration != null && candidateDuration > 0) {
        val diffRatio = kotlin.math.abs(originDuration - candidateDuration).toDouble() / originDuration.toDouble()
        return (score - minOf(diffRatio * MAX_REPLACEMENT_DURATION_PENALTY, MAX_REPLACEMENT_DURATION_PENALTY))
            .coerceAtLeast(0.0)
    }
    return score.coerceAtLeast(0.0)
}

private fun normalizeReplacementText(value: String): String =
    value.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

private fun replacementArtistMatchTexts(value: String): List<String> {
    var parts = listOf(value)
    REPLACEMENT_ARTIST_SEPARATORS.forEach { separator ->
        parts = parts.flatMap { part -> part.split(separator) }
    }
    return parts
        .map { part -> normalizeReplacementText(part.trim()) }
        .filter { it.isNotBlank() }
        .distinct()
}

private const val NETEASE_PROVIDER_ID = "netease"
private const val NETEASE_LEGACY_TOP_ARTISTS_FEATURE_ID = "netease_top_artists"
private const val BILIBILI_PROVIDER_ID = "bilibili"
private val BILIBILI_REPLACEMENT_BONUS_KEYWORDS = listOf("mv", "hires")
private val BILIBILI_REPLACEMENT_PENALTY_KEYWORDS = listOf("cover", "翻唱", "remix")
private val REPLACEMENT_ARTIST_SEPARATORS = listOf(" / ", "/", "、", ",", "，", ";", "；", "&", "+", "＋")
private const val MAX_REPLACEMENT_DURATION_PENALTY = 0.30

fun createKotlinProviderRepository(
    credentials: ProviderCredentialStore,
    persistentCache: ProviderPersistentCache? = null,
    isCellularConnection: () -> Boolean = { false },
    replacementRanker: ReplacementRanker = LegacyReplacementRanker,
    replacementModelManager: ReplacementModelManager? = null,
): KotlinProviderRepository = KotlinProviderRepository(
    http = ProviderHttpClient(persistentCache = persistentCache),
    credentials = credentials,
    isCellularConnection = isCellularConnection,
    replacementRanker = replacementRanker,
    replacementModelManager = replacementModelManager,
)
