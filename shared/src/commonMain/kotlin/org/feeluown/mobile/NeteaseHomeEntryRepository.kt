package org.feeluown.mobile

import io.ktor.http.Parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.array
import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.network.ProviderCachePolicies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient

/**
 * Loads the artwork used by NetEase Cloud Music's discovery-page circular entries.
 * The core provider stays responsible for the actual feature content; this wrapper
 * only primes presentation metadata before NetEase feature sections are shown.
 */
internal class NeteaseHomeEntryRepository(
    private val delegate: ProviderMusicRepository,
    private val homeEntries: NeteaseHomeEntryClient,
) : ProviderMusicRepository by delegate {
    override suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection =
        loadFeaturePage(feature, 0, PROVIDER_PAGE_SIZE)

    override suspend fun loadFeaturePage(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        if (
            feature.providerId == NETEASE_PROVIDER_ID &&
            (feature.category == ProviderFeatureCategory.Recommend || feature.category == ProviderFeatureCategory.Music)
        ) {
            homeEntries.ensureLoaded()
        }
        return delegate.loadFeaturePage(feature, offset, limit)
    }
}

internal class NeteaseHomeEntryClient(
    private val http: ProviderHttpClient,
    private val credentials: ProviderCredentialStore,
) {
    private val mutex = Mutex()
    private val attemptedAuthModes = mutableSetOf<Boolean>()

    suspend fun ensureLoaded() {
        val saved = credentials.read(NETEASE_PROVIDER_ID)
        val cookie = saved?.cookieHeader?.takeIf { it.isNotBlank() }
            ?: saved?.cookies
                ?.takeIf { it.isNotEmpty() }
                ?.entries
                ?.joinToString("; ") { (key, value) -> "$key=$value" }
        val authenticated = !cookie.isNullOrBlank()

        mutex.withLock {
            if (authenticated in attemptedAuthModes) return
            attemptedAuthModes += authenticated

            val covers = runCatching { fetchCovers(cookie) }.getOrDefault(emptyMap())
            if (covers.isNotEmpty()) publishNeteaseHomeEntryCovers(covers)
        }
    }

    private suspend fun fetchCovers(cookie: String?): Map<String, String> {
        val response = http.postForm(
            providerId = NETEASE_PROVIDER_ID,
            url = "$NETEASE_BASE/api/homepage/dragon/ball/static",
            form = Parameters.build {},
            headers = buildMap {
                put("Referer", "$NETEASE_BASE/")
                put("Origin", NETEASE_BASE)
                put("User-Agent", NETEASE_HOME_USER_AGENT)
                cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            },
            cacheKey = "netease:homepage:dragon-ball",
            cachePolicy = ProviderCachePolicies.recommendation,
        )
        val root = providerJson.parseToJsonElement(response.value).asObject()
        val entries = root.array("data").mapNotNull { element ->
            val item = runCatching { element.asObject() }.getOrNull() ?: return@mapNotNull null
            val iconUrl = item.stringOrNull("iconUrl")
                ?: item.stringOrNull("iconUrl2")
                ?: return@mapNotNull null
            NeteaseHomeEntry(
                name = item.string("name"),
                iconUrl = iconUrl,
                url = item.string("url"),
            )
        }
        return mapNeteaseHomeEntryCovers(entries)
    }
}

internal data class NeteaseHomeEntry(
    val name: String,
    val iconUrl: String,
    val url: String = "",
)

internal fun mapNeteaseHomeEntryCovers(entries: List<NeteaseHomeEntry>): Map<String, String> = buildMap {
    NETEASE_HOME_ENTRY_ALIASES.forEach { (featureId, aliases) ->
        findNeteaseHomeEntryCover(entries, aliases)?.let { coverUrl ->
            put(featureId, coverUrl)
        }
    }
}

internal fun neteaseHomeEntryCoverUrl(featureId: String): String? =
    neteaseHomeEntryCovers[neteaseFeatureBaseId(featureId)]

internal fun neteaseFeatureBaseId(featureId: String): String =
    featureId.substringBefore("^filters^").substringBefore('|')

private fun publishNeteaseHomeEntryCovers(covers: Map<String, String>) {
    neteaseHomeEntryCovers = neteaseHomeEntryCovers + covers
}

private fun findNeteaseHomeEntryCover(
    entries: List<NeteaseHomeEntry>,
    aliases: List<String>,
): String? {
    val normalizedAliases = aliases.map(::normalizeNeteaseHomeEntryName)
    normalizedAliases.forEach { alias ->
        entries.firstOrNull { normalizeNeteaseHomeEntryName(it.name) == alias }
            ?.iconUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }
    normalizedAliases.forEach { alias ->
        entries.firstOrNull {
            val name = normalizeNeteaseHomeEntryName(it.name)
            name.isNotBlank() && alias.isNotBlank() && (name.contains(alias) || alias.contains(name))
        }?.iconUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }
    return null
}

private fun normalizeNeteaseHomeEntryName(value: String): String =
    value.lowercase()
        .replace(" ", "")
        .replace("-", "")
        .replace("_", "")

private var neteaseHomeEntryCovers: Map<String, String> = emptyMap()

private val NETEASE_HOME_ENTRY_ALIASES = mapOf(
    "netease_daily_songs" to listOf("每日推荐"),
    "netease_daily_playlists" to listOf("每日推荐"),
    "netease_recommended_new_songs" to listOf("新歌新碟", "新歌"),
    "netease_radio" to listOf("私人漫游", "私人FM"),
    "netease_toplists" to listOf("排行榜"),
    "netease_playlist_square" to listOf("歌单"),
    "netease_artist_square" to listOf("歌手"),
    "netease_mv_square" to listOf("MV广场", "MV"),
    "netease_styles" to listOf("曲风", "风格"),
    "netease_new_songs" to listOf("新歌新碟", "新歌"),
    "netease_new_albums" to listOf("新歌新碟", "新碟", "数字专辑"),
    "netease_top_artists" to listOf("歌手"),
    "netease_highquality_playlists" to listOf("歌单"),
    "netease_recommended_mvs" to listOf("MV"),
    "netease_top_mvs" to listOf("MV排行", "MV", "排行榜"),
)

private const val NETEASE_PROVIDER_ID = "netease"
private const val NETEASE_BASE = "https://music.163.com"
private const val NETEASE_HOME_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
