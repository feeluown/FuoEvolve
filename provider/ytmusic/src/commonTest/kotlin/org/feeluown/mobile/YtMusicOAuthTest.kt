package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderRetryPolicy
import org.feeluown.mobile.provider.ytmusic.YtMusicOAuth
import org.feeluown.mobile.provider.ytmusic.YtMusicOAuthClient
import org.feeluown.mobile.provider.ytmusic.YtMusicOAuthClientCredentials
import org.feeluown.mobile.provider.ytmusic.YtMusicOAuthException
import org.feeluown.mobile.provider.ytmusic.YtMusicOAuthPollResult
import org.feeluown.mobile.provider.ytmusic.YtMusicDeviceAuthCode
import org.feeluown.mobile.provider.ytmusic.YtMusicOAuthToken

class YtMusicOAuthTest {
    @Test
    fun requestDeviceCodePostsClientIdAndScope() = runTest {
        val bodies = mutableListOf<String>()
        val http = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        bodies += (request.body as? TextContent)?.text.orEmpty()
                        assertTrue(request.url.toString().contains("youtube.com/o/oauth2/device/code"))
                        respond(
                            """{"device_code":"dev","user_code":"ABCD-EFGH","verification_url":"https://www.google.com/device","expires_in":1800,"interval":5}""",
                        )
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val client = YtMusicOAuthClient(http, YtMusicOAuthClientCredentials("cid", "secret"))

        val code = client.requestDeviceCode()

        assertEquals("dev", code.deviceCode)
        assertEquals("ABCD-EFGH", code.userCode)
        assertEquals("https://www.google.com/device?user_code=ABCD-EFGH", code.verificationUrlWithCode)
        assertTrue(bodies.single().contains("client_id=cid"))
        assertTrue(bodies.single().contains("scope="))
        http.close()
    }

    @Test
    fun requestDeviceCodeAppliesSafeDefaults() = runTest {
        val http = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond("""{"device_code":"dev","user_code":"ABCD","interval":0}""")
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val client = YtMusicOAuthClient(http, YtMusicOAuthClientCredentials("cid", "secret"))

        val code = client.requestDeviceCode()

        assertEquals(YtMusicOAuth.DEFAULT_VERIFICATION_URL, code.verificationUrl)
        assertEquals("${YtMusicOAuth.DEFAULT_VERIFICATION_URL}?user_code=ABCD", code.verificationUrlWithCode)
        assertEquals(1_800, code.expiresInSeconds)
        assertEquals(1, code.intervalSeconds)
        http.close()
    }

    @Test
    fun exchangeDeviceCodeReturnsAuthorizedToken() = runTest {
        val http = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            """{"access_token":"access","refresh_token":"refresh","scope":"${YtMusicOAuth.SCOPE}","token_type":"Bearer","expires_in":3600}""",
                        )
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val client = YtMusicOAuthClient(http, YtMusicOAuthClientCredentials("cid", "secret"))

        val result = client.exchangeDeviceCode("device")

        val authorized = assertIs<YtMusicOAuthPollResult.Authorized>(result)
        assertEquals("access", authorized.token.accessToken)
        assertEquals("refresh", authorized.token.refreshToken)
        assertEquals("Bearer access", authorized.token.asAuthorizationHeader())
        http.close()
    }

    @Test
    fun exchangeDeviceCodeMapsAuthorizationPending() = runTest {
        val http = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            """{"error":"authorization_pending"}""",
                            status = HttpStatusCode.BadRequest,
                        )
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val client = YtMusicOAuthClient(http, YtMusicOAuthClientCredentials("cid", "secret"))

        assertEquals(YtMusicOAuthPollResult.Pending, client.exchangeDeviceCode("device"))
        http.close()
    }

    @Test
    fun exchangeDeviceCodeMapsSlowDownAndInvalidClient() = runTest {
        var requestCount = 0
        val http = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        requestCount += 1
                        val error = if (requestCount == 1) "slow_down" else "invalid_client"
                        respond("""{"error":"$error"}""", status = HttpStatusCode.BadRequest)
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val client = YtMusicOAuthClient(http, YtMusicOAuthClientCredentials("cid", "secret"))

        assertEquals(YtMusicOAuthPollResult.SlowDown, client.exchangeDeviceCode("device"))
        val denied = assertIs<YtMusicOAuthPollResult.Denied>(client.exchangeDeviceCode("device"))
        assertTrue(denied.message.contains("Invalid OAuth client"))
        http.close()
    }

    @Test
    fun refreshAccessTokenKeepsRefreshTokenWhenOmitted() = runTest {
        val http = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            """{"access_token":"fresh-access","token_type":"Bearer","expires_in":1800,"scope":"${YtMusicOAuth.SCOPE}"}""",
                        )
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val client = YtMusicOAuthClient(http, YtMusicOAuthClientCredentials("cid", "secret"))

        val token = client.refreshAccessToken("refresh-keep")

        assertEquals("fresh-access", token.accessToken)
        assertEquals("refresh-keep", token.refreshToken)
        http.close()
    }

    @Test
    fun refreshAccessTokenSurfacesDeniedResponseAndRejectsBlankToken() = runTest {
        val http = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond("""{"error":"access_denied"}""", status = HttpStatusCode.BadRequest)
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val client = YtMusicOAuthClient(http, YtMusicOAuthClientCredentials("cid", "secret"))

        assertFailsWith<IllegalArgumentException> { client.refreshAccessToken(" ") }
        val exception = assertFailsWith<YtMusicOAuthException> {
            client.refreshAccessToken("refresh")
        }
        assertEquals("User denied OAuth access", exception.message)
        assertTrue(exception.cause != null)
        http.close()
    }

    @Test
    fun cancelledDevicePollingFailsWithoutSendingRequest() = runTest {
        val http = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler { error("cancelled polling must not send a request") }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val client = YtMusicOAuthClient(http, YtMusicOAuthClientCredentials("cid", "secret"))

        val exception = assertFailsWith<YtMusicOAuthException> {
            client.pollUntilAuthorized("device", intervalSeconds = 1, expiresInSeconds = 30) { false }
        }

        assertTrue(exception.message.orEmpty().contains("expired or cancelled"))
        http.close()
    }

    @Test
    fun parseOauthJsonReadsYtmusicapiFile() {
        val token = YtMusicOAuth.parseOauthJson(
            """
            {
              "scope": "${YtMusicOAuth.SCOPE}",
              "token_type": "Bearer",
              "access_token": "a",
              "refresh_token": "r",
              "expires_at": 1700000000,
              "expires_in": 3600
            }
            """.trimIndent(),
        )
        assertEquals("a", token.accessToken)
        assertEquals("r", token.refreshToken)
        assertEquals(1_700_000_000L, token.expiresAtEpochSeconds)
    }

    @Test
    fun parseClientSecretJsonReadsInstalledDownload() {
        val credentials = YtMusicOAuth.parseClientSecretJson(
            """
            {
              "installed": {
                "client_id": "cid.apps.googleusercontent.com",
                "project_id": "demo",
                "auth_uri": "https://accounts.google.com/o/oauth2/auth",
                "token_uri": "https://oauth2.googleapis.com/token",
                "client_secret": "GOCSPX-demo"
              }
            }
            """.trimIndent(),
        )
        assertEquals("cid.apps.googleusercontent.com", credentials.clientId)
        assertEquals("GOCSPX-demo", credentials.clientSecret)
        assertTrue(YtMusicOAuth.looksLikeClientSecretJson("""{"installed":{"client_id":"a","client_secret":"b"}}"""))
        assertTrue(YtMusicOAuth.looksLikeOauthTokenJson("""{"access_token":"a","refresh_token":"r"}"""))
    }

    @Test
    fun parseClientSecretJsonReadsFlatObject() {
        val credentials = YtMusicOAuth.parseClientSecretJson(
            """{"client_id":"flat-id","client_secret":"flat-secret"}""",
        )
        assertEquals("flat-id", credentials.clientId)
        assertEquals("flat-secret", credentials.clientSecret)
    }

    @Test
    fun oauthJsonDetectionRejectsMalformedAndAmbiguousPayloads() {
        assertFalse(YtMusicOAuth.looksLikeOauthTokenJson("not-json"))
        assertFalse(YtMusicOAuth.looksLikeOauthTokenJson("""{"access_token":"only-access"}"""))
        assertFalse(
            YtMusicOAuth.looksLikeClientSecretJson(
                """{"access_token":"a","refresh_token":"r","client_id":"cid","client_secret":"secret"}""",
            ),
        )
        assertFailsWith<YtMusicOAuthException> { YtMusicOAuth.parseOauthJson("[]") }
        assertFailsWith<YtMusicOAuthException> {
            YtMusicOAuth.parseClientSecretJson("""{"installed":{"client_id":"cid"}}""")
        }
    }

    @Test
    fun oauthValueObjectsKeepExistingCodeAndRefreshExpiry() {
        val code = YtMusicDeviceAuthCode(
            deviceCode = "device",
            userCode = "ABCD",
            verificationUrl = "https://example.com/activate?user_code=ABCD",
            expiresInSeconds = 1_800,
            intervalSeconds = 5,
        )
        val token = YtMusicOAuthToken(
            accessToken = "old-access",
            refreshToken = "refresh",
            expiresAtEpochSeconds = 120,
            expiresInSeconds = 120,
        )

        assertEquals(code.verificationUrl, code.verificationUrlWithCode)
        assertFalse(token.isExpiring(nowMillis = 0))
        assertTrue(token.isExpiring(nowMillis = 61_000))

        val refreshed = token.withRefreshedAccess(
            accessToken = "new-access",
            expiresInSeconds = 3_600,
            nowMillis = 1_000,
        )
        assertEquals("new-access", refreshed.accessToken)
        assertEquals("refresh", refreshed.refreshToken)
        assertEquals(3_601, refreshed.expiresAtEpochSeconds)
        assertFailsWith<IllegalArgumentException> { YtMusicOAuthClientCredentials("", "secret") }
    }
}
