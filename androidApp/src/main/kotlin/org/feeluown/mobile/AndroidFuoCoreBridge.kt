package org.feeluown.mobile

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    override suspend fun search(keyword: String): List<MusicTrack> {
        initialize()
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "search start keyword=$keyword")
                val raw = requireNotNull(bridge).callAttr("search", keyword).toString()
                val array = JSONObject(raw).getJSONArray("tracks")
                List(array.length()) { index ->
                    array.getJSONObject(index).toTrack()
                }.also {
                    Log.d(TAG, "search done count=${it.size}")
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, "search failed keyword=$keyword", throwable)
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

    private fun JSONObject.toTrack(): MusicTrack {
        val id = getString("id")
        return MusicTrack(
            id = id,
            title = optString("title"),
            artists = optString("artists"),
            album = optString("album"),
            source = optString("source"),
            sourceType = TrackSourceType.Provider,
            coverUrl = optString("cover_url").takeIf { it.isNotBlank() },
            durationMs = optLong("duration_ms").takeIf { it > 0 },
            providerId = id,
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
