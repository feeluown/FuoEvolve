package org.feeluown.mobile.provider.bilibili

import io.ktor.http.Parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.ProviderContentSection
import org.feeluown.mobile.ProviderContentType
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderFeatureCategory
import org.feeluown.mobile.ProviderFeatureFilterCodec
import org.feeluown.mobile.ProviderFeatureFilterOption
import org.feeluown.mobile.ProviderFeatureFilterSpec
import org.feeluown.mobile.ProviderMediaItem
import org.feeluown.mobile.ProviderMediaItemDetail
import org.feeluown.mobile.ProviderMediaItemType
import org.feeluown.mobile.ProviderMutationResult
import org.feeluown.mobile.ProviderPlaylist
import org.feeluown.mobile.ProviderPlaylistDetail
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
import org.feeluown.mobile.provider.core.playlistKey
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.splitResourceId
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.trackKey
import org.feeluown.mobile.provider.core.network.ProviderCachePolicies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderRequestKind
import org.feeluown.mobile.provider.core.network.currentTimeMillis

/**
 * Content-oriented Bilibili decorator.
 *
 * The existing provider remains responsible for search, playback, comments and
 * favorites. This layer restores the richer Bilibili browsing surfaces that
 * were available before the Kotlin provider migration, while keeping live
 * content deliberately out of scope.
 */
class BilibiliContentProvider(
    private val http: ProviderHttpClient,
    private val credentials: ProviderCredentialStore,
    private val delegate: BilibiliProvider = BilibiliProvider(http, credentials),
) : KotlinMusicProvider by delegate {
    override val features: List<ProviderFeature> = delegate.features + EXTRA_FEATURES

    private val cursorMutex = Mutex()
    private val historyCursors = mutableMapOf<Int, HistoryCursor?>(0 to null)
    private val dynamicCursors = mutableMapOf<Int, String?>(0 to null)
    private val wbiMutex = Mutex()
    private var wbiKeys: WbiKeys? = null

    override suspend fun loadFeature(feature: ProviderFeature, offset: Int, limit: Int): ProviderContentSection {
        if (feature.requiresLogin && !delegate.authState().isLoggedIn) {
            return ProviderContentSection(feature, isLoginRequired = true)
        }
        val request = parseFeatureRequest(feature.id)
        return when (request.baseId) {
            RECOMMENDED_VIDEOS -> loadRecommendedVideos(feature, offset, limit)
            WEEKLY_MUST_WATCH -> loadWeeklyMustWatch(feature, offset, limit)
            WATCH_LATER -> loadWatchLaterFeature(feature)
            HISTORY -> loadHistory(feature, offset, limit)
            DYNAMIC_VIDEOS -> loadDynamicVideos(feature, offset, limit)
            FOLLOWED_CREATORS -> loadFollowedCreators(feature, offset, limit)
            COLLECTED_MEDIA -> loadCollectedMedia(feature, request.params["type"] ?: "1", offset, limit)
            else -> delegate.loadFeature(feature, offset, limit)
        }
    }

    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> {
        val identifier = splitResourceId(playlist.id, "playlist").second
        return if (identifier == WATCH_LATER_PLAYLIST || identifier.startsWith(WEEKLY_PREFIX)) {
            playlistDetail(playlist, 0, SPECIAL_PLAYLIST_PAGE_SIZE).tracks
        } else {
            delegate.playlistTracks(playlist)
        }
    }

    override suspend fun playlistDetail(
        playlist: ProviderPlaylist,
        offset: Int,
        limit: Int,
    ): ProviderPlaylistDetail {
        val identifier = splitResourceId(playlist.id, "playlist").second
        return when {
            identifier == WATCH_LATER_PLAYLIST -> loadWatchLaterDetail(playlist, offset, limit)
            identifier.startsWith(WEEKLY_PREFIX) -> loadWeeklyDetail(playlist, identifier.removePrefix(WEEKLY_PREFIX), offset, limit)
            else -> delegate.playlistDetail(playlist, offset, limit)
        }
    }

    override suspend fun playlistOperationTargets(track: MusicTrack): List<ProviderPlaylist> {
        val targets = delegate.playlistOperationTargets(track)
        if (!delegate.authState().isLoggedIn || track.source != ID) return targets
        return listOf(watchLaterPlaylist()) + targets
    }

    override suspend fun addTrackToPlaylist(playlist: ProviderPlaylist, track: MusicTrack): ProviderMutationResult {
        val identifier = splitResourceId(playlist.id, "playlist").second
        if (identifier != WATCH_LATER_PLAYLIST) return delegate.addTrackToPlaylist(playlist, track)
        val (bvid, page) = parsePagedIdentifier(track.providerId ?: track.id)
        if (page != null) return ProviderMutationResult(false, "哔哩哔哩稍后再看不支持分 P 条目")
        return mutateWatchLater(add = true, bvid = bvid)
    }

    override suspend fun removeTrackFromPlaylist(playlist: ProviderPlaylist, track: MusicTrack): ProviderMutationResult {
        val identifier = splitResourceId(playlist.id, "playlist").second
        if (identifier != WATCH_LATER_PLAYLIST) return delegate.removeTrackFromPlaylist(playlist, track)
        val (bvid, page) = parsePagedIdentifier(track.providerId ?: track.id)
        if (page != null) return ProviderMutationResult(false, "哔哩哔哩稍后再看不支持分 P 条目")
        return mutateWatchLater(add = false, bvid = bvid)
    }

    override suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack> =
        mediaItemDetail(item, 0, 0, SPECIAL_PLAYLIST_PAGE_SIZE).tracks

    override suspend fun mediaItemDetail(
        item: ProviderMediaItem,
        tracksOffset: Int,
        albumsOffset: Int,
        limit: Int,
    ): ProviderMediaItemDetail {
        val identifier = splitResourceId(item.id, if (item.type == ProviderMediaItemType.Artist) "artist" else "album").second
        return when (item.type) {
            ProviderMediaItemType.Artist -> loadCreatorDetail(item, identifier, tracksOffset, limit)
            ProviderMediaItemType.Album -> if (identifier.startsWith(SEASON_PREFIX)) {
                loadSeasonDetail(item, identifier.removePrefix(SEASON_PREFIX), tracksOffset, limit)
            } else {
                delegate.mediaItemDetail(item, tracksOffset, albumsOffset, limit)
            }
        }
    }

    private suspend fun loadRecommendedVideos(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val pageSize = limit.coerceIn(1, MAX_RECOMMEND_PAGE_SIZE)
        val page = offset / pageSize + 1
        val root = http.getText(
            providerId = ID,
            url = signedQueryUrl(
                "$BASE/x/web-interface/wbi/index/top/feed/rcmd",
                mapOf(
                    "fresh_type" to "4",
                    "feed_version" to "V8",
                    "ps" to pageSize.toString(),
                    "fresh_idx" to page.toString(),
                    "fresh_idx_1h" to page.toString(),
                    "brush" to page.toString(),
                    "homepage_ver" to "1",
                    "web_location" to "1430650",
                ),
            ),
            headers = headers(),
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val values = root.obj("data")?.array("item").orEmpty()
        val tracks = values.mapNotNull { recommendationToTrack(it.asObject()) }
        return ProviderContentSection(
            feature = feature,
            tracks = tracks,
            nextOffset = offset + pageSize,
            hasMore = values.size >= pageSize,
            errorMessage = apiError(root),
        )
    }

    private suspend fun loadWeeklyMustWatch(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val root = http.getText(
            providerId = ID,
            url = "$BASE/x/web-interface/popular/series/list",
            headers = headers(),
            cacheKey = "bilibili:weekly:list",
            cachePolicy = ProviderCachePolicies.recommendation,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val values = root.obj("data")?.array("list").orEmpty()
        val page = values.drop(offset).take(limit.coerceAtLeast(1)).mapNotNull { element ->
            val item = element.asObject()
            val number = item.stringOrNull("number") ?: item.long("number")?.toString() ?: return@mapNotNull null
            val subject = item.string("subject")
            val name = item.string("name")
            providerPlaylist(
                identifier = "$WEEKLY_PREFIX$number",
                title = subject.ifBlank { "每周必看 第${number}期" },
                description = name,
                providerUrl = "https://www.bilibili.com/v/popular/weekly?num=$number",
            )
        }
        val nextOffset = offset + page.size
        return ProviderContentSection(
            feature = feature,
            playlists = page,
            nextOffset = nextOffset,
            hasMore = nextOffset < values.size,
            errorMessage = apiError(root),
        )
    }

    private suspend fun loadWatchLaterFeature(feature: ProviderFeature): ProviderContentSection {
        val root = watchLaterRoot()
        val data = root.obj("data")
        val values = data?.array("list").orEmpty()
        val playlist = watchLaterPlaylist(
            count = data?.int("count") ?: values.size,
            coverUrl = values.firstOrNull()?.asObject()?.stringOrNull("pic")?.let(::normalizeCover),
        )
        return ProviderContentSection(
            feature = feature,
            playlists = listOf(playlist),
            errorMessage = apiError(root),
        )
    }

    private suspend fun loadWatchLaterDetail(
        playlist: ProviderPlaylist,
        offset: Int,
        limit: Int,
    ): ProviderPlaylistDetail {
        val root = watchLaterRoot()
        val data = root.obj("data")
        val values = data?.array("list").orEmpty()
        val page = values.drop(offset).take(limit.coerceAtLeast(1))
        val tracks = page.mapNotNull { historyLikeToTrack(it.asObject()) }
        val count = data?.int("count") ?: values.size
        val actual = playlist.copy(
            coverUrl = values.firstOrNull()?.asObject()?.stringOrNull("pic")?.let(::normalizeCover) ?: playlist.coverUrl,
            trackCount = count,
        )
        val nextOffset = offset + page.size
        return ProviderPlaylistDetail(
            playlist = actual,
            tracks = tracks,
            tracksNextOffset = nextOffset,
            tracksHasMore = nextOffset < values.size,
        )
    }

    private suspend fun watchLaterRoot(): JsonObject = http.getText(
        providerId = ID,
        url = "$BASE/x/v2/history/toview/web?jsonp=jsonp",
        headers = headers(),
        cacheKey = null,
    ).value.let { providerJson.parseToJsonElement(it).asObject() }

    private suspend fun loadHistory(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val pageSize = limit.coerceIn(1, 30)
        val cursor = cursorMutex.withLock {
            if (offset == 0) {
                historyCursors.clear()
                historyCursors[0] = null
            }
            historyCursors[offset]
        }
        if (offset > 0 && cursor == null) return ProviderContentSection(feature)
        val root = http.getText(
            providerId = ID,
            url = queryUrl(
                "$BASE/x/web-interface/history/cursor",
                buildMap {
                    put("ps", pageSize.toString())
                    put("type", "archive")
                    cursor?.let {
                        put("max", it.max.toString())
                        put("view_at", it.viewAt.toString())
                        put("business", it.business)
                    }
                },
            ),
            headers = headers(),
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val data = root.obj("data")
        val values = data?.array("list").orEmpty()
        val tracks = values.mapNotNull { historyCursorItemToTrack(it.asObject()) }
        val nextOffset = offset + values.size
        val nextCursor = data?.obj("cursor")?.let {
            val max = it.long("max") ?: 0L
            val viewAt = it.long("view_at") ?: 0L
            val business = it.string("business")
            if (viewAt > 0 && business.isNotBlank()) HistoryCursor(max, viewAt, business) else null
        }
        if (values.isNotEmpty()) {
            cursorMutex.withLock { historyCursors[nextOffset] = nextCursor }
        }
        return ProviderContentSection(
            feature = feature,
            tracks = tracks,
            nextOffset = nextOffset,
            hasMore = values.isNotEmpty() && nextCursor != null,
            errorMessage = apiError(root),
        )
    }

    private suspend fun loadDynamicVideos(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val cursor = cursorMutex.withLock {
            if (offset == 0) {
                dynamicCursors.clear()
                dynamicCursors[0] = null
            }
            dynamicCursors[offset]
        }
        if (offset > 0 && cursor == null) return ProviderContentSection(feature)
        val page = if (offset == 0) 1 else offset / limit.coerceAtLeast(1) + 1
        val root = http.getText(
            providerId = ID,
            url = queryUrl(
                "$BASE/x/polymer/web-dynamic/v1/feed/all",
                buildMap {
                    put("timezone_offset", "-480")
                    put("type", "video")
                    put("page", page.toString())
                    cursor?.takeIf { it.isNotBlank() }?.let { put("offset", it) }
                },
            ),
            headers = headers(),
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val data = root.obj("data")
        val values = data?.array("items").orEmpty()
        val tracks = values.mapNotNull { dynamicItemToTrack(it.asObject()) }
        val nextOffset = offset + values.size
        val nextCursor = data?.stringOrNull("offset")
        if (values.isNotEmpty()) {
            cursorMutex.withLock { dynamicCursors[nextOffset] = nextCursor }
        }
        return ProviderContentSection(
            feature = feature,
            tracks = tracks,
            nextOffset = nextOffset,
            hasMore = data?.boolean("has_more") == true && !nextCursor.isNullOrBlank(),
            errorMessage = apiError(root),
        )
    }

    private suspend fun loadFollowedCreators(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val mid = currentUserMid() ?: return ProviderContentSection(feature, isLoginRequired = true)
        val pageSize = limit.coerceIn(1, 50)
        val page = offset / pageSize + 1
        val root = http.getText(
            providerId = ID,
            url = queryUrl(
                "$BASE/x/relation/followings",
                mapOf(
                    "vmid" to mid,
                    "pn" to page.toString(),
                    "ps" to pageSize.toString(),
                    "order" to "desc",
                ),
            ),
            headers = headers(),
            cacheKey = "bilibili:followings:$mid:$page:$pageSize",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val data = root.obj("data")
        val values = data?.array("list").orEmpty()
        val items = values.mapNotNull { creatorSummary(it.asObject()) }
        val nextOffset = offset + values.size
        val total = data?.int("total")
        return ProviderContentSection(
            feature = feature,
            mediaItems = items,
            nextOffset = nextOffset,
            hasMore = total?.let { nextOffset < it } ?: (values.size >= pageSize),
            errorMessage = apiError(root),
        )
    }

    private suspend fun loadCreatorDetail(
        item: ProviderMediaItem,
        mid: String,
        offset: Int,
        limit: Int,
    ): ProviderMediaItemDetail {
        val pageSize = limit.coerceIn(1, 30)
        val page = offset / pageSize + 1
        val infoRoot = http.getText(
            providerId = ID,
            url = signedQueryUrl("$BASE/x/space/wbi/acc/info", mapOf("mid" to mid)),
            headers = headers(),
            cacheKey = "bilibili:creator:$mid",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val uploadsRoot = http.getText(
            providerId = ID,
            url = signedQueryUrl(
                "$BASE/x/space/wbi/arc/search",
                mapOf(
                    "mid" to mid,
                    "pn" to page.toString(),
                    "ps" to pageSize.toString(),
                    "order" to "pubdate",
                    "tid" to "0",
                    "keyword" to "",
                    "order_avoided" to "true",
                    "platform" to "web",
                ),
            ),
            headers = headers(),
            cacheKey = "bilibili:creator:uploads:$mid:$page:$pageSize",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val info = infoRoot.obj("data")
        val uploadData = uploadsRoot.obj("data")
        val values = uploadData?.obj("list")?.array("vlist").orEmpty()
        val tracks = values.mapNotNull { creatorVideoToTrack(it.asObject(), item.title) }
        val total = uploadData?.obj("page")?.int("count") ?: item.trackCount
        val actual = item.copy(
            title = info?.string("name").orEmpty().ifBlank { item.title },
            coverUrl = normalizeCover(info?.stringOrNull("face")) ?: item.coverUrl,
            description = info?.string("sign").orEmpty().ifBlank { item.description },
            trackCount = total,
            providerUrl = "https://space.bilibili.com/$mid",
        )
        val nextOffset = offset + values.size
        return ProviderMediaItemDetail(
            item = actual,
            tracks = tracks,
            tracksNextOffset = nextOffset,
            tracksHasMore = total?.let { nextOffset < it } ?: (values.size >= pageSize),
        )
    }

    private suspend fun loadCollectedMedia(
        feature: ProviderFeature,
        type: String,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val mid = currentUserMid() ?: return ProviderContentSection(feature, isLoginRequired = true)
        val normalizedType = if (type == "2") "2" else "1"
        val pageSize = limit.coerceIn(1, 30)
        val page = offset / pageSize + 1
        val root = http.getText(
            providerId = ID,
            url = queryUrl(
                "$BASE/x/space/bangumi/follow/list",
                mapOf(
                    "vmid" to mid,
                    "type" to normalizedType,
                    "pn" to page.toString(),
                    "ps" to pageSize.toString(),
                ),
            ),
            headers = headers(),
            cacheKey = "bilibili:collected-media:$mid:$normalizedType:$page:$pageSize",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val data = root.obj("data")
        val values = data?.array("list").orEmpty()
        val items = values.mapNotNull { seasonSummary(it.asObject()) }
        val nextOffset = offset + values.size
        val total = data?.int("total")
        val presentationFeature = ProviderFeatureFilterCodec.attach(
            feature,
            listOf(
                ProviderFeatureFilterSpec(
                    key = "type",
                    title = "类型",
                    options = listOf(
                        ProviderFeatureFilterOption(
                            label = "番剧",
                            featureId = "$COLLECTED_MEDIA|type=1",
                            selected = normalizedType == "1",
                        ),
                        ProviderFeatureFilterOption(
                            label = "影视",
                            featureId = "$COLLECTED_MEDIA|type=2",
                            selected = normalizedType == "2",
                        ),
                    ),
                )
            ),
        )
        return ProviderContentSection(
            feature = presentationFeature,
            mediaItems = items,
            nextOffset = nextOffset,
            hasMore = total?.let { nextOffset < it } ?: (values.size >= pageSize),
            errorMessage = apiError(root),
        )
    }

    private suspend fun loadSeasonDetail(
        item: ProviderMediaItem,
        seasonId: String,
        offset: Int,
        limit: Int,
    ): ProviderMediaItemDetail {
        val root = http.getText(
            providerId = ID,
            url = queryUrl("$BASE/pgc/view/web/season", mapOf("season_id" to seasonId)),
            headers = headers(mapOf("Referer" to "https://www.bilibili.com/bangumi/play/ss$seasonId")),
            cacheKey = "bilibili:season:$seasonId",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val result = root.obj("result") ?: return ProviderMediaItemDetail(item)
        val episodes = buildList<JsonElement> {
            addAll(result.array("episodes"))
            result.array("section").forEach { section ->
                addAll(section.asObject().array("episodes"))
            }
        }.distinctBy { it.asObject().string("id") }
        val page = episodes.drop(offset).take(limit.coerceAtLeast(1))
        val title = result.string("title").ifBlank { item.title }
        val actual = item.copy(
            title = title,
            coverUrl = normalizeCover(result.stringOrNull("cover")) ?: item.coverUrl,
            description = result.string("evaluate").ifBlank { item.description },
            trackCount = episodes.size,
            providerUrl = "https://www.bilibili.com/bangumi/play/ss$seasonId",
        )
        val tracks = page.mapNotNull { episodeToTrack(it.asObject(), actual) }
        val nextOffset = offset + page.size
        return ProviderMediaItemDetail(
            item = actual,
            tracks = tracks,
            tracksNextOffset = nextOffset,
            tracksHasMore = nextOffset < episodes.size,
        )
    }

    private suspend fun loadWeeklyDetail(
        playlist: ProviderPlaylist,
        number: String,
        offset: Int,
        limit: Int,
    ): ProviderPlaylistDetail {
        val root = http.getText(
            providerId = ID,
            url = queryUrl("$BASE/x/web-interface/popular/series/one", mapOf("number" to number)),
            headers = headers(),
            cacheKey = "bilibili:weekly:$number",
            cachePolicy = ProviderCachePolicies.recommendation,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val data = root.obj("data")
        val values = data?.array("list").orEmpty()
        val page = values.drop(offset).take(limit.coerceAtLeast(1))
        val tracks = page.mapNotNull { historyLikeToTrack(it.asObject()) }
        val config = data?.obj("config")
        val actual = playlist.copy(
            title = config?.string("subject").orEmpty().ifBlank { playlist.title },
            trackCount = values.size,
        )
        val nextOffset = offset + page.size
        return ProviderPlaylistDetail(
            playlist = actual,
            tracks = tracks,
            tracksNextOffset = nextOffset,
            tracksHasMore = nextOffset < values.size,
        )
    }

    private suspend fun mutateWatchLater(add: Boolean, bvid: String): ProviderMutationResult {
        val csrf = csrfToken() ?: return ProviderMutationResult(false, "缺少哔哩哔哩 csrf Cookie")
        val aid = if (add) null else videoAid(bvid)
        if (!add && aid == null) return ProviderMutationResult(false, "无法读取哔哩哔哩视频编号")
        val root = http.postForm(
            providerId = ID,
            url = "$BASE/x/v2/history/toview/${if (add) "add" else "del"}",
            form = Parameters.build {
                if (add) append("bvid", bvid) else append("aid", aid.toString())
                append("csrf", csrf)
            },
            headers = headers(),
            kind = ProviderRequestKind.Mutation,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val success = root.int("code") == 0
        return ProviderMutationResult(
            success = success,
            message = if (success) {
                if (add) "已添加到稍后再看" else "已从稍后再看移除"
            } else {
                root.string("message").ifBlank { "哔哩哔哩稍后再看操作失败" }
            },
        )
    }

    private suspend fun videoAid(bvid: String): Long? = http.getText(
        providerId = ID,
        url = queryUrl("$BASE/x/web-interface/view", mapOf("bvid" to bvid)),
        headers = headers(),
        cacheKey = "bilibili:view:$bvid",
        cachePolicy = ProviderCachePolicies.detail,
    ).value.let { providerJson.parseToJsonElement(it).asObject().obj("data")?.long("aid") }

    private suspend fun currentUserMid(): String? = nav()?.obj("data")?.stringOrNull("mid")

    private suspend fun nav(): JsonObject? = runCatching {
        http.getText(
            providerId = ID,
            url = "$BASE/x/web-interface/nav",
            headers = headers(),
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
    }.getOrNull()

    private suspend fun csrfToken(): String? {
        val stored = credentials.read(ID) ?: return null
        return stored.cookies["bili_jct"]?.takeIf { it.isNotBlank() }
            ?: org.feeluown.mobile.provider.core.parseCookies(stored.cookieHeader.orEmpty())["bili_jct"]?.takeIf { it.isNotBlank() }
    }

    private suspend fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val stored = credentials.read(ID)
        return buildMap {
            put("User-Agent", USER_AGENT)
            put("Referer", "https://www.bilibili.com/")
            put("Accept", "application/json, text/plain, */*")
            cookieHeader(stored).takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            stored?.authorization?.takeIf { it.isNotBlank() }?.let { put("Authorization", it) }
            putAll(extra)
        }
    }

    private fun recommendationToTrack(item: JsonObject): MusicTrack? {
        val bvid = item.stringOrNull("bvid") ?: return null
        val owner = item.obj("owner")
        return providerTrack(
            bvid = bvid,
            title = item.string("title"),
            artist = owner?.string("name").orEmpty().ifBlank { item.string("author") },
            cover = item.stringOrNull("pic"),
            durationMs = item.long("duration")?.times(1_000),
        )
    }

    private fun historyLikeToTrack(item: JsonObject): MusicTrack? {
        val bvid = item.stringOrNull("bvid") ?: item.stringOrNull("bv_id") ?: return null
        return providerTrack(
            bvid = bvid,
            title = item.string("title"),
            artist = item.obj("owner")?.string("name").orEmpty()
                .ifBlank { item.obj("upper")?.string("name").orEmpty() }
                .ifBlank { item.string("author") },
            cover = item.stringOrNull("pic") ?: item.stringOrNull("cover"),
            durationMs = item.long("duration")?.times(1_000),
        )
    }

    private fun historyCursorItemToTrack(item: JsonObject): MusicTrack? {
        val history = item.obj("history") ?: return null
        if (history.string("business") != "archive") return null
        val bvid = history.stringOrNull("bvid") ?: return null
        val page = history.int("page")?.takeIf { it > 1 }
        val identifier = if (page != null) "paged_${bvid}__${page}" else bvid
        return MusicTrack(
            id = trackKey(ID, identifier),
            title = history.string("part").takeIf { it.isNotBlank() && page != null } ?: item.string("title"),
            artists = item.string("author_name"),
            album = item.string("tag_name"),
            source = ID,
            sourceType = TrackSourceType.Provider,
            coverUrl = normalizeCover(item.stringOrNull("cover")),
            durationMs = item.long("duration")?.times(1_000),
            providerId = trackKey(ID, identifier),
            providerName = NAME,
            providerUrl = "https://www.bilibili.com/video/$bvid",
        )
    }

    private fun dynamicItemToTrack(item: JsonObject): MusicTrack? {
        val modules = item.obj("modules") ?: return null
        val archive = modules.obj("module_dynamic")?.obj("major")?.obj("archive") ?: return null
        val bvid = archive.stringOrNull("bvid") ?: return null
        val author = modules.obj("module_author")
        return providerTrack(
            bvid = bvid,
            title = archive.string("title"),
            artist = author?.string("name").orEmpty(),
            cover = archive.stringOrNull("cover"),
            durationMs = parseDurationText(archive.string("duration_text")),
        )
    }

    private fun creatorSummary(item: JsonObject): ProviderMediaItem? {
        val mid = item.stringOrNull("mid") ?: item.long("mid")?.toString() ?: return null
        val name = item.stringOrNull("uname") ?: return null
        return ProviderMediaItem(
            id = mediaItemKey(ProviderMediaItemType.Artist, ID, mid),
            title = name,
            providerId = ID,
            providerName = NAME,
            type = ProviderMediaItemType.Artist,
            coverUrl = normalizeCover(item.stringOrNull("face")),
            description = item.string("sign"),
            providerUrl = "https://space.bilibili.com/$mid",
        )
    }

    private fun creatorVideoToTrack(item: JsonObject, fallbackArtist: String): MusicTrack? {
        val bvid = item.stringOrNull("bvid") ?: return null
        return providerTrack(
            bvid = bvid,
            title = item.string("title"),
            artist = item.string("author").ifBlank { fallbackArtist },
            cover = item.stringOrNull("pic"),
            durationMs = parseDurationText(item.string("length")),
        )
    }

    private fun seasonSummary(item: JsonObject): ProviderMediaItem? {
        val seasonId = item.stringOrNull("season_id") ?: item.long("season_id")?.toString() ?: return null
        val title = item.stringOrNull("title") ?: return null
        val description = item.string("evaluate").ifBlank {
            item.obj("new_ep")?.string("index_show").orEmpty()
        }
        return ProviderMediaItem(
            id = mediaItemKey(ProviderMediaItemType.Album, ID, "$SEASON_PREFIX$seasonId"),
            title = title,
            providerId = ID,
            providerName = NAME,
            type = ProviderMediaItemType.Album,
            coverUrl = normalizeCover(item.stringOrNull("cover")),
            description = description,
            providerUrl = "https://www.bilibili.com/bangumi/play/ss$seasonId",
        )
    }

    private fun episodeToTrack(item: JsonObject, season: ProviderMediaItem): MusicTrack? {
        val bvid = item.stringOrNull("bvid") ?: return null
        val title = item.string("long_title").ifBlank { item.string("title") }.ifBlank { season.title }
        return MusicTrack(
            id = trackKey(ID, bvid),
            title = title,
            artists = season.title,
            album = season.title,
            source = ID,
            sourceType = TrackSourceType.Provider,
            coverUrl = normalizeCover(item.stringOrNull("cover")) ?: season.coverUrl,
            durationMs = normalizeEpisodeDuration(item.long("duration")),
            providerId = trackKey(ID, bvid),
            providerName = NAME,
            albumItemId = season.id,
            providerUrl = "https://www.bilibili.com/video/$bvid",
        )
    }

    private fun providerTrack(
        bvid: String,
        title: String,
        artist: String,
        cover: String?,
        durationMs: Long?,
    ): MusicTrack = MusicTrack(
        id = trackKey(ID, bvid),
        title = title,
        artists = artist,
        album = "",
        source = ID,
        sourceType = TrackSourceType.Provider,
        coverUrl = normalizeCover(cover),
        durationMs = durationMs,
        providerId = trackKey(ID, bvid),
        providerName = NAME,
        providerUrl = "https://www.bilibili.com/video/$bvid",
    )

    private fun providerPlaylist(
        identifier: String,
        title: String,
        description: String = "",
        coverUrl: String? = null,
        trackCount: Int? = null,
        providerUrl: String? = null,
    ): ProviderPlaylist = ProviderPlaylist(
        id = playlistKey(ID, identifier),
        title = title,
        providerId = ID,
        providerName = NAME,
        coverUrl = coverUrl,
        description = description,
        trackCount = trackCount,
        providerUrl = providerUrl,
    )

    private fun watchLaterPlaylist(count: Int? = null, coverUrl: String? = null): ProviderPlaylist = providerPlaylist(
        identifier = WATCH_LATER_PLAYLIST,
        title = "稍后再看",
        description = "哔哩哔哩稍后再看",
        coverUrl = coverUrl,
        trackCount = count,
        providerUrl = "https://www.bilibili.com/watchlater/",
    )

    private fun apiError(root: JsonObject): String? {
        val code = root.int("code") ?: return null
        if (code == 0) return null
        return root.string("message").ifBlank { "哔哩哔哩接口请求失败 ($code)" }
    }

    private fun parseFeatureRequest(featureId: String): FeatureRequest {
        val parts = ProviderFeatureFilterCodec.requestId(featureId).split('|')
        return FeatureRequest(
            baseId = parts.firstOrNull().orEmpty(),
            params = parts.drop(1).mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                part.substring(0, separator) to part.substring(separator + 1)
            }.toMap(),
        )
    }

    private fun parsePagedIdentifier(value: String): Pair<String, Int?> {
        val raw = splitResourceId(value).second.ifBlank { value.substringAfterLast(':') }
        if (!raw.startsWith("paged_")) return raw to null
        val parts = raw.removePrefix("paged_").split("__", limit = 2)
        return parts.first() to parts.getOrNull(1)?.toIntOrNull()
    }

    private fun parseDurationText(value: String): Long? {
        val parts = value.split(':').mapNotNull { it.toLongOrNull() }
        if (parts.isEmpty()) return null
        return parts.fold(0L) { total, part -> total * 60 + part } * 1_000
    }

    private fun normalizeEpisodeDuration(value: Long?): Long? = value?.let {
        if (it >= 10_000L) it else it * 1_000L
    }

    private fun normalizeCover(value: String?): String? = value?.let {
        when {
            it.startsWith("//") -> "https:$it"
            it.startsWith("http://") -> "https://${it.removePrefix("http://")}"
            else -> it
        }
    }

    private fun queryUrl(base: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (key, value) ->
            "${encodeQueryComponent(key)}=${encodeQueryComponent(value)}"
        }
        return if (query.isBlank()) base else "$base?$query"
    }

    private suspend fun signedQueryUrl(base: String, params: Map<String, String>): String {
        val keys = wbiKeys() ?: return queryUrl(base, params)
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
                cacheKey = "bilibili:content:nav",
                cachePolicy = ProviderCachePolicies.detail,
            ).value.let { providerJson.parseToJsonElement(it).asObject().obj("data") }
            val image = data?.obj("wbi_img")
            val imageKey = image?.stringOrNull("img_url")?.keyFromUrl()
            val subKey = image?.stringOrNull("sub_url")?.keyFromUrl()
            if (imageKey.isNullOrBlank() || subKey.isNullOrBlank()) return@runCatching null
            WbiKeys(mixinKey(imageKey + subKey))
        }.getOrNull()?.also { wbiKeys = it }
    }

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

    private fun String.keyFromUrl(): String = substringAfterLast('/').substringBeforeLast('.')

    private data class FeatureRequest(
        val baseId: String,
        val params: Map<String, String>,
    )

    private data class HistoryCursor(
        val max: Long,
        val viewAt: Long,
        val business: String,
    )

    private data class WbiKeys(val mixinKey: String)

    private companion object {
        const val ID = "bilibili"
        const val NAME = "哔哩哔哩"
        const val BASE = "https://api.bilibili.com"

        const val RECOMMENDED_VIDEOS = "bilibili_recommended_videos"
        const val WEEKLY_MUST_WATCH = "bilibili_weekly_must_watch"
        const val WATCH_LATER = "bilibili_watch_later"
        const val HISTORY = "bilibili_history"
        const val DYNAMIC_VIDEOS = "bilibili_dynamic_videos"
        const val FOLLOWED_CREATORS = "bilibili_followed_creators"
        const val COLLECTED_MEDIA = "bilibili_collected_media"

        const val WATCH_LATER_PLAYLIST = "watch_later"
        const val WEEKLY_PREFIX = "weekly_"
        const val SEASON_PREFIX = "season_"
        const val MAX_RECOMMEND_PAGE_SIZE = 30
        const val SPECIAL_PLAYLIST_PAGE_SIZE = 200
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

        val EXTRA_FEATURES = listOf(
            ProviderFeature(RECOMMENDED_VIDEOS, ID, NAME, "个性化推荐", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, true),
            ProviderFeature(WEEKLY_MUST_WATCH, ID, NAME, "每周必看", ProviderFeatureCategory.Music, ProviderContentType.Playlists, false),
            ProviderFeature(WATCH_LATER, ID, NAME, "稍后再看", ProviderFeatureCategory.MinePlaylists, ProviderContentType.Playlists, true),
            ProviderFeature(HISTORY, ID, NAME, "历史记录", ProviderFeatureCategory.Mine, ProviderContentType.Songs, true),
            ProviderFeature(DYNAMIC_VIDEOS, ID, NAME, "动态视频", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, true),
            ProviderFeature(FOLLOWED_CREATORS, ID, NAME, "关注的 UP 主", ProviderFeatureCategory.Mine, ProviderContentType.Artists, true),
            ProviderFeature(COLLECTED_MEDIA, ID, NAME, "收藏番剧/影视", ProviderFeatureCategory.Mine, ProviderContentType.Albums, true),
        )

        val MIXIN_KEY_TABLE = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
        )
    }
}
