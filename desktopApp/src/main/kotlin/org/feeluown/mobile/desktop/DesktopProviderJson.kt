package org.feeluown.mobile.desktop

import org.feeluown.mobile.AudioQualityPolicy
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPart
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.ProviderAuthState
import org.feeluown.mobile.ProviderCapabilities
import org.feeluown.mobile.ProviderComment
import org.feeluown.mobile.ProviderContentSection
import org.feeluown.mobile.ProviderContentType
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderFeatureCategory
import org.feeluown.mobile.ProviderHeaderInput
import org.feeluown.mobile.ProviderInfo
import org.feeluown.mobile.ProviderLoginConfig
import org.feeluown.mobile.ProviderLoginMode
import org.feeluown.mobile.ProviderMediaItem
import org.feeluown.mobile.ProviderMediaItemDetail
import org.feeluown.mobile.ProviderMediaItemType
import org.feeluown.mobile.ProviderMutationResult
import org.feeluown.mobile.ProviderPlaylist
import org.feeluown.mobile.ProviderPlaylistDetail
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.ProviderVideo
import org.feeluown.mobile.TrackSourceType
import org.feeluown.mobile.VideoPlaybackPayload
import org.json.JSONArray
import org.json.JSONObject

internal fun enabledProvidersJson(providerIds: Set<String>): String {
    val array = JSONArray()
    providerIds.forEach { array.put(it) }
    return JSONObject().put("enabled", array).toString()
}

internal fun smartReplacementProviderIdsJson(providerIds: Set<String>): String {
    val array = JSONArray()
    providerIds.forEach { array.put(it) }
    return array.toString()
}

internal fun JSONObject.toProviderInfo(): ProviderInfo {
    val providerId = optString("provider_id")
    return ProviderInfo(
        providerId = providerId,
        providerName = optString("provider_name").ifBlank { providerId },
        loginConfig = optJSONObject("login_config")?.toLoginConfig(),
        supportedLoginModes = optJSONArray("login_modes").toLoginModes(),
    )
}

internal fun JSONObject.toSearchResults(): ProviderSearchResults {
    val tracksArray = optJSONArray("tracks") ?: JSONArray()
    val playlistsArray = optJSONArray("playlists") ?: JSONArray()
    val artistsArray = optJSONArray("artists") ?: JSONArray()
    val albumsArray = optJSONArray("albums") ?: JSONArray()
    val videosArray = optJSONArray("videos") ?: JSONArray()
    return ProviderSearchResults(
        tracks = List(tracksArray.length()) { index -> tracksArray.getJSONObject(index).toTrack() },
        playlists = List(playlistsArray.length()) { index -> playlistsArray.getJSONObject(index).toPlaylist() },
        artists = List(artistsArray.length()) { index -> artistsArray.getJSONObject(index).toMediaItem() },
        albums = List(albumsArray.length()) { index -> albumsArray.getJSONObject(index).toMediaItem() },
        videos = List(videosArray.length()) { index -> videosArray.getJSONObject(index).toProviderVideo() },
        errorMessage = optString("error_message").takeIf { it.isNotBlank() },
    )
}

internal fun JSONObject.toLoginConfig(): ProviderLoginConfig = ProviderLoginConfig(
    loginUrl = optString("login_url"),
    cookieKeyGroups = optJSONArray("cookie_key_groups").toStringGroups(),
)

internal fun JSONObject.toCapabilities(): ProviderCapabilities {
    val providerId = optString("provider_id")
    return ProviderCapabilities(
        providerId = providerId,
        providerName = optString("provider_name").ifBlank { providerId },
        canAddSongToPlaylist = optBoolean("can_add_song_to_playlist"),
        canRemoveSongFromPlaylist = optBoolean("can_remove_song_from_playlist"),
        canFavoritePlaylist = optBoolean("can_favorite_playlist"),
        canUnfavoritePlaylist = optBoolean("can_unfavorite_playlist"),
        canFavoriteArtist = optBoolean("can_favorite_artist"),
        canUnfavoriteArtist = optBoolean("can_unfavorite_artist"),
        canFavoriteAlbum = optBoolean("can_favorite_album"),
        canUnfavoriteAlbum = optBoolean("can_unfavorite_album"),
    )
}

internal fun JSONObject.toFeature(): ProviderFeature {
    val providerId = optString("provider_id")
    return ProviderFeature(
        id = getString("id"),
        providerId = providerId,
        providerName = optString("provider_name").ifBlank { providerId },
        title = optString("title"),
        category = ProviderFeatureCategory.valueOf(optString("category")),
        contentType = ProviderContentType.valueOf(optString("content_type")),
        requiresLogin = optBoolean("requires_login"),
    )
}

internal fun JSONObject.toContentSection(fallbackFeature: ProviderFeature): ProviderContentSection {
    val parsedFeature = optJSONObject("feature")?.toFeature() ?: fallbackFeature
    val tracksArray = optJSONArray("tracks") ?: JSONArray()
    val playlistsArray = optJSONArray("playlists") ?: JSONArray()
    val mediaItemsArray = optJSONArray("media_items") ?: JSONArray()
    return ProviderContentSection(
        feature = parsedFeature,
        tracks = List(tracksArray.length()) { index -> tracksArray.getJSONObject(index).toTrack() },
        playlists = List(playlistsArray.length()) { index -> playlistsArray.getJSONObject(index).toPlaylist() },
        mediaItems = List(mediaItemsArray.length()) { index -> mediaItemsArray.getJSONObject(index).toMediaItem() },
        isLoginRequired = optBoolean("is_login_required"),
        errorMessage = optString("error_message").takeIf { it.isNotBlank() },
        nextOffset = optInt("next_offset"),
        hasMore = optBoolean("has_more"),
    )
}

internal fun JSONObject.toPlaylist(): ProviderPlaylist {
    val providerId = optString("provider_id")
    return ProviderPlaylist(
        id = getString("id"),
        title = optString("title"),
        providerId = providerId,
        providerName = optString("provider_name").ifBlank { providerId },
        coverUrl = optString("cover_url").takeIf { it.isNotBlank() },
        description = optString("description"),
        playCount = optLong("play_count").takeIf { it > 0 },
        providerUrl = optString("provider_url").takeIf { it.isNotBlank() },
        trackCount = optNullableInt("track_count"),
    )
}

internal fun JSONObject.toPlaylistDetail(fallbackPlaylist: ProviderPlaylist): ProviderPlaylistDetail {
    val tracksArray = optJSONArray("tracks") ?: JSONArray()
    return ProviderPlaylistDetail(
        playlist = optJSONObject("playlist")?.toPlaylist() ?: fallbackPlaylist,
        tracks = List(tracksArray.length()) { index -> tracksArray.getJSONObject(index).toTrack() },
        tracksNextOffset = optInt("tracks_next_offset"),
        tracksHasMore = optBoolean("tracks_has_more"),
    )
}

internal fun JSONObject.toMediaItem(): ProviderMediaItem {
    val providerId = optString("provider_id")
    return ProviderMediaItem(
        id = getString("id"),
        title = optString("title"),
        providerId = providerId,
        providerName = optString("provider_name").ifBlank { providerId },
        type = ProviderMediaItemType.valueOf(optString("type")),
        coverUrl = optString("cover_url").takeIf { it.isNotBlank() },
        description = optString("description"),
        providerUrl = optString("provider_url").takeIf { it.isNotBlank() },
        trackCount = optNullableInt("track_count"),
        albumCount = optNullableInt("album_count"),
    )
}

internal fun JSONObject.toProviderVideo(): ProviderVideo {
    val providerId = optString("provider_id")
    return ProviderVideo(
        id = getString("id"),
        title = optString("title"),
        artists = optString("artists"),
        providerId = providerId,
        providerName = optString("provider_name").ifBlank { providerId },
        coverUrl = optString("cover_url").takeIf { it.isNotBlank() },
        durationMs = optLong("duration_ms").takeIf { it > 0 },
        providerUrl = optString("provider_url").takeIf { it.isNotBlank() },
    )
}

internal fun JSONObject.toProviderComment(): ProviderComment = ProviderComment(
    id = optString("id"),
    userName = optString("user_name"),
    content = optString("content"),
    likedCount = optLong("liked_count"),
    timeSeconds = optLong("time"),
)

internal fun JSONObject.toMediaItemDetail(fallbackItem: ProviderMediaItem): ProviderMediaItemDetail {
    val tracksArray = optJSONArray("tracks") ?: JSONArray()
    val albumsArray = optJSONArray("albums") ?: JSONArray()
    return ProviderMediaItemDetail(
        item = optJSONObject("item")?.toMediaItem() ?: fallbackItem,
        tracks = List(tracksArray.length()) { index -> tracksArray.getJSONObject(index).toTrack() },
        albums = List(albumsArray.length()) { index -> albumsArray.getJSONObject(index).toMediaItem() },
        tracksNextOffset = optInt("tracks_next_offset"),
        tracksHasMore = optBoolean("tracks_has_more"),
        albumsNextOffset = optInt("albums_next_offset"),
        albumsHasMore = optBoolean("albums_has_more"),
    )
}

internal fun JSONObject.toVideoPlaybackPayload(fallbackVideo: ProviderVideo): VideoPlaybackPayload = VideoPlaybackPayload(
    video = optJSONObject("video")?.toProviderVideo() ?: fallbackVideo,
    url = optString("url"),
    videoUrl = optString("video_url"),
    audioUrl = optString("audio_url"),
    headers = optJSONObject("headers").toStringMap(),
    quality = optString("quality").takeIf { it.isNotBlank() },
)

internal fun JSONObject.toTrack(): MusicTrack {
    val id = getString("id")
    val source = optString("source")
    return MusicTrack(
        id = id,
        title = optString("title"),
        artists = optString("artists"),
        album = optString("album"),
        source = source,
        sourceType = TrackSourceType.Provider,
        coverUrl = optString("cover_url").takeIf { it.isNotBlank() },
        durationMs = optLong("duration_ms").takeIf { it > 0 },
        providerId = id,
        providerName = optString("provider_name").ifBlank { source }.takeIf { it.isNotBlank() },
        artistItemId = optString("artist_item_id").takeIf { it.isNotBlank() },
        albumItemId = optString("album_item_id").takeIf { it.isNotBlank() },
        providerUrl = optString("provider_url").takeIf { it.isNotBlank() },
    )
}

internal fun JSONObject.toPayload(track: MusicTrack): PlaybackPayload {
    val smartReplacement = optBoolean("smart_replacement", false)
    return PlaybackPayload(
        url = getString("url"),
        title = optString("title").ifBlank { track.title },
        artists = optString("artists").ifBlank { track.artists },
        album = optString("album").ifBlank { track.album },
        source = optString("source").ifBlank { track.source },
        headers = optJSONObject("headers").toStringMap(),
        coverUrl = optString("cover_url").takeIf { it.isNotBlank() }
            ?: optString("original_cover_url").takeIf { smartReplacement && it.isNotBlank() }
            ?: track.coverUrl,
        durationMs = optLong("duration_ms").takeIf { it > 0 } ?: track.durationMs,
        lyrics = optString("lyrics").takeIf { it.isNotBlank() },
        audioQuality = optString("audio_quality").takeIf { it.isNotBlank() },
        providerName = optString("replacement_provider_name")
            .takeIf { smartReplacement && it.isNotBlank() }
            ?: optString("provider_name").takeIf { it.isNotBlank() }
            ?: track.providerName,
        isSmartReplacement = smartReplacement,
        originalTitle = optString("original_title").takeIf { smartReplacement && it.isNotBlank() },
        originalProviderName = optString("original_provider_name").takeIf { smartReplacement && it.isNotBlank() },
        originalCoverUrl = optString("original_cover_url").takeIf { smartReplacement && it.isNotBlank() },
        replacementTitle = optString("replacement_title").takeIf { smartReplacement && it.isNotBlank() },
        replacementArtists = optString("replacement_artists").takeIf { smartReplacement && it.isNotBlank() },
        replacementSource = optString("replacement_source").takeIf { smartReplacement && it.isNotBlank() },
        replacementProviderName = optString("replacement_provider_name").takeIf { smartReplacement && it.isNotBlank() },
        replacementCoverUrl = optString("replacement_cover_url").takeIf { smartReplacement && it.isNotBlank() },
        replacementStrategy = optString("standby_strategy").takeIf { smartReplacement && it.isNotBlank() },
        replacementScore = optDouble("standby_score").takeIf { smartReplacement && has("standby_score") },
        parts = optJSONArray("parts").toPlaybackParts(),
        currentPartIndex = optInt("current_part_index", -1),
    )
}

internal fun JSONObject.toAuthState(providerId: String): ProviderAuthState = ProviderAuthState(
    providerId = optString("provider_id").ifBlank { providerId },
    providerName = optString("provider_name").ifBlank { providerId },
    isLoggedIn = optBoolean("is_logged_in"),
    userName = optString("user_name").takeIf { it.isNotBlank() },
)

internal fun JSONObject.toMutationResult(): ProviderMutationResult = ProviderMutationResult(
    success = optBoolean("success"),
    message = optString("message"),
)

internal fun providerCookieInputsJson(inputs: Map<String, String>): String {
    val json = JSONObject()
    inputs.forEach { (key, value) ->
        if (key.isNotBlank() && value.isNotBlank()) json.put(key, value)
    }
    return json.toString()
}

internal fun providerHeaderInputsJson(inputs: Map<String, ProviderHeaderInput>): String {
    val json = JSONObject()
    inputs.forEach { (providerId, input) ->
        if (providerId.isNotBlank() && (input.authorization.isNotBlank() || input.cookie.isNotBlank())) {
            json.put(
                providerId,
                JSONObject()
                    .put("authorization", input.authorization)
                    .put("cookie", input.cookie),
            )
        }
    }
    return json.toString()
}

internal fun JSONObject.readProviderCookieInputs(): Map<String, String> {
    val result = linkedMapOf<String, String>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = optString(key)
        if (key.isNotBlank() && value.isNotBlank()) result[key] = value
    }
    return result
}

internal fun JSONObject.readProviderHeaderInputs(): Map<String, ProviderHeaderInput> {
    val result = linkedMapOf<String, ProviderHeaderInput>()
    val keys = keys()
    while (keys.hasNext()) {
        val providerId = keys.next()
        val value = optJSONObject(providerId) ?: continue
        val input = ProviderHeaderInput(
            authorization = value.optString("authorization"),
            cookie = value.optString("cookie"),
        )
        if (providerId.isNotBlank() && (input.authorization.isNotBlank() || input.cookie.isNotBlank())) {
            result[providerId] = input
        }
    }
    return result
}

internal fun audioQualityPolicyForDesktop(
    wifiPolicy: AudioQualityPolicy,
    cellularPolicy: AudioQualityPolicy,
): AudioQualityPolicy = wifiPolicy

private fun JSONArray?.toStringGroups(): List<List<String>> {
    if (this == null) return emptyList()
    return List(length()) { index ->
        val group = getJSONArray(index)
        List(group.length()) { keyIndex -> group.getString(keyIndex) }
    }
}

private fun JSONArray?.toLoginModes(): Set<ProviderLoginMode> {
    if (this == null) return setOf(ProviderLoginMode.WebView, ProviderLoginMode.Cookie)
    return List(length()) { index -> optString(index) }
        .mapNotNull { raw -> runCatching { ProviderLoginMode.valueOf(raw) }.getOrNull() }
        .toSet()
        .ifEmpty { setOf(ProviderLoginMode.WebView, ProviderLoginMode.Cookie) }
}

private fun JSONArray?.toPlaybackParts(): List<PlaybackPart> {
    if (this == null) return emptyList()
    return List(length()) { index ->
        val item = getJSONObject(index)
        PlaybackPart(
            id = item.optString("id"),
            title = item.optString("title"),
            durationMs = item.optLong("duration_ms").takeIf { it > 0 },
        )
    }.filter { it.id.isNotBlank() }
}

private fun JSONObject?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    val keys = keys()
    val result = linkedMapOf<String, String>()
    while (keys.hasNext()) {
        val key = keys.next()
        result[key] = optString(key)
    }
    return result
}

private fun JSONObject.optNullableInt(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}
