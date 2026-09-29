package org.feeluown.mobile

import android.os.Parcel
import androidx.media3.common.MediaMetadata
import org.json.JSONObject

internal const val COLOR_OS_LYRIC_INFO_KEY = "lyricInfo"
internal const val COLOR_OS_TOGGLE_TRANSLATION_ACTION =
    "io.github.andrealtb.lockscreenlyrics.action.TOGGLE_TRANSLATION"
internal const val COLOR_OS_MAX_METADATA_BYTES = 480 * 1024

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

internal fun isColorOsMetadataWithinLimit(metadata: MediaMetadata): Boolean {
    val parcel = Parcel.obtain()
    return try {
        parcel.writeBundle(metadata.toBundle())
        parcel.dataSize() <= COLOR_OS_MAX_METADATA_BYTES
    } finally {
        parcel.recycle()
    }
}
