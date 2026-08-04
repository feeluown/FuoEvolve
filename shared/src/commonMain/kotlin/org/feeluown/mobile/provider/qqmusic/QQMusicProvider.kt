package org.feeluown.mobile.provider.qqmusic

import org.feeluown.mobile.AudioQualityPolicy
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.ProviderCapabilities
import org.feeluown.mobile.ProviderComment
import org.feeluown.mobile.ProviderContentType
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderFeatureCategory
import org.feeluown.mobile.ProviderInfo
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
import org.feeluown.mobile.provider.core.network.ProviderRequestKind
import io.ktor.http.Parameters

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
        return ProviderSearchResults(tracks = root.obj("data")?.obj("song")?.array("list").orEmpty().map(::song))
    }

    override suspend fun trackDetail(identifier: String) = songDetail(rawIdentifier(identifier))

    override suspend fun resolve(track: org.feeluown.mobile.MusicTrack, qualityPolicy: String): PlaybackPayload? {
        val (_, identifier) = splitResourceId(track.providerId ?: track.id)
        val songMid = identifier.ifBlank { track.id.substringAfterLast(':') }
        val detail = songDetailObject(songMid)
        val mediaId = detail?.obj("file")?.stringOrNull("media_mid").orEmpty().ifBlank { songMid }
        val credentials = currentCredentials()
        val guid = credentials?.cookies?.get("guid") ?: "10000"
        val uin = credentials?.cookies?.get("wxuin")?.removePrefix("o")
            ?: credentials?.cookies?.get("uin")
            ?: "0"
        val filename = qualityPolicy.toQqFilename(mediaId)
        val request = """
            {"req_0":{"module":"vkey.GetVkeyServer","method":"CgiGetVkey","param":{"filename":["$filename"],"guid":"$guid","songmid":["$songMid"],"songtype":[0],"uin":"$uin","loginflag":1,"platform":"20"}},"comm":{"format":"json","ct":24,"cv":0}}
        """.trimIndent()
        val sign = "zza0000000000${md5Hex("CJBPACrRuNy7$request")}"
        val root = http.postJson(
            providerId = ID,
            url = queryUrl("$VKEY_BASE/cgi-bin/musicu.fcg", mapOf("sign" to sign)),
            json = request,
            headers = authenticatedHeaders(mapOf("Referer" to "https://y.qq.com/")),
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val data = root.obj("req_0")?.obj("data")
        val item = data?.array("midurlinfo")?.firstOrNull()?.asObject() ?: return null
        val purl = item.stringOrNull("purl")
        val sip = data.array("sip").firstOrNull()?.asString().orEmpty()
        val testFile = data.stringOrNull("testfilewifi") ?: data.stringOrNull("testfile2g")
        val url = when {
            !purl.isNullOrBlank() && purl.startsWith("http") -> purl
            !purl.isNullOrBlank() -> sip + purl
            !testFile.isNullOrBlank() && testFile.startsWith("http") -> testFile
            !testFile.isNullOrBlank() -> sip + testFile
            else -> return null
        }
        return PlaybackPayload(
            url = url,
            title = track.title,
            artists = track.artists,
            album = track.album,
            source = ID,
            headers = mapOf("Referer" to "https://y.qq.com/"),
            coverUrl = track.coverUrl,
            durationMs = track.durationMs,
            lyrics = lyric(songMid),
            audioQuality = qualityPolicy,
            providerName = NAME,
        )
    }

    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<org.feeluown.mobile.MusicTrack> =
        playlistDetail(playlist, 0, 300).tracks

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
                    "json" to "1",
                    "utf8" to "1",
                    "onlysong" to "0",
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

    override suspend fun similarTracks(track: org.feeluown.mobile.MusicTrack): List<org.feeluown.mobile.MusicTrack> {
        val identifier = rawIdentifier(track.providerId ?: track.id)
        val root = rpc(
            """
            {"simsongs":{"module":"rcmusic.similarSongRadioServer","method":"get_simsongs","param":{"songid":"$identifier"}}}
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

    private suspend fun searchRoot(keyword: String) = http.getText(
        ID,
        queryUrl("$SEARCH_BASE/soso/fcgi-bin/search_for_qq_cp", mapOf("format" to "json", "n" to "30", "p" to "1", "w" to keyword, "cr" to "1", "g_tk" to "5381", "t" to "0")),
        authenticatedHeaders(mapOf("Referer" to "https://y.qq.com/")),
        cacheKey = "qqmusic:search:$keyword",
        cachePolicy = ProviderCachePolicies.search,
    ).value.let { providerJson.parseToJsonElement(it).asObject() }

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
        val userPlaylists = data.obj("mydiss")?.array("list")
            ?: data.obj("mymusic")?.array("list")
            ?: data.array("disslist")
        return buildList {
            creator?.stringOrNull("fav_pid")?.let { favoriteId ->
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
            {"req_0":{"module":"recommend.RecommendFeedServer","method":"get_recommend_feed","param":{"direction":0,"page":1,"v_cache":[],"v_uniq":[],"s_num":0}},"comm":{"ct":20,"cv":1770,"g_tk":5381,"uin":"$uin","format":"json","inCharset":"utf-8","outCharset":"utf-8","platform":"wk_v17","uid":"","guid":""}}
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
        val uin = currentUin().orEmpty()
        val requestLimit = limit.coerceIn(1, 50)
        val root = rpc(
            """
            {"songlist":{"module":"mb_track_radio_svr","method":"get_radio_track","param":{"id":99,"firstplay":0,"num":$requestLimit}},"comm":{"loginUin":"$uin","hostUin":0,"g_tk":5381,"inCharset":"utf8","outCharset":"utf-8","notice":0,"platform":"yqq","needNewCode":0}}
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
        val values = currentCredentials()?.cookies.orEmpty()
        return values["wxuin"]?.removePrefix("o")?.takeIf { it.isNotBlank() }
            ?: values["uin"]?.takeIf { it.isNotBlank() }
    }

    private suspend fun rpc(
        payload: String,
        kind: ProviderRequestKind = ProviderRequestKind.SafeRead,
    ): kotlinx.serialization.json.JsonObject {
        val sign = "zza0000000000${md5Hex("CJBPACrRuNy7$payload")}"
        return http.postJson(
            providerId = ID,
            url = queryUrl("$VKEY_BASE/cgi-bin/musicu.fcg", mapOf("sign" to sign)),
            json = payload,
            headers = authenticatedHeaders(mapOf("Referer" to "https://y.qq.com/")),
            kind = kind,
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
    }

    private suspend fun mutatePlaylist(
        method: String,
        playlist: ProviderPlaylist,
        track: org.feeluown.mobile.MusicTrack,
    ): ProviderMutationResult {
        val (_, playlistId) = splitResourceId(playlist.id, "playlist")
        val songId = songDetailObject(rawIdentifier(track.providerId ?: track.id))?.long("id")
            ?: return ProviderMutationResult(false, "无法读取 QQ 音乐歌曲编号")
        val root = rpc(
            """
            {"req_0":{"method":"$method","module":"music.musicasset.PlaylistDetailWrite","param":{"dirId":${playlistId.toLongOrNull() ?: 0},"v_songInfo":[{"songId":$songId,"songType":0}]}}}
            """.trimIndent(),
            kind = ProviderRequestKind.Mutation,
        )
        val success = root.obj("req_0")?.int("code") == 0
        return ProviderMutationResult(success, if (success) "操作成功" else "QQ 音乐歌单操作失败")
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

    private suspend fun lyric(identifier: String): String? = runCatching {
        val root = http.getText(
            providerId = ID,
            url = queryUrl(
                "$SEARCH_BASE/lyric/fcgi-bin/fcg_query_lyric_new.fcg",
                mapOf("songmid" to identifier, "format" to "json"),
            ),
            headers = authenticatedHeaders(mapOf("Referer" to "https://y.qq.com/")),
            cacheKey = "qqmusic:lyric:$identifier",
            cachePolicy = ProviderCachePolicies.lyric,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        root.stringOrNull("lyric")?.let(::base64DecodeToString)
    }.getOrNull()

    private fun song(value: kotlinx.serialization.json.JsonElement): org.feeluown.mobile.MusicTrack {
        val item = value.asObject()
        val identifier = item.string("songmid")
            .ifBlank { item.string("mid") }
            .ifBlank { item.string("songid") }
        val singers = item.array("singer").map { it.asObject().string("name") }.filter { it.isNotBlank() }.joinToString(" / ")
        val album = item.string("albumname").ifBlank { item.obj("album")?.string("name").orEmpty() }
        val albumMid = item.stringOrNull("albummid") ?: item.obj("album")?.stringOrNull("mid")
        return track(
            identifier = identifier,
            title = item.string("songname").ifBlank { item.string("name") }.ifBlank { item.string("songorig") },
            artists = singers,
            album = album,
            coverUrl = albumMid?.let { "https://y.qq.com/music/photo_new/T002R300x300M000${it}.jpg" },
            durationMs = item.long("interval")?.times(1_000),
            artistItemId = item.array("singer").firstOrNull()?.asObject()?.stringOrNull("mid")?.let { "artist:$ID:$it" },
            albumItemId = albumMid?.let { "album:$ID:$it" },
            providerUrl = "https://y.qq.com/n/ryqq/songDetail/$identifier",
        )
    }

    private fun songs(values: Iterable<kotlinx.serialization.json.JsonElement>): List<org.feeluown.mobile.MusicTrack> =
        values.mapNotNull { value ->
            runCatching { song(value) }.getOrNull()?.takeIf { it.id.substringAfterLast(':').isNotBlank() }
        }.distinctBy { it.id }

    private fun rawIdentifier(value: String): String = splitResourceId(value).second.ifBlank { value.substringAfterLast(':') }

    private companion object {
        const val ID = "qqmusic"
        const val NAME = "QQ 音乐"
        const val SEARCH_BASE = "https://c.y.qq.com"
        const val VKEY_BASE = "https://u.y.qq.com"
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

private fun String.toQqFilename(mediaId: String): String = when (this) {
    AudioQualityPolicy.Low.policy -> "M500$mediaId.mp3"
    AudioQualityPolicy.Standard.policy -> "C600$mediaId.m4a"
    AudioQualityPolicy.High.policy -> "M800$mediaId.mp3"
    AudioQualityPolicy.Highest.policy -> "F000$mediaId.flac"
    else -> "M500$mediaId.mp3"
}
