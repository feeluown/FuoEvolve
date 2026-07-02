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
        withContext(Dispatchers.IO) {
            synchronized(this@AndroidFuoCoreBridge) {
                if (bridge != null && enabledProviderIds == nextProviderIds) return@synchronized
                try {
                    Log.d(TAG, "initialize start providers=$nextProviderIds")
                    val nextBridge = Python.getInstance()
                        .getModule("fuo_mobile.bridge")
                        .callAttr("create_bridge", enabledProvidersJson(nextProviderIds))
                    bridge = nextBridge
                    enabledProviderIds = nextProviderIds
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

    override suspend fun resolve(
        track: MusicTrack,
        unavailablePolicy: UnavailablePlaybackPolicy,
    ): PlaybackPayload {
        initialize()
        return withContext(Dispatchers.IO) {
            val trackId = track.providerId ?: track.id
            try {
                val policy = currentAudioQualityPolicy()
                Log.d(TAG, "resolve start trackId=$trackId policy=${policy.policy} unavailablePolicy=$unavailablePolicy")
                val raw = requireNotNull(bridge)
                    .callAttr(
                        "resolve",
                        trackId,
                        policy.policy,
                        unavailablePolicy == UnavailablePlaybackPolicy.SmartReplace,
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

    override suspend fun authState(providerId: String): ProviderAuthState {
        initialize()
        return withContext(Dispatchers.IO) {
            val raw = requireNotNull(bridge).callAttr("provider_auth_state", providerId).toString()
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

    override suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection {
        initialize()
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "loadFeature start featureId=${feature.id}")
                val raw = requireNotNull(bridge).callAttr("load_feature", feature.id).toString()
                JSONObject(raw).toContentSection(feature).also {
                    Log.d(
                        TAG,
                        "loadFeature done featureId=${feature.id} tracks=${it.tracks.size} playlists=${it.playlists.size} loginRequired=${it.isLoginRequired}",
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

    private fun JSONObject.toProviderInfo(): ProviderInfo {
        val providerId = optString("provider_id")
        return ProviderInfo(
            providerId = providerId,
            providerName = optString("provider_name").ifBlank { providerId },
            loginConfig = optJSONObject("login_config")?.toLoginConfig(),
            supportedLoginModes = optJSONArray("login_modes").toLoginModes(),
        )
    }

    private fun JSONObject.toLoginConfig(): ProviderLoginConfig = ProviderLoginConfig(
        loginUrl = optString("login_url"),
        cookieKeyGroups = optJSONArray("cookie_key_groups").toStringGroups(),
    )

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
        return ProviderContentSection(
            feature = parsedFeature,
            tracks = List(tracksArray.length()) { index -> tracksArray.getJSONObject(index).toTrack() },
            playlists = List(playlistsArray.length()) { index -> playlistsArray.getJSONObject(index).toPlaylist() },
            mediaItems = List(mediaItemsArray.length()) { index -> mediaItemsArray.getJSONObject(index).toMediaItem() },
            isLoginRequired = optBoolean("is_login_required"),
            errorMessage = optString("error_message").takeIf { it.isNotBlank() },
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
            coverUrl = optString("cover_url").takeIf { it.isNotBlank() } ?: track.coverUrl,
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
            replacementStrategy = optString("standby_strategy").takeIf { smartReplacement && it.isNotBlank() },
            replacementScore = optDouble("standby_score").takeIf { smartReplacement && has("standby_score") },
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
                    loginUrl = "https://www.bilibili.com",
                    cookieKeyGroups = listOf(listOf("SESSDATA", "bili_jct")),
                ),
            ),
            ProviderInfo(
                providerId = "ytmusic",
                providerName = "YouTube Music",
                supportedLoginModes = setOf(ProviderLoginMode.Headers),
            ),
        )

        private fun enabledProvidersJson(providerIds: Set<String>): String {
            val array = JSONArray()
            providerIds.forEach { array.put(it) }
            return JSONObject().put("enabled", array).toString()
        }
    }
}
