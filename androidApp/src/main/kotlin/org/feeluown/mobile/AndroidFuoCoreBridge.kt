package org.feeluown.mobile

import android.content.Context
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

    override suspend fun initialize() {
        withContext(Dispatchers.IO) {
            if (bridge != null) return@withContext
            synchronized(this@AndroidFuoCoreBridge) {
                if (bridge != null) return@synchronized
                try {
                    Log.d(TAG, "initialize start")
                    val providers = context.assets.open("providers.json")
                        .bufferedReader()
                        .use { it.readText() }
                    bridge = Python.getInstance()
                        .getModule("fuo_mobile.bridge")
                        .callAttr("create_bridge", providers)
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

    override suspend fun resolve(track: MusicTrack): PlaybackPayload {
        initialize()
        return withContext(Dispatchers.IO) {
            val trackId = track.providerId ?: track.id
            try {
                Log.d(TAG, "resolve start trackId=$trackId")
                val raw = requireNotNull(bridge).callAttr("resolve", trackId).toString()
                JSONObject(raw).toPayload(track).also {
                    Log.d(TAG, "resolve done title=${it.title}")
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, "resolve failed trackId=$trackId", throwable)
                throw throwable
            }
        }
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

    private fun JSONObject.toProviderInfo(): ProviderInfo {
        val providerId = optString("provider_id")
        return ProviderInfo(
            providerId = providerId,
            providerName = optString("provider_name").ifBlank { providerId },
            loginConfig = optJSONObject("login_config")?.toLoginConfig(),
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
        return ProviderContentSection(
            feature = parsedFeature,
            tracks = List(tracksArray.length()) { index -> tracksArray.getJSONObject(index).toTrack() },
            playlists = List(playlistsArray.length()) { index -> playlistsArray.getJSONObject(index).toPlaylist() },
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

    private fun JSONObject.toPayload(track: MusicTrack): PlaybackPayload = PlaybackPayload(
        url = getString("url"),
        title = optString("title").ifBlank { track.title },
        artists = optString("artists").ifBlank { track.artists },
        album = optString("album").ifBlank { track.album },
        source = optString("source").ifBlank { track.source },
        headers = optJSONObject("headers").toStringMap(),
        coverUrl = optString("cover_url").takeIf { it.isNotBlank() } ?: track.coverUrl,
        durationMs = optLong("duration_ms").takeIf { it > 0 } ?: track.durationMs,
        lyrics = optString("lyrics").takeIf { it.isNotBlank() },
    )

    private fun JSONObject.toAuthState(providerId: String): ProviderAuthState = ProviderAuthState(
        providerId = optString("provider_id").ifBlank { providerId },
        providerName = optString("provider_name").ifBlank { providerId },
        isLoggedIn = optBoolean("is_logged_in"),
        userName = optString("user_name").takeIf { it.isNotBlank() },
    )

    private fun JSONArray?.toStringGroups(): List<List<String>> {
        if (this == null) return emptyList()
        return List(length()) { index ->
            val group = getJSONArray(index)
            List(group.length()) { keyIndex -> group.getString(keyIndex) }
        }
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
    }
}
