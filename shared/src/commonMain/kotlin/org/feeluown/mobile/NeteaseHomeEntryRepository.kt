package org.feeluown.mobile

import io.ktor.http.Parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.array
import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.network.ProviderCachePolicies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.netease.NeteaseWeApi

/**
 * Loads artwork from NetEase Cloud Music's mobile discovery homepage.
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
        // The discovery homepage itself is a WeAPI endpoint. Besides the circular
        // shortcut block, it also carries artwork for new releases, styles, video
        // recommendations and charts, which gives the Explore entries useful
        // server-provided artwork even when a dedicated dragon-ball icon is absent.
        val payload = NeteaseWeApi.encrypt("""{"refresh":false}""")
        val response = http.postForm(
            providerId = NETEASE_PROVIDER_ID,
            url = "$NETEASE_BASE/weapi/homepage/block/page",
            form = Parameters.build {
                append("params", payload.params)
                append("encSecKey", payload.encSecKey)
            },
            headers = buildMap {
                put("Referer", "$NETEASE_BASE/")
                put("Origin", NETEASE_BASE)
                put("User-Agent", NETEASE_HOME_USER_AGENT)
                put("Cookie", neteaseHomepageCookie(cookie))
            },
            cacheKey = "netease:homepage:block-page",
            cachePolicy = ProviderCachePolicies.recommendation,
        )
        val root = providerJson.parseToJsonElement(response.value).asObject()
        val blocks = root.obj("data")?.array("blocks").orEmpty().mapNotNull { element ->
            runCatching { element.asObject() }.getOrNull()
        }
        return mapNeteaseHomepageCovers(blocks)
    }
}

internal data class NeteaseHomeEntry(
    val name: String,
    val iconUrl: String,
    val url: String = "",
)

internal fun mapNeteaseHomepageCovers(blocks: List<JsonObject>): Map<String, String> {
    val entries = blocks
        .filter { block -> block.string("blockCode") == NETEASE_DRAGON_BALL_BLOCK }
        .flatMap(::neteaseHomepageResources)
        .mapNotNull { resource ->
            val iconUrl = neteaseUiImageUrl(resource) ?: return@mapNotNull null
            val title = resource.obj("uiElement")?.obj("mainTitle")?.string("title").orEmpty()
            if (title.isBlank()) return@mapNotNull null
            NeteaseHomeEntry(
                name = title,
                iconUrl = iconUrl,
                url = resource.string("action"),
            )
        }

    // Block artwork is a fallback for Explore features that are not represented by
    // a circular shortcut. Exact shortcut icons win when both are available.
    return mapNeteaseHomepageBlockCovers(blocks) + mapNeteaseHomeEntryCovers(entries)
}

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

private fun mapNeteaseHomepageBlockCovers(blocks: List<JsonObject>): Map<String, String> = buildMap {
    NETEASE_HOME_BLOCK_ALIASES.forEach { (featureId, blockCodes) ->
        findNeteaseHomepageBlockCover(blocks, blockCodes)?.let { coverUrl ->
            put(featureId, coverUrl)
        }
    }
}

private fun findNeteaseHomepageBlockCover(
    blocks: List<JsonObject>,
    blockCodes: List<String>,
): String? {
    blockCodes.forEach { code ->
        blocks.firstOrNull { it.string("blockCode") == code }
            ?.let(::firstNeteaseHomepageImage)
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
    }
    return null
}

private fun firstNeteaseHomepageImage(block: JsonObject): String? {
    neteaseUiImageUrl(block)?.let { return it }
    block.array("creatives").forEach { creativeElement ->
        val creative = runCatching { creativeElement.asObject() }.getOrNull() ?: return@forEach
        neteaseUiImageUrl(creative)?.let { return it }
        creative.array("resources").forEach { resourceElement ->
            val resource = runCatching { resourceElement.asObject() }.getOrNull() ?: return@forEach
            neteaseUiImageUrl(resource)?.let { return it }
        }
    }
    return null
}

private fun neteaseHomepageResources(block: JsonObject): List<JsonObject> =
    block.array("creatives").flatMap { creativeElement ->
        val creative = runCatching { creativeElement.asObject() }.getOrNull() ?: return@flatMap emptyList()
        creative.array("resources").mapNotNull { resourceElement ->
            runCatching { resourceElement.asObject() }.getOrNull()
        }
    }

private fun neteaseUiImageUrl(value: JsonObject): String? =
    value.obj("uiElement")
        ?.obj("image")
        ?.let { image -> image.stringOrNull("imageUrl") ?: image.stringOrNull("imageUrl2") }

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

private fun neteaseHomepageCookie(cookie: String?): String {
    val values = cookie.orEmpty()
        .split(';')
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot { item ->
            val key = item.substringBefore('=').trim()
            key.equals("appver", ignoreCase = true) ||
                key.equals("os", ignoreCase = true) ||
                key.equals("__remember_me", ignoreCase = true)
        }
        .toMutableList()
    values += "__remember_me=true"
    values += "appver=8.10.90"
    values += "os=ios"
    return values.joinToString("; ")
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

private val NETEASE_HOME_BLOCK_ALIASES = mapOf(
    "netease_recommended_new_songs" to listOf("HOMEPAGE_BLOCK_NEW_ALBUM_NEW_SONG"),
    "netease_new_songs" to listOf("HOMEPAGE_BLOCK_NEW_ALBUM_NEW_SONG"),
    "netease_new_albums" to listOf("HOMEPAGE_BLOCK_NEW_ALBUM_NEW_SONG"),
    "netease_styles" to listOf("HOMEPAGE_BLOCK_STYLE_RCMD", "HOMEPAGE_BLOCK_OFFICIAL_PLAYLIST"),
    "netease_mv_square" to listOf("HOMEPAGE_MUSIC_MLOG", "HOMEPAGE_BLOCK_VIDEO_PLAYLIST"),
    "netease_recommended_mvs" to listOf("HOMEPAGE_MUSIC_MLOG", "HOMEPAGE_BLOCK_VIDEO_PLAYLIST"),
    "netease_top_mvs" to listOf("HOMEPAGE_BLOCK_VIDEO_PLAYLIST", "HOMEPAGE_MUSIC_MLOG", "HOMEPAGE_BLOCK_TOPLIST"),
    "netease_toplists" to listOf("HOMEPAGE_BLOCK_TOPLIST"),
    "netease_playlist_square" to listOf("HOMEPAGE_BLOCK_PLAYLIST_RCMD"),
    "netease_daily_playlists" to listOf("HOMEPAGE_BLOCK_PLAYLIST_RCMD"),
    "netease_highquality_playlists" to listOf("HOMEPAGE_BLOCK_PLAYLIST_RCMD"),
)

private const val NETEASE_DRAGON_BALL_BLOCK = "HOMEPAGE_BLOCK_OLD_DRAGON_BALL"
private const val NETEASE_PROVIDER_ID = "netease"
private const val NETEASE_BASE = "https://music.163.com"
private const val NETEASE_HOME_USER_AGENT =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_2 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148"
