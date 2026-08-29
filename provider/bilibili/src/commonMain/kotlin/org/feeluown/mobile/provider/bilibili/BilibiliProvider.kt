package org.feeluown.mobile.provider.bilibili

import io.ktor.http.Parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.feeluown.mobile.AudioQualityPolicy
import org.feeluown.mobile.PlaybackPart
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.ProviderAuthState
import org.feeluown.mobile.ProviderComment
import org.feeluown.mobile.ProviderContentSection
import org.feeluown.mobile.ProviderFeature
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
import org.feeluown.mobile.provider.core.network.ProviderRequestKind
import org.feeluown.mobile.provider.core.network.currentTimeMillis

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
            ).copy(providerTags = parseSearchTags(item.stringOrNull("tag")))
        }
        return ProviderSearchResults(tracks = tracks)
    }

    override suspend fun trackDetail(identifier: String): org.feeluown.mobile.MusicTrack? {
        val (bvid, page) = parsePaged(rawIdentifier(identifier))
        val root = videoInfo(bvid)
        val data = root.obj("data") ?: return null
        val owner = data.obj("owner")
        val pages = data.array("pages")
        if (page != null && page !in 1..pages.size) return null
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

    override suspend fun lyricsSearchKeyword(track: org.feeluown.mobile.MusicTrack): String? {
        val fallback = track.title.trim().takeIf { it.isNotBlank() }
        val (bvid, page) = parsePaged(rawIdentifier(track.providerId ?: track.id))
        return runCatching {
            val info = videoInfo(bvid).obj("data") ?: return@runCatching fallback
            val pages = info.array("pages")
            if (page != null && page !in 1..pages.size) return@runCatching fallback
            val pageInfo = pages.getOrNull((page ?: 1) - 1)?.asObject()
            val cid = pageInfo?.long("cid") ?: info.long("cid") ?: return@runCatching fallback
            playerInfoData(bvid, cid)
                ?.obj("bgm_info")
                ?.stringOrNull("music_title")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: fallback
        }.getOrNull() ?: fallback
    }

    override suspend fun resolve(track: org.feeluown.mobile.MusicTrack, qualityPolicy: String): PlaybackPayload? {
        val (bvid, page) = parsePaged(rawIdentifier(track.providerId ?: track.id))
        val info = videoInfo(bvid).obj("data") ?: return null
        val pages = info.array("pages")
        if (page != null && page !in 1..pages.size) return null
        val pageNumber = page ?: 1
        val pageInfo = pages.getOrNull(pageNumber - 1)?.asObject()
        val cid = pageInfo?.long("cid") ?: info.long("cid") ?: return null
        val data = playUrlData(bvid, cid) ?: return null
        val dash = data.obj("dash")
        val audio = selectAudio(
            qualityPolicy = qualityPolicy,
            audio = dash?.array("audio").orEmpty().mapNotNull { it.asObject().toAudioStream() },
            flac = dash?.obj("flac")?.obj("audio")?.toAudioStream(isFlac = true),
        )
        val durl = data.array("durl").firstOrNull()?.asObject()
        val url = audio?.stream?.url
            ?: durl?.stringOrNull("url")
            ?: return null
        val parts = if (pages.size > 1) {
            pages.mapIndexedNotNull { index, element ->
                val item = element.asObject()
                item.long("cid")?.let {
                    PlaybackPart(
                        id = "bilibili:paged_${bvid}__${index + 1}",
                        title = item.string("part"),
                        durationMs = item.long("duration")?.times(1_000),
                    )
                }
            }
        } else {
            emptyList()
        }
        return PlaybackPayload(
            url = url,
            title = track.title,
            artists = track.artists,
            album = track.album,
            source = ID,
            headers = mediaHeaders(),
            coverUrl = track.coverUrl,
            durationMs = (audio?.stream?.durationMs ?: durl?.long("length")) ?: track.durationMs,
            audioQuality = audio?.quality,
            providerName = NAME,
            parts = parts,
            currentPartIndex = if (parts.isEmpty()) -1 else pageNumber - 1,
        )
    }

    override suspend fun authState(): ProviderAuthState {
        val credentials = currentCredentials()
        if (credentials == null) return authState(null)
        val root = nav()
        val data = root?.obj("data")
        return ProviderAuthState(
            providerId = ID,
            providerName = NAME,
            isLoggedIn = root?.let { data?.boolean("isLogin") == true } ?: credentialsArePresent(credentials),
            userName = data?.stringOrNull("uname"),
        )
    }

    override suspend fun similarTracks(track: org.feeluown.mobile.MusicTrack): List<org.feeluown.mobile.MusicTrack> {
        val (bvid, page) = parsePaged(rawIdentifier(track.providerId ?: track.id))
        if (page != null) return emptyList()
        val root = http.getText(ID, queryUrl("$BASE/x/web-interface/archive/related", mapOf("bvid" to bvid)), headers(), cacheKey = "bilibili:related:$bvid", cachePolicy = ProviderCachePolicies.recommendation)
            .value.let { providerJson.parseToJsonElement(it).asObject() }
        return root.array("data").mapNotNull { element -> searchItemToTrack(element.asObject()) }
    }

    override suspend fun hotComments(track: org.feeluown.mobile.MusicTrack): List<ProviderComment> {
        val (bvid, _) = parsePaged(rawIdentifier(track.providerId ?: track.id))
        val aid = videoInfo(bvid).obj("data")?.long("aid") ?: return emptyList()
        val root = http.getText(ID, queryUrl("$BASE/x/v2/reply", mapOf("type" to "1", "oid" to aid.toString(), "pn" to "1", "ps" to "10", "sort" to "2")), headers(), cacheKey = "bilibili:comments:$bvid", cachePolicy = ProviderCachePolicies.detail)
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
        val (bvid, page) = parsePaged(rawIdentifier(track.providerId ?: track.id))
        if (page != null) return null
        return video(bvid, track.title, track.artists, track.coverUrl, track.durationMs, "https://www.bilibili.com/video/$bvid")
    }

    override suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload {
        val (bvid, page) = parsePaged(splitResourceId(video.id, "video").second)
        val info = videoInfo(bvid).obj("data") ?: return VideoPlaybackPayload(video = video)
        val pages = info.array("pages")
        if (page != null && page !in 1..pages.size) return VideoPlaybackPayload(video = video)
        val pageNumber = page ?: 1
        val pageInfo = pages.getOrNull(pageNumber - 1)?.asObject()
        val cid = pageInfo?.long("cid") ?: info.long("cid") ?: return VideoPlaybackPayload(video = video)
        val data = playUrlData(bvid, cid) ?: return VideoPlaybackPayload(video = video)
        val dash = data.obj("dash")
        val videoStream = dash?.array("video").orEmpty()
            .mapNotNull { it.asObject().toVideoStream() }
            .maxByOrNull { it.bandwidth }
        val audio = selectAudio(
            qualityPolicy = AudioQualityPolicy.High.policy,
            audio = dash?.array("audio").orEmpty().mapNotNull { it.asObject().toAudioStream() },
            flac = dash?.obj("flac")?.obj("audio")?.toAudioStream(isFlac = true),
        )
        if (videoStream != null && audio != null) {
            return VideoPlaybackPayload(
                video = video,
                videoUrl = videoStream.url,
                audioUrl = audio.stream.url,
                headers = mediaHeaders(),
                quality = "video",
            )
        }
        val durl = data.array("durl").firstOrNull()?.asObject()?.stringOrNull("url")
            ?: return VideoPlaybackPayload(video = video)
        return VideoPlaybackPayload(
            video = video,
            url = durl,
            videoUrl = durl,
            headers = mediaHeaders(),
            quality = "video",
        )
    }

    override suspend fun playlistOperationTargets(track: org.feeluown.mobile.MusicTrack): List<ProviderPlaylist> {
        if (!authState().isLoggedIn) return emptyList()
        val feature = FEATURES.first { it.id == "bilibili_user_playlists" }
        return loadFavoriteFolders(feature, 0, MAX_FAVORITE_PAGE_SIZE).playlists
    }

    override suspend fun addTrackToPlaylist(
        playlist: ProviderPlaylist,
        track: org.feeluown.mobile.MusicTrack,
    ): ProviderMutationResult = mutateFavoriteResource(playlist, track, add = true)

    override suspend fun removeTrackFromPlaylist(
        playlist: ProviderPlaylist,
        track: org.feeluown.mobile.MusicTrack,
    ): ProviderMutationResult = mutateFavoriteResource(playlist, track, add = false)

    private suspend fun mutateFavoriteResource(
        playlist: ProviderPlaylist,
        track: org.feeluown.mobile.MusicTrack,
        add: Boolean,
    ): ProviderMutationResult {
        val (bvid, page) = parsePaged(rawIdentifier(track.providerId ?: track.id))
        if (page != null) return ProviderMutationResult(false, "哔哩哔哩不支持对分 P 视频执行收藏操作")
        val aid = videoInfo(bvid).obj("data")?.long("aid")
            ?: return ProviderMutationResult(false, "无法读取哔哩哔哩视频编号")
        val csrf = csrfToken()
            ?: return ProviderMutationResult(false, "缺少哔哩哔哩 csrf Cookie")
        val (_, mediaId) = splitResourceId(playlist.id, "playlist")
        if (mediaId.startsWith(COLLECTION_PREFIX)) {
            return ProviderMutationResult(false, "哔哩哔哩合集不支持收藏夹增删操作")
        }
        val field = if (add) "add_media_ids" else "del_media_ids"
        val root = http.postForm(
            providerId = ID,
            url = "$BASE/x/v3/fav/resource/deal",
            form = Parameters.build {
                append("rid", aid.toString())
                append("type", "2")
                append(field, mediaId)
                append("csrf", csrf)
            },
            headers = headers(),
            kind = ProviderRequestKind.Mutation,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val success = root.int("code") == 0
        return ProviderMutationResult(
            success = success,
            message = if (success) "操作成功" else root.string("message").ifBlank { "哔哩哔哩收藏操作失败" },
        )
    }

    override suspend fun loadFeature(feature: ProviderFeature, offset: Int, limit: Int): ProviderContentSection {
        if (feature.requiresLogin && !authState().isLoggedIn) {
            return ProviderContentSection(feature, isLoginRequired = true)
        }
        if (feature.id == "bilibili_user_playlists" || feature.id == "bilibili_favorite_playlists") {
            return loadFavoriteFolders(feature, offset, limit)
        }
        if (feature.id != "bilibili_popular_videos") return ProviderContentSection(feature)
        val root = http.getText(ID, queryUrl("$BASE/x/web-interface/popular", mapOf("ps" to limit.toString(), "pn" to (offset / limit + 1).toString())), headers(), cacheKey = "bilibili:popular:${offset / limit}:$limit", cachePolicy = ProviderCachePolicies.recommendation)
            .value.let { providerJson.parseToJsonElement(it).asObject() }
        val tracks = root.obj("data")?.array("list").orEmpty().mapNotNull { searchItemToTrack(it.asObject()) }
        return ProviderContentSection(feature, tracks = tracks, nextOffset = offset + tracks.size, hasMore = tracks.size == limit)
    }

    override suspend fun playlistDetail(playlist: ProviderPlaylist, offset: Int, limit: Int): ProviderPlaylistDetail {
        val (_, encodedId) = splitResourceId(playlist.id, "playlist")
        val isCollection = encodedId.startsWith(COLLECTION_PREFIX)
        val resourceId = encodedId.removePrefix(COLLECTION_PREFIX)
        val pageSize = limit.coerceIn(1, MAX_FAVORITE_PAGE_SIZE)
        val pageNumber = offset / pageSize + 1
        val root = http.getText(
            providerId = ID,
            url = if (isCollection) {
                queryUrl(
                    "$BASE/x/space/fav/season/list",
                    mapOf(
                        "season_id" to resourceId,
                        "pn" to pageNumber.toString(),
                        "ps" to pageSize.toString(),
                        "keyword" to "",
                        "order" to "mtime",
                        "type" to "0",
                        "tid" to "0",
                        "platform" to "web",
                    ),
                )
            } else {
                queryUrl(
                    "$BASE/x/v3/fav/resource/list",
                    mapOf(
                        "media_id" to resourceId,
                        "pn" to pageNumber.toString(),
                        "ps" to pageSize.toString(),
                        "keyword" to "",
                        "order" to "mtime",
                        "type" to "0",
                        "tid" to "0",
                        "platform" to "web",
                    ),
                )
            },
            headers = headers(),
            cacheKey = "bilibili:${if (isCollection) "collection" else "favorite"}:$resourceId:$pageNumber:$pageSize",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val data = root.obj("data") ?: return ProviderPlaylistDetail(playlist)
        val actualPlaylist = if (isCollection) {
            playlist
        } else {
            data.obj("info")?.toFavoritePlaylist() ?: playlist
        }
        val medias = data.array("medias")
        val tracks = medias.mapNotNull { searchItemToTrack(it.asObject()) }
        val total = actualPlaylist.trackCount ?: playlist.trackCount
        val nextOffset = offset + medias.size
        return ProviderPlaylistDetail(
            playlist = actualPlaylist,
            tracks = tracks,
            tracksNextOffset = nextOffset,
            tracksHasMore = medias.isNotEmpty() && (data.boolean("has_more") || (total != null && nextOffset < total)),
        )
    }

    private suspend fun videoInfo(bvid: String) = http.getText(
        ID,
        queryUrl("$BASE/x/web-interface/view", mapOf("bvid" to bvid)),
        headers(),
        cacheKey = "bilibili:view:$bvid",
        cachePolicy = ProviderCachePolicies.detail,
    ).value.let { providerJson.parseToJsonElement(it).asObject() }

    private suspend fun playerInfoData(
        bvid: String,
        cid: Long,
    ): kotlinx.serialization.json.JsonObject? {
        val response = http.getText(
            ID,
            queryUrl(
                "$BASE/x/player/wbi/v2",
                mapOf(
                    "bvid" to bvid,
                    "cid" to cid.toString(),
                ),
            ),
            headers(),
            cacheKey = "bilibili:player-info:$bvid:$cid",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        if (response.int("code") != null && response.int("code") != 0) return null
        return response.obj("data")
    }

    private suspend fun playUrlData(
        bvid: String,
        cid: Long,
    ): kotlinx.serialization.json.JsonObject? {
        val response = http.getText(
            ID,
            signedQueryUrl(
                "$BASE/x/player/playurl",
                mapOf(
                    "bvid" to bvid,
                    "cid" to cid.toString(),
                    "fnval" to "16",
                ),
            ),
            headers(),
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        return response.obj("data")?.takeIf { response.int("code") == null || response.int("code") == 0 }
    }

    private suspend fun loadFavoriteFolders(feature: ProviderFeature, offset: Int, limit: Int): ProviderContentSection {
        val mid = currentUserMid()
            ?: return ProviderContentSection(feature, errorMessage = "无法读取哔哩哔哩用户信息")
        val collected = feature.id == "bilibili_favorite_playlists"
        val pageSize = limit.coerceAtLeast(1)
        val root = http.getText(
            providerId = ID,
            url = queryUrl(
                if (collected) "$BASE/x/v3/fav/folder/collected/list" else "$BASE/x/v3/fav/folder/created/list-all",
                buildMap {
                    put("up_mid", mid)
                    if (collected) {
                        put("pn", (offset / pageSize + 1).toString())
                        put("ps", pageSize.toString())
                        put("platform", "web")
                    } else {
                        put("type", "2")
                    }
                },
            ),
            headers = headers(),
            cacheKey = "bilibili:${if (collected) "collected" else "created"}:folders:$mid:${offset / pageSize}:$pageSize",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val code = root.int("code")
        if (code != null && code != 0) {
            return if (code == -101) {
                ProviderContentSection(feature, isLoginRequired = true)
            } else {
                ProviderContentSection(feature, errorMessage = root.string("message").ifBlank { "无法读取哔哩哔哩收藏夹" })
            }
        }
        val data = root.obj("data")
        val folders = data?.array("list").orEmpty().mapNotNull {
            it.asObject().toFavoritePlaylist(isCollection = collected)
        }
        val page = if (collected) folders else folders.drop(offset).take(pageSize)
        val resolvedPage = if (collected) page else resolveCreatedPlaylistCovers(page)
        val total = data?.int("count") ?: folders.size
        val nextOffset = offset + page.size
        return ProviderContentSection(
            feature = feature,
            playlists = resolvedPage,
            nextOffset = nextOffset,
            hasMore = nextOffset < total,
        )
    }

    private suspend fun resolveCreatedPlaylistCovers(playlists: List<ProviderPlaylist>): List<ProviderPlaylist> {
        val resolved = ArrayList<ProviderPlaylist>(playlists.size)
        for (playlist in playlists) {
            val coverUrl = playlist.coverUrl?.takeIf { it.isNotBlank() }
                ?: previewCreatedPlaylistCover(playlist)
            resolved += if (coverUrl.isNullOrBlank()) playlist else playlist.copy(coverUrl = coverUrl)
        }
        return resolved
    }

    private suspend fun previewCreatedPlaylistCover(playlist: ProviderPlaylist): String? {
        val (_, encodedId) = splitResourceId(playlist.id, "playlist")
        val resourceId = encodedId.removePrefix(COLLECTION_PREFIX)
        return runCatching {
            http.getText(
                providerId = ID,
                url = queryUrl(
                    "$BASE/x/v3/fav/resource/list",
                    mapOf(
                        "media_id" to resourceId,
                        "pn" to "1",
                        "ps" to "1",
                        "keyword" to "",
                        "order" to "mtime",
                        "type" to "0",
                        "platform" to "web",
                    ),
                ),
                headers = headers(),
                cacheKey = "bilibili:favorite-preview:$resourceId",
                cachePolicy = ProviderCachePolicies.detail,
            ).value
                .let { providerJson.parseToJsonElement(it).asObject() }
                .obj("data")
                ?.array("medias")
                ?.firstOrNull()
                ?.asObject()
                ?.let { item ->
                    normalizeCover(item.stringOrNull("cover") ?: item.stringOrNull("pic"))
                }
        }.getOrNull()
    }

    private suspend fun currentUserMid(): String? = nav()?.obj("data")?.stringOrNull("mid")

    private suspend fun csrfToken(): String? = csrfCookie(currentCredentials())

    private suspend fun nav(): kotlinx.serialization.json.JsonObject? = runCatching {
        http.getText(
            providerId = ID,
            url = "$BASE/x/web-interface/nav",
            headers = headers(),
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
    }.getOrNull()

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
        val bvid = item.stringOrNull("bvid") ?: item.stringOrNull("bv_id") ?: return null
        return track(
            identifier = bvid,
            title = stripHtml(item.string("title")),
            artists = item.string("author").ifBlank { item.obj("owner")?.string("name").orEmpty() }.ifBlank { item.obj("upper")?.string("name").orEmpty() },
            album = "",
            coverUrl = normalizeCover(item.stringOrNull("pic") ?: item.stringOrNull("cover")),
            durationMs = item.long("duration")?.times(1_000) ?: parseDurationMs(item.string("duration")),
            providerUrl = "https://www.bilibili.com/video/$bvid",
        ).copy(providerTags = parseSearchTags(item.stringOrNull("tag")))
    }

    private fun kotlinx.serialization.json.JsonObject.toFavoritePlaylist(
        isCollection: Boolean = false,
    ): ProviderPlaylist? {
        val rawIdentifier = stringOrNull("id") ?: stringOrNull("media_id") ?: return null
        val identifier = if (isCollection) "$COLLECTION_PREFIX$rawIdentifier" else rawIdentifier
        val title = stringOrNull("title") ?: return null
        val ownerMid = stringOrNull("mid") ?: obj("upper")?.stringOrNull("mid")
        return playlist(
            identifier = identifier,
            title = title,
            coverUrl = normalizeCover(stringOrNull("cover")),
            description = string("intro"),
            playCount = obj("cnt_info")?.long("play"),
            trackCount = int("media_count"),
            providerUrl = ownerMid?.let { "https://space.bilibili.com/$it/favlist?fid=$rawIdentifier" },
        )
    }

    private suspend fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        putAll(authenticatedHeaders(mapOf("Referer" to "https://www.bilibili.com/", "Accept" to "application/json, text/plain, */*")))
        putAll(extra)
    }

    private suspend fun mediaHeaders(): Map<String, String> = authenticatedHeaders(
        mapOf(
            "Referer" to "https://www.bilibili.com/",
            "User-Agent" to BILIBILI_MEDIA_USER_AGENT,
        ),
    )

    private companion object {
        const val ID = BilibiliProviderDefinition.ID
        const val NAME = BilibiliProviderDefinition.NAME
        const val BASE = BilibiliProviderDefinition.BASE
        const val COLLECTION_PREFIX = BilibiliProviderDefinition.COLLECTION_PREFIX
        const val MAX_FAVORITE_PAGE_SIZE = BilibiliProviderDefinition.MAX_FAVORITE_PAGE_SIZE
        const val BILIBILI_MEDIA_USER_AGENT = BilibiliProviderDefinition.MEDIA_USER_AGENT
        val INFO get() = BilibiliProviderDefinition.info
        val CAPABILITIES get() = BilibiliProviderDefinition.capabilities
        val FEATURES get() = BilibiliProviderDefinition.features
    }
}
