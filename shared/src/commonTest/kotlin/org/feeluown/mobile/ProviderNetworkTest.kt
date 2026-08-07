package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.feeluown.mobile.provider.core.network.CacheFreshness
import org.feeluown.mobile.provider.core.network.PersistedProviderCacheEntry
import org.feeluown.mobile.provider.core.network.ProviderCachePolicy
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderNetworkException
import org.feeluown.mobile.provider.core.network.ProviderRequestKind
import org.feeluown.mobile.provider.core.network.ProviderRetryPolicy
import org.feeluown.mobile.provider.core.network.ProviderPersistentCache

class ProviderNetworkTest {
    @Test
    fun safeReadsRetryTransientResponses() = runTest {
        var calls = 0
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    calls += 1
                    if (calls < 3) {
                        respondError(HttpStatusCode.ServiceUnavailable)
                    } else {
                        respond("ok")
                    }
                }
            }
        }
        val client = ProviderHttpClient(
            httpClient = httpClient,
            retryPolicy = ProviderRetryPolicy(maxRetries = 2, baseDelayMillis = 0, maxDelayMillis = 0),
            random = { 0.0 },
        )

        val result = client.getText("test", "https://example.test/resource")

        assertEquals("ok", result.value)
        assertEquals(CacheFreshness.Network, result.freshness)
        assertEquals(3, calls)
        client.close()
    }

    @Test
    fun httpErrorsPreferJsonMessageFromPrettyPrintedBody() = runTest {
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = """
                            {
                              "error": {
                                "code": 400,
                                "message": "Request contains an invalid argument."
                              }
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.BadRequest,
                    )
                }
            }
        }
        val client = ProviderHttpClient(
            httpClient = httpClient,
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
        )

        val error = assertFailsWith<ProviderNetworkException.Http> {
            client.getText("test", "https://example.test/pretty")
        }

        assertEquals(
            "provider request failed with HTTP 400: Request contains an invalid argument.",
            error.message,
        )
        client.close()
    }

    @Test
    fun mutationsAreNeverRetried() = runTest {
        var calls = 0
        val httpClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    calls += 1
                    respondError(HttpStatusCode.ServiceUnavailable)
                }
            }
        }
        val client = ProviderHttpClient(
            httpClient = httpClient,
            retryPolicy = ProviderRetryPolicy(maxRetries = 3, baseDelayMillis = 0, maxDelayMillis = 0),
        )

        assertFailsWith<ProviderNetworkException.Http> {
            client.getText(
                providerId = "test",
                url = "https://example.test/mutation",
                kind = ProviderRequestKind.Mutation,
            )
        }

        assertEquals(1, calls)
        client.close()
    }

    @Test
    fun persistentCacheServesFreshAndStaleValues() = runTest {
        var now = 1_000L
        val persistentCache = FakePersistentCache()
        var firstCalls = 0
        val firstClient = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        firstCalls += 1
                        respond("network-value")
                    }
                }
            },
            persistentCache = persistentCache,
            nowMillis = { now },
        )
        val policy = ProviderCachePolicy(ttlMillis = 10_000, staleMillis = 100_000)

        firstClient.getText(
            providerId = "test",
            url = "https://example.test/cache",
            cacheKey = "test:cache",
            cachePolicy = policy,
        )
        firstClient.close()

        var secondCalls = 0
        val secondClient = ProviderHttpClient(
            httpClient = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        secondCalls += 1
                        respondError(HttpStatusCode.BadGateway)
                    }
                }
            },
            persistentCache = persistentCache,
            retryPolicy = ProviderRetryPolicy(maxRetries = 0),
            nowMillis = { now },
        )

        val fresh = secondClient.getText(
            providerId = "test",
            url = "https://example.test/cache",
            cacheKey = "test:cache",
            cachePolicy = policy,
        )
        assertEquals("network-value", fresh.value)
        assertEquals(CacheFreshness.Fresh, fresh.freshness)
        assertEquals(0, secondCalls)

        now = 50_000L
        val stale = secondClient.getText(
            providerId = "test",
            url = "https://example.test/cache",
            cacheKey = "test:cache",
            cachePolicy = policy,
        )
        assertEquals("network-value", stale.value)
        assertEquals(CacheFreshness.Stale, stale.freshness)
        assertEquals(1, secondCalls)
        assertEquals(1, firstCalls)

        secondClient.close()
    }

    private class FakePersistentCache : ProviderPersistentCache {
        private val values = mutableMapOf<String, PersistedProviderCacheEntry>()

        override suspend fun read(key: String): PersistedProviderCacheEntry? = values[key]

        override suspend fun write(key: String, entry: PersistedProviderCacheEntry) {
            values[key] = entry
        }

        override suspend fun invalidate(prefix: String) {
            values.keys.removeAll { it.startsWith(prefix) }
        }

        override suspend fun clear() = values.clear()
    }
}
