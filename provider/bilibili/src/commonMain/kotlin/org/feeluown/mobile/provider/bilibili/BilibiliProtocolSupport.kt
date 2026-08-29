package org.feeluown.mobile.provider.bilibili

import kotlinx.serialization.json.JsonObject
import org.feeluown.mobile.AudioQualityPolicy
import org.feeluown.mobile.provider.core.ProviderCredentials
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.parseCookies
import org.feeluown.mobile.provider.core.splitResourceId
import org.feeluown.mobile.provider.core.stringOrNull

internal fun rawIdentifier(value: String): String =
    splitResourceId(value).second.ifBlank { value.substringAfterLast(':') }

internal fun parsePaged(value: String): Pair<String, Int?> {
    if (!value.startsWith("paged_")) return value to null
    val parts = value.removePrefix("paged_").split("__", limit = 2)
    return parts.first() to parts.getOrNull(1)?.toIntOrNull()
}

internal fun selectAudio(
    qualityPolicy: String,
    audio: List<AudioStream>,
    flac: AudioStream?,
): SelectedAudio? {
    if (qualityPolicy == AudioQualityPolicy.Highest.policy && flac != null) {
        return SelectedAudio(flac, "SHQ")
    }
    val sorted = audio.sortedByDescending { it.bandwidth }
    val preferred = when (qualityPolicy) {
        AudioQualityPolicy.Low.policy -> sorted.filter {
            it.bandwidth <= BilibiliProviderDefinition.AUDIO_LOW_MAX_BANDWIDTH
        }
        AudioQualityPolicy.Standard.policy -> sorted.filter {
            it.bandwidth <= BilibiliProviderDefinition.AUDIO_STANDARD_MAX_BANDWIDTH
        }
        else -> sorted
    }
    val fallback = if (qualityPolicy == AudioQualityPolicy.Low.policy) sorted.lastOrNull() else sorted.firstOrNull()
    val selected = preferred.firstOrNull() ?: fallback ?: flac ?: return null
    return SelectedAudio(selected, selected.qualityLabel())
}

internal fun JsonObject.toAudioStream(isFlac: Boolean = false): AudioStream? {
    val url = stringOrNull("baseUrl") ?: stringOrNull("base_url") ?: return null
    return AudioStream(
        url = url,
        bandwidth = long("bandwidth") ?: 0L,
        durationMs = long("length"),
        isFlac = isFlac,
    )
}

internal fun JsonObject.toVideoStream(): VideoStream? {
    val url = stringOrNull("baseUrl") ?: stringOrNull("base_url") ?: return null
    return VideoStream(
        url = url,
        bandwidth = long("bandwidth") ?: 0L,
    )
}

internal fun stripHtml(value: String): String = value
    .replace(Regex("<[^>]+>"), "")
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")

internal fun normalizeCover(value: String?): String? = value?.let {
    if (it.startsWith("//")) "https:$it" else it
}

internal fun parseDurationMs(value: String): Long? {
    val parts = value.split(':').mapNotNull { it.toLongOrNull() }
    if (parts.isEmpty()) return null
    return parts.fold(0L) { total, part -> total * 60 + part } * 1_000
}

internal fun parseSearchTags(value: String?): List<String> = value
    ?.split(',', '，')
    ?.map { tag -> tag.trim() }
    ?.filter { tag -> tag.isNotBlank() }
    ?.distinct()
    .orEmpty()

internal fun String.keyFromUrl(): String = substringAfterLast('/').substringBeforeLast('.')

internal fun encodeQueryComponent(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val number = byte.toInt() and 0xff
        when {
            number == 0x20 -> append('+')
            number in 0x30..0x39 ||
                number in 0x41..0x5a ||
                number in 0x61..0x7a ||
                number in setOf(45, 46, 95, 126) -> append(number.toChar())
            else -> {
                append('%')
                append("0123456789ABCDEF"[number ushr 4])
                append("0123456789ABCDEF"[number and 0x0f])
            }
        }
    }
}

internal fun mixinKey(value: String): String = BilibiliProviderDefinition.mixinKeyTable.indices
    .mapNotNull { index -> value.getOrNull(BilibiliProviderDefinition.mixinKeyTable[index]) }
    .joinToString("")
    .take(32)

internal fun credentialsArePresent(credentials: ProviderCredentials): Boolean =
    credentials.cookies.isNotEmpty() ||
        !credentials.cookieHeader.isNullOrBlank() ||
        !credentials.authorization.isNullOrBlank()

internal fun csrfCookie(credentials: ProviderCredentials?): String? {
    credentials ?: return null
    return credentials.cookies["bili_jct"]?.takeIf { it.isNotBlank() }
        ?: parseCookies(credentials.cookieHeader.orEmpty())["bili_jct"]?.takeIf { it.isNotBlank() }
}

internal data class WbiKeys(val mixinKey: String)

internal data class AudioStream(
    val url: String,
    val bandwidth: Long,
    val durationMs: Long?,
    val isFlac: Boolean = false,
)

internal data class SelectedAudio(
    val stream: AudioStream,
    val quality: String,
)

internal data class VideoStream(
    val url: String,
    val bandwidth: Long,
)

private fun AudioStream.qualityLabel(): String = when {
    isFlac -> "SHQ"
    bandwidth <= BilibiliProviderDefinition.AUDIO_LOW_MAX_BANDWIDTH -> "LQ"
    bandwidth <= BilibiliProviderDefinition.AUDIO_STANDARD_MAX_BANDWIDTH -> "SQ"
    else -> "HQ"
}
