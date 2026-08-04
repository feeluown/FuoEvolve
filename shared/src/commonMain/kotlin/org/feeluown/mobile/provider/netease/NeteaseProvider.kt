package org.feeluown.mobile.provider.netease

import io.ktor.http.Parameters
import kotlinx.serialization.json.JsonObject
import org.feeluown.mobile.AudioQualityPolicy
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
import org.feeluown.mobile.ProviderMediaItemType
import org.feeluown.mobile.ProviderMutationResult
import org.feeluown.mobile.ProviderPlaylist
import org.feeluown.mobile.ProviderPlaylistDetail
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.ProviderVideo
import org.feeluown.mobile.VideoPlaybackPayload
import org.feeluown.mobile.provider.core.BaseKotlinProvider
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.ProviderCredentials
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.array
import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.boolean
import org.feeluown.mobile.provider.core.double
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.splitResourceId
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.network.ProviderCachePolicies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderRequestKind

class NeteaseProvider(
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
        val tracks = searchType(keyword, 1).array("songs").map(::song)
        val playlists = searchType(keyword, 1000).array("playlists").map { it.asObject().toPlaylist() }
        val artists = searchType(keyword, 100).array("artists").map { artist(it.asObject()) }
        val albums = searchType(keyword, 10).array("albums").map { album(it.asObject()) }
        return ProviderSearchResults(tracks = tracks, playlists = playlists, artists = artists, albums = albums)
    }

    override suspend fun trackDetail(identifier: String) = songDetail(rawIdentifier(identifier))?.let { song(it) }

    override suspend fun resolve(track: org.feeluown.mobile.MusicTrack, qualityPolicy: String): PlaybackPayload? {
        val (_, identifier) = splitResourceId(track.providerId ?: track.id)
        val id = identifier.ifBlank { track.id.substringAfterLast(':') }
        val credentials = currentCredentials()
        val quality = qualityPolicy.toNeteaseLevel()
        val response = http.postForm(
            providerId = ID,
            url = "$BASE/api/song/enhance/player/url/v1",
            form = Parameters.build {
                append("ids", "[$id]")
                append("level", quality)
                append("encodeType", "mp3")
                append("csrf_token", credentials?.cookies?.get("__csrf").orEmpty())
            },
            headers = authenticatedHeaders(mapOf("Referer" to "https://music.163.com/")),
            cacheKey = null,
        )
        val data = runCatching { providerJson.parseToJsonElement(response.value).asObject().array("data") }.getOrNull()
            ?.firstOrNull()
            ?.asObject()
            ?: return null
        val url = data.stringOrNull("url") ?: return null
        return PlaybackPayload(
            url = url,
            title = track.title,
            artists = track.artists,
            album = track.album,
            source = ID,
            headers = mapOf("Referer" to "https://music.163.com/"),
            coverUrl = track.coverUrl,
            durationMs = data.long("time") ?: track.durationMs,
            lyrics = lyric(id),
            audioQuality = data.stringOrNull("type") ?: quality,
            providerName = NAME,
        )
    }

    override suspend fun authState(): ProviderAuthState {
        val credentials = currentCredentials()
        if (credentials == null) return authState(null)
        val user = runCatching {
            http.getText(
                providerId = ID,
                url = "$BASE/api/user/account",
                headers = authenticatedHeaders(),
                cacheKey = null,
            ).value.let { providerJson.parseToJsonElement(it).asObject() }
        }.getOrNull()
        return ProviderAuthState(
            providerId = ID,
            providerName = NAME,
            isLoggedIn = user?.let { it.int("code") == 200 } ?: credentialsArePresent(credentials),
            userName = user?.obj("profile")?.stringOrNull("nickname"),
        )
    }

    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<org.feeluown.mobile.MusicTrack> {
        return playlistDetail(playlist, 0, 300).tracks
    }

    override suspend fun playlistDetail(playlist: ProviderPlaylist, offset: Int, limit: Int): ProviderPlaylistDetail {
        val (_, identifier) = splitResourceId(playlist.id, "playlist")
        val result = http.getText(
            providerId = ID,
            url = queryUrl("$BASE/api/playlist/detail", mapOf("id" to identifier, "limit" to limit.toString(), "offset" to offset.toString())),
            headers = authenticatedHeaders(mapOf("Referer" to "https://music.163.com/")),
            cacheKey = "netease:playlist:$identifier:$offset:$limit",
            cachePolicy = ProviderCachePolicies.detail,
        )
        val root = providerJson.parseToJsonElement(result.value).asObject()
        val playlistObject = root.obj("playlist") ?: return ProviderPlaylistDetail(playlist)
        val tracks = playlistObject.array("tracks").map(::song)
        val actualPlaylist = playlistObject.toPlaylist()
        val count = actualPlaylist.trackCount ?: playlist.trackCount ?: tracks.size
        return ProviderPlaylistDetail(
            playlist = actualPlaylist,
            tracks = tracks,
            tracksNextOffset = offset + tracks.size,
            tracksHasMore = offset + tracks.size < count,
        )
    }

    override suspend fun mediaItemTracks(item: ProviderMediaItem): List<org.feeluown.mobile.MusicTrack> {
        val (_, identifier) = splitResourceId(item.id, if (item.type == ProviderMediaItemType.Artist) "artist" else "album")
        val root = if (item.type == ProviderMediaItemType.Artist) {
            http.getText(ID, queryUrl("$BASE/api/artist", mapOf("id" to identifier)), authenticatedHeaders(), cacheKey = "netease:artist:$identifier", cachePolicy = ProviderCachePolicies.detail)
                .value.let { providerJson.parseToJsonElement(it).asObject() }
        } else {
            http.getText(ID, queryUrl("$BASE/api/album", mapOf("id" to identifier)), authenticatedHeaders(), cacheKey = "netease:album:$identifier", cachePolicy = ProviderCachePolicies.detail)
                .value.let { providerJson.parseToJsonElement(it).asObject() }
        }
        val songs = if (item.type == ProviderMediaItemType.Artist) root.array("hotSongs") else root.obj("album")?.array("songs") ?: root.array("songs")
        return songs.map(::song)
    }

    override suspend fun mediaItemDetail(item: ProviderMediaItem, tracksOffset: Int, albumsOffset: Int, limit: Int): ProviderMediaItemDetail {
        val tracks = mediaItemTracks(item).drop(tracksOffset).take(limit)
        return ProviderMediaItemDetail(
            item = item,
            tracks = tracks,
            tracksNextOffset = tracksOffset + tracks.size,
            tracksHasMore = tracks.size == limit,
        )
    }

    override suspend fun similarTracks(track: org.feeluown.mobile.MusicTrack): List<org.feeluown.mobile.MusicTrack> {
        val (_, identifier) = splitResourceId(track.providerId ?: track.id)
        val root = http.getText(
            ID,
            queryUrl("$BASE/api/song/similar", mapOf("id" to identifier)),
            authenticatedHeaders(),
            cacheKey = "netease:similar:$identifier",
            cachePolicy = ProviderCachePolicies.recommendation,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        return root.array("songs").map(::song)
    }

    override suspend fun hotComments(track: org.feeluown.mobile.MusicTrack): List<ProviderComment> {
        val (_, identifier) = splitResourceId(track.providerId ?: track.id)
        val root = http.getText(
            ID,
            queryUrl("$BASE/api/comment/hot", mapOf("id" to identifier, "type" to "0", "limit" to "20")),
            authenticatedHeaders(),
            cacheKey = "netease:comments:$identifier",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        return root.array("hotComments").map { element ->
            val comment = element.asObject()
            ProviderComment(
                id = comment.string("commentId"),
                userName = comment.obj("user")?.string("nickname").orEmpty(),
                content = comment.string("content"),
                likedCount = comment.long("likedCount") ?: 0,
                timeSeconds = (comment.long("time") ?: 0) / 1_000,
            )
        }
    }

    override suspend fun trackVideo(track: org.feeluown.mobile.MusicTrack): ProviderVideo? {
        val (_, identifier) = splitResourceId(track.providerId ?: track.id)
        val root = http.getText(ID, queryUrl("$BASE/api/song/mv", mapOf("id" to identifier)), authenticatedHeaders(), cacheKey = "netease:mv:$identifier", cachePolicy = ProviderCachePolicies.detail)
            .value.let { providerJson.parseToJsonElement(it).asObject() }
        return root.array("mvs").firstOrNull()?.asObject()?.let { mv ->
            video(
                identifier = mv.string("id"),
                title = mv.string("name").ifBlank { track.title },
                artists = track.artists,
                coverUrl = mv.stringOrNull("cover"),
                durationMs = mv.long("duration"),
                providerUrl = "https://music.163.com/#/mv?id=${mv.string("id")}",
            )
        }
    }

    override suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload = VideoPlaybackPayload(video = video)

    override suspend fun playlistOperationTargets(track: org.feeluown.mobile.MusicTrack): List<ProviderPlaylist> {
        val auth = authState()
        if (!auth.isLoggedIn) return emptyList()
        val uid = currentUserId() ?: return emptyList()
        val root = http.getText(ID, queryUrl("$BASE/api/user/playlist", mapOf("uid" to uid, "limit" to "100")), authenticatedHeaders(), cacheKey = null)
            .value.let { providerJson.parseToJsonElement(it).asObject() }
        return root.array("playlist").map { it.asObject().toPlaylist() }
    }

    override suspend fun addTrackToPlaylist(playlist: ProviderPlaylist, track: org.feeluown.mobile.MusicTrack): ProviderMutationResult =
        mutatePlaylist("add", playlist, track)

    override suspend fun removeTrackFromPlaylist(playlist: ProviderPlaylist, track: org.feeluown.mobile.MusicTrack): ProviderMutationResult =
        mutatePlaylist("del", playlist, track)

    override suspend fun createPlaylist(name: String): ProviderMutationResult {
        val root = http.postForm(ID, "$BASE/api/playlist/create", Parameters.build { append("name", name) }, authenticatedHeaders(), ProviderRequestKind.Mutation)
            .value.let { providerJson.parseToJsonElement(it).asObject() }
        return mutation(root, "已新建：$name")
    }

    override suspend fun deletePlaylist(playlist: ProviderPlaylist): ProviderMutationResult {
        val (_, identifier) = splitResourceId(playlist.id, "playlist")
        val root = http.postForm(ID, "$BASE/api/playlist/delete", Parameters.build { append("id", identifier); append("pid", identifier) }, authenticatedHeaders(), ProviderRequestKind.Mutation)
            .value.let { providerJson.parseToJsonElement(it).asObject() }
        return mutation(root, "已删除：${playlist.title}")
    }

    override suspend fun loadFeature(feature: ProviderFeature, offset: Int, limit: Int): ProviderContentSection {
        if (feature.requiresLogin && !authState().isLoggedIn) {
            return ProviderContentSection(feature, isLoginRequired = true)
        }
        val payload = when (feature.id) {
            "netease_daily_songs" -> {
                val root = http.getText(
                    ID,
                    "$BASE/api/recommend/songs",
                    authenticatedHeaders(),
                    cacheKey = "netease:recommend:songs",
                    cachePolicy = ProviderCachePolicies.recommendation,
                ).value.let { providerJson.parseToJsonElement(it).asObject() }
                val songs = root.obj("data")?.array("dailySongs").orEmpty()
                ProviderContentSection(feature, tracks = songs.drop(offset).take(limit).map(::song), nextOffset = offset + limit, hasMore = songs.size > offset + limit)
            }
            "netease_radio" -> {
                val songs = http.getText(
                    ID,
                    "$BASE/api/personal_fm",
                    authenticatedHeaders(),
                    cacheKey = null,
                ).value.let { providerJson.parseToJsonElement(it).asObject().array("data") }
                ProviderContentSection(feature, tracks = songs.drop(offset).take(limit).map(::song), nextOffset = offset + limit, hasMore = songs.size > offset + limit)
            }
            "netease_toplists" -> {
                val root = http.getText(ID, "$BASE/api/toplist", authenticatedHeaders(), cacheKey = "netease:toplist", cachePolicy = ProviderCachePolicies.recommendation)
                    .value.let { providerJson.parseToJsonElement(it).asObject() }
                val playlists = root.array("list")
                ProviderContentSection(feature, playlists = playlists.drop(offset).take(limit).map { it.asObject().toPlaylist() }, nextOffset = offset + limit, hasMore = playlists.size > offset + limit)
            }
            "netease_daily_playlists" -> {
                val root = http.getText(ID, queryUrl("$BASE/api/personalized", mapOf("limit" to limit.toString())), authenticatedHeaders(), cacheKey = "netease:personalized:$limit", cachePolicy = ProviderCachePolicies.recommendation)
                    .value.let { providerJson.parseToJsonElement(it).asObject() }
                val playlists = root.array("result")
                ProviderContentSection(feature, playlists = playlists.drop(offset).take(limit).map { it.asObject().toPlaylist() }, nextOffset = offset + limit, hasMore = playlists.size > offset + limit)
            }
            "netease_user_playlists", "netease_favorite_playlists" -> {
                val uid = currentUserId()
                if (uid == null) {
                    ProviderContentSection(feature, errorMessage = "无法读取网易云音乐用户信息")
                } else {
                    val root = http.getText(
                        ID,
                        queryUrl("$BASE/api/user/playlist", mapOf("uid" to uid, "limit" to "100")),
                        authenticatedHeaders(),
                        cacheKey = null,
                    ).value.let { providerJson.parseToJsonElement(it).asObject() }
                    val playlists = root.array("playlist").filter { item ->
                        feature.id != "netease_favorite_playlists" || item.asObject().boolean("subscribed")
                    }
                    ProviderContentSection(feature, playlists = playlists.drop(offset).take(limit).map { it.asObject().toPlaylist() }, nextOffset = offset + limit, hasMore = playlists.size > offset + limit)
                }
            }
            "netease_favorite_songs" -> {
                val root = http.getText(
                    ID,
                    queryUrl("$BASE/api/song/sublist", mapOf("limit" to "100")),
                    authenticatedHeaders(),
                    cacheKey = null,
                ).value.let { providerJson.parseToJsonElement(it).asObject() }
                val songs = root.array("data")
                ProviderContentSection(feature, tracks = songs.drop(offset).take(limit).map(::song), nextOffset = offset + limit, hasMore = songs.size > offset + limit)
            }
            "netease_cloud_songs" -> {
                val root = http.getText(
                    ID,
                    queryUrl("$BASE/api/user/cloud", mapOf("limit" to "100", "offset" to offset.toString())),
                    authenticatedHeaders(),
                    cacheKey = null,
                ).value.let { providerJson.parseToJsonElement(it).asObject() }
                val songs = root.array("data").mapNotNull { item ->
                    val value = item.asObject()["simpleSong"] ?: item
                    runCatching { song(value) }.getOrNull()
                }
                ProviderContentSection(feature, tracks = songs.take(limit), nextOffset = offset + songs.size, hasMore = songs.size == limit)
            }
            "netease_favorite_artists", "netease_favorite_albums" -> {
                val endpoint = if (feature.id == "netease_favorite_artists") "artist/sublist" else "album/sublist"
                val root = http.getText(
                    ID,
                    queryUrl("$BASE/api/$endpoint", mapOf("limit" to "100", "offset" to offset.toString())),
                    authenticatedHeaders(),
                    cacheKey = null,
                ).value.let { providerJson.parseToJsonElement(it).asObject() }
                val type = if (feature.id == "netease_favorite_artists") ProviderMediaItemType.Artist else ProviderMediaItemType.Album
                val items = root.array("data").map { value ->
                    if (type == ProviderMediaItemType.Artist) artist(value.asObject()) else album(value.asObject())
                }
                ProviderContentSection(feature, mediaItems = items.take(limit), nextOffset = offset + items.size, hasMore = items.size == limit)
            }
            else -> ProviderContentSection(feature, errorMessage = "网易云音乐暂不支持该内容")
        }
        return payload
    }

    private suspend fun searchType(keyword: String, type: Int): JsonObject {
        return http.postForm(
            ID,
            "$BASE/api/search/get",
            Parameters.build {
                append("s", keyword)
                append("type", type.toString())
                append("offset", "0")
                append("total", "true")
                append("limit", "30")
            },
            authenticatedHeaders(mapOf("Referer" to "https://music.163.com/")),
            cacheKey = "netease:search:$type:$keyword",
            cachePolicy = ProviderCachePolicies.search,
        ).value.let { providerJson.parseToJsonElement(it).asObject().obj("result") ?: providerJson.parseToJsonElement(it).asObject() }
    }

    private suspend fun songDetail(identifier: String): JsonObject? {
        val root = http.getText(ID, queryUrl("$BASE/api/song/detail", mapOf("ids" to "[$identifier]")), authenticatedHeaders(), cacheKey = "netease:song:$identifier", cachePolicy = ProviderCachePolicies.detail)
            .value.let { providerJson.parseToJsonElement(it).asObject() }
        return root.array("songs").firstOrNull()?.asObject()
    }

    private suspend fun lyric(identifier: String): String? {
        val root = http.getText(ID, queryUrl("$BASE/api/song/lyric", mapOf("id" to identifier, "lv" to "1", "kv" to "1", "tv" to "-1")), authenticatedHeaders(), cacheKey = "netease:lyric:$identifier", cachePolicy = ProviderCachePolicies.lyric)
            .value.let { providerJson.parseToJsonElement(it).asObject() }
        return root.obj("lrc")?.stringOrNull("lyric")
    }

    private suspend fun currentUserId(): String? = runCatching {
        val root = http.getText(
            providerId = ID,
            url = "$BASE/api/user/account",
            headers = authenticatedHeaders(),
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        root.obj("profile")?.stringOrNull("userId")
            ?: root.obj("account")?.stringOrNull("id")
            ?: root.stringOrNull("userId")
            ?: root.stringOrNull("id")
    }.getOrNull()

    private fun credentialsArePresent(credentials: org.feeluown.mobile.provider.core.ProviderCredentials): Boolean =
        credentials.cookies.isNotEmpty() ||
            !credentials.cookieHeader.isNullOrBlank() ||
            !credentials.authorization.isNullOrBlank()

    private fun song(value: kotlinx.serialization.json.JsonElement): org.feeluown.mobile.MusicTrack {
        val item = value.asObject()
        val artist = item.array("ar").firstOrNull()?.asObject()
        val album = item.obj("al")
        val identifier = item.string("id")
        return track(
            identifier = identifier,
            title = item.string("name"),
            artists = item.array("ar").map { it.asObject().string("name") }.filter { it.isNotBlank() }.joinToString(" / "),
            album = album?.string("name").orEmpty(),
            coverUrl = album?.stringOrNull("picUrl"),
            durationMs = item.long("dt") ?: item.long("duration"),
            artistItemId = artist?.stringOrNull("id")?.let { "artist:$ID:$it" },
            albumItemId = album?.stringOrNull("id")?.let { "album:$ID:$it" },
            providerUrl = "https://music.163.com/#/song?id=$identifier",
        )
    }

    private fun JsonObject.toPlaylist(): ProviderPlaylist = playlist(
        identifier = string("id"),
        title = string("name"),
        coverUrl = stringOrNull("coverImgUrl") ?: stringOrNull("picUrl"),
        description = string("description").ifBlank { string("creator").ifBlank { string("creatorName") } },
        playCount = long("playCount"),
        trackCount = int("trackCount"),
        providerUrl = "https://music.163.com/#/playlist?id=${string("id")}",
    )

    private fun artist(value: JsonObject): ProviderMediaItem = mediaItem(
        type = ProviderMediaItemType.Artist,
        identifier = value.string("id"),
        title = value.string("name"),
        coverUrl = value.stringOrNull("picUrl"),
        providerUrl = "https://music.163.com/#/artist?id=${value.string("id")}",
    )

    private fun album(value: JsonObject): ProviderMediaItem = mediaItem(
        type = ProviderMediaItemType.Album,
        identifier = value.string("id"),
        title = value.string("name"),
        coverUrl = value.stringOrNull("picUrl"),
        providerUrl = "https://music.163.com/#/album?id=${value.string("id")}",
    )

    private suspend fun mutatePlaylist(action: String, playlist: ProviderPlaylist, track: org.feeluown.mobile.MusicTrack): ProviderMutationResult {
        val (_, playlistId) = splitResourceId(playlist.id, "playlist")
        val (_, trackId) = splitResourceId(track.providerId ?: track.id)
        val root = http.postForm(ID, "$BASE/api/playlist/track/$action", Parameters.build { append("pid", playlistId); append("trackIds", trackId); append("tracks", trackId) }, authenticatedHeaders(), ProviderRequestKind.Mutation)
            .value.let { providerJson.parseToJsonElement(it).asObject() }
        return mutation(root, if (action == "add") "已添加到：${playlist.title}" else "已从歌单移除：${track.title}")
    }

    private fun mutation(root: kotlinx.serialization.json.JsonObject, successMessage: String): ProviderMutationResult =
        ProviderMutationResult(root.int("code") == 200 || root.boolean("success"), if (root.int("code") == 200) successMessage else root.string("message").ifBlank { "操作失败" })

    private fun String.toNeteaseLevel(): String = when (this) {
        AudioQualityPolicy.High.policy, AudioQualityPolicy.Highest.policy -> "exhigh"
        AudioQualityPolicy.Standard.policy -> "standard"
        AudioQualityPolicy.Low.policy -> "standard"
        else -> "exhigh"
    }

    private fun rawIdentifier(value: String): String = splitResourceId(value).second.ifBlank { value.substringAfterLast(':') }

    private companion object {
        const val ID = "netease"
        const val NAME = "网易云音乐"
        const val BASE = "https://music.163.com"
        val INFO = ProviderInfo(
            providerId = ID,
            providerName = NAME,
            loginConfig = org.feeluown.mobile.ProviderLoginConfig("https://music.163.com", listOf(listOf("MUSIC_U"))),
        )
        val CAPABILITIES = ProviderCapabilities(
            providerId = ID,
            providerName = NAME,
            canAddSongToPlaylist = true,
            canRemoveSongFromPlaylist = true,
            canCreatePlaylist = true,
            canDeletePlaylist = true,
        )
        val FEATURES = listOf(
            ProviderFeature("netease_daily_songs", ID, NAME, "每日推荐歌曲", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, true),
            ProviderFeature("netease_daily_playlists", ID, NAME, "推荐歌单", ProviderFeatureCategory.Recommend, ProviderContentType.Playlists, true),
            ProviderFeature("netease_radio", ID, NAME, "私人 FM", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, true),
            ProviderFeature("netease_toplists", ID, NAME, "排行榜", ProviderFeatureCategory.Music, ProviderContentType.Playlists, false),
            ProviderFeature("netease_user_playlists", ID, NAME, "我的歌单", ProviderFeatureCategory.MinePlaylists, ProviderContentType.Playlists, true),
            ProviderFeature("netease_favorite_songs", ID, NAME, "收藏歌曲", ProviderFeatureCategory.Mine, ProviderContentType.Songs, true),
            ProviderFeature("netease_cloud_songs", ID, NAME, "云盘歌曲", ProviderFeatureCategory.Mine, ProviderContentType.Songs, true),
            ProviderFeature("netease_favorite_playlists", ID, NAME, "收藏歌单", ProviderFeatureCategory.MineFavoritePlaylists, ProviderContentType.Playlists, true),
            ProviderFeature("netease_favorite_artists", ID, NAME, "收藏歌手", ProviderFeatureCategory.Mine, ProviderContentType.Artists, true),
            ProviderFeature("netease_favorite_albums", ID, NAME, "收藏专辑", ProviderFeatureCategory.Mine, ProviderContentType.Albums, true),
        )
    }
}
