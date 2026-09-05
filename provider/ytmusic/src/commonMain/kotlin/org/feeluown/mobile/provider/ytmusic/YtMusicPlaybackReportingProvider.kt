package org.feeluown.mobile.provider.ytmusic

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.feeluown.mobile.ProviderPlaybackReport
import org.feeluown.mobile.ProviderPlaybackReportingCapability
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.cookieHeader
import org.feeluown.mobile.provider.core.md5Hex
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.splitResourceId
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderRequestKind
import org.feeluown.mobile.provider.core.network.currentTimeMillis

internal class YtMusicPlaybackReportingProvider(
    private val delegate: KotlinMusicProvider,
    private val http: ProviderHttpClient,
    private val credentials: ProviderCredentialStore,
) : KotlinMusicProvider by delegate, ProviderPlaybackReportingCapability {
    private val reportedMutex = Mutex()
    private val reportedSessions = mutableSetOf<String>()

    override suspend fun reportPlayback(report: ProviderPlaybackReport) {
        if (!report.qualified) return
        if (reportedMutex.withLock { report.sessionId in reportedSessions }) return
        val stored = credentials.read(ID) ?: return
        val auth = authenticatedRequest(stored) ?: return
        val videoId = splitResourceId(report.trackId).second
            .ifBlank { report.trackId.substringAfterLast(':') }
            .takeIf(String::isNotBlank)
            ?: return
        val body = buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", YtMusicProvider.dynamicClientVersion())
                    put("hl", "en")
                })
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }.toString()
        val playerUrl = buildString {
            append(YtMusicProvider.API_BASE)
            append("/player?alt=json")
            if (!auth.oauth) {
                append("&key=")
                append(YtMusicProvider.FALLBACK_API_KEY)
            }
        }
        val player = http.postJson(
            providerId = ID,
            url = playerUrl,
            json = body,
            headers = auth.headers,
            kind = ProviderRequestKind.Auth,
        ).value.let { providerJson.parseToJsonElement(it) }
        val trackingUrl = (player as? kotlinx.serialization.json.JsonObject)
            ?.obj("playbackTracking")
            ?.obj("videostatsPlaybackUrl")
            ?.stringOrNull("baseUrl")
            ?: return
        val separator = if ('?' in trackingUrl) '&' else '?'
        val cpn = md5Hex(report.sessionId).take(16)
        http.getText(
            providerId = ID,
            url = "$trackingUrl${separator}ver=2&c=WEB_REMIX&cpn=$cpn",
            headers = auth.headers,
            kind = ProviderRequestKind.Auth,
            cacheKey = null,
        )
        reportedMutex.withLock { reportedSessions += report.sessionId }
    }

    private data class AuthenticatedRequest(
        val headers: Map<String, String>,
        val oauth: Boolean,
    )

    private suspend fun authenticatedRequest(
        stored: org.feeluown.mobile.provider.core.ProviderCredentials,
    ): AuthenticatedRequest? {
        if (stored.hasOAuthAccess()) {
            val token = validOAuthToken(stored) ?: return null
            return AuthenticatedRequest(
                headers = mapOf(
                    "User-Agent" to YtMusicOAuth.USER_AGENT,
                    "Accept" to "*/*",
                    "Origin" to YtMusicProvider.YTM_ORIGIN,
                    "Referer" to "${YtMusicProvider.YTM_ORIGIN}/",
                    "Authorization" to token.asAuthorizationHeader(),
                    "X-Goog-Request-Time" to (currentTimeMillis() / 1_000L).toString(),
                ),
                oauth = true,
            )
        }
        val cookie = cookieHeader(stored)
        if (cookie.isBlank()) return null
        val headers = mutableMapOf(
            "User-Agent" to YtMusicOAuth.USER_AGENT,
            "Accept" to "*/*",
            "Origin" to YtMusicProvider.YTM_ORIGIN,
            "Referer" to "${YtMusicProvider.YTM_ORIGIN}/",
            "Cookie" to cookie,
        )
        val sapisid = YtMusicProvider.sapisidFromCookie(cookie)
        headers["Authorization"] = when {
            !sapisid.isNullOrBlank() -> YtMusicProvider.sapisidHashAuthorization(sapisid, YtMusicProvider.YTM_ORIGIN)
            !stored.authorization.isNullOrBlank() -> stored.authorization
            else -> return null
        }
        return AuthenticatedRequest(headers = headers, oauth = false)
    }

    private suspend fun validOAuthToken(
        stored: org.feeluown.mobile.provider.core.ProviderCredentials,
    ): YtMusicOAuthToken? {
        val clientId = stored.oauthClientId.orEmpty()
        val clientSecret = stored.oauthClientSecret.orEmpty()
        if (clientId.isBlank() || clientSecret.isBlank()) return null
        var token = YtMusicOAuthToken(
            accessToken = stored.oauthAccessToken.orEmpty(),
            refreshToken = stored.oauthRefreshToken.orEmpty(),
            scope = stored.oauthScope ?: YtMusicOAuth.SCOPE,
            tokenType = "Bearer",
            expiresAtEpochSeconds = (stored.oauthExpiresAtMillis ?: 0L) / 1_000L,
            expiresInSeconds = 0,
        )
        if (token.accessToken.isNotBlank() && !token.isExpiring()) return token
        if (token.refreshToken.isBlank()) return null
        token = YtMusicOAuthClient(
            http = http,
            credentials = YtMusicOAuthClientCredentials(clientId, clientSecret),
        ).refreshAccessToken(token.refreshToken)
        credentials.write(
            ID,
            stored.copy(
                oauthAccessToken = token.accessToken,
                oauthRefreshToken = token.refreshToken,
                oauthExpiresAtMillis = token.expiresAtEpochSeconds * 1_000L,
                oauthScope = token.scope,
            ),
        )
        return token
    }

    private companion object {
        const val ID = "ytmusic"
    }
}
