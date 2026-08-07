package org.feeluown.mobile

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
    private var enabledProviderIds: Set<String> = DEFAULT_ENABLED_PROVIDER_IDS
    private var initialized = false
    private var wifiAudioQualityPolicy = DEFAULT_WIFI_AUDIO_QUALITY_POLICY
    private var cellularAudioQualityPolicy = DEFAULT_CELLULAR_AUDIO_QUALITY_POLICY

    constructor() {
        http = ProviderHttpClient()
        credentials = InMemoryProviderCredentialStore()
        isCellularConnection = { false }
        providerMap = createProviders(http, credentials)
    }

    internal constructor(
        http: ProviderHttpClient,
        credentials: ProviderCredentialStore,
        isCellularConnection: () -> Boolean = { false },
    ) {
        this.http = http
        this.credentials = credentials
        this.isCellularConnection = isCellularConnection
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

    override suspend fun resolve(
        track: MusicTrack,
        unavailablePolicy: UnavailablePlaybackPolicy,
        smartReplacementProviderIds: Set<String>,
        smartReplacementMinScore: Double,
        smartReplacementUseOriginalMetadata: Boolean,
        smartReplacementUseOriginalLyrics: Boolean,
    ): PlaybackPayload {
        initialize()
        val quality = if (isCellularConnection()) {
            cellularAudioQualityPolicy.policy
        } else {
            wifiAudioQualityPolicy.policy
        }
        // Persisted smart-replaced tracks keep original ids but may flip `source` to the
        // replacement provider. Prefer the known replacement, then the original identity.
        if (track.isSmartReplacement) {
            resolveKnownReplacement(
                track = track,
                quality = quality,
                useOriginalMetadata = smartReplacementUseOriginalMetadata,
                useOriginalLyrics = smartReplacementUseOriginalLyrics,
            )?.let { return it }
        }
        val mediaTrack = track.asOriginalMediaTrack()
        val originalProviderId = mediaTrack.source.ifBlank {
            splitResourceId(mediaTrack.providerId ?: mediaTrack.id).first
        }
        val originalProvider = providerMap[originalProviderId]
        val direct = originalProvider?.resolve(mediaTrack, quality)
        if (direct != null) return direct
        if (unavailablePolicy == UnavailablePlaybackPolicy.Skip) {
            error("media unavailable: ${mediaTrack.id}")
        }

        val candidates = selectedProvidersForReplacement(smartReplacementProviderIds, originalProviderId)
            .flatMap { provider -> provider.search("${mediaTrack.title} ${mediaTrack.artists}").tracks }
        val selected = selectReplacementCandidate(
            candidates = candidates,
            minScore = smartReplacementMinScore,
            scoreOf = { candidate -> replacementScore(mediaTrack, candidate) },
            resolve = { candidate -> providerMap[candidate.source]?.resolve(candidate, quality) },
        ) ?: error("media unavailable and no smart replacement: ${mediaTrack.id}")
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
        )
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
        return enabledProviderIds.flatMap { providerMap[it]?.features.orEmpty() }
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
        return ids.filter { it != originalProviderId }.mapNotNull { providerMap[it] }
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

    private fun requireYtMusicProvider(): YtMusicProvider =
        requireProvider("ytmusic") as? YtMusicProvider
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

    private fun createProviders(http: ProviderHttpClient, credentials: ProviderCredentialStore): Map<String, KotlinMusicProvider> = mapOf(
        "netease" to NeteaseProvider(http, credentials),
        "qqmusic" to QQMusicProvider(http, credentials),
        "bilibili" to BilibiliProvider(http, credentials),
        "ytmusic" to YtMusicProvider(http, credentials),
    )
}

internal suspend fun <T> selectReplacementCandidate(
    candidates: List<MusicTrack>,
    minScore: Double,
    scoreOf: (MusicTrack) -> Double,
    resolve: suspend (MusicTrack) -> T?,
): Triple<MusicTrack, Double, T>? {
    val ranked = candidates
        .map { candidate -> candidate to scoreOf(candidate) }
        .filter { (_, score) -> score >= minScore }
        .sortedByDescending { (_, score) -> score }
    for ((candidate, score) in ranked) {
        val resolved = resolve(candidate) ?: continue
        return Triple(candidate, score, resolved)
    }
    return null
}

internal fun bilibiliReplacementScore(origin: MusicTrack, candidate: MusicTrack): Double {
    val originTitle = normalizeReplacementText(origin.title)
    val candidateTitle = normalizeReplacementText(candidate.title)
    if (originTitle.isBlank() || candidateTitle.isBlank()) return 0.0

    var score = 0.0
    if (originTitle in candidateTitle) {
        score += 0.40
    }
    if (replacementArtistMatchTexts(origin.artists).any { artist -> artist in candidateTitle }) {
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

private const val BILIBILI_PROVIDER_ID = "bilibili"
private val BILIBILI_REPLACEMENT_BONUS_KEYWORDS = listOf("mv", "hires")
private val BILIBILI_REPLACEMENT_PENALTY_KEYWORDS = listOf("cover", "翻唱", "remix")
private val REPLACEMENT_ARTIST_SEPARATORS = listOf(" / ", "/", "、", ",", "，", ";", "；", "&", "+", "＋")
private const val MAX_REPLACEMENT_DURATION_PENALTY = 0.30

fun createKotlinProviderRepository(
    credentials: ProviderCredentialStore,
    persistentCache: ProviderPersistentCache? = null,
    isCellularConnection: () -> Boolean = { false },
): KotlinProviderRepository = KotlinProviderRepository(
    http = ProviderHttpClient(persistentCache = persistentCache),
    credentials = credentials,
    isCellularConnection = isCellularConnection,
)
