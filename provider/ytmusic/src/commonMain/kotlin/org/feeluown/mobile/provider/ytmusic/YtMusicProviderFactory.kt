package org.feeluown.mobile.provider.ytmusic

import org.feeluown.mobile.ProviderDeviceAuthorization
import org.feeluown.mobile.ProviderDeviceAuthorizationPollResult
import org.feeluown.mobile.ProviderOAuthToken
import org.feeluown.mobile.ProviderPlaybackReport
import org.feeluown.mobile.ProviderPlaybackReportingCapability
import org.feeluown.mobile.ProviderSearchHit
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.provider.core.CapabilityDelegatingProvider
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.KotlinProviderFactory
import org.feeluown.mobile.provider.core.ProviderDeviceAuthorizationCapability
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies

/** Compile-time plugin entry for the YouTube Music provider module. */
object YtMusicProviderFactory : KotlinProviderFactory {
    override val providerId: String = "ytmusic"

    override fun create(dependencies: ProviderRuntimeDependencies): KotlinMusicProvider {
        val base = YtMusicProvider(dependencies.http, dependencies.credentials)
        val content = YtMusicContentProvider(base, dependencies.http, dependencies.credentials)
        val provider = CapabilityDelegatingProvider(
            base = base,
            presentation = content,
            account = content,
            discovery = content,
            content = content,
            library = content,
            playback = content,
        )
        val reportingProvider = YtMusicPlaybackReportingProvider(
            delegate = provider,
            http = dependencies.http,
            credentials = dependencies.credentials,
        )
        return YtMusicApplicationProvider(
            provider = reportingProvider,
            oauth = content,
        )
    }
}

private class YtMusicApplicationProvider(
    private val provider: KotlinMusicProvider,
    private val oauth: YtMusicContentProvider,
) : KotlinMusicProvider by provider,
    ProviderDeviceAuthorizationCapability,
    ProviderPlaybackReportingCapability {
    override suspend fun search(keyword: String): ProviderSearchResults {
        val results = provider.search(keyword)
        if (results.bestMatches.isNotEmpty()) return results
        return results.copy(bestMatches = listOfNotNull(bestMatch(keyword, results)))
    }

    override suspend fun reportPlayback(report: ProviderPlaybackReport) {
        (provider as? ProviderPlaybackReportingCapability)?.reportPlayback(report)
    }

    override suspend fun beginDeviceAuthorization(
        clientId: String,
        clientSecret: String,
    ): ProviderDeviceAuthorization = oauth.beginOAuth(clientId, clientSecret).let { code ->
        ProviderDeviceAuthorization(
            providerId = YtMusicProvider.ID,
            deviceCode = code.deviceCode,
            userCode = code.userCode,
            verificationUrl = code.verificationUrl,
            expiresInSeconds = code.expiresInSeconds,
            intervalSeconds = code.intervalSeconds,
        )
    }

    override suspend fun pollDeviceAuthorization(
        deviceCode: String,
        clientId: String,
        clientSecret: String,
    ): ProviderDeviceAuthorizationPollResult = when (
        val result = oauth.pollOAuth(deviceCode, clientId, clientSecret)
    ) {
        is YtMusicOAuthPollResult.Authorized -> ProviderDeviceAuthorizationPollResult.Authorized(
            ProviderOAuthToken(
                accessToken = result.token.accessToken,
                refreshToken = result.token.refreshToken,
                scope = result.token.scope,
                expiresAtMillis = result.token.expiresAtEpochSeconds * 1_000L,
            ),
        )
        YtMusicOAuthPollResult.Pending -> ProviderDeviceAuthorizationPollResult.Pending
        YtMusicOAuthPollResult.SlowDown -> ProviderDeviceAuthorizationPollResult.SlowDown
        is YtMusicOAuthPollResult.Denied -> ProviderDeviceAuthorizationPollResult.Denied(result.message)
    }

    override suspend fun loginWithOAuthJson(
        oauthJson: String,
        clientId: String,
        clientSecret: String,
    ) = oauth.loginWithOAuthJson(oauthJson, clientId, clientSecret)

    private fun bestMatch(keyword: String, results: ProviderSearchResults): ProviderSearchHit? {
        val query = normalize(keyword)
        if (query.isBlank()) return null
        return buildList {
            results.artists.take(5).forEachIndexed { index, item ->
                add(Candidate(ProviderSearchHit.Artist(item), item.title, index, 5))
            }
            results.albums.take(5).forEachIndexed { index, item ->
                add(Candidate(ProviderSearchHit.Album(item), item.title, index, 4))
            }
            results.tracks.take(5).forEachIndexed { index, item ->
                val extra = if (normalize(item.artists) == query) 250 else 0
                add(Candidate(ProviderSearchHit.Track(item), item.title, index, 3, extra))
            }
            results.playlists.take(5).forEachIndexed { index, item ->
                add(Candidate(ProviderSearchHit.Playlist(item), item.title, index, 2))
            }
            results.videos.take(5).forEachIndexed { index, item ->
                add(Candidate(ProviderSearchHit.Video(item), item.title, index, 1))
            }
        }.maxByOrNull { candidate ->
            val title = normalize(candidate.title)
            val relevance = when {
                title == query -> 1_000
                title.startsWith(query) -> 600
                title.contains(query) -> 350
                else -> 0
            }
            relevance + candidate.extraScore + candidate.typePriority * 10 - candidate.index
        }?.hit
    }

    private fun normalize(value: String): String = value.trim().lowercase().replace(" ", "")

    private data class Candidate(
        val hit: ProviderSearchHit,
        val title: String,
        val index: Int,
        val typePriority: Int,
        val extraScore: Int = 0,
    )
}
