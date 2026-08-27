package org.feeluown.mobile.provider.qqmusic

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.feeluown.mobile.MediaRef
import org.feeluown.mobile.MediaRefType
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.ProviderPlaylist
import org.feeluown.mobile.ProviderSearchHit
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.ProviderVideo
import org.feeluown.mobile.TrackSourceType
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.array
import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.cookieHeader
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.md5Hex
import org.feeluown.mobile.provider.core.mediaItemKey
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.playlistKey
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.trackKey
import org.feeluown.mobile.provider.core.videoKey
import org.feeluown.mobile.provider.core.network.ProviderCachePolicies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.currentTimeMillis
import kotlin.random.Random

/** Uses QQ Music's SearchAdaptor/do_search_v2 as the default multi-type search surface. */
internal class QQMusicComprehensiveSearchProvider(
    private val delegate: KotlinMusicProvider,
    private val http: ProviderHttpClient,
    private val credentials: ProviderCredentialStore,
) : KotlinMusicProvider by delegate {
    override suspend fun search(keyword: String): ProviderSearchResults {
        if (keyword.isBlank()) return ProviderSearchResults()
        val quickBest = runCatching { quickSearchBestMatch(keyword) }.getOrNull()
        val comprehensive = runCatching { generalSearch(keyword) }.getOrNull()
        if (comprehensive != null && comprehensive.hasCatalogResults()) {
            return comprehensive.copy(
                bestMatches = listOfNotNull(quickBest ?: comprehensive.fallbackBestMatch(keyword)),
            )
        }
        val fallback = delegate.search(keyword)
        return fallback.copy(
            bestMatches = fallback.bestMatches.ifEmpty {
                listOfNotNull(quickBest ?: fallback.fallbackBestMatch(keyword))
            },
        )
    }

    private suspend fun generalSearch(keyword: String): ProviderSearchResults {
        val request = """
            {
              "search":{
                "module":"music.adaptor.SearchAdaptor",
                "method":"do_search_v2",
                "param":{
                  "searchid":${currentTimeMillis()},
                  "search_type":100,
                  "page_num":20,
                  "query":${jsonString(keyword)},
                  "page_id":1,
                  "highlight":false,
                  "grp":true
                }
              },
              "comm":{"ct":24,"cv":0,"uin":"0","format":"json"}
            }
        """.trimIndent()
        val root = rpc(request, "qqmusic:search:comprehensive:$keyword")
        val data = root.obj("search")?.obj("data") ?: root.obj("search") ?: root
        val body = data.obj("body") ?: data
        return ProviderSearchResults(
            tracks = bucket(body, "item_song").mapNotNull(::toTrack).distinctBy { it.id },
            artists = bucket(body, "singer").mapNotNull(::toArtist).distinctBy { it.id },
            albums = bucket(body, "item_album").mapNotNull(::toAlbum).distinctBy { it.id },
            playlists = bucket(body, "item_songlist").mapNotNull(::toPlaylist).distinctBy { it.id },
            videos = bucket(body, "item_mv").mapNotNull(::toVideo).distinctBy { it.id },
        )
    }

    private suspend fun quickSearchBestMatch(keyword: String): ProviderSearchHit? {
        val root = http.getText(
            providerId = ID,
            url = "$QUICK_SEARCH_BASE?key=${encodeUrlComponent(keyword)}",
            headers = publicHeaders(),
            cacheKey = "qqmusic:search:quick:$keyword",
            cachePolicy = ProviderCachePolicies.search,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val data = root.obj("data") ?: return null
        val categories = listOf(
            QuickCategory("song", data.obj("song")),
            QuickCategory("singer", data.obj("singer")),
            QuickCategory("album", data.obj("album")),
            QuickCategory("mv", data.obj("mv")),
        ).filter { it.value?.array("itemlist")?.isNotEmpty() == true }
        if (categories.isEmpty()) return null

        val normalized = normalize(keyword)
        val exact = categories.asSequence().flatMap { category ->
            category.value!!.array("itemlist").asSequence().mapNotNull { value ->
                val item = runCatching { value.asObject() }.getOrNull() ?: return@mapNotNull null
                if (normalize(item.string("name")) != normalized) return@mapNotNull null
                quickHit(category.key, item)
            }
        }.firstOrNull()
        if (exact != null) return exact

        val selected = categories.minByOrNull { category ->
            category.value?.int("order")?.takeIf { it > 0 } ?: Int.MAX_VALUE
        } ?: return null
        val item = selected.value?.array("itemlist")?.firstOrNull()?.let {
            runCatching { it.asObject() }.getOrNull()
        } ?: return null
        return quickHit(selected.key, item)
    }

    private fun quickHit(type: String, item: JsonObject): ProviderSearchHit? = when (type) {
        "song" -> toQuickTrack(item)?.let(ProviderSearchHit::Track)
        "singer" -> toQuickArtist(item)?.let(ProviderSearchHit::Artist)
        "album" -> toQuickAlbum(item)?.let(ProviderSearchHit::Album)
        "mv" -> toQuickVideo(item)?.let(ProviderSearchHit::Video)
        else -> null
    }

    private suspend fun rpc(payload: String, cacheKey: String): JsonObject {
        val signedPayload = payload
        val sign = contentSign(signedPayload)
        return http.getText(
            providerId = ID,
            url = "$RPC_BASE/cgi-bin/musicu.fcg?_=${currentTimeMillis()}&sign=${encodeUrlComponent(sign)}&data=${encodeUrlComponent(signedPayload)}",
            headers = publicHeaders(),
            cacheKey = cacheKey,
            cachePolicy = ProviderCachePolicies.search,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
    }

    private suspend fun publicHeaders(): Map<String, String> {
        val cookie = cookieHeader(credentials.read(ID))
        return buildMap {
            put("User-Agent", USER_AGENT)
            put("Referer", "https://y.qq.com/")
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
    }

    private fun bucket(body: JsonObject, key: String): List<JsonElement> {
        val value = body.obj(key)
        return firstNonEmpty(
            value?.array("items").orEmpty(),
            value?.array("list").orEmpty(),
            body.array(key),
        )
    }

    private fun toTrack(value: JsonElement): MusicTrack? {
        val source = runCatching { value.asObject() }.getOrNull() ?: return null
        val item = source.obj("songInfo") ?: source.obj("data") ?: source
        val identifier = item.string("mid")
            .ifBlank { item.string("songmid") }
            .ifBlank { item.string("song_id") }
            .ifBlank { item.string("songid") }
            .ifBlank { item.string("id") }
        if (identifier.isBlank()) return null
        val singerValues = item.array("singer")
        val firstSinger = singerValues.firstOrNull()?.let { runCatching { it.asObject() }.getOrNull() }
        val album = item.obj("album")
        val albumMid = album?.string("mid").orEmpty().ifBlank { item.string("albummid") }
        val albumId = album?.string("id").orEmpty().ifBlank { item.string("albumid") }
        return MusicTrack(
            id = trackKey(ID, identifier),
            title = item.string("name").ifBlank { item.string("title") }.stripHtml(),
            artists = singerValues.mapNotNull { element ->
                runCatching { element.asObject().string("name") }.getOrNull()
            }.filter(String::isNotBlank).joinToString(" / ").ifBlank { item.string("singername") },
            album = album?.string("name").orEmpty().ifBlank { item.string("albumname") },
            source = ID,
            sourceType = TrackSourceType.Provider,
            coverUrl = item.stringOrNull("pic") ?: albumMid.takeIf(String::isNotBlank)?.let(::albumCover),
            durationMs = item.long("interval")?.times(1_000) ?: item.long("duration")?.let(::durationToMillis),
            providerId = trackKey(ID, identifier),
            providerName = NAME,
            artistItemId = firstSinger?.stringOrNull("mid")?.let { mediaItemKey(MediaRefType.Artist, ID, it) },
            albumItemId = (albumMid.takeIf(String::isNotBlank) ?: albumId.takeIf(String::isNotBlank))
                ?.let { mediaItemKey(MediaRefType.Album, ID, it) },
            providerUrl = "https://y.qq.com/n/ryqq/songDetail/$identifier",
        )
    }

    private fun toArtist(value: JsonElement): MediaRef? {
        val source = runCatching { value.asObject() }.getOrNull() ?: return null
        val item = source.obj("singerInfo") ?: source.obj("data") ?: source
        val identifier = item.string("mid")
            .ifBlank { item.string("singerMID") }
            .ifBlank { item.string("singerMid") }
            .ifBlank { item.string("singer_mid") }
            .ifBlank { item.string("id") }
        if (identifier.isBlank()) return null
        return MediaRef(
            id = mediaItemKey(MediaRefType.Artist, ID, identifier),
            title = item.string("name").ifBlank { item.string("singerName") }.stripHtml(),
            providerId = ID,
            providerName = NAME,
            type = MediaRefType.Artist,
            coverUrl = item.stringOrNull("singerPic") ?: item.stringOrNull("pic"),
            description = item.string("subtitle"),
            providerUrl = "https://y.qq.com/n/ryqq/singer/$identifier",
        )
    }

    private fun toAlbum(value: JsonElement): MediaRef? {
        val source = runCatching { value.asObject() }.getOrNull() ?: return null
        val item = source.obj("albumInfo") ?: source.obj("data") ?: source
        val identifier = item.string("mid")
            .ifBlank { item.string("albumMID") }
            .ifBlank { item.string("albumMid") }
            .ifBlank { item.string("id") }
        if (identifier.isBlank()) return null
        return MediaRef(
            id = mediaItemKey(MediaRefType.Album, ID, identifier),
            title = item.string("name").ifBlank { item.string("albumName") }.ifBlank { item.string("title") }.stripHtml(),
            providerId = ID,
            providerName = NAME,
            type = MediaRefType.Album,
            coverUrl = item.stringOrNull("pic") ?: albumCover(identifier),
            description = item.string("description"),
            providerUrl = "https://y.qq.com/n/ryqq/albumDetail/$identifier",
        )
    }

    private fun toPlaylist(value: JsonElement): ProviderPlaylist? {
        val source = runCatching { value.asObject() }.getOrNull() ?: return null
        val item = source.obj("dissInfo") ?: source.obj("data") ?: source
        val identifier = item.string("dissid")
            .ifBlank { item.string("tid") }
            .ifBlank { item.string("id") }
        if (identifier.isBlank()) return null
        return ProviderPlaylist(
            id = playlistKey(ID, identifier),
            title = item.string("dissname").ifBlank { item.string("title") }.ifBlank { item.string("name") }.stripHtml(),
            providerId = ID,
            providerName = NAME,
            coverUrl = item.stringOrNull("imgurl") ?: item.stringOrNull("logo") ?: item.stringOrNull("pic"),
            description = item.string("introduction").ifBlank { item.string("desc") },
            playCount = item.long("listennum") ?: item.long("visitnum"),
            providerUrl = "https://y.qq.com/n/ryqq/playlist/$identifier",
            trackCount = item.int("song_count") ?: item.int("songnum"),
        )
    }

    private fun toVideo(value: JsonElement): ProviderVideo? {
        val source = runCatching { value.asObject() }.getOrNull() ?: return null
        val item = source.obj("mvInfo") ?: source.obj("data") ?: source
        val identifier = item.string("vid")
            .ifBlank { item.string("mv_id") }
            .ifBlank { item.string("mid") }
        if (identifier.isBlank()) return null
        val singers = item.array("singer").mapNotNull { element ->
            runCatching { element.asObject().string("name") }.getOrNull()
        }.filter(String::isNotBlank).joinToString(" / ")
        return ProviderVideo(
            id = videoKey(ID, identifier),
            title = item.string("mv_name").ifBlank { item.string("name") }.ifBlank { item.string("title") }.stripHtml(),
            artists = singers.ifBlank { item.string("singer_name") }.ifBlank { item.string("singername") },
            providerId = ID,
            providerName = NAME,
            coverUrl = item.stringOrNull("picurl") ?: item.stringOrNull("pic"),
            durationMs = item.long("duration")?.let(::durationToMillis),
            providerUrl = "https://y.qq.com/n/ryqq/mvdetail/$identifier",
        )
    }

    private fun toQuickTrack(item: JsonObject): MusicTrack? {
        val identifier = item.string("mid").ifBlank { item.string("id") }
        if (identifier.isBlank()) return null
        return MusicTrack(
            id = trackKey(ID, identifier),
            title = item.string("name").stripHtml(),
            artists = item.string("singer").stripHtml(),
            album = "",
            source = ID,
            sourceType = TrackSourceType.Provider,
            coverUrl = item.stringOrNull("pic"),
            providerId = trackKey(ID, identifier),
            providerName = NAME,
            providerUrl = "https://y.qq.com/n/ryqq/songDetail/$identifier",
        )
    }

    private fun toQuickArtist(item: JsonObject): MediaRef? {
        val identifier = item.string("mid").ifBlank { item.string("id") }
        if (identifier.isBlank()) return null
        return MediaRef(
            id = mediaItemKey(MediaRefType.Artist, ID, identifier),
            title = item.string("name").stripHtml(),
            providerId = ID,
            providerName = NAME,
            type = MediaRefType.Artist,
            coverUrl = item.stringOrNull("pic"),
            providerUrl = "https://y.qq.com/n/ryqq/singer/$identifier",
        )
    }

    private fun toQuickAlbum(item: JsonObject): MediaRef? {
        val identifier = item.string("mid").ifBlank { item.string("id") }
        if (identifier.isBlank()) return null
        return MediaRef(
            id = mediaItemKey(MediaRefType.Album, ID, identifier),
            title = item.string("name").stripHtml(),
            providerId = ID,
            providerName = NAME,
            type = MediaRefType.Album,
            coverUrl = item.stringOrNull("pic") ?: albumCover(identifier),
            providerUrl = "https://y.qq.com/n/ryqq/albumDetail/$identifier",
        )
    }

    private fun toQuickVideo(item: JsonObject): ProviderVideo? {
        val identifier = item.string("vid").ifBlank { item.string("mid") }.ifBlank { item.string("id") }
        if (identifier.isBlank()) return null
        return ProviderVideo(
            id = videoKey(ID, identifier),
            title = item.string("name").stripHtml(),
            artists = item.string("singer").stripHtml(),
            providerId = ID,
            providerName = NAME,
            coverUrl = item.stringOrNull("pic"),
            providerUrl = "https://y.qq.com/n/ryqq/mvdetail/$identifier",
        )
    }

    private fun ProviderSearchResults.hasCatalogResults(): Boolean =
        tracks.isNotEmpty() || playlists.isNotEmpty() || artists.isNotEmpty() || albums.isNotEmpty() || videos.isNotEmpty()

    private fun ProviderSearchResults.fallbackBestMatch(keyword: String): ProviderSearchHit? {
        val normalized = normalize(keyword)
        return artists.firstOrNull { normalize(it.title) == normalized }?.let(ProviderSearchHit::Artist)
            ?: albums.firstOrNull { normalize(it.title) == normalized }?.let(ProviderSearchHit::Album)
            ?: tracks.firstOrNull { normalize(it.title) == normalized }?.let(ProviderSearchHit::Track)
            ?: playlists.firstOrNull { normalize(it.title) == normalized }?.let(ProviderSearchHit::Playlist)
            ?: videos.firstOrNull { normalize(it.title) == normalized }?.let(ProviderSearchHit::Video)
            ?: tracks.firstOrNull()?.let(ProviderSearchHit::Track)
            ?: artists.firstOrNull()?.let(ProviderSearchHit::Artist)
            ?: albums.firstOrNull()?.let(ProviderSearchHit::Album)
            ?: playlists.firstOrNull()?.let(ProviderSearchHit::Playlist)
            ?: videos.firstOrNull()?.let(ProviderSearchHit::Video)
    }

    private fun durationToMillis(value: Long): Long = if (value < 100_000) value * 1_000 else value

    private fun albumCover(mid: String): String = "https://y.qq.com/music/photo_new/T002R300x300M000$mid.jpg"

    private fun String.stripHtml(): String = replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    private fun normalize(value: String): String = value.stripHtml().trim().lowercase().replace(" ", "")

    private fun jsonString(value: String): String = JsonPrimitive(value).toString()

    private fun contentSign(data: String): String {
        val randomPart = buildString {
            repeat(Random.nextInt(10, 17)) {
                append(SIGN_ALPHABET[Random.nextInt(SIGN_ALPHABET.length)])
            }
        }
        return "zza$randomPart${md5Hex("CJBPACrRuNy7$data")}"
    }

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

    private data class QuickCategory(val key: String, val value: JsonObject?)

    private companion object {
        const val ID = "qqmusic"
        const val NAME = "QQ 音乐"
        const val RPC_BASE = "https://u.y.qq.com"
        const val QUICK_SEARCH_BASE = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg"
        const val SIGN_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }
}

private fun <T> firstNonEmpty(vararg values: List<T>): List<T> =
    values.firstOrNull { it.isNotEmpty() }.orEmpty()