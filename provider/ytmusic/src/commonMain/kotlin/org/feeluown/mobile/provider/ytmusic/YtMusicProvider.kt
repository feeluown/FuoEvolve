package org.feeluown.mobile.provider.ytmusic

import org.feeluown.mobile.AudioQualityPolicy
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.ProviderAuthState
import org.feeluown.mobile.ProviderCapabilities
import org.feeluown.mobile.ProviderContentType
import org.feeluown.mobile.ProviderContentSection
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderFeatureCategory
import org.feeluown.mobile.ProviderInfo
import org.feeluown.mobile.ProviderMediaItem
import org.feeluown.mobile.ProviderPlaylist
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.ProviderVideo
import org.feeluown.mobile.VideoPlaybackPayload
import org.feeluown.mobile.provider.core.BaseKotlinProvider
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.array
import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.cookieHeader
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.splitResourceId
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.network.ProviderCachePolicies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.currentTimeMillis

class YtMusicProvider(
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
    private var apiKey: String? = null
    private var clientVersion: String = dynamicClientVersion()
    private var visitorId: String? = null
    private var playerJsUrl: String? = null
    private var signatureTimestamp: Int? = null
    private var signatureDecipher: SignatureDecipher? = null
    private var configLoaded: Boolean = false

    override suspend fun search(keyword: String): ProviderSearchResults {
        val root = innerTube("search", "{\"query\":${quote(keyword)}}", useOAuth = false)
        val tracks = mutableListOf<org.feeluown.mobile.MusicTrack>()
        collectSearchItems(root, tracks)
        return ProviderSearchResults(tracks = tracks.distinctBy { it.id }.take(50))
    }

    override suspend fun trackDetail(identifier: String): org.feeluown.mobile.MusicTrack? {
        val (_, videoId) = splitResourceId(identifier)
        val root = player(videoId)
        val details = root.obj("videoDetails") ?: return null
        val author = details.string("author")
        val duration = details.string("lengthSeconds").toLongOrNull()?.times(1_000)
        return track(
            identifier = videoId,
            title = details.string("title"),
            artists = author,
            album = "",
            coverUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            durationMs = duration,
            providerUrl = "https://music.youtube.com/watch?v=$videoId",
        )
    }

    override suspend fun resolve(track: org.feeluown.mobile.MusicTrack, qualityPolicy: String): PlaybackPayload? {
        val (_, videoId) = splitResourceId(track.providerId ?: track.id)
        val played = playablePlayer(videoId) ?: return null
        val formats = played.root.obj("streamingData")?.array("adaptiveFormats").orEmpty()
            .map { it.asObject() }
            .filter { it.string("mimeType").startsWith("audio/") }
        val preferred = formats.filter { it.string("mimeType").startsWith("audio/mp4") }
            .ifEmpty { formats }
            .sortedByDescending { it.int("bitrate") ?: 0 }
        val selected = when (qualityPolicy) {
            AudioQualityPolicy.Low.policy -> preferred.lastOrNull()
            AudioQualityPolicy.Standard.policy -> preferred.getOrNull(preferred.size / 2) ?: preferred.lastOrNull()
            else -> preferred.firstOrNull()
        } ?: return null
        val directUrl = selected.stringOrNull("url")
            ?: decodeSignatureCipher(
                selected.stringOrNull("signatureCipher") ?: selected.stringOrNull("cipher"),
            )
            ?: return null
        return PlaybackPayload(
            url = directUrl,
            title = track.title,
            artists = track.artists,
            album = track.album,
            source = ID,
            headers = played.playbackHeaders,
            coverUrl = track.coverUrl,
            durationMs = selected.long("approxDurationMs") ?: track.durationMs,
            audioQuality = selected.stringOrNull("audioQuality") ?: selected.int("bitrate")?.toString(),
            providerName = NAME,
        )
    }

    override suspend fun trackVideo(track: org.feeluown.mobile.MusicTrack): ProviderVideo = video(
        identifier = splitResourceId(track.providerId ?: track.id).second,
        title = track.title,
        artists = track.artists,
        coverUrl = track.coverUrl,
        durationMs = track.durationMs,
        providerUrl = "https://music.youtube.com/watch?v=${splitResourceId(track.providerId ?: track.id).second}",
    )

    override suspend fun playlistDetail(playlist: ProviderPlaylist, offset: Int, limit: Int): org.feeluown.mobile.ProviderPlaylistDetail {
        val (_, playlistId) = splitResourceId(playlist.id, "playlist")
        if (ensureOAuthAccessToken() != null) {
            val page = fetchOAuthPlaylistTracks(playlistId, offset = offset, limit = limit)
            return org.feeluown.mobile.ProviderPlaylistDetail(
                playlist = playlist,
                tracks = page.tracks,
                tracksNextOffset = offset + page.tracks.size,
                tracksHasMore = page.hasMore,
            )
        }
        val root = innerTube("browse", "{\"browseId\":${quote(normalizeBrowsePlaylistId(playlistId))}}", useOAuth = false)
        val tracks = mutableListOf<org.feeluown.mobile.MusicTrack>()
        collectSearchItems(root, tracks)
        val page = tracks.drop(offset).take(limit)
        return org.feeluown.mobile.ProviderPlaylistDetail(
            playlist = playlist,
            tracks = page,
            tracksNextOffset = offset + page.size,
            tracksHasMore = tracks.size > offset + page.size,
        )
    }

    override suspend fun loadFeature(feature: ProviderFeature, offset: Int, limit: Int): ProviderContentSection {
        if (feature.id == "ytmusic_user_playlists" && !authState().isLoggedIn) {
            return ProviderContentSection(feature, isLoginRequired = true)
        }
        if (feature.id == "ytmusic_user_playlists" && ensureOAuthAccessToken() != null) {
            val playlists = fetchOAuthPlaylists()
            val page = playlists.drop(offset).take(limit)
            return ProviderContentSection(
                feature = feature,
                playlists = page,
                nextOffset = offset + page.size,
                hasMore = playlists.size > offset + page.size,
            )
        }
        val payload = when (feature.id) {
            "ytmusic_toplists" ->
                "{\"browseId\":\"FEmusic_charts\",\"formData\":{\"selectedValues\":[\"ZZ\"]}}"
            "ytmusic_user_playlists" -> "{\"browseId\":\"FEmusic_liked_playlists\"}"
            else -> "{\"browseId\":\"FEmusic_home\"}"
        }
        val root = innerTube(
            method = "browse",
            payload = payload,
            useOAuth = false,
        )
        return when (feature.contentType) {
            ProviderContentType.Playlists -> {
                val playlists = mutableListOf<ProviderPlaylist>()
                collectPlaylists(root, playlists)
                ProviderContentSection(feature, playlists = playlists.distinctBy { it.id }.drop(offset).take(limit), nextOffset = offset + limit, hasMore = playlists.size > offset + limit)
            }
            else -> {
                val tracks = mutableListOf<org.feeluown.mobile.MusicTrack>()
                collectSearchItems(root, tracks)
                ProviderContentSection(feature, tracks = tracks.distinctBy { it.id }.drop(offset).take(limit), nextOffset = offset + limit, hasMore = tracks.size > offset + limit)
            }
        }
    }

    override suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload {
        val track = trackDetail(splitResourceId(video.id, "video").second) ?: return VideoPlaybackPayload(video = video)
        val payload = resolve(track, AudioQualityPolicy.High.policy) ?: return VideoPlaybackPayload(video = video)
        return VideoPlaybackPayload(video = video, url = payload.url, audioUrl = payload.url, headers = payload.headers, quality = payload.audioQuality)
    }

    private data class PlayedStream(
        val root: kotlinx.serialization.json.JsonObject,
        val playbackHeaders: Map<String, String>,
    )

    private suspend fun playablePlayer(videoId: String): PlayedStream? {
        ensureYoutubeVisitorId()
        if (!visitorId.isNullOrBlank()) {
            val androidVr = runCatching { androidVrPlayer(videoId) }.getOrNull()
            if (androidVr != null && hasPlayableAudio(androidVr, requireDirectUrl = true)) {
                return PlayedStream(root = androidVr, playbackHeaders = emptyMap())
            }
        }
        val android = runCatching { androidPlayer(videoId) }.getOrNull()
        if (android != null && hasPlayableAudio(android, requireDirectUrl = true)) {
            return PlayedStream(root = android, playbackHeaders = emptyMap())
        }
        val web = runCatching { player(videoId) }.getOrNull()
        if (web != null && hasPlayableAudio(web, requireDirectUrl = false)) {
            return PlayedStream(
                root = web,
                playbackHeaders = mapOf(
                    "Origin" to YTM_ORIGIN,
                    "Referer" to "$YTM_ORIGIN/",
                    "User-Agent" to YtMusicOAuth.USER_AGENT,
                ),
            )
        }
        return null
    }

    private fun hasPlayableAudio(
        root: kotlinx.serialization.json.JsonObject,
        requireDirectUrl: Boolean,
    ): Boolean {
        val status = root.obj("playabilityStatus")?.stringOrNull("status")
        if (status != null && status != "OK") return false
        return root.obj("streamingData")?.array("adaptiveFormats").orEmpty()
            .map { it.asObject() }
            .any { format ->
                if (!format.string("mimeType").startsWith("audio/")) return@any false
                val hasUrl = !format.stringOrNull("url").isNullOrBlank()
                val hasCipher = !format.stringOrNull("signatureCipher").isNullOrBlank() ||
                    !format.stringOrNull("cipher").isNullOrBlank()
                if (requireDirectUrl) hasUrl else (hasUrl || hasCipher)
            }
    }

    private suspend fun player(videoId: String): kotlinx.serialization.json.JsonObject {
        ensureConfig()
        val sts = ensureSignatureTimestamp()
        return innerTube(
            "player",
            "{" +
                "\"videoId\":${quote(videoId)}," +
                "\"contentCheckOk\":true," +
                "\"racyCheckOk\":true," +
                "\"playbackContext\":{\"contentPlaybackContext\":{\"signatureTimestamp\":$sts}}" +
                "}",
            useOAuth = false,
        )
    }

    private suspend fun androidVrPlayer(videoId: String): kotlinx.serialization.json.JsonObject {
        ensureConfig()
        ensureYoutubeVisitorId()
        val visitor = visitorId?.takeIf { it.isNotBlank() }
            ?: error("ANDROID_VR player requires X-Goog-Visitor-Id")
        val sts = ensureSignatureTimestamp()
        val body =
            "{" +
                "\"context\":{\"client\":{" +
                "\"clientName\":\"ANDROID_VR\"," +
                "\"clientVersion\":\"$ANDROID_VR_CLIENT_VERSION\"," +
                "\"deviceMake\":\"Oculus\"," +
                "\"deviceModel\":\"Quest 3\"," +
                "\"androidSdkVersion\":32," +
                "\"userAgent\":${quote(ANDROID_VR_USER_AGENT)}," +
                "\"osName\":\"Android\"," +
                "\"osVersion\":\"12L\"," +
                "\"hl\":\"en\"," +
                "\"timeZone\":\"UTC\"," +
                "\"utcOffsetMinutes\":0" +
                "},\"user\":{}}," +
                "\"videoId\":${quote(videoId)}," +
                "\"playbackContext\":{\"contentPlaybackContext\":{" +
                "\"html5Preference\":\"HTML5_PREF_WANTS\"," +
                "\"signatureTimestamp\":$sts" +
                "}}," +
                "\"contentCheckOk\":true," +
                "\"racyCheckOk\":true" +
                "}"
        return http.postJson(
            providerId = ID,
            url = "$YOUTUBE_API_BASE/player?prettyPrint=false",
            json = body,
            headers = mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to ANDROID_VR_USER_AGENT,
                "Origin" to "https://www.youtube.com",
                "X-Youtube-Client-Name" to ANDROID_VR_CLIENT_NAME,
                "X-Youtube-Client-Version" to ANDROID_VR_CLIENT_VERSION,
                "X-Goog-Visitor-Id" to visitor,
            ),
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
    }

    private suspend fun androidPlayer(videoId: String): kotlinx.serialization.json.JsonObject {
        ensureConfig()
        val body =
            "{" +
                "\"context\":{\"client\":{" +
                "\"clientName\":\"ANDROID\"," +
                "\"clientVersion\":\"$ANDROID_CLIENT_VERSION\"," +
                "\"androidSdkVersion\":30," +
                "\"hl\":\"zh_CN\"" +
                "},\"user\":{}}," +
                "\"videoId\":${quote(videoId)}," +
                "\"contentCheckOk\":true," +
                "\"racyCheckOk\":true" +
                "}"
        return http.postJson(
            providerId = ID,
            url = "$YOUTUBE_API_BASE/player?prettyPrint=false",
            json = body,
            headers = mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to ANDROID_USER_AGENT,
                "Origin" to "https://www.youtube.com",
                "X-Youtube-Client-Name" to ANDROID_CLIENT_NAME,
                "X-Youtube-Client-Version" to ANDROID_CLIENT_VERSION,
            ),
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
    }

    private suspend fun ensureYoutubeVisitorId() {
        if (!visitorId.isNullOrBlank()) return
        ensureConfig()
        if (!visitorId.isNullOrBlank()) return
        val html = http.getText(
            ID,
            "https://www.youtube.com/watch?v=jNQXAC9IVRw",
            mapOf(
                "User-Agent" to YtMusicOAuth.USER_AGENT,
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9",
                "Cookie" to "SOCS=CAI",
            ),
            cacheKey = null,
            cachePolicy = ProviderCachePolicies.none,
        ).value
        visitorId = extractVisitorData(html)
        if (playerJsUrl.isNullOrBlank()) {
            playerJsUrl = extractPlayerJsUrl(html)
        }
    }

    private fun extractVisitorData(html: String): String? =
        Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""["']VISITOR_DATA["']\s*[:=]\s*["']([^"']+)["']""")
                .find(html)?.groupValues?.getOrNull(1)

    private suspend fun innerTube(
        method: String,
        payload: String,
        useOAuth: Boolean = false,
    ): kotlinx.serialization.json.JsonObject {
        ensureConfig()
        val oauthToken = if (useOAuth) ensureOAuthAccessToken() else null
        val body = if (payload.startsWith("{") && payload.endsWith("}")) {
            "{\"context\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"$clientVersion\",\"hl\":\"zh_CN\"},\"user\":{}},${payload.drop(1)}"
        } else payload
        val query = buildString {
            append("?alt=json")
            if (oauthToken == null) {
                append("&key=")
                append(apiKey.orEmpty().ifBlank { FALLBACK_API_KEY })
            }
        }
        return http.postJson(
            providerId = ID,
            url = "$API_BASE/$method$query",
            json = body,
            headers = ytMusicHeaders(oauthToken),
            cacheKey = if (method == "search") "ytmusic:search:$payload" else null,
            cachePolicy = if (method == "search") ProviderCachePolicies.search else ProviderCachePolicies.none,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
    }

    private suspend fun ytMusicHeaders(oauthToken: YtMusicOAuthToken?): Map<String, String> {
        val origin = YTM_ORIGIN
        if (oauthToken != null) {
            return buildMap {
                put("User-Agent", YtMusicOAuth.USER_AGENT)
                put("Accept", "*/*")
                put("Origin", origin)
                put("Authorization", oauthToken.asAuthorizationHeader())
                put("X-Goog-Request-Time", (currentTimeMillis() / 1_000).toString())
                visitorId?.takeIf { it.isNotBlank() }?.let { put("X-Goog-Visitor-Id", it) }
            }
        }
        val stored = currentCredentials()
        val base = authenticatedHeaders(
            mapOf(
                "Accept" to "*/*",
                "Origin" to origin,
                "Referer" to "$origin/",
            ),
        ).toMutableMap()
        visitorId?.takeIf { it.isNotBlank() }?.let { base["X-Goog-Visitor-Id"] = it }
        val cookie = cookieHeader(stored)
        val sapisid = sapisidFromCookie(cookie)
        if (!sapisid.isNullOrBlank()) {
            base["Authorization"] = sapisidHashAuthorization(sapisid, origin)
        }
        return base
    }

    private suspend fun ensureOAuthAccessToken(): YtMusicOAuthToken? {
        val stored = currentCredentials() ?: return null
        if (!stored.hasOAuthAccess()) return null
        val clientId = stored.oauthClientId.orEmpty()
        val clientSecret = stored.oauthClientSecret.orEmpty()
        require(clientId.isNotBlank() && clientSecret.isNotBlank()) {
            "YouTube Music OAuth client_id/client_secret are required to refresh tokens"
        }
        var token = YtMusicOAuthToken(
            accessToken = stored.oauthAccessToken.orEmpty(),
            refreshToken = stored.oauthRefreshToken.orEmpty(),
            scope = stored.oauthScope ?: YtMusicOAuth.SCOPE,
            tokenType = "Bearer",
            expiresAtEpochSeconds = (stored.oauthExpiresAtMillis ?: 0L) / 1_000,
            expiresInSeconds = 0,
        )
        if (token.accessToken.isNotBlank() && !token.isExpiring()) {
            return token
        }
        require(token.refreshToken.isNotBlank()) {
            "YouTube Music OAuth access token expired and refresh_token is missing"
        }
        val client = YtMusicOAuthClient(
            http = http,
            credentials = YtMusicOAuthClientCredentials(clientId, clientSecret),
        )
        token = client.refreshAccessToken(token.refreshToken)
        credentials.write(
            id,
            stored.copy(
                oauthAccessToken = token.accessToken,
                oauthRefreshToken = token.refreshToken,
                oauthExpiresAtMillis = token.expiresAtEpochSeconds * 1_000,
                oauthScope = token.scope,
            ),
        )
        return token
    }

    private suspend fun fetchOAuthPlaylists(): List<ProviderPlaylist> {
        val token = ensureOAuthAccessToken() ?: return emptyList()
        val playlists = mutableListOf<ProviderPlaylist>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append(DATA_API_BASE)
                append("/playlists?part=snippet,contentDetails&mine=true&maxResults=50")
                pageToken?.takeIf { it.isNotBlank() }?.let {
                    append("&pageToken=")
                    append(encodeUrlComponent(it))
                }
            }
            val root = dataApiGet(url, token)
            root.array("items").forEach { element ->
                val item = element.asObject()
                val playlistId = item.stringOrNull("id") ?: return@forEach
                val snippet = item.obj("snippet")
                val title = snippet?.stringOrNull("title").orEmpty()
                val cover = snippet?.obj("thumbnails")
                    ?.let { thumbs ->
                        thumbs.obj("high")?.stringOrNull("url")
                            ?: thumbs.obj("medium")?.stringOrNull("url")
                            ?: thumbs.obj("default")?.stringOrNull("url")
                    }
                val trackCount = item.obj("contentDetails")?.int("itemCount")
                playlists += playlist(
                    identifier = normalizeBrowsePlaylistId(playlistId),
                    title = title.ifBlank { playlistId },
                    coverUrl = cover,
                    trackCount = trackCount,
                    providerUrl = "https://music.youtube.com/playlist?list=$playlistId",
                )
            }
            pageToken = root.stringOrNull("nextPageToken")
        } while (!pageToken.isNullOrBlank() && playlists.size < 200)
        return playlists
    }

    private data class OAuthTrackPage(
        val tracks: List<org.feeluown.mobile.MusicTrack>,
        val hasMore: Boolean,
    )

    private suspend fun fetchOAuthPlaylistTracks(
        playlistId: String,
        offset: Int,
        limit: Int,
    ): OAuthTrackPage {
        val token = ensureOAuthAccessToken() ?: return OAuthTrackPage(emptyList(), false)
        val dataApiId = dataApiPlaylistId(playlistId)
        val tracks = mutableListOf<org.feeluown.mobile.MusicTrack>()
        var pageToken: String? = null
        var skipped = 0
        var hasMore = false
        do {
            val remaining = (offset + limit) - tracks.size - skipped
            if (remaining <= 0) {
                hasMore = !pageToken.isNullOrBlank() || tracks.size + skipped > offset + limit
                break
            }
            val pageSize = remaining.coerceIn(1, 50)
            val url = buildString {
                append(DATA_API_BASE)
                append("/playlistItems?part=snippet,contentDetails&maxResults=")
                append(pageSize)
                append("&playlistId=")
                append(encodeUrlComponent(dataApiId))
                pageToken?.takeIf { it.isNotBlank() }?.let {
                    append("&pageToken=")
                    append(encodeUrlComponent(it))
                }
            }
            val root = dataApiGet(url, token)
            val items = root.array("items")
            items.forEach { element ->
                val item = element.asObject()
                val snippet = item.obj("snippet")
                val videoId = item.obj("contentDetails")?.stringOrNull("videoId")
                    ?: snippet?.obj("resourceId")?.stringOrNull("videoId")
                    ?: return@forEach
                if (snippet?.stringOrNull("title") == "Private video" ||
                    snippet?.stringOrNull("title") == "Deleted video"
                ) {
                    return@forEach
                }
                if (skipped < offset) {
                    skipped += 1
                    return@forEach
                }
                if (tracks.size >= limit) {
                    hasMore = true
                    return@forEach
                }
                val title = snippet?.stringOrNull("title").orEmpty()
                val artists = snippet?.stringOrNull("videoOwnerChannelTitle")
                    ?: snippet?.stringOrNull("channelTitle").orEmpty()
                val cover = snippet?.obj("thumbnails")
                    ?.let { thumbs ->
                        thumbs.obj("high")?.stringOrNull("url")
                            ?: thumbs.obj("medium")?.stringOrNull("url")
                            ?: thumbs.obj("default")?.stringOrNull("url")
                    }
                    ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                tracks += track(
                    identifier = videoId,
                    title = title.ifBlank { videoId },
                    artists = artists,
                    album = "",
                    coverUrl = cover,
                    providerUrl = "https://music.youtube.com/watch?v=$videoId",
                )
            }
            pageToken = root.stringOrNull("nextPageToken")
            if (tracks.size >= limit) {
                hasMore = hasMore || !pageToken.isNullOrBlank()
                break
            }
        } while (!pageToken.isNullOrBlank())
        if (!hasMore) {
            hasMore = !pageToken.isNullOrBlank()
        }
        return OAuthTrackPage(tracks = tracks, hasMore = hasMore)
    }

    private suspend fun dataApiGet(url: String, token: YtMusicOAuthToken): kotlinx.serialization.json.JsonObject {
        return http.getText(
            providerId = ID,
            url = url,
            headers = mapOf(
                "User-Agent" to YtMusicOAuth.USER_AGENT,
                "Accept" to "application/json",
                "Authorization" to token.asAuthorizationHeader(),
            ),
            kind = org.feeluown.mobile.provider.core.network.ProviderRequestKind.SafeRead,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
    }

    private fun normalizeBrowsePlaylistId(playlistId: String): String {
        val trimmed = playlistId.trim()
        if (trimmed.startsWith("VL") || trimmed.startsWith("FE") || trimmed.startsWith("MP")) {
            return trimmed
        }
        return "VL$trimmed"
    }

    private fun dataApiPlaylistId(playlistId: String): String {
        val trimmed = playlistId.trim()
        return if (trimmed.startsWith("VL")) trimmed.removePrefix("VL") else trimmed
    }

    suspend fun beginOAuth(clientId: String, clientSecret: String): YtMusicDeviceAuthCode {
        val client = YtMusicOAuthClient(
            http = http,
            credentials = YtMusicOAuthClientCredentials(clientId.trim(), clientSecret.trim()),
        )
        return client.requestDeviceCode()
    }

    suspend fun pollOAuth(
        deviceCode: String,
        clientId: String,
        clientSecret: String,
    ): YtMusicOAuthPollResult {
        val client = YtMusicOAuthClient(
            http = http,
            credentials = YtMusicOAuthClientCredentials(clientId.trim(), clientSecret.trim()),
        )
        return client.exchangeDeviceCode(deviceCode)
    }

    suspend fun loginWithOAuthJson(oauthJson: String, clientId: String, clientSecret: String): ProviderAuthState {
        val token = YtMusicOAuth.parseOauthJson(oauthJson)
        return loginWithOAuth(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresAtMillis = token.expiresAtEpochSeconds * 1_000,
            scope = token.scope,
            clientId = clientId,
            clientSecret = clientSecret,
        )
    }

    private suspend fun ensureConfig() {
        if (configLoaded) return
        val html = http.getText(
            ID,
            YTM_ORIGIN,
            mapOf(
                "User-Agent" to YtMusicOAuth.USER_AGENT,
                "Accept" to "*/*",
                "Origin" to YTM_ORIGIN,
                "Cookie" to "SOCS=CAI",
            ),
            cacheKey = "ytmusic:landing",
            cachePolicy = ProviderCachePolicies.detail,
        ).value
        apiKey = Regex("""["']?INNERTUBE_API_KEY["']?\s*[:=]\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""["']?INNERTUBE_API_KEY["']?\s*[:=]\s*([^&"'\s,}]+)""").find(html)?.groupValues?.getOrNull(1)
            ?: FALLBACK_API_KEY
        clientVersion = Regex("""["']?INNERTUBE_CLIENT_VERSION["']?\s*[:=]\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.getOrNull(1)
            ?: dynamicClientVersion()
        visitorId = extractVisitorData(html)
        playerJsUrl = extractPlayerJsUrl(html)
        configLoaded = true
    }

    private suspend fun ensureSignatureTimestamp(): Int {
        signatureTimestamp?.let { return it }
        ensureConfig()
        val jsUrl = ensurePlayerJsUrl()
        if (!jsUrl.isNullOrBlank()) {
            val javascript = http.getText(
                ID,
                absolutePlayerJsUrl(jsUrl),
                mapOf("User-Agent" to YtMusicOAuth.USER_AGENT, "Accept" to "*/*"),
                cacheKey = "ytmusic:basejs",
                cachePolicy = ProviderCachePolicies.detail,
            ).value
            Regex("""signatureTimestamp[:=](\d+)""").find(javascript)?.groupValues?.getOrNull(1)
                ?.toIntOrNull()
                ?.let {
                    signatureTimestamp = it
                    SignatureDecipher.parse(javascript)?.also { decipher -> signatureDecipher = decipher }
                    return it
                }
        }
        val fallback = fallbackSignatureTimestamp()
        signatureTimestamp = fallback
        return fallback
    }

    private suspend fun ensurePlayerJsUrl(): String? {
        playerJsUrl?.takeIf { it.isNotBlank() }?.let { return it }
        ensureConfig()
        playerJsUrl?.takeIf { it.isNotBlank() }?.let { return it }
        val html = http.getText(
            ID,
            "https://www.youtube.com/watch?v=jNQXAC9IVRw",
            mapOf(
                "User-Agent" to YtMusicOAuth.USER_AGENT,
                "Accept" to "*/*",
                "Cookie" to "SOCS=CAI",
            ),
            cacheKey = "ytmusic:watch-jsurl",
            cachePolicy = ProviderCachePolicies.detail,
        ).value
        playerJsUrl = extractPlayerJsUrl(html)
        return playerJsUrl
    }

    private fun extractPlayerJsUrl(html: String): String? =
        Regex("""jsUrl"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
            ?: Regex("""["']jsUrl["']\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
            ?: Regex("""https://(?:music|www)\.youtube\.com/s/player/[^"'\s]+/base\.js""")
                .find(html)?.value
            ?: Regex("""/s/player/[^"'\s]+/base\.js""").find(html)?.value

    private fun absolutePlayerJsUrl(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> "https://www.youtube.com$url"
    }

    private fun fallbackSignatureTimestamp(nowMillis: Long = currentTimeMillis()): Int {
        val (year, month, day) = utcYmd(nowMillis)
        val stamp = year * 10_000 + month * 100 + day
        return stamp - 1
    }

    private fun collectSearchItems(element: kotlinx.serialization.json.JsonElement, output: MutableList<org.feeluown.mobile.MusicTrack>) {
        when (element) {
            is kotlinx.serialization.json.JsonObject -> {
                element.obj("musicResponsiveListItemRenderer")?.let { item ->
                    val videoId = item.obj("playlistItemData")?.stringOrNull("videoId") ?: item.stringOrNull("videoId")
                    if (!videoId.isNullOrBlank()) {
                        val columns = item.array("flexColumns")
                        val title = columns.firstOrNull()?.asObject()?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs")?.firstOrNull()?.asObject()?.string("text").orEmpty()
                        val metadata = columns.drop(1).flatMap { column ->
                            column.asObject().obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")?.array("runs").orEmpty()
                        }.map { it.asObject().string("text") }.filter { it.isNotBlank() }
                        output += track(
                            identifier = videoId,
                            title = title.ifBlank { videoId },
                            artists = metadata.firstOrNull().orEmpty(),
                            album = metadata.getOrNull(2).orEmpty(),
                            coverUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                            providerUrl = "https://music.youtube.com/watch?v=$videoId",
                        )
                    }
                }
                element.forEach { (_, value) -> collectSearchItems(value, output) }
            }
            is kotlinx.serialization.json.JsonArray -> element.forEach { collectSearchItems(it, output) }
            else -> Unit
        }
    }

    private fun collectPlaylists(element: kotlinx.serialization.json.JsonElement, output: MutableList<ProviderPlaylist>) {
        when (element) {
            is kotlinx.serialization.json.JsonObject -> {
                element.obj("musicTwoRowItemRenderer")?.let { item ->
                    val playlistId = item.obj("navigationEndpoint")?.obj("browseEndpoint")?.stringOrNull("browseId")
                    if (!playlistId.isNullOrBlank()) {
                        val title = item.obj("title")?.array("runs")?.firstOrNull()?.asObject()?.string("text").orEmpty()
                        val cover = item.obj("thumbnailRenderer")?.obj("musicThumbnailRenderer")
                            ?.obj("thumbnail")?.array("thumbnails")?.lastOrNull()?.asObject()?.stringOrNull("url")
                        output += playlist(
                            identifier = playlistId,
                            title = title.ifBlank { playlistId },
                            coverUrl = cover,
                            providerUrl = "https://music.youtube.com/playlist?list=$playlistId",
                        )
                    }
                }
                element.forEach { (_, value) -> collectPlaylists(value, output) }
            }
            is kotlinx.serialization.json.JsonArray -> element.forEach { collectPlaylists(it, output) }
            else -> Unit
        }
    }

    private suspend fun decodeSignatureCipher(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val params = value.split('&').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else decodeUrl(part.substring(0, index)) to decodeUrl(part.substring(index + 1))
        }.toMap()
        val url = params["url"] ?: return null
        val signature = params["s"] ?: return url
        ensureSignatureTimestamp()
        val decipher = signatureDecipher ?: loadSignatureDecipher() ?: return null
        val parameter = params["sp"].orEmpty().ifBlank { "sig" }
        val separator = if ('?' in url) '&' else '?'
        return "$url$separator${encodeUrlComponent(parameter)}=${encodeUrlComponent(decipher.decode(signature))}"
    }

    private suspend fun loadSignatureDecipher(): SignatureDecipher? {
        val url = ensurePlayerJsUrl() ?: return null
        val javascript = http.getText(ID, absolutePlayerJsUrl(url)).value
        return SignatureDecipher.parse(javascript)?.also { signatureDecipher = it }
    }

    private fun decodeUrl(value: String): String {
        val bytes = ArrayList<Byte>()
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                character == '%' && index + 2 < value.length -> {
                    val number = value.substring(index + 1, index + 3).toIntOrNull(16)
                    if (number != null) {
                        bytes += number.toByte()
                        index += 3
                        continue
                    }
                    bytes += character.code.toByte()
                }
                character == '+' -> bytes += ' '.code.toByte()
                else -> bytes += character.code.toByte()
            }
            index += 1
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun encodeUrlComponent(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val number = byte.toInt() and 0xff
            if (number in 0x30..0x39 || number in 0x41..0x5a || number in 0x61..0x7a || number in setOf(45, 46, 95, 126)) {
                append(number.toChar())
            } else {
                append('%')
                append("0123456789ABCDEF"[number ushr 4])
                append("0123456789ABCDEF"[number and 0x0f])
            }
        }
    }

    private class SignatureDecipher(
        private val operations: List<Operation>,
    ) {
        fun decode(value: String): String {
            val chars = value.toMutableList()
            operations.forEach { operation ->
                when (operation) {
                    Operation.Reverse -> chars.reverse()
                    is Operation.Splice -> repeat(operation.count.coerceAtMost(chars.size)) { chars.removeAt(0) }
                    is Operation.Swap -> if (chars.isNotEmpty()) {
                        val index = operation.index % chars.size
                        val first = chars[0]
                        chars[0] = chars[index]
                        chars[index] = first
                    }
                }
            }
            return chars.joinToString("")
        }

        companion object {
            fun parse(javascript: String): SignatureDecipher? {
                val function = Regex(
                    "(?s)(?:function(?:\\s+[\\w$]+)?\\s*\\(a\\)|[\\w$]+\\s*=\\s*function\\s*\\(a\\))\\s*\\{\\s*a\\s*=\\s*a\\.split\\(\\\"\\\"\\);(.*?);?\\s*return\\s+a\\.join\\(\\\"\\\"\\)\\s*\\}",
                ).find(javascript)?.groupValues?.getOrNull(1) ?: return null
                val helperOperations = Regex(
                    "(?s)([\\w$]+):function\\(a(?:,b)?\\)\\{([^{}]*)}",
                ).findAll(javascript).associate { match ->
                    val body = match.groupValues[2]
                    val operation = when {
                        ".reverse()" in body -> Operation.Reverse
                        ".splice(0,b)" in body -> Operation.Splice(0)
                        "b%a.length" in body -> Operation.Swap(0)
                        else -> null
                    }
                    match.groupValues[1] to operation
                }
                val operations = buildList {
                    Regex("a\\.reverse\\(\\)").findAll(function).forEach { add(Operation.Reverse) }
                    Regex("a\\.splice\\(0,(\\d+)\\)").findAll(function).forEach { add(Operation.Splice(it.groupValues[1].toInt())) }
                    Regex("([\\w$]+)\\.([\\w$]+)\\(a(?:,(\\d+))?\\)").findAll(function).forEach { match ->
                        when (helperOperations[match.groupValues[2]]) {
                            Operation.Reverse -> add(Operation.Reverse)
                            is Operation.Splice -> add(Operation.Splice(match.groupValues[3].toIntOrNull() ?: 0))
                            is Operation.Swap -> add(Operation.Swap(match.groupValues[3].toIntOrNull() ?: 0))
                            null -> Unit
                        }
                    }
                }
                return operations.takeIf { it.isNotEmpty() }?.let(::SignatureDecipher)
            }
        }

        private sealed interface Operation {
            data object Reverse : Operation
            data class Splice(val count: Int) : Operation
            data class Swap(val index: Int) : Operation
        }
    }

    private fun quote(value: String): String = providerJson.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(value))

    companion object {
        const val ID = YtMusicProviderDefinition.ID
        const val NAME = YtMusicProviderDefinition.NAME
        const val YTM_ORIGIN = YtMusicProviderDefinition.YTM_ORIGIN
        const val API_BASE = YtMusicProviderDefinition.API_BASE
        const val DATA_API_BASE = YtMusicProviderDefinition.DATA_API_BASE
        const val YOUTUBE_API_BASE = YtMusicProviderDefinition.YOUTUBE_API_BASE
        const val FALLBACK_API_KEY = YtMusicProviderDefinition.FALLBACK_API_KEY
        const val ANDROID_VR_CLIENT_NAME = YtMusicProviderDefinition.ANDROID_VR_CLIENT_NAME
        const val ANDROID_VR_CLIENT_VERSION = YtMusicProviderDefinition.ANDROID_VR_CLIENT_VERSION
        const val ANDROID_VR_USER_AGENT = YtMusicProviderDefinition.ANDROID_VR_USER_AGENT
        const val ANDROID_CLIENT_NAME = YtMusicProviderDefinition.ANDROID_CLIENT_NAME
        const val ANDROID_CLIENT_VERSION = YtMusicProviderDefinition.ANDROID_CLIENT_VERSION
        const val ANDROID_USER_AGENT = YtMusicProviderDefinition.ANDROID_USER_AGENT

        val INFO: ProviderInfo get() = YtMusicProviderDefinition.info
        val CAPABILITIES: ProviderCapabilities get() = YtMusicProviderDefinition.capabilities
        val FEATURES: List<ProviderFeature> get() = YtMusicProviderDefinition.features

        fun dynamicClientVersion(nowMillis: Long = currentTimeMillis()): String =
            YtMusicProviderDefinition.dynamicClientVersion(nowMillis)

        fun sapisidFromCookie(cookie: String): String? = YtMusicProviderDefinition.sapisidFromCookie(cookie)

        fun sapisidHashAuthorization(
            sapisid: String,
            origin: String,
            nowMillis: Long = currentTimeMillis(),
        ): String = YtMusicProviderDefinition.sapisidHashAuthorization(sapisid, origin, nowMillis)

        fun sha1Hex(value: String): String = YtMusicProviderDefinition.sha1Hex(value)
        fun sha1(message: ByteArray): ByteArray = YtMusicProviderDefinition.sha1(message)
    }

    private fun utcYmd(epochMillis: Long): Triple<Int, Int, Int> {
        val version = YtMusicProviderDefinition.dynamicClientVersion(epochMillis)
        val date = version.substringAfter("1.").substringBefore(".01.00")
        return Triple(
            date.substring(0, 4).toInt(),
            date.substring(4, 6).toInt(),
            date.substring(6, 8).toInt(),
        )
    }
}
