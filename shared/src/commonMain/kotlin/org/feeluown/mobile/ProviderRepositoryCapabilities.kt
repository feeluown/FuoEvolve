package org.feeluown.mobile

/** Read-only provider surface needed by the search feature. */
interface ProviderSearchRepository {
    suspend fun search(keyword: String, providerId: String? = null): List<MusicTrack>
    suspend fun searchAll(keyword: String, providerId: String? = null): ProviderSearchResults
}

/** Provider surface needed by playback resolution and lyrics. */
interface ProviderPlaybackRepository {
    suspend fun replacementCandidates(
        track: MusicTrack,
        smartReplacementProviderIds: Set<String> = emptySet(),
        smartReplacementMinScore: Double = DEFAULT_SMART_REPLACEMENT_MIN_SCORE,
    ): List<ReplacementCandidate>

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
    ): PlaybackPayload

    suspend fun lyrics(track: MusicTrack): String?
}

/** Provider authentication surface independent of any concrete provider implementation. */
interface ProviderAuthRepository {
    suspend fun authState(providerId: String): ProviderAuthState
    suspend fun refreshAuthState(providerId: String): ProviderAuthState
    suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState
    suspend fun loginWithHeaders(providerId: String, authorization: String, cookie: String): ProviderAuthState
    suspend fun loginWithHeaderFile(providerId: String, headerFileJson: String): ProviderAuthState
    suspend fun beginDeviceAuthorization(
        providerId: String,
        clientId: String,
        clientSecret: String,
    ): ProviderDeviceAuthorization
    suspend fun pollDeviceAuthorization(
        providerId: String,
        deviceCode: String,
        clientId: String,
        clientSecret: String,
    ): ProviderDeviceAuthorizationPollResult
    suspend fun loginWithOAuth(
        providerId: String,
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long?,
        scope: String?,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState
    suspend fun loginWithOAuthJson(
        providerId: String,
        oauthJson: String,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState
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

/**
 * Compatibility adapters while the legacy aggregate repository is being retired.
 * New features depend on capability interfaces rather than the aggregate surface.
 */
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
        when (providerId) {
            "ytmusic" -> delegate.loginWithYtmusicHeaderFile(headerFileJson)
            else -> throw UnsupportedOperationException("provider does not support header file login: $providerId")
        }

    override suspend fun beginDeviceAuthorization(
        providerId: String,
        clientId: String,
        clientSecret: String,
    ): ProviderDeviceAuthorization = when (providerId) {
        "ytmusic" -> delegate.beginYtmusicOAuth(clientId, clientSecret).let { result ->
            ProviderDeviceAuthorization(
                providerId = providerId,
                deviceCode = result.deviceCode,
                userCode = result.userCode,
                verificationUrl = result.verificationUrl,
                expiresInSeconds = result.expiresInSeconds,
                intervalSeconds = result.intervalSeconds,
            )
        }
        else -> throw UnsupportedOperationException("provider does not support device authorization: $providerId")
    }

    override suspend fun pollDeviceAuthorization(
        providerId: String,
        deviceCode: String,
        clientId: String,
        clientSecret: String,
    ): ProviderDeviceAuthorizationPollResult = when (providerId) {
        "ytmusic" -> when (val result = delegate.pollYtmusicOAuth(deviceCode, clientId, clientSecret)) {
            is org.feeluown.mobile.provider.ytmusic.YtMusicOAuthPollResult.Authorized ->
                ProviderDeviceAuthorizationPollResult.Authorized(
                    ProviderOAuthToken(
                        accessToken = result.token.accessToken,
                        refreshToken = result.token.refreshToken,
                        scope = result.token.scope,
                        expiresAtMillis = result.token.expiresAtEpochSeconds * 1_000,
                    )
                )
            org.feeluown.mobile.provider.ytmusic.YtMusicOAuthPollResult.Pending ->
                ProviderDeviceAuthorizationPollResult.Pending
            org.feeluown.mobile.provider.ytmusic.YtMusicOAuthPollResult.SlowDown ->
                ProviderDeviceAuthorizationPollResult.SlowDown
            is org.feeluown.mobile.provider.ytmusic.YtMusicOAuthPollResult.Denied ->
                ProviderDeviceAuthorizationPollResult.Denied(result.message)
        }
        else -> throw UnsupportedOperationException("provider does not support device authorization: $providerId")
    }

    override suspend fun loginWithOAuth(
        providerId: String,
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long?,
        scope: String?,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState = when (providerId) {
        "ytmusic" -> delegate.loginWithYtmusicOAuth(
            accessToken,
            refreshToken,
            expiresAtMillis,
            scope,
            clientId,
            clientSecret,
        )
        else -> throw UnsupportedOperationException("provider does not support OAuth login: $providerId")
    }

    override suspend fun loginWithOAuthJson(
        providerId: String,
        oauthJson: String,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState = when (providerId) {
        "ytmusic" -> delegate.loginWithYtmusicOAuthJson(oauthJson, clientId, clientSecret)
        else -> throw UnsupportedOperationException("provider does not support OAuth JSON login: $providerId")
    }

    override suspend fun logout(providerId: String): ProviderAuthState = delegate.logout(providerId)
}
