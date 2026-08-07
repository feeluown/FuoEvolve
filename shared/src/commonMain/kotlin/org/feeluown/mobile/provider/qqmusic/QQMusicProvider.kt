package org.feeluown.mobile.provider.qqmusic

import io.ktor.http.Parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.feeluown.mobile.AudioQualityPolicy
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.ProviderCapabilities
import org.feeluown.mobile.ProviderComment
import org.feeluown.mobile.ProviderContentType
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderFeatureCategory
import org.feeluown.mobile.ProviderInfo
import org.feeluown.mobile.ProviderMediaItem
import org.feeluown.mobile.ProviderMediaItemDetail
import org.feeluown.mobile.ProviderMediaItemType
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
import org.feeluown.mobile.provider.core.asString
import org.feeluown.mobile.provider.core.base64DecodeToString
import org.feeluown.mobile.provider.core.boolean
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.md5Hex
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.parseCookies
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.splitResourceId
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.network.ProviderCachePolicies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderRequestKind
import org.feeluown.mobile.provider.core.network.currentTimeMillis
import kotlin.random.Random

class QQMusicProvider(
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
    override suspend fun search(keyword: String): ProviderSearchResults {
        val root = searchRoot(keyword)
        val values = root.obj("search")?.obj("data")?.obj("body")?.obj("song")?.array("list")
            ?: root.obj("data")?.obj("song")?.array("list")
            ?: root.array("songlist")
        return ProviderSearchResults(tracks = values.map(::song))
    }

    override suspend fun trackDetail(identifier: String) = songDetail(rawIdentifier(identifier))

    override suspend fun resolve(track: org.feeluown.mobile.MusicTrack, qualityPolicy: String): PlaybackPayload? {
        val (_, identifier) = splitResourceId(track.providerId ?: track.id)
        val songMid = identifier.ifBlank { track.id.substringAfterLast(':') }
        val detail = songDetailObject(songMid) ?: return null
        val file = detail.obj("file")
        val mediaId = file?.stringOrNull("media_mid").orEmpty().ifBlank { songMid }
        val cookies = qqCookies()
        val guid = cookies["guid"]?.takeIf { it.isNotBlank() } ?: defaultGuid
        val uin = cookies["wxuin"]?.removePrefix("o")
            ?: cookies["uin"]
            ?: "0"
        qqQualityCandidates(qualityPolicy, file).forEach { quality ->
            val url = qqMediaUrl(songMid, mediaId, quality, guid, uin) ?: return@forEach
            return PlaybackPayload(
                url = url,
                title = track.title,
                artists = track.artists,
                album = track.album,
                source = ID,
                headers = mapOf("Referer" to "https://y.qq.com/"),
                coverUrl = track.coverUrl,
                durationMs = track.durationMs,
                audioQuality = quality.label,
                providerName = NAME,
            )
        }
        return null
    }

    private suspend fun qqMediaUrl(
        songMid: String,
        mediaId: String,
        quality: QqAudioQuality,
        guid: String,
        uin: String,
    ): String? {
        val request = """
            {"req_0":{"module":"vkey.GetVkeyServer","method":"CgiGetVkey","param":{"filename":[${jsonString(quality.filename(mediaId))}],"guid":${jsonString(guid)},"songmid":[${jsonString(songMid)}],"songtype":[0],"uin":${jsonString(uin)},"loginflag":1,"platform":"20"}},"comm":{"uin":${jsonString(uin)},"format":"json","ct":19,"cv":0}}
        """.trimIndent()
        val sign = qqSign(request)
        val root = http.getText(
            providerId = ID,
            url = queryUrl(
                "$VKEY_BASE/cgi-bin/musicu.fcg",
                mapOf(
                    "sign" to sign,
                    "g_tk" to "5381",
                    "loginUin" to "",
                    "hostUin" to "0",
                    "format" to "json",
                    "inCharset" to "utf8",
                    "outCharset" to "utf-8",
                    "platform" to "yqq.json",
                    "needNewCode" to "0",
                    "data" to request,
                ),
            ),
            headers = authenticatedHeaders(mapOf("Referer" to "http://y.qq.com/")),
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val data = root.obj("req_0")?.obj("data") ?: return null
        val purl = data.array("midurlinfo").firstOrNull()?.asObject()?.stringOrNull("purl") ?: return null
        if (purl.startsWith("http")) return purl
        return "$QQ_AUDIO_BASE/${purl.trimStart('/')}"
    }

    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<org.feeluown.mobile.MusicTrack> =
        playlistDetail(playlist, 0, 1_000).tracks

    override suspend fun playlistDetail(playlist: ProviderPlaylist, offset: Int, limit: Int): ProviderPlaylistDetail {
        val (_, identifier) = splitResourceId(playlist.id, "playlist")
        val root = http.getText(
            ID,
            queryUrl(
                "$SEARCH_BASE/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg",
                mapOf(
                    "format" to "json",
                    "disstid" to identifier,
                    "type" to "1",
                    "utf8" to "1",
                    "new_format" to "1",
                    "song_begin" to offset.toString(),
                    "song_num" to limit.toString(),
                ),
            ),
            authenticatedHeaders(mapOf("Referer" to "https://y.qq.com/")),
            cacheKey = "qqmusic:playlist:$identifier:$offset:$limit",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val detail = root.array("cdlist").firstOrNull()?.asObject() ?: return ProviderPlaylistDetail(playlist)
        val songItems = detail.array("songlist")
        val tracks = songs(songItems)
        val actual = playlist(
            identifier = detail.string("disstid").ifBlank { identifier },
            title = detail.string("dissname").ifBlank { playlist.title },
            coverUrl = detail.stringOrNull("logo"),
            description = detail.string("desc"),
            playCount = detail.long("visitnum"),
            trackCount = detail.int("songnum") ?: songItems.size,
            providerUrl = "https://y.qq.com/n/ryqq/playlist/$identifier",
        )
        val count = actual.trackCount ?: tracks.size
        val nextOffset = offset + songItems.size
        return ProviderPlaylistDetail(
            playlist = actual,
            tracks = tracks,
            tracksNextOffset = nextOffset,
            tracksHasMore = songItems.isNotEmpty() && nextOffset < count,
        )
    }

    override suspend fun mediaItemTracks(item: ProviderMediaItem): List<org.feeluown.mobile.MusicTrack> =
        mediaItemDetail(item, tracksOffset = 0, albumsOffset = 0, limit = 300).tracks

    override suspend fun mediaItemDetail(item: ProviderMediaItem, tracksOffset: Int, albumsOffset: Int, limit: Int): ProviderMediaItemDetail {
        val (_, identifier) = splitResourceId(
            item.id,
            if (item.type == ProviderMediaItemType.Artist) "artist" else "album",
        )
        return if (item.type == ProviderMediaItemType.Artist) {
            val artistId = resolveArtistId(identifier)
            val artistSongs = artistSongsPage(artistId, tracksOffset, limit)
            val artistAlbums = artistAlbumsPage(artistId, albumsOffset, limit)
            ProviderMediaItemDetail(
                item = item.copy(
                    trackCount = artistSongs.total ?: item.trackCount,
                    albumCount = artistAlbums.total ?: item.albumCount,
                ),
                tracks = artistSongs.values,
                albums = artistAlbums.values,
                tracksNextOffset = tracksOffset + artistSongs.rawSize,
                tracksHasMore = artistSongs.hasMore,
                albumsNextOffset = albumsOffset + artistAlbums.rawSize,
                albumsHasMore = artistAlbums.hasMore,
            )
        } else {
            val detail = albumDetailRoot(identifier)
            val albumInfo = detail.obj("getAlbumInfo")
            val values = detail.array("getSongInfo").ifEmpty { detail.array("songlist") }
            val allTracks = songs(values)
            val tracks = allTracks.drop(tracksOffset).take(limit)
            val album = albumInfo?.let(::qqAlbum)
            ProviderMediaItemDetail(
                item = item.copy(
                    title = album?.title?.ifBlank { item.title } ?: item.title,
                    coverUrl = album?.coverUrl ?: item.coverUrl,
                    description = detail.obj("getAlbumDesc")?.string("Falbum_desc")
                        ?.ifBlank { item.description }
                        ?: item.description,
                    trackCount = album?.trackCount ?: allTracks.size,
                ),
                tracks = tracks,
                tracksNextOffset = tracksOffset + tracks.size,
                tracksHasMore = tracksOffset + tracks.size < allTracks.size,
            )
        }
    }

    override suspend fun similarTracks(track: org.feeluown.mobile.MusicTrack): List<org.feeluown.mobile.MusicTrack> {
        val identifier = rawIdentifier(track.providerId ?: track.id)
        val root = rpc(
            """
            {"simsongs":{"module":"rcmusic.similarSongRadioServer","method":"get_simsongs","param":{"songid":${jsonString(identifier)}}}}
            """.trimIndent(),
        )
        return root.obj("simsongs")?.obj("data")?.array("songInfoList").orEmpty().map(::song)
    }

    override suspend fun hotComments(track: org.feeluown.mobile.MusicTrack): List<ProviderComment> {
        val identifier = rawIdentifier(track.providerId ?: track.id)
        val root = http.getText(
            providerId = ID,
            url = queryUrl(
                "$SEARCH_BASE/base/fcgi-bin/fcg_global_comment_h5.fcg",
                mapOf("biztype" to "1", "cmd" to "8", "topid" to identifier, "pagenum" to "0", "pagesize" to "25"),
            ),
            headers = authenticatedHeaders(mapOf("Referer" to "https://y.qq.com/")),
            cacheKey = "qqmusic:comments:$identifier",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        return root.obj("hot_comment")?.array("commentlist").orEmpty().map { value ->
            val item = value.asObject()
            ProviderComment(
                id = item.string("commentid"),
                userName = item.string("nick"),
                content = item.string("rootcommentcontent"),
                likedCount = item.long("praisenum") ?: 0,
                timeSeconds = item.long("time") ?: 0,
            )
        }
    }

    override suspend fun trackVideo(track: org.feeluown.mobile.MusicTrack): ProviderVideo? {
        val detail = songDetailObject(rawIdentifier(track.providerId ?: track.id)) ?: return null
        val videoId = detail.obj("mv")?.stringOrNull("vid")?.takeIf { it.isNotBlank() } ?: return null
        return video(
            identifier = videoId,
            title = track.title,
            artists = track.artists,
            coverUrl = track.coverUrl,
            durationMs = track.durationMs,
            providerUrl = "https://y.qq.com/n/ryqq/mvdetail/$videoId",
        )
    }

    override suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload {
        val identifier = splitResourceId(video.id, "video").second
        val root = rpc(
            """
            {"getMvUrl":{"module":"gosrf.Stream.MvUrlProxy","method":"GetMvUrls","param":{"vids":["$identifier"],"request_typet":10001}}}
            """.trimIndent(),
        )
        val data = root.obj("getMvUrl")?.obj("data")?.obj(identifier) ?: return VideoPlaybackPayload(video = video)
        val url = data.array("mp4").asSequence()
            .map { it.asObject() }
            .flatMap { it.array("freeflow_url").asSequence().mapNotNull { value -> value.asString().takeIf(String::isNotBlank) } }
            .firstOrNull()
            ?: return VideoPlaybackPayload(video = video)
        return VideoPlaybackPayload(
            video = video,
            url = url,
            videoUrl = url,
            headers = mapOf("Referer" to "https://y.qq.com/"),
            quality = "video",
        )
    }

    override suspend fun playlistOperationTargets(track: org.feeluown.mobile.MusicTrack): List<ProviderPlaylist> {
        if (!authState().isLoggedIn) return emptyList()
        return userPlaylists()
    }

    override suspend fun loadFeature(feature: ProviderFeature, offset: Int, limit: Int): org.feeluown.mobile.ProviderContentSection {
        if (feature.id !in setOf(
                "qqmusic_daily_songs",
                "qqmusic_radio",
                "qqmusic_daily_playlists",
                "qqmusic_user_playlists",
            )
        ) {
            return super.loadFeature(feature, offset, limit)
        }
        if (!authState().isLoggedIn) {
            return org.feeluown.mobile.ProviderContentSection(feature, isLoginRequired = true)
        }
        return when (feature.id) {
            "qqmusic_daily_songs" -> recommendedDailySongs(feature, offset, limit)
            "qqmusic_radio" -> privateFm(feature, offset, limit)
            "qqmusic_daily_playlists" -> {
                val playlists = recommendedPlaylists()
                val page = playlists.drop(offset).take(limit)
                org.feeluown.mobile.ProviderContentSection(
                    feature = feature,
                    playlists = page,
                    nextOffset = offset + page.size,
                    hasMore = playlists.size > offset + page.size,
                )
            }
            else -> {
                val playlists = userPlaylists()
                val page = playlists.drop(offset).take(limit)
                org.feeluown.mobile.ProviderContentSection(
                    feature = feature,
                    playlists = page,
                    nextOffset = offset + page.size,
                    hasMore = playlists.size > offset + page.size,
                )
            }
        }
    }

    override suspend fun addTrackToPlaylist(playlist: ProviderPlaylist, track: org.feeluown.mobile.MusicTrack): ProviderMutationResult =
        mutatePlaylist("AddSonglist", playlist, track)

    override suspend fun removeTrackFromPlaylist(playlist: ProviderPlaylist, track: org.feeluown.mobile.MusicTrack): ProviderMutationResult =
        mutatePlaylist("DelSonglist", playlist, track)

    private suspend fun artistSongsPage(identifier: String, offset: Int, limit: Int): QQTrackPage {
        val root = rpc(
            """
                {"req_0":{"module":"music.musichallSong.SongListInter","method":"GetSingerSongList","param":{"singerid":${qqIdentifierJson(identifier)},"begin":$offset,"num":$limit,"order":1,"newsong":1}},"comm":{"g_tk":${qqToken()},"uin":${jsonString(currentUin() ?: "0")},"format":"json"}}
            """.trimIndent(),
        )
        val data = root.obj("req_0")?.obj("data") ?: root.obj("data") ?: root
        val values = data.array("songlist").takeIf { it.isNotEmpty() } ?: data.array("list")
        val tracks = songs(values)
        val total = data.int("total") ?: data.int("songnum") ?: data.int("song_count")
        return QQTrackPage(
            values = tracks,
            rawSize = values.size,
            total = total,
            hasMore = data.boolean("more") || total?.let { offset + values.size < it } == true || values.size == limit,
        )
    }

    private suspend fun artistAlbumsPage(identifier: String, offset: Int, limit: Int): QQAlbumPage {
        val root = http.getText(
            ID,
            queryUrl(
                "$SEARCH_BASE/v8/fcg-bin/fcg_v8_singer_album.fcg",
                mapOf(
                    "singerid" to identifier,
                    "order" to "time",
                    "begin" to offset.toString(),
                    "num" to limit.toString(),
                ),
            ),
            authenticatedHeaders(mapOf("Referer" to "https://y.qq.com/")),
            cacheKey = "qqmusic:artist-albums:$identifier:$offset:$limit",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val data = root.obj("data") ?: root
        val rawValues = data.array("list")
        val values = rawValues.mapNotNull { value ->
            runCatching { qqAlbum(value.asObject()) }.getOrNull()
        }
        val total = data.int("total") ?: data.int("albumnum") ?: data.int("album_num")
        return QQAlbumPage(
            values = values,
            rawSize = rawValues.size,
            total = total,
            hasMore = data.boolean("more") || total?.let { offset + rawValues.size < it } == true || rawValues.size == limit,
        )
    }

    private suspend fun albumDetailRoot(identifier: String): JsonObject {
        val root = http.getText(
            ID,
            queryUrl(
                "$SEARCH_BASE/v8/fcg-bin/fcg_v8_album_detail_cp.fcg",
                mapOf("albumid" to identifier, "format" to "json", "newsong" to "1"),
            ),
            authenticatedHeaders(mapOf("Referer" to "https://y.qq.com/")),
            cacheKey = "qqmusic:album:$identifier",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        return root.obj("data") ?: root
    }

    private suspend fun resolveArtistId(identifier: String): String {
        if (identifier.toLongOrNull() != null) return identifier
        val root = runCatching {
            rpc(
                """
                {"req_0":{"module":"music.musichallSinger.SingerInfoInter","method":"GetSingerDetail","param":{"singer_mids":[${jsonString(identifier)}],"pic":1,"group_singer":1,"wiki_singer":1,"ex_singer":1}},"comm":{"g_tk":${qqToken()},"uin":${jsonString(currentUin() ?: "0")},"format":"json"}}
                """.trimIndent(),
            )
        }.getOrNull() ?: return identifier
        val singer = root.obj("req_0")?.obj("data")?.array("singer_list")?.firstOrNull()?.asObject()
        val basicInfo = singer?.obj("basic_info")
        return basicInfo?.string("singer_id")?.takeIf { it.isNotBlank() }
            ?: singer?.string("singer_id")?.takeIf { it.isNotBlank() }
            ?: identifier
    }

    private fun qqAlbum(value: JsonObject): ProviderMediaItem {
        val identifier = value.string("albumID")
            .ifBlank { value.string("albumid") }
            .ifBlank { value.string("Falbum_id") }
            .ifBlank { value.string("album_id") }
            .ifBlank { value.string("id") }
        val mid = value.string("albumMID")
            .ifBlank { value.string("albummid") }
            .ifBlank { value.string("Falbum_mid") }
            .ifBlank { value.string("mid") }
        val realIdentifier = identifier.ifBlank { mid }
        val title = value.string("albumName")
            .ifBlank { value.string("albumname") }
            .ifBlank { value.string("Falbum_name") }
            .ifBlank { value.string("name") }
        val cover = value.stringOrNull("albumPic")
            ?: value.stringOrNull("albumPicUrl")
            ?: value.stringOrNull("picurl")
            ?: value.stringOrNull("picUrl")
            ?: mid.takeIf { it.isNotBlank() }?.let(::qqAlbumCover)
        return mediaItem(
            type = ProviderMediaItemType.Album,
            identifier = realIdentifier,
            title = title,
            coverUrl = cover,
            description = value.string("Falbum_desc"),
            trackCount = value.int("song_count")
                ?: value.int("songnum")
                ?: value.int("Falbum_songnum"),
            providerUrl = "https://y.qq.com/n/ryqq/albumDetail/$realIdentifier",
        )
    }

    private fun qqIdentifierJson(value: String): String = value.toLongOrNull()?.toString() ?: jsonString(value)

    private fun jsonString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun qqAlbumCover(mid: String): String =
        "https://y.qq.com/music/photo_new/T002R300x300M000$mid.jpg"

    private data class QQTrackPage(
        val values: List<org.feeluown.mobile.MusicTrack>,
        val rawSize: Int,
        val total: Int?,
        val hasMore: Boolean,
    )

    private data class QQAlbumPage(
        val values: List<ProviderMediaItem>,
        val rawSize: Int,
        val total: Int?,
        val hasMore: Boolean,
    )

    private suspend fun searchRoot(keyword: String) = rpc(
        """
        {"search":{"module":"music.search.SearchCgiService","method":"DoSearchForQQMusicDesktop","param":{"num_per_page":30,"page_num":1,"search_type":0,"query":${jsonString(keyword)}}}}
        """.trimIndent(),
        cacheKey = "qqmusic:search:$keyword",
        cachePolicy = ProviderCachePolicies.search,
    )

    private suspend fun userPlaylists(): List<ProviderPlaylist> {
        val uin = currentUin() ?: return emptyList()
        val root = http.getText(
            providerId = ID,
            url = queryUrl(
                "$SEARCH_BASE/rsc/fcgi-bin/fcg_get_profile_homepage.fcg",
                mapOf(
                    "cid" to "205360838",
                    "reqfrom" to "1",
                    "userid" to uin,
                ),
            ),
            headers = authenticatedHeaders(mapOf("Referer" to "https://y.qq.com/")),
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val data = root.obj("data") ?: return emptyList()
        val creator = data.obj("creator")
        val favoriteId = creator?.stringOrNull("fav_pid")
            ?: data.array("mymusic").firstOrNull()?.asObject()?.stringOrNull("id")
        val userPlaylists = data.obj("mydiss")?.array("list")
            ?: data.obj("mymusic")?.array("list")
            ?: data.array("disslist")
        return buildList {
            favoriteId?.let { favoriteId ->
                add(
                    playlist(
                        identifier = favoriteId,
                        title = "我喜欢",
                        providerUrl = "https://y.qq.com/n/ryqq/playlist/$favoriteId",
                    ),
                )
            }
            addAll(userPlaylists.mapNotNull { item ->
                val value = item.asObject()
                val identifier = value.string("dissid").ifBlank { value.string("tid") }
                if (identifier.isBlank()) return@mapNotNull null
                playlist(
                    identifier = identifier,
                    title = value.string("title").ifBlank { value.string("dissname") },
                    coverUrl = value.stringOrNull("logo")
                        ?: value.stringOrNull("picurl")
                        ?: value.stringOrNull("cover")
                        ?: value.stringOrNull("imgurl"),
                    description = value.string("desc"),
                    playCount = value.long("visitnum"),
                    trackCount = value.int("songnum"),
                    providerUrl = "https://y.qq.com/n/ryqq/playlist/$identifier",
                )
            })
        }
    }

    private suspend fun recommendedPlaylists(): List<ProviderPlaylist> {
        val root = rpc(
            """
            {"recomPlaylist":{"module":"playlist.HotRecommendServer","method":"get_hot_recommend","param":{"cmd":2,"async":1}}}
            """.trimIndent(),
        )
        return root.obj("recomPlaylist")?.obj("data")?.array("v_hot").orEmpty().mapNotNull { item ->
            val value = item.asObject()
            val identifier = value.string("content_id").ifBlank { value.string("id") }
            if (identifier.isBlank()) return@mapNotNull null
            playlist(
                identifier = identifier,
                title = value.string("title"),
                coverUrl = value.stringOrNull("cover"),
                description = value.string("rcmdtemplate"),
                playCount = value.long("listen_num"),
                providerUrl = "https://y.qq.com/n/ryqq/playlist/$identifier",
            )
        }
    }

    private suspend fun recommendedDailySongs(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): org.feeluown.mobile.ProviderContentSection {
        val dailyPlaylist = recommendedDailyPlaylist()
            ?: return org.feeluown.mobile.ProviderContentSection(feature, nextOffset = offset)
        val detail = playlistDetail(dailyPlaylist, offset, limit)
        return org.feeluown.mobile.ProviderContentSection(
            feature = feature,
            tracks = detail.tracks,
            nextOffset = detail.tracksNextOffset,
            hasMore = detail.tracksHasMore,
        )
    }

    private suspend fun recommendedDailyPlaylist(): ProviderPlaylist? {
        val uin = currentUin().orEmpty()
        val root = rpc(
            """
            {"req_0":{"module":"recommend.RecommendFeedServer","method":"get_recommend_feed","param":{"direction":0,"page":1,"v_cache":[],"v_uniq":[],"s_num":0}},"comm":{"ct":20,"cv":1770,"g_tk":5381,"uin":${jsonString(uin)},"format":"json","inCharset":"utf-8","outCharset":"utf-8","platform":"wk_v17","uid":"","guid":""}}
            """.trimIndent(),
        )
        val cards = root.obj("req_0")?.obj("data")?.array("v_shelf").orEmpty()
            .asSequence()
            .flatMap { shelf -> shelf.asObject().array("v_niche").asSequence() }
            .flatMap { batch -> batch.asObject().array("v_card").asSequence() }
            .map { it.asObject() }
            .filter { card ->
                card.int("jumptype") == 10014 &&
                    card.obj("extra_info")?.string("moduleID")?.startsWith("recforyou") == true
            }
            .toList()
        val card = cards.firstOrNull { it.string("title").contains("每日") } ?: cards.firstOrNull()
        val identifier = card?.string("id")?.takeIf { it.isNotBlank() } ?: return null
        return playlist(
            identifier = identifier,
            title = card.string("title").ifBlank { "每日推荐歌曲" },
            coverUrl = card.stringOrNull("cover"),
            description = card.obj("miscellany")?.string("rcmdtemplate").orEmpty(),
            providerUrl = "https://y.qq.com/n/ryqq/playlist/$identifier",
        )
    }

    private suspend fun privateFm(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): org.feeluown.mobile.ProviderContentSection {
        val requestLimit = limit.coerceIn(1, 50)
        val root = rpc(
            """
            {"songlist":{"module":"mb_track_radio_svr","method":"get_radio_track","param":{"id":99,"firstplay":0,"num":$requestLimit}}}
            """.trimIndent(),
        )
        val songItems = root.obj("songlist")?.obj("data")?.array("tracks").orEmpty()
        return org.feeluown.mobile.ProviderContentSection(
            feature = feature,
            tracks = songs(songItems),
            nextOffset = offset + songItems.size,
            hasMore = false,
        )
    }

    private suspend fun currentUin(): String? {
        val values = qqCookies()
        return values["wxuin"]?.removePrefix("o")?.takeIf { it.isNotBlank() }
            ?: values["uin"]?.takeIf { it.isNotBlank() }
    }

    private suspend fun qqCookies(): Map<String, String> {
        val stored = currentCredentials() ?: return emptyMap()
        return parseCookies(stored.cookieHeader.orEmpty()) + stored.cookies
    }

    private suspend fun qqToken(): String {
        val cookies = qqCookies()
        val value = listOf("qqmusic_key", "p_skey", "skey", "p_lskey", "lskey")
            .asSequence()
            .mapNotNull { cookies[it] }
            .firstOrNull()
            .orEmpty()
        if (value.isBlank()) return "5381"
        var hash = 5_381L
        value.forEach { character ->
            hash = (hash * 33 + character.code) and 0xffff_ffffL
        }
        return (hash and 0x7fff_ffffL).toString()
    }

    private suspend fun rpc(
        payload: String,
        kind: ProviderRequestKind = ProviderRequestKind.SafeRead,
        cacheKey: String? = null,
        cachePolicy: org.feeluown.mobile.provider.core.network.ProviderCachePolicy = ProviderCachePolicies.none,
    ): kotlinx.serialization.json.JsonObject {
        val request = qqRpcPayload(payload)
        val sign = qqSign(request)
        return http.getText(
            providerId = ID,
            url = queryUrl(
                "$VKEY_BASE/cgi-bin/musicu.fcg",
                mapOf(
                    "_" to currentTimeMillis().toString(),
                    "sign" to sign,
                    "data" to request,
                ),
            ),
            headers = authenticatedHeaders(mapOf("Referer" to "https://y.qq.com/")),
            kind = kind,
            cacheKey = cacheKey,
            cachePolicy = cachePolicy,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
    }

    private suspend fun qqRpcPayload(payload: String): String {
        val root = providerJson.parseToJsonElement(payload).jsonObject
        val common = mapOf(
            "loginUin" to JsonPrimitive(currentUin() ?: "0"),
            "hostUin" to JsonPrimitive(0),
            "g_tk" to JsonPrimitive(qqToken().toLongOrNull() ?: 5_381L),
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

    private suspend fun mutatePlaylist(
        method: String,
        playlist: ProviderPlaylist,
        track: org.feeluown.mobile.MusicTrack,
    ): ProviderMutationResult {
        val (_, playlistId) = splitResourceId(playlist.id, "playlist")
        val dirId = playlistDirectoryId(playlistId)
            ?: return ProviderMutationResult(false, "无法读取 QQ 音乐歌单目录编号")
        val songId = songDetailObject(rawIdentifier(track.providerId ?: track.id))?.long("id")
            ?: return ProviderMutationResult(false, "无法读取 QQ 音乐歌曲编号")
        val root = rpc(
            """
            {"req_0":{"method":${jsonString(method)},"module":"music.musicasset.PlaylistDetailWrite","param":{"dirId":$dirId,"v_songInfo":[{"songId":$songId,"songType":0}]}}}
            """.trimIndent(),
            kind = ProviderRequestKind.Mutation,
        )
        val success = root.obj("req_0")?.int("code") == 0
        return ProviderMutationResult(success, if (success) "操作成功" else "QQ 音乐歌单操作失败")
    }

    private suspend fun playlistDirectoryId(identifier: String): Long? {
        val root = http.getText(
            providerId = ID,
            url = queryUrl(
                "$SEARCH_BASE/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg",
                mapOf(
                    "type" to "1",
                    "utf8" to "1",
                    "disstid" to identifier,
                    "format" to "json",
                    "new_format" to "1",
                    "song_begin" to "0",
                    "song_num" to "1",
                ),
            ),
            headers = authenticatedHeaders(mapOf("Referer" to "https://y.qq.com/")),
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        return root.array("cdlist").firstOrNull()?.asObject()?.long("dirid")?.takeIf { it > 0 }
    }

    private suspend fun songDetail(identifier: String): org.feeluown.mobile.MusicTrack? {
        return songDetailObject(identifier)?.let(::song)
    }

    private suspend fun songDetailObject(identifier: String): kotlinx.serialization.json.JsonObject? {
        val root = http.getText(
            ID,
            queryUrl("$SEARCH_BASE/v8/fcg-bin/fcg_play_single_song.fcg", mapOf("songmid" to identifier, "format" to "json")),
            authenticatedHeaders(mapOf("Referer" to "https://y.qq.com/")),
            cacheKey = "qqmusic:song:$identifier",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        return root.array("data").firstOrNull()?.asObject()
            ?: root.array("songlist").firstOrNull()?.asObject()
    }

    override suspend fun lyrics(track: org.feeluown.mobile.MusicTrack): String? {
        val (_, identifier) = splitResourceId(track.providerId ?: track.id)
        val id = identifier.ifBlank { track.id.substringAfterLast(':') }
        return lyric(id)
    }

    private suspend fun lyric(identifier: String): String? = runCatching {
        val root = http.getText(
            providerId = ID,
            url = queryUrl(
                "$SEARCH_BASE/lyric/fcgi-bin/fcg_query_lyric_new.fcg",
                mapOf(
                    "songmid" to identifier,
                    "pcachetime" to currentTimeMillis().toString(),
                    "format" to "json",
                ),
            ),
            headers = authenticatedHeaders(mapOf("Referer" to "https://y.qq.com/")),
            cacheKey = "qqmusic:lyric:$identifier",
            cachePolicy = ProviderCachePolicies.lyric,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        root.stringOrNull("lyric")?.let(::base64DecodeToString)
    }.getOrNull()

    private fun song(value: kotlinx.serialization.json.JsonElement): org.feeluown.mobile.MusicTrack {
        val item = value.asObject().obj("songInfo") ?: value.asObject()
        val identifier = item.string("songmid")
            .ifBlank { item.string("mid") }
            .ifBlank { item.string("songid") }
            .ifBlank { item.string("id") }
        val singerItems = item.array("singer")
        val singers = singerItems.map { it.asObject().string("name") }
            .filter { it.isNotBlank() }
            .joinToString(" / ")
        val albumObject = item.obj("album")
        val album = item.string("albumname").ifBlank { albumObject?.string("name").orEmpty() }
        val albumId = item.string("albumid")
            .ifBlank { item.string("albumID") }
            .ifBlank { item.string("album_id") }
            .ifBlank { albumObject?.string("id").orEmpty() }
            .ifBlank { albumObject?.string("albumid").orEmpty() }
        val albumMid = item.stringOrNull("albummid")
            ?: albumObject?.stringOrNull("mid")
        val singer = singerItems.firstOrNull()?.asObject()
        val artistId = singer?.string("id")
            ?.ifBlank { singer.string("singerid") }
            ?.ifBlank { singer.string("singerID") }
            ?.ifBlank { singer.string("singer_id") }
            ?.ifBlank { singer.string("mid") }
        return track(
            identifier = identifier,
            title = item.string("songname")
                .ifBlank { item.string("title") }
                .ifBlank { item.string("name") }
                .ifBlank { item.string("songorig") },
            artists = singers,
            album = album,
            coverUrl = albumObject?.stringOrNull("picurl")
                ?: albumObject?.stringOrNull("picUrl")
                ?: albumMid?.let(::qqAlbumCover),
            durationMs = item.long("interval")?.times(1_000)
                ?: item.long("duration")?.let { if (it < 10_000) it * 1_000 else it },
            artistItemId = artistId?.takeIf { it.isNotBlank() }?.let { "artist:$ID:$it" },
            albumItemId = (albumId.takeIf { it.isNotBlank() } ?: albumMid)
                ?.let { "album:$ID:$it" },
            providerUrl = "https://y.qq.com/n/ryqq/songDetail/$identifier",
        )
    }

    private fun songs(values: Iterable<kotlinx.serialization.json.JsonElement>): List<org.feeluown.mobile.MusicTrack> =
        values.mapNotNull { value ->
            runCatching { song(value) }.getOrNull()?.takeIf { it.id.substringAfterLast(':').isNotBlank() }
        }.distinctBy { it.id }

    private fun rawIdentifier(value: String): String = splitResourceId(value).second.ifBlank { value.substringAfterLast(':') }

    private fun qqQualityCandidates(policy: String, file: JsonObject?): List<QqAudioQuality> {
        val flac = QqAudioQuality("shq", "F000", "flac", "SHQ")
        val ape = QqAudioQuality("shq", "A000", "ape", "SHQ")
        val high = QqAudioQuality("hq", "M800", "mp3", "HQ")
        val standard = QqAudioQuality("sq", "C600", "m4a", "SQ")
        val low = QqAudioQuality("lq", "M500", "mp3", "LQ")
        val all = listOf(flac, ape, high, standard, low)
        val ordered = when (policy) {
            AudioQualityPolicy.Highest.policy -> listOf(flac, ape, high, standard, low)
            AudioQualityPolicy.High.policy -> listOf(high, flac, ape, standard, low)
            AudioQualityPolicy.Standard.policy -> listOf(standard, high, flac, ape, low)
            AudioQualityPolicy.Low.policy -> listOf(low, standard, high, flac, ape)
            else -> listOf(high, flac, ape, standard, low)
        }
        val available = all.filter { quality ->
            when (quality.code) {
                "shq" -> if (quality.prefix == "F000") file?.hasPositive("size_flac") == true else file?.hasPositive("size_ape") == true
                "hq" -> file?.hasPositive("size_320") == true || file?.hasPositive("size_320mp3") == true
                "sq" -> file?.hasPositive("size_aac") == true || file?.hasPositive("size_192aac") == true
                else -> file?.hasPositive("size_128") == true || file?.hasPositive("size_128mp3") == true
            }
        }
        return if (available.isEmpty()) ordered else ordered.filter { it in available }
    }

    private companion object {
        const val ID = "qqmusic"
        const val NAME = "QQ 音乐"
        const val SEARCH_BASE = "https://c.y.qq.com"
        const val VKEY_BASE = "https://u.y.qq.com"
        const val QQ_AUDIO_BASE = "http://isure.stream.qqmusic.qq.com"
        val defaultGuid = Random.nextLong(100_000_000, 1_000_000_000).toString()
        val INFO = ProviderInfo(
            providerId = ID,
            providerName = NAME,
            loginConfig = org.feeluown.mobile.ProviderLoginConfig(
                "https://y.qq.com",
                listOf(listOf("qqmusic_key", "wxuin", "qm_keyst"), listOf("qqmusic_key", "uin", "qm_keyst")),
            ),
            supportedLoginModes = setOf(org.feeluown.mobile.ProviderLoginMode.WebView),
        )
        val CAPABILITIES = ProviderCapabilities(
            providerId = ID,
            providerName = NAME,
            canAddSongToPlaylist = true,
            canRemoveSongFromPlaylist = true,
        )
        val FEATURES = listOf(
            ProviderFeature("qqmusic_daily_songs", ID, NAME, "每日推荐歌曲", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, true),
            ProviderFeature("qqmusic_radio", ID, NAME, "私人 FM", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, true),
            ProviderFeature("qqmusic_daily_playlists", ID, NAME, "推荐歌单", ProviderFeatureCategory.Recommend, ProviderContentType.Playlists, true),
            ProviderFeature("qqmusic_user_playlists", ID, NAME, "我的歌单", ProviderFeatureCategory.MinePlaylists, ProviderContentType.Playlists, true),
        )
    }
}

private data class QqAudioQuality(
    val code: String,
    val prefix: String,
    val extension: String,
    val label: String,
) {
    fun filename(mediaId: String): String = "$prefix$mediaId.$extension"
}

private fun JsonObject.hasPositive(key: String): Boolean = string(key).toLongOrNull()?.let { it > 0 } == true

private fun qqSign(data: String): String {
    val randomPart = buildString {
        repeat(Random.nextInt(10, 17)) {
            append(QQ_SIGN_ALPHABET[Random.nextInt(QQ_SIGN_ALPHABET.length)])
        }
    }
    return "zza$randomPart${md5Hex("CJBPACrRuNy7$data")}"
}

private const val QQ_SIGN_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
