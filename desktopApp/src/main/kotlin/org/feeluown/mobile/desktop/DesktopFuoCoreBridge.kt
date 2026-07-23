package org.feeluown.mobile.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.feeluown.mobile.AudioQualityPolicy
import org.feeluown.mobile.DEFAULT_AUDIO_CACHE_LIMIT_MB
import org.feeluown.mobile.DEFAULT_CELLULAR_AUDIO_QUALITY_POLICY
import org.feeluown.mobile.DEFAULT_ENABLED_PROVIDER_IDS
import org.feeluown.mobile.DEFAULT_SMART_REPLACEMENT_MIN_SCORE
import org.feeluown.mobile.DEFAULT_UNAVAILABLE_PLAYBACK_POLICY
import org.feeluown.mobile.DEFAULT_WIFI_AUDIO_QUALITY_POLICY
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PROVIDER_PAGE_SIZE
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.ProviderAuthState
import org.feeluown.mobile.ProviderCapabilities
import org.feeluown.mobile.ProviderComment
import org.feeluown.mobile.ProviderContentSection
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderInfo
import org.feeluown.mobile.ProviderMediaItem
import org.feeluown.mobile.ProviderMediaItemDetail
import org.feeluown.mobile.ProviderMutationResult
import org.feeluown.mobile.ProviderMusicRepository
import org.feeluown.mobile.ProviderPlaylist
import org.feeluown.mobile.ProviderPlaylistDetail
import org.feeluown.mobile.ProviderResourceState
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.ProviderVideo
import org.feeluown.mobile.UnavailablePlaybackPolicy
import org.feeluown.mobile.VideoPlaybackPayload
import org.json.JSONArray
import org.json.JSONObject

internal class DesktopFuoCoreBridge(
    private val client: DesktopPythonClient = DesktopPythonClient(),
) : ProviderMusicRepository, AutoCloseable {
    @Volatile
    private var enabledProviderIds: Set<String> = DEFAULT_ENABLED_PROVIDER_IDS
    @Volatile
    private var wifiAudioQualityPolicy: AudioQualityPolicy = DEFAULT_WIFI_AUDIO_QUALITY_POLICY
    @Volatile
    private var cellularAudioQualityPolicy: AudioQualityPolicy = DEFAULT_CELLULAR_AUDIO_QUALITY_POLICY

    override suspend fun initialize() {
        client.initialize(enabledProvidersJson(enabledProviderIds))
    }

    override suspend fun availableProviders(): List<ProviderInfo> = AVAILABLE_PROVIDERS

    override suspend fun updateEnabledProviders(providerIds: Set<String>) {
        val nextProviderIds = providerIds
            .ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS }
            .intersect(AVAILABLE_PROVIDERS.map { it.providerId }.toSet())
            .ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS }
        enabledProviderIds = nextProviderIds
        client.initialize(enabledProvidersJson(nextProviderIds))
    }

    override suspend fun providers(): List<ProviderInfo> = callArray("providers", "providers") { it.toProviderInfo() }

    override suspend fun search(keyword: String, providerId: String?): List<MusicTrack> =
        callArray("search", "tracks", listOf(keyword, providerId.orEmpty())) { it.toTrack() }

    override suspend fun searchAll(keyword: String, providerId: String?): ProviderSearchResults {
        initialize()
        return JSONObject(client.call("search_all", listOf(keyword, providerId.orEmpty()))).toSearchResults()
    }

    override suspend fun trackDetail(trackId: String): MusicTrack {
        initialize()
        return JSONObject(client.call("track_detail", listOf(trackId))).getJSONObject("track").toTrack()
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
        val policy = audioQualityPolicyForDesktop(wifiAudioQualityPolicy, cellularAudioQualityPolicy)
        val raw = client.call(
            "resolve",
            listOf(
                track.providerId ?: track.id,
                policy.policy,
                unavailablePolicy == UnavailablePlaybackPolicy.SmartReplace,
                smartReplacementProviderIdsJson(smartReplacementProviderIds),
                smartReplacementMinScore,
                smartReplacementUseOriginalMetadata,
                smartReplacementUseOriginalLyrics,
            ),
        )
        return JSONObject(raw).toPayload(track)
    }

    override suspend fun authState(providerId: String): ProviderAuthState {
        initialize()
        return JSONObject(client.call("provider_auth_state", listOf(providerId))).toAuthState(providerId)
    }

    override suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState {
        initialize()
        return JSONObject(client.call("provider_login_with_cookies", listOf(providerId, cookiesJson))).toAuthState(providerId)
    }

    override suspend fun loginWithHeaders(providerId: String, authorization: String, cookie: String): ProviderAuthState {
        initialize()
        return JSONObject(
            client.call("provider_login_with_headers", listOf(providerId, authorization, cookie)),
        ).toAuthState(providerId)
    }

    override suspend fun logout(providerId: String): ProviderAuthState {
        initialize()
        return JSONObject(client.call("provider_logout", listOf(providerId))).toAuthState(providerId)
    }

    override suspend fun updateAudioQualityPolicies(wifiPolicy: AudioQualityPolicy, cellularPolicy: AudioQualityPolicy) {
        wifiAudioQualityPolicy = wifiPolicy
        cellularAudioQualityPolicy = cellularPolicy
    }

    override suspend fun providerCapabilities(): List<ProviderCapabilities> =
        callArray("provider_capabilities", "providers") { it.toCapabilities() }

    override suspend fun features(): List<ProviderFeature> =
        callArray("features", "features") { it.toFeature() }

    override suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection =
        loadFeaturePage(feature, offset = 0, limit = PROVIDER_PAGE_SIZE)

    override suspend fun loadFeaturePage(feature: ProviderFeature, offset: Int, limit: Int): ProviderContentSection {
        initialize()
        return JSONObject(client.call("load_feature", listOf(feature.id, offset, limit))).toContentSection(feature)
    }

    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> =
        callArray("playlist_tracks", "tracks", listOf(playlist.id)) { it.toTrack() }

    override suspend fun playlistDetail(playlist: ProviderPlaylist): ProviderPlaylistDetail =
        playlistDetailPage(playlist, offset = 0, limit = PROVIDER_PAGE_SIZE)

    override suspend fun playlistDetailPage(
        playlist: ProviderPlaylist,
        offset: Int,
        limit: Int,
    ): ProviderPlaylistDetail {
        initialize()
        return JSONObject(client.call("playlist_detail", listOf(playlist.id, offset, limit))).toPlaylistDetail(playlist)
    }

    override suspend fun playlistOperationTargets(track: MusicTrack): List<ProviderPlaylist> =
        callArray("playlist_operation_targets", "playlists", listOf(track.providerId ?: track.id)) { it.toPlaylist() }

    override suspend fun addTrackToPlaylist(playlist: ProviderPlaylist, track: MusicTrack): ProviderMutationResult {
        initialize()
        return JSONObject(
            client.call("playlist_add_song", listOf(playlist.id, track.providerId ?: track.id)),
        ).toMutationResult()
    }

    override suspend fun removeTrackFromPlaylist(playlist: ProviderPlaylist, track: MusicTrack): ProviderMutationResult {
        initialize()
        return JSONObject(
            client.call("playlist_remove_song", listOf(playlist.id, track.providerId ?: track.id)),
        ).toMutationResult()
    }

    override suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack> =
        callArray("media_item_tracks", "tracks", listOf(item.id)) { it.toTrack() }

    override suspend fun mediaItemDetail(item: ProviderMediaItem): ProviderMediaItemDetail =
        mediaItemDetailPage(item, tracksOffset = 0, albumsOffset = 0, limit = PROVIDER_PAGE_SIZE)

    override suspend fun mediaItemDetailPage(
        item: ProviderMediaItem,
        tracksOffset: Int,
        albumsOffset: Int,
        limit: Int,
    ): ProviderMediaItemDetail {
        initialize()
        return JSONObject(
            client.call("media_item_detail", listOf(item.id, tracksOffset, albumsOffset, limit)),
        ).toMediaItemDetail(item)
    }

    override suspend fun similarTracks(track: MusicTrack): List<MusicTrack> =
        callArray("similar_tracks", "tracks", listOf(track.providerId ?: track.id)) { it.toTrack() }

    override suspend fun hotComments(track: MusicTrack): List<ProviderComment> =
        callArray("hot_comments", "comments", listOf(track.providerId ?: track.id)) { it.toProviderComment() }

    override suspend fun trackVideo(track: MusicTrack): ProviderVideo? {
        initialize()
        return JSONObject(client.call("track_video", listOf(track.providerId ?: track.id))).optJSONObject("video")?.toProviderVideo()
    }

    override suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload {
        initialize()
        return JSONObject(client.call("video_payload", listOf(video.id, ""))).toVideoPlaybackPayload(video)
    }

    override suspend fun resourceState(resourceType: String, resourceId: String): ProviderResourceState =
        ProviderResourceState(providerId = "", resourceId = resourceId)

    override suspend fun setResourceFavorite(
        resourceType: String,
        resourceId: String,
        favorite: Boolean,
    ): ProviderMutationResult = ProviderMutationResult(false, "桌面端暂不支持收藏状态修改")

    override fun close() {
        client.close()
    }

    private suspend fun <T> callArray(
        method: String,
        key: String,
        args: List<Any?> = emptyList(),
        mapper: (JSONObject) -> T,
    ): List<T> {
        initialize()
        val array = JSONObject(client.call(method, args)).optJSONArray(key) ?: JSONArray()
        return List(array.length()) { index -> mapper(array.getJSONObject(index)) }
    }

    private companion object {
        private val AVAILABLE_PROVIDERS = listOf(
            ProviderInfo(
                providerId = "netease",
                providerName = "网易云音乐",
                loginConfig = org.feeluown.mobile.ProviderLoginConfig(
                    loginUrl = "https://music.163.com",
                    cookieKeyGroups = listOf(listOf("MUSIC_U")),
                ),
            ),
            ProviderInfo(
                providerId = "qqmusic",
                providerName = "QQ 音乐",
                loginConfig = org.feeluown.mobile.ProviderLoginConfig(
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
                loginConfig = org.feeluown.mobile.ProviderLoginConfig(
                    loginUrl = "https://passport.bilibili.com/h5-app/passport/login?gourl=https%3A%2F%2Fm.bilibili.com%2F",
                    cookieKeyGroups = listOf(listOf("SESSDATA", "bili_jct")),
                ),
            ),
            ProviderInfo(
                providerId = "ytmusic",
                providerName = "YouTube Music",
                loginConfig = org.feeluown.mobile.ProviderLoginConfig(
                    loginUrl = "https://music.youtube.com",
                    cookieKeyGroups = listOf(listOf("__Secure-3PAPISID")),
                ),
                supportedLoginModes = setOf(
                    org.feeluown.mobile.ProviderLoginMode.WebView,
                    org.feeluown.mobile.ProviderLoginMode.Headers,
                ),
            ),
        )
    }
}
