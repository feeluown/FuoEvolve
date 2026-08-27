package org.feeluown.mobile.provider.bilibili

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.feeluown.mobile.MediaRef
import org.feeluown.mobile.MediaRefType
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.ProviderSearchHit
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.ProviderVideo
import org.feeluown.mobile.TrackSourceType
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.array
import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.cookieHeader
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.mediaItemKey
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.trackKey
import org.feeluown.mobile.provider.core.videoKey
import org.feeluown.mobile.provider.core.network.ProviderCachePolicies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient

/** Maps Bilibili's native all-search response onto the common provider search contract. */
internal class BilibiliComprehensiveSearchProvider(
    private val delegate: KotlinMusicProvider,
    private val http: ProviderHttpClient,
    private val credentials: ProviderCredentialStore,
) : KotlinMusicProvider by delegate {
    override suspend fun search(keyword: String): ProviderSearchResults {
        if (keyword.isBlank()) return ProviderSearchResults()
        val comprehensive = runCatching { comprehensiveSearch(keyword) }.getOrNull()
        if (comprehensive != null && comprehensive.hasCatalogResults()) return comprehensive
        val fallback = delegate.search(keyword)
        return fallback.copy(
            bestMatches = fallback.bestMatches.ifEmpty { listOfNotNull(fallback.fallbackBestMatch(keyword)) },
        )
    }

    private suspend fun comprehensiveSearch(keyword: String): ProviderSearchResults {
        val root = http.getText(
            providerId = ID,
            url = "$BASE/x/web-interface/search/all/v2?keyword=${encodeUrlComponent(keyword)}&page=1",
            headers = headers(),
            cacheKey = "bilibili:search:comprehensive:$keyword",
            cachePolicy = ProviderCachePolicies.search,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val groups = root.obj("data")?.array("result").orEmpty()
        val tracks = mutableListOf<MusicTrack>()
        val artists = mutableListOf<MediaRef>()
        val albums = mutableListOf<MediaRef>()
        val videos = mutableListOf<ProviderVideo>()
        var nativeBest: ProviderSearchHit? = null

        groups.forEach { groupValue ->
            val group = runCatching { groupValue.asObject() }.getOrNull() ?: return@forEach
            val type = group.string("result_type")
            group.array("data").forEach { itemValue ->
                val item = runCatching { itemValue.asObject() }.getOrNull() ?: return@forEach
                when (type) {
                    "video" -> {
                        val track = toTrack(item)
                        val video = toVideo(item)
                        if (track != null) tracks += track
                        if (video != null) videos += video
                        if (nativeBest == null) nativeBest = track?.let(ProviderSearchHit::Track)
                            ?: video?.let(ProviderSearchHit::Video)
                    }
                    "bili_user" -> {
                        val artist = toArtist(item)
                        if (artist != null) artists += artist
                        if (nativeBest == null) nativeBest = artist?.let(ProviderSearchHit::Artist)
                    }
                    "media_bangumi", "media_ft" -> {
                        val album = toSeason(item)
                        if (album != null) albums += album
                        if (nativeBest == null) nativeBest = album?.let(ProviderSearchHit::Album)
                    }
                }
            }
        }

        val results = ProviderSearchResults(
            tracks = tracks.distinctBy { it.id },
            artists = artists.distinctBy { it.id },
            albums = albums.distinctBy { it.id },
            videos = videos.distinctBy { it.id },
        )
        return results.copy(
            bestMatches = listOfNotNull(nativeBest ?: results.fallbackBestMatch(keyword)),
        )
    }

    private suspend fun headers(): Map<String, String> {
        val cookie = cookieHeader(credentials.read(ID))
        return buildMap {
            put("User-Agent", USER_AGENT)
            put("Referer", "https://www.bilibili.com/")
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
    }

    private fun toTrack(item: JsonObject): MusicTrack? {
        val bvid = item.stringOrNull("bvid") ?: return null
        return MusicTrack(
            id = trackKey(ID, bvid),
            title = item.string("title").stripHtml(),
            artists = item.string("author").stripHtml(),
            album = item.string("typename"),
            source = ID,
            sourceType = TrackSourceType.Provider,
            coverUrl = normalizeCover(item.stringOrNull("pic")),
            durationMs = parseDurationMs(item.string("duration")),
            providerId = trackKey(ID, bvid),
            providerName = NAME,
            artistItemId = item.stringOrNull("mid")?.let { mediaItemKey(MediaRefType.Artist, ID, it) },
            providerUrl = "https://www.bilibili.com/video/$bvid",
        )
    }

    private fun toVideo(item: JsonObject): ProviderVideo? {
        val bvid = item.stringOrNull("bvid") ?: return null
        return ProviderVideo(
            id = videoKey(ID, bvid),
            title = item.string("title").stripHtml(),
            artists = item.string("author").stripHtml(),
            providerId = ID,
            providerName = NAME,
            coverUrl = normalizeCover(item.stringOrNull("pic")),
            durationMs = parseDurationMs(item.string("duration")),
            providerUrl = "https://www.bilibili.com/video/$bvid",
        )
    }

    private fun toArtist(item: JsonObject): MediaRef? {
        val identifier = item.string("mid")
        if (identifier.isBlank()) return null
        return MediaRef(
            id = mediaItemKey(MediaRefType.Artist, ID, identifier),
            title = item.string("uname").ifBlank { item.string("name") }.stripHtml(),
            providerId = ID,
            providerName = NAME,
            type = MediaRefType.Artist,
            coverUrl = normalizeCover(item.stringOrNull("upic") ?: item.stringOrNull("face")),
            description = item.string("usign").stripHtml(),
            providerUrl = "https://space.bilibili.com/$identifier",
        )
    }

    private fun toSeason(item: JsonObject): MediaRef? {
        val seasonId = item.string("season_id")
            .ifBlank { item.long("season_id")?.toString().orEmpty() }
            .ifBlank { item.string("seasonId") }
        if (seasonId.isBlank()) return null
        val title = item.string("title").ifBlank { item.string("org_title") }.stripHtml()
        val description = item.string("evaluate").ifBlank {
            item.obj("new_ep")?.string("index_show").orEmpty()
        }
        return MediaRef(
            id = mediaItemKey(MediaRefType.Album, ID, "$SEASON_PREFIX$seasonId"),
            title = title,
            providerId = ID,
            providerName = NAME,
            type = MediaRefType.Album,
            coverUrl = normalizeCover(item.stringOrNull("cover")),
            description = description.stripHtml(),
            providerUrl = "https://www.bilibili.com/bangumi/play/ss$seasonId",
        )
    }

    private fun ProviderSearchResults.hasCatalogResults(): Boolean =
        tracks.isNotEmpty() || artists.isNotEmpty() || albums.isNotEmpty() || videos.isNotEmpty()

    private fun ProviderSearchResults.fallbackBestMatch(keyword: String): ProviderSearchHit? {
        val normalized = normalize(keyword)
        return artists.firstOrNull { normalize(it.title) == normalized }?.let(ProviderSearchHit::Artist)
            ?: albums.firstOrNull { normalize(it.title) == normalized }?.let(ProviderSearchHit::Album)
            ?: tracks.firstOrNull { normalize(it.title) == normalized }?.let(ProviderSearchHit::Track)
            ?: videos.firstOrNull { normalize(it.title) == normalized }?.let(ProviderSearchHit::Video)
            ?: tracks.firstOrNull()?.let(ProviderSearchHit::Track)
            ?: artists.firstOrNull()?.let(ProviderSearchHit::Artist)
            ?: albums.firstOrNull()?.let(ProviderSearchHit::Album)
            ?: videos.firstOrNull()?.let(ProviderSearchHit::Video)
    }

    private fun parseDurationMs(value: String): Long? {
        val parts = value.trim().split(':').mapNotNull(String::toLongOrNull)
        if (parts.isEmpty()) return null
        var seconds = 0L
        parts.forEach { part -> seconds = seconds * 60 + part }
        return seconds * 1_000
    }

    private fun normalizeCover(value: String?): String? = value
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { if (it.startsWith("//")) "https:$it" else it }

    private fun String.stripHtml(): String = replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    private fun normalize(value: String): String = value.stripHtml().trim().lowercase().replace(" ", "")

    private fun encodeUrlComponent(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val number = byte.toInt() and 0xff
            if (number in 0x30..0x39 || number in 0x41..0x5a || number in 0x61..0x7a || number in setOf(45, 46, 95, 126)) {
                append(number.toChar())
            } else {
                append('%')
                append("0123456789ABCDEF"[number ushr 4])
                append("0123456789ABCDEF"[number and 15])
            }
        }
    }

    private companion object {
        const val ID = "bilibili"
        const val NAME = "哔哩哔哩"
        const val BASE = "https://api.bilibili.com"
        const val SEASON_PREFIX = "season_"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }
}