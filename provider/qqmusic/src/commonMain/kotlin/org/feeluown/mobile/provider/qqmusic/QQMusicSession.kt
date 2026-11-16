package org.feeluown.mobile.provider.qqmusic

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.feeluown.mobile.ProviderOperationException
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.asObject
import org.feeluown.mobile.provider.core.cookieHeader
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.parseCookies
import org.feeluown.mobile.providerBusinessException
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderRequestKind
import org.feeluown.mobile.provider.core.network.currentTimeMillis

/**
 * QQ Music signals over `musicu.fcg`.
 *
 * The web session (a `qm_keyst` + `uin` pair captured from the login WebView) is accepted
 * locally long after the server dropped it. The only reliable way to tell "logged in" from
 * "expired" is to ask the account service, which answers with a business code instead of an
 * HTTP status. Code 1000 is what QQ Music returns for an expired or missing session; 104400 and
 * 104401 are its "auth parameter invalid" siblings.
 */
internal object QQMusicSession {
    const val MODULE = "music.UserInfo.userInfoServer"
    const val METHOD = "GetLoginUserInfo"

    /** Business codes that mean "sign in again", not "the response format changed". */
    val LOGIN_EXPIRED_CODES: Set<Int> = setOf(1000, 104400, 104401)

    /** Reuse a probe result for this long so catalogue refreshes do not hammer the account service. */
    const val PROBE_TTL_MILLIS = 60_000L
}

/** Classifies QQ Music session codes as login expiry instead of an upstream contract change. */
internal fun qqBusinessException(code: Int?, message: String): ProviderOperationException =
    providerBusinessException(
        providerId = QQMusicProviderDefinition.ID,
        code = code,
        message = message,
        loginExpiredCodes = QQMusicSession.LOGIN_EXPIRED_CODES,
    )

/**
 * Asks QQ Music whether the stored web session is still accepted by the server.
 *
 * Returns `null` when the probe itself cannot decide (offline, unknown business code) so callers
 * keep the locally stored session instead of signing the user out on a flaky network.
 */
internal class QQMusicSessionValidator(
    private val http: ProviderHttpClient,
    private val credentials: ProviderCredentialStore,
    private val ttlMillis: Long = QQMusicSession.PROBE_TTL_MILLIS,
    private val nowMillis: () -> Long = { currentTimeMillis() },
) {
    private val mutex = Mutex()
    private var cachedCookieFingerprint: String? = null
    private var cachedAlive: Boolean? = null
    private var cachedAtMillis: Long = 0L

    suspend fun isSessionAlive(): Boolean? {
        val fingerprint = cookieFingerprint() ?: return null
        mutex.withLock {
            val cached = cachedAlive
            val fresh = nowMillis() - cachedAtMillis <= ttlMillis
            if (cached != null && fresh && cachedCookieFingerprint == fingerprint) return cached
        }
        val alive = runCatching { probe() }.getOrNull()
        mutex.withLock {
            cachedCookieFingerprint = fingerprint
            cachedAlive = alive
            cachedAtMillis = nowMillis()
        }
        return alive
    }

    /**
     * The last verdict for the *currently stored* cookies, or `null` when this process has not
     * probed them yet. Never performs I/O, so [QQMusicCompositeProvider.authState] can consult it.
     */
    suspend fun cachedVerdict(): Boolean? = mutex.withLock {
        val fingerprint = cookieFingerprint() ?: return@withLock null
        cachedAlive?.takeIf { cachedCookieFingerprint == fingerprint }
    }

    private suspend fun cookieFingerprint(): String? {
        val stored = credentials.read(QQMusicProviderDefinition.ID) ?: return null
        val cookies = parseCookies(stored.cookieHeader.orEmpty()) + stored.cookies
        if (cookies.isEmpty()) return null
        val value = FINGERPRINT_KEYS
            .mapNotNull { key -> cookies[key]?.takeIf(String::isNotBlank)?.let { "$key=$it" } }
            .joinToString(";")
        return value.takeIf(String::isNotBlank)
    }

    private suspend fun probe(): Boolean? {
        val stored = credentials.read(QQMusicProviderDefinition.ID) ?: return null
        val cookies = parseCookies(stored.cookieHeader.orEmpty()) + stored.cookies
        val uin = qqAccountId(cookies) ?: "0"
        val payload = providerJson.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                mapOf(
                    "req_0" to JsonObject(
                        mapOf(
                            "module" to JsonPrimitive(QQMusicSession.MODULE),
                            "method" to JsonPrimitive(QQMusicSession.METHOD),
                            "param" to JsonObject(emptyMap()),
                        ),
                    ),
                    "comm" to JsonObject(
                        mapOf(
                            "loginUin" to JsonPrimitive(uin),
                            "hostUin" to JsonPrimitive(0),
                            "g_tk" to JsonPrimitive(qqToken(cookies)),
                            "inCharset" to JsonPrimitive("utf8"),
                            "outCharset" to JsonPrimitive("utf-8"),
                            "notice" to JsonPrimitive(0),
                            "platform" to JsonPrimitive("yqq"),
                            "needNewCode" to JsonPrimitive(0),
                        ),
                    ),
                ),
            ),
        )
        val root = http.getText(
            providerId = QQMusicProviderDefinition.ID,
            url = QQMusicProviderDefinition.VKEY_BASE +
                "/cgi-bin/musicu.fcg?_=" +
                currentTimeMillis() +
                "&data=" +
                encodeUrlComponent(payload),
            headers = buildMap {
                put("User-Agent", QQ_MUSIC_USER_AGENT)
                put("Referer", "https://y.qq.com/")
                cookieHeader(stored).takeIf(String::isNotBlank)?.let { put("Cookie", it) }
            },
            kind = ProviderRequestKind.Auth,
            cacheKey = null,
        ).value.let { providerJson.parseToJsonElement(it).asObject() }
        val envelope = root.obj("req_0") ?: root
        val code = envelope.int("code") ?: return null
        return when {
            code == 0 -> true
            code in QQMusicSession.LOGIN_EXPIRED_CODES -> false
            else -> null
        }
    }

    private fun qqToken(cookies: Map<String, String>): Long {
        val tokenSource = listOf("qqmusic_key", "p_skey", "skey", "p_lskey", "lskey")
            .asSequence()
            .mapNotNull { cookies[it] }
            .firstOrNull()
            .orEmpty()
        if (tokenSource.isBlank()) return 5_381L
        var hash = 5_381L
        tokenSource.forEach { character -> hash = (hash * 33 + character.code) and 0xffff_ffffL }
        return hash and 0x7fff_ffffL
    }

    private companion object {
        val FINGERPRINT_KEYS = listOf("uin", "qqmusic_key", "qm_keyst", "wxuin", "p_skey", "skey")
    }
}

/**
 * QQ Music account id from the stored web cookies.
 *
 * The login WebView writes `uin` in the legacy `o<digits>` form while every musicu request expects
 * the bare numeric id. Normalizing in one place keeps the signed payloads (vkey, playlist writes,
 * the session probe) consistent, which is otherwise an intermittent "login expired" source.
 */
internal fun qqAccountId(cookies: Map<String, String>): String? {
    val raw = cookies["wxuin"]
        ?.takeIf(String::isNotBlank)
        ?: cookies["uin"]?.takeIf(String::isNotBlank)
        ?: cookies["str_musicid"]?.takeIf(String::isNotBlank)
        ?: cookies["musicid"]?.takeIf(String::isNotBlank)
        ?: return null
    val normalized = raw.trim().removePrefix("o").takeIf(String::isNotBlank) ?: return null
    return normalized.takeIf { value -> value.all(Char::isDigit) && value.any { it != '0' } }
        ?: raw.trim()
}

internal const val QQ_MUSIC_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

internal fun encodeUrlComponent(value: String): String = buildString {
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

/**
 * Parses a QQ Music response body into a JSON object.
 *
 * QQ Music answers with an HTML risk-control or gateway page instead of JSON when it throttles a
 * client. Letting that surface as a kotlinx serialization failure classified the whole provider as
 * [org.feeluown.mobile.ProviderFailureKind.UpstreamContractChanged] - i.e. users were told to
 * "更新应用" for a temporary block (issue #283). Treating a non-JSON body as a transport problem
 * keeps the "接口响应已变化" message for genuine schema changes only.
 */
internal fun parseQQMusicObject(providerId: String, body: String): JsonObject {
    val trimmed = body.trimStart('\uFEFF').trim()
    val looksLikeJson = trimmed.startsWith('{') || trimmed.startsWith('[')
    if (!looksLikeJson && trimmed.contains('<')) {
        throw org.feeluown.mobile.ProviderOperationException(
            org.feeluown.mobile.ProviderFailure(
                kind = org.feeluown.mobile.ProviderFailureKind.ContentUnavailable,
                providerId = providerId,
                technicalMessage = "QQ Music returned a non-JSON response (risk control or gateway page)",
            ),
        )
    }
    return org.feeluown.mobile.provider.core.providerJson.parseToJsonElement(body).asObject()
}

