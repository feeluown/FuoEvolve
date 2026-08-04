package org.feeluown.mobile.provider.bilibili

import org.feeluown.mobile.AudioQualityPolicy
import org.feeluown.mobile.PlaybackPart
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.ProviderAuthState
import org.feeluown.mobile.ProviderCapabilities
import org.feeluown.mobile.ProviderComment
import org.feeluown.mobile.ProviderContentSection
import org.feeluown.mobile.ProviderContentType
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderFeatureCategory
import org.feeluown.mobile.ProviderInfo
import org.feeluown.mobile.ProviderMediaItem
import org.feeluown.mobile.ProviderMediaItemDetail
import org.feeluown.mobile.ProviderMutationResult
import org.feeluown.mobile.ProviderPlaylist
import org.feeluown.mobile.ProviderPlaylistDetail
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.ProviderVideo
import org.feeluown.mobile.VideoPlaybackPayload
import org.feeluown.mobile.provider.core.BaseKotlinProvider
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.array
import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.boolean
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.md5Hex
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.splitResourceId
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.network.ProviderCachePolicies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.currentTimeMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BilibiliProvider(
    http: ProviderHttpClient,
    credentials: ProviderCredentialStore,
) : BaseKotlinProvider(
    http = http,
    credentials = credentials,
    id = ID,
    name = NAME,
    info = INFO,
    capabilities = CAPABILITIES,
    features = FEATURES,
), KotlinMusicProvider {
    private val wbiMutex = Mutex()
    private var wbiKeys: WbiKeys? = null

    override suspend fun search(keyword: String): ProviderSearchResults {
        val root = http.getText(
            ID,
            queryUrl("$BASE/x/web-interface/search/type", mapOf("search_type" to "video", "keyword" to keyword, "page" to "1", "page_size" to "30")),
            headers(),
            cacheKey = "bilibili:search:$keyword",
            cachePolicy = ProviderCachePolicies.search,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val tracks = root.obj("data")?.array("result").orEmpty().mapNotNull { element ->
            val item = element.asObject()
            val bvid = item.stringOrNull("bvid") ?: return@mapNotNull null
            track(
                identifier = bvid,
                title = stripHtml(item.string("title")),
                artists = item.string("author"),
                album = "",
                coverUrl = normalizeCover(item.stringOrNull("pic")),
                durationMs = parseDurationMs(item.string("duration")),
                providerUrl = "https://www.bilibili.com/video/$bvid",
            )
        }
        return ProviderSearchResults(tracks = tracks)
    }

    override suspend fun trackDetail(identifier: String): org.feeluown.mobile.MusicTrack? {
        val (bvid, page) = parsePaged(rawIdentifier(identifier))
        val root = videoInfo(bvid)
        val data = root.obj("data") ?: return null
        val owner = data.obj("owner")
        val pages = data.array("pages")
        val pageObject = pages.getOrNull((page ?: 1) - 1)?.asObject()
        return track(
            identifier = if (page != null) "paged_${bvid}__${page}" else bvid,
            title = pageObject?.string("part").takeUnless { it.isNullOrBlank() } ?: data.string("title"),
            artists = owner?.string("name").orEmpty(),
            album = data.string("tname"),
            coverUrl = normalizeCover(data.stringOrNull("pic")),
            durationMs = pageObject?.long("duration")?.times(1_000) ?: data.long("duration")?.times(1_000),
            providerUrl = "https://www.bilibili.com/video/$bvid",
        )
    }

    override suspend fun resolve(track: org.feeluown.mobile.MusicTrack, qualityPolicy: String): PlaybackPayload? {
        val (bvid, page) = parsePaged(rawIdentifier(track.providerId ?: track.id))
        val info = videoInfo(bvid).obj("data") ?: return null
        val pages = info.array("pages")
        val pageNumber = page ?: 1
        val pageInfo = pages.getOrNull(pageNumber - 1)?.asObject()
        val cid = pageInfo?.long("cid") ?: info.long("cid") ?: return null
        val response = http.getText(
            ID,
            signedQueryUrl(
                "$BASE/x/player/playurl",
                mapOf(
                    "bvid" to bvid,
                    "cid" to cid.toString(),
                    "fnval" to "4048",
                    "fourk" to "1",
                    "qn" to qualityPolicy.toBilibiliQn(),
                ),
            ),
            headers(),
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val data = response.obj("data") ?: return null
        val dash = data.obj("dash")
        val audio = dash?.array("audio")?.sortedByDescending { it.asObject().long("bandwidth") ?: 0 }?.firstOrNull()?.asObject()
        val durl = data.array("durl").firstOrNull()?.asObject()
        val url = audio?.stringOrNull("baseUrl")
            ?: audio?.stringOrNull("base_url")
            ?: durl?.stringOrNull("url")
            ?: return null
        val parts = pages.mapNotNull { element ->
            val item = element.asObject()
            item.stringOrNull("cid")?.let { cidValue ->
                PlaybackPart(
                    id = "bilibili:${item.long("cid") ?: cidValue}",
                    title = item.string("part"),
                    durationMs = item.long("duration")?.times(1_000),
                )
            }
        }
        return PlaybackPayload(
            url = url,
            title = track.title,
            artists = track.artists,
            album = track.album,
            source = ID,
            headers = headers(mapOf("Referer" to "https://www.bilibili.com/video/$bvid")),
            coverUrl = track.coverUrl,
            durationMs = (audio?.long("length") ?: durl?.long("length")) ?: track.durationMs,
            audioQuality = audio?.long("bandwidth")?.toString() ?: qualityPolicy,
            providerName = NAME,
            parts = parts,
            currentPartIndex = (page ?: 1) - 1,
        )
    }

    override suspend fun authState(): ProviderAuthState {
        val credentials = currentCredentials()
        if (credentials == null) return authState(null)
        val root = runCatching {
            http.getText(ID, "$BASE/x/web-interface/nav", headers(), cacheKey = null).value.let { providerJson.parseToJsonElement(it).asObject() }
        }.getOrNull()
        val data = root?.obj("data")
        return ProviderAuthState(
            providerId = ID,
            providerName = NAME,
            isLoggedIn = data?.boolean("isLogin") == true || credentials.cookies.isNotEmpty(),
            userName = data?.stringOrNull("uname"),
        )
    }

    override suspend fun similarTracks(track: org.feeluown.mobile.MusicTrack): List<org.feeluown.mobile.MusicTrack> {
        val (bvid, _) = parsePaged(rawIdentifier(track.providerId ?: track.id))
        val info = videoInfo(bvid).obj("data") ?: return emptyList()
        val aid = info.long("aid") ?: return emptyList()
        val root = http.getText(ID, queryUrl("$BASE/x/web-interface/archive/related", mapOf("aid" to aid.toString())), headers(), cacheKey = "bilibili:related:$bvid", cachePolicy = ProviderCachePolicies.recommendation)
            .value.let { providerJson.parseToJsonElement(it).asObject() }
        return root.array("data").mapNotNull { element -> searchItemToTrack(element.asObject()) }
    }

    override suspend fun hotComments(track: org.feeluown.mobile.MusicTrack): List<ProviderComment> {
        val (bvid, _) = parsePaged(rawIdentifier(track.providerId ?: track.id))
        val aid = videoInfo(bvid).obj("data")?.long("aid") ?: return emptyList()
        val root = http.getText(ID, queryUrl("$BASE/x/v2/reply", mapOf("type" to "1", "oid" to aid.toString(), "ps" to "20")), headers(), cacheKey = "bilibili:comments:$bvid", cachePolicy = ProviderCachePolicies.detail)
            .value.let { providerJson.parseToJsonElement(it).asObject() }
        return root.obj("data")?.array("replies").orEmpty().map { element ->
            val item = element.asObject()
            ProviderComment(
                id = item.string("rpid"),
                userName = item.obj("member")?.string("uname").orEmpty(),
                content = item.obj("content")?.string("message").orEmpty(),
                likedCount = item.long("like") ?: 0,
                timeSeconds = item.long("ctime") ?: 0,
            )
        }
    }

    override suspend fun trackVideo(track: org.feeluown.mobile.MusicTrack): ProviderVideo? {
        val (bvid, _) = parsePaged(rawIdentifier(track.providerId ?: track.id))
        return video(bvid, track.title, track.artists, track.coverUrl, track.durationMs, "https://www.bilibili.com/video/$bvid")
    }

    override suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload {
        val track = trackDetail(splitResourceId(video.id, "video").second) ?: return VideoPlaybackPayload(video = video)
        val payload = resolve(track, AudioQualityPolicy.High.policy) ?: return VideoPlaybackPayload(video = video)
        return VideoPlaybackPayload(video = video, url = payload.url, audioUrl = payload.url, headers = payload.headers, quality = payload.audioQuality)
    }

    override suspend fun loadFeature(feature: ProviderFeature, offset: Int, limit: Int): ProviderContentSection {
        if (feature.id != "bilibili_popular_videos") return ProviderContentSection(feature, isLoginRequired = feature.requiresLogin && !authState().isLoggedIn)
        val root = http.getText(ID, queryUrl("$BASE/x/web-interface/popular", mapOf("ps" to limit.toString(), "pn" to (offset / limit + 1).toString())), headers(), cacheKey = "bilibili:popular:${offset / limit}:$limit", cachePolicy = ProviderCachePolicies.recommendation)
            .value.let { providerJson.parseToJsonElement(it).asObject() }
        val tracks = root.obj("data")?.array("list").orEmpty().mapNotNull { searchItemToTrack(it.asObject()) }
        return ProviderContentSection(feature, tracks = tracks, nextOffset = offset + tracks.size, hasMore = tracks.size == limit)
    }

    private suspend fun videoInfo(bvid: String) = http.getText(
        ID,
        queryUrl("$BASE/x/web-interface/view", mapOf("bvid" to bvid)),
        headers(),
        cacheKey = "bilibili:view:$bvid",
        cachePolicy = ProviderCachePolicies.detail,
    ).value.let { providerJson.parseToJsonElement(it).asObject() }

    private suspend fun signedQueryUrl(base: String, params: Map<String, String>): String {
        val keys = wbiKeys()
            ?: return queryUrl(base, params)
        val signed = (params + ("wts" to (currentTimeMillis() / 1_000).toString()))
            .entries
            .sortedBy { it.key }
            .map { entry -> entry.key to entry.value.filterNot { it in "!'()*" } }
        val query = signed.joinToString("&") { (key, value) ->
            "${encodeQueryComponent(key)}=${encodeQueryComponent(value)}"
        }
        return "$base?$query&w_rid=${md5Hex(query + keys.mixinKey)}"
    }

    private suspend fun wbiKeys(): WbiKeys? = wbiMutex.withLock {
        wbiKeys ?: runCatching {
            val data = http.getText(
                providerId = ID,
                url = "$BASE/x/web-interface/nav",
                headers = headers(),
                cacheKey = "bilibili:nav",
                cachePolicy = ProviderCachePolicies.detail,
            ).value.let { providerJson.parseToJsonElement(it).asObject().obj("data") }
            val image = data?.obj("wbi_img")
            val imageKey = image?.stringOrNull("img_url")?.keyFromUrl()
            val subKey = image?.stringOrNull("sub_url")?.keyFromUrl()
            if (imageKey.isNullOrBlank() || subKey.isNullOrBlank()) return@runCatching null
            WbiKeys(mixinKey(imageKey + subKey))
        }.getOrNull()?.also { wbiKeys = it }
    }

    private fun searchItemToTrack(item: kotlinx.serialization.json.JsonObject): org.feeluown.mobile.MusicTrack? {
        val bvid = item.stringOrNull("bvid") ?: return null
        return track(
            identifier = bvid,
            title = stripHtml(item.string("title")),
            artists = item.string("author").ifBlank { item.obj("owner")?.string("name").orEmpty() },
            album = "",
            coverUrl = normalizeCover(item.stringOrNull("pic")),
            durationMs = parseDurationMs(item.string("duration")) ?: item.long("duration")?.times(1_000),
            providerUrl = "https://www.bilibili.com/video/$bvid",
        )
    }

    private suspend fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        putAll(authenticatedHeaders(mapOf("Referer" to "https://www.bilibili.com/", "Accept" to "application/json, text/plain, */*")))
        putAll(extra)
    }

    private fun rawIdentifier(value: String): String = splitResourceId(value).second.ifBlank { value.substringAfterLast(':') }

    private fun parsePaged(value: String): Pair<String, Int?> {
        if (!value.startsWith("paged_")) return value to null
        val parts = value.removePrefix("paged_").split("__", limit = 2)
        return parts.first() to parts.getOrNull(1)?.toIntOrNull()
    }

    private fun String.toBilibiliQn(): String = when (this) {
        AudioQualityPolicy.Low.policy -> "32"
        AudioQualityPolicy.Standard.policy -> "64"
        AudioQualityPolicy.High.policy, AudioQualityPolicy.Highest.policy -> "80"
        else -> "80"
    }

    private fun stripHtml(value: String): String = value
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    private fun normalizeCover(value: String?): String? = value?.let { if (it.startsWith("//")) "https:$it" else it }

    private fun parseDurationMs(value: String): Long? {
        val parts = value.split(':').mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) return null
        return parts.fold(0L) { total, part -> total * 60 + part } * 1_000
    }

    private fun String.keyFromUrl(): String = substringAfterLast('/').substringBeforeLast('.')

    private fun encodeQueryComponent(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val number = byte.toInt() and 0xff
            when {
                number == 0x20 -> append('+')
                number in 0x30..0x39 || number in 0x41..0x5a || number in 0x61..0x7a || number in setOf(45, 46, 95, 126) -> append(number.toChar())
                else -> {
                    append('%')
                    append("0123456789ABCDEF"[number ushr 4])
                    append("0123456789ABCDEF"[number and 0x0f])
                }
            }
        }
    }

    private fun mixinKey(value: String): String = MIXIN_KEY_TABLE.indices
        .mapNotNull { index -> value.getOrNull(MIXIN_KEY_TABLE[index]) }
        .joinToString("")
        .take(32)

    private data class WbiKeys(val mixinKey: String)

    private companion object {
        const val ID = "bilibili"
        const val NAME = "哔哩哔哩"
        const val BASE = "https://api.bilibili.com"
        val MIXIN_KEY_TABLE = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
        )
        val INFO = ProviderInfo(
            providerId = ID,
            providerName = NAME,
            loginConfig = org.feeluown.mobile.ProviderLoginConfig(
                "https://passport.bilibili.com/h5-app/passport/login?gourl=https%3A%2F%2Fm.bilibili.com%2F",
                listOf(listOf("SESSDATA", "bili_jct")),
            ),
        )
        val CAPABILITIES = ProviderCapabilities(providerId = ID, providerName = NAME, canAddSongToPlaylist = true, canRemoveSongFromPlaylist = true)
        val FEATURES = listOf(
            ProviderFeature("bilibili_popular_videos", ID, NAME, "热门视频", ProviderFeatureCategory.Music, ProviderContentType.Songs, false),
            ProviderFeature("bilibili_user_playlists", ID, NAME, "我的歌单", ProviderFeatureCategory.MinePlaylists, ProviderContentType.Playlists, true),
            ProviderFeature("bilibili_favorite_playlists", ID, NAME, "收藏歌单", ProviderFeatureCategory.MineFavoritePlaylists, ProviderContentType.Playlists, true),
        )
    }
}
