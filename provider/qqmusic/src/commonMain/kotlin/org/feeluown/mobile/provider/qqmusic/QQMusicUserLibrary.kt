package org.feeluown.mobile.provider.qqmusic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.feeluown.mobile.ProviderContentSection
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderMediaItem
import org.feeluown.mobile.ProviderMediaItemType
import org.feeluown.mobile.ProviderPlaylist
import org.feeluown.mobile.providerBusinessException
import org.feeluown.mobile.providerContractException
import org.feeluown.mobile.providerFailureOrNull
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
import org.feeluown.mobile.provider.core.parseCookies
import org.feeluown.mobile.provider.core.playlistKey
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.currentTimeMillis
import kotlin.random.Random

/**
 * Reads the QQ Music account profile and Mine library content.
 *
 * The profile-homepage endpoint remains the primary source for the nickname,
 * "我喜欢" and created playlists. Favorite playlists/albums and followed singers
 * are loaded from QQ Music's current musicu services using the encrypted UIN from
 * the account profile (or the credential when it is already available).
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
        val snapshot = runCatching { snapshot() }.getOrElse { failure ->
            return ProviderContentSection(
                feature = feature,
                errorMessage = failure.providerMessage("QQ 音乐账号资料加载失败，当前登录状态已保留"),
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

    suspend fun loadFavoritePlaylists(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection = runCatching {
        val euin = encryptedUin()
        val root = rpc(
            """
            {"favoritePlaylists":{"module":"music.musicasset.PlaylistFavRead","method":"CgiGetPlaylistFavInfo","param":{"uin":${jsonString(euin)},"offset":$offset,"size":$limit}}}
            """.trimIndent(),
        )
        val data = rpcData(root, "favoritePlaylists")
        val rawValues = firstNonEmpty(
            data.array("v_list"),
            data.array("v_playlist"),
            data.array("list"),
        )
        val playlists = rawValues.mapNotNull { value ->
            runCatching { playlistFromFavorite(value.asObject()) }.getOrNull()
        }.distinctBy { it.id }
        val total = data.int("total")
        val nextOffset = offset + rawValues.size
        ProviderContentSection(
            feature = feature,
            playlists = playlists,
            nextOffset = nextOffset,
            hasMore = data.int("hasmore")?.let { it != 0 }
                ?: total?.let { nextOffset < it }
                ?: (rawValues.size >= limit),
        )
    }.getOrElse { failure ->
        ProviderContentSection(
            feature = feature,
            errorMessage = failure.providerMessage("QQ 音乐收藏歌单加载失败"),
        )
    }

    suspend fun loadFollowedArtists(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection = runCatching {
        val euin = encryptedUin()
        val root = rpc(
            """
            {"followedArtists":{"module":"music.concern.RelationList","method":"GetFollowSingerList","param":{"HostUin":${jsonString(euin)},"From":$offset,"Size":$limit}}}
            """.trimIndent(),
        )
        val data = rpcData(root, "followedArtists")
        val rawValues = firstNonEmpty(
            data.array("List"),
            data.array("list"),
            data.array("singerList"),
        )
        val artists = rawValues.mapNotNull { value ->
            runCatching { artistFromFollow(value.asObject()) }.getOrNull()
        }.distinctBy { it.id }
        val total = data.int("Total") ?: data.int("total")
        val nextOffset = offset + rawValues.size
        ProviderContentSection(
            feature = feature,
            mediaItems = artists,
            nextOffset = nextOffset,
            hasMore = when {
                data.containsKey("HasMore") -> data.boolean("HasMore")
                data.containsKey("hasMore") -> data.boolean("hasMore")
                total != null -> nextOffset < total
                else -> rawValues.size >= limit
            },
        )
    }.getOrElse { failure ->
        ProviderContentSection(
            feature = feature,
            errorMessage = failure.providerMessage("QQ 音乐关注歌手加载失败"),
        )
    }

    suspend fun loadFavoriteAlbums(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection = runCatching {
        val euin = encryptedUin()
        val root = rpc(
            """
            {"favoriteAlbums":{"module":"music.musicasset.AlbumFavRead","method":"CgiGetAlbumFavInfo","param":{"euin":${jsonString(euin)},"offset":$offset,"size":$limit}}}
            """.trimIndent(),
        )
        val data = rpcData(root, "favoriteAlbums")
        val rawValues = firstNonEmpty(
            data.array("v_list"),
            data.array("v_album"),
            data.array("list"),
        )
        val albums = rawValues.mapNotNull { value ->
            runCatching { albumFromFavorite(value.asObject()) }.getOrNull()
        }.distinctBy { it.id }
        val total = data.int("total")
        val nextOffset = offset + rawValues.size
        ProviderContentSection(
            feature = feature,
            mediaItems = albums,
            nextOffset = nextOffset,
            hasMore = data.int("hasmore")?.let { it != 0 }
                ?: total?.let { nextOffset < it }
                ?: (rawValues.size >= limit),
        )
    }.getOrElse { failure ->
        ProviderContentSection(
            feature = feature,
            errorMessage = failure.providerMessage("QQ 音乐收藏专辑加载失败"),
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

    private suspend fun encryptedUin(): String {
        currentEncryptedUin()?.let { return it }
        val accountIds = currentAccountIds()
        if (accountIds.isEmpty()) error("QQ Music account id is unavailable")
        var lastFailure: Throwable? = null
        accountIds.forEach { accountId ->
            val profile = runCatching { profileHome(accountId) }
                .onFailure { lastFailure = it }
                .getOrNull()
            profile?.encryptedUin?.takeIf(String::isNotBlank)?.let { return it }
        }
        throw lastFailure ?: IllegalStateException("QQ Music encrypted uin is unavailable")
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
                    "loginUin" to accountId,
                ),
            ),
            headers = authenticatedHeaders("https://y.qq.com/portal/profile.html?uin=$accountId"),
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }

        val code = root.int("code")
        if (code != null && code != 0) {
            throw providerBusinessException(ID, code, root.errorMessage())
        }
        val data = root.obj("data")
            ?: throw providerContractException(ID, "QQ Music profile payload missing data")
        val creator = data.obj("creator")
        val userName = creator?.stringOrNull("nick")
            ?: creator?.stringOrNull("nickname")
            ?: creator?.stringOrNull("name")
            ?: creator?.stringOrNull("hostname")
            ?: data.stringOrNull("hostname")
            ?: data.stringOrNull("nickname")
            ?: data.stringOrNull("nick")
        val encryptedUin = creator?.stringOrNull("encrypt_uin")
            ?: creator?.stringOrNull("encryptUin")
            ?: creator?.stringOrNull("encuin")
            ?: creator?.stringOrNull("euin")
            ?: data.stringOrNull("encrypt_uin")
            ?: data.stringOrNull("encryptUin")
            ?: data.stringOrNull("encuin")
            ?: data.stringOrNull("euin")

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
            encryptedUin = encryptedUin?.takeIf { it.isNotBlank() },
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
            throw providerBusinessException(ID, code, root.errorMessage())
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

    private fun playlistFromFavorite(item: JsonObject): ProviderPlaylist? {
        val identifier = item.string("tid")
            .ifBlank { item.string("dissid") }
            .ifBlank { item.string("id") }
            .takeIf(String::isNotBlank)
            ?: return null
        return ProviderPlaylist(
            id = playlistKey(ID, identifier),
            title = item.string("title")
                .ifBlank { item.string("dissname") }
                .ifBlank { item.string("name") }
                .ifBlank { "未命名歌单" },
            providerId = ID,
            providerName = NAME,
            coverUrl = item.stringOrNull("picurl")
                ?: item.stringOrNull("picUrl")
                ?: item.stringOrNull("cover")
                ?: item.stringOrNull("logo")
                ?: item.stringOrNull("albumPicUrl"),
            description = item.string("desc").ifBlank { item.string("nickname") },
            playCount = item.long("play_cnt")
                ?: item.long("listennum")
                ?: item.long("listen_num"),
            trackCount = item.int("songnum")
                ?: item.int("songNum")
                ?: item.int("song_cnt"),
            providerUrl = "https://y.qq.com/n/ryqq/playlist/$identifier",
        )
    }

    private fun artistFromFollow(item: JsonObject): ProviderMediaItem? {
        val identifier = item.string("MID")
            .ifBlank { item.string("mid") }
            .ifBlank { item.string("SingerMid") }
            .ifBlank { item.string("singerMid") }
            .takeIf(String::isNotBlank)
            ?: return null
        return ProviderMediaItem(
            id = mediaItemKey(ProviderMediaItemType.Artist, ID, identifier),
            title = item.string("Name")
                .ifBlank { item.string("name") }
                .ifBlank { item.string("SingerName") },
            providerId = ID,
            providerName = NAME,
            type = ProviderMediaItemType.Artist,
            coverUrl = item.stringOrNull("AvatarUrl")
                ?: item.stringOrNull("avatarUrl")
                ?: item.stringOrNull("picUrl")
                ?: artistCover(identifier),
            description = item.string("Desc").ifBlank { item.string("desc") },
            providerUrl = "https://y.qq.com/n/ryqq/singer/$identifier",
        )
    }

    private fun albumFromFavorite(item: JsonObject): ProviderMediaItem? {
        val albumId = item.string("id")
            .ifBlank { item.string("albumID") }
            .ifBlank { item.string("albumId") }
        val mid = item.string("mid")
            .ifBlank { item.string("albumMid") }
            .ifBlank { item.string("albumMID") }
            .ifBlank { item.string("pmid") }
        val identifier = albumId.ifBlank { mid }.takeIf(String::isNotBlank) ?: return null
        val artists = firstNonEmpty(item.array("v_singer"), item.array("singer"))
            .map { value ->
                val singer = value.asObject()
                singer.string("name").ifBlank { singer.string("singerName") }
            }
            .filter(String::isNotBlank)
            .joinToString(" / ")
        return ProviderMediaItem(
            id = mediaItemKey(ProviderMediaItemType.Album, ID, identifier),
            title = item.string("name")
                .ifBlank { item.string("albumName") }
                .ifBlank { item.string("title") },
            providerId = ID,
            providerName = NAME,
            type = ProviderMediaItemType.Album,
            coverUrl = item.stringOrNull("picurl")
                ?: item.stringOrNull("picUrl")
                ?: item.stringOrNull("logo")
                ?: mid.takeIf(String::isNotBlank)?.let(::albumCover),
            description = artists,
            providerUrl = "https://y.qq.com/n/ryqq/albumDetail/$identifier",
            trackCount = item.int("songnum")
                ?: item.int("songNum")
                ?: item.int("song_count"),
        )
    }

    private suspend fun currentAccountIds(): List<String> {
        val values = qqCookies()
        if (values.isEmpty()) return emptyList()
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

    private suspend fun currentEncryptedUin(): String? {
        val values = qqCookies()
        return listOf("encryptUin", "encrypt_uin", "encuin", "euin")
            .asSequence()
            .mapNotNull { key -> values[key]?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
    }

    private suspend fun qqCookies(): Map<String, String> {
        val stored = credentials.read(ID) ?: return emptyMap()
        return parseCookies(stored.cookieHeader.orEmpty()) + stored.cookies
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

    private suspend fun rpc(payload: String): JsonObject {
        val request = qqRpcPayload(payload)
        return http.getText(
            providerId = ID,
            url = queryUrl(
                "$RPC_BASE/cgi-bin/musicu.fcg",
                mapOf(
                    "_" to currentTimeMillis().toString(),
                    "sign" to contentSign(request),
                    "data" to request,
                ),
            ),
            headers = authenticatedHeaders("https://y.qq.com/"),
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
    }

    private suspend fun qqRpcPayload(payload: String): String {
        val root = providerJson.parseToJsonElement(payload).jsonObject
        val values = qqCookies()
        val uin = values["wxuin"]?.removePrefix("o")?.takeIf(String::isNotBlank)
            ?: values["uin"]?.removePrefix("o")?.takeIf(String::isNotBlank)
            ?: values["str_musicid"]?.takeIf(String::isNotBlank)
            ?: values["musicid"]?.takeIf(String::isNotBlank)
            ?: "0"
        val tokenSource = listOf("qqmusic_key", "p_skey", "skey", "p_lskey", "lskey")
            .asSequence()
            .mapNotNull { values[it] }
            .firstOrNull()
            .orEmpty()
        val token = if (tokenSource.isBlank()) {
            5_381L
        } else {
            var hash = 5_381L
            tokenSource.forEach { character ->
                hash = (hash * 33 + character.code) and 0xffff_ffffL
            }
            hash and 0x7fff_ffffL
        }
        val common = mapOf(
            "loginUin" to JsonPrimitive(uin),
            "hostUin" to JsonPrimitive(0),
            "g_tk" to JsonPrimitive(token),
            "inCharset" to JsonPrimitive("utf8"),
            "outCharset" to JsonPrimitive("utf-8"),
            "notice" to JsonPrimitive(0),
            "platform" to JsonPrimitive("yqq"),
            "needNewCode" to JsonPrimitive(0),
        )
        val mergedComm = JsonObject(common + (root.obj("comm") ?: emptyMap()))
        return providerJson.encodeToString(
            JsonObject.serializer(),
            JsonObject(root + ("comm" to mergedComm)),
        )
    }

    private fun rpcData(root: JsonObject, key: String): JsonObject {
        val envelope = root.obj(key) ?: root
        val code = envelope.int("code")
        if (code != null && code != 0) {
            throw providerBusinessException(ID, code, envelope.errorMessage())
        }
        return envelope.obj("data") ?: envelope
    }

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

    private fun jsonString(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun contentSign(data: String): String {
        val randomPart = buildString {
            repeat(Random.nextInt(10, 17)) {
                append(CONTENT_SIGN_ALPHABET[Random.nextInt(CONTENT_SIGN_ALPHABET.length)])
            }
        }
        return "zza$randomPart${md5Hex("CJBPACrRuNy7$data")}" 
    }

    private fun albumCover(mid: String): String =
        "https://y.gtimg.cn/music/photo_new/T002R300x300M000$mid.jpg"

    private fun artistCover(mid: String): String =
        "https://y.gtimg.cn/music/photo_new/T001R300x300M000$mid.jpg"

    private fun firstNonEmpty(vararg values: List<kotlinx.serialization.json.JsonElement>): List<kotlinx.serialization.json.JsonElement> =
        values.firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun JsonObject.errorMessage(): String =
        string("message").ifBlank { string("msg") }.ifBlank { string("errmsg") }.ifBlank { string("error") }

    private fun Throwable.providerMessage(fallback: String): String =
        providerFailureOrNull(ID)?.userMessage ?: fallback

    private fun CreatedPlaylistsResult?.orEmptyPlaylists(): List<ProviderPlaylist> = this?.playlists.orEmpty()

    private data class CreatedPlaylistsResult(
        val userName: String? = null,
        val playlists: List<ProviderPlaylist> = emptyList(),
    )

    private data class ProfileHomeResult(
        val userName: String? = null,
        val encryptedUin: String? = null,
        val playlists: List<ProviderPlaylist> = emptyList(),
        val hasPlaylistPayload: Boolean = false,
    ) {
        fun isMeaningful(): Boolean =
            userName != null || encryptedUin != null || playlists.isNotEmpty() || hasPlaylistPayload
    }

    private companion object {
        const val ID = "qqmusic"
        const val NAME = "QQ 音乐"
        const val BASE = "https://c.y.qq.com"
        const val PROFILE_BASE = "https://c6.y.qq.com"
        const val RPC_BASE = "https://u.y.qq.com"
        const val FAVORITE_DIR_ID = 201
        const val PRIVATE_PLAYLIST_CODE = 4000
        const val WECHAT_LOGIN_TYPE = 2
        const val MIN_QQ_UIN_LENGTH = 5
        const val MAX_QQ_UIN_LENGTH = 12
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        const val CONTENT_SIGN_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}

internal data class QQMusicUserLibrarySnapshot(
    val userName: String? = null,
    val playlists: List<ProviderPlaylist> = emptyList(),
)
