package org.feeluown.mobile.provider.qqmusic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.feeluown.mobile.ProviderContentSection
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderMediaItem
import org.feeluown.mobile.ProviderMediaItemType
import org.feeluown.mobile.providerBusinessException
import org.feeluown.mobile.providerFailureOrNull
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.array
import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.boolean
import org.feeluown.mobile.provider.core.cookieHeader
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.md5Hex
import org.feeluown.mobile.provider.core.mediaItemKey
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.parseCookies
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.currentTimeMillis
import kotlin.random.Random

/**
 * Loads QQ Music followed singers with the current web musicu request context.
 *
 * The relation service is stricter than the asset endpoints about `comm`: it expects
 * the web ct/cv pair, the logged-in UIN, both g_tk fields and `platform=yqq.json`.
 * Keeping that request here avoids changing the working favorite playlist/album RPCs
 * while still using the same encrypted HostUin contract.
 */
internal class QQMusicFollowedArtistsLoader(
    private val http: ProviderHttpClient,
    private val credentials: ProviderCredentialStore,
) {
    suspend fun load(
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

    private suspend fun encryptedUin(): String {
        val values = qqCookies()
        listOf("encryptUin", "encrypt_uin", "encuin", "euin")
            .asSequence()
            .mapNotNull { key -> values[key]?.trim()?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?.let { return it }

        val accountIds = currentAccountIds(values)
        if (accountIds.isEmpty()) error("QQ Music account id is unavailable")
        var lastFailure: Throwable? = null
        accountIds.forEach { accountId ->
            val euin = runCatching { profileEncryptedUin(accountId) }
                .onFailure { lastFailure = it }
                .getOrNull()
            euin?.takeIf(String::isNotBlank)?.let { return it }
        }
        throw lastFailure ?: IllegalStateException("QQ Music encrypted uin is unavailable")
    }

    private suspend fun profileEncryptedUin(accountId: String): String? {
        val root = http.getText(
            providerId = ID,
            url = queryUrl(
                "$PROFILE_BASE/rsc/fcgi-bin/fcg_get_profile_homepage.fcg",
                mapOf(
                    "cv" to WEB_CV.toString(),
                    "ct" to WEB_CT.toString(),
                    "format" to "json",
                    "inCharset" to "utf-8",
                    "outCharset" to "utf-8",
                    "notice" to "0",
                    "platform" to WEB_PLATFORM,
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
        val data = root.obj("data") ?: return null
        val creator = data.obj("creator")
        return creator?.stringOrNull("encrypt_uin")
            ?: creator?.stringOrNull("encryptUin")
            ?: creator?.stringOrNull("encuin")
            ?: creator?.stringOrNull("euin")
            ?: data.stringOrNull("encrypt_uin")
            ?: data.stringOrNull("encryptUin")
            ?: data.stringOrNull("encuin")
            ?: data.stringOrNull("euin")
    }

    private suspend fun rpc(payload: String): JsonObject {
        val request = rpcPayload(payload)
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

    private suspend fun rpcPayload(payload: String): String {
        val root = providerJson.parseToJsonElement(payload).jsonObject
        val values = qqCookies()
        val uin = currentAccountIds(values).firstOrNull() ?: "0"
        val token = qqToken(values)
        val common = mapOf(
            "cv" to JsonPrimitive(WEB_CV),
            "ct" to JsonPrimitive(WEB_CT),
            "uin" to JsonPrimitive(uin),
            "g_tk" to JsonPrimitive(token),
            "g_tk_new_20200303" to JsonPrimitive(token),
            "format" to JsonPrimitive("json"),
            "inCharset" to JsonPrimitive("utf-8"),
            "outCharset" to JsonPrimitive("utf-8"),
            "notice" to JsonPrimitive(0),
            "platform" to JsonPrimitive(WEB_PLATFORM),
            "needNewCode" to JsonPrimitive(1),
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
                ?: item.stringOrNull("SingerPic")
                ?: item.stringOrNull("picUrl")
                ?: artistCover(identifier),
            description = item.string("Desc").ifBlank { item.string("desc") },
            providerUrl = "https://y.qq.com/n/ryqq/singer/$identifier",
        )
    }

    private suspend fun qqCookies(): Map<String, String> {
        val stored = credentials.read(ID) ?: return emptyMap()
        return parseCookies(stored.cookieHeader.orEmpty()) + stored.cookies
    }

    private fun currentAccountIds(values: Map<String, String>): List<String> {
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

    private fun normalizeAccountId(raw: String?): String? {
        val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
        val digits = when {
            value.all(Char::isDigit) -> value
            value.startsWith('o') && value.drop(1).isNotEmpty() && value.drop(1).all(Char::isDigit) -> value.drop(1)
            else -> return null
        }
        return digits.takeIf { it.any { character -> character != '0' } }
    }

    private fun qqToken(values: Map<String, String>): Long {
        val tokenSource = listOf("qqmusic_key", "p_skey", "skey", "p_lskey", "lskey")
            .asSequence()
            .mapNotNull { values[it] }
            .firstOrNull()
            .orEmpty()
        if (tokenSource.isBlank()) return 5_381L
        var hash = 5_381L
        tokenSource.forEach { character ->
            hash = (hash * 33 + character.code) and 0xffff_ffffL
        }
        return hash and 0x7fff_ffffL
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

    private fun artistCover(mid: String): String =
        "https://y.gtimg.cn/music/photo_new/T001R300x300M000$mid.jpg"

    private fun firstNonEmpty(vararg values: List<kotlinx.serialization.json.JsonElement>): List<kotlinx.serialization.json.JsonElement> =
        values.firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun JsonObject.errorMessage(): String =
        string("message").ifBlank { string("msg") }.ifBlank { string("errmsg") }.ifBlank { string("error") }

    private fun Throwable.providerMessage(fallback: String): String =
        providerFailureOrNull(ID)?.userMessage ?: fallback

    private companion object {
        const val ID = "qqmusic"
        const val NAME = "QQ 音乐"
        const val PROFILE_BASE = "https://c6.y.qq.com"
        const val RPC_BASE = "https://u.y.qq.com"
        const val WECHAT_LOGIN_TYPE = 2
        const val WEB_CT = 24
        const val WEB_CV = 4_747_474
        const val WEB_PLATFORM = "yqq.json"
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        const val CONTENT_SIGN_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    }
}
