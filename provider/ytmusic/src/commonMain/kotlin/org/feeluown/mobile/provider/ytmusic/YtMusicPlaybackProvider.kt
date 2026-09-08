package org.feeluown.mobile.provider.ytmusic

import kotlinx.serialization.json.JsonObject
import org.feeluown.mobile.AudioQualityPolicy
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.ProviderVideo
import org.feeluown.mobile.VideoPlaybackPayload
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
import org.feeluown.mobile.provider.core.network.ProviderRequestKind

/**
 * Playback-only YouTube extractor modelled after yt-dlp's current YouTube pipeline.
 *
 * Account/catalogue concerns deliberately remain in [YtMusicProvider] and
 * [YtMusicContentProvider]. This class owns the volatile playback protocol:
 * client selection, player responses, signature/n challenges, PO-token policy,
 * SABR avoidance, HLS fallback and final CDN probing.
 */
internal class YtMusicPlaybackProvider(
    private val delegate: KotlinMusicProvider,
    http: ProviderHttpClient,
    credentials: ProviderCredentialStore,
    poTokenProvider: YtPoTokenProvider = CredentialYtPoTokenProvider(credentials),
) : KotlinMusicProvider by delegate {
    private val extractor = YtMusicPlaybackExtractor(http, credentials, poTokenProvider)

    override suspend fun resolve(track: MusicTrack, qualityPolicy: String): PlaybackPayload? =
        extractor.resolve(track, qualityPolicy)

    override suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload {
        val videoId = splitResourceId(video.id, "video").second
        val track = delegate.trackDetail(videoId) ?: return VideoPlaybackPayload(video = video)
        val payload = resolve(track, AudioQualityPolicy.High.policy)
            ?: return VideoPlaybackPayload(video = video)
        return VideoPlaybackPayload(
            video = video,
            url = payload.url,
            audioUrl = payload.url,
            headers = payload.headers,
            quality = payload.audioQuality,
        )
    }
}

internal class YtMusicPlaybackExtractor(
    private val http: ProviderHttpClient,
    private val credentials: ProviderCredentialStore,
    private val poTokenProvider: YtPoTokenProvider,
    private val challengeSolver: YtMusicJsChallengeSolver = YtMusicJsChallengeSolver(),
) {
    private data class PlayerSession(
        val videoId: String,
        val apiKey: String,
        val visitorData: String?,
        val playerJsUrl: String?,
        val playerJs: String?,
        val signatureTimestamp: Int?,
        val cookie: String,
        val hasBrowserSession: Boolean,
    )

    private data class PlayerResponse(
        val client: YtPlayerClientProfile,
        val root: JsonObject,
        val playerToken: String?,
    )

    private data class Candidate(
        val url: String,
        val bitrate: Int,
        val mimeType: String,
        val durationMs: Long?,
        val audioQuality: String?,
        val client: YtPlayerClientProfile,
        val manifest: Boolean = false,
    )

    suspend fun resolve(track: MusicTrack, qualityPolicy: String): PlaybackPayload? {
        val videoId = splitResourceId(track.providerId ?: track.id).second
            .ifBlank { track.providerId ?: track.id }
        if (videoId.isBlank()) return null

        val session = createSession(videoId)
        val clients = YtMusicPlayerClients.ordered(session.hasBrowserSession)
        for (client in clients) {
            val response = fetchPlayer(session, client) ?: continue
            if (!isPlayable(response.root)) continue
            val candidates = extractCandidates(session, response)
            for (candidate in selectCandidates(candidates, qualityPolicy)) {
                if (!probe(candidate)) continue
                return PlaybackPayload(
                    url = candidate.url,
                    title = track.title,
                    artists = track.artists,
                    album = track.album,
                    source = YtMusicProvider.ID,
                    // FeelUOwn/yt-dlp intentionally hand the signed media URL to
                    // the media stack without copying InnerTube request headers.
                    headers = emptyMap(),
                    coverUrl = track.coverUrl,
                    durationMs = candidate.durationMs ?: track.durationMs,
                    audioQuality = candidate.audioQuality
                        ?: candidate.bitrate.takeIf { it > 0 }?.toString()
                        ?: if (candidate.manifest) "HLS" else null,
                    providerName = YtMusicProvider.NAME,
                )
            }
        }
        return null
    }

    private suspend fun createSession(videoId: String): PlayerSession {
        val stored = credentials.read(YtMusicProvider.ID)
        val cookie = cookieHeader(stored)
        val hasBrowserSession = cookie.isNotBlank()
        val watchHeaders = buildMap {
            put("User-Agent", YtMusicOAuth.USER_AGENT)
            put("Accept", "text/html,*/*")
            put("Accept-Language", "en-US,en;q=0.9")
            put("Cookie", mergeCookies("SOCS=CAI", cookie))
        }
        val html = runCatching {
            http.getText(
                providerId = YtMusicProvider.ID,
                url = "https://www.youtube.com/watch?v=$videoId&bpctr=9999999999&has_verified=1",
                headers = watchHeaders,
                kind = ProviderRequestKind.SafeRead,
                cacheKey = null,
                cachePolicy = ProviderCachePolicies.none,
            ).value
        }.getOrDefault("")

        val apiKey = extractConfigValue(html, "INNERTUBE_API_KEY")
            ?: YtMusicProvider.FALLBACK_API_KEY
        val visitor = extractConfigValue(html, "VISITOR_DATA")
        val jsUrl = extractPlayerJsUrl(html)
        val playerJs = jsUrl?.let { url ->
            runCatching {
                http.getText(
                    providerId = YtMusicProvider.ID,
                    url = absolutePlayerJsUrl(url),
                    headers = mapOf("User-Agent" to YtMusicOAuth.USER_AGENT, "Accept" to "*/*"),
                    cacheKey = "ytmusic:playback:playerjs:${playerJsId(url)}",
                    cachePolicy = ProviderCachePolicies.detail,
                ).value
            }.getOrNull()
        }
        return PlayerSession(
            videoId = videoId,
            apiKey = apiKey,
            visitorData = visitor,
            playerJsUrl = jsUrl,
            playerJs = playerJs,
            signatureTimestamp = playerJs?.let(challengeSolver::signatureTimestamp),
            cookie = cookie,
            hasBrowserSession = hasBrowserSession,
        )
    }

    private suspend fun fetchPlayer(
        session: PlayerSession,
        client: YtPlayerClientProfile,
    ): PlayerResponse? {
        if (client.requireAuth && !session.hasBrowserSession) return null

        val playerToken = if (client.playerPoTokenRecommended) {
            poTokenProvider.token(
                YtPoTokenRequest(
                    context = YtPoTokenContext.Player,
                    client = client,
                    videoId = session.videoId,
                    visitorData = session.visitorData,
                ),
            )
        } else null

        val body = playerBody(session, client, playerToken)
        val origin = "https://${client.host}"
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("User-Agent", client.userAgent)
            put("Origin", origin)
            put("X-Youtube-Client-Name", client.clientId)
            put("X-Youtube-Client-Version", client.clientVersion)
            session.visitorData?.takeIf { it.isNotBlank() }?.let {
                put("X-Goog-Visitor-Id", it)
            }
            if (client.supportsCookies && session.cookie.isNotBlank()) {
                put("Cookie", mergeCookies("SOCS=CAI", session.cookie))
                YtMusicProvider.sapisidFromCookie(session.cookie)?.let { sapisid ->
                    put("Authorization", YtMusicProvider.sapisidHashAuthorization(sapisid, origin))
                    put("X-Origin", origin)
                }
            }
        }
        val url = buildString {
            append("https://")
            append(client.host)
            append("/youtubei/v1/player?prettyPrint=false&key=")
            append(encodeUrlComponent(session.apiKey))
        }
        val root = runCatching {
            http.postJson(
                providerId = YtMusicProvider.ID,
                url = url,
                json = body,
                headers = headers,
                kind = ProviderRequestKind.SafeRead,
                cacheKey = null,
                cachePolicy = ProviderCachePolicies.none,
            ).value.let { providerJson.parseToJsonElement(it).asObject() }
        }.getOrNull() ?: return null
        return PlayerResponse(client, root, playerToken)
    }

    private fun playerBody(
        session: PlayerSession,
        client: YtPlayerClientProfile,
        playerToken: String?,
    ): String = buildString {
        append("{\"context\":{\"client\":{")
        append("\"clientName\":").append(quote(client.clientName)).append(',')
        append("\"clientVersion\":").append(quote(client.clientVersion)).append(',')
        append("\"userAgent\":").append(quote(client.userAgent)).append(',')
        append("\"hl\":\"en\",\"timeZone\":\"UTC\",\"utcOffsetMinutes\":0")
        client.deviceMake?.let { append(",\"deviceMake\":").append(quote(it)) }
        client.deviceModel?.let { append(",\"deviceModel\":").append(quote(it)) }
        client.osName?.let { append(",\"osName\":").append(quote(it)) }
        client.osVersion?.let { append(",\"osVersion\":").append(quote(it)) }
        client.androidSdkVersion?.let { append(",\"androidSdkVersion\":").append(it) }
        append("},\"user\":{}")
        if (client.embedded) {
            append(",\"thirdParty\":{\"embedUrl\":\"https://www.reddit.com/\"}")
        }
        append("},\"videoId\":").append(quote(session.videoId))
        append(",\"playbackContext\":{\"contentPlaybackContext\":{")
        append("\"html5Preference\":\"HTML5_PREF_WANTS\"")
        if (client.requireJsPlayer) {
            session.signatureTimestamp?.let { append(",\"signatureTimestamp\":").append(it) }
        }
        append("}}")
        append(",\"contentCheckOk\":true,\"racyCheckOk\":true")
        if (!playerToken.isNullOrBlank()) {
            append(",\"serviceIntegrityDimensions\":{\"poToken\":")
                .append(quote(playerToken)).append('}')
        }
        append('}')
    }

    private fun isPlayable(root: JsonObject): Boolean {
        val status = root.obj("playabilityStatus")?.stringOrNull("status")
        return status == null || status == "OK"
    }

    private suspend fun extractCandidates(
        session: PlayerSession,
        response: PlayerResponse,
    ): List<Candidate> {
        val streaming = response.root.obj("streamingData") ?: return emptyList()
        val output = mutableListOf<Candidate>()
        val formats = buildList {
            addAll(streaming.array("adaptiveFormats"))
            addAll(streaming.array("formats"))
        }.map { it.asObject() }

        for (format in formats) {
            val mime = format.string("mimeType")
            // Audio-only formats are preferred for a music player. Muxed formats
            // are left to HLS fallback instead of downloading unnecessary video.
            if (!mime.startsWith("audio/")) continue
            val baseUrl = format.stringOrNull("url")
                ?: decipherCipher(session, format.stringOrNull("signatureCipher") ?: format.stringOrNull("cipher"))
                ?: continue // Missing URL/cipher is commonly a SABR-only response.
            var url = solveN(session, baseUrl) ?: continue
            val policy = response.client.policy(YtStreamingProtocol.Https)
            val tokenRequired = policy.required &&
                !(policy.notRequiredWithPlayerToken && !response.playerToken.isNullOrBlank())
            val gvsToken = if (policy.required || policy.recommended) {
                poTokenProvider.token(
                    YtPoTokenRequest(
                        context = YtPoTokenContext.Gvs,
                        client = response.client,
                        videoId = session.videoId,
                        visitorData = session.visitorData,
                    ),
                )
            } else null
            if (tokenRequired && gvsToken.isNullOrBlank()) continue
            if (!gvsToken.isNullOrBlank()) url = appendQuery(url, "pot", gvsToken)
            output += Candidate(
                url = url,
                bitrate = format.int("bitrate") ?: 0,
                mimeType = mime,
                durationMs = format.long("approxDurationMs"),
                audioQuality = format.stringOrNull("audioQuality"),
                client = response.client,
            )
        }

        streaming.stringOrNull("hlsManifestUrl")?.let { manifest ->
            val policy = response.client.policy(YtStreamingProtocol.Hls)
            val tokenRequired = policy.required &&
                !(policy.notRequiredWithPlayerToken && !response.playerToken.isNullOrBlank())
            val token = if (policy.required || policy.recommended) {
                poTokenProvider.token(
                    YtPoTokenRequest(
                        context = YtPoTokenContext.Gvs,
                        client = response.client,
                        videoId = session.videoId,
                        visitorData = session.visitorData,
                    ),
                )
            } else null
            if (!tokenRequired || !token.isNullOrBlank()) {
                var url = solveManifestN(session, manifest) ?: manifest
                if (!token.isNullOrBlank()) url = appendManifestPot(url, token)
                output += Candidate(
                    url = url,
                    bitrate = 0,
                    mimeType = "application/x-mpegURL",
                    durationMs = null,
                    audioQuality = "HLS",
                    client = response.client,
                    manifest = true,
                )
            }
        }
        return output.distinctBy { it.url }
    }

    private suspend fun decipherCipher(session: PlayerSession, value: String?): String? {
        if (value.isNullOrBlank()) return null
        val params = parseQuery(value)
        var url = params["url"] ?: return null
        val signature = params["s"]
        if (!signature.isNullOrBlank()) {
            val js = session.playerJs ?: return null
            val solved = challengeSolver.solveSignature(js, signature)
                ?: return null
            url = appendQuery(url, params["sp"].orEmpty().ifBlank { "sig" }, solved)
        }
        return url
    }

    private suspend fun solveN(session: PlayerSession, url: String): String? {
        val raw = Regex("""([?&])n=([^&]+)""").find(url)?.groupValues?.getOrNull(2)
            ?: return url
        val js = session.playerJs ?: return null
        val challenge = decodeUrl(raw)
        val solved = challengeSolver.solveN(js, challenge) ?: return null
        return url.replaceFirst("n=$raw", "n=${encodeUrlComponent(solved)}")
    }

    private suspend fun solveManifestN(session: PlayerSession, url: String): String? {
        val raw = Regex("""/n/([^/]+)""").find(url)?.groupValues?.getOrNull(1)
            ?: return solveN(session, url)
        val js = session.playerJs ?: return null
        val solved = challengeSolver.solveN(js, decodeUrl(raw)) ?: return null
        return url.replaceFirst("/n/$raw", "/n/${encodeUrlComponent(solved)}")
    }

    private fun selectCandidates(candidates: List<Candidate>, qualityPolicy: String): List<Candidate> {
        val direct = candidates.filterNot { it.manifest }.sortedWith(
            compareByDescending<Candidate> { it.mimeType.startsWith("audio/mp4") }
                .thenByDescending { it.bitrate },
        )
        val orderedDirect = when (qualityPolicy) {
            AudioQualityPolicy.Low.policy -> direct.sortedBy { it.bitrate }
            AudioQualityPolicy.Standard.policy -> {
                if (direct.size <= 2) direct
                else {
                    val center = direct.size / 2
                    direct.sortedBy { kotlin.math.abs(direct.indexOf(it) - center) }
                }
            }
            else -> direct
        }
        return orderedDirect + candidates.filter { it.manifest }
    }

    private suspend fun probe(candidate: Candidate): Boolean = runCatching {
        val headers = if (candidate.manifest) {
            mapOf("User-Agent" to candidate.client.userAgent, "Accept" to "*/*")
        } else {
            mapOf(
                "User-Agent" to candidate.client.userAgent,
                "Accept" to "*/*",
                "Range" to "bytes=0-0",
            )
        }
        http.getText(
            providerId = YtMusicProvider.ID,
            url = candidate.url,
            headers = headers,
            kind = ProviderRequestKind.Media,
            cacheKey = null,
            cachePolicy = ProviderCachePolicies.none,
        )
        true
    }.getOrDefault(false)

    private fun extractConfigValue(html: String, key: String): String? =
        Regex("""["']?${Regex.escape(key)}["']?\s*[:=]\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.getOrNull(1)

    private fun extractPlayerJsUrl(html: String): String? =
        Regex("""["']jsUrl["']\s*:\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
            ?: Regex("""["']PLAYER_JS_URL["']\s*:\s*["']([^"']+)["']""")
                .find(html)?.groupValues?.getOrNull(1)?.replace("\\/", "/")
            ?: Regex("""/s/player/[^"'\s]+/base\.js""").find(html)?.value

    private fun absolutePlayerJsUrl(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        else -> "https://www.youtube.com$url"
    }

    private fun playerJsId(url: String): String =
        Regex("""/s/player/([^/]+)/""").find(url)?.groupValues?.getOrNull(1)
            ?: url.hashCode().toString()

    private fun mergeCookies(vararg values: String): String = values
        .map { it.trim().trim(';') }
        .filter { it.isNotBlank() }
        .joinToString("; ")

    private fun parseQuery(value: String): Map<String, String> = value.split('&').mapNotNull { part ->
        val index = part.indexOf('=')
        if (index <= 0) null
        else decodeUrl(part.substring(0, index)) to decodeUrl(part.substring(index + 1))
    }.toMap()

    private fun appendQuery(url: String, name: String, value: String): String {
        val separator = if ('?' in url) '&' else '?'
        return "$url$separator${encodeUrlComponent(name)}=${encodeUrlComponent(value)}"
    }

    private fun appendManifestPot(url: String, token: String): String {
        val queryIndex = url.indexOf('?')
        val path = if (queryIndex >= 0) url.substring(0, queryIndex) else url
        val query = if (queryIndex >= 0) url.substring(queryIndex) else ""
        return path.trimEnd('/') + "/pot/$token" + query
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
            if (number in 0x30..0x39 || number in 0x41..0x5a || number in 0x61..0x7a ||
                number in setOf(45, 46, 95, 126)
            ) {
                append(number.toChar())
            } else {
                append('%')
                append("0123456789ABCDEF"[number ushr 4])
                append("0123456789ABCDEF"[number and 0x0f])
            }
        }
    }

    private fun quote(value: String): String = providerJson.encodeToString(
        kotlinx.serialization.json.JsonPrimitive.serializer(),
        kotlinx.serialization.json.JsonPrimitive(value),
    )
}
