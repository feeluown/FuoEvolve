package org.feeluown.mobile

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SHARED_LINK_CONNECT_TIMEOUT_MS = 4_000
private const val SHARED_LINK_READ_TIMEOUT_MS = 4_000
private const val MAX_SHARED_LINK_REDIRECTS = 6
private val ANDROID_SHARED_URL_REGEX = Regex("""https?://[^\s<>\"'，。！？、；]+""", RegexOption.IGNORE_CASE)
private val SHORT_SHARE_HOSTS = setOf(
    "163cn.tv",
    "www.163cn.tv",
    "b23.tv",
    "www.b23.tv",
    "bili2233.cn",
    "www.bili2233.cn",
    "c6.y.qq.com",
    "url.cn",
)

suspend fun resolveAndroidSharedText(text: String): String = withContext(Dispatchers.IO) {
    if (text.isBlank() || parseSharedResource(text) != null) return@withContext text
    var resolvedText = text
    for (match in ANDROID_SHARED_URL_REGEX.findAll(text)) {
        val originalUrl = match.value.trimEnd('.', ',', ':', ';', '!', '?', ')', ']', '}', '。', '，', '：', '；', '！', '？')
        if (!shouldExpandSharedUrl(originalUrl)) continue
        val expandedUrl = runCatching { followRedirects(originalUrl) }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != originalUrl }
            ?: continue
        resolvedText = resolvedText.replace(originalUrl, expandedUrl)
        if (parseSharedResource(resolvedText) != null) return@withContext resolvedText
    }
    resolvedText
}

private fun shouldExpandSharedUrl(url: String): Boolean = runCatching {
    URL(url).host.lowercase() in SHORT_SHARE_HOSTS
}.getOrDefault(false)

private fun followRedirects(sourceUrl: String): String {
    var currentUrl = sourceUrl
    repeat(MAX_SHARED_LINK_REDIRECTS) {
        val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = SHARED_LINK_CONNECT_TIMEOUT_MS
            readTimeout = SHARED_LINK_READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) FuoEvolve")
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
        }
        val nextUrl = try {
            val responseCode = connection.responseCode
            if (responseCode !in 300..399) return currentUrl
            val location = connection.getHeaderField("Location")?.takeIf { it.isNotBlank() }
                ?: return currentUrl
            URL(URL(currentUrl), location).toString()
        } finally {
            connection.disconnect()
        }
        if (nextUrl == currentUrl) return currentUrl
        currentUrl = nextUrl
    }
    return currentUrl
}
