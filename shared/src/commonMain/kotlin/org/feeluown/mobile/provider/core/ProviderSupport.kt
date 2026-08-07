package org.feeluown.mobile.provider.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.Serializable
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.ProviderAuthState
import org.feeluown.mobile.ProviderCapabilities
import org.feeluown.mobile.ProviderContentSection
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderInfo
import org.feeluown.mobile.ProviderMediaItem
import org.feeluown.mobile.ProviderMediaItemDetail
import org.feeluown.mobile.ProviderMediaItemType
import org.feeluown.mobile.ProviderMutationResult
import org.feeluown.mobile.ProviderPlaylist
import org.feeluown.mobile.ProviderPlaylistDetail
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.ProviderVideo
import org.feeluown.mobile.TrackSourceType
import org.feeluown.mobile.VideoPlaybackPayload
import org.feeluown.mobile.provider.core.network.ProviderCachePolicies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderRequestKind

internal val providerJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

internal fun JsonElement.objOrNull(): JsonObject? = this as? JsonObject

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())

internal fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

internal fun JsonObject.stringOrNull(key: String): String? = string(key).takeIf { it.isNotBlank() }

internal fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

internal fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

internal fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

internal fun JsonObject.boolean(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: false

internal fun JsonElement.asObject(): JsonObject = jsonObject

internal fun JsonElement.asArray(): JsonArray = jsonArray

internal fun JsonElement.asString(): String = jsonPrimitive.contentOrNull.orEmpty()

internal fun JsonElement.asLong(): Long? = jsonPrimitive.longOrNull

internal fun JsonElement.asInt(): Int? = jsonPrimitive.intOrNull

internal fun JsonElement.asBoolean(): Boolean = jsonPrimitive.booleanOrNull ?: false

internal fun trackKey(providerId: String, identifier: String): String = "$providerId:$identifier"

internal fun playlistKey(providerId: String, identifier: String): String = "playlist:$providerId:$identifier"

internal fun mediaItemKey(type: ProviderMediaItemType, providerId: String, identifier: String): String {
    val prefix = if (type == ProviderMediaItemType.Artist) "artist" else "album"
    return "$prefix:$providerId:$identifier"
}

internal fun videoKey(providerId: String, identifier: String): String = "video:$providerId:$identifier"

internal fun splitResourceId(value: String, expectedPrefix: String? = null): Pair<String, String> {
    val parts = value.split(':')
    val start = if (expectedPrefix != null && parts.firstOrNull() == expectedPrefix) 1 else 0
    val provider = parts.getOrNull(start).orEmpty()
    val id = parts.drop(start + 1).joinToString(":")
    return provider to id
}

@Serializable
data class ProviderCredentials(
    val cookies: Map<String, String> = emptyMap(),
    val authorization: String? = null,
    val cookieHeader: String? = null,
    val headerFileJson: String? = null,
    val oauthAccessToken: String? = null,
    val oauthRefreshToken: String? = null,
    val oauthExpiresAtMillis: Long? = null,
    val oauthScope: String? = null,
    val oauthClientId: String? = null,
    val oauthClientSecret: String? = null,
) {
    fun hasOAuthAccess(): Boolean =
        !oauthAccessToken.isNullOrBlank() || !oauthRefreshToken.isNullOrBlank()
}

interface ProviderCredentialStore {
    suspend fun read(providerId: String): ProviderCredentials?
    suspend fun write(providerId: String, credentials: ProviderCredentials)
    suspend fun delete(providerId: String)
    suspend fun migrateLegacyIfNeeded() = Unit
}

internal class InMemoryProviderCredentialStore : ProviderCredentialStore {
    private val mutex = Mutex()
    private val values = mutableMapOf<String, ProviderCredentials>()

    override suspend fun read(providerId: String): ProviderCredentials? = mutex.withLock { values[providerId] }

    override suspend fun write(providerId: String, credentials: ProviderCredentials) {
        mutex.withLock { values[providerId] = credentials }
    }

    override suspend fun delete(providerId: String) {
        mutex.withLock { values.remove(providerId) }
    }
}

internal fun parseCookies(raw: String): Map<String, String> {
    val value = raw.trim()
    if (value.isBlank()) return emptyMap()
    runCatching {
        val json = providerJson.parseToJsonElement(value)
        if (json is JsonObject) {
            return json.mapNotNull { (key, element) ->
                element.jsonPrimitive.contentOrNull?.let { key to it }
            }.toMap()
        }
    }
    return value.split(';')
        .mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) return@mapNotNull null
            part.substring(0, index).trim().takeIf { it.isNotBlank() }?.let { key ->
                key to part.substring(index + 1).trim()
            }
        }
        .toMap()
}

fun providerCredentialsFromCookieInput(raw: String): ProviderCredentials {
    val cookies = parseCookies(raw)
    return if (cookies.isNotEmpty()) {
        ProviderCredentials(cookies = cookies)
    } else {
        ProviderCredentials(cookieHeader = raw.trim())
    }
}

internal fun cookieHeader(credentials: ProviderCredentials?): String {
    if (credentials == null) return ""
    credentials.cookieHeader?.takeIf { it.isNotBlank() }?.let { return it }
    return credentials.cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
}

abstract class BaseKotlinProvider(
    protected val http: ProviderHttpClient,
    protected val credentials: ProviderCredentialStore,
    final override val id: String,
    final override val name: String,
    final override val info: ProviderInfo,
    final override val capabilities: ProviderCapabilities,
    final override val features: List<ProviderFeature>,
) : KotlinMusicProvider {
    protected suspend fun currentCredentials(): ProviderCredentials? = credentials.read(id)

    protected suspend fun authenticatedHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val stored = currentCredentials()
        return buildMap {
            put("User-Agent", DEFAULT_USER_AGENT)
            putAll(extra)
            cookieHeader(stored).takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            stored?.authorization?.takeIf { it.isNotBlank() }?.let { put("Authorization", it) }
        }
    }

    protected fun track(
        identifier: String,
        title: String,
        artists: String,
        album: String,
        coverUrl: String? = null,
        durationMs: Long? = null,
        artistItemId: String? = null,
        albumItemId: String? = null,
        providerUrl: String? = null,
    ): MusicTrack = MusicTrack(
        id = trackKey(id, identifier),
        title = title,
        artists = artists,
        album = album,
        source = id,
        sourceType = TrackSourceType.Provider,
        coverUrl = coverUrl,
        durationMs = durationMs,
        providerId = trackKey(id, identifier),
        providerName = name,
        artistItemId = artistItemId,
        albumItemId = albumItemId,
        providerUrl = providerUrl,
    )

    protected fun playlist(
        identifier: String,
        title: String,
        coverUrl: String? = null,
        description: String = "",
        playCount: Long? = null,
        trackCount: Int? = null,
        providerUrl: String? = null,
    ): ProviderPlaylist = ProviderPlaylist(
        id = playlistKey(id, identifier),
        title = title,
        providerId = id,
        providerName = name,
        coverUrl = coverUrl,
        description = description,
        playCount = playCount,
        providerUrl = providerUrl,
        trackCount = trackCount,
    )

    protected fun mediaItem(
        type: ProviderMediaItemType,
        identifier: String,
        title: String,
        coverUrl: String? = null,
        description: String = "",
        trackCount: Int? = null,
        albumCount: Int? = null,
        providerUrl: String? = null,
    ): ProviderMediaItem = ProviderMediaItem(
        id = mediaItemKey(type, id, identifier),
        title = title,
        providerId = id,
        providerName = name,
        type = type,
        coverUrl = coverUrl,
        description = description,
        providerUrl = providerUrl,
        trackCount = trackCount,
        albumCount = albumCount,
    )

    protected fun video(
        identifier: String,
        title: String,
        artists: String = "",
        coverUrl: String? = null,
        durationMs: Long? = null,
        providerUrl: String? = null,
    ): ProviderVideo = ProviderVideo(
        id = videoKey(id, identifier),
        title = title,
        artists = artists,
        providerId = id,
        providerName = name,
        coverUrl = coverUrl,
        durationMs = durationMs,
        providerUrl = providerUrl,
    )

    protected fun authState(credentials: ProviderCredentials?): ProviderAuthState = ProviderAuthState(
        providerId = id,
        providerName = name,
        isLoggedIn = credentials != null && (
            credentials.cookies.isNotEmpty() ||
                !credentials.authorization.isNullOrBlank() ||
                !credentials.cookieHeader.isNullOrBlank() ||
                !credentials.headerFileJson.isNullOrBlank() ||
                credentials.hasOAuthAccess()
            ),
        userName = null,
    )

    override suspend fun initialize() = Unit

    override suspend fun authState(): ProviderAuthState = authState(currentCredentials())

    override suspend fun loginWithCookies(cookiesJson: String): ProviderAuthState {
        val cookies = parseCookies(cookiesJson)
        require(cookies.isNotEmpty()) { "cookies must be a non-empty JSON object" }
        val value = ProviderCredentials(cookies = cookies)
        credentials.write(id, value)
        return authState(value)
    }

    override suspend fun loginWithHeaders(authorization: String, cookie: String): ProviderAuthState {
        require(authorization.isNotBlank() && cookie.isNotBlank()) { "authorization and cookie must be non-empty" }
        val value = ProviderCredentials(authorization = authorization.trim(), cookieHeader = cookie.trim())
        credentials.write(id, value)
        return authState(value)
    }

    override suspend fun loginWithHeaderFile(headerFileJson: String): ProviderAuthState {
        val json = providerJson.parseToJsonElement(headerFileJson).jsonObject
        val authorization = json.string("Authorization")
        val cookie = json.string("Cookie")
        require(authorization.isNotBlank() && cookie.isNotBlank()) {
            "ytmusic header file must contain Authorization and Cookie"
        }
        val value = ProviderCredentials(
            authorization = authorization,
            cookieHeader = cookie,
            headerFileJson = headerFileJson,
        )
        credentials.write(id, value)
        return authState(value)
    }

    override suspend fun loginWithOAuth(
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long?,
        scope: String?,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState {
        require(accessToken.isNotBlank() || refreshToken.isNotBlank()) {
            "oauth access_token or refresh_token is required"
        }
        require(clientId.isNotBlank() && clientSecret.isNotBlank()) {
            "oauth client_id and client_secret are required"
        }
        val value = ProviderCredentials(
            oauthAccessToken = accessToken.trim().takeIf { it.isNotBlank() },
            oauthRefreshToken = refreshToken.trim().takeIf { it.isNotBlank() },
            oauthExpiresAtMillis = expiresAtMillis,
            oauthScope = scope?.trim()?.takeIf { it.isNotBlank() },
            oauthClientId = clientId.trim(),
            oauthClientSecret = clientSecret.trim(),
        )
        credentials.write(id, value)
        return authState(value)
    }

    override suspend fun logout(): ProviderAuthState {
        credentials.delete(id)
        return authState(null)
    }

    override suspend fun trackDetail(identifier: String): MusicTrack? = null

    override suspend fun search(keyword: String): ProviderSearchResults = ProviderSearchResults()

    override suspend fun resolve(track: MusicTrack, qualityPolicy: String): PlaybackPayload? = null

    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> = emptyList()

    override suspend fun playlistDetail(playlist: ProviderPlaylist, offset: Int, limit: Int): ProviderPlaylistDetail =
        ProviderPlaylistDetail(playlist, playlistTracks(playlist))

    override suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack> = emptyList()

    override suspend fun mediaItemDetail(item: ProviderMediaItem, tracksOffset: Int, albumsOffset: Int, limit: Int): ProviderMediaItemDetail =
        ProviderMediaItemDetail(item, mediaItemTracks(item))

    override suspend fun loadFeature(feature: ProviderFeature, offset: Int, limit: Int): ProviderContentSection =
        ProviderContentSection(feature = feature)

    override suspend fun similarTracks(track: MusicTrack): List<MusicTrack> = emptyList()

    override suspend fun hotComments(track: MusicTrack): List<org.feeluown.mobile.ProviderComment> = emptyList()

    override suspend fun trackVideo(track: MusicTrack): ProviderVideo? = null

    override suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload =
        VideoPlaybackPayload(video = video)

    override suspend fun playlistOperationTargets(track: MusicTrack): List<ProviderPlaylist> = emptyList()

    override suspend fun addTrackToPlaylist(playlist: ProviderPlaylist, track: MusicTrack): ProviderMutationResult =
        unsupported("当前音源不支持添加歌曲到歌单")

    override suspend fun removeTrackFromPlaylist(playlist: ProviderPlaylist, track: MusicTrack): ProviderMutationResult =
        unsupported("当前音源不支持从歌单移除歌曲")

    override suspend fun createPlaylist(name: String): ProviderMutationResult = unsupported("当前音源不支持新建歌单")

    override suspend fun deletePlaylist(playlist: ProviderPlaylist): ProviderMutationResult = unsupported("当前音源不支持删除歌单")

    override suspend fun setSongDisliked(track: MusicTrack, disliked: Boolean): ProviderMutationResult =
        unsupported("当前音源不支持不喜欢操作")

    override suspend fun resourceState(resourceType: String, resourceId: String) =
        org.feeluown.mobile.ProviderResourceState(providerId = id, resourceId = resourceId)

    override suspend fun setResourceFavorite(resourceType: String, resourceId: String, favorite: Boolean): ProviderMutationResult =
        unsupported("当前音源不支持该收藏操作")

    protected fun unsupported(message: String) = ProviderMutationResult(false, message)

    protected fun jsonObject(raw: String): JsonObject = providerJson.parseToJsonElement(raw).jsonObject

    protected fun queryUrl(base: String, params: Map<String, String>): String {
        val encoded = params.entries.joinToString("&") { (key, value) ->
            "${key.encodeUrlComponent()}=${value.encodeUrlComponent()}"
        }
        return if (encoded.isBlank()) base else "$base?$encoded"
    }

    private fun String.encodeUrlComponent(): String = buildString {
        for (byte in encodeToByteArray()) {
            val value = byte.toInt() and 0xff
            if (value in 0x30..0x39 || value in 0x41..0x5a || value in 0x61..0x7a || value in setOf(45, 46, 95, 126)) {
                append(value.toChar())
            } else {
                append('%')
                append("0123456789ABCDEF"[value ushr 4])
                append("0123456789ABCDEF"[value and 15])
            }
        }
    }

    protected companion object {
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    }
}

interface KotlinMusicProvider {
    val id: String
    val name: String
    val info: ProviderInfo
    val capabilities: ProviderCapabilities
    val features: List<ProviderFeature>

    suspend fun initialize()
    suspend fun search(keyword: String): ProviderSearchResults
    suspend fun trackDetail(identifier: String): MusicTrack?
    suspend fun resolve(track: MusicTrack, qualityPolicy: String): PlaybackPayload?
    suspend fun authState(): ProviderAuthState
    suspend fun loginWithCookies(cookiesJson: String): ProviderAuthState
    suspend fun loginWithHeaders(authorization: String, cookie: String): ProviderAuthState
    suspend fun loginWithHeaderFile(headerFileJson: String): ProviderAuthState
    suspend fun loginWithOAuth(
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long?,
        scope: String?,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState = throw UnsupportedOperationException("provider does not support OAuth login: $id")
    suspend fun logout(): ProviderAuthState
    suspend fun loadFeature(feature: ProviderFeature, offset: Int, limit: Int): ProviderContentSection
    suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack>
    suspend fun playlistDetail(playlist: ProviderPlaylist, offset: Int, limit: Int): ProviderPlaylistDetail
    suspend fun playlistOperationTargets(track: MusicTrack): List<ProviderPlaylist>
    suspend fun addTrackToPlaylist(playlist: ProviderPlaylist, track: MusicTrack): ProviderMutationResult
    suspend fun removeTrackFromPlaylist(playlist: ProviderPlaylist, track: MusicTrack): ProviderMutationResult
    suspend fun createPlaylist(name: String): ProviderMutationResult
    suspend fun deletePlaylist(playlist: ProviderPlaylist): ProviderMutationResult
    suspend fun setSongDisliked(track: MusicTrack, disliked: Boolean): ProviderMutationResult
    suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack>
    suspend fun mediaItemDetail(item: ProviderMediaItem, tracksOffset: Int, albumsOffset: Int, limit: Int): ProviderMediaItemDetail
    suspend fun similarTracks(track: MusicTrack): List<MusicTrack>
    suspend fun hotComments(track: MusicTrack): List<org.feeluown.mobile.ProviderComment>
    suspend fun trackVideo(track: MusicTrack): ProviderVideo?
    suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload
    suspend fun resourceState(resourceType: String, resourceId: String): org.feeluown.mobile.ProviderResourceState
    suspend fun setResourceFavorite(resourceType: String, resourceId: String, favorite: Boolean): ProviderMutationResult
}
