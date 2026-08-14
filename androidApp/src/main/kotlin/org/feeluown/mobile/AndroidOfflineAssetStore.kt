package org.feeluown.mobile

import android.content.Context
import org.json.JSONObject

internal class AndroidOfflineAssetStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun all(): List<OfflineAsset> {
        return preferences.all.entries.mapNotNull { (key, value) ->
            if (!key.startsWith(ASSET_KEY_PREFIX)) return@mapNotNull null
            (value as? String)?.let { raw -> runCatching { JSONObject(raw).toOfflineAsset() }.getOrNull() }
        }
    }

    fun findById(assetId: String): OfflineAsset? {
        return read(assetId)
    }

    fun findByLocalUri(localUri: String): OfflineAsset? {
        return all().firstOrNull { it.localUri == localUri }
    }

    fun findByTrack(track: MusicTrack): OfflineAsset? {
        track.localUri?.let(::findByLocalUri)?.let { return it }
        return read(offlineAssetId(track))
            ?: all().firstOrNull { it.providerTrackId == track.id && it.source == track.source }
    }

    fun upsert(asset: OfflineAsset) {
        preferences.edit()
            .putString(assetKey(asset.id), asset.toJson().toString())
            .apply()
    }

    fun remove(assetId: String) {
        preferences.edit().remove(assetKey(assetId)).apply()
    }

    fun removeByLocalUri(localUri: String) {
        val editor = preferences.edit()
        var changed = false
        all().filter { it.localUri == localUri }.forEach { asset ->
            editor.remove(assetKey(asset.id))
            changed = true
        }
        if (changed) editor.apply()
    }

    private fun read(assetId: String): OfflineAsset? {
        val raw = preferences.getString(assetKey(assetId), null) ?: return null
        return runCatching { JSONObject(raw).toOfflineAsset() }.getOrNull()
    }

    private fun assetKey(assetId: String): String = "$ASSET_KEY_PREFIX$assetId"

    private companion object {
        private const val PREFERENCES_NAME = "offline_assets"
        private const val ASSET_KEY_PREFIX = "asset:"
    }
}

private fun OfflineAsset.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("providerTrackId", providerTrackId)
    .put("providerId", providerId ?: "")
    .put("providerName", providerName ?: "")
    .put("source", source)
    .put("title", title)
    .put("artists", artists)
    .put("album", album)
    .put("localUri", localUri)
    .put("coverUrl", coverUrl ?: "")
    .put("durationMs", durationMs ?: 0)
    .put("fileSize", fileSize)
    .put("audioQuality", audioQuality ?: "")
    .put("createdAt", createdAt)

private fun JSONObject.toOfflineAsset(): OfflineAsset = OfflineAsset(
    id = getString("id"),
    providerTrackId = getString("providerTrackId"),
    providerId = optString("providerId").takeIf { it.isNotBlank() },
    providerName = optString("providerName").takeIf { it.isNotBlank() },
    source = optString("source"),
    title = optString("title"),
    artists = optString("artists"),
    album = optString("album"),
    localUri = getString("localUri"),
    coverUrl = optString("coverUrl").takeIf { it.isNotBlank() },
    durationMs = optLong("durationMs").takeIf { it > 0 },
    fileSize = optLong("fileSize"),
    audioQuality = optString("audioQuality").takeIf { it.isNotBlank() },
    createdAt = optLong("createdAt"),
)
