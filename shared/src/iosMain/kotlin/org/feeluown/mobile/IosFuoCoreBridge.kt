package org.feeluown.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class IosFuoCoreBridge(
    private val pythonRuntime: IosPythonRuntime,
    private val networkStatusOutput: IosNetworkStatusOutput,
) : ProviderMusicRepository {
    private var enabledProviderIds: Set<String> = DEFAULT_ENABLED_PROVIDER_IDS
    private var wifiAudioQualityPolicy: AudioQualityPolicy = DEFAULT_WIFI_AUDIO_QUALITY_POLICY
    private var cellularAudioQualityPolicy: AudioQualityPolicy = DEFAULT_CELLULAR_AUDIO_QUALITY_POLICY

    override suspend fun initialize() {
        updateEnabledProviders(enabledProviderIds)
    }

    override suspend fun availableProviders(): List<ProviderInfo> = AVAILABLE_PROVIDERS

    override suspend fun updateEnabledProviders(providerIds: Set<String>) {
        enabledProviderIds = providerIds
            .ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS }
            .intersect(AVAILABLE_PROVIDERS.map { it.providerId }.toSet())
            .ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS }
        val providersJson = enabledProviderIds.joinToString(prefix = "{\"enabled\":[", postfix = "]}") { "\"$it\"" }
        pythonRuntime.checked(pythonRuntime.createBridge(providersJson))
    }

    override suspend fun providers(): List<ProviderInfo> {
        initialize()
        return withContext(Dispatchers.Default) {
            pythonRuntime.checked(pythonRuntime.call("providers", emptyList()))
                .rootObject()
                .array("providers")
                .map { it.jsonObject.toProviderInfo() }
        }
    }

    override suspend fun search(keyword: String, providerId: String?): List<MusicTrack> = withContext(Dispatchers.Default) {
        initialize()
        pythonRuntime.checked(
            pythonRuntime.call("search", listOf(keyword, providerId.orEmpty())),
        ).rootObject().array("tracks").map { it.jsonObject.toTrack() }
    }

    override suspend fun searchAll(keyword: String, providerId: String?): ProviderSearchResults = withContext(Dispatchers.Default) {
        initialize()
        val root = pythonRuntime.checked(
            pythonRuntime.call("search_all", listOf(keyword, providerId.orEmpty())),
        ).rootObject()
        ProviderSearchResults(
            tracks = root.array("tracks").map { it.jsonObject.toTrack() },
            playlists = root.array("playlists").map { it.jsonObject.toPlaylist() },
            artists = root.array("artists").map { it.jsonObject.toMediaItem() },
            albums = root.array("albums").map { it.jsonObject.toMediaItem() },
            videos = root.array("videos").map { it.jsonObject.toProviderVideo() },
            errorMessage = root.string("error_message").takeIf { it.isNotBlank() },
        )
    }

    override suspend fun trackDetail(trackId: String): MusicTrack = withContext(Dispatchers.Default) {
        initialize()
        pythonRuntime.checked(pythonRuntime.call("track_detail", listOf(trackId)))
            .rootObject()
            .objectValue("track")
            ?.toTrack()
            ?: throw IllegalStateException("音源未返回歌曲详情")
    }

    override suspend fun resolve(
        track: MusicTrack,
        unavailablePolicy: UnavailablePlaybackPolicy,
        smartReplacementProviderIds: Set<String>,
        smartReplacementMinScore: Double,
        smartReplacementUseOriginalMetadata: Boolean,
        smartReplacementUseOriginalLyrics: Boolean,
    ): PlaybackPayload = withContext(Dispatchers.Default) {
        initialize()
        val trackId = track.providerId ?: track.id
        val policy = currentAudioQualityPolicy().policy
        pythonRuntime.checked(
            pythonRuntime.call(
                "resolve",
                listOf(
                    trackId,
                    policy,
                    "__FUO_BOOL__:${unavailablePolicy == UnavailablePlaybackPolicy.SmartReplace}",
                    smartReplacementProviderIds.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
                        .takeIf { smartReplacementProviderIds.isNotEmpty() } ?: "[]",
                    "__FUO_DOUBLE__:$smartReplacementMinScore",
                    "__FUO_BOOL__:$smartReplacementUseOriginalMetadata",
                    "__FUO_BOOL__:$smartReplacementUseOriginalLyrics",
                ),
            ),
        ).rootObject().toPayload(track)
    }

    override suspend fun authState(providerId: String): ProviderAuthState {
        initialize()
        return pythonRuntime.checked(
            pythonRuntime.call("provider_auth_state", listOf(providerId)),
        ).toAuthState(providerId)
    }

    override suspend fun refreshAuthState(providerId: String): ProviderAuthState {
        initialize()
        return pythonRuntime.checked(
            pythonRuntime.call("provider_auth_state_with_user", listOf(providerId)),
        ).toAuthState(providerId)
    }

    override suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState = withContext(Dispatchers.Default) {
        initialize()
        pythonRuntime.checked(
            pythonRuntime.call("provider_login_with_cookies", listOf(providerId, cookiesJson)),
        ).toAuthState(providerId)
    }

    override suspend fun loginWithHeaders(
        providerId: String,
        authorization: String,
        cookie: String,
    ): ProviderAuthState = withContext(Dispatchers.Default) {
        initialize()
        pythonRuntime.checked(
            pythonRuntime.call("provider_login_with_headers", listOf(providerId, authorization, cookie)),
        ).toAuthState(providerId)
    }

    override suspend fun logout(providerId: String): ProviderAuthState {
        initialize()
        return pythonRuntime.checked(
            pythonRuntime.call("provider_logout", listOf(providerId)),
        ).toAuthState(providerId)
    }

    override suspend fun updateAudioQualityPolicies(wifiPolicy: AudioQualityPolicy, cellularPolicy: AudioQualityPolicy) {
        wifiAudioQualityPolicy = wifiPolicy
        cellularAudioQualityPolicy = cellularPolicy
    }

    override suspend fun providerCapabilities(): List<ProviderCapabilities> = withContext(Dispatchers.Default) {
        initialize()
        pythonRuntime.checked(pythonRuntime.call("provider_capabilities", emptyList()))
            .rootObject()
            .array("providers")
            .map { it.jsonObject.toCapabilities() }
    }

    override suspend fun features(): List<ProviderFeature> {
        initialize()
        return withContext(Dispatchers.Default) {
            pythonRuntime.checked(pythonRuntime.call("features", emptyList()))
                .rootObject()
                .array("features")
                .map { it.jsonObject.toFeature() }
        }
    }

    override suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection =
        loadFeaturePage(feature, offset = 0, limit = PROVIDER_PAGE_SIZE)

    override suspend fun loadFeaturePage(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        initialize()
        return withContext(Dispatchers.Default) {
            pythonRuntime.checked(
                pythonRuntime.call(
                    "load_feature",
                    listOf(feature.id, "__FUO_INT__:$offset", "__FUO_INT__:$limit"),
                ),
            ).rootObject().toContentSection(feature)
        }
    }

    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> = withContext(Dispatchers.Default) {
        initialize()
        pythonRuntime.checked(pythonRuntime.call("playlist_tracks", listOf(playlist.id)))
            .rootObject()
            .array("tracks")
            .map { it.jsonObject.toTrack() }
    }

    override suspend fun playlistDetail(playlist: ProviderPlaylist): ProviderPlaylistDetail =
        playlistDetailPage(playlist, offset = 0, limit = PROVIDER_PAGE_SIZE)

    override suspend fun playlistDetailPage(
        playlist: ProviderPlaylist,
        offset: Int,
        limit: Int,
    ): ProviderPlaylistDetail = withContext(Dispatchers.Default) {
        initialize()
        pythonRuntime.checked(
            pythonRuntime.call(
                "playlist_detail",
                listOf(playlist.id, "__FUO_INT__:$offset", "__FUO_INT__:$limit"),
            ),
        ).rootObject().toPlaylistDetail(playlist)
    }

    override suspend fun playlistOperationTargets(track: MusicTrack): List<ProviderPlaylist> = withContext(Dispatchers.Default) {
        initialize()
        pythonRuntime.checked(
            pythonRuntime.call("playlist_operation_targets", listOf(track.providerId ?: track.id)),
        ).rootObject().array("playlists").map { it.jsonObject.toPlaylist() }
    }

    override suspend fun addTrackToPlaylist(
        playlist: ProviderPlaylist,
        track: MusicTrack,
    ): ProviderMutationResult = withContext(Dispatchers.Default) {
        initialize()
        pythonRuntime.checked(
            pythonRuntime.call("playlist_add_song", listOf(playlist.id, track.providerId ?: track.id)),
        ).rootObject().toMutationResult()
    }

    override suspend fun removeTrackFromPlaylist(
        playlist: ProviderPlaylist,
        track: MusicTrack,
    ): ProviderMutationResult = withContext(Dispatchers.Default) {
        initialize()
        pythonRuntime.checked(
            pythonRuntime.call("playlist_remove_song", listOf(playlist.id, track.providerId ?: track.id)),
        ).rootObject().toMutationResult()
    }

    override suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack> = withContext(Dispatchers.Default) {
        initialize()
        pythonRuntime.checked(pythonRuntime.call("media_item_tracks", listOf(item.id)))
            .rootObject()
            .array("tracks")
            .map { it.jsonObject.toTrack() }
    }

    override suspend fun mediaItemDetail(item: ProviderMediaItem): ProviderMediaItemDetail =
        mediaItemDetailPage(item, tracksOffset = 0, albumsOffset = 0, limit = PROVIDER_PAGE_SIZE)

    override suspend fun mediaItemDetailPage(
        item: ProviderMediaItem,
        tracksOffset: Int,
        albumsOffset: Int,
        limit: Int,
    ): ProviderMediaItemDetail = withContext(Dispatchers.Default) {
        initialize()
        pythonRuntime.checked(
            pythonRuntime.call(
                "media_item_detail",
                listOf(
                    item.id,
                    "__FUO_INT__:$tracksOffset",
                    "__FUO_INT__:$albumsOffset",
                    "__FUO_INT__:$limit",
                ),
            ),
        ).rootObject().toMediaItemDetail(item)
    }

    override suspend fun similarTracks(track: MusicTrack): List<MusicTrack> = withContext(Dispatchers.Default) {
        initialize()
        pythonRuntime.checked(
            pythonRuntime.call("similar_tracks", listOf(track.providerId ?: track.id)),
        ).rootObject().array("tracks").map { it.jsonObject.toTrack() }
    }

    override suspend fun hotComments(track: MusicTrack): List<ProviderComment> = withContext(Dispatchers.Default) {
        initialize()
        pythonRuntime.checked(
            pythonRuntime.call("hot_comments", listOf(track.providerId ?: track.id)),
        ).rootObject().array("comments").map { it.jsonObject.toProviderComment() }
    }

    override suspend fun trackVideo(track: MusicTrack): ProviderVideo? = withContext(Dispatchers.Default) {
        initialize()
        pythonRuntime.checked(
            pythonRuntime.call("track_video", listOf(track.providerId ?: track.id)),
        ).rootObject().objectValue("video")?.toProviderVideo()
    }

    override suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload = withContext(Dispatchers.Default) {
        initialize()
        pythonRuntime.checked(
            pythonRuntime.call("video_payload", listOf(video.id, "")),
        ).rootObject().toVideoPlaybackPayload(video)
    }

    private fun providerName(providerId: String): String {
        return AVAILABLE_PROVIDERS.firstOrNull { it.providerId == providerId }?.providerName ?: providerId
    }

    private fun providerFeatures(providerId: String): List<ProviderFeature> {
        val providerName = providerName(providerId)
        return when (providerId) {
            "netease" -> listOf(
                feature(providerId, providerName, "netease_daily_songs", "每日推荐歌曲", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, true),
                feature(providerId, providerName, "netease_daily_playlists", "推荐歌单", ProviderFeatureCategory.Recommend, ProviderContentType.Playlists, true),
                feature(providerId, providerName, "netease_radio", "私人 FM", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, true),
                feature(providerId, providerName, "netease_toplists", "排行榜", ProviderFeatureCategory.Music, ProviderContentType.Playlists, false),
                feature(providerId, providerName, "netease_user_playlists", "我的歌单", ProviderFeatureCategory.MinePlaylists, ProviderContentType.Playlists, true),
                feature(providerId, providerName, "netease_favorite_songs", "收藏歌曲", ProviderFeatureCategory.Mine, ProviderContentType.Songs, true),
                feature(providerId, providerName, "netease_favorite_playlists", "收藏歌单", ProviderFeatureCategory.MineFavoritePlaylists, ProviderContentType.Playlists, true),
            )
            "qqmusic" -> listOf(
                feature(providerId, providerName, "qqmusic_daily_songs", "每日推荐歌曲", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, true),
                feature(providerId, providerName, "qqmusic_daily_playlists", "推荐歌单", ProviderFeatureCategory.Recommend, ProviderContentType.Playlists, true),
                feature(providerId, providerName, "qqmusic_radio", "私人 FM", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, true),
                feature(providerId, providerName, "qqmusic_user_playlists", "我的歌单", ProviderFeatureCategory.MinePlaylists, ProviderContentType.Playlists, true),
            )
            "bilibili" -> listOf(
                feature(providerId, providerName, "bilibili_user_playlists", "我的歌单", ProviderFeatureCategory.MinePlaylists, ProviderContentType.Playlists, true),
                feature(providerId, providerName, "bilibili_favorite_playlists", "收藏歌单", ProviderFeatureCategory.MineFavoritePlaylists, ProviderContentType.Playlists, true),
            )
            "ytmusic" -> listOf(
                feature(providerId, providerName, "ytmusic_daily_songs", "每日推荐歌曲", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, false),
                feature(providerId, providerName, "ytmusic_daily_playlists", "推荐歌单", ProviderFeatureCategory.Recommend, ProviderContentType.Playlists, false),
                feature(providerId, providerName, "ytmusic_toplists", "排行榜", ProviderFeatureCategory.Music, ProviderContentType.Playlists, false),
            )
            else -> emptyList()
        }
    }

    private fun feature(
        providerId: String,
        providerName: String,
        id: String,
        title: String,
        category: ProviderFeatureCategory,
        contentType: ProviderContentType,
        requiresLogin: Boolean,
    ) = ProviderFeature(
        id = id,
        providerId = providerId,
        providerName = providerName,
        title = title,
        category = category,
        contentType = contentType,
        requiresLogin = requiresLogin,
    )

    private fun pythonUnavailable(): Nothing {
        throw IllegalStateException("iOS Python 音源运行时尚未接入")
    }

    private fun String.rootObject(): JsonObject = Json.parseToJsonElement(this).jsonObject

    private fun JsonObject.array(key: String): JsonArray = this[key]?.jsonArray ?: JsonArray(emptyList())

    private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.boolean(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: false

    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0

    private fun JsonObject.longOrNull(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.toProviderInfo(): ProviderInfo {
        val providerId = string("provider_id")
        val loginConfig = objectValue("login_config")?.let { config ->
            ProviderLoginConfig(
                loginUrl = config.string("login_url"),
                cookieKeyGroups = config.array("cookie_key_groups").map { group ->
                    group.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
                },
            )
        }
        val loginModes = array("login_modes")
            .mapNotNull { element ->
                element.jsonPrimitive.contentOrNull?.let { raw ->
                    runCatching { ProviderLoginMode.valueOf(raw) }.getOrNull()
                }
            }
            .toSet()
            .ifEmpty { setOf(ProviderLoginMode.WebView, ProviderLoginMode.Cookie) }
        return ProviderInfo(
            providerId = providerId,
            providerName = string("provider_name").ifBlank { providerName(providerId) },
            loginConfig = loginConfig,
            supportedLoginModes = loginModes,
        )
    }

    private fun JsonObject.toCapabilities(): ProviderCapabilities {
        val providerId = string("provider_id")
        return ProviderCapabilities(
            providerId = providerId,
            providerName = string("provider_name").ifBlank { providerName(providerId) },
            canAddSongToPlaylist = boolean("can_add_song_to_playlist"),
            canRemoveSongFromPlaylist = boolean("can_remove_song_from_playlist"),
            canFavoritePlaylist = boolean("can_favorite_playlist"),
            canUnfavoritePlaylist = boolean("can_unfavorite_playlist"),
            canFavoriteArtist = boolean("can_favorite_artist"),
            canUnfavoriteArtist = boolean("can_unfavorite_artist"),
            canFavoriteAlbum = boolean("can_favorite_album"),
            canUnfavoriteAlbum = boolean("can_unfavorite_album"),
        )
    }

    private fun JsonObject.toFeature(): ProviderFeature {
        val providerId = string("provider_id")
        return ProviderFeature(
            id = string("id"),
            providerId = providerId,
            providerName = string("provider_name").ifBlank { providerName(providerId) },
            title = string("title"),
            category = ProviderFeatureCategory.valueOf(string("category")),
            contentType = ProviderContentType.valueOf(string("content_type")),
            requiresLogin = boolean("requires_login"),
        )
    }

    private fun JsonObject.toContentSection(fallbackFeature: ProviderFeature): ProviderContentSection {
        val parsedFeature = this["feature"]?.jsonObject?.toFeature() ?: fallbackFeature
        return ProviderContentSection(
            feature = parsedFeature,
            tracks = array("tracks").map { it.jsonObject.toTrack() },
            playlists = array("playlists").map { it.jsonObject.toPlaylist() },
            mediaItems = array("media_items").map { it.jsonObject.toMediaItem() },
            isLoginRequired = boolean("is_login_required"),
            errorMessage = string("error_message").takeIf { it.isNotBlank() },
            nextOffset = int("next_offset"),
            hasMore = boolean("has_more"),
        )
    }

    private fun JsonObject.toTrack(): MusicTrack {
        val id = string("id")
        val source = string("source")
        return MusicTrack(
            id = id,
            title = string("title"),
            artists = string("artists"),
            album = string("album"),
            source = source,
            sourceType = TrackSourceType.Provider,
            coverUrl = string("cover_url").takeIf { it.isNotBlank() },
            durationMs = longOrNull("duration_ms")?.takeIf { it > 0 },
            providerId = id,
            providerName = string("provider_name").ifBlank { source }.takeIf { it.isNotBlank() },
            artistItemId = string("artist_item_id").takeIf { it.isNotBlank() },
            albumItemId = string("album_item_id").takeIf { it.isNotBlank() },
            artistItems = array("artist_items").map { it.jsonObject.toMediaItem() },
            providerUrl = string("provider_url").takeIf { it.isNotBlank() },
        )
    }

    private fun JsonObject.toPlaylist(): ProviderPlaylist {
        val providerId = string("provider_id")
        return ProviderPlaylist(
            id = string("id"),
            title = string("title"),
            providerId = providerId,
            providerName = string("provider_name").ifBlank { providerName(providerId) },
            coverUrl = string("cover_url").takeIf { it.isNotBlank() },
            description = string("description"),
            playCount = longOrNull("play_count")?.takeIf { it > 0 },
            providerUrl = string("provider_url").takeIf { it.isNotBlank() },
            trackCount = this["track_count"]?.jsonPrimitive?.intOrNull,
        )
    }

    private fun JsonObject.toPlaylistDetail(fallbackPlaylist: ProviderPlaylist): ProviderPlaylistDetail {
        return ProviderPlaylistDetail(
            playlist = objectValue("playlist")?.toPlaylist() ?: fallbackPlaylist,
            tracks = array("tracks").map { it.jsonObject.toTrack() },
            tracksNextOffset = int("tracks_next_offset"),
            tracksHasMore = boolean("tracks_has_more"),
        )
    }

    private fun JsonObject.toMediaItem(): ProviderMediaItem {
        val providerId = string("provider_id")
        return ProviderMediaItem(
            id = string("id"),
            title = string("title"),
            providerId = providerId,
            providerName = string("provider_name").ifBlank { providerName(providerId) },
            type = ProviderMediaItemType.valueOf(string("type")),
            coverUrl = string("cover_url").takeIf { it.isNotBlank() },
            description = string("description"),
            providerUrl = string("provider_url").takeIf { it.isNotBlank() },
            trackCount = this["track_count"]?.jsonPrimitive?.intOrNull,
            albumCount = this["album_count"]?.jsonPrimitive?.intOrNull,
        )
    }

    private fun JsonObject.toProviderVideo(): ProviderVideo {
        val providerId = string("provider_id")
        return ProviderVideo(
            id = string("id"),
            title = string("title"),
            artists = string("artists"),
            providerId = providerId,
            providerName = string("provider_name").ifBlank { providerName(providerId) },
            coverUrl = string("cover_url").takeIf { it.isNotBlank() },
            durationMs = longOrNull("duration_ms")?.takeIf { it > 0 },
            providerUrl = string("provider_url").takeIf { it.isNotBlank() },
        )
    }

    private fun JsonObject.toProviderComment(): ProviderComment = ProviderComment(
        id = string("id"),
        userName = string("user_name"),
        content = string("content"),
        likedCount = longOrNull("liked_count") ?: 0,
        timeSeconds = longOrNull("time") ?: 0,
    )

    private fun JsonObject.toMediaItemDetail(fallbackItem: ProviderMediaItem): ProviderMediaItemDetail {
        return ProviderMediaItemDetail(
            item = objectValue("item")?.toMediaItem() ?: fallbackItem,
            tracks = array("tracks").map { it.jsonObject.toTrack() },
            albums = array("albums").map { it.jsonObject.toMediaItem() },
            tracksNextOffset = int("tracks_next_offset"),
            tracksHasMore = boolean("tracks_has_more"),
            albumsNextOffset = int("albums_next_offset"),
            albumsHasMore = boolean("albums_has_more"),
        )
    }

    private fun JsonObject.toVideoPlaybackPayload(fallbackVideo: ProviderVideo): VideoPlaybackPayload {
        return VideoPlaybackPayload(
            video = objectValue("video")?.toProviderVideo() ?: fallbackVideo,
            url = string("url"),
            videoUrl = string("video_url"),
            audioUrl = string("audio_url"),
            headers = objectValue("headers")?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() } ?: emptyMap(),
            quality = string("quality").takeIf { it.isNotBlank() },
        )
    }

    private fun JsonObject.toMutationResult(): ProviderMutationResult = ProviderMutationResult(
        success = boolean("success"),
        message = string("message"),
    )

    private fun JsonObject.toPayload(track: MusicTrack): PlaybackPayload {
        val smartReplacement = boolean("smart_replacement")
        return PlaybackPayload(
            url = string("url"),
            title = string("title").ifBlank { track.title },
            artists = string("artists").ifBlank { track.artists },
            album = string("album").ifBlank { track.album },
            source = string("source").ifBlank { track.source },
            headers = this["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() } ?: emptyMap(),
            coverUrl = string("cover_url").takeIf { it.isNotBlank() } ?: track.coverUrl,
            durationMs = longOrNull("duration_ms") ?: track.durationMs,
            lyrics = string("lyrics").takeIf { it.isNotBlank() },
            audioQuality = string("audio_quality").takeIf { it.isNotBlank() },
            providerName = string("provider_name").takeIf { it.isNotBlank() } ?: track.providerName,
            isSmartReplacement = smartReplacement,
            originalTitle = string("original_title").takeIf { smartReplacement && it.isNotBlank() },
            originalProviderName = string("original_provider_name").takeIf { smartReplacement && it.isNotBlank() },
            originalCoverUrl = string("original_cover_url").takeIf { smartReplacement && it.isNotBlank() },
            replacementTitle = string("replacement_title").takeIf { smartReplacement && it.isNotBlank() },
            replacementArtists = string("replacement_artists").takeIf { smartReplacement && it.isNotBlank() },
            replacementSource = string("replacement_source").takeIf { smartReplacement && it.isNotBlank() },
            replacementProviderName = string("replacement_provider_name").takeIf { smartReplacement && it.isNotBlank() },
            replacementCoverUrl = string("replacement_cover_url").takeIf { smartReplacement && it.isNotBlank() },
            replacementStrategy = string("standby_strategy")
                .ifBlank { string("replacement_strategy") }
                .takeIf { smartReplacement && it.isNotBlank() },
            replacementScore = (this["standby_score"] ?: this["replacement_score"])
                ?.jsonPrimitive
                ?.doubleOrNull
                ?.takeIf { smartReplacement },
            parts = array("parts").map { element ->
                val part = element.jsonObject
                PlaybackPart(
                    id = part.string("id"),
                    title = part.string("title"),
                    durationMs = part.longOrNull("duration_ms")?.takeIf { it > 0 },
                )
            }.filter { it.id.isNotBlank() },
            currentPartIndex = int("current_part_index").takeIf { it >= 0 } ?: -1,
        )
    }

    private fun currentAudioQualityPolicy(): AudioQualityPolicy {
        return if (networkStatusOutput.isCellularConnection()) {
            cellularAudioQualityPolicy
        } else {
            wifiAudioQualityPolicy
        }
    }

    private fun String.toAuthState(providerId: String): ProviderAuthState {
        fun stringValue(key: String): String? = Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"")
            .find(this)
            ?.groupValues
            ?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
        fun booleanValue(key: String): Boolean = Regex("\"$key\"\\s*:\\s*(true|false)")
            .find(this)
            ?.groupValues
            ?.get(1)
            ?.toBoolean() == true
        return ProviderAuthState(
            providerId = providerId,
            providerName = stringValue("provider_name") ?: providerName(providerId),
            isLoggedIn = booleanValue("is_logged_in"),
            userName = stringValue("user_name"),
        )
    }

    private companion object {
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
                supportedLoginModes = setOf(ProviderLoginMode.Headers),
            ),
        )
    }
}
