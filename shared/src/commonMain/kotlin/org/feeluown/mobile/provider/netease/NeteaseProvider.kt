package org.feeluown.mobile.provider.netease

import io.ktor.http.Parameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import org.feeluown.mobile.provider.core.asString
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
        val videos = searchType(keyword, 1004).array("mvs").mapNotNull { value ->
            runCatching { mvVideo(value.asObject()) }.getOrNull()
        }
        return ProviderSearchResults(
            tracks = tracks,
            playlists = playlists,
            artists = artists,
            albums = albums,
            videos = videos,
        )
    }

    override suspend fun trackDetail(identifier: String) = songDetail(rawIdentifier(identifier))?.let { song(it) }

    override suspend fun resolve(track: org.feeluown.mobile.MusicTrack, qualityPolicy: String): PlaybackPayload? {
        val (_, identifier) = splitResourceId(track.providerId ?: track.id)
        val id = identifier.ifBlank { track.id.substringAfterLast(':') }
        val credentials = currentCredentials()
        val bitrate = qualityPolicy.toNeteaseBitrate()
        val root = neteaseWeApiPost(
            "$BASE/weapi/song/enhance/player/url",
            """{"ids":[$id],"br":$bitrate,"csrf_token":"${credentials?.cookies?.get("__csrf").orEmpty().jsonString()}"}""",
        )
        val data = root.array("data").firstOrNull()?.asObject() ?: return null
        if (data["freeTrialInfo"]?.let { it !is JsonNull } == true) return null
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
            audioQuality = data.stringOrNull("type") ?: qualityPolicy,
            providerName = NAME,
        )
    }

    override suspend fun lyrics(track: org.feeluown.mobile.MusicTrack): String? {
        val (_, identifier) = splitResourceId(track.providerId ?: track.id)
        val id = identifier.ifBlank { track.id.substringAfterLast(':') }
        return lyric(id)
    }

    override suspend fun authState(): ProviderAuthState {
        val credentials = currentCredentials()
        if (credentials == null) return authState(null)
        if (!credentialsArePresent(credentials)) return authState(credentials)
        return runCatching { remoteAuthState() }.getOrElse { loggedOutState() }
    }

    override suspend fun loginWithCookies(cookiesJson: String): ProviderAuthState {
        super.loginWithCookies(cookiesJson)
        val state = remoteAuthState()
        if (!state.isLoggedIn) credentials.delete(ID)
        return state
    }

    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<org.feeluown.mobile.MusicTrack> {
        return playlistDetail(playlist, 0, 300).tracks
    }

    override suspend fun playlistDetail(playlist: ProviderPlaylist, offset: Int, limit: Int): ProviderPlaylistDetail {
        val (_, identifier) = splitResourceId(playlist.id, "playlist")
        val root = runCatching {
            neteaseWeApiPost(
                "$BASE/weapi/v3/playlist/detail",
                """{"id":${neteaseId(identifier)},"limit":$limit,"offset":$offset,"n":$limit}""",
            )
        }.getOrNull()?.takeIf { it.obj("playlist") != null || it.obj("result") != null }
            ?: http.getText(
                providerId = ID,
                url = queryUrl("$BASE/api/playlist/detail", mapOf("id" to identifier, "limit" to limit.toString(), "offset" to offset.toString())),
                headers = neteaseAuthenticatedHeaders(mapOf("Referer" to "https://music.163.com/")),
                cacheKey = "netease:playlist:$identifier:$offset:$limit",
                cachePolicy = ProviderCachePolicies.detail,
            ).value.let { parseNeteaseResponse(it) }
        val playlistObject = root.obj("playlist")
            ?: root.obj("result")?.obj("playlist")
            ?: root.obj("result")
            ?: return ProviderPlaylistDetail(playlist)
        val trackIds = playlistObject.array("trackIds")
        val trackValues = if (trackIds.isNotEmpty()) {
            trackIds.drop(offset).take(limit).map { trackId ->
                JsonObject(mapOf("id" to JsonPrimitive(trackId.asObject().string("id"))))
            }
        } else {
            playlistObject.array("tracks").drop(offset).take(limit)
        }
        val tracks = loadSongs(trackValues)
        val actualPlaylist = playlistObject.toPlaylist()
        val count = actualPlaylist.trackCount ?: playlist.trackCount ?: trackIds.size.coerceAtLeast(tracks.size)
        return ProviderPlaylistDetail(
            playlist = actualPlaylist,
            tracks = tracks,
            tracksNextOffset = offset + trackValues.size,
            tracksHasMore = offset + trackValues.size < count,
        )
    }

    override suspend fun mediaItemTracks(item: ProviderMediaItem): List<org.feeluown.mobile.MusicTrack> {
        return mediaItemDetail(item, tracksOffset = 0, albumsOffset = 0, limit = 300).tracks
    }

    override suspend fun mediaItemDetail(item: ProviderMediaItem, tracksOffset: Int, albumsOffset: Int, limit: Int): ProviderMediaItemDetail {
        val (_, identifier) = splitResourceId(
            item.id,
            if (item.type == ProviderMediaItemType.Artist) "artist" else "album",
        )
        return if (item.type == ProviderMediaItemType.Artist) {
            val artistSongs = artistSongsPage(identifier, tracksOffset, limit)
            val artistAlbums = artistAlbumsPage(identifier, albumsOffset, limit)
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
            val root = albumRoot(identifier)
            val albumObject = root.obj("album") ?: root
            val allTracks = loadSongs(albumObject.array("songs").toList())
            val tracks = allTracks.drop(tracksOffset).take(limit)
            ProviderMediaItemDetail(
                item = item.copy(
                    title = albumObject.string("name").ifBlank { item.title },
                    coverUrl = albumObject.stringOrNull("picUrl") ?: item.coverUrl,
                    description = albumObject.string("description")
                        .ifBlank { albumObject.string("briefDesc") }
                        .ifBlank { item.description },
                    trackCount = albumObject.int("size") ?: allTracks.size,
                ),
                tracks = tracks,
                tracksNextOffset = tracksOffset + tracks.size,
                tracksHasMore = tracksOffset + tracks.size < allTracks.size,
            )
        }
    }

    override suspend fun similarTracks(track: org.feeluown.mobile.MusicTrack): List<org.feeluown.mobile.MusicTrack> {
        val (_, identifier) = splitResourceId(track.providerId ?: track.id)
        val root = http.getText(
            ID,
            queryUrl(
                "$BASE/api/discovery/simiSong",
                mapOf("songid" to identifier, "offset" to "0", "total" to "true", "limit" to "10"),
            ),
            authenticatedHeaders(),
            cacheKey = "netease:similar:$identifier",
            cachePolicy = ProviderCachePolicies.recommendation,
        ).value.let { parseNeteaseResponse(it) }
        return root.array("songs").map(::song)
    }

    override suspend fun hotComments(track: org.feeluown.mobile.MusicTrack): List<ProviderComment> {
        val (_, identifier) = splitResourceId(track.providerId ?: track.id)
        val resourceId = "R_SO_4_$identifier"
        val root = neteaseWeApiPost(
            "$BASE/weapi/v1/resource/comments/$resourceId",
            """{"rid":"$resourceId","offset":"0","total":"true","limit":"20","csrf_token":"${csrfToken().jsonString()}"}""",
        )
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
        val songObject = runCatching { songDetail(identifier) }.getOrNull() ?: return null
        val mvIdentifier = (songObject.long("mvid") ?: songObject.long("mv"))
            ?.takeIf { it > 0 }
            ?.toString()
            ?: return null
        val mv = runCatching { mvDetail(mvIdentifier).obj("data") }.getOrNull() ?: return null
        val artists = mv.array("artists").map { it.asObject().string("name") }
            .filter(String::isNotBlank)
            .joinToString(" / ")
            .ifBlank { track.artists }
        return video(
            identifier = mvIdentifier,
            title = mv.string("name").ifBlank { track.title },
            artists = artists,
            coverUrl = mv.stringOrNull("cover") ?: mv.stringOrNull("coverUrl"),
            durationMs = mv.long("duration"),
            providerUrl = "https://music.163.com/#/mv?id=$mvIdentifier",
        )
    }

    override suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload {
        val (_, identifier) = splitResourceId(video.id, "video")
        val mv = runCatching { mvDetail(identifier).obj("data") }.getOrNull()
            ?: return VideoPlaybackPayload(video = video)
        val selected = mv.obj("brs")?.entries
            ?.mapNotNull { (quality, value) ->
                value.asString().takeIf(String::isNotBlank)?.let { quality to it }
            }
            ?.maxByOrNull { (quality, _) -> quality.toIntOrNull() ?: 0 }
            ?: return VideoPlaybackPayload(video = video)
        return VideoPlaybackPayload(
            video = video,
            url = selected.second,
            videoUrl = selected.second,
            headers = mapOf("Referer" to "https://music.163.com/"),
            quality = selected.first,
        )
    }

    private suspend fun mvDetail(identifier: String): JsonObject = http.getText(
        ID,
        queryUrl("$BASE/api/mv/detail", mapOf("id" to identifier)),
        authenticatedHeaders(),
        cacheKey = "netease:mv:$identifier",
        cachePolicy = ProviderCachePolicies.detail,
    ).value.let { parseNeteaseResponse(it) }

    override suspend fun playlistOperationTargets(track: org.feeluown.mobile.MusicTrack): List<ProviderPlaylist> {
        val auth = authState()
        if (!auth.isLoggedIn) return emptyList()
        val uid = currentUserId() ?: return emptyList()
        return userPlaylistObjects(uid).map { it.toPlaylist() }
    }

    override suspend fun addTrackToPlaylist(playlist: ProviderPlaylist, track: org.feeluown.mobile.MusicTrack): ProviderMutationResult =
        mutatePlaylist("add", playlist, track)

    override suspend fun removeTrackFromPlaylist(playlist: ProviderPlaylist, track: org.feeluown.mobile.MusicTrack): ProviderMutationResult =
        mutatePlaylist("del", playlist, track)

    override suspend fun createPlaylist(name: String): ProviderMutationResult {
        val uid = currentUserId() ?: return ProviderMutationResult(false, "无法读取网易云音乐用户信息")
        val root = http.postForm(
            ID,
            "$BASE/api/playlist/create",
            Parameters.build { append("uid", uid); append("name", name) },
            neteaseAuthenticatedHeaders(),
            ProviderRequestKind.Mutation,
        )
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
                val root = neteaseWeApiPost("$BASE/weapi/v3/discovery/recommend/songs", "{}")
                val songs = root.obj("data")?.array("dailySongs").orEmpty()
                ProviderContentSection(feature, tracks = songs.drop(offset).take(limit).map(::song), nextOffset = offset + limit, hasMore = songs.size > offset + limit)
            }
            "netease_recommended_new_songs" -> {
                val requestLimit = (offset + limit).coerceAtLeast(limit)
                val root = neteaseWeApiPost(
                    "$BASE/weapi/personalized/newsong",
                    """{"type":"recommend","limit":$requestLimit,"areaId":0}""",
                )
                val values = root.array("result")
                val songs = values.map { value -> value.asObject().obj("song") ?: value }
                val page = songs.drop(offset).take(limit)
                ProviderContentSection(
                    feature = feature,
                    tracks = loadSongs(page),
                    nextOffset = offset + page.size,
                    hasMore = songs.size > offset + page.size,
                )
            }
            "netease_radio" -> {
                val songs = http.getText(
                    ID,
                    "$BASE/api/radio/get",
                    neteaseAuthenticatedHeaders(),
                    cacheKey = null,
                ).value.let { parseNeteaseResponse(it).array("data") }
                ProviderContentSection(feature, tracks = songs.drop(offset).take(limit).map(::song), nextOffset = offset + limit, hasMore = songs.size > offset + limit)
            }
            "netease_toplists" -> {
                val root = http.getText(ID, "$BASE/api/toplist", authenticatedHeaders(), cacheKey = "netease:toplist", cachePolicy = ProviderCachePolicies.recommendation)
                    .value.let { parseNeteaseResponse(it) }
                val playlists = root.array("list")
                ProviderContentSection(feature, playlists = playlists.drop(offset).take(limit).map { it.asObject().toPlaylist() }, nextOffset = offset + limit, hasMore = playlists.size > offset + limit)
            }
            "netease_new_songs" -> {
                val root = neteaseWeApiPost(
                    "$BASE/weapi/v1/discovery/new/songs",
                    """{"areaId":0,"total":true}""",
                )
                val songs = root.array("data")
                val page = songs.drop(offset).take(limit)
                ProviderContentSection(
                    feature = feature,
                    tracks = page.map(::song),
                    nextOffset = offset + page.size,
                    hasMore = songs.size > offset + page.size,
                )
            }
            "netease_new_albums" -> {
                val root = neteaseWeApiPost(
                    "$BASE/weapi/album/new",
                    """{"limit":$limit,"offset":$offset,"total":true,"area":"ALL"}""",
                )
                val values = root.array("albums")
                ProviderContentSection(
                    feature = feature,
                    mediaItems = values.mapNotNull { value -> runCatching { album(value.asObject()) }.getOrNull() },
                    nextOffset = offset + values.size,
                    hasMore = root.boolean("more") || root.int("total")?.let { offset + values.size < it } == true || values.size == limit,
                )
            }
            "netease_top_artists" -> {
                val root = neteaseWeApiPost(
                    "$BASE/weapi/artist/top",
                    """{"limit":$limit,"offset":$offset,"total":true}""",
                )
                val values = root.array("artists")
                ProviderContentSection(
                    feature = feature,
                    mediaItems = values.mapNotNull { value -> runCatching { artist(value.asObject()) }.getOrNull() },
                    nextOffset = offset + values.size,
                    hasMore = root.boolean("more") || values.size == limit,
                )
            }
            "netease_highquality_playlists" -> {
                val requestLimit = (offset + limit).coerceAtLeast(limit)
                val root = neteaseWeApiPost(
                    "$BASE/weapi/playlist/highquality/list",
                    """{"cat":"全部","limit":$requestLimit,"lasttime":0,"total":true}""",
                )
                val values = root.array("playlists")
                val page = values.drop(offset).take(limit)
                ProviderContentSection(
                    feature = feature,
                    playlists = page.mapNotNull { value -> runCatching { value.asObject().toPlaylist() }.getOrNull() },
                    nextOffset = offset + page.size,
                    hasMore = values.size > offset + page.size || root.boolean("more"),
                )
            }
            "netease_recommended_mvs" -> {
                val root = neteaseWeApiPost("$BASE/weapi/personalized/mv", "{}")
                val values = root.array("result")
                val page = values.drop(offset).take(limit)
                ProviderContentSection(
                    feature = feature,
                    videos = page.mapNotNull { value -> runCatching { mvVideo(value.asObject()) }.getOrNull() },
                    nextOffset = offset + page.size,
                    hasMore = values.size > offset + page.size,
                )
            }
            "netease_top_mvs" -> {
                val root = neteaseWeApiPost(
                    "$BASE/weapi/mv/toplist",
                    """{"area":"","limit":$limit,"offset":$offset,"total":true}""",
                )
                val values = root.array("data")
                ProviderContentSection(
                    feature = feature,
                    videos = values.mapNotNull { value -> runCatching { mvVideo(value.asObject()) }.getOrNull() },
                    nextOffset = offset + values.size,
                    hasMore = root.boolean("hasMore") || values.size == limit,
                )
            }
            "netease_daily_playlists" -> {
                val root = neteaseWeApiPost("$BASE/api/discovery/recommend/resource", "{}")
                val playlists = root.array("recommend")
                val page = playlists.drop(offset).take(limit)
                ProviderContentSection(feature, playlists = page.map { it.asObject().toPlaylist() }, nextOffset = offset + page.size, hasMore = playlists.size > offset + page.size)
            }
            "netease_user_playlists", "netease_favorite_playlists" -> {
                val uid = currentUserId()
                if (uid == null) {
                    ProviderContentSection(feature, errorMessage = "无法读取网易云音乐用户信息")
                } else {
                    val playlists = userPlaylistObjects(uid).filter { item ->
                        val subscribed = item.asObject().boolean("subscribed")
                        if (feature.id == "netease_favorite_playlists") subscribed else !subscribed
                    }
                    ProviderContentSection(feature, playlists = playlists.drop(offset).take(limit).map { it.asObject().toPlaylist() }, nextOffset = offset + limit, hasMore = playlists.size > offset + limit)
                }
            }
            "netease_favorite_songs" -> {
                val uid = currentUserId()
                if (uid == null) {
                    ProviderContentSection(feature, errorMessage = "无法读取网易云音乐用户信息")
                } else {
                    val playlists = userPlaylistObjects(uid)
                    val favoritePlaylist = playlists.firstOrNull { it.string("id") == uid }
                        ?: playlists.firstOrNull { !it.boolean("subscribed") }
                    if (favoritePlaylist == null) {
                        ProviderContentSection(feature, errorMessage = "未找到我喜欢的音乐歌单")
                    } else {
                        val detail = playlistDetail(favoritePlaylist.toPlaylist(), offset, limit)
                        ProviderContentSection(
                            feature,
                            tracks = detail.tracks,
                            nextOffset = detail.tracksNextOffset,
                            hasMore = detail.tracksHasMore,
                        )
                    }
                }
            }
            "netease_cloud_songs" -> {
                cloudSongs(feature, offset, limit)
            }
            "netease_favorite_artists", "netease_favorite_albums" -> {
                val endpoint = if (feature.id == "netease_favorite_artists") "artist/sublist" else "album/sublist"
                val root = neteaseWeApiPost(
                    "$BASE/weapi/$endpoint",
                    """{"limit":$limit,"offset":$offset,"csrf_token":"${csrfToken().jsonString()}"}""",
                )
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
        ).value.let { parseNeteaseResponse(it) }.let { it.obj("result") ?: it }
    }

    private suspend fun songDetail(identifier: String): JsonObject? {
        val root = http.getText(ID, queryUrl("$BASE/api/song/detail", mapOf("ids" to "[$identifier]")), authenticatedHeaders(), cacheKey = "netease:song:$identifier", cachePolicy = ProviderCachePolicies.detail)
            .value.let { parseNeteaseResponse(it) }
        return root.array("songs").firstOrNull()?.asObject()
    }

    private suspend fun artistSongsPage(identifier: String, offset: Int, limit: Int): NeteaseSongPage {
        val request = """
            {"id":${neteaseId(identifier)},"limit":$limit,"offset":$offset,"order":"hot","work_type":1,"private_cloud":"true"}
        """.trimIndent()
        val weApiRoot = runCatching {
            neteaseWeApiPost("$BASE/weapi/v1/artist/songs", request)
        }.getOrNull()
        val weApiData = weApiRoot?.obj("data")
        val values = weApiData?.array("songs")?.takeIf { it.isNotEmpty() }
            ?: weApiRoot?.array("songs")?.takeIf { it.isNotEmpty() }
        if (values != null) {
            return NeteaseSongPage(
                values = loadSongs(values.toList()),
                rawSize = values.size,
                total = weApiData?.int("total") ?: weApiRoot?.int("total"),
                hasMore = weApiData?.boolean("more")
                    ?: weApiRoot?.boolean("more")
                    ?: values.size == limit,
            )
        }
        val fallback = http.getText(
            ID,
            "$BASE/api/artist/$identifier",
            authenticatedHeaders(),
            cacheKey = "netease:artist:$identifier",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { parseNeteaseResponse(it) }
        val fallbackValues = fallback.array("hotSongs")
        return NeteaseSongPage(
            values = loadSongs(fallbackValues.toList()),
            rawSize = fallbackValues.size,
            total = fallbackValues.size,
            hasMore = false,
        )
    }

    private suspend fun artistAlbumsPage(identifier: String, offset: Int, limit: Int): NeteaseAlbumPage {
        val root = http.getText(
            ID,
            queryUrl("$BASE/api/artist/albums/$identifier", mapOf("offset" to offset.toString(), "limit" to limit.toString())),
            authenticatedHeaders(),
            cacheKey = "netease:artist-albums:$identifier:$offset:$limit",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { parseNeteaseResponse(it) }
        val values = root.array("hotAlbums").mapNotNull { value ->
            runCatching { album(value.asObject()) }.getOrNull()
        }
        val data = root.obj("artist")
        val total = root.int("total") ?: data?.int("albumSize")
        return NeteaseAlbumPage(
            values = values,
            rawSize = values.size,
            total = total,
            hasMore = root.boolean("more") || total?.let { offset + values.size < it } == true,
        )
    }

    private suspend fun albumRoot(identifier: String): JsonObject {
        val pathRoot = runCatching {
            http.getText(
                ID,
                "$BASE/api/album/$identifier",
                authenticatedHeaders(),
                cacheKey = "netease:album:$identifier",
                cachePolicy = ProviderCachePolicies.detail,
            ).value.let { parseNeteaseResponse(it) }
        }.getOrNull()
        pathRoot?.let { root ->
            if (root.obj("album") != null || root.array("songs").isNotEmpty()) return root
        }
        return http.getText(
            ID,
            queryUrl("$BASE/api/album", mapOf("id" to identifier)),
            authenticatedHeaders(),
            cacheKey = "netease:album:$identifier",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { parseNeteaseResponse(it) }
    }

    private suspend fun cloudSongs(feature: ProviderFeature, offset: Int, limit: Int): ProviderContentSection {
        val root = neteaseWeApiPost(
            "$BASE/weapi/v1/cloud/get",
            """{"limit":$limit,"offset":$offset}""",
        )
        val entries = root.array("data")
        val privateIds = entries.mapNotNull { entry ->
            val value = entry.asObject()
            value.string("id").takeIf { it.isNotBlank() && it == value.string("s_id") }
        }
        val privateSongs = cloudSongDetails(privateIds)
        val values = entries.mapNotNull { entry ->
            val value = entry.asObject()
            val identifier = value.string("id")
            privateSongs[identifier] ?: value.obj("simpleSong") ?: value
        }
        val songs = loadSongs(values)
        val total = root.int("count")
        return ProviderContentSection(
            feature = feature,
            tracks = songs,
            nextOffset = offset + entries.size,
            hasMore = total?.let { offset + entries.size < it } ?: entries.size == limit,
        )
    }

    private suspend fun cloudSongDetails(identifiers: List<String>): Map<String, JsonObject> {
        if (identifiers.isEmpty()) return emptyMap()
        val root = neteaseWeApiPost(
            "$BASE/weapi/v1/cloud/get/byids",
            """{"songIds":[${identifiers.joinToString(",") { "\"$it\"" }}]}""",
        )
        return root.array("data").ifEmpty { root.array("songs") }
            .mapNotNull { value ->
                val song = value.asObject().obj("simpleSong") ?: value.asObject()
                song.string("id").takeIf { it.isNotBlank() }?.let { it to song }
            }
            .toMap()
    }

    private suspend fun loadSongs(values: Iterable<JsonElement>): List<org.feeluown.mobile.MusicTrack> {
        val objects = values.mapNotNull { runCatching { it.asObject() }.getOrNull() }
        val detailIds = objects.filter(::needsSongDetail).mapNotNull { it.string("id").takeIf(String::isNotBlank) }
        val details = loadSongDetails(detailIds)
        return objects.mapNotNull { value ->
            val identifier = value.string("id").ifBlank { value.string("songId") }
            runCatching { song(details[identifier] ?: value) }
                .getOrNull()
                ?.takeIf { it.id.substringAfterLast(':').isNotBlank() }
        }
    }

    private suspend fun loadSongDetails(identifiers: List<String>): Map<String, JsonObject> {
        val ids = identifiers.distinct().filter { it.toLongOrNull() != null }
        if (ids.isEmpty()) return emptyMap()
        return ids.chunked(200).flatMap { batch ->
            val idList = batch.joinToString(",")
            val weApiRoot = runCatching {
                neteaseWeApiPost(
                    "$BASE/weapi/v3/song/detail",
                    """{"c":[${batch.joinToString(",") { """{"id":$it}""" }}],"ids":[$idList]}""",
                )
            }.getOrNull()?.takeIf { it.array("songs").isNotEmpty() }
            val root = weApiRoot ?: http.getText(
                ID,
                queryUrl("$BASE/api/song/detail", mapOf("ids" to "[$idList]")),
                neteaseAuthenticatedHeaders(),
                cacheKey = "netease:songs:$idList",
                cachePolicy = ProviderCachePolicies.detail,
            ).value.let { parseNeteaseResponse(it) }
            root.array("songs").mapNotNull { value ->
                val song = value.asObject()
                song.string("id").takeIf { it.isNotBlank() }?.let { it to song }
            }
        }.toMap()
    }

    private fun needsSongDetail(value: JsonObject): Boolean {
        val artists = value.array("ar").takeIf { it.isNotEmpty() } ?: value.array("artists")
        val album = value.obj("al") ?: value.obj("album")
        return artists.isEmpty() || album?.string("name").orEmpty().isBlank()
    }

    private fun neteaseId(identifier: String): String = identifier.toLongOrNull()?.toString() ?: "0"

    private data class NeteaseSongPage(
        val values: List<org.feeluown.mobile.MusicTrack>,
        val rawSize: Int,
        val total: Int?,
        val hasMore: Boolean,
    )

    private data class NeteaseAlbumPage(
        val values: List<ProviderMediaItem>,
        val rawSize: Int,
        val total: Int?,
        val hasMore: Boolean,
    )

    private suspend fun lyric(identifier: String): String? = runCatching {
        val root = http.getText(
            ID,
            queryUrl(
                "$BASE/api/song/lyric",
                mapOf(
                    "id" to identifier,
                    "lv" to "-1",
                    "kv" to "-1",
                    "tv" to "-1",
                    "yv" to "-1",
                ),
            ),
            neteaseAuthenticatedHeaders(),
            cacheKey = "netease:lyric-yrc-tr:$identifier",
            cachePolicy = ProviderCachePolicies.lyric,
        ).value.let { parseNeteaseResponse(it) }
        val yrc = root.obj("yrc")?.stringOrNull("lyric")
        val lrc = root.obj("lrc")?.stringOrNull("lyric")
        val main = yrc ?: lrc ?: return@runCatching null
        val translation = when {
            yrc != null -> root.obj("ytlrc")?.stringOrNull("lyric")
                ?: root.obj("tlyric")?.stringOrNull("lyric")
            else -> root.obj("tlyric")?.stringOrNull("lyric")
        }
        org.feeluown.mobile.composeLyricsWithTranslation(main, translation)
    }.getOrNull()

    private suspend fun currentUserId(): String? = runCatching { requestCurrentUserId() }.getOrNull()

    private suspend fun requestCurrentUserId(): String? {
        val root = runCatching { neteaseWeApiPost("$BASE/api/user/level", "{}") }.getOrNull() ?: return null
        if (root.int("code") != 200) return null
        val data = root.obj("data") ?: return null
        return data.long("userId")?.toString() ?: data.stringOrNull("userId")
    }

    private suspend fun remoteAuthState(): ProviderAuthState {
        val userId = requestCurrentUserId() ?: return loggedOutState()
        val userName = runCatching {
            val numericUserId = userId.toLongOrNull() ?: return@runCatching null
            val root = neteaseWeApiPost(
                "$BASE/weapi/share/userprofile/info",
                "{\"userId\":$numericUserId}",
            )
            root.stringOrNull("nickname")
                ?: root.obj("profile")?.stringOrNull("nickname")
                ?: root.obj("data")?.stringOrNull("nickname")
        }.getOrNull()
        return ProviderAuthState(
            providerId = ID,
            providerName = NAME,
            isLoggedIn = true,
            userName = userName,
        )
    }

    private suspend fun neteaseWeApiPost(url: String, json: String): JsonObject {
        val payload = NeteaseWeApi.encrypt(json)
        return http.postForm(
            providerId = ID,
            url = url,
            form = Parameters.build {
                append("params", payload.params)
                append("encSecKey", payload.encSecKey)
            },
            headers = neteaseAuthenticatedHeaders(mapOf("Referer" to "https://music.163.com/")),
            kind = ProviderRequestKind.Auth,
            cacheKey = null,
        ).value.let { parseNeteaseResponse(it) }
    }

    private suspend fun csrfToken(): String = currentCredentials()?.cookies?.get("__csrf").orEmpty()

    private suspend fun userPlaylistObjects(uid: String): List<JsonObject> = http.getText(
        ID,
        queryUrl("$BASE/api/user/playlist/", mapOf("uid" to uid, "limit" to "100")),
        neteaseAuthenticatedHeaders(),
        cacheKey = null,
    ).value.let { parseNeteaseResponse(it) }.array("playlist").map { it.asObject() }

    private fun parseNeteaseResponse(raw: String): JsonObject {
        val root = providerJson.parseToJsonElement(raw).asObject()
        val code = root.int("code")
        if (code != null && code != 200) {
            val message = root.string("message").ifBlank { root.string("msg") }
            error("网易云音乐接口失败：code=$code${message.takeIf(String::isNotBlank)?.let { ", $it" }.orEmpty()}")
        }
        return root
    }

    private suspend fun neteaseAuthenticatedHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val headers = authenticatedHeaders(extra)
        val cookies = headers["Cookie"].orEmpty()
            .split(';')
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { cookie ->
                val name = cookie.substringBefore('=')
                name.equals("appver", ignoreCase = true) || name.equals("os", ignoreCase = true)
            }
            .toMutableList()
        cookies += "appver=7.2.24"
        cookies += "os=android"
        return headers + ("Cookie" to cookies.joinToString("; "))
    }

    private fun loggedOutState(): ProviderAuthState = ProviderAuthState(
        providerId = ID,
        providerName = NAME,
        isLoggedIn = false,
    )

    private fun credentialsArePresent(credentials: org.feeluown.mobile.provider.core.ProviderCredentials): Boolean =
        credentials.cookies.isNotEmpty() ||
            !credentials.cookieHeader.isNullOrBlank() ||
            !credentials.authorization.isNullOrBlank()

    private fun song(value: kotlinx.serialization.json.JsonElement): org.feeluown.mobile.MusicTrack {
        val item = value.asObject()
        val artists = item.array("ar").takeIf { it.isNotEmpty() } ?: item.array("artists")
        val artist = artists.firstOrNull()?.asObject()
        val album = item.obj("al") ?: item.obj("album")
        val identifier = item.string("id").ifBlank { item.string("songId") }
        return track(
            identifier = identifier,
            title = item.string("name").ifBlank { item.string("songName") }.ifBlank { item.string("title") },
            artists = artists.map { it.asObject().string("name") }.filter { it.isNotBlank() }.joinToString(" / ")
                .ifBlank { item.string("artistName") },
            album = album?.string("name").orEmpty().ifBlank { item.string("albumName") },
            coverUrl = album?.stringOrNull("picUrl") ?: item.stringOrNull("picUrl"),
            durationMs = item.long("dt") ?: item.long("duration") ?: item.long("interval")?.times(1_000),
            artistItemId = artist?.stringOrNull("id")?.let { "artist:$ID:$it" },
            albumItemId = album?.stringOrNull("id")?.let { "album:$ID:$it" },
            providerUrl = "https://music.163.com/#/song?id=$identifier",
        )
    }

    private fun JsonObject.toPlaylist(): ProviderPlaylist = playlist(
        identifier = string("id"),
        title = string("name"),
        coverUrl = stringOrNull("coverImgUrl") ?: stringOrNull("picUrl"),
        description = string("description").ifBlank { obj("creator")?.stringOrNull("nickname") ?: string("creatorName") },
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

    private fun mvVideo(value: JsonObject): ProviderVideo {
        val identifier = value.string("id").ifBlank { value.string("mvId") }
        val artists = value.array("artists")
            .map { it.asObject().string("name") }
            .filter(String::isNotBlank)
            .joinToString(" / ")
            .ifBlank { value.string("artistName") }
        return video(
            identifier = identifier,
            title = value.string("name").ifBlank { value.string("title") },
            artists = artists,
            coverUrl = value.stringOrNull("cover")
                ?: value.stringOrNull("coverUrl")
                ?: value.stringOrNull("picUrl"),
            durationMs = value.long("duration") ?: value.long("durationMs"),
            providerUrl = "https://music.163.com/#/mv?id=$identifier",
        )
    }

    private suspend fun mutatePlaylist(action: String, playlist: ProviderPlaylist, track: org.feeluown.mobile.MusicTrack): ProviderMutationResult {
        val (_, playlistId) = splitResourceId(playlist.id, "playlist")
        val (_, trackId) = splitResourceId(track.providerId ?: track.id)
        val root = http.postForm(
            ID,
            "$BASE/api/playlist/manipulate/tracks",
            Parameters.build {
                append("pid", playlistId)
                append("trackIds", "[\"$trackId\"]")
                append("tracks", trackId)
                append("op", action)
            },
            neteaseAuthenticatedHeaders(),
            ProviderRequestKind.Mutation,
        )
            .value.let { providerJson.parseToJsonElement(it).asObject() }
        return mutation(root, if (action == "add") "已添加到：${playlist.title}" else "已从歌单移除：${track.title}")
    }

    private fun mutation(root: kotlinx.serialization.json.JsonObject, successMessage: String): ProviderMutationResult =
        ProviderMutationResult(root.int("code") == 200 || root.boolean("success"), if (root.int("code") == 200) successMessage else root.string("message").ifBlank { "操作失败" })

    private fun String.toNeteaseBitrate(): Int = when (this) {
        AudioQualityPolicy.Highest.policy -> 999_000
        AudioQualityPolicy.High.policy -> 320_000
        AudioQualityPolicy.Standard.policy -> 192_000
        AudioQualityPolicy.Low.policy -> 128_000
        else -> 320_000
    }

    private fun String.jsonString(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private fun rawIdentifier(value: String): String = splitResourceId(value).second.ifBlank { value.substringAfterLast(':') }

    private companion object {
        const val ID = "netease"
        const val NAME = "网易云音乐"
        const val BASE = "https://music.163.com"
        val INFO = ProviderInfo(
            providerId = ID,
            providerName = NAME,
            loginConfig = org.feeluown.mobile.ProviderLoginConfig("https://music.163.com", listOf(listOf("MUSIC_U"))),
            supportedLoginModes = setOf(org.feeluown.mobile.ProviderLoginMode.WebView),
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
            ProviderFeature("netease_daily_playlists", ID, NAME, "每日推荐歌单", ProviderFeatureCategory.Recommend, ProviderContentType.Playlists, true),
            ProviderFeature("netease_radio", ID, NAME, "私人 FM", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, true),
            ProviderFeature("netease_recommended_new_songs", ID, NAME, "推荐新歌", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, false),
            ProviderFeature("netease_recommended_mvs", ID, NAME, "推荐 MV", ProviderFeatureCategory.Recommend, ProviderContentType.Videos, false),
            ProviderFeature("netease_toplists", ID, NAME, "排行榜", ProviderFeatureCategory.Music, ProviderContentType.Playlists, false),
            ProviderFeature("netease_new_songs", ID, NAME, "新歌速递", ProviderFeatureCategory.Music, ProviderContentType.Songs, false),
            ProviderFeature("netease_new_albums", ID, NAME, "新碟上架", ProviderFeatureCategory.Music, ProviderContentType.Albums, false),
            ProviderFeature("netease_top_artists", ID, NAME, "热门歌手", ProviderFeatureCategory.Music, ProviderContentType.Artists, false),
            ProviderFeature("netease_highquality_playlists", ID, NAME, "精品歌单", ProviderFeatureCategory.Music, ProviderContentType.Playlists, false),
            ProviderFeature("netease_top_mvs", ID, NAME, "MV 排行", ProviderFeatureCategory.Music, ProviderContentType.Videos, false),
            ProviderFeature("netease_user_playlists", ID, NAME, "我的歌单", ProviderFeatureCategory.MinePlaylists, ProviderContentType.Playlists, true),
            ProviderFeature("netease_favorite_songs", ID, NAME, "收藏歌曲", ProviderFeatureCategory.Mine, ProviderContentType.Songs, true),
            ProviderFeature("netease_cloud_songs", ID, NAME, "云盘歌曲", ProviderFeatureCategory.Mine, ProviderContentType.Songs, true),
            ProviderFeature("netease_favorite_playlists", ID, NAME, "收藏歌单", ProviderFeatureCategory.MineFavoritePlaylists, ProviderContentType.Playlists, true),
            ProviderFeature("netease_favorite_artists", ID, NAME, "收藏歌手", ProviderFeatureCategory.Mine, ProviderContentType.Artists, true),
            ProviderFeature("netease_favorite_albums", ID, NAME, "收藏专辑", ProviderFeatureCategory.Mine, ProviderContentType.Albums, true),
        )
    }
}
