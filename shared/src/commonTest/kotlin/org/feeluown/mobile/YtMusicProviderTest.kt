package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentials
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderRetryPolicy
import org.feeluown.mobile.provider.ytmusic.YtMusicProvider

class YtMusicProviderTest {
    @Test
    fun searchFallsBackWhenLandingPageDoesNotExposeApiKey() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val providerHttp = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        requests += capture(request)
                        if (request.method == HttpMethod.Get) {
                            respond("<html>YouTube Music is not available in your area</html>")
                        } else {
                            respond("{}")
                        }
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val provider = YtMusicProvider(providerHttp, InMemoryProviderCredentialStore())

        assertEquals(emptyList(), provider.search("test").tracks)

        val apiRequest = requests.first { it.url.contains("/youtubei/v1/search") }
        assertTrue(apiRequest.url.contains("alt=json"))
        assertTrue(apiRequest.url.contains("key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"))
        assertFalse(apiRequest.body.contains("\"gl\""), apiRequest.body)
        assertTrue(apiRequest.body.contains("\"hl\":\"zh_CN\""), apiRequest.body)
        assertTrue(apiRequest.body.contains("\"user\":{}"), apiRequest.body)
        providerHttp.close()
    }

    @Test
    fun oauthRequestsOmitInnerTubeApiKey() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val store = InMemoryProviderCredentialStore()
        store.write(
            "ytmusic",
            ProviderCredentials(oauthAccessToken = "ya29.oauth-token"),
        )
        val providerHttp = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        requests += capture(request)
                        if (request.method == HttpMethod.Get) {
                            respond("<html></html>")
                        } else {
                            respond("{}")
                        }
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val provider = YtMusicProvider(providerHttp, store)

        provider.search("oauth")

        val apiRequest = requests.first { it.url.contains("/youtubei/v1/search") }
        assertTrue(apiRequest.url.contains("alt=json"))
        assertFalse(apiRequest.url.contains("key="), apiRequest.url)
        assertEquals("Bearer ya29.oauth-token", apiRequest.headers["Authorization"])
        assertTrue(apiRequest.headers.containsKey("X-Goog-Request-Time"))
        providerHttp.close()
    }

    @Test
    fun browserAuthRefreshesSapisidHash() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val store = InMemoryProviderCredentialStore()
        store.write(
            "ytmusic",
            ProviderCredentials(
                authorization = "SAPISIDHASH stale_token",
                cookieHeader = "SID=abc; __Secure-3PAPISID=sapisid-secret",
            ),
        )
        val providerHttp = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        requests += capture(request)
                        if (request.method == HttpMethod.Get) {
                            respond(
                                """<html>ytcfg.set({"INNERTUBE_API_KEY":"AIzaSyTestKey","INNERTUBE_CLIENT_VERSION":"1.20260807.01.00","VISITOR_DATA":"visitor-token"});</html>""",
                            )
                        } else {
                            respond("{}")
                        }
                    }
                }
            },
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val provider = YtMusicProvider(providerHttp, store)

        provider.search("browser")

        val apiRequest = requests.first { it.url.contains("/youtubei/v1/search") }
        assertTrue(apiRequest.url.contains("key=AIzaSyTestKey"))
        assertEquals("visitor-token", apiRequest.headers["X-Goog-Visitor-Id"])
        val authorization = apiRequest.headers["Authorization"].orEmpty()
        assertTrue(authorization.startsWith("SAPISIDHASH "), authorization)
        assertFalse(authorization.contains("stale_token"), authorization)
        providerHttp.close()
    }

    @Test
    fun sapisidHashMatchesYtmusicapiAlgorithm() {
        assertEquals(
            "SAPISIDHASH 1234567890_79e414afaea32d1087783097cc075f75a96dc46c",
            YtMusicProvider.sapisidHashAuthorization(
                sapisid = "sapisid",
                origin = "https://music.youtube.com",
                nowMillis = 1_234_567_890_000L,
            ),
        )
    }

    private data class CapturedRequest(
        val method: HttpMethod,
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun capture(request: io.ktor.client.request.HttpRequestData): CapturedRequest = CapturedRequest(
        method = request.method,
        url = request.url.toString(),
        headers = request.headers.entries().associate { it.key to it.value.joinToString(",") },
        body = (request.body as? TextContent)?.text.orEmpty(),
    )
}
