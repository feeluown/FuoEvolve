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
 * The user-created-playlist endpoint is the primary source because it returns
 * both `hostname` and `disslist`. The older profile-homepage endpoint is kept
 * only as a fallback for nickname / "我喜欢" when QQ omits them from disslist.
 */
internal class QQMusicUserLibrary(
    private val http: ProviderHttpClient,
    private val credentials: ProviderCredentialStore,
) {
    suspend fun userName(): String? {
        val uin = currentUin() ?: return null
        return runCatching { snapshot(uin).userName }.getOrNull()
    }

    suspend fun loadPlaylists(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val uin = currentUin()
            ?: return ProviderContentSection(
                feature = feature,
                errorMessage = "无法读取 QQ 音乐账号信息，请重新登录",
            )
        val snapshot = runCatching { snapshot(uin) }.getOrElse { throwable ->
            return ProviderContentSection(
                feature = feature,
                errorMessage = throwable.message ?: "加载 QQ 音乐歌单失败",
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

    private suspend fun snapshot(uin: String): QQMusicUserLibrarySnapshot {
        val created = createdPlaylists(uin)
        var userName = created.userName
        var playlists = created.playlists

        if (userName.isNullOrBlank() || !created.hasFavorite) {
            val home = runCatching { profileHome(uin) }.getOrNull()
            if (userName.isNullOrBlank()) userName = home?.userName
            if (!created.hasFavorite) {
                home?.favoritePlaylist?.let { favorite ->
                    playlists = (listOf(favorite) + playlists).distinctBy { it.id }
                }
            }
        }
        return QQMusicUserLibrarySnapshot(
            userName = userName?.takeIf { it.isNotBlank() },
            playlists = playlists.distinctBy { it.id },
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
            val message = root.string("message").ifBlank { root.string("msg") }
            error(
                message.ifBlank {
                    if (code != null) "QQ 音乐用户歌单加载失败（code=$code）" else "QQ 音乐用户歌单加载失败"
                },
            )
        }
        val rawPlaylists = data.array("disslist")
        return CreatedPlaylistsResult(
            userName = data.stringOrNull("hostname")
                ?: data.stringOrNull("nickname")
                ?: data.stringOrNull("nick"),
            playlists = rawPlaylists.mapNotNull { value ->
                playlistFromCreated(value.asObject())
            },
            hasFavorite = rawPlaylists.any { value ->
                val item = value.asObject()
                item.int("dirid") == FAVORITE_DIR_ID || item.string("dirid").toIntOrNull() == FAVORITE_DIR_ID
            },
        )
    }

    private suspend fun profileHome(uin: String): ProfileHomeResult {
        val root = http.getText(
            providerId = ID,
            url = queryUrl(
                "$BASE/rsc/fcgi-bin/fcg_get_profile_homepage.fcg",
                mapOf(
                    "cid" to "205360838",
                    "reqfrom" to "1",
                    "userid" to uin,
                ),
            ),
            headers = authenticatedHeaders("https://y.qq.com/"),
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val data = root.obj("data") ?: return ProfileHomeResult()
        val creator = data.obj("creator")
        val userName = creator?.stringOrNull("nick")
            ?: creator?.stringOrNull("nickname")
            ?: creator?.stringOrNull("name")
            ?: data.stringOrNull("hostname")
            ?: data.stringOrNull("nickname")
            ?: data.stringOrNull("nick")
        val favorite = data.array("mymusic").firstOrNull()?.asObject()
        val favoriteId = creator?.stringOrNull("fav_pid")
            ?: favorite?.stringOrNull("id")
        return ProfileHomeResult(
            userName = userName,
            favoritePlaylist = favoriteId?.let { identifier ->
                ProviderPlaylist(
                    id = playlistKey(ID, identifier),
                    title = "我喜欢",
                    providerId = ID,
                    providerName = NAME,
                    coverUrl = favorite?.stringOrNull("picurl")
                        ?: favorite?.stringOrNull("cover")
                        ?: favorite?.stringOrNull("logo"),
                    trackCount = favorite?.int("num0") ?: favorite?.int("songnum"),
                    providerUrl = "https://y.qq.com/n/ryqq/playlist/$identifier",
                )
            },
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

    private suspend fun currentUin(): String? {
        val stored = credentials.read(ID) ?: return null
        val values = parseCookies(stored.cookieHeader.orEmpty()) + stored.cookies
        val loginType = values["login_type"]?.toIntOrNull()
        val candidates = if (loginType == WECHAT_LOGIN_TYPE) {
            listOf(values["wxuin"], values["uin"])
        } else {
            listOf(values["uin"], values["wxuin"])
        }
        return candidates.asSequence().mapNotNull(::normalizeUin).firstOrNull()
    }

    private fun normalizeUin(raw: String?): String? = raw
        ?.filter { character -> character.isDigit() }
        ?.takeIf { digits -> digits.isNotBlank() && digits.any { character -> character != '0' } }

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

    private data class CreatedPlaylistsResult(
        val userName: String? = null,
        val playlists: List<ProviderPlaylist> = emptyList(),
        val hasFavorite: Boolean = false,
    )

    private data class ProfileHomeResult(
        val userName: String? = null,
        val favoritePlaylist: ProviderPlaylist? = null,
    )

    private companion object {
        const val ID = "qqmusic"
        const val NAME = "QQ 音乐"
        const val BASE = "https://c.y.qq.com"
        const val FAVORITE_DIR_ID = 201
        const val PRIVATE_PLAYLIST_CODE = 4000
        const val WECHAT_LOGIN_TYPE = 2
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    }
}

internal data class QQMusicUserLibrarySnapshot(
    val userName: String? = null,
    val playlists: List<ProviderPlaylist> = emptyList(),
)
