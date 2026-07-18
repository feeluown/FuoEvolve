package org.feeluown.mobile

import org.json.JSONArray
import org.json.JSONObject

internal fun PlaybackPlan.toJson(): String = JSONObject()
    .put("generation", generation)
    .put("requests", JSONArray().apply {
        requests.forEach { put(it.toJsonObject()) }
    })
    .toString()

internal fun String.toPlaybackPlan(): PlaybackPlan {
    val root = JSONObject(this)
    val requests = root.optJSONArray("requests") ?: JSONArray()
    return PlaybackPlan(
        generation = root.optLong("generation"),
        requests = buildList {
            for (index in 0 until requests.length()) {
                add(requests.getJSONObject(index).toPlaybackRequest())
            }
        },
    )
}

private fun PlaybackRequest.toJsonObject(): JSONObject = JSONObject()
    .put("track", track.toJsonObject())
    .put("resolve_track", resolveTrack.toJsonObject())
    .put("requested_part_index", requestedPartIndex)
    .put("unavailable_policy", unavailablePolicy.name)
    .put("replacement_provider_ids", JSONArray(smartReplacementProviderIds.toList()))
    .put("replacement_min_score", smartReplacementMinScore)
    .put("use_original_metadata", smartReplacementUseOriginalMetadata)
    .put("use_original_lyrics", smartReplacementUseOriginalLyrics)

private fun JSONObject.toPlaybackRequest(): PlaybackRequest = PlaybackRequest(
    track = getJSONObject("track").toMusicTrack(),
    resolveTrack = getJSONObject("resolve_track").toMusicTrack(),
    requestedPartIndex = optInt("requested_part_index", -1).takeIf { it >= 0 },
    unavailablePolicy = optString("unavailable_policy")
        .let { value -> runCatching { UnavailablePlaybackPolicy.valueOf(value) }.getOrDefault(DEFAULT_UNAVAILABLE_PLAYBACK_POLICY) },
    smartReplacementProviderIds = optJSONArray("replacement_provider_ids")
        ?.let { ids -> buildSet { for (index in 0 until ids.length()) add(ids.getString(index)) } }
        .orEmpty(),
    smartReplacementMinScore = optDouble("replacement_min_score", DEFAULT_SMART_REPLACEMENT_MIN_SCORE),
    smartReplacementUseOriginalMetadata = optBoolean("use_original_metadata"),
    smartReplacementUseOriginalLyrics = optBoolean("use_original_lyrics"),
)

internal fun MusicTrack.toJsonObject(): JSONObject = JSONObject()
    .put("id", id)
    .put("title", title)
    .put("artists", artists)
    .put("album", album)
    .put("source", source)
    .put("source_type", sourceType.name)
    .put("cover_url", coverUrl)
    .put("duration_ms", durationMs)
    .put("local_uri", localUri)
    .put("lyrics", lyrics)
    .put("provider_id", providerId)
    .put("provider_name", providerName)
    .put("smart_replacement", isSmartReplacement)
    .put("original_title", originalTitle)
    .put("original_provider_name", originalProviderName)
    .put("original_cover_url", originalCoverUrl)
    .put("replacement_title", replacementTitle)
    .put("replacement_artists", replacementArtists)
    .put("replacement_source", replacementSource)
    .put("replacement_provider_name", replacementProviderName)
    .put("replacement_cover_url", replacementCoverUrl)
    .put("replacement_strategy", replacementStrategy)
    .put("replacement_score", replacementScore)
    .put("unavailable", isUnavailable)

internal fun JSONObject.toMusicTrack(): MusicTrack = MusicTrack(
    id = getString("id"),
    title = optString("title"),
    artists = optString("artists"),
    album = optString("album"),
    source = optString("source"),
    sourceType = optString("source_type")
        .let { value -> runCatching { TrackSourceType.valueOf(value) }.getOrDefault(TrackSourceType.Provider) },
    coverUrl = optString("cover_url").takeIf { it.isNotBlank() },
    durationMs = optLong("duration_ms", 0L).takeIf { it > 0L },
    localUri = optString("local_uri").takeIf { it.isNotBlank() },
    lyrics = optString("lyrics").takeIf { it.isNotBlank() },
    providerId = optString("provider_id").takeIf { it.isNotBlank() },
    providerName = optString("provider_name").takeIf { it.isNotBlank() },
    isSmartReplacement = optBoolean("smart_replacement"),
    originalTitle = optString("original_title").takeIf { it.isNotBlank() },
    originalProviderName = optString("original_provider_name").takeIf { it.isNotBlank() },
    originalCoverUrl = optString("original_cover_url").takeIf { it.isNotBlank() },
    replacementTitle = optString("replacement_title").takeIf { it.isNotBlank() },
    replacementArtists = optString("replacement_artists").takeIf { it.isNotBlank() },
    replacementSource = optString("replacement_source").takeIf { it.isNotBlank() },
    replacementProviderName = optString("replacement_provider_name").takeIf { it.isNotBlank() },
    replacementCoverUrl = optString("replacement_cover_url").takeIf { it.isNotBlank() },
    replacementStrategy = optString("replacement_strategy").takeIf { it.isNotBlank() },
    replacementScore = optDouble("replacement_score", 0.0).takeIf { it > 0.0 },
    isUnavailable = optBoolean("unavailable"),
)
