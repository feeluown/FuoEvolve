package org.feeluown.mobile.provider.ytmusic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.ProviderAuthState
import org.feeluown.mobile.ProviderCapabilities
import org.feeluown.mobile.ProviderMediaItem
import org.feeluown.mobile.ProviderMediaItemDetail
import org.feeluown.mobile.ProviderMediaItemType
import org.feeluown.mobile.ProviderPlaylist
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.ProviderVideo
import org.feeluown.mobile.TrackSourceType
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.array
import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.cookieHeader
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.mediaItemKey
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.playlistKey
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.splitResourceId
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.trackKey
import org.feeluown.mobile.provider.core.videoKey
import org.feeluown.mobile.provider.core.network.ProviderCachePolicies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient

/**
 * Browsing/search layer for YouTube Music.
 *
 * The existing [YtMusicProvider] remains responsible for playback, signature
 * deciphering, OAuth flow and playlist handling. This decorator only replaces
 * catalogue-style operations that map naturally onto FuoEvolve's common
 * provider contracts.
 */
internal class YtMusicContentProvider(
    private val base: YtMusicProvider,
    private val http: ProviderHttpClient,
    private val credentials: ProviderCredentialStore,
) : KotlinMusicProvider by base {
    private var cachedAccountName: String? = null

    // The base provider used to advertise add-to-playlist before implementing it.
    // Keep the UI honest until the mutation implementation lands.
    override val capabilities: ProviderCapabilities = base.capabilities.copy(
        canAddSongToPlaylist = false,
    )

    override suspend fun search(keyword: String): ProviderSearchResults {
        val root = webRequest(
            method = "search",
            payload = "{\"query\":${quote(keyword)}}",
            cacheKey = "ytmusic:content-search:$keyword",
        )
        val output = SearchOutput()
        collectSearchResults(root, output)
        return ProviderSearchResults(
            tracks = output.tracks.distinctBy { it.id }.take(50),
            playlists = output.playlists.distinctBy { it.id }.take(50),
            artists = output.artists.distinctBy { it.id }.take(50),
            albums = output.albums.distinctBy { it.id }.take(50),
            videos = output.videos.distinctBy { it.id }.take(50),
        )
    }

    override suspend fun lyrics(track: MusicTrack): String? = runCatching {
        val videoId = rawTrackId(track)
        if (videoId.isBlank()) return@runCatching null
        val watch = webRequest(
            method = "next",
            payload = watchPayload(videoId, radio = false),
            cacheKey = "ytmusic:watch:$videoId",
        )
        val browseId = findBrowseIdForPageType(watch, "MUSIC_PAGE_TYPE_TRACK_LYRICS")
            ?: findBrowseIdWithPrefix(watch, "MPLY")
            ?: return@runCatching null

        // ytmusicapi switches to ANDROID_MUSIC for timestamped lyrics. Keep this
        // request anonymous because authenticated WEB_REMIX/mobile lyric requests
        // are known to return HTTP 400 for otherwise valid lyric browse ids.
        val mobile = mobileBrowse(browseId)
        timedLyricsToLrc(mobile)
            ?: plainLyrics(mobile)
            ?: plainLyrics(
                webRequest(
                    method = "browse",
                    payload = "{\"browseId\":${quote(browseId)}}",
                    cacheKey = "ytmusic:lyrics-plain:$browseId",
                ),
            )
    }.getOrNull()

    override suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack> =
        mediaItemDetail(item, tracksOffset = 0, albumsOffset = 0, limit = 300).tracks

    override suspend fun mediaItemDetail(
        item: ProviderMediaItem,
        tracksOffset: Int,
        albumsOffset: Int,
        limit: Int,
    ): ProviderMediaItemDetail {
        val expectedPrefix = if (item.type == ProviderMediaItemType.Artist) "artist" else "album"
        val (_, browseId) = splitResourceId(item.id, expectedPrefix)
        if (browseId.isBlank()) return ProviderMediaItemDetail(item)
        val root = webRequest(
            method = "browse",
            payload = "{\"browseId\":${quote(browseId)}}",
            cacheKey = "ytmusic:media:$browseId",
        )
        val output = SearchOutput()
        collectSearchResults(root, output)
        val allTracks = output.tracks.distinctBy { it.id }
        val allAlbums = if (item.type == ProviderMediaItemType.Artist) {
            output.albums.distinctBy { it.id }
        } else {
            emptyList()
        }
        val tracks = allTracks.drop(tracksOffset).take(limit)
        val albums = allAlbums.drop(albumsOffset).take(limit)
        val header = mediaHeader(root, item.type)
        val actualItem = item.copy(
            title = header?.let(::titleText)?.ifBlank { item.title } ?: item.title,
            coverUrl = header?.let(::thumbnailUrl) ?: item.coverUrl,
            description = findDescription(root).ifBlank { item.description },
            trackCount = if (item.type == ProviderMediaItemType.Album) allTracks.size else item.trackCount,
            albumCount = if (item.type == ProviderMediaItemType.Artist && allAlbums.isNotEmpty()) {
                allAlbums.size
            } else {
                item.albumCount
            },
        )
        return ProviderMediaItemDetail(
            item = actualItem,
            tracks = tracks,
            albums = albums,
            tracksNextOffset = tracksOffset + tracks.size,
            tracksHasMore = tracksOffset + tracks.size < allTracks.size,
            albumsNextOffset = albumsOffset + albums.size,
            albumsHasMore = albumsOffset + albums.size < allAlbums.size,
        )
    }

    override suspend fun similarTracks(track: MusicTrack): List<MusicTrack> = runCatching {
        val videoId = rawTrackId(track)
        if (videoId.isBlank()) return@runCatching emptyList()
        val root = webRequest(
            method = "next",
            payload = watchPayload(videoId, radio = true),
            cacheKey = "ytmusic:radio:$videoId",
        )
        val tracks = mutableListOf<MusicTrack>()
        collectPlaylistPanelTracks(root, tracks)
        tracks.distinctBy { it.id }
            .filterNot { rawTrackId(it) == videoId }
            .take(50)
    }.getOrDefault(emptyList())

    override suspend fun authState(): ProviderAuthState {
        val baseState = base.authState()
        if (!baseState.isLoggedIn) {
            cachedAccountName = null
            return baseState
        }
        cachedAccountName?.let { return baseState.copy(userName = it) }
        val accountName = runCatching {
            ensureOAuthAccessToken()?.let { fetchOAuthAccountName(it) }
                ?: fetchBrowserAccountName()
        }.getOrNull()?.takeIf(String::isNotBlank)
        if (accountName != null) cachedAccountName = accountName
        return baseState.copy(userName = accountName)
    }

    override suspend fun loginWithHeaders(authorization: String, cookie: String): ProviderAuthState {
        cachedAccountName = null
        base.loginWithHeaders(authorization, cookie)
        return authState()
    }

    override suspend fun loginWithHeaderFile(headerFileJson: String): ProviderAuthState {
        cachedAccountName = null
        base.loginWithHeaderFile(headerFileJson)
        return authState()
    }

    override suspend fun loginWithOAuth(
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long?,
        scope: String?,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState {
        cachedAccountName = null
        base.loginWithOAuth(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMillis = expiresAtMillis,
            scope = scope,
            clientId = clientId,
            clientSecret = clientSecret,
        )
        return authState()
    }

    override suspend fun logout(): ProviderAuthState {
        cachedAccountName = null
        return base.logout()
    }

    suspend fun beginOAuth(clientId: String, clientSecret: String): YtMusicDeviceAuthCode =
        base.beginOAuth(clientId, clientSecret)

    suspend fun pollOAuth(
        deviceCode: String,
        clientId: String,
        clientSecret: String,
    ): YtMusicOAuthPollResult = base.pollOAuth(deviceCode, clientId, clientSecret)

    suspend fun loginWithOAuthJson(
        oauthJson: String,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState {
        cachedAccountName = null
        base.loginWithOAuthJson(oauthJson, clientId, clientSecret)
        return authState()
    }

    private suspend fun webRequest(
        method: String,
        payload: String,
        cacheKey: String? = null,
        authenticated: Boolean = false,
    ): JsonObject {
        val body = withWebContext(payload)
        val cachePolicy = when (method) {
            "search" -> ProviderCachePolicies.search
            "next" -> ProviderCachePolicies.recommendation
            "browse" -> ProviderCachePolicies.detail
            else -> ProviderCachePolicies.none
        }
        return http.postJson(
            providerId = ID,
            url = "${YtMusicProvider.API_BASE}/$method?alt=json&key=${YtMusicProvider.FALLBACK_API_KEY}",
            json = body,
            headers = if (authenticated) browserHeaders() else anonymousHeaders(),
            cacheKey = cacheKey,
            cachePolicy = if (cacheKey == null) ProviderCachePolicies.none else cachePolicy,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
    }

    private suspend fun mobileBrowse(browseId: String): JsonObject {
        val body = "{" +
            "\"context\":{\"client\":{" +
            "\"clientName\":\"ANDROID_MUSIC\"," +
            "\"clientVersion\":\"7.21.50\"," +
            "\"hl\":\"zh_CN\"" +
            "},\"user\":{}}," +
            "\"browseId\":${quote(browseId)}" +
            "}"
        return http.postJson(
            providerId = ID,
            url = "${YtMusicProvider.API_BASE}/browse?alt=json&key=${YtMusicProvider.FALLBACK_API_KEY}",
            json = body,
            headers = anonymousHeaders(),
            cacheKey = "ytmusic:lyrics-mobile:$browseId",
            cachePolicy = ProviderCachePolicies.lyric,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
    }

    private fun withWebContext(payload: String): String {
        val trimmed = payload.trim()
        return if (trimmed.startsWith('{') && trimmed.endsWith('}')) {
            "{" +
                "\"context\":{\"client\":{" +
                "\"clientName\":\"WEB_REMIX\"," +
                "\"clientVersion\":${quote(YtMusicProvider.dynamicClientVersion())}," +
                "\"hl\":\"zh_CN\"" +
                "},\"user\":{}}," +
                trimmed.drop(1)
        } else {
            trimmed
        }
    }

    private fun anonymousHeaders(): Map<String, String> = mapOf(
        "User-Agent" to YtMusicOAuth.USER_AGENT,
        "Accept" to "*/*",
        "Origin" to YtMusicProvider.YTM_ORIGIN,
        "Referer" to "${YtMusicProvider.YTM_ORIGIN}/",
    )

    private suspend fun browserHeaders(): Map<String, String> {
        val stored = credentials.read(ID)
        val cookie = cookieHeader(stored)
        return buildMap {
            putAll(anonymousHeaders())
            if (cookie.isNotBlank()) put("Cookie", cookie)
            val sapisid = YtMusicProvider.sapisidFromCookie(cookie)
            if (!sapisid.isNullOrBlank()) {
                put(
                    "Authorization",
                    YtMusicProvider.sapisidHashAuthorization(sapisid, YtMusicProvider.YTM_ORIGIN),
                )
            } else {
                stored?.authorization?.takeIf(String::isNotBlank)?.let { put("Authorization", it) }
            }
        }
    }

    private suspend fun fetchBrowserAccountName(): String? {
        val stored = credentials.read(ID) ?: return null
        if (cookieHeader(stored).isBlank() && stored.authorization.isNullOrBlank()) return null
        val root = webRequest(
            method = "account/account_menu",
            payload = "{}",
            cacheKey = null,
            authenticated = true,
        )
        val header = findObjectByRenderer(root, "activeAccountHeaderRenderer") ?: return null
        return header.obj("accountName")?.array("runs")
            ?.firstOrNull()?.asObject()?.stringOrNull("text")
    }

    private suspend fun fetchOAuthAccountName(token: YtMusicOAuthToken): String? {
        val url = "${YtMusicProvider.DATA_API_BASE}/channels?part=snippet&mine=true&maxResults=1"
        val root = http.getText(
            providerId = ID,
            url = url,
            headers = mapOf(
                "User-Agent" to YtMusicOAuth.USER_AGENT,
                "Accept" to "application/json",
                "Authorization" to token.asAuthorizationHeader(),
            ),
            cacheKey = "ytmusic:oauth-account",
            cachePolicy = ProviderCachePolicies.detail,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        return root.array("items").firstOrNull()?.asObject()
            ?.obj("snippet")?.stringOrNull("title")
    }

    private suspend fun ensureOAuthAccessToken(): YtMusicOAuthToken? {
        val stored = credentials.read(ID) ?: return null
        if (!stored.hasOAuthAccess()) return null
        val clientId = stored.oauthClientId.orEmpty()
        val clientSecret = stored.oauthClientSecret.orEmpty()
        if (clientId.isBlank() || clientSecret.isBlank()) return null
        var token = YtMusicOAuthToken(
            accessToken = stored.oauthAccessToken.orEmpty(),
            refreshToken = stored.oauthRefreshToken.orEmpty(),
            scope = stored.oauthScope ?: YtMusicOAuth.SCOPE,
            tokenType = "Bearer",
            expiresAtEpochSeconds = (stored.oauthExpiresAtMillis ?: 0L) / 1_000,
            expiresInSeconds = 0,
        )
        if (token.accessToken.isNotBlank() && !token.isExpiring()) return token
        if (token.refreshToken.isBlank()) return null
        token = YtMusicOAuthClient(
            http = http,
            credentials = YtMusicOAuthClientCredentials(clientId, clientSecret),
        ).refreshAccessToken(token.refreshToken)
        credentials.write(
            ID,
            stored.copy(
                oauthAccessToken = token.accessToken,
                oauthRefreshToken = token.refreshToken,
                oauthExpiresAtMillis = token.expiresAtEpochSeconds * 1_000,
                oauthScope = token.scope,
            ),
        )
        return token
    }

    private data class SearchOutput(
        val tracks: MutableList<MusicTrack> = mutableListOf(),
        val playlists: MutableList<ProviderPlaylist> = mutableListOf(),
        val artists: MutableList<ProviderMediaItem> = mutableListOf(),
        val albums: MutableList<ProviderMediaItem> = mutableListOf(),
        val videos: MutableList<ProviderVideo> = mutableListOf(),
    )

    private data class RunInfo(
        val text: String,
        val browseId: String? = null,
    )

    private fun collectSearchResults(element: JsonElement, output: SearchOutput) {
        when (element) {
            is JsonObject -> {
                element.obj("musicResponsiveListItemRenderer")?.let { parseResponsiveItem(it, output) }
                element.obj("musicTwoRowItemRenderer")?.let { parseTwoRowItem(it, output) }
                element.obj("musicCardShelfRenderer")?.let { parseCardItem(it, output) }
                element.forEach { (_, value) -> collectSearchResults(value, output) }
            }
            is JsonArray -> element.forEach { collectSearchResults(it, output) }
            else -> Unit
        }
    }

    private fun parseResponsiveItem(item: JsonObject, output: SearchOutput) {
        val browseId = primaryBrowseId(item)
        when {
            browseId.isArtistBrowseId() -> output.artists += artistFromItem(item, browseId!!)
            browseId.isAlbumBrowseId() -> output.albums += albumFromItem(item, browseId!!)
            browseId.isPlaylistBrowseId() -> output.playlists += playlistFromItem(item, browseId!!)
            else -> {
                val videoId = findVideoId(item) ?: return
                val videoType = findStringByKey(item, "musicVideoType")
                if (videoType == null || videoType == "MUSIC_VIDEO_TYPE_ATV") {
                    output.tracks += trackFromResponsiveItem(item, videoId)
                } else {
                    output.videos += videoFromItem(item, videoId)
                }
            }
        }
    }

    private fun parseTwoRowItem(item: JsonObject, output: SearchOutput) {
        val browseId = primaryBrowseId(item)
        when {
            browseId.isArtistBrowseId() -> output.artists += artistFromItem(item, browseId!!)
            browseId.isAlbumBrowseId() -> output.albums += albumFromItem(item, browseId!!)
            browseId.isPlaylistBrowseId() -> output.playlists += playlistFromItem(item, browseId!!)
            else -> {
                val videoId = findVideoId(item) ?: return
                val videoType = findStringByKey(item, "musicVideoType")
                if (videoType == "MUSIC_VIDEO_TYPE_ATV") {
                    output.tracks += trackFromGenericItem(item, videoId)
                } else {
                    output.videos += videoFromItem(item, videoId)
                }
            }
        }
    }

    private fun parseCardItem(item: JsonObject, output: SearchOutput) {
        val browseId = primaryBrowseId(item)
        when {
            browseId.isArtistBrowseId() -> output.artists += artistFromItem(item, browseId!!)
            browseId.isAlbumBrowseId() -> output.albums += albumFromItem(item, browseId!!)
            browseId.isPlaylistBrowseId() -> output.playlists += playlistFromItem(item, browseId!!)
            else -> {
                val videoId = findVideoId(item) ?: return
                val videoType = findStringByKey(item, "musicVideoType")
                if (videoType == null || videoType == "MUSIC_VIDEO_TYPE_ATV") {
                    output.tracks += trackFromGenericItem(item, videoId)
                } else {
                    output.videos += videoFromItem(item, videoId)
                }
            }
        }
    }

    private fun trackFromResponsiveItem(item: JsonObject, videoId: String): MusicTrack {
        val columns = item.array("flexColumns")
        val titleRuns = columns.getOrNull(0)?.asObject()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
            .orEmpty().map(::runInfo)
        val metadataRuns = columns.drop(1).flatMap { column ->
            column.asObject().obj("musicResponsiveListItemFlexColumnRenderer")
                ?.obj("text")?.array("runs").orEmpty().map(::runInfo)
        }
        return trackFromRuns(
            videoId = videoId,
            title = titleRuns.firstOrNull()?.text.orEmpty().ifBlank { videoId },
            runs = metadataRuns,
            cover = thumbnailUrl(item),
        )
    }

    private fun trackFromGenericItem(item: JsonObject, videoId: String): MusicTrack {
        val title = titleText(item).ifBlank { videoId }
        val runs = collectTextRuns(item)
        return trackFromRuns(videoId, title, runs, thumbnailUrl(item))
    }

    private fun trackFromRuns(
        videoId: String,
        title: String,
        runs: List<RunInfo>,
        cover: String?,
    ): MusicTrack {
        val artistRuns = runs.filter { it.browseId.isArtistBrowseId() }
        val albumRun = runs.firstOrNull { it.browseId.isAlbumBrowseId() }
        val metadataTexts = runs.map { it.text.trim() }
            .filter { it.isNotBlank() && it != "•" }
        val artists = artistRuns.map { it.text.trim() }
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" / ")
            .ifBlank {
                metadataTexts.firstOrNull { text ->
                    !isTypeLabel(text) && parseDurationMillis(text) == null && text.toIntOrNull() == null
                }.orEmpty()
            }
        val artistItems = artistRuns.mapNotNull { run ->
            val browseId = run.browseId ?: return@mapNotNull null
            ProviderMediaItem(
                id = mediaItemKey(ProviderMediaItemType.Artist, ID, browseId),
                title = run.text,
                providerId = ID,
                providerName = NAME,
                type = ProviderMediaItemType.Artist,
                providerUrl = "https://music.youtube.com/channel/$browseId",
            )
        }.distinctBy { it.id }
        val albumBrowseId = albumRun?.browseId
        return MusicTrack(
            id = trackKey(ID, videoId),
            title = title,
            artists = artists,
            album = albumRun?.text.orEmpty(),
            source = ID,
            sourceType = TrackSourceType.Provider,
            coverUrl = cover ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            durationMs = metadataTexts.asSequence().mapNotNull(::parseDurationMillis).firstOrNull(),
            providerId = trackKey(ID, videoId),
            providerName = NAME,
            artistItemId = artistRuns.firstOrNull()?.browseId?.let {
                mediaItemKey(ProviderMediaItemType.Artist, ID, it)
            },
            albumItemId = albumBrowseId?.let { mediaItemKey(ProviderMediaItemType.Album, ID, it) },
            artistItems = artistItems,
            providerUrl = "https://music.youtube.com/watch?v=$videoId",
        )
    }

    private fun artistFromItem(item: JsonObject, browseId: String): ProviderMediaItem = ProviderMediaItem(
        id = mediaItemKey(ProviderMediaItemType.Artist, ID, browseId),
        title = titleText(item).ifBlank { browseId },
        providerId = ID,
        providerName = NAME,
        type = ProviderMediaItemType.Artist,
        coverUrl = thumbnailUrl(item),
        providerUrl = "https://music.youtube.com/channel/$browseId",
    )

    private fun albumFromItem(item: JsonObject, browseId: String): ProviderMediaItem = ProviderMediaItem(
        id = mediaItemKey(ProviderMediaItemType.Album, ID, browseId),
        title = titleText(item).ifBlank { browseId },
        providerId = ID,
        providerName = NAME,
        type = ProviderMediaItemType.Album,
        coverUrl = thumbnailUrl(item),
        providerUrl = "https://music.youtube.com/browse/$browseId",
    )

    private fun playlistFromItem(item: JsonObject, browseId: String): ProviderPlaylist {
        val playlistId = browseId.removePrefix("VL")
        val count = collectTextRuns(item).asSequence()
            .map { it.text.trim() }
            .firstOrNull { text ->
                val normalized = text.lowercase()
                "song" in normalized || "首" in text
            }
            ?.filter(Char::isDigit)
            ?.takeIf(String::isNotBlank)
            ?.toIntOrNull()
        return ProviderPlaylist(
            id = playlistKey(ID, browseId),
            title = titleText(item).ifBlank { browseId },
            providerId = ID,
            providerName = NAME,
            coverUrl = thumbnailUrl(item),
            providerUrl = "https://music.youtube.com/playlist?list=$playlistId",
            trackCount = count,
        )
    }

    private fun videoFromItem(item: JsonObject, videoId: String): ProviderVideo {
        val runs = collectTextRuns(item)
        val artists = runs.filter { it.browseId.isArtistBrowseId() }
            .map { it.text.trim() }
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" / ")
        return ProviderVideo(
            id = videoKey(ID, videoId),
            title = titleText(item).ifBlank { videoId },
            artists = artists,
            providerId = ID,
            providerName = NAME,
            coverUrl = thumbnailUrl(item) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            durationMs = runs.asSequence().map { it.text }.mapNotNull(::parseDurationMillis).firstOrNull(),
            providerUrl = "https://music.youtube.com/watch?v=$videoId",
        )
    }

    private fun collectPlaylistPanelTracks(element: JsonElement, output: MutableList<MusicTrack>) {
        when (element) {
            is JsonObject -> {
                element.obj("playlistPanelVideoRenderer")?.let { item ->
                    val videoId = item.stringOrNull("videoId") ?: findVideoId(item)
                    if (!videoId.isNullOrBlank()) {
                        val bylineRuns = listOf("longBylineText", "shortBylineText")
                            .asSequence()
                            .mapNotNull(item::obj)
                            .flatMap { it.array("runs").asSequence() }
                            .map(::runInfo)
                            .toList()
                        output += trackFromRuns(
                            videoId = videoId,
                            title = titleText(item).ifBlank { videoId },
                            runs = bylineRuns,
                            cover = thumbnailUrl(item),
                        ).copy(
                            durationMs = item.obj("lengthText")?.array("runs")
                                ?.firstOrNull()?.asObject()?.stringOrNull("text")
                                ?.let(::parseDurationMillis),
                        )
                    }
                }
                element.forEach { (_, value) -> collectPlaylistPanelTracks(value, output) }
            }
            is JsonArray -> element.forEach { collectPlaylistPanelTracks(it, output) }
            else -> Unit
        }
    }

    private fun mediaHeader(root: JsonObject, type: ProviderMediaItemType): JsonObject? {
        val preferred = if (type == ProviderMediaItemType.Artist) {
            listOf("musicImmersiveHeaderRenderer", "musicVisualHeaderRenderer", "musicResponsiveHeaderRenderer")
        } else {
            listOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "musicImmersiveHeaderRenderer")
        }
        return preferred.firstNotNullOfOrNull { findObjectByRenderer(root, it) }
    }

    private fun findDescription(root: JsonObject): String {
        val shelf = findObjectByRenderer(root, "musicDescriptionShelfRenderer")
        return shelf?.obj("description")?.array("runs")
            ?.joinToString("") { it.asObject().string("text") }
            .orEmpty()
    }

    private fun timedLyricsToLrc(root: JsonObject): String? {
        val lyricsData = root.obj("contents")
            ?.obj("elementRenderer")
            ?.obj("newElement")
            ?.obj("type")
            ?.obj("componentType")
            ?.obj("model")
            ?.obj("timedLyricsModel")
            ?.obj("lyricsData")
            ?: return null
        val lines = lyricsData.array("timedLyricsData").mapNotNull { value ->
            val item = value.asObject()
            val text = item.stringOrNull("lyricLine") ?: return@mapNotNull null
            val cue = item.obj("cueRange") ?: return@mapNotNull null
            val startMs = cue.long("startTimeMilliseconds")
                ?: cue.string("startTimeMilliseconds").toLongOrNull()
                ?: return@mapNotNull null
            "${lrcTimestamp(startMs)}$text"
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun plainLyrics(root: JsonObject): String? {
        val shelf = findObjectByRenderer(root, "musicDescriptionShelfRenderer") ?: return null
        val text = shelf.obj("description")?.array("runs")
            ?.joinToString("") { it.asObject().string("text") }
            .orEmpty()
        return text.takeIf(String::isNotBlank)
    }

    private fun lrcTimestamp(milliseconds: Long): String {
        val normalized = milliseconds.coerceAtLeast(0)
        val minutes = normalized / 60_000
        val seconds = (normalized % 60_000) / 1_000
        val centiseconds = (normalized % 1_000) / 10
        return "[${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}.${centiseconds.toString().padStart(2, '0')}]"
    }

    private fun watchPayload(videoId: String, radio: Boolean): String = buildString {
        append('{')
        append("\"enablePersistentPlaylistPanel\":true,")
        append("\"isAudioOnly\":true,")
        append("\"tunerSettingValue\":\"AUTOMIX_SETTING_NORMAL\",")
        append("\"videoId\":")
        append(quote(videoId))
        append(',')
        append("\"playlistId\":")
        append(quote("RDAMVM$videoId"))
        if (radio) {
            append(",\"params\":\"wAEB\"")
        } else {
            append(",\"watchEndpointMusicSupportedConfigs\":{")
            append("\"watchEndpointMusicConfig\":{")
            append("\"hasPersistentPlaylistPanel\":true,")
            append("\"musicVideoType\":\"MUSIC_VIDEO_TYPE_ATV\"")
            append("}}")
        }
        append('}')
    }

    private fun titleText(item: JsonObject): String {
        item.obj("title")?.array("runs")?.firstOrNull()?.asObject()?.stringOrNull("text")?.let { return it }
        item.array("flexColumns").firstOrNull()?.asObject()
            ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
            ?.firstOrNull()?.asObject()?.stringOrNull("text")?.let { return it }
        return ""
    }

    private fun runInfo(element: JsonElement): RunInfo {
        val run = element.asObject()
        return RunInfo(
            text = run.string("text"),
            browseId = run.obj("navigationEndpoint")?.obj("browseEndpoint")?.stringOrNull("browseId"),
        )
    }

    private fun collectTextRuns(item: JsonObject): List<RunInfo> {
        val output = mutableListOf<RunInfo>()
        fun visit(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    element.array("runs").takeIf { it.isNotEmpty() }?.forEach { run ->
                        run.asObject().stringOrNull("text")?.let { output += runInfo(run) }
                    }
                    element.forEach { (key, value) -> if (key != "runs") visit(value) }
                }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }
        visit(item)
        return output.distinctBy { it.text to it.browseId }
    }

    private fun primaryBrowseId(item: JsonObject): String? =
        item.obj("navigationEndpoint")?.obj("browseEndpoint")?.stringOrNull("browseId")
            ?: item.obj("onTap")?.obj("browseEndpoint")?.stringOrNull("browseId")
            ?: item.obj("title")?.array("runs")?.firstOrNull()?.asObject()
                ?.obj("navigationEndpoint")?.obj("browseEndpoint")?.stringOrNull("browseId")
            ?: item.array("flexColumns").firstOrNull()?.asObject()
                ?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")
                ?.firstOrNull()?.asObject()?.obj("navigationEndpoint")?.obj("browseEndpoint")
                ?.stringOrNull("browseId")

    private fun findVideoId(element: JsonElement): String? {
        when (element) {
            is JsonObject -> {
                element.obj("watchEndpoint")?.stringOrNull("videoId")?.let { return it }
                element.stringOrNull("videoId")?.let { return it }
                element.forEach { (_, value) -> findVideoId(value)?.let { return it } }
            }
            is JsonArray -> element.forEach { findVideoId(it)?.let { id -> return id } }
            else -> Unit
        }
        return null
    }

    private fun thumbnailUrl(element: JsonElement): String? {
        when (element) {
            is JsonObject -> {
                val thumbnails = element.array("thumbnails")
                thumbnails.lastOrNull()?.asObject()?.stringOrNull("url")?.let { return it }
                element.forEach { (_, value) -> thumbnailUrl(value)?.let { return it } }
            }
            is JsonArray -> element.forEach { thumbnailUrl(it)?.let { url -> return url } }
            else -> Unit
        }
        return null
    }

    private fun findStringByKey(element: JsonElement, key: String): String? {
        when (element) {
            is JsonObject -> {
                (element[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let { return it }
                element.forEach { (_, value) -> findStringByKey(value, key)?.let { return it } }
            }
            is JsonArray -> element.forEach { findStringByKey(it, key)?.let { value -> return value } }
            else -> Unit
        }
        return null
    }

    private fun findObjectByRenderer(element: JsonElement, key: String): JsonObject? {
        when (element) {
            is JsonObject -> {
                element.obj(key)?.let { return it }
                element.forEach { (_, value) -> findObjectByRenderer(value, key)?.let { return it } }
            }
            is JsonArray -> element.forEach { findObjectByRenderer(it, key)?.let { value -> return value } }
            else -> Unit
        }
        return null
    }

    private fun findBrowseIdForPageType(element: JsonElement, pageType: String): String? {
        when (element) {
            is JsonObject -> {
                element.obj("browseEndpoint")?.let { endpoint ->
                    if (findStringByKey(endpoint, "pageType") == pageType) {
                        endpoint.stringOrNull("browseId")?.let { return it }
                    }
                }
                element.forEach { (_, value) -> findBrowseIdForPageType(value, pageType)?.let { return it } }
            }
            is JsonArray -> element.forEach { findBrowseIdForPageType(it, pageType)?.let { value -> return value } }
            else -> Unit
        }
        return null
    }

    private fun findBrowseIdWithPrefix(element: JsonElement, prefix: String): String? {
        when (element) {
            is JsonObject -> {
                element.stringOrNull("browseId")?.takeIf { it.startsWith(prefix) }?.let { return it }
                element.forEach { (_, value) -> findBrowseIdWithPrefix(value, prefix)?.let { return it } }
            }
            is JsonArray -> element.forEach { findBrowseIdWithPrefix(it, prefix)?.let { value -> return value } }
            else -> Unit
        }
        return null
    }

    private fun String?.isArtistBrowseId(): Boolean =
        this?.let { it.startsWith("UC") || it.startsWith("MPLA") } == true

    private fun String?.isAlbumBrowseId(): Boolean = this?.startsWith("MPRE") == true

    private fun String?.isPlaylistBrowseId(): Boolean = this?.let {
        it.startsWith("VL") || it.startsWith("RD") || it.startsWith("VM") || it.startsWith("PL")
    } == true

    private fun isTypeLabel(value: String): Boolean {
        val normalized = value.trim().lowercase()
        return normalized in setOf(
            "song", "songs", "video", "videos", "album", "single", "ep", "playlist",
            "歌曲", "视频", "专辑", "单曲", "歌单",
        )
    }

    private fun parseDurationMillis(value: String): Long? {
        val parts = value.trim().split(':')
        if (parts.size !in 2..3 || parts.any { it.isBlank() || it.any { char -> !char.isDigit() } }) return null
        val seconds = when (parts.size) {
            2 -> (parts[0].toLongOrNull() ?: return null) * 60 + (parts[1].toLongOrNull() ?: return null)
            3 -> (parts[0].toLongOrNull() ?: return null) * 3_600 +
                (parts[1].toLongOrNull() ?: return null) * 60 +
                (parts[2].toLongOrNull() ?: return null)
            else -> return null
        }
        return seconds * 1_000
    }

    private fun rawTrackId(track: MusicTrack): String {
        val (_, identifier) = splitResourceId(track.providerId ?: track.id)
        return identifier.ifBlank { track.id.substringAfterLast(':') }
    }

    private fun quote(value: String): String = providerJson.encodeToString(
        kotlinx.serialization.json.JsonPrimitive.serializer(),
        kotlinx.serialization.json.JsonPrimitive(value),
    )

    private companion object {
        const val ID = YtMusicProvider.ID
        const val NAME = YtMusicProvider.NAME
    }
}
