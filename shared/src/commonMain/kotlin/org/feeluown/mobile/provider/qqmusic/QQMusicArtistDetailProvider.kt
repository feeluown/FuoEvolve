package org.feeluown.mobile.provider.qqmusic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.ProviderMediaItem
import org.feeluown.mobile.ProviderMediaItemType
import org.feeluown.mobile.TrackSourceType
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.array
import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.cookieHeader
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.md5Hex
import org.feeluown.mobile.provider.core.mediaItemKey
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.splitResourceId
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.trackKey
import org.feeluown.mobile.provider.core.network.ProviderCachePolicies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.currentTimeMillis
import kotlin.random.Random

/**
 * Loads QQ Music artist tracks using singer MID, which is the identifier expected by
 * the current singer-song endpoint. It also normalizes legacy QQ artist cover URLs.
 */
class QQMusicArtistDetailProvider(
    private val http: ProviderHttpClient,
    private val credentials: ProviderCredentialStore,
) {
    fun normalizeArtist(item: ProviderMediaItem): ProviderMediaItem {
        if (item.providerId != ID || item.type != ProviderMediaItemType.Artist) return item
        val rawIdentifier = splitResourceId(item.id, "artist").second
        val mid = rawIdentifier.takeUnless(::isNumericId)
            ?: artistMidFromCover(item.coverUrl)
        return normalizeArtist(item, mid)
    }

    suspend fun loadTracksPage(
        item: ProviderMediaItem,
        offset: Int,
        limit: Int,
    ): QQMusicArtistTrackPage {
        val mid = resolveSingerMid(item)
        val normalized = normalizeArtist(item, mid)
        if (mid.isNullOrBlank()) {
            return QQMusicArtistTrackPage(normalized, emptyList(), offset, false, normalized.trackCount)
        }
        val requestLimit = limit.coerceAtLeast(1)
        val root = rpc(
            """
            {"singerSongList":{"method":"GetSingerSongList","param":{"order":1,"singerMid":${jsonString(mid)},"begin":$offset,"num":$requestLimit},"module":"musichall.song_list_server"}}
            """.trimIndent(),
            cacheKey = "qqmusic:artist-tracks:$mid:$offset:$requestLimit",
            cachePolicy = ProviderCachePolicies.detail,
        )
        val data = root.obj("singerSongList")?.obj("data")
            ?: root.obj("data")
            ?: JsonObject(emptyMap())
        val rawValues = data.array("songList").ifEmpty { data.array("songlist") }
        val tracks = rawValues.mapNotNull { value ->
            runCatching { song(value.asObject()) }.getOrNull()
        }.filter { it.id.substringAfterLast(':').isNotBlank() }
            .distinctBy { it.id }
        val total = data.int("totalNum")
            ?: data.int("total")
            ?: data.int("songnum")
            ?: data.int("song_count")
        val nextOffset = offset + rawValues.size
        val hasMore = total?.let { nextOffset < it } ?: (rawValues.size >= requestLimit)
        return QQMusicArtistTrackPage(
            item = normalized.copy(trackCount = total ?: normalized.trackCount),
            tracks = tracks,
            nextOffset = nextOffset,
            hasMore = hasMore,
            total = total,
        )
    }

    private suspend fun resolveSingerMid(item: ProviderMediaItem): String? {
        val normalized = normalizeArtist(item)
        val rawIdentifier = splitResourceId(normalized.id, "artist").second
        if (rawIdentifier.isNotBlank() && !isNumericId(rawIdentifier)) return rawIdentifier
        artistMidFromCover(normalized.coverUrl)?.let { return it }
        if (item.title.isBlank()) return null

        val root = rpc(
            """
            {"singerSearch":{"module":"music.search.SearchCgiService","method":"DoSearchForQQMusicDesktop","param":{"num_per_page":10,"page_num":1,"search_type":1,"query":${jsonString(item.title)}}}}
            """.trimIndent(),
            cacheKey = "qqmusic:artist-mid:${item.title}",
            cachePolicy = ProviderCachePolicies.search,
        )
        val searchData = root.obj("singerSearch")?.obj("data") ?: root.obj("singerSearch")
        val body = searchData?.obj("body") ?: searchData ?: JsonObject(emptyMap())
        val values = body.obj("singer")?.array("list").orEmpty()
        val expectedNumericId = rawIdentifier.takeIf(::isNumericId)
        val candidates = values.mapNotNull { value -> runCatching { value.asObject() }.getOrNull() }
        val matched = candidates.firstOrNull { candidate ->
            expectedNumericId != null && singerNumericId(candidate) == expectedNumericId
        } ?: candidates.firstOrNull { candidate -> singerName(candidate) == item.title }
            ?: candidates.firstOrNull()
        return matched?.let(::singerMid)?.takeIf(String::isNotBlank)
    }

    private fun normalizeArtist(item: ProviderMediaItem, mid: String?): ProviderMediaItem {
        val correctedCover = item.coverUrl
            ?.replace("https://y.qq.com/music/photo_new/", "https://y.gtimg.cn/music/photo_new/")
            ?.replace("http://y.qq.com/music/photo_new/", "https://y.gtimg.cn/music/photo_new/")
        val cover = correctedCover
            ?: mid?.takeIf(String::isNotBlank)?.let(::artistCover)
        if (mid.isNullOrBlank()) return item.copy(coverUrl = cover)
        return item.copy(
            id = mediaItemKey(ProviderMediaItemType.Artist, ID, mid),
            coverUrl = cover ?: artistCover(mid),
            providerUrl = "https://y.qq.com/n/ryqq/singer/$mid",
        )
    }

    private fun song(value: JsonObject): MusicTrack {
        val item = value.obj("songInfo") ?: value
        val identifier = item.string("songmid")
            .ifBlank { item.string("mid") }
            .ifBlank { item.string("song_id") }
            .ifBlank { item.string("songid") }
            .ifBlank { item.string("id") }
        val singerItems = item.array("singer")
        val artists = singerItems.map { singerValue ->
            val singer = singerValue.asObject()
            singer.string("name").ifBlank { singer.string("singer_name") }
        }.filter(String::isNotBlank).joinToString(" / ").ifBlank {
            item.string("singer_name").ifBlank { item.string("singername") }
        }
        val album = item.obj("album")
        val albumName = item.string("albumname")
            .ifBlank { item.string("album_name") }
            .ifBlank { album?.string("name").orEmpty() }
        val albumId = item.string("albumid")
            .ifBlank { item.string("album_id") }
            .ifBlank { album?.string("id").orEmpty() }
        val albumMid = item.string("albummid")
            .ifBlank { item.string("album_mid") }
            .ifBlank { album?.string("mid").orEmpty() }
        val firstSinger = singerItems.firstOrNull()?.asObject()
        val artistIdentifier = firstSinger?.string("mid")
            ?.ifBlank { firstSinger.string("singer_mid") }
            ?.ifBlank { firstSinger.string("singerMID") }
            ?.ifBlank { firstSinger.string("id") }
            ?.ifBlank { firstSinger.string("singer_id") }
        val rawDuration = item.long("interval") ?: item.long("duration")
        return MusicTrack(
            id = trackKey(ID, identifier),
            title = item.string("songname")
                .ifBlank { item.string("name") }
                .ifBlank { item.string("title") }
                .ifBlank { item.string("songorig") },
            artists = artists,
            album = albumName,
            source = ID,
            sourceType = TrackSourceType.Provider,
            coverUrl = item.stringOrNull("picurl")
                ?: album?.stringOrNull("picurl")
                ?: albumMid.takeIf(String::isNotBlank)?.let(::albumCover),
            durationMs = rawDuration?.let { if (it < 100_000) it * 1_000 else it },
            providerId = trackKey(ID, identifier),
            providerName = NAME,
            artistItemId = artistIdentifier?.takeIf(String::isNotBlank)?.let {
                mediaItemKey(ProviderMediaItemType.Artist, ID, it)
            },
            albumItemId = (albumId.takeIf(String::isNotBlank) ?: albumMid.takeIf(String::isNotBlank))?.let {
                mediaItemKey(ProviderMediaItemType.Album, ID, it)
            },
            providerUrl = "https://y.qq.com/n/ryqq/songDetail/$identifier",
        )
    }

    private suspend fun rpc(
        payload: String,
        cacheKey: String? = null,
        cachePolicy: org.feeluown.mobile.provider.core.network.ProviderCachePolicy = ProviderCachePolicies.none,
    ): JsonObject {
        val request = qqRpcPayload(payload)
        return http.getText(
            providerId = ID,
            url = queryUrl(
                "$U_BASE/cgi-bin/musicu.fcg",
                mapOf(
                    "_" to currentTimeMillis().toString(),
                    "sign" to contentSign(request),
                    "data" to request,
                ),
            ),
            headers = authenticatedHeaders(),
            cacheKey = cacheKey,
            cachePolicy = cachePolicy,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
    }

    private fun qqRpcPayload(payload: String): String {
        val root = providerJson.parseToJsonElement(payload).jsonObject
        val common = mapOf(
            "loginUin" to JsonPrimitive("0"),
            "hostUin" to JsonPrimitive(0),
            "g_tk" to JsonPrimitive(5_381),
            "inCharset" to JsonPrimitive("utf8"),
            "outCharset" to JsonPrimitive("utf-8"),
            "notice" to JsonPrimitive(0),
            "platform" to JsonPrimitive("yqq"),
            "needNewCode" to JsonPrimitive(0),
        )
        val mergedComm = JsonObject(common + (root.obj("comm") ?: emptyMap()))
        return providerJson.encodeToString(
            JsonObject.serializer(),
            JsonObject(root + ("comm" to mergedComm)),
        )
    }

    private suspend fun authenticatedHeaders(): Map<String, String> = buildMap {
        put("User-Agent", DEFAULT_USER_AGENT)
        put("Referer", "https://y.qq.com/")
        cookieHeader(credentials.read(ID)).takeIf(String::isNotBlank)?.let { put("Cookie", it) }
    }

    private fun queryUrl(base: String, params: Map<String, String>): String {
        val encoded = params.entries.joinToString("&") { (key, value) ->
            "${encodeUrlComponent(key)}=${encodeUrlComponent(value)}"
        }
        return if (encoded.isBlank()) base else "$base?$encoded"
    }

    private fun encodeUrlComponent(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val intValue = byte.toInt() and 0xff
            if (
                intValue in 0x30..0x39 ||
                intValue in 0x41..0x5a ||
                intValue in 0x61..0x7a ||
                intValue in setOf(45, 46, 95, 126)
            ) {
                append(intValue.toChar())
            } else {
                append('%')
                append("0123456789ABCDEF"[intValue ushr 4])
                append("0123456789ABCDEF"[intValue and 15])
            }
        }
    }

    private fun jsonString(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun singerNumericId(item: JsonObject): String = item.string("singer_id")
        .ifBlank { item.string("singerID") }
        .ifBlank { item.string("singerid") }
        .ifBlank { item.string("id") }

    private fun singerMid(item: JsonObject): String = item.string("singer_mid")
        .ifBlank { item.string("singerMID") }
        .ifBlank { item.string("singermid") }
        .ifBlank { item.string("mid") }

    private fun singerName(item: JsonObject): String = item.string("singer_name")
        .ifBlank { item.string("singerName") }
        .ifBlank { item.string("singername") }
        .ifBlank { item.string("name") }

    private fun artistMidFromCover(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return ARTIST_COVER_MID.find(url)?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)
    }

    private fun isNumericId(value: String): Boolean = value.isNotBlank() && value.all(Char::isDigit)

    private fun artistCover(mid: String): String =
        "https://y.gtimg.cn/music/photo_new/T001R300x300M000$mid.jpg"

    private fun albumCover(mid: String): String =
        "https://y.gtimg.cn/music/photo_new/T002R300x300M000$mid.jpg"

    private fun contentSign(data: String): String {
        val randomPart = buildString {
            repeat(Random.nextInt(10, 17)) {
                append(SIGN_ALPHABET[Random.nextInt(SIGN_ALPHABET.length)])
            }
        }
        return "zza$randomPart${md5Hex("CJBPACrRuNy7$data")}" 
    }

    private companion object {
        const val ID = "qqmusic"
        const val NAME = "QQ 音乐"
        const val U_BASE = "https://u.y.qq.com"
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        const val SIGN_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        val ARTIST_COVER_MID = Regex("T001R\\d+x\\d+M000([A-Za-z0-9]+)\\.jpg")
    }
}

data class QQMusicArtistTrackPage(
    val item: ProviderMediaItem,
    val tracks: List<MusicTrack>,
    val nextOffset: Int,
    val hasMore: Boolean,
    val total: Int?,
)
