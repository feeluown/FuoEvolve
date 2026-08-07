package org.feeluown.mobile.provider.ytmusic

import io.ktor.http.Parameters
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderNetworkException
import org.feeluown.mobile.provider.core.network.ProviderRequestKind
import org.feeluown.mobile.provider.core.network.currentTimeMillis
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull

/**
 * Google TV / Limited Input device-code OAuth, matching ytmusicapi.
 *
 * Code URL uses YouTube's endpoint (not oauth2.googleapis.com/device/code).
 */
data class YtMusicOAuthClientCredentials(
    val clientId: String,
    val clientSecret: String,
) {
    init {
        require(clientId.isNotBlank() && clientSecret.isNotBlank()) {
            "client_id and client_secret are required"
        }
    }
}

data class YtMusicDeviceAuthCode(
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

data class YtMusicOAuthToken(
    val accessToken: String,
    val refreshToken: String,
    val scope: String = YtMusicOAuth.SCOPE,
    val tokenType: String = "Bearer",
    val expiresAtEpochSeconds: Long = 0,
    val expiresInSeconds: Int = 0,
) {
    fun asAuthorizationHeader(): String = "$tokenType $accessToken"

    fun isExpiring(nowMillis: Long = currentTimeMillis()): Boolean =
        expiresAtEpochSeconds - (nowMillis / 1_000) < 60

    fun withRefreshedAccess(accessToken: String, expiresInSeconds: Int, nowMillis: Long = currentTimeMillis()): YtMusicOAuthToken =
        copy(
            accessToken = accessToken,
            expiresInSeconds = expiresInSeconds,
            expiresAtEpochSeconds = (nowMillis / 1_000) + expiresInSeconds,
        )
}

sealed class YtMusicOAuthPollResult {
    data class Authorized(val token: YtMusicOAuthToken) : YtMusicOAuthPollResult()
    data object Pending : YtMusicOAuthPollResult()
    data object SlowDown : YtMusicOAuthPollResult()
    data class Denied(val message: String) : YtMusicOAuthPollResult()
}

class YtMusicOAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

class YtMusicOAuthClient(
    private val http: ProviderHttpClient,
    private val credentials: YtMusicOAuthClientCredentials,
) {
    suspend fun requestDeviceCode(): YtMusicDeviceAuthCode {
        val body = postForm(
            url = YtMusicOAuth.CODE_URL,
            form = Parameters.build {
                append("client_id", credentials.clientId)
                append("scope", YtMusicOAuth.SCOPE)
            },
        )
        val deviceCode = body.stringOrNull("device_code")
            ?: throw YtMusicOAuthException("device_code missing from OAuth response")
        val userCode = body.stringOrNull("user_code")
            ?: throw YtMusicOAuthException("user_code missing from OAuth response")
        val verificationUrl = body.stringOrNull("verification_url")
            ?: YtMusicOAuth.DEFAULT_VERIFICATION_URL
        return YtMusicDeviceAuthCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUrl = verificationUrl,
            expiresInSeconds = body.int("expires_in") ?: 1_800,
            intervalSeconds = (body.int("interval") ?: 5).coerceAtLeast(1),
        )
    }

    suspend fun exchangeDeviceCode(deviceCode: String): YtMusicOAuthPollResult {
        return try {
            val body = postForm(
                url = YtMusicOAuth.TOKEN_URL,
                form = Parameters.build {
                    append("client_id", credentials.clientId)
                    append("client_secret", credentials.clientSecret)
                    append("code", deviceCode)
                    append("grant_type", YtMusicOAuth.DEVICE_GRANT_TYPE)
                },
            )
            YtMusicOAuthPollResult.Authorized(parseTokenResponse(body, requireRefreshToken = true))
        } catch (exception: ProviderNetworkException.Http) {
            mapTokenError(exception)
        }
    }

    suspend fun pollUntilAuthorized(
        deviceCode: String,
        intervalSeconds: Int,
        expiresInSeconds: Int,
        isActive: () -> Boolean = { true },
    ): YtMusicOAuthToken {
        val deadlineMillis = currentTimeMillis() + expiresInSeconds.coerceAtLeast(1) * 1_000L
        var interval = intervalSeconds.coerceAtLeast(1)
        while (isActive() && currentTimeMillis() < deadlineMillis) {
            delay(interval * 1_000L)
            if (!isActive()) break
            when (val result = exchangeDeviceCode(deviceCode)) {
                is YtMusicOAuthPollResult.Authorized -> return result.token
                YtMusicOAuthPollResult.Pending -> Unit
                YtMusicOAuthPollResult.SlowDown -> interval += 5
                is YtMusicOAuthPollResult.Denied -> throw YtMusicOAuthException(result.message)
            }
        }
        throw YtMusicOAuthException("OAuth device code expired or cancelled")
    }

    suspend fun refreshAccessToken(refreshToken: String): YtMusicOAuthToken {
        require(refreshToken.isNotBlank()) { "refresh_token is required" }
        return try {
            val body = postForm(
                url = YtMusicOAuth.TOKEN_URL,
                form = Parameters.build {
                    append("client_id", credentials.clientId)
                    append("client_secret", credentials.clientSecret)
                    append("refresh_token", refreshToken)
                    append("grant_type", "refresh_token")
                },
            )
            parseTokenResponse(body, requireRefreshToken = false)
                .let { fresh ->
                    if (fresh.refreshToken.isBlank()) {
                        fresh.copy(refreshToken = refreshToken)
                    } else {
                        fresh
                    }
                }
        } catch (exception: ProviderNetworkException.Http) {
            when (val mapped = mapTokenError(exception)) {
                is YtMusicOAuthPollResult.Denied -> throw YtMusicOAuthException(mapped.message, exception)
                else -> throw YtMusicOAuthException(
                    "OAuth refresh failed (HTTP ${exception.statusCode})",
                    exception,
                )
            }
        }
    }

    private suspend fun postForm(url: String, form: Parameters): JsonObject {
        val response = http.postForm(
            providerId = YtMusicProvider.ID,
            url = url,
            form = form,
            headers = mapOf("User-Agent" to YtMusicOAuth.USER_AGENT),
            kind = ProviderRequestKind.Auth,
        ).value
        return providerJson.parseToJsonElement(response).let { element ->
            element as? JsonObject
                ?: throw YtMusicOAuthException("OAuth response is not a JSON object")
        }
    }

    private fun parseTokenResponse(body: JsonObject, requireRefreshToken: Boolean): YtMusicOAuthToken {
        val accessToken = body.stringOrNull("access_token")
            ?: throw YtMusicOAuthException("access_token missing from OAuth response")
        val refreshToken = body.stringOrNull("refresh_token").orEmpty()
        if (requireRefreshToken && refreshToken.isBlank()) {
            throw YtMusicOAuthException("refresh_token missing from OAuth response")
        }
        val expiresIn = body.int("expires_in") ?: 3_600
        val expiresAt = body.long("expires_at")
            ?: ((currentTimeMillis() / 1_000) + expiresIn)
        return YtMusicOAuthToken(
            accessToken = accessToken,
            refreshToken = refreshToken,
            scope = body.stringOrNull("scope") ?: YtMusicOAuth.SCOPE,
            tokenType = body.stringOrNull("token_type") ?: "Bearer",
            expiresAtEpochSeconds = expiresAt,
            expiresInSeconds = expiresIn,
        )
    }

    private fun mapTokenError(exception: ProviderNetworkException.Http): YtMusicOAuthPollResult {
        val error = runCatching {
            providerJson.parseToJsonElement(exception.responseBody) as? JsonObject
        }.getOrNull()?.stringOrNull("error").orEmpty()
        return when (error) {
            "authorization_pending" -> YtMusicOAuthPollResult.Pending
            "slow_down" -> YtMusicOAuthPollResult.SlowDown
            "access_denied" -> YtMusicOAuthPollResult.Denied("User denied OAuth access")
            "expired_token" -> YtMusicOAuthPollResult.Denied("OAuth device code expired")
            "invalid_client" -> YtMusicOAuthPollResult.Denied(
                "Invalid OAuth client. Check client_id/client_secret and YouTube Data API enablement.",
            )
            "unauthorized_client" -> YtMusicOAuthPollResult.Denied(
                "Unauthorized OAuth client. Token/client mismatch.",
            )
            else -> YtMusicOAuthPollResult.Denied(
                error.ifBlank { "OAuth token request failed (HTTP ${exception.statusCode})" },
            )
        }
    }
}

object YtMusicOAuth {
    const val SCOPE = "https://www.googleapis.com/auth/youtube"
    const val CODE_URL = "https://www.youtube.com/o/oauth2/device/code"
    const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    const val DEVICE_GRANT_TYPE = "http://oauth.net/grant_type/device/1.0"
    const val DEFAULT_VERIFICATION_URL = "https://www.google.com/device"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:88.0) Gecko/20100101 Firefox/88.0 Cobalt/Version"

    fun parseOauthJson(json: String): YtMusicOAuthToken {
        val body = providerJson.parseToJsonElement(json) as? JsonObject
            ?: throw YtMusicOAuthException("oauth.json must be a JSON object")
        val accessToken = body.stringOrNull("access_token")
            ?: throw YtMusicOAuthException("oauth.json missing access_token")
        val refreshToken = body.stringOrNull("refresh_token")
            ?: throw YtMusicOAuthException("oauth.json missing refresh_token")
        val expiresIn = body.int("expires_in") ?: 3_600
        val expiresAt = body.long("expires_at")
            ?: ((currentTimeMillis() / 1_000) + expiresIn)
        return YtMusicOAuthToken(
            accessToken = accessToken,
            refreshToken = refreshToken,
            scope = body.stringOrNull("scope") ?: SCOPE,
            tokenType = body.stringOrNull("token_type") ?: "Bearer",
            expiresAtEpochSeconds = expiresAt,
            expiresInSeconds = expiresIn,
        )
    }

    fun looksLikeOauthTokenJson(json: String): Boolean {
        val body = runCatching { providerJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return false
        return !body.stringOrNull("access_token").isNullOrBlank() &&
            !body.stringOrNull("refresh_token").isNullOrBlank()
    }

    fun looksLikeClientSecretJson(json: String): Boolean {
        val body = runCatching { providerJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return false
        if (looksLikeOauthTokenJson(json)) return false
        val nested = body.obj("installed") ?: body.obj("web") ?: body
        return !nested.stringOrNull("client_id").isNullOrBlank() &&
            !nested.stringOrNull("client_secret").isNullOrBlank()
    }

    /**
     * Parses Google Cloud Console client secret downloads, e.g.
     * `{"installed":{"client_id":"...","client_secret":"..."}}` or `{"web":{...}}`,
     * as well as a flat `{"client_id":"...","client_secret":"..."}` object.
     */
    fun parseClientSecretJson(json: String): YtMusicOAuthClientCredentials {
        val body = providerJson.parseToJsonElement(json) as? JsonObject
            ?: throw YtMusicOAuthException("client_secret.json must be a JSON object")
        val nested = body.obj("installed")
            ?: body.obj("web")
            ?: body
        val clientId = nested.stringOrNull("client_id")
            ?: throw YtMusicOAuthException("client_secret.json missing client_id")
        val clientSecret = nested.stringOrNull("client_secret")
            ?: throw YtMusicOAuthException("client_secret.json missing client_secret")
        return YtMusicOAuthClientCredentials(clientId = clientId, clientSecret = clientSecret)
    }
}
