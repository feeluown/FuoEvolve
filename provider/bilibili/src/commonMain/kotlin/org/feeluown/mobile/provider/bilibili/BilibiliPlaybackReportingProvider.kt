package org.feeluown.mobile.provider.bilibili

import io.ktor.http.Parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.feeluown.mobile.ProviderPlaybackReport
import org.feeluown.mobile.ProviderPlaybackReportKind
import org.feeluown.mobile.ProviderPlaybackReportingCapability
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.array
import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.cookieHeader
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.md5Hex
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.splitResourceId
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderRequestKind

internal class BilibiliPlaybackReportingProvider(
    private val delegate: KotlinMusicProvider,
    private val http: ProviderHttpClient,
    private val credentials: ProviderCredentialStore,
) : KotlinMusicProvider by delegate, ProviderPlaybackReportingCapability {
    private data class VideoIdentity(
        val aid: Long,
        val cid: Long,
    )

    private val identityMutex = Mutex()
    private val identities = mutableMapOf<String, VideoIdentity>()

    override suspend fun reportPlayback(report: ProviderPlaybackReport) {
        val stored = credentials.read(ID) ?: return
        val cookie = cookieHeader(stored)
        val csrf = cookieValue(cookie, "bili_jct") ?: return
        if (cookieValue(cookie, "SESSDATA").isNullOrBlank()) return

        val (bvid, page) = parseTrackId(report.trackId) ?: return
        val identity = videoIdentity(bvid, page) ?: return
        val playedSeconds = (report.playedMs.coerceAtLeast(0L) / 1_000L).toInt()
        val durationSeconds = report.durationMs
            ?.takeIf { it > 0L }
            ?.div(1_000L)
            ?.coerceAtLeast(1L)
            ?.toInt()
        val finished = report.kind == ProviderPlaybackReportKind.Completed
        val response = http.postForm(
            providerId = ID,
            url = "$API_BASE/x/click-interface/web/heartbeat",
            form = Parameters.build {
                append("aid", identity.aid.toString())
                append("bvid", bvid)
                append("cid", identity.cid.toString())
                append("played_time", if (finished) "-1" else playedSeconds.toString())
                append("realtime", playedSeconds.toString())
                append("real_played_time", playedSeconds.toString())
                durationSeconds?.let { append("video_duration", it.toString()) }
                append("last_play_progress_time", playedSeconds.toString())
                append("max_play_progress_time", playedSeconds.toString())
                append("start_ts", (report.startedAtMillis / 1_000L).toString())
                append("type", "3")
                append("dt", "2")
                append("outer", "0")
                append("session", md5Hex(report.sessionId))
                append("play_type", report.kind.toBilibiliPlayType().toString())
                append("csrf", csrf)
            },
            headers = mapOf(
                "Cookie" to cookie,
                "Referer" to "https://www.bilibili.com/video/$bvid",
                "Origin" to "https://www.bilibili.com",
                "User-Agent" to USER_AGENT,
            ),
            kind = ProviderRequestKind.Mutation,
        ).value
        val code = providerJson.parseToJsonElement(response).asObject().int("code")
        check(code == null || code == 0) { "Bilibili playback heartbeat failed: code=$code" }
    }

    private suspend fun videoIdentity(bvid: String, page: Int): VideoIdentity? {
        val key = "$bvid:$page"
        identityMutex.withLock { identities[key] }?.let { return it }
        val response = http.getText(
            providerId = ID,
            url = "$API_BASE/x/web-interface/view?bvid=$bvid",
            headers = mapOf(
                "Referer" to "https://www.bilibili.com/video/$bvid",
                "User-Agent" to USER_AGENT,
            ),
            cacheKey = null,
        ).value
        val data = providerJson.parseToJsonElement(response).asObject().obj("data") ?: return null
        val aid = data.long("aid") ?: return null
        val pages = data.array("pages")
        val pageInfo = pages.getOrNull((page - 1).coerceAtLeast(0))?.asObject()
        val cid = pageInfo?.long("cid") ?: data.long("cid") ?: return null
        return VideoIdentity(aid, cid).also { identity ->
            identityMutex.withLock { identities[key] = identity }
        }
    }

    private fun parseTrackId(trackId: String): Pair<String, Int>? {
        val raw = splitResourceId(trackId).second.ifBlank { trackId.substringAfterLast(':') }
        if (!raw.startsWith(PAGED_PREFIX)) return raw.takeIf { it.startsWith("BV") }?.let { it to 1 }
        val encoded = raw.removePrefix(PAGED_PREFIX)
        val separator = encoded.lastIndexOf(PAGE_SEPARATOR)
        if (separator <= 0) return null
        val bvid = encoded.substring(0, separator)
        val page = encoded.substring(separator + PAGE_SEPARATOR.length).toIntOrNull() ?: return null
        return bvid to page.coerceAtLeast(1)
    }

    private fun cookieValue(cookie: String, name: String): String? = cookie
        .split(';')
        .asSequence()
        .map(String::trim)
        .firstOrNull { it.substringBefore('=').trim() == name }
        ?.substringAfter('=', "")
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun ProviderPlaybackReportKind.toBilibiliPlayType(): Int = when (this) {
        ProviderPlaybackReportKind.Started -> 1
        ProviderPlaybackReportKind.Progress -> 0
        ProviderPlaybackReportKind.Completed,
        ProviderPlaybackReportKind.Changed,
        ProviderPlaybackReportKind.Stopped,
        ProviderPlaybackReportKind.Error,
        -> 4
    }

    private companion object {
        const val ID = "bilibili"
        const val API_BASE = "https://api.bilibili.com"
        const val PAGED_PREFIX = "paged_"
        const val PAGE_SEPARATOR = "__"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    }
}
