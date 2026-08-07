package org.feeluown.mobile.provider.ytmusic

import org.feeluown.mobile.AudioQualityPolicy
import org.feeluown.mobile.PlaybackPayload
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
    private var signatureDecipher: SignatureDecipher? = null
    private var configLoaded: Boolean = false

    override suspend fun search(keyword: String): ProviderSearchResults {
        val root = innerTube("search", "{\"query\":${quote(keyword)}}")
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
        val root = player(videoId)
        val formats = root.obj("streamingData")?.array("adaptiveFormats").orEmpty()
            .map { it.asObject() }
            .filter { it.string("mimeType").startsWith("audio/") }
            .sortedByDescending { it.int("bitrate") ?: 0 }
        val selected = when (qualityPolicy) {
            AudioQualityPolicy.Low.policy -> formats.lastOrNull()
            AudioQualityPolicy.Standard.policy -> formats.getOrNull(formats.size / 2) ?: formats.lastOrNull()
            else -> formats.firstOrNull()
        } ?: return null
        val directUrl = selected.stringOrNull("url")
            ?: decodeSignatureCipher(selected.stringOrNull("signatureCipher"))
            ?: return null
        return PlaybackPayload(
            url = directUrl,
            title = track.title,
            artists = track.artists,
            album = track.album,
            source = ID,
            headers = mapOf("Origin" to "https://music.youtube.com", "Referer" to "https://music.youtube.com/"),
            coverUrl = track.coverUrl,
            durationMs = selected.long("approxDurationMs") ?: track.durationMs,
            audioQuality = selected.int("bitrate")?.toString(),
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
        val root = innerTube("browse", "{\"browseId\":${quote(playlistId)}}")
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
        val browseId = when (feature.id) {
            "ytmusic_toplists" -> "FEmusic_charts"
            "ytmusic_user_playlists" -> "FEmusic_liked"
            else -> "FEmusic_home"
        }
        val root = innerTube("browse", "{\"browseId\":${quote(browseId)}}")
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

    private suspend fun player(videoId: String): kotlinx.serialization.json.JsonObject = innerTube(
        "player",
        "{\"videoId\":${quote(videoId)},\"contentCheckOk\":true,\"racyCheckOk\":true}",
    )

    private suspend fun innerTube(method: String, payload: String): kotlinx.serialization.json.JsonObject {
        ensureConfig()
        // Match ytmusicapi / fuo_ytmusic: WEB_REMIX with hl=zh_CN and no unsupported gl=CN.
        // YouTube Music rejects gl=CN with HTTP 400 INVALID_ARGUMENT on every InnerTube call.
        val body = if (payload.startsWith("{") && payload.endsWith("}")) {
            "{\"context\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"$clientVersion\",\"hl\":\"zh_CN\"},\"user\":{}},${payload.drop(1)}"
        } else payload
        val credentials = currentCredentials()
        val oauthBearer = credentials?.oauthAccessToken?.takeIf { it.isNotBlank() }
        // ytmusicapi only appends the WEB InnerTube key for browser/cookie auth.
        // Mixing ?key= (WEB project) with an OAuth Bearer from another Google client yields 400.
        val query = buildString {
            append("?alt=json")
            if (oauthBearer == null) {
                append("&key=")
                append(apiKey.orEmpty().ifBlank { FALLBACK_API_KEY })
            }
        }
        return http.postJson(
            providerId = ID,
            url = "$API_BASE/$method$query",
            json = body,
            headers = ytMusicHeaders(),
            cacheKey = if (method == "search") "ytmusic:search:$payload" else null,
            cachePolicy = if (method == "search") ProviderCachePolicies.search else ProviderCachePolicies.none,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
    }

    private suspend fun ytMusicHeaders(): Map<String, String> {
        val stored = currentCredentials()
        val origin = YTM_ORIGIN
        val base = authenticatedHeaders(
            mapOf(
                "Accept" to "*/*",
                "Origin" to origin,
                "Referer" to "$origin/",
            ),
        ).toMutableMap()
        visitorId?.takeIf { it.isNotBlank() }?.let { base["X-Goog-Visitor-Id"] = it }
        val oauthBearer = stored?.oauthAccessToken?.takeIf { it.isNotBlank() }
        if (oauthBearer != null) {
            base["Authorization"] = "Bearer $oauthBearer"
            base["X-Goog-Request-Time"] = (currentTimeMillis() / 1_000).toString()
            return base
        }
        val cookie = cookieHeader(stored)
        val sapisid = sapisidFromCookie(cookie)
        if (!sapisid.isNullOrBlank()) {
            // Prefer a fresh SAPISIDHASH (ytmusicapi) over a stale Authorization snapshot.
            base["Authorization"] = sapisidHashAuthorization(sapisid, origin)
        }
        return base
    }

    private suspend fun ensureConfig() {
        if (configLoaded) return
        val html = http.getText(
            ID,
            YTM_ORIGIN,
            authenticatedHeaders(mapOf("Origin" to YTM_ORIGIN, "Referer" to "$YTM_ORIGIN/")),
            cacheKey = "ytmusic:landing",
            cachePolicy = ProviderCachePolicies.detail,
        ).value
        apiKey = Regex("""["']?INNERTUBE_API_KEY["']?\s*[:=]\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""["']?INNERTUBE_API_KEY["']?\s*[:=]\s*([^&"'\s,}]+)""").find(html)?.groupValues?.getOrNull(1)
            ?: FALLBACK_API_KEY
        clientVersion = Regex("""["']?INNERTUBE_CLIENT_VERSION["']?\s*[:=]\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.getOrNull(1)
            ?: dynamicClientVersion()
        visitorId = Regex("""["']?VISITOR_DATA["']?\s*[:=]\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""ytcfg\.set\s*\(\s*(\{.+?\})\s*\)\s*;""").find(html)?.groupValues?.getOrNull(1)
                ?.let { blob ->
                    Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""").find(blob)?.groupValues?.getOrNull(1)
                }
        playerJsUrl = Regex("\\\"jsUrl\\\":\\\"([^\\\"]+)").find(html)?.groupValues?.getOrNull(1)
            ?.replace("\\\\/", "/")
            ?: Regex("https://music\\.youtube\\.com/s/player/[^\\\"]+/player_ias\\.vflset/[^\\\"]+/base\\.js")
                .find(html)?.value
        configLoaded = true
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
        val decipher = signatureDecipher ?: loadSignatureDecipher() ?: return null
        val parameter = params["sp"].orEmpty().ifBlank { "sig" }
        val separator = if ('?' in url) '&' else '?'
        return "$url$separator${encodeUrlComponent(parameter)}=${encodeUrlComponent(decipher.decode(signature))}"
    }

    private suspend fun loadSignatureDecipher(): SignatureDecipher? {
        val url = playerJsUrl ?: return null
        val javascript = http.getText(ID, if (url.startsWith("http")) url else "https://music.youtube.com$url").value
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
        const val ID = "ytmusic"
        const val NAME = "YouTube Music"
        const val YTM_ORIGIN = "https://music.youtube.com"
        const val API_BASE = "$YTM_ORIGIN/youtubei/v1"
        // Same WEB InnerTube key used by ytmusicapi (note casing: Xya6, not xYq6).
        const val FALLBACK_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
        val INFO = ProviderInfo(
            providerId = ID,
            providerName = NAME,
            supportedLoginModes = setOf(
                org.feeluown.mobile.ProviderLoginMode.OAuth,
                org.feeluown.mobile.ProviderLoginMode.Headers,
            ),
            oauthConfig = org.feeluown.mobile.ProviderOAuthConfig(
                scopes = listOf("https://www.googleapis.com/auth/youtube"),
            ),
        )
        val CAPABILITIES = ProviderCapabilities(providerId = ID, providerName = NAME, canAddSongToPlaylist = true)
        val FEATURES = listOf(
            ProviderFeature("ytmusic_daily_songs", ID, NAME, "每日推荐歌曲", ProviderFeatureCategory.Recommend, ProviderContentType.Songs, false),
            ProviderFeature("ytmusic_daily_playlists", ID, NAME, "推荐歌单", ProviderFeatureCategory.Recommend, ProviderContentType.Playlists, false),
            ProviderFeature("ytmusic_toplists", ID, NAME, "排行榜", ProviderFeatureCategory.Music, ProviderContentType.Playlists, false),
            ProviderFeature("ytmusic_user_playlists", ID, NAME, "我的歌单", ProviderFeatureCategory.MinePlaylists, ProviderContentType.Playlists, true),
        )

        /** ytmusicapi: `1.` + UTC `YYYYMMDD` + `.01.00`. */
        fun dynamicClientVersion(nowMillis: Long = currentTimeMillis()): String {
            val (year, month, day) = utcYmd(nowMillis)
            return "1.${year.toString().padStart(4, '0')}${month.toString().padStart(2, '0')}${day.toString().padStart(2, '0')}.01.00"
        }

        private fun utcYmd(epochMillis: Long): Triple<Int, Int, Int> {
            val days = floorDiv(epochMillis, 86_400_000L)
            // Civil date from days since Unix epoch (Howard Hinnant algorithm).
            val z = days + 719_468L
            val era = floorDiv(z, 146_097L)
            val doe = (z - era * 146_097L).toInt()
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
            val y = (yoe.toLong() + era * 400L).toInt()
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val day = doy - (153 * mp + 2) / 5 + 1
            val month = mp + if (mp < 10) 3 else -9
            val year = y + if (month <= 2) 1 else 0
            return Triple(year, month, day)
        }

        private fun floorDiv(value: Long, divisor: Long): Long {
            var result = value / divisor
            if ((value xor divisor) < 0 && result * divisor != value) result -= 1
            return result
        }

        fun sapisidFromCookie(cookie: String): String? {
            if (cookie.isBlank()) return null
            val parts = cookie.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            val values = parts.mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).trim() to part.substring(index + 1).trim()
            }.toMap()
            return values["__Secure-3PAPISID"]
                ?: values["SAPISID"]
                ?: values["__Secure-1PAPISID"]
        }

        fun sapisidHashAuthorization(sapisid: String, origin: String, nowMillis: Long = currentTimeMillis()): String {
            val timestamp = (nowMillis / 1_000).toString()
            val digest = sha1Hex("$timestamp $sapisid $origin")
            return "SAPISIDHASH ${timestamp}_$digest"
        }

        fun sha1Hex(value: String): String {
            val bytes = sha1(value.encodeToByteArray())
            val hex = CharArray(bytes.size * 2)
            val digits = "0123456789abcdef"
            bytes.forEachIndexed { index, byte ->
                val number = byte.toInt() and 0xff
                hex[index * 2] = digits[number ushr 4]
                hex[index * 2 + 1] = digits[number and 0x0f]
            }
            return hex.concatToString()
        }

        // Minimal SHA-1 for SAPISIDHASH (no platform crypto dependency in commonMain).
        fun sha1(message: ByteArray): ByteArray {
            val h = intArrayOf(0x67452301, 0xEFCDAB89.toInt(), 0x98BADCFE.toInt(), 0x10325476, 0xC3D2E1F0.toInt())
            val bitLength = message.size.toLong() * 8
            val withOne = message + byteArrayOf(0x80.toByte())
            val padding = ((56 - withOne.size % 64) + 64) % 64
            val padded = withOne + ByteArray(padding) + byteArrayOf(
                (bitLength ushr 56).toByte(),
                (bitLength ushr 48).toByte(),
                (bitLength ushr 40).toByte(),
                (bitLength ushr 32).toByte(),
                (bitLength ushr 24).toByte(),
                (bitLength ushr 16).toByte(),
                (bitLength ushr 8).toByte(),
                bitLength.toByte(),
            )
            var offset = 0
            while (offset < padded.size) {
                val w = IntArray(80)
                for (i in 0 until 16) {
                    val j = offset + i * 4
                    w[i] = ((padded[j].toInt() and 0xff) shl 24) or
                        ((padded[j + 1].toInt() and 0xff) shl 16) or
                        ((padded[j + 2].toInt() and 0xff) shl 8) or
                        (padded[j + 3].toInt() and 0xff)
                }
                for (i in 16 until 80) {
                    w[i] = rotateLeft(w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16], 1)
                }
                var a = h[0]
                var b = h[1]
                var c = h[2]
                var d = h[3]
                var e = h[4]
                for (i in 0 until 80) {
                    val (f, k) = when {
                        i < 20 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                        i < 40 -> (b xor c xor d) to 0x6ED9EBA1
                        i < 60 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                        else -> (b xor c xor d) to 0xCA62C1D6.toInt()
                    }
                    val temp = rotateLeft(a, 5) + f + e + k + w[i]
                    e = d
                    d = c
                    c = rotateLeft(b, 30)
                    b = a
                    a = temp
                }
                h[0] += a
                h[1] += b
                h[2] += c
                h[3] += d
                h[4] += e
                offset += 64
            }
            val out = ByteArray(20)
            for (i in 0 until 5) {
                out[i * 4] = (h[i] ushr 24).toByte()
                out[i * 4 + 1] = (h[i] ushr 16).toByte()
                out[i * 4 + 2] = (h[i] ushr 8).toByte()
                out[i * 4 + 3] = h[i].toByte()
            }
            return out
        }

        private fun rotateLeft(value: Int, bits: Int): Int = (value shl bits) or (value ushr (32 - bits))
    }
}
