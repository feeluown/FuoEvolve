package org.feeluown.mobile

/** Provider surface needed by playback resolution and lyrics. */
interface ProviderPlaybackRepository {
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

    suspend fun lyricsSearchKeyword(track: MusicTrack): String? = null
}

/** Compatibility adapters while the application aggregate repository is being retired. */
internal class ProviderSearchRepositoryView(
    private val delegate: ProviderMusicRepository,
) : ProviderSearchRepository {
    override suspend fun search(keyword: String, providerId: String?): List<MusicTrack> =
        delegate.search(keyword, providerId)

    override suspend fun searchAll(keyword: String, providerId: String?): ProviderSearchResults =
        delegate.searchAll(keyword, providerId)
}

internal class ProviderPlaybackRepositoryView(
    private val delegate: ProviderMusicRepository,
) : ProviderPlaybackRepository {
    override suspend fun replacementCandidates(
        track: MusicTrack,
        smartReplacementProviderIds: Set<String>,
        smartReplacementMinScore: Double,
    ): List<ReplacementCandidate> = delegate.replacementCandidates(
        track,
        smartReplacementProviderIds,
        smartReplacementMinScore,
    )

    override suspend fun resolve(
        track: MusicTrack,
        unavailablePolicy: UnavailablePlaybackPolicy,
        smartReplacementProviderIds: Set<String>,
        smartReplacementMinScore: Double,
        smartReplacementUseOriginalMetadata: Boolean,
        smartReplacementUseOriginalLyrics: Boolean,
    ): PlaybackPayload = delegate.resolve(
        track,
        unavailablePolicy,
        smartReplacementProviderIds,
        smartReplacementMinScore,
        smartReplacementUseOriginalMetadata,
        smartReplacementUseOriginalLyrics,
    )

    override suspend fun resolveSelectedReplacement(
        track: MusicTrack,
        smartReplacementUseOriginalMetadata: Boolean,
        smartReplacementUseOriginalLyrics: Boolean,
        smartReplacementProviderIds: Set<String>,
    ): PlaybackPayload = delegate.resolveSelectedReplacement(
        track,
        smartReplacementUseOriginalMetadata,
        smartReplacementUseOriginalLyrics,
        smartReplacementProviderIds,
    )

    override suspend fun lyrics(track: MusicTrack): String? = delegate.lyrics(track)

    override suspend fun lyricsSearchKeyword(track: MusicTrack): String? =
        delegate.lyricsSearchKeyword(track)
}

internal class ProviderAuthRepositoryView(
    private val delegate: ProviderMusicRepository,
) : ProviderAuthRepository {
    override suspend fun authState(providerId: String): ProviderAuthState = delegate.authState(providerId)
    override suspend fun refreshAuthState(providerId: String): ProviderAuthState = delegate.refreshAuthState(providerId)
    override suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState =
        delegate.loginWithCookies(providerId, cookiesJson)

    override suspend fun loginWithHeaders(
        providerId: String,
        authorization: String,
        cookie: String,
    ): ProviderAuthState = delegate.loginWithHeaders(providerId, authorization, cookie)

    override suspend fun loginWithHeaderFile(providerId: String, headerFileJson: String): ProviderAuthState =
        delegate.loginWithHeaderFile(providerId, headerFileJson)

    override suspend fun beginDeviceAuthorization(
        providerId: String,
        clientId: String,
        clientSecret: String,
    ): ProviderDeviceAuthorization = delegate.beginDeviceAuthorization(providerId, clientId, clientSecret)

    override suspend fun pollDeviceAuthorization(
        providerId: String,
        deviceCode: String,
        clientId: String,
        clientSecret: String,
    ): ProviderDeviceAuthorizationPollResult =
        delegate.pollDeviceAuthorization(providerId, deviceCode, clientId, clientSecret)

    override suspend fun loginWithOAuth(
        providerId: String,
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long?,
        scope: String?,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState = delegate.loginWithOAuth(
        providerId,
        accessToken,
        refreshToken,
        expiresAtMillis,
        scope,
        clientId,
        clientSecret,
    )

    override suspend fun loginWithOAuthJson(
        providerId: String,
        oauthJson: String,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState = delegate.loginWithOAuthJson(providerId, oauthJson, clientId, clientSecret)

    override suspend fun logout(providerId: String): ProviderAuthState = delegate.logout(providerId)
}
