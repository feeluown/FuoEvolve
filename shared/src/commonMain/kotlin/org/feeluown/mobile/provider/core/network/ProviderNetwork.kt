package org.feeluown.mobile.provider.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.HttpClientConfig
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.random.Random

enum class ProviderRequestKind {
    SafeRead,
    Auth,
    Mutation,
    Media,
}

data class ProviderRetryPolicy(
    val maxRetries: Int = 2,
    val baseDelayMillis: Long = 250,
    val maxDelayMillis: Long = 4_000,
)

data class ProviderCachePolicy(
    val ttlMillis: Long,
    val staleMillis: Long = 0,
)

object ProviderCachePolicies {
    val none = ProviderCachePolicy(ttlMillis = 0)
    val search = ProviderCachePolicy(ttlMillis = 2 * 60 * 1_000)
    val detail = ProviderCachePolicy(ttlMillis = 10 * 60 * 1_000)
    val recommendation = ProviderCachePolicy(ttlMillis = 5 * 60 * 1_000)
    val lyric = ProviderCachePolicy(ttlMillis = 24 * 60 * 60 * 1_000, staleMillis = 24 * 60 * 60 * 1_000)
}

enum class CacheFreshness {
    Network,
    Fresh,
    Stale,
}

data class CachedText(
    val value: String,
    val freshness: CacheFreshness,
    val storedAtMillis: Long,
)

@Serializable
data class PersistedProviderCacheEntry(
    val value: String,
    val storedAtMillis: Long,
)

interface ProviderPersistentCache {
    suspend fun read(key: String): PersistedProviderCacheEntry?

    suspend fun write(key: String, entry: PersistedProviderCacheEntry)

    suspend fun invalidate(prefix: String)

    suspend fun clear()
}

sealed class ProviderNetworkException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Http(
        val statusCode: Int,
        val responseBody: String,
        val retryAfterMillis: Long? = null,
    ) : ProviderNetworkException(httpFailureMessage(statusCode, responseBody))

    class Transport(cause: Throwable) : ProviderNetworkException("provider request transport failure", cause)

    class Timeout(cause: Throwable) : ProviderNetworkException("provider request timed out", cause)
}

private fun httpFailureMessage(statusCode: Int, responseBody: String): String {
    val trimmed = responseBody.trim()
    val detail = if (trimmed.isEmpty()) {
        null
    } else {
        // Prefer JSON error.message when present (YouTube InnerTube, Google OAuth, etc.).
        // Search the whole body: pretty-printed responses often start with "{" alone.
        val messageMatch = Regex(""""message"\s*:\s*"((?:\\.|[^"\\])*)"""").find(trimmed)
        val message = messageMatch?.groupValues?.getOrNull(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\n", " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        message ?: trimmed.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.take(160)
    }
    return if (detail.isNullOrBlank()) {
        "provider request failed with HTTP $statusCode"
    } else {
        "provider request failed with HTTP $statusCode: $detail"
    }
}

private data class CacheRecord(
    val value: String,
    val storedAtMillis: Long,
)

class ProviderResponseCache(
    private val nowMillis: () -> Long = { currentTimeMillis() },
) {
    private val mutex = Mutex()
    private val records = LinkedHashMap<String, CacheRecord>()

    suspend fun get(key: String, policy: ProviderCachePolicy): CachedText? {
        if (policy.ttlMillis <= 0) return null
        val record = mutex.withLock { records[key] } ?: return null
        val age = (nowMillis() - record.storedAtMillis).coerceAtLeast(0)
        return when {
            age <= policy.ttlMillis -> CachedText(record.value, CacheFreshness.Fresh, record.storedAtMillis)
            age <= policy.ttlMillis + policy.staleMillis -> CachedText(record.value, CacheFreshness.Stale, record.storedAtMillis)
            else -> {
                mutex.withLock { records.remove(key) }
                null
            }
        }
    }

    suspend fun put(key: String, value: String) {
        mutex.withLock {
            records.remove(key)
            records[key] = CacheRecord(value, nowMillis())
            while (records.size > 256) records.remove(records.keys.first())
        }
    }

    suspend fun invalidate(prefix: String) {
        mutex.withLock {
            records.keys.removeAll { it.startsWith(prefix) }
        }
    }

    suspend fun clear() = mutex.withLock { records.clear() }
}

class ProviderHttpClient(
    private val httpClient: HttpClient = createProviderHttpClient(),
    private val cache: ProviderResponseCache = ProviderResponseCache(),
    private val persistentCache: ProviderPersistentCache? = null,
    private val retryPolicy: ProviderRetryPolicy = ProviderRetryPolicy(),
    private val nowMillis: () -> Long = { currentTimeMillis() },
    private val random: () -> Double = { Random.nextDouble() },
) {
    suspend fun getText(
        providerId: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        kind: ProviderRequestKind = ProviderRequestKind.SafeRead,
        cacheKey: String? = null,
        cachePolicy: ProviderCachePolicy = ProviderCachePolicies.none,
    ): CachedText {
        return execute(
            providerId = providerId,
            method = HttpMethod.Get,
            url = url,
            headers = headers,
            kind = kind,
            body = null,
            cacheKey = cacheKey,
            cachePolicy = cachePolicy,
        )
    }

    suspend fun postForm(
        providerId: String,
        url: String,
        form: Parameters,
        headers: Map<String, String> = emptyMap(),
        kind: ProviderRequestKind = ProviderRequestKind.SafeRead,
        cacheKey: String? = null,
        cachePolicy: ProviderCachePolicy = ProviderCachePolicies.none,
    ): CachedText {
        return execute(
            providerId = providerId,
            method = HttpMethod.Post,
            url = url,
            headers = headers,
            kind = kind,
            body = TextContent(form.formUrlEncode(), ContentType.Application.FormUrlEncoded),
            cacheKey = cacheKey,
            cachePolicy = cachePolicy,
        )
    }

    suspend fun postJson(
        providerId: String,
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        kind: ProviderRequestKind = ProviderRequestKind.SafeRead,
        cacheKey: String? = null,
        cachePolicy: ProviderCachePolicy = ProviderCachePolicies.none,
    ): CachedText {
        return execute(
            providerId = providerId,
            method = HttpMethod.Post,
            url = url,
            headers = headers,
            kind = kind,
            body = TextContent(json, ContentType.Application.Json),
            cacheKey = cacheKey,
            cachePolicy = cachePolicy,
        )
    }

    suspend fun invalidateCache(prefix: String) {
        cache.invalidate(prefix)
        persistentCache?.invalidate(prefix)
    }

    fun close() = httpClient.close()

    private suspend fun execute(
        providerId: String,
        method: HttpMethod,
        url: String,
        headers: Map<String, String>,
        kind: ProviderRequestKind,
        body: Any?,
        cacheKey: String?,
        cachePolicy: ProviderCachePolicy,
    ): CachedText {
        val effectiveCacheKey = cacheKey?.takeIf {
            cachePolicy.ttlMillis > 0 &&
                kind == ProviderRequestKind.SafeRead &&
                headers.keys.none { header ->
                    header.equals(HttpHeaders.Cookie, ignoreCase = true) ||
                        header.equals(HttpHeaders.Authorization, ignoreCase = true)
                }
        }
        val cached = effectiveCacheKey?.let { key ->
            cache.get(key, cachePolicy) ?: persistentCache?.read(key)?.toCachedText(cachePolicy, nowMillis)
        }
        if (cached?.freshness == CacheFreshness.Fresh) return cached

        var lastFailure: ProviderNetworkException? = null
        for (attempt in 0 until retryCount(kind)) {
            try {
                val response = httpClient.request(url) {
                    this.method = method
                    headers.forEach { (name, value) -> header(name, value) }
                    if (body != null) setBody(body)
                }
                val responseBody = response.bodyAsText()
                if (response.status.isSuccess()) {
                    effectiveCacheKey?.let { key ->
                        cache.put(key, responseBody)
                        persistentCache?.write(
                            key,
                            PersistedProviderCacheEntry(responseBody, nowMillis()),
                        )
                    }
                    return CachedText(responseBody, CacheFreshness.Network, nowMillis())
                }
                val exception = ProviderNetworkException.Http(
                    statusCode = response.status.value,
                    responseBody = responseBody,
                    retryAfterMillis = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.times(1_000),
                )
                if (!shouldRetry(exception.statusCode, kind) || attempt == retryPolicy.maxRetries) throw exception
                lastFailure = exception
                delay(retryDelayMillis(attempt, exception.retryAfterMillis))
            } catch (exception: ProviderNetworkException) {
                if (exception is ProviderNetworkException.Http && !shouldRetry(exception.statusCode, kind)) throw exception
                if (attempt == retryPolicy.maxRetries) {
                    lastFailure = exception
                    break
                }
                lastFailure = exception
                delay(retryDelayMillis(attempt, null))
            } catch (exception: HttpRequestTimeoutException) {
                val timeout = ProviderNetworkException.Timeout(exception)
                if (kind != ProviderRequestKind.SafeRead || attempt == retryPolicy.maxRetries) {
                    lastFailure = timeout
                    break
                }
                lastFailure = timeout
                delay(retryDelayMillis(attempt, null))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                val transport = ProviderNetworkException.Transport(exception)
                if (kind != ProviderRequestKind.SafeRead || attempt == retryPolicy.maxRetries) {
                    lastFailure = transport
                    break
                }
                lastFailure = transport
                delay(retryDelayMillis(attempt, null))
            }
        }

        cached?.takeIf { it.freshness == CacheFreshness.Stale }?.let { return it }
        throw lastFailure ?: ProviderNetworkException.Transport(IllegalStateException("request failed: $providerId $url"))
    }

    private fun retryCount(kind: ProviderRequestKind): Int {
        return if (kind == ProviderRequestKind.SafeRead) retryPolicy.maxRetries + 1 else 1
    }

    private fun shouldRetry(statusCode: Int, kind: ProviderRequestKind): Boolean {
        return kind == ProviderRequestKind.SafeRead && statusCode in setOf(408, 425, 429, 500, 502, 503, 504)
    }

    private fun retryDelayMillis(attempt: Int, retryAfterMillis: Long?): Long {
        retryAfterMillis?.let { return it.coerceAtMost(retryPolicy.maxDelayMillis) }
        val exponential = min(
            retryPolicy.maxDelayMillis,
            retryPolicy.baseDelayMillis * (1L shl attempt.coerceAtMost(10)),
        )
        return (exponential * (0.5 + random() * 0.5)).toLong()
    }
}

private suspend fun PersistedProviderCacheEntry.toCachedText(
    policy: ProviderCachePolicy,
    nowMillis: () -> Long,
): CachedText? {
    if (policy.ttlMillis <= 0) return null
    val age = (nowMillis() - storedAtMillis).coerceAtLeast(0)
    return when {
        age <= policy.ttlMillis -> CachedText(value, CacheFreshness.Fresh, storedAtMillis)
        age <= policy.ttlMillis + policy.staleMillis -> CachedText(value, CacheFreshness.Stale, storedAtMillis)
        else -> null
    }
}

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

internal fun HttpClientConfig<*>.installProviderClientDefaults() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        })
    }
    install(HttpCache)
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
}

internal expect fun createProviderHttpClient(): HttpClient

internal expect fun currentTimeMillis(): Long
