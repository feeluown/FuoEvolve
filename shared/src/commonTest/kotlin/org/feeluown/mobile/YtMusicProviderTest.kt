package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderRetryPolicy
import org.feeluown.mobile.provider.ytmusic.YtMusicProvider

class YtMusicProviderTest {
    @Test
    fun searchFallsBackWhenLandingPageDoesNotExposeApiKey() = runTest {
        val requests = mutableListOf<String>()
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requests += request.url.toString()
                    if (request.method == HttpMethod.Get) {
                        respond("<html>YouTube Music is not available in your area</html>")
                    } else {
                        respond("{}")
                    }
                }
            }
        }
        val providerHttp = ProviderHttpClient(
            httpClient = httpClient,
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )
        val provider = YtMusicProvider(providerHttp, InMemoryProviderCredentialStore())

        assertEquals(emptyList(), provider.search("test").tracks)

        val apiRequest = requests.first { it.contains("/youtubei/v1/search") }
        assertTrue(apiRequest.contains("key=AIzaSyC9XL3ZjWddxYq6X74dJoCTL-WEYFDNX30"))
        providerHttp.close()
    }
}
