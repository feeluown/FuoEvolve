package org.feeluown.mobile.provider.netease

import io.ktor.http.Parameters
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.feeluown.mobile.ProviderPlaybackReport
import org.feeluown.mobile.ProviderPlaybackReportingCapability
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.cookieHeader
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.md5Hex
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderRequestKind
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.splitResourceId

internal class NeteasePlaybackReportingProvider(
    private val delegate: KotlinMusicProvider,
    private val http: ProviderHttpClient,
    private val credentials: ProviderCredentialStore,
) : KotlinMusicProvider by delegate, ProviderPlaybackReportingCapability {
    override suspend fun reportPlayback(report: ProviderPlaybackReport) {
        val stored = credentials.read(ID) ?: return
        val cookie = cookieHeader(stored)
        if (cookie.isBlank() || !hasAccountCookie(cookie)) return
        val songId = splitResourceId(report.trackId).second
            .ifBlank { report.trackId.substringAfterLast(':') }
            .toLongOrNull()
            ?: return
        val durationMs = report.durationMs?.takeIf { it > 0L }
        val playedMs = durationMs?.let { report.playedMs.coerceAtMost(it) } ?: report.playedMs
        val request = buildJsonObject {
            put(
                "playStateSubmitReq",
                buildJsonObject {
                    put("resource", buildJsonObject {
                        put("id", songId.toString())
                        put("type", "song")
                    })
                    put("progress", (playedMs.coerceAtLeast(0L) / 1_000L).toInt())
                    put("sessionId", md5Hex(report.sessionId).uppercase().take(12))
                    put("playMode", "list_loop")
                }.toString(),
            )
        }.toString()
        val payload = NeteaseWeApi.encrypt(request)
        val response = http.postForm(
            providerId = ID,
            url = "$BASE/weapi/relay/play/state/submit",
            form = Parameters.build {
                append("params", payload.params)
                append("encSecKey", payload.encSecKey)
            },
            headers = mapOf(
                "Cookie" to cookie,
                "Referer" to "$BASE/",
                "User-Agent" to USER_AGENT,
            ),
            kind = ProviderRequestKind.Mutation,
        ).value
        val code = providerJson.parseToJsonElement(response).asObject().int("code")
        check(code == null || code == 200) { "NetEase playback report failed: code=$code" }
    }

    private fun hasAccountCookie(cookie: String): Boolean =
        cookie.split(';').any { part ->
            val name = part.substringBefore('=').trim()
            name == "MUSIC_U" || name == "MUSIC_A"
        }

    private companion object {
        const val ID = "netease"
        const val BASE = "https://music.163.com"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    }
}
