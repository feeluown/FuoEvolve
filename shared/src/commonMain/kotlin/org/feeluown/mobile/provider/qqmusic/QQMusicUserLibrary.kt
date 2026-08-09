package org.feeluown.mobile.provider.qqmusic

import kotlinx.serialization.json.JsonObject
import org.feeluown.mobile.ProviderContentSection
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderPlaylist
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.array
import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.cookieHeader
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.parseCookies
import org.feeluown.mobile.provider.core.playlistKey
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.network.ProviderHttpClient

/**
 * Reads the QQ Music account profile and user-created playlists.
 *
 * QQ Music's profile-homepage endpoint is the primary source. It can return
 * the nickname, "我喜欢" and created playlists together and does not require
 * the legacy `hostuin` parameter. The older user-created-playlist endpoint is
 * retained only as a fallback for an unambiguously numeric QQ number.
 */
internal class QQMusicUserLibrary(
    private val http: ProviderHttpClient,
    private val credentials: ProviderCredentialStore,
) {
    suspend fun userName(): String? = runCatching { snapshot().userName }.getOrNull()

    suspend fun loadPlaylists(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val snapshot = runCatching { snapshot() }.getOrElse {
            return ProviderContentSection(
                feature = feature,
                errorMessage = "QQ 音乐账号资料加载失败，当前登录状态已保留",
            )
        }
        val page = snapshot.playlists.drop(offset).take(limit)
        return ProviderContentSection(
            feature = feature,
            playlists = page,
            nextOffset = offset + page.size,
            hasMore = snapshot.playlists.size > offset + page.size,
        )
    }

    private suspend fun snapshot(): QQMusicUserLibrarySnapshot {
        val accountIds = currentAccountIds()
        if (accountIds.isEmpty()) error("QQ Music account id is unavailable")

        var lastFailure: Throwable? = null
        for (accountId in accountIds) {
            val profile = runCatching { profileHome(accountId) }
                .onFailure { lastFailure = it }
                .getOrNull()

            if (profile != null && profile.isMeaningful()) {
                if (profile.hasPlaylistPayload || !isLikelyQqNumber(accountId)) {
                    return QQMusicUserLibrarySnapshot(
                        userName = profile.userName,
                        playlists = profile.playlists.distinctBy { it.id },
                    )
                }

                val fallback = runCatching { createdPlaylists(accountId) }
                    .onFailure { lastFailure = it }
                    .getOrNull()
                return QQMusicUserLibrarySnapshot(
                    userName = profile.userName ?: fallback?.userName,
                    playlists = (profile.playlists + fallback.orEmptyPlaylists()).distinctBy { it.id },
                )
            }
        }

        for (accountId in accountIds.filter(::isLikelyQqNumber)) {
            val created = runCatching { createdPlaylists(accountId) }
                .onFailure { lastFailure = it }
                .getOrNull()
                ?: continue
            if (created.userName != null || created.playlists.isNotEmpty()) {
                return QQMusicUserLibrarySnapshot(
                    userName = created.userName,
                    playlists = created.playlists.distinctBy { it.id },
                )
            }
        }

        throw lastFailure ?: IllegalStateException("QQ Music profile is unavailable")
    }

    private suspend fun profileHome(accountId: String): ProfileHomeResult {
        val root = http.getText(
            providerId = ID,
            url = queryUrl(
                "$PROFILE_BASE/rsc/fcgi-bin/fcg_get_profile_homepage.fcg",
                mapOf(
                    "cv" to "4747474",
                    "ct" to "24",
                    "format" to "json",
                    "inCharset" to "utf-8",
                    "outCharset" to "utf-8",
                    "notice" to "0",
                    "platform" to "yqq.json",
                    "needNewCode" to "0",
                    "uin" to accountId,
                    "g_tk_new_20200303" to "0",
                    "g_tk" to "0",
                    "cid" to "205360838",
                    "userid" to accountId,
                    "reqfrom" to "1",
                    "reqtype" to "0",
                    "hostUin" to "0",
                    "loginUin" to accountId,
                ),
            ),
            headers = authenticatedHeaders("https://y.qq.com/portal/profile.html?uin=$accountId"),
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }

        val code = root.int("code")
        if (code != null && code != 0) {
            error(root.errorMessage().ifBlank { "QQ Music profile request failed (code=$code)" })
        }
        val data = root.obj("data") ?: error("QQ Music profile payload missing data")
        val creator = data.obj("creator")
        val userName = creator?.stringOrNull("nick")
            ?: creator?.stringOrNull("nickname")
            ?: creator?.stringOrNull("name")
            ?: creator?.stringOrNull("hostname")
            ?: data.stringOrNull("hostname")
            ?: data.stringOrNull("nickname")
            ?: data.stringOrNull("nick")

        val favoriteValues = data.array("mymusic")
        val createdValues = firstNonEmpty(
            data.obj("mydiss")?.array("list").orEmpty(),
            data.array("mydiss"),
            data.array("createdDissList"),
            data.array("createdList"),
            data.array("playlists"),
            data.array("playlist"),
        )
        val favoriteFromCreator = creator?.stringOrNull("fav_pid")
            ?.takeIf { it.isNotBlank() }
            ?.let { identifier ->
                ProviderPlaylist(
                    id = playlistKey(ID, identifier),
                    title = "我喜欢",
                    providerId = ID,
                    providerName = NAME,
                    providerUrl = "https://y.qq.com/n/ryqq/playlist/$identifier",
                )
            }

        val playlists = buildList {
            favoriteValues.mapNotNullTo(this) { value ->
                playlistFromProfile(value.asObject(), favorite = true)
            }
            favoriteFromCreator?.let(::add)
            createdValues.mapNotNullTo(this) { value ->
                playlistFromProfile(value.asObject(), favorite = false)
            }
        }.distinctBy { it.id }

        val hasPlaylistPayload =
            data.containsKey("mymusic") ||
                data.containsKey("mydiss") ||
                data.containsKey("createdDissList") ||
                data.containsKey("createdList") ||
                data.containsKey("playlists") ||
                data.containsKey("playlist") ||
                creator?.containsKey("fav_pid") == true

        return ProfileHomeResult(
            userName = userName?.takeIf { it.isNotBlank() },
            playlists = playlists,
            hasPlaylistPayload = hasPlaylistPayload,
        )
    }

    private suspend fun createdPlaylists(uin: String): CreatedPlaylistsResult {
        val root = http.getText(
            providerId = ID,
            url = queryUrl(
                "$BASE/rsc/fcgi-bin/fcg_user_created_diss",
                mapOf(
                    "hostUin" to "0",
                    "hostuin" to uin,
                    "sin" to "0",
                    "size" to "200",
                    "g_tk" to "5381",
                    "loginUin" to "0",
                    "format" to "json",
                    "inCharset" to "utf8",
                    "outCharset" to "utf-8",
                    "notice" to "0",
                    "platform" to "yqq.json",
                    "needNewCode" to "0",
                ),
            ),
            headers = authenticatedHeaders("https://y.qq.com/portal/profile.html"),
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val data = root.obj("data")
        if (data == null) {
            val code = root.int("code")
            if (code == PRIVATE_PLAYLIST_CODE) return CreatedPlaylistsResult()
            error(root.errorMessage().ifBlank { "QQ Music user playlists request failed" })
        }
        val rawPlaylists = data.array("disslist")
        return CreatedPlaylistsResult(
            userName = data.stringOrNull("hostname")
                ?: data.stringOrNull("nickname")
                ?: data.stringOrNull("nick"),
            playlists = rawPlaylists.mapNotNull { value ->
                playlistFromCreated(value.asObject())
            },
        )
    }

    private fun playlistFromProfile(item: JsonObject, favorite: Boolean): ProviderPlaylist? {
        val identifier = item.string("dissid")
            .ifBlank { item.string("tid") }
            .ifBlank { item.string("id") }
            .takeIf { it.isNotBlank() }
            ?: return null
        val dirId = item.int("dirid") ?: item.string("dirid").toIntOrNull()
        val isFavorite = favorite || dirId == FAVORITE_DIR_ID
        return ProviderPlaylist(
            id = playlistKey(ID, identifier),
            title = item.string("title")
                .ifBlank { item.string("diss_name") }
                .ifBlank { item.string("dissname") }
                .ifBlank { item.string("name") }
                .ifBlank { if (isFavorite) "我喜欢" else "未命名歌单" },
            providerId = ID,
            providerName = NAME,
            coverUrl = item.stringOrNull("picurl")
                ?: item.stringOrNull("diss_cover")
                ?: item.stringOrNull("logo")
                ?: item.stringOrNull("cover"),
            description = item.string("desc"),
            playCount = item.long("listen_num") ?: item.long("visitnum"),
            trackCount = item.int("num0")
                ?: item.int("song_cnt")
                ?: item.int("songnum")
                ?: item.int("song_count"),
            providerUrl = "https://y.qq.com/n/ryqq/playlist/$identifier",
        )
    }

    private fun playlistFromCreated(item: JsonObject): ProviderPlaylist? {
        val dirId = item.int("dirid") ?: item.string("dirid").toIntOrNull()
        val identifier = item.string("tid")
            .ifBlank { item.string("dissid") }
            .ifBlank { item.string("id") }
            .takeIf { it.isNotBlank() }
            ?: return null
        return ProviderPlaylist(
            id = playlistKey(ID, identifier),
            title = item.string("diss_name")
                .ifBlank { item.string("dissname") }
                .ifBlank { item.string("title") }
                .ifBlank { if (dirId == FAVORITE_DIR_ID) "我喜欢" else "未命名歌单" },
            providerId = ID,
            providerName = NAME,
            coverUrl = item.stringOrNull("diss_cover")
                ?: item.stringOrNull("logo")
                ?: item.stringOrNull("picurl")
                ?: item.stringOrNull("cover"),
            description = item.string("desc"),
            playCount = item.long("listen_num") ?: item.long("visitnum"),
            trackCount = item.int("song_cnt") ?: item.int("songnum"),
            providerUrl = "https://y.qq.com/n/ryqq/playlist/$identifier",
        )
    }

    private suspend fun currentAccountIds(): List<String> {
        val stored = credentials.read(ID) ?: return emptyList()
        val values = parseCookies(stored.cookieHeader.orEmpty()) + stored.cookies
        val loginType = values["login_type"]?.toIntOrNull()
        val isWechat = loginType == WECHAT_LOGIN_TYPE ||
            !values["wxopenid"].isNullOrBlank() ||
            !values["wxunionid"].isNullOrBlank()
        val keys = if (isWechat) {
            listOf("str_musicid", "musicid", "wxuin", "uin")
        } else {
            listOf("uin", "str_musicid", "musicid", "wxuin")
        }
        return keys.mapNotNull { key -> normalizeAccountId(values[key]) }.distinct()
    }

    private fun normalizeAccountId(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val digits = when {
            value.all(Char::isDigit) -> value
            value.startsWith('o') && value.drop(1).isNotEmpty() && value.drop(1).all(Char::isDigit) -> value.drop(1)
            else -> return null
        }
        return digits.takeIf { it.any { character -> character != '0' } }
    }

    private fun isLikelyQqNumber(value: String): Boolean =
        value.length in MIN_QQ_UIN_LENGTH..MAX_QQ_UIN_LENGTH && value.all(Char::isDigit)

    private suspend fun authenticatedHeaders(referer: String): Map<String, String> = buildMap {
        put("User-Agent", DEFAULT_USER_AGENT)
        put("Referer", referer)
        cookieHeader(credentials.read(ID)).takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
    }

    private fun queryUrl(base: String, params: Map<String, String>): String {
        val encoded = params.entries.joinToString("&") { (key, value) ->
            "${encodeUrlComponent(key)}=${encodeUrlComponent(value)}"
        }
        return if (encoded.isBlank()) base else "$base?$encoded"
    }

    private fun encodeUrlComponent(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val current = byte.toInt() and 0xff
            if (
                current in 0x30..0x39 ||
                current in 0x41..0x5a ||
                current in 0x61..0x7a ||
                current in setOf(45, 46, 95, 126)
            ) {
                append(current.toChar())
            } else {
                append('%')
                append("0123456789ABCDEF"[current ushr 4])
                append("0123456789ABCDEF"[current and 15])
            }
        }
    }

    private fun firstNonEmpty(vararg values: List<kotlinx.serialization.json.JsonElement>): List<kotlinx.serialization.json.JsonElement> =
        values.firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun JsonObject.errorMessage(): String =
        string("message").ifBlank { string("msg") }.ifBlank { string("errmsg") }.ifBlank { string("error") }

    private fun CreatedPlaylistsResult?.orEmptyPlaylists(): List<ProviderPlaylist> = this?.playlists.orEmpty()

    private data class CreatedPlaylistsResult(
        val userName: String? = null,
        val playlists: List<ProviderPlaylist> = emptyList(),
    )

    private data class ProfileHomeResult(
        val userName: String? = null,
        val playlists: List<ProviderPlaylist> = emptyList(),
        val hasPlaylistPayload: Boolean = false,
    ) {
        fun isMeaningful(): Boolean = userName != null || playlists.isNotEmpty() || hasPlaylistPayload
    }

    private companion object {
        const val ID = "qqmusic"
        const val NAME = "QQ 音乐"
        const val BASE = "https://c.y.qq.com"
        const val PROFILE_BASE = "https://c6.y.qq.com"
        const val FAVORITE_DIR_ID = 201
        const val PRIVATE_PLAYLIST_CODE = 4000
        const val WECHAT_LOGIN_TYPE = 2
        const val MIN_QQ_UIN_LENGTH = 5
        const val MAX_QQ_UIN_LENGTH = 12
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    }
}

internal data class QQMusicUserLibrarySnapshot(
    val userName: String? = null,
    val playlists: List<ProviderPlaylist> = emptyList(),
)
