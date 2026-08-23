package org.feeluown.mobile.provider.qqmusic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.ProviderContentSection
import org.feeluown.mobile.ProviderContentType
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderFeatureCategory
import org.feeluown.mobile.ProviderFeatureFilterCodec
import org.feeluown.mobile.ProviderMediaItem
import org.feeluown.mobile.ProviderMediaItemType
import org.feeluown.mobile.ProviderPlaylist
import org.feeluown.mobile.ProviderPlaylistDetail
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.ProviderVideo
import org.feeluown.mobile.TrackSourceType
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.array
import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.boolean
import org.feeluown.mobile.provider.core.cookieHeader
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.md5Hex
import org.feeluown.mobile.provider.core.mediaItemKey
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.parseCookies
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

/**
 * Adds QQ Music public discovery and multi-type search on top of [QQMusicProvider].
 * Playback, authentication, user-library operations, regular playlist details,
 * artist/album details and video playback stay delegated to the core provider.
 */
class QQMusicContentProvider(
    private val http: ProviderHttpClient,
    private val credentials: ProviderCredentialStore,
    private val delegate: QQMusicProvider = QQMusicProvider(http, credentials),
) : KotlinMusicProvider by delegate {
    override val features: List<ProviderFeature> = delegate.features + EXTRA_FEATURES

    override suspend fun search(keyword: String): ProviderSearchResults {
        val base = delegate.search(keyword)
        val extras = runCatching { searchExtras(keyword) }.getOrElse { ProviderSearchResults() }
        return base.copy(
            playlists = extras.playlists,
            artists = extras.artists,
            albums = extras.albums,
            videos = extras.videos,
        )
    }

    /**
     * Fetches only non-song result types so repository integration can preserve
     * the existing QQ song-search request instead of issuing it twice.
     */
    internal suspend fun searchExtras(keyword: String): ProviderSearchResults {
        if (keyword.isBlank()) return ProviderSearchResults()
        val root = rpc(
            """
            {
              "singerSearch":{"module":"music.search.SearchCgiService","method":"DoSearchForQQMusicDesktop","param":{"num_per_page":30,"page_num":1,"search_type":1,"query":${jsonString(keyword)}}},
              "albumSearch":{"module":"music.search.SearchCgiService","method":"DoSearchForQQMusicDesktop","param":{"num_per_page":30,"page_num":1,"search_type":2,"query":${jsonString(keyword)}}},
              "playlistSearch":{"module":"music.search.SearchCgiService","method":"DoSearchForQQMusicDesktop","param":{"num_per_page":30,"page_num":1,"search_type":3,"query":${jsonString(keyword)}}},
              "mvSearch":{"module":"music.search.SearchCgiService","method":"DoSearchForQQMusicDesktop","param":{"num_per_page":30,"page_num":1,"search_type":4,"query":${jsonString(keyword)}}}
            }
            """.trimIndent(),
            cacheKey = "qqmusic:search-extras:$keyword",
            cachePolicy = ProviderCachePolicies.search,
        )
        val singerBody = searchBody(root, "singerSearch")
        val albumBody = searchBody(root, "albumSearch")
        val playlistBody = searchBody(root, "playlistSearch")
        val mvBody = searchBody(root, "mvSearch")

        val singerValues = firstNonEmpty(
            singerBody.obj("singer")?.array("list").orEmpty(),
            singerBody.array("singer"),
            singerBody.array("list"),
        )
        val albumValues = firstNonEmpty(
            albumBody.obj("album")?.array("list").orEmpty(),
            albumBody.array("album"),
            albumBody.array("list"),
        )
        val playlistValues = firstNonEmpty(
            playlistBody.obj("songlist")?.array("list").orEmpty(),
            playlistBody.obj("playlist")?.array("list").orEmpty(),
            playlistBody.array("songlist"),
            playlistBody.array("playlist"),
            playlistBody.array("list"),
        )
        val videoValues = firstNonEmpty(
            mvBody.obj("mv")?.array("list").orEmpty(),
            mvBody.obj("video")?.array("list").orEmpty(),
            mvBody.array("mv"),
            mvBody.array("video"),
            mvBody.array("list"),
        )

        return ProviderSearchResults(
            playlists = playlistValues.mapNotNull { value ->
                runCatching { qqPlaylist(value.asObject()) }.getOrNull()
            }.filter(::hasResourceId).distinctBy { it.id },
            artists = singerValues.mapNotNull { value ->
                runCatching { qqArtist(value.asObject()) }.getOrNull()
            }.filter(::hasResourceId).distinctBy { it.id },
            albums = albumValues.mapNotNull { value ->
                runCatching { qqAlbum(value.asObject()) }.getOrNull()
            }.filter(::hasResourceId).distinctBy { it.id },
            videos = videoValues.mapNotNull { value ->
                runCatching { qqVideo(value.asObject()) }.getOrNull()
            }.filter(::hasResourceId).distinctBy { it.id },
        )
    }

    override suspend fun loadFeature(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val request = parseQQMusicFeatureRequest(feature.id)
        return when (request.baseId) {
            QQMUSIC_TOPLISTS -> loadToplists(feature, offset, limit)
            QQMUSIC_PLAYLIST_SQUARE -> loadPlaylistSquare(feature, request, offset, limit)
            QQMUSIC_ARTIST_SQUARE -> loadArtistSquare(feature, request, offset, limit)
            QQMUSIC_NEW_ALBUMS -> loadNewAlbums(feature, offset, limit)
            QQMUSIC_MV_SQUARE -> loadMvSquare(feature, request, offset, limit)
            else -> delegate.loadFeature(feature, offset, limit)
        }
    }

    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> =
        if (playlist.isQQMusicToplist()) {
            playlistDetail(playlist, 0, 1_000).tracks
        } else {
            delegate.playlistTracks(playlist)
        }

    override suspend fun playlistDetail(
        playlist: ProviderPlaylist,
        offset: Int,
        limit: Int,
    ): ProviderPlaylistDetail {
        if (!playlist.isQQMusicToplist()) {
            return delegate.playlistDetail(playlist, offset, limit)
        }
        val spec = parseToplistIdentifier(resourceId(playlist.id))
            ?: return ProviderPlaylistDetail(playlist)
        val periodJson = spec.period
            .takeIf(String::isNotBlank)
            ?.let { ",\"period\":${jsonString(it)}" }
            .orEmpty()
        val root = rpc(
            """
            {"detail":{"module":"musicToplist.ToplistInfoServer","method":"GetDetail","param":{"topId":${spec.topId},"offset":$offset,"num":$limit$periodJson}}}
            """.trimIndent(),
            cacheKey = "qqmusic:toplist:${spec.topId}:${spec.period}:$offset:$limit",
            cachePolicy = ProviderCachePolicies.recommendation,
        )
        val detailData = root.obj("detail")?.obj("data")
            ?: root.obj("data")
            ?: JsonObject(emptyMap())
        val metadata = detailData.obj("data") ?: detailData
        val songValues = firstNonEmpty(
            detailData.array("songInfoList"),
            metadata.array("songInfoList"),
            metadata.array("song"),
            metadata.array("songlist"),
        )
        val tracks = songValues.mapNotNull { value ->
            val item = value.asObject().obj("songInfo")
                ?: value.asObject().obj("data")
                ?: value.asObject()
            runCatching { qqTrack(item) }.getOrNull()
        }.filter(::hasResourceId)
        val total = metadata.int("totalNum")
            ?: metadata.int("total")
            ?: metadata.int("songNum")
            ?: playlist.trackCount
        val actualPeriod = metadata.string("period").ifBlank { spec.period }
        val actual = playlist.copy(
            id = toplistPlaylistId(spec.topId, actualPeriod),
            title = metadata.string("title").ifBlank { playlist.title },
            coverUrl = metadata.stringOrNull("headPicUrl")
                ?: metadata.stringOrNull("frontPicUrl")
                ?: metadata.stringOrNull("picUrl")
                ?: playlist.coverUrl,
            description = metadata.string("intro")
                .ifBlank { metadata.string("desc") }
                .ifBlank { playlist.description },
            playCount = metadata.long("listenNum")
                ?: metadata.long("listenCount")
                ?: playlist.playCount,
            trackCount = total ?: tracks.size,
            providerUrl = "https://y.qq.com/n/ryqq/toplist/${spec.topId}",
        )
        val nextOffset = offset + songValues.size
        return ProviderPlaylistDetail(
            playlist = actual,
            tracks = tracks,
            tracksNextOffset = nextOffset,
            tracksHasMore = songValues.isNotEmpty() &&
                (total?.let { nextOffset < it } ?: (songValues.size >= limit)),
        )
    }

    private suspend fun loadToplists(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val root = rpc(
            """
            {"topList":{"module":"musicToplist.ToplistInfoServer","method":"GetAll","param":{}},"comm":{"ct":23,"cv":0,"platform":"h5","needNewCode":1}}
            """.trimIndent(),
            cacheKey = "qqmusic:toplists",
            cachePolicy = ProviderCachePolicies.recommendation,
        )
        val groups = root.obj("topList")?.obj("data")?.array("group").orEmpty()
        val values = groups.flatMap { groupValue ->
            val group = groupValue.asObject()
            firstNonEmpty(
                group.array("toplist"),
                group.array("topList"),
                group.array("list"),
            )
        }
        val playlists = values.mapNotNull { value ->
            val item = value.asObject()
            val topId = item.int("topId")
                ?: item.int("id")
                ?: item.string("topId").toIntOrNull()
                ?: item.string("id").toIntOrNull()
                ?: return@mapNotNull null
            val period = item.string("period")
                .ifBlank { item.string("update_key") }
                .ifBlank { item.string("updateKey") }
            ProviderPlaylist(
                id = toplistPlaylistId(topId, period),
                title = item.string("title")
                    .ifBlank { item.string("topTitle") }
                    .ifBlank { item.string("name") },
                providerId = ID,
                providerName = NAME,
                coverUrl = item.stringOrNull("headPicUrl")
                    ?: item.stringOrNull("frontPicUrl")
                    ?: item.stringOrNull("picUrl"),
                description = item.string("intro").ifBlank { item.string("updateTips") },
                playCount = item.long("listenNum") ?: item.long("listenCount"),
                trackCount = item.int("totalNum")
                    ?: item.int("songNum")
                    ?: item.array("song").size.takeIf { it > 0 },
                providerUrl = "https://y.qq.com/n/ryqq/toplist/$topId",
            )
        }.filter { it.title.isNotBlank() }
        val page = playlists.drop(offset).take(limit)
        return ProviderContentSection(
            feature = feature,
            playlists = page,
            nextOffset = offset + page.size,
            hasMore = playlists.size > offset + page.size,
        )
    }

    private suspend fun loadPlaylistSquare(
        feature: ProviderFeature,
        request: QQMusicFeatureRequest,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val categoryId = request.params["categoryId"]
            .orEmpty()
            .ifBlank { QQ_DEFAULT_PLAYLIST_CATEGORY_ID }
        val sortId = request.params["sortId"]
            .orEmpty()
            .ifBlank { QQ_DEFAULT_PLAYLIST_SORT_ID }
        val categories = runCatching { loadPlaylistCategories() }.getOrDefault(emptyList())
        val end = offset + limit.coerceAtLeast(1) - 1
        val root = http.getText(
            providerId = ID,
            url = queryUrl(
                "$C_BASE/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg",
                mapOf(
                    "format" to "json",
                    "inCharset" to "utf8",
                    "outCharset" to "utf-8",
                    "picmid" to "1",
                    "categoryId" to categoryId,
                    "sortId" to sortId,
                    "sin" to offset.toString(),
                    "ein" to end.toString(),
                ),
            ),
            headers = publicHeaders(),
            cacheKey = "qqmusic:playlist-square:$categoryId:$sortId:$offset:$limit",
            cachePolicy = ProviderCachePolicies.recommendation,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val data = root.obj("data") ?: root
        val rawValues = firstNonEmpty(data.array("list"), data.array("disslist"))
        val playlists = rawValues.mapNotNull { value ->
            runCatching { qqPlaylist(value.asObject()) }.getOrNull()
        }.filter(::hasResourceId)
        val total = data.int("sum") ?: data.int("total")
        val nextOffset = offset + rawValues.size
        return ProviderContentSection(
            feature = ProviderFeatureFilterCodec.attach(
                feature,
                qqMusicPlaylistSquareFilters(categories, categoryId, sortId),
            ),
            playlists = playlists,
            nextOffset = nextOffset,
            hasMore = total?.let { nextOffset < it } ?: (rawValues.size >= limit),
        )
    }

    private suspend fun loadPlaylistCategories(): List<QQMusicPlaylistCategory> {
        val root = http.getText(
            providerId = ID,
            url = queryUrl(
                "$C_BASE/splcloud/fcgi-bin/fcg_get_diss_tag_conf.fcg",
                mapOf(
                    "format" to "json",
                    "inCharset" to "utf8",
                    "outCharset" to "utf-8",
                ),
            ),
            headers = publicHeaders(),
            cacheKey = "qqmusic:playlist-categories",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        return root.obj("data")?.array("categories").orEmpty().flatMap { categoryValue ->
            val category = categoryValue.asObject()
            firstNonEmpty(category.array("items"), category.array("item")).mapNotNull { itemValue ->
                val item = itemValue.asObject()
                val id = item.string("categoryId")
                    .ifBlank { item.int("categoryId")?.toString().orEmpty() }
                    .ifBlank { item.string("id") }
                val name = item.string("categoryName").ifBlank { item.string("name") }
                val usable = item["usable"] == null ||
                    item.boolean("usable") ||
                    item.int("usable")?.let { it != 0 } == true
                if (id.isBlank() || name.isBlank() || !usable) {
                    null
                } else {
                    QQMusicPlaylistCategory(id, name)
                }
            }
        }.distinctBy { it.id }
    }

    private suspend fun loadArtistSquare(
        feature: ProviderFeature,
        request: QQMusicFeatureRequest,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val area = request.params["area"].orEmpty().ifBlank { QQ_DEFAULT_ARTIST_FILTER }
        val sex = request.params["sex"].orEmpty().ifBlank { QQ_DEFAULT_ARTIST_FILTER }
        val genre = request.params["genre"].orEmpty().ifBlank { QQ_DEFAULT_ARTIST_FILTER }
        val index = request.params["index"].orEmpty().ifBlank { QQ_DEFAULT_ARTIST_FILTER }

        // QQ's singer-list endpoint is fixed at 80 rows per server page. Keep the
        // repository's arbitrary page size by slicing the corresponding server page.
        val serverPageStart = (offset / QQ_SINGER_PAGE_SIZE) * QQ_SINGER_PAGE_SIZE
        val localOffset = offset - serverPageStart
        val root = rpc(
            """
            {"singerList":{"module":"Music.SingerListServer","method":"get_singer_list","param":{"area":$area,"sex":$sex,"genre":$genre,"index":$index,"sin":$serverPageStart,"cur_page":${serverPageStart / QQ_SINGER_PAGE_SIZE + 1}}}}
            """.trimIndent(),
            cacheKey = "qqmusic:artist-square:$area:$sex:$genre:$index:$serverPageStart",
            cachePolicy = ProviderCachePolicies.recommendation,
        )
        val data = root.obj("singerList")?.obj("data") ?: root.obj("data") ?: root
        val rawValues = firstNonEmpty(data.array("singerlist"), data.array("list"))
        val pageValues = rawValues.drop(localOffset).take(limit)
        val artists = pageValues.mapNotNull { value ->
            val item = value.asObject().obj("singer_info") ?: value.asObject()
            runCatching { qqArtist(item) }.getOrNull()
        }.filter(::hasResourceId)
        val total = data.int("total") ?: data.int("totalNum") ?: data.int("total_num")
        val nextOffset = offset + pageValues.size
        return ProviderContentSection(
            feature = ProviderFeatureFilterCodec.attach(
                feature,
                qqMusicArtistSquareFilters(area, sex, genre, index),
            ),
            mediaItems = artists,
            nextOffset = nextOffset,
            hasMore = total?.let { nextOffset < it }
                ?: (localOffset + pageValues.size < rawValues.size || rawValues.size >= QQ_SINGER_PAGE_SIZE),
        )
    }

    private suspend fun loadNewAlbums(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val root = rpc(
            """
            {"new_album":{"module":"newalbum.NewAlbumServer","method":"get_new_album_info","param":{"area":1,"sin":$offset,"num":$limit}}}
            """.trimIndent(),
            cacheKey = "qqmusic:new-albums:$offset:$limit",
            cachePolicy = ProviderCachePolicies.recommendation,
        )
        val data = root.obj("new_album")?.obj("data") ?: root.obj("data") ?: root
        val rawValues = firstNonEmpty(
            data.array("albums"),
            data.array("album_list"),
            data.array("list"),
        )
        val albums = rawValues.mapNotNull { value ->
            val item = value.asObject().obj("album") ?: value.asObject()
            runCatching { qqAlbum(item) }.getOrNull()
        }.filter(::hasResourceId)
        val total = data.int("total") ?: data.int("totalNum") ?: data.int("total_num")
        val nextOffset = offset + rawValues.size
        return ProviderContentSection(
            feature = feature,
            mediaItems = albums,
            nextOffset = nextOffset,
            hasMore = total?.let { nextOffset < it } ?: (rawValues.size >= limit),
        )
    }

    private suspend fun loadMvSquare(
        feature: ProviderFeature,
        request: QQMusicFeatureRequest,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val area = request.params["area"].orEmpty().ifBlank { QQ_DEFAULT_MV_AREA }
        val version = request.params["version"].orEmpty().ifBlank { QQ_DEFAULT_MV_VERSION }
        val order = request.params["order"].orEmpty().ifBlank { QQ_DEFAULT_MV_ORDER }
        val root = rpc(
            """
            {"mv_list":{"module":"MvService.MvInfoProServer","method":"GetAllocMvInfo","param":{"start":$offset,"size":$limit,"version_id":$version,"area_id":$area,"order":$order}}}
            """.trimIndent(),
            cacheKey = "qqmusic:mv-square:$area:$version:$order:$offset:$limit",
            cachePolicy = ProviderCachePolicies.recommendation,
        )
        val data = root.obj("mv_list")?.obj("data") ?: root.obj("data") ?: root
        val rawValues = firstNonEmpty(
            data.array("list"),
            data.array("mv_list"),
            data.array("mvlist"),
        )
        val videos = rawValues.mapNotNull { value ->
            val item = value.asObject().obj("mv_info") ?: value.asObject()
            runCatching { qqVideo(item) }.getOrNull()
        }.filter(::hasResourceId)
        val total = data.int("total") ?: data.int("totalNum") ?: data.int("total_num")
        val nextOffset = offset + rawValues.size
        return ProviderContentSection(
            feature = ProviderFeatureFilterCodec.attach(
                feature,
                qqMusicMvSquareFilters(area, version, order),
            ),
            videos = videos,
            nextOffset = nextOffset,
            hasMore = total?.let { nextOffset < it } ?: (rawValues.size >= limit),
        )
    }

    private fun qqPlaylist(item: JsonObject): ProviderPlaylist {
        val identifier = item.string("dissid")
            .ifBlank { item.string("tid") }
            .ifBlank { item.string("content_id") }
            .ifBlank { item.string("id") }
        return ProviderPlaylist(
            id = playlistKey(ID, identifier),
            title = item.string("dissname")
                .ifBlank { item.string("title") }
                .ifBlank { item.string("name") },
            providerId = ID,
            providerName = NAME,
            coverUrl = item.stringOrNull("imgurl")
                ?: item.stringOrNull("picurl")
                ?: item.stringOrNull("picUrl")
                ?: item.stringOrNull("cover")
                ?: item.stringOrNull("logo"),
            description = item.string("introduction")
                .ifBlank { item.string("desc") }
                .ifBlank { item.string("description") },
            playCount = item.long("listennum")
                ?: item.long("listen_num")
                ?: item.long("visitnum"),
            trackCount = item.int("song_count")
                ?: item.int("songnum")
                ?: item.int("total_song_num"),
            providerUrl = "https://y.qq.com/n/ryqq/playlist/$identifier",
        )
    }

    private fun qqArtist(item: JsonObject): ProviderMediaItem {
        val identifier = item.string("singer_id")
            .ifBlank { item.string("singerID") }
            .ifBlank { item.string("singerid") }
            .ifBlank { item.string("id") }
            .ifBlank { item.string("singer_mid") }
            .ifBlank { item.string("singerMID") }
            .ifBlank { item.string("singermid") }
            .ifBlank { item.string("mid") }
        val mid = item.string("singer_mid")
            .ifBlank { item.string("singerMID") }
            .ifBlank { item.string("singermid") }
            .ifBlank { item.string("mid") }
        return ProviderMediaItem(
            id = mediaItemKey(ProviderMediaItemType.Artist, ID, identifier),
            title = item.string("singer_name")
                .ifBlank { item.string("singerName") }
                .ifBlank { item.string("singername") }
                .ifBlank { item.string("name") }
                .ifBlank { item.string("title") },
            providerId = ID,
            providerName = NAME,
            type = ProviderMediaItemType.Artist,
            coverUrl = item.stringOrNull("singer_pic")
                ?: item.stringOrNull("singerPic")
                ?: item.stringOrNull("pic")
                ?: item.stringOrNull("picUrl")
                ?: item.stringOrNull("picurl")
                ?: mid.takeIf(String::isNotBlank)?.let(::artistCover),
            description = item.string("desc").ifBlank { item.string("description") },
            trackCount = item.int("song_num") ?: item.int("songnum"),
            albumCount = item.int("album_num") ?: item.int("albumnum"),
            providerUrl = "https://y.qq.com/n/ryqq/singer/$identifier",
        )
    }

    private fun qqAlbum(item: JsonObject): ProviderMediaItem {
        val album = item.obj("album") ?: item
        val identifier = album.string("album_id")
            .ifBlank { album.string("albumID") }
            .ifBlank { album.string("albumid") }
            .ifBlank { album.string("id") }
            .ifBlank { album.string("album_mid") }
            .ifBlank { album.string("albumMID") }
            .ifBlank { album.string("albummid") }
            .ifBlank { album.string("mid") }
        val mid = album.string("album_mid")
            .ifBlank { album.string("albumMID") }
            .ifBlank { album.string("albummid") }
            .ifBlank { album.string("mid") }
        return ProviderMediaItem(
            id = mediaItemKey(ProviderMediaItemType.Album, ID, identifier),
            title = album.string("album_name")
                .ifBlank { album.string("albumName") }
                .ifBlank { album.string("albumname") }
                .ifBlank { album.string("name") }
                .ifBlank { album.string("title") },
            providerId = ID,
            providerName = NAME,
            type = ProviderMediaItemType.Album,
            coverUrl = album.stringOrNull("album_pic")
                ?: album.stringOrNull("albumPic")
                ?: album.stringOrNull("pic")
                ?: album.stringOrNull("picUrl")
                ?: album.stringOrNull("picurl")
                ?: mid.takeIf(String::isNotBlank)?.let(::albumCover),
            description = album.string("desc").ifBlank { album.string("description") },
            trackCount = album.int("song_count")
                ?: album.int("songnum")
                ?: album.int("total_song_num"),
            providerUrl = "https://y.qq.com/n/ryqq/albumDetail/$identifier",
        )
    }

    private fun qqVideo(item: JsonObject): ProviderVideo {
        val mv = item.obj("mv") ?: item
        val identifier = mv.string("vid")
            .ifBlank { mv.string("mv_id") }
            .ifBlank { mv.string("id") }
        val singerItems = firstNonEmpty(mv.array("singer"), mv.array("singers"))
        val artists = singerItems.map { singerValue ->
            val singer = singerValue.asObject()
            singer.string("name").ifBlank { singer.string("singer_name") }
        }.filter(String::isNotBlank).joinToString(" / ").ifBlank {
            mv.string("singer_name")
                .ifBlank { mv.string("singername") }
                .ifBlank { mv.string("creator") }
        }
        val rawDuration = mv.long("duration")
        val durationMs = mv.long("duration_ms")
            ?: rawDuration?.let { if (it < 100_000) it * 1_000 else it }
        return ProviderVideo(
            id = videoKey(ID, identifier),
            title = mv.string("mv_name")
                .ifBlank { mv.string("mvName") }
                .ifBlank { mv.string("name") }
                .ifBlank { mv.string("title") },
            artists = artists,
            providerId = ID,
            providerName = NAME,
            coverUrl = mv.stringOrNull("picurl")
                ?: mv.stringOrNull("picUrl")
                ?: mv.stringOrNull("pic")
                ?: mv.stringOrNull("cover")
                ?: mv.stringOrNull("cover_pic")
                ?: mv.stringOrNull("poster_pic"),
            durationMs = durationMs,
            providerUrl = "https://y.qq.com/n/ryqq/mvdetail/$identifier",
        )
    }

    private fun qqTrack(item: JsonObject): MusicTrack {
        val song = item.obj("songInfo") ?: item
        val identifier = song.string("songmid")
            .ifBlank { song.string("mid") }
            .ifBlank { song.string("song_id") }
            .ifBlank { song.string("songid") }
            .ifBlank { song.string("id") }
        val singerItems = firstNonEmpty(song.array("singer"), song.array("singers"))
        val artists = singerItems.map { singerValue ->
            val singer = singerValue.asObject()
            singer.string("name").ifBlank { singer.string("singer_name") }
        }.filter(String::isNotBlank).joinToString(" / ").ifBlank {
            song.string("singer_name").ifBlank { song.string("singername") }
        }
        val album = song.obj("album")
        val albumName = song.string("albumname")
            .ifBlank { song.string("album_name") }
            .ifBlank { album?.string("name").orEmpty() }
        val albumId = song.string("albumid")
            .ifBlank { song.string("album_id") }
            .ifBlank { album?.string("id").orEmpty() }
        val albumMid = song.string("albummid")
            .ifBlank { song.string("album_mid") }
            .ifBlank { album?.string("mid").orEmpty() }
        val firstSinger = singerItems.firstOrNull()?.asObject()
        val artistId = firstSinger?.string("id")
            ?.ifBlank { firstSinger.string("singer_id") }
            ?.ifBlank { firstSinger.string("mid") }
        val rawDuration = song.long("interval") ?: song.long("duration")
        return MusicTrack(
            id = trackKey(ID, identifier),
            title = song.string("songname")
                .ifBlank { song.string("name") }
                .ifBlank { song.string("title") }
                .ifBlank { song.string("songorig") },
            artists = artists,
            album = albumName,
            source = ID,
            sourceType = TrackSourceType.Provider,
            coverUrl = song.stringOrNull("picurl")
                ?: album?.stringOrNull("picurl")
                ?: albumMid.takeIf(String::isNotBlank)?.let(::albumCover),
            durationMs = rawDuration?.let { if (it < 100_000) it * 1_000 else it },
            providerId = trackKey(ID, identifier),
            providerName = NAME,
            artistItemId = artistId?.takeIf(String::isNotBlank)?.let {
                mediaItemKey(ProviderMediaItemType.Artist, ID, it)
            },
            albumItemId = (albumId.takeIf(String::isNotBlank) ?: albumMid.takeIf(String::isNotBlank))?.let {
                mediaItemKey(ProviderMediaItemType.Album, ID, it)
            },
            providerUrl = "https://y.qq.com/n/ryqq/songDetail/$identifier",
        )
    }

    private fun searchBody(root: JsonObject, key: String): JsonObject {
        val data = root.obj(key)?.obj("data") ?: root.obj(key) ?: return JsonObject(emptyMap())
        return data.obj("body") ?: data
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

    private suspend fun qqRpcPayload(payload: String): String {
        val root = providerJson.parseToJsonElement(payload).jsonObject
        val cookies = qqCookies()
        val uin = cookies["wxuin"]?.removePrefix("o")?.takeIf(String::isNotBlank)
            ?: cookies["uin"]?.takeIf(String::isNotBlank)
            ?: "0"
        val tokenSource = listOf("qqmusic_key", "p_skey", "skey", "p_lskey", "lskey")
            .asSequence()
            .mapNotNull { cookies[it] }
            .firstOrNull()
            .orEmpty()
        val token = if (tokenSource.isBlank()) {
            5_381L
        } else {
            var hash = 5_381L
            tokenSource.forEach { character ->
                hash = (hash * 33 + character.code) and 0xffff_ffffL
            }
            hash and 0x7fff_ffffL
        }
        val common = mapOf(
            "loginUin" to JsonPrimitive(uin),
            "hostUin" to JsonPrimitive(0),
            "g_tk" to JsonPrimitive(token),
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

    private fun publicHeaders(): Map<String, String> = mapOf(
        "User-Agent" to DEFAULT_USER_AGENT,
        "Referer" to "https://y.qq.com/",
    )

    private suspend fun qqCookies(): Map<String, String> {
        val stored = credentials.read(ID) ?: return emptyMap()
        return parseCookies(stored.cookieHeader.orEmpty()) + stored.cookies
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

    private fun albumCover(mid: String): String =
        "https://y.qq.com/music/photo_new/T002R300x300M000$mid.jpg"

    private fun artistCover(mid: String): String =
        "https://y.qq.com/music/photo_new/T001R300x300M000$mid.jpg"

    private fun resourceId(value: String): String {
        val prefix = "$ID:"
        return value.substringAfter(prefix, value.substringAfterLast(':'))
    }

    private fun hasResourceId(item: ProviderPlaylist): Boolean = resourceId(item.id).isNotBlank()

    private fun hasResourceId(item: ProviderMediaItem): Boolean = resourceId(item.id).isNotBlank()

    private fun hasResourceId(item: ProviderVideo): Boolean = resourceId(item.id).isNotBlank()

    private fun hasResourceId(item: MusicTrack): Boolean = resourceId(item.id).isNotBlank()

    private fun toplistPlaylistId(topId: Int, period: String): String {
        val suffix = if (period.isBlank()) topId.toString() else "$topId:$period"
        return playlistKey(ID, "$TOPLIST_PREFIX$suffix")
    }

    private fun parseToplistIdentifier(identifier: String): ToplistSpec? {
        if (!identifier.startsWith(TOPLIST_PREFIX)) return null
        val parts = identifier.removePrefix(TOPLIST_PREFIX).split(':', limit = 2)
        val topId = parts.firstOrNull()?.toIntOrNull() ?: return null
        return ToplistSpec(topId, parts.getOrNull(1).orEmpty())
    }

    private fun ProviderPlaylist.isQQMusicToplist(): Boolean =
        providerId == ID && resourceId(id).startsWith(TOPLIST_PREFIX)

    private data class ToplistSpec(
        val topId: Int,
        val period: String,
    )

    private companion object {
        const val ID = "qqmusic"
        const val NAME = "QQ 音乐"
        const val C_BASE = "https://c.y.qq.com"
        const val U_BASE = "https://u.y.qq.com"
        const val TOPLIST_PREFIX = "toplist:"
        const val QQ_SINGER_PAGE_SIZE = 80
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

        val EXTRA_FEATURES = listOf(
            ProviderFeature(
                QQMUSIC_TOPLISTS,
                ID,
                NAME,
                "排行榜",
                ProviderFeatureCategory.Music,
                ProviderContentType.Playlists,
                false,
            ),
            ProviderFeature(
                QQMUSIC_PLAYLIST_SQUARE,
                ID,
                NAME,
                "歌单广场",
                ProviderFeatureCategory.Music,
                ProviderContentType.Playlists,
                false,
            ),
            ProviderFeature(
                QQMUSIC_ARTIST_SQUARE,
                ID,
                NAME,
                "歌手广场",
                ProviderFeatureCategory.Music,
                ProviderContentType.Artists,
                false,
            ),
            ProviderFeature(
                QQMUSIC_NEW_ALBUMS,
                ID,
                NAME,
                "新碟上架",
                ProviderFeatureCategory.Music,
                ProviderContentType.Albums,
                false,
            ),
            ProviderFeature(
                QQMUSIC_MV_SQUARE,
                ID,
                NAME,
                "MV 广场",
                ProviderFeatureCategory.Music,
                ProviderContentType.Videos,
                false,
            ),
        )
    }
}

private fun <T> firstNonEmpty(vararg values: List<T>): List<T> =
    values.firstOrNull { it.isNotEmpty() }.orEmpty()

private fun contentSign(data: String): String {
    val randomPart = buildString {
        repeat(Random.nextInt(10, 17)) {
            append(CONTENT_SIGN_ALPHABET[Random.nextInt(CONTENT_SIGN_ALPHABET.length)])
        }
    }
    return "zza$randomPart${md5Hex("CJBPACrRuNy7$data")}" 
}

private const val CONTENT_SIGN_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
