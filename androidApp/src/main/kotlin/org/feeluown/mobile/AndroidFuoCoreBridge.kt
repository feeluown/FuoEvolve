package org.feeluown.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AndroidFuoCoreBridge(
    private val context: Context,
) : ProviderMusicRepository {
    @Volatile
    private var bridge: PyObject? = null
    @Volatile
    private var enabledProviderIds: Set<String> = DEFAULT_ENABLED_PROVIDER_IDS
    @Volatile
    private var initializedProviderIds: Set<String>? = null
    @Volatile
    private var wifiAudioQualityPolicy: AudioQualityPolicy = DEFAULT_WIFI_AUDIO_QUALITY_POLICY
    @Volatile
    private var cellularAudioQualityPolicy: AudioQualityPolicy = DEFAULT_CELLULAR_AUDIO_QUALITY_POLICY

    override suspend fun initialize() {
        updateEnabledProviders(enabledProviderIds)
    }

    override suspend fun availableProviders(): List<ProviderInfo> = AVAILABLE_PROVIDERS

    override suspend fun updateEnabledProviders(providerIds: Set<String>) {
        val nextProviderIds = providerIds
            .ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS }
            .intersect(AVAILABLE_PROVIDERS.map { it.providerId }.toSet())
            .ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS }
        synchronized(this) {
            enabledProviderIds = nextProviderIds
        }
        withContext(Dispatchers.IO) {
            synchronized(this@AndroidFuoCoreBridge) {
                if (enabledProviderIds != nextProviderIds) return@synchronized
                if (bridge != null && initializedProviderIds == nextProviderIds) return@synchronized
                try {
                    Log.d(TAG, "initialize start providers=$nextProviderIds")
                    val nextBridge = Python.getInstance()
                        .getModule("fuo_mobile.bridge")
                        .callAttr("create_bridge", enabledProvidersJson(nextProviderIds))
                    bridge = nextBridge
                    initializedProviderIds = nextProviderIds
                    Log.d(TAG, "initialize done")
                } catch (throwable: Throwable) {
                    Log.e(TAG, "initialize failed", throwable)
                    throw throwable
                }
            }
        }
    }

    override suspend fun providers(): List<ProviderInfo> {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge).callAttr("providers").toString()
            val array = JSONObject(raw).getJSONArray("providers")
            List(array.length()) { index -> array.getJSONObject(index).toProviderInfo() }
        }
    }

    override suspend fun search(keyword: String, providerId: String?): List<MusicTrack> {
        initialize()
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "search start keyword=$keyword providerId=$providerId")
                val raw = requireNotNull(bridge).callAttr("search", keyword, providerId.orEmpty()).toString()
                val array = JSONObject(raw).getJSONArray("tracks")
                List(array.length()) { index ->
                    array.getJSONObject(index).toTrack()
                }.also {
                    Log.d(TAG, "search done count=${it.size}")
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, "search failed keyword=$keyword providerId=$providerId", throwable)
                throw throwable
            }
        }
    }

    override suspend fun searchAll(keyword: String, providerId: String?): ProviderSearchResults {
        initialize()
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "searchAll start keyword=$keyword providerId=$providerId")
                val raw = requireNotNull(bridge).callAttr("search_all", keyword, providerId.orEmpty()).toString()
                JSONObject(raw).toSearchResults().also {
                    Log.d(
                        TAG,
                        "searchAll done tracks=${it.tracks.size} playlists=${it.playlists.size} " +
                            "artists=${it.artists.size} albums=${it.albums.size} videos=${it.videos.size}",
                    )
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, "searchAll failed keyword=$keyword providerId=$providerId", throwable)
                throw throwable
            }
        }
    }

    override suspend fun trackDetail(trackId: String): MusicTrack {
        initialize()
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "trackDetail start trackId=$trackId")
                val raw = requireNotNull(bridge).callAttr("track_detail", trackId).toString()
                JSONObject(raw).getJSONObject("track").toTrack().also {
                    Log.d(TAG, "trackDetail done trackId=$trackId title=${it.title}")
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, "trackDetail failed trackId=$trackId", throwable)
                throw throwable
            }
        }
    }

    override suspend fun resolve(
        track: MusicTrack,
        unavailablePolicy: UnavailablePlaybackPolicy,
        smartReplacementProviderIds: Set<String>,
        smartReplacementMinScore: Double,
        smartReplacementUseOriginalMetadata: Boolean,
        smartReplacementUseOriginalLyrics: Boolean,
    ): PlaybackPayload {
        initialize()
        return withContext(Dispatchers.IO) {
            val trackId = track.providerId ?: track.id
            try {
                val policy = currentAudioQualityPolicy()
                Log.d(
                    TAG,
                    "resolve start trackId=$trackId policy=${policy.policy} " +
                        "unavailablePolicy=$unavailablePolicy smartReplacementProviderIds=$smartReplacementProviderIds " +
                        "smartReplacementMinScore=$smartReplacementMinScore",
                )
                val raw = requireNotNull(bridge)
                    .callAttr(
                        "resolve",
                        trackId,
                        policy.policy,
                        unavailablePolicy == UnavailablePlaybackPolicy.SmartReplace,
                        smartReplacementProviderIdsJson(smartReplacementProviderIds),
                        smartReplacementMinScore,
                        smartReplacementUseOriginalMetadata,
                        smartReplacementUseOriginalLyrics,
                    )
                    .toString()
                JSONObject(raw).toPayload(track).also {
                    Log.d(TAG, it.resolveLog(trackId))
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, "resolve failed trackId=$trackId", throwable)
                throw throwable
            }
        }
    }

    override suspend fun updateAudioQualityPolicies(wifiPolicy: AudioQualityPolicy, cellularPolicy: AudioQualityPolicy) {
        wifiAudioQualityPolicy = wifiPolicy
        cellularAudioQualityPolicy = cellularPolicy
    }

    override suspend fun providerCapabilities(): List<ProviderCapabilities> {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge).callAttr("provider_capabilities").toString()
            val array = JSONObject(raw).getJSONArray("providers")
            List(array.length()) { index -> array.getJSONObject(index).toCapabilities() }
        }
    }

    override suspend fun authState(providerId: String): ProviderAuthState {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge).callAttr("provider_auth_state", providerId).toString()
            JSONObject(raw).toAuthState(providerId)
        }
    }

    override suspend fun refreshAuthState(providerId: String): ProviderAuthState {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge).callAttr("provider_auth_state_with_user", providerId).toString()
            JSONObject(raw).toAuthState(providerId)
        }
    }

    override suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge).callAttr("provider_login_with_cookies", providerId, cookiesJson).toString()
            JSONObject(raw).toAuthState(providerId)
        }
    }

    override suspend fun loginWithHeaders(providerId: String, authorization: String, cookie: String): ProviderAuthState {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge)
                .callAttr("provider_login_with_headers", providerId, authorization, cookie)
                .toString()
            JSONObject(raw).toAuthState(providerId)
        }
    }

    override suspend fun loginWithYtmusicHeaderFile(headerFileJson: String): ProviderAuthState {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge)
                .callAttr("provider_login_with_ytmusic_headerfile", headerFileJson)
                .toString()
            JSONObject(raw).toAuthState("ytmusic")
        }
    }

    override suspend fun logout(providerId: String): ProviderAuthState {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge).callAttr("provider_logout", providerId).toString()
            JSONObject(raw).toAuthState(providerId)
        }
    }

    override suspend fun features(): List<ProviderFeature> {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge).callAttr("features").toString()
            val array = JSONObject(raw).getJSONArray("features")
            List(array.length()) { index -> array.getJSONObject(index).toFeature() }
        }
    }

    override suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection =
        loadFeaturePage(feature, offset = 0, limit = PROVIDER_PAGE_SIZE)

    override suspend fun loadFeaturePage(feature: ProviderFeature, offset: Int, limit: Int): ProviderContentSection {
        initialize()
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "loadFeature start featureId=${feature.id} offset=$offset limit=$limit")
                val raw = requireNotNull(bridge).callAttr("load_feature", feature.id, offset, limit).toString()
                JSONObject(raw).toContentSection(feature).also {
                    Log.d(
                        TAG,
                        "loadFeature done featureId=${feature.id} tracks=${it.tracks.size} playlists=${it.playlists.size} " +
                            "loginRequired=${it.isLoginRequired} hasMore=${it.hasMore}",
                    )
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, "loadFeature failed featureId=${feature.id}", throwable)
                throw throwable
            }
        }
    }

    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> {
        initialize()
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "playlistTracks start playlistId=${playlist.id} title=${playlist.title}")
                val raw = requireNotNull(bridge).callAttr("playlist_tracks", playlist.id).toString()
                val array = JSONObject(raw).getJSONArray("tracks")
                List(array.length()) { index -> array.getJSONObject(index).toTrack() }.also {
                    Log.d(TAG, "playlistTracks done playlistId=${playlist.id} count=${it.size}")
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, "playlistTracks failed playlistId=${playlist.id}", throwable)
                throw throwable
            }
        }
    }

    override suspend fun playlistDetail(playlist: ProviderPlaylist): ProviderPlaylistDetail =
        playlistDetailPage(playlist, offset = 0, limit = PROVIDER_PAGE_SIZE)

    override suspend fun playlistDetailPage(playlist: ProviderPlaylist, offset: Int, limit: Int): ProviderPlaylistDetail {
        initialize()
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "playlistDetail start playlistId=${playlist.id} title=${playlist.title} offset=$offset limit=$limit")
                val raw = requireNotNull(bridge).callAttr("playlist_detail", playlist.id, offset, limit).toString()
                JSONObject(raw).toPlaylistDetail(playlist).also {
                    Log.d(TAG, "playlistDetail done playlistId=${playlist.id} count=${it.tracks.size} hasMore=${it.tracksHasMore}")
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, "playlistDetail failed playlistId=${playlist.id}", throwable)
                throw throwable
            }
        }
    }

    override suspend fun playlistOperationTargets(track: MusicTrack): List<ProviderPlaylist> {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge)
                .callAttr("playlist_operation_targets", track.providerId ?: track.id)
                .toString()
            val array = JSONObject(raw).getJSONArray("playlists")
            List(array.length()) { index -> array.getJSONObject(index).toPlaylist() }
        }
    }

    override suspend fun addTrackToPlaylist(playlist: ProviderPlaylist, track: MusicTrack): ProviderMutationResult {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge)
                .callAttr("playlist_add_song", playlist.id, track.providerId ?: track.id)
                .toString()
            JSONObject(raw).toMutationResult()
        }
    }

    override suspend fun removeTrackFromPlaylist(playlist: ProviderPlaylist, track: MusicTrack): ProviderMutationResult {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge)
                .callAttr("playlist_remove_song", playlist.id, track.providerId ?: track.id)
                .toString()
            JSONObject(raw).toMutationResult()
        }
    }

    override suspend fun createPlaylist(providerId: String, name: String): ProviderMutationResult {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge).callAttr("playlist_create", providerId, name).toString()
            JSONObject(raw).optJSONObject("result")?.toMutationResult()
                ?: JSONObject(raw).toMutationResult()
        }
    }

    override suspend fun deletePlaylist(playlist: ProviderPlaylist): ProviderMutationResult {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge).callAttr("playlist_delete", playlist.id).toString()
            JSONObject(raw).toMutationResult()
        }
    }

    override suspend fun setSongDisliked(track: MusicTrack, disliked: Boolean): ProviderMutationResult {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge)
                .callAttr("dislike_song", track.providerId ?: track.id, disliked)
                .toString()
            JSONObject(raw).toMutationResult()
        }
    }

    override suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack> {
        initialize()
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "mediaItemTracks start itemId=${item.id} title=${item.title}")
                val raw = requireNotNull(bridge).callAttr("media_item_tracks", item.id).toString()
                val array = JSONObject(raw).getJSONArray("tracks")
                List(array.length()) { index -> array.getJSONObject(index).toTrack() }.also {
                    Log.d(TAG, "mediaItemTracks done itemId=${item.id} count=${it.size}")
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, "mediaItemTracks failed itemId=${item.id}", throwable)
                throw throwable
            }
        }
    }

    override suspend fun mediaItemDetail(item: ProviderMediaItem): ProviderMediaItemDetail =
        mediaItemDetailPage(item, tracksOffset = 0, albumsOffset = 0, limit = PROVIDER_PAGE_SIZE)

    override suspend fun mediaItemDetailPage(
        item: ProviderMediaItem,
        tracksOffset: Int,
        albumsOffset: Int,
        limit: Int,
    ): ProviderMediaItemDetail {
        initialize()
        return withContext(Dispatchers.IO) {
            try {
                Log.d(
                    TAG,
                    "mediaItemDetail start itemId=${item.id} title=${item.title} " +
                        "tracksOffset=$tracksOffset albumsOffset=$albumsOffset limit=$limit",
                )
                val raw = requireNotNull(bridge)
                    .callAttr("media_item_detail", item.id, tracksOffset, albumsOffset, limit)
                    .toString()
                JSONObject(raw).toMediaItemDetail(item).also {
                    Log.d(
                        TAG,
                        "mediaItemDetail done itemId=${item.id} tracks=${it.tracks.size} albums=${it.albums.size} " +
                            "tracksHasMore=${it.tracksHasMore} albumsHasMore=${it.albumsHasMore}",
                    )
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, "mediaItemDetail failed itemId=${item.id}", throwable)
                throw throwable
            }
        }
    }

    override suspend fun similarTracks(track: MusicTrack): List<MusicTrack> {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge).callAttr("similar_tracks", track.providerId ?: track.id).toString()
            val array = JSONObject(raw).getJSONArray("tracks")
            List(array.length()) { index -> array.getJSONObject(index).toTrack() }
        }
    }

    override suspend fun hotComments(track: MusicTrack): List<ProviderComment> {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge).callAttr("hot_comments", track.providerId ?: track.id).toString()
            val array = JSONObject(raw).getJSONArray("comments")
            List(array.length()) { index -> array.getJSONObject(index).toProviderComment() }
        }
    }

    override suspend fun trackVideo(track: MusicTrack): ProviderVideo? {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge).callAttr("track_video", track.providerId ?: track.id).toString()
            JSONObject(raw).optJSONObject("video")?.toProviderVideo()
        }
    }

    override suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge).callAttr("video_payload", video.id, "").toString()
            JSONObject(raw).toVideoPlaybackPayload(video)
        }
    }

    private fun JSONObject.toProviderInfo(): ProviderInfo {
        val providerId = optString("provider_id")
        return ProviderInfo(
            providerId = providerId,
            providerName = optString("provider_name").ifBlank { providerId },
            loginConfig = optJSONObject("login_config")?.toLoginConfig(),
            supportedLoginModes = optJSONArray("login_modes").toLoginModes(),
        )
    }

    private fun JSONObject.toSearchResults(): ProviderSearchResults {
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

    private fun JSONObject.toLoginConfig(): ProviderLoginConfig = ProviderLoginConfig(
        loginUrl = optString("login_url"),
        cookieKeyGroups = optJSONArray("cookie_key_groups").toStringGroups(),
    )

    private fun JSONObject.toCapabilities(): ProviderCapabilities {
        val providerId = optString("provider_id")
        return ProviderCapabilities(
            providerId = providerId,
            providerName = optString("provider_name").ifBlank { providerId },
            canAddSongToPlaylist = optBoolean("can_add_song_to_playlist"),
            canRemoveSongFromPlaylist = optBoolean("can_remove_song_from_playlist"),
            canCreatePlaylist = optBoolean("can_create_playlist"),
            canDeletePlaylist = optBoolean("can_delete_playlist"),
            canListDislikedSongs = optBoolean("can_list_disliked_songs"),
            canAddDislikedSong = optBoolean("can_add_disliked_song"),
            canRemoveDislikedSong = optBoolean("can_remove_disliked_song"),
            canFavoritePlaylist = optBoolean("can_favorite_playlist"),
            canUnfavoritePlaylist = optBoolean("can_unfavorite_playlist"),
            canFavoriteArtist = optBoolean("can_favorite_artist"),
            canUnfavoriteArtist = optBoolean("can_unfavorite_artist"),
            canFavoriteAlbum = optBoolean("can_favorite_album"),
            canUnfavoriteAlbum = optBoolean("can_unfavorite_album"),
        )
    }

    private fun JSONObject.toFeature(): ProviderFeature {
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

    private fun JSONObject.toContentSection(fallbackFeature: ProviderFeature): ProviderContentSection {
        val parsedFeature = optJSONObject("feature")?.toFeature() ?: fallbackFeature
        val tracksArray = optJSONArray("tracks") ?: JSONArray()
        val playlistsArray = optJSONArray("playlists") ?: JSONArray()
        val mediaItemsArray = optJSONArray("media_items") ?: JSONArray()
        val videosArray = optJSONArray("videos") ?: JSONArray()
        return ProviderContentSection(
            feature = parsedFeature,
            tracks = List(tracksArray.length()) { index -> tracksArray.getJSONObject(index).toTrack() },
            playlists = List(playlistsArray.length()) { index -> playlistsArray.getJSONObject(index).toPlaylist() },
            mediaItems = List(mediaItemsArray.length()) { index -> mediaItemsArray.getJSONObject(index).toMediaItem() },
            videos = List(videosArray.length()) { index -> videosArray.getJSONObject(index).toProviderVideo() },
            isLoginRequired = optBoolean("is_login_required"),
            errorMessage = optString("error_message").takeIf { it.isNotBlank() },
            nextOffset = optInt("next_offset"),
            hasMore = optBoolean("has_more"),
        )
    }

    private fun JSONObject.toPlaylist(): ProviderPlaylist {
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

    private fun JSONObject.toPlaylistDetail(fallbackPlaylist: ProviderPlaylist): ProviderPlaylistDetail {
        val tracksArray = optJSONArray("tracks") ?: JSONArray()
        return ProviderPlaylistDetail(
            playlist = optJSONObject("playlist")?.toPlaylist() ?: fallbackPlaylist,
            tracks = List(tracksArray.length()) { index -> tracksArray.getJSONObject(index).toTrack() },
            tracksNextOffset = optInt("tracks_next_offset"),
            tracksHasMore = optBoolean("tracks_has_more"),
        )
    }

    private fun JSONObject.toMediaItem(): ProviderMediaItem {
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

    private fun JSONObject.toProviderVideo(): ProviderVideo {
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

    private fun JSONObject.toProviderComment(): ProviderComment {
        return ProviderComment(
            id = optString("id"),
            userName = optString("user_name"),
            content = optString("content"),
            likedCount = optLong("liked_count"),
            timeSeconds = optLong("time"),
        )
    }

    private fun JSONObject.toMediaItemDetail(fallbackItem: ProviderMediaItem): ProviderMediaItemDetail {
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

    private fun JSONObject.toVideoPlaybackPayload(fallbackVideo: ProviderVideo): VideoPlaybackPayload {
        return VideoPlaybackPayload(
            video = optJSONObject("video")?.toProviderVideo() ?: fallbackVideo,
            url = optString("url"),
            videoUrl = optString("video_url"),
            audioUrl = optString("audio_url"),
            headers = optJSONObject("headers").toStringMap(),
            quality = optString("quality").takeIf { it.isNotBlank() },
        )
    }

    private fun JSONObject.toTrack(): MusicTrack {
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

    private fun JSONObject.toPayload(track: MusicTrack): PlaybackPayload {
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

    private fun PlaybackPayload.resolveLog(trackId: String): String {
        if (!isSmartReplacement) {
            return "resolve done trackId=$trackId title=$title source=$source quality=${audioQuality.orEmpty()}"
        }
        return "resolve done smartReplacement trackId=$trackId strategy=${replacementStrategy.orEmpty()} " +
            "score=${replacementScore?.toString().orEmpty()} " +
            "original=${originalProviderName.orEmpty()}:${originalTitle.orEmpty()} " +
            "replacement=${providerName.orEmpty()}:$title source=$source quality=${audioQuality.orEmpty()}"
    }

    private fun JSONObject.toAuthState(providerId: String): ProviderAuthState = ProviderAuthState(
        providerId = optString("provider_id").ifBlank { providerId },
        providerName = optString("provider_name").ifBlank { providerId },
        isLoggedIn = optBoolean("is_logged_in"),
        userName = optString("user_name").takeIf { it.isNotBlank() },
    )

    private fun JSONObject.toMutationResult(): ProviderMutationResult = ProviderMutationResult(
        success = optBoolean("success"),
        message = optString("message"),
    )

    private fun currentAudioQualityPolicy(): AudioQualityPolicy {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return wifiAudioQualityPolicy
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
            cellularAudioQualityPolicy
        } else {
            wifiAudioQualityPolicy
        }
    }

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

    private companion object {
        private const val TAG = "FuoCoreBridge"

        private val AVAILABLE_PROVIDERS = listOf(
            ProviderInfo(
                providerId = "netease",
                providerName = "网易云音乐",
                loginConfig = ProviderLoginConfig(
                    loginUrl = "https://music.163.com",
                    cookieKeyGroups = listOf(listOf("MUSIC_U")),
                ),
            ),
            ProviderInfo(
                providerId = "qqmusic",
                providerName = "QQ 音乐",
                loginConfig = ProviderLoginConfig(
                    loginUrl = "https://y.qq.com",
                    cookieKeyGroups = listOf(
                        listOf("qqmusic_key", "wxuin", "qm_keyst"),
                        listOf("qqmusic_key", "uin", "qm_keyst"),
                    ),
                ),
            ),
            ProviderInfo(
                providerId = "bilibili",
                providerName = "哔哩哔哩",
                loginConfig = ProviderLoginConfig(
                    loginUrl = "https://passport.bilibili.com/h5-app/passport/login?gourl=https%3A%2F%2Fm.bilibili.com%2F",
                    cookieKeyGroups = listOf(listOf("SESSDATA", "bili_jct")),
                ),
            ),
            ProviderInfo(
                providerId = "ytmusic",
                providerName = "YouTube Music",
                loginConfig = ProviderLoginConfig(
                    loginUrl = "https://music.youtube.com",
                    cookieKeyGroups = listOf(listOf("__Secure-3PAPISID")),
                ),
                supportedLoginModes = setOf(ProviderLoginMode.WebView, ProviderLoginMode.Headers),
            ),
        )

        private fun enabledProvidersJson(providerIds: Set<String>): String {
            val array = JSONArray()
            providerIds.forEach { array.put(it) }
            return JSONObject().put("enabled", array).toString()
        }

        private fun smartReplacementProviderIdsJson(providerIds: Set<String>): String {
            val array = JSONArray()
            providerIds.forEach { array.put(it) }
            return array.toString()
        }
    }
}
