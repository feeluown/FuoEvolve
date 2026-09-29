package org.feeluown.mobile

import org.json.JSONObject

internal const val COLOR_OS_LYRIC_INFO_KEY = "lyricInfo"
internal const val COLOR_OS_TOGGLE_TRANSLATION_ACTION =
    "io.github.andrealtb.lockscreenlyrics.action.TOGGLE_TRANSLATION"
internal const val COLOR_OS_MAX_LYRIC_INFO_BYTES = 480 * 1024

internal fun buildColorOsLyricInfo(
    packageName: String,
    track: MusicTrack,
    lyrics: PlatformTimedLyrics,
    generation: Long,
): String {
    val providerTrackId = track.providerId?.takeIf(String::isNotBlank) ?: track.id
    val trackKey = listOf(
        track.source,
        providerTrackId,
        track.title,
        track.artists,
        track.durationMs?.toString().orEmpty(),
    )
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString("|")
        .ifBlank { track.id }

    return JSONObject()
        .put("songName", track.title)
        .put("artist", track.artists)
        .put("songId", track.id)
        .put("lyricType", 0)
        .put("lyric", lyrics.lyric)
        .put("noLyric", false)
        .put("provider", packageName)
        .put("source", "fuoevolve")
        .put("trackKey", trackKey)
        .put("sessionGeneration", generation.coerceAtLeast(1L))
        .apply {
            track.album.takeIf(String::isNotBlank)?.let { put("album", it) }
            lyrics.rawLyric?.let { put("rawLyric", it) }
            lyrics.translationLyric?.let { put("translationLyric", it) }
        }
        .toString()
}

internal fun isColorOsLyricInfoWithinLimit(lyricInfo: String): Boolean =
    lyricInfo.toByteArray(Charsets.UTF_8).size <= COLOR_OS_MAX_LYRIC_INFO_BYTES
