package org.feeluown.mobile.provider.netease

import io.ktor.http.Parameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.ProviderAuthState
import org.feeluown.mobile.ProviderContentSection
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderFeatureFilterCodec
import org.feeluown.mobile.ProviderMediaItem
import org.feeluown.mobile.ProviderMediaItemType
import org.feeluown.mobile.ProviderPlaylist
import org.feeluown.mobile.ProviderPlaylistDetail
import org.feeluown.mobile.ProviderVideo
import org.feeluown.mobile.provider.core.array
import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.boolean
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.network.ProviderCachePolicies
import org.feeluown.mobile.provider.core.network.ProviderCachePolicy
import org.feeluown.mobile.provider.core.network.ProviderRequestKind

internal class NeteaseFeatureLoader(
    private val context: NeteaseFeatureContext,
) {
    private var styleTagCache: List<NeteaseStyleTag>? = null
    private val styleCursorPages = mutableMapOf<String, MutableMap<Int, String>>()

    suspend fun load(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        if (feature.requiresLogin && !context.authState().isLoggedIn) {
            return ProviderContentSection(feature, isLoginRequired = true)
        }
        val request = parseNeteaseFeatureRequest(feature.id)
        return when (request.baseId) {
            "netease_daily_songs" -> {
                val root = weApi("$BASE/weapi/v3/discovery/recommend/songs", "{}")
                val songs = root.obj("data")?.array("dailySongs").orEmpty()
                ProviderContentSection(
                    feature,
                    tracks = songs.drop(offset).take(limit).map(context.song),
                    nextOffset = offset + limit,
                    hasMore = songs.size > offset + limit,
                )
            }
            "netease_recommended_new_songs" -> {
                val requestLimit = (offset + limit).coerceAtLeast(limit)
                val root = weApi(
                    "$BASE/weapi/personalized/newsong",
                    """{"type":"recommend","limit":$requestLimit,"areaId":0}""",
                )
                val values = root.array("result")
                val songs = values.map { value -> value.asObject().obj("song") ?: value }
                val page = songs.drop(offset).take(limit)
                ProviderContentSection(
                    feature = feature,
                    tracks = context.loadSongs(page),
                    nextOffset = offset + page.size,
                    hasMore = songs.size > offset + page.size,
                )
            }
            "netease_radio" -> {
                val songs = context.getJson(
                    "$BASE/api/radio/get",
                    context.neteaseAuthenticatedHeaders(emptyMap()),
                    null,
                    ProviderCachePolicies.none,
                ).array("data")
                ProviderContentSection(
                    feature,
                    tracks = songs.drop(offset).take(limit).map(context.song),
                    nextOffset = offset + limit,
                    hasMore = songs.size > offset + limit,
                )
            }
            "netease_toplists" -> {
                val root = context.getJson(
                    "$BASE/api/toplist",
                    context.authenticatedHeaders(emptyMap()),
                    "netease:toplist",
                    ProviderCachePolicies.recommendation,
                )
                val playlists = root.array("list")
                ProviderContentSection(
                    feature,
                    playlists = playlists.drop(offset).take(limit).map { context.playlist(it.asObject()) },
                    nextOffset = offset + limit,
                    hasMore = playlists.size > offset + limit,
                )
            }
            NETEASE_PLAYLIST_SQUARE -> playlistSquare(feature, request, offset, limit)
            NETEASE_ARTIST_SQUARE -> artistSquare(feature, request, offset, limit)
            NETEASE_MV_SQUARE -> mvSquare(feature, request, offset, limit)
            NETEASE_STYLES -> styleFeature(feature, request, offset, limit)
            "netease_new_songs" -> {
                val root = weApi(
                    "$BASE/weapi/v1/discovery/new/songs",
                    """{"areaId":0,"total":true}""",
                )
                val songs = root.array("data")
                val page = songs.drop(offset).take(limit)
                ProviderContentSection(
                    feature = feature,
                    tracks = page.map(context.song),
                    nextOffset = offset + page.size,
                    hasMore = songs.size > offset + page.size,
                )
            }
            "netease_new_albums" -> {
                val root = weApi(
                    "$BASE/weapi/album/new",
                    """{"limit":$limit,"offset":$offset,"total":true,"area":"ALL"}""",
                )
                val values = root.array("albums")
                ProviderContentSection(
                    feature = feature,
                    mediaItems = values.mapNotNull { value ->
                        runCatching { context.album(value.asObject()) }.getOrNull()
                    },
                    nextOffset = offset + values.size,
                    hasMore = root.boolean("more") ||
                        root.int("total")?.let { offset + values.size < it } == true ||
                        values.size == limit,
                )
            }
            "netease_top_artists" -> {
                val root = weApi(
                    "$BASE/weapi/artist/top",
                    """{"limit":$limit,"offset":$offset,"total":true}""",
                )
                val values = root.array("artists")
                ProviderContentSection(
                    feature = feature,
                    mediaItems = values.mapNotNull { value ->
                        runCatching { context.artist(value.asObject()) }.getOrNull()
                    },
                    nextOffset = offset + values.size,
                    hasMore = root.boolean("more") || values.size == limit,
                )
            }
            "netease_highquality_playlists" -> {
                val requestLimit = (offset + limit).coerceAtLeast(limit)
                val root = weApi(
                    "$BASE/weapi/playlist/highquality/list",
                    """{"cat":"全部","limit":$requestLimit,"lasttime":0,"total":true}""",
                )
                val values = root.array("playlists")
                val page = values.drop(offset).take(limit)
                ProviderContentSection(
                    feature = feature,
                    playlists = page.mapNotNull { value ->
                        runCatching { context.playlist(value.asObject()) }.getOrNull()
                    },
                    nextOffset = offset + page.size,
                    hasMore = values.size > offset + page.size || root.boolean("more"),
                )
            }
            "netease_recommended_mvs" -> {
                val root = weApi("$BASE/weapi/personalized/mv", "{}")
                val values = root.array("result")
                val page = values.drop(offset).take(limit)
                ProviderContentSection(
                    feature = feature,
                    videos = page.mapNotNull { value ->
                        runCatching { context.video(value.asObject()) }.getOrNull()
                    },
                    nextOffset = offset + page.size,
                    hasMore = values.size > offset + page.size,
                )
            }
            "netease_top_mvs" -> {
                val root = weApi(
                    "$BASE/weapi/mv/toplist",
                    """{"area":"","limit":$limit,"offset":$offset,"total":true}""",
                )
                val values = root.array("data")
                ProviderContentSection(
                    feature = feature,
                    videos = values.mapNotNull { value ->
                        runCatching { context.video(value.asObject()) }.getOrNull()
                    },
                    nextOffset = offset + values.size,
                    hasMore = root.boolean("hasMore") || values.size == limit,
                )
            }
            "netease_daily_playlists" -> {
                val root = weApi("$BASE/api/discovery/recommend/resource", "{}")
                val playlists = root.array("recommend")
                val page = playlists.drop(offset).take(limit)
                ProviderContentSection(
                    feature,
                    playlists = page.map { context.playlist(it.asObject()) },
                    nextOffset = offset + page.size,
                    hasMore = playlists.size > offset + page.size,
                )
            }
            "netease_user_playlists", "netease_favorite_playlists" -> userPlaylists(feature, request, offset, limit)
            "netease_favorite_songs" -> favoriteSongs(feature, offset, limit)
            "netease_cloud_songs" -> context.cloudSongs(feature, offset, limit)
            "netease_favorite_artists", "netease_favorite_albums" -> {
                val endpoint = if (request.baseId == "netease_favorite_artists") "artist/sublist" else "album/sublist"
                val root = weApi(
                    "$BASE/weapi/$endpoint",
                    """{"limit":$limit,"offset":$offset,"csrf_token":"${context.csrfToken().jsonString()}"}""",
                )
                val type = if (request.baseId == "netease_favorite_artists") {
                    ProviderMediaItemType.Artist
                } else {
                    ProviderMediaItemType.Album
                }
                val items = root.array("data").map { value ->
                    if (type == ProviderMediaItemType.Artist) {
                        context.artist(value.asObject())
                    } else {
                        context.album(value.asObject())
                    }
                }
                ProviderContentSection(
                    feature,
                    mediaItems = items.take(limit),
                    nextOffset = offset + items.size,
                    hasMore = items.size == limit,
                )
            }
            else -> ProviderContentSection(feature, errorMessage = "网易云音乐暂不支持该内容")
        }
    }

    private suspend fun userPlaylists(
        feature: ProviderFeature,
        request: NeteaseFeatureRequest,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val uid = context.currentUserId()
            ?: return ProviderContentSection(feature, errorMessage = "无法读取网易云音乐用户信息")
        val playlists = context.userPlaylists(uid).filter { item ->
            val subscribed = item.boolean("subscribed")
            if (request.baseId == "netease_favorite_playlists") subscribed else !subscribed
        }
        val page = playlists.drop(offset).take(limit).map { item ->
            if (item.boolean("subscribed")) context.subscribedPlaylist(item) else context.ownedPlaylist(item)
        }
        return ProviderContentSection(
            feature = feature,
            playlists = page,
            nextOffset = offset + page.size,
            hasMore = playlists.size > offset + page.size,
        )
    }

    private suspend fun favoriteSongs(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val uid = context.currentUserId()
            ?: return ProviderContentSection(feature, errorMessage = "无法读取网易云音乐用户信息")
        val playlists = context.userPlaylists(uid)
        val favoritePlaylist = playlists.firstOrNull { it.string("id") == uid }
            ?: playlists.firstOrNull { !it.boolean("subscribed") }
            ?: return ProviderContentSection(feature, errorMessage = "未找到我喜欢的音乐歌单")
        val detail = context.playlistDetail(context.ownedPlaylist(favoritePlaylist), offset, limit)
        return ProviderContentSection(
            feature,
            tracks = detail.tracks,
            nextOffset = detail.tracksNextOffset,
            hasMore = detail.tracksHasMore,
        )
    }

    private suspend fun playlistSquare(
        feature: ProviderFeature,
        request: NeteaseFeatureRequest,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val cat = request.params["cat"].orEmpty().ifBlank { "全部" }
        val order = request.params["order"].orEmpty().ifBlank { "hot" }
        val root = weApi(
            "$BASE/weapi/playlist/list",
            """{"cat":"${cat.jsonString()}","order":"${order.jsonString()}","limit":$limit,"offset":$offset,"total":true}""",
        )
        val values = root.array("playlists")
        return ProviderContentSection(
            feature = ProviderFeatureFilterCodec.attach(feature, playlistSquareFilters(cat, order)),
            playlists = values.mapNotNull { value ->
                runCatching { context.playlist(value.asObject()) }.getOrNull()
            },
            nextOffset = offset + values.size,
            hasMore = root.boolean("more") ||
                root.int("total")?.let { offset + values.size < it } == true ||
                values.size == limit,
        )
    }

    private suspend fun artistSquare(
        feature: ProviderFeature,
        request: NeteaseFeatureRequest,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val area = request.params["area"].orEmpty().ifBlank { "-1" }
        val type = request.params["type"].orEmpty().ifBlank { "-1" }
        val initial = request.params["initial"].orEmpty().ifBlank { "-1" }
        val root = weApi(
            "$BASE/weapi/v1/artist/list",
            """{"initial":$initial,"offset":$offset,"limit":$limit,"total":true,"type":$type,"area":$area}""",
        )
        val values = root.array("artists").ifEmpty { root.obj("data")?.array("artists").orEmpty() }
        return ProviderContentSection(
            feature = ProviderFeatureFilterCodec.attach(feature, artistSquareFilters(area, type, initial)),
            mediaItems = values.mapNotNull { value ->
                runCatching { context.artist(value.asObject()) }.getOrNull()
            },
            nextOffset = offset + values.size,
            hasMore = root.boolean("more") || root.obj("data")?.boolean("more") == true || values.size == limit,
        )
    }

    private suspend fun mvSquare(
        feature: ProviderFeature,
        request: NeteaseFeatureRequest,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val area = request.params["area"].orEmpty().ifBlank { "全部" }
        val type = request.params["type"].orEmpty().ifBlank { "全部" }
        val order = request.params["order"].orEmpty().ifBlank { "上升最快" }
        val tags = """{"地区":"${area.jsonString()}","类型":"${type.jsonString()}","排序":"${order.jsonString()}"}"""
        val root = context.postFormJson(
            "$BASE/api/mv/all",
            Parameters.build {
                append("tags", tags)
                append("offset", offset.toString())
                append("total", "true")
                append("limit", limit.toString())
            },
            context.neteaseAuthenticatedHeaders(mapOf("Referer" to "https://music.163.com/")),
        )
        val values = root.array("data")
        return ProviderContentSection(
            feature = ProviderFeatureFilterCodec.attach(feature, mvSquareFilters(area, type, order)),
            videos = values.mapNotNull { value -> runCatching { context.video(value.asObject()) }.getOrNull() },
            nextOffset = offset + values.size,
            hasMore = root.boolean("hasMore") || root.boolean("more") || values.size == limit,
        )
    }

    private suspend fun styleFeature(
        feature: ProviderFeature,
        request: NeteaseFeatureRequest,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val styles = loadStyleTags()
        if (styles.isEmpty()) return ProviderContentSection(feature, errorMessage = "暂无曲风数据")
        val requestedTag = request.params["tagId"]
        val tagId = requestedTag?.takeIf { candidate -> styles.any { it.id == candidate } } ?: styles.first().id
        val kind = request.params["kind"]?.takeIf { it in STYLE_KINDS } ?: "songs"
        val cursorKey = "$tagId:$kind"
        val cursor = if (offset == 0) "0" else styleCursorPages[cursorKey]?.get(offset) ?: "0"
        val endpoint = when (kind) {
            "playlists" -> "playlist"
            "albums" -> "album"
            "artists" -> "artist"
            else -> "song"
        }
        val root = weApi(
            "$BASE/weapi/style-tag/home/$endpoint",
            buildString {
                append("{\"cursor\":")
                append(cursor.toLongOrNull()?.toString() ?: "\"${cursor.jsonString()}\"")
                append(",\"size\":$limit,\"tagId\":")
                append(tagId.toLongOrNull()?.toString() ?: "\"${tagId.jsonString()}\"")
                append(",\"sort\":0}")
            },
        )
        val values = styleResourceValues(root, kind)
        val data = root.obj("data")
        val nextCursor = data?.stringOrNull("cursor")
            ?: data?.long("cursor")?.toString()
            ?: root.stringOrNull("cursor")
            ?: root.long("cursor")?.toString()
        val moreFlag = data?.boolean("hasMore") == true || data?.boolean("more") == true ||
            root.boolean("hasMore") || root.boolean("more")
        val canContinue = !nextCursor.isNullOrBlank() && nextCursor != cursor && nextCursor != "0" &&
            (moreFlag || values.size >= limit)
        val nextOffset = offset + 1
        if (canContinue) {
            styleCursorPages.getOrPut(cursorKey) { mutableMapOf() }[nextOffset] = nextCursor.orEmpty()
        }
        val presentationFeature = ProviderFeatureFilterCodec.attach(feature, styleFilters(styles, tagId, kind))
        return when (kind) {
            "playlists" -> ProviderContentSection(
                feature = presentationFeature,
                playlists = values.mapNotNull { value -> runCatching { context.playlist(value.asObject()) }.getOrNull() },
                nextOffset = nextOffset,
                hasMore = canContinue,
            )
            "albums" -> ProviderContentSection(
                feature = presentationFeature,
                mediaItems = values.mapNotNull { value -> runCatching { context.album(value.asObject()) }.getOrNull() },
                nextOffset = nextOffset,
                hasMore = canContinue,
            )
            "artists" -> ProviderContentSection(
                feature = presentationFeature,
                mediaItems = values.mapNotNull { value -> runCatching { context.artist(value.asObject()) }.getOrNull() },
                nextOffset = nextOffset,
                hasMore = canContinue,
            )
            else -> ProviderContentSection(
                feature = presentationFeature,
                tracks = context.loadSongs(values),
                nextOffset = nextOffset,
                hasMore = canContinue,
            )
        }
    }

    private suspend fun loadStyleTags(): List<NeteaseStyleTag> {
        styleTagCache?.takeIf { it.isNotEmpty() }?.let { return it }
        val root = weApi("$BASE/weapi/tag/list/get", "{}")
        val candidates = buildList<JsonElement> {
            addAll(root.array("tagList"))
            addAll(root.array("tags"))
            addAll(root.array("list"))
            addAll(root.array("data"))
            root.obj("data")?.let { data ->
                add(data)
                addAll(data.array("tagList"))
                addAll(data.array("tags"))
                addAll(data.array("list"))
                addAll(data.array("childrenTags"))
            }
        }
        return flattenStyleTags(candidates).distinctBy { it.id }.also { styleTagCache = it }
    }

    private fun flattenStyleTags(values: Iterable<JsonElement>): List<NeteaseStyleTag> = buildList {
        values.forEach { element ->
            val value = runCatching { element.asObject() }.getOrNull() ?: return@forEach
            val id = value.string("tagId").ifBlank { value.string("id") }
            val name = value.string("tagName").ifBlank { value.string("name") }
            if (id.isNotBlank() && name.isNotBlank()) add(NeteaseStyleTag(id, name))
            listOf("childrenTags", "children", "tags", "tagList", "list").forEach { key ->
                val children = value.array(key)
                if (children.isNotEmpty()) addAll(flattenStyleTags(children))
            }
        }
    }

    private fun styleResourceValues(root: JsonObject, kind: String): List<JsonElement> {
        val keys = when (kind) {
            "playlists" -> listOf("playlists", "playlistList", "list")
            "albums" -> listOf("albums", "albumList", "list")
            "artists" -> listOf("artists", "artistList", "list")
            else -> listOf("songs", "songList", "list")
        }
        val data = root.obj("data")
        listOfNotNull(data, root).forEach { container ->
            keys.forEach { key ->
                val values = container.array(key)
                if (values.isNotEmpty()) return values
            }
        }
        return root.array("data")
    }

    private suspend fun weApi(url: String, json: String): JsonObject =
        context.weApiPost(url, json, ProviderRequestKind.Auth)

    private fun String.jsonString(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val BASE = "https://music.163.com"
        val STYLE_KINDS = setOf("songs", "playlists", "albums", "artists")
    }
}

internal data class NeteaseFeatureContext(
    val authState: suspend () -> ProviderAuthState,
    val weApiPost: suspend (String, String, ProviderRequestKind) -> JsonObject,
    val getJson: suspend (String, Map<String, String>, String?, ProviderCachePolicy) -> JsonObject,
    val postFormJson: suspend (String, Parameters, Map<String, String>) -> JsonObject,
    val authenticatedHeaders: suspend (Map<String, String>) -> Map<String, String>,
    val neteaseAuthenticatedHeaders: suspend (Map<String, String>) -> Map<String, String>,
    val song: (JsonElement) -> MusicTrack,
    val loadSongs: suspend (Iterable<JsonElement>) -> List<MusicTrack>,
    val playlist: (JsonObject) -> ProviderPlaylist,
    val artist: (JsonObject) -> ProviderMediaItem,
    val album: (JsonObject) -> ProviderMediaItem,
    val video: (JsonObject) -> ProviderVideo,
    val currentUserId: suspend () -> String?,
    val userPlaylists: suspend (String) -> List<JsonObject>,
    val ownedPlaylist: (JsonObject) -> ProviderPlaylist,
    val subscribedPlaylist: (JsonObject) -> ProviderPlaylist,
    val playlistDetail: suspend (ProviderPlaylist, Int, Int) -> ProviderPlaylistDetail,
    val cloudSongs: suspend (ProviderFeature, Int, Int) -> ProviderContentSection,
    val csrfToken: suspend () -> String,
)
