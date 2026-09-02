package org.feeluown.mobile

import io.ktor.http.Parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderRequestKind

class NeteaseAudioRecognitionMatcher(
    private val httpClient: ProviderHttpClient = ProviderHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AudioRecognitionMatcher {
    override suspend fun match(sessionId: String, fingerprint: String): List<RecognizedSong> {
        val response = httpClient.postForm(
            providerId = "netease",
            url = RECOGNITION_ENDPOINT,
            form = Parameters.build {
                append("sessionId", sessionId)
                append("algorithmCode", "shazam_v2")
                append("duration", "6")
                append("rawdata", fingerprint)
                append("times", "2")
                append("decrypt", "1")
            },
            headers = mapOf("Origin" to RECOGNITION_ORIGIN),
            kind = ProviderRequestKind.Media,
        ).value
        return parseNeteaseRecognitionMatches(response, json)
    }
}

internal fun parseNeteaseRecognitionMatches(
    response: String,
    json: Json = Json { ignoreUnknownKeys = true },
): List<RecognizedSong> {
    val root = json.parseToJsonElement(response).jsonObject
    val code = root["code"]?.jsonPrimitive?.longOrNull
    check(code == 200L) {
        root["message"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: "识别接口返回错误"
    }
    val data = root["data"] as? JsonObject
        ?: throw IllegalStateException("识别接口响应缺少 data")
    val results = data["result"] as? JsonArray ?: return emptyList()
    return results.mapNotNull { element ->
        val match = element as? JsonObject ?: return@mapNotNull null
        val song = match["song"] as? JsonObject ?: return@mapNotNull null
        val title = song["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val artists = (song["artists"] ?: song["ar"]) as? JsonArray
        val album = (song["album"] ?: song["al"]) as? JsonObject
        RecognizedSong(
            neteaseSongId = song["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
            title = title,
            artists = artists.artistNames(),
            album = album?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
            coverUrl = album?.get("picUrl")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
            matchStartTimeMs = match["startTime"]?.jsonPrimitive?.longOrNull,
        )
    }
}

private fun JsonArray?.artistNames(): List<String> = this
    ?.mapNotNull { element ->
        (element as? JsonObject)
            ?.get("name")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf(String::isNotBlank)
    }
    .orEmpty()

private const val RECOGNITION_ENDPOINT = "https://interface.music.163.com/api/music/audio/match"
private const val RECOGNITION_ORIGIN = "chrome-extension://pgphbbekcgpfaekhcbjamjjkegcclhhd"
