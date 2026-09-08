package org.feeluown.mobile.provider.ytmusic

import kotlinx.serialization.json.JsonObject
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.stringOrNull

internal enum class YtPoTokenContext {
    Player,
    Gvs,
    Subs,
}

internal data class YtPoTokenRequest(
    val context: YtPoTokenContext,
    val client: YtPlayerClientProfile,
    val videoId: String,
    val visitorData: String?,
)

/** Mirrors yt-dlp's provider boundary: token generation is intentionally pluggable. */
internal fun interface YtPoTokenProvider {
    suspend fun token(request: YtPoTokenRequest): String?
}

/**
 * Reads explicitly supplied PO tokens without coupling playback extraction to a
 * specific BotGuard implementation. yt-dlp follows the same design: its core
 * contains the policy/provider framework while actual token minting may come
 * from an external provider.
 *
 * Existing header-file login JSON can optionally carry one of:
 *
 *   "player_po_token": "..."
 *   "gvs_po_token": "..."
 *   "subs_po_token": "..."
 *   "poTokens": {
 *     "visionos": { "gvs": "...", "player": "..." },
 *     "web": { "gvs": "..." }
 *   }
 *
 * The fields are additive and do not alter browser/OAuth authentication.
 */
internal class CredentialYtPoTokenProvider(
    private val credentials: ProviderCredentialStore,
) : YtPoTokenProvider {
    override suspend fun token(request: YtPoTokenRequest): String? {
        val raw = credentials.read(YtMusicProvider.ID)?.headerFileJson?.takeIf { it.isNotBlank() }
            ?: return null
        val root = runCatching { providerJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return null
        val contextKey = request.context.key
        val perClient = root["poTokens"] as? JsonObject
        val client = perClient?.get(request.client.key) as? JsonObject
            ?: perClient?.get(request.client.clientName.lowercase()) as? JsonObject
        return client?.stringOrNull(contextKey)
            ?: root.stringOrNull("${contextKey}_po_token")
            ?: root.stringOrNull("po_token")
    }

    private val YtPoTokenContext.key: String
        get() = when (this) {
            YtPoTokenContext.Player -> "player"
            YtPoTokenContext.Gvs -> "gvs"
            YtPoTokenContext.Subs -> "subs"
        }
}
