package org.feeluown.mobile.provider.qqmusic

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.feeluown.mobile.ProviderFailure
import org.feeluown.mobile.ProviderFailureKind
import org.feeluown.mobile.ProviderOperationException
import org.feeluown.mobile.provider.core.ProviderSessionCredential
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.string
import org.feeluown.mobile.provider.core.stringOrNull
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderRequestKind
import org.feeluown.mobile.provider.core.network.currentTimeMillis
import org.feeluown.mobile.providerBusinessException
import org.feeluown.mobile.providerContractException
import org.feeluown.mobile.providerFailureOrNull

/**
 * QQ Music authorization-code login and session renewal.
 *
 * The QQ Music web login only ever yields cookies, and those cookies cannot be renewed on their own:
 * once `qm_keyst`/qqmusic_key expire the account is simply logged out. The OAuth redirect that ends
 * the login flow does carry a single-use `code` though, and exchanging it at `musicu.fcg` returns a
 * `musickey` (3 day lifetime) plus a `refresh_token` (60 day grant) that can mint new keys.
 *
 * Everything below was verified against the live service:
 *
 * 1. exchange - the `code` is single-use; replaying it returns `req.code == 1000`.
 * 2. renewal - does not require `refresh_key` (that field is empty for this login type) and returns
 *    a brand new `musickey` together with a rotated `refresh_token`/`access_token`.
 * 3. transport quirk - the body is a JSON *string*, not a form, and at least one verified capture
 *    advertised `Content-Type: application/x-www-form-urlencoded`. A live probe (same payload with
 *    `form-urlencoded` and `application/json`) returned the identical 668-byte `req.code = 1000`
 *    answer, so the gateway parses the raw body and the client's default JSON content type is fine.
 *
 * Session cookies (`uin`, `qm_keyst`, `qqmusic_key`) are synthesized from the session so every
 * existing QQ Music request path keeps working unchanged.
 */
internal object QQMusicAuthorization {
    /** `musicu.fcg` path appended to [QQMusicProviderDefinition.VKEY_BASE]. */
    const val ENDPOINT_PATH = "/cgi-bin/musicu.fcg"

    const val EXCHANGE_MODULE = "QQConnectLogin.LoginServer"
    const val EXCHANGE_METHOD = "QQLogin"
    const val RENEW_MODULE = "music.login.LoginServer"
    const val RENEW_METHOD = "Login"

    /** `comm` block shared by both calls (matches the verified live requests). */
    const val COMM_G_TK = 5381L
    const val COMM_PLATFORM = "yqq"
    const val COMM_CT = 24
    const val COMM_CV = 0

    /** Login flavour QQ Music expects when renewing: verified literal for this login type. */
    const val RENEW_LOGIN_MODE = 2

    /** Measured `musickey` lifetime (3 days) used when the response omits `keyExpiresIn`. */
    const val DEFAULT_AUTH_KEY_LIFETIME_MILLIS = 259_200_000L

    const val REFERER = "https://y.qq.com/"
}

/**
 * A renewable QQ Music session obtained from a browser login authorization code.
 */
internal data class QQMusicAuthorizationResult(
    val session: ProviderSessionCredential,
) {
    /** Cookies synthesized from [session] so the existing QQ Music request paths keep working. */
    fun cookies(): Map<String, String> = qqSessionCookies(session)
}

/**
 * Exchanges the single-use OAuth `code` captured from the QQ Music login redirect for a renewable
 * session.
 *
 * [loginType] is part of the browser authorize URL (`login_type` in the redirect); the verified
 * `QQLogin` exchange derives the login flavour from the code itself, so it is not sent here.
 *
 * @throws ProviderOperationException with [ProviderFailureKind.LoginExpired] for expired/invalid
 *   codes (1000/104400/104401) and with the mapped failure kind for business or transport problems.
 */
internal suspend fun exchangeAuthorizationCode(
    http: ProviderHttpClient,
    code: String,
    loginType: Int? = null,
    nowMillis: Long = currentTimeMillis(),
): QQMusicAuthorizationResult {
    val authorizationCode = code.trim()
    if (authorizationCode.isEmpty()) {
        throw providerContractException(
            QQMusicProviderDefinition.ID,
            "QQ Music login redirect carried an empty authorization code",
        )
    }
    val data = requestSessionData(
        http = http,
        payload = exchangePayload(authorizationCode),
        operation = "QQ Music authorization code exchange",
    )
    return QQMusicAuthorizationResult(
        session = sessionFromPayload(data, nowMillis = nowMillis, previous = null),
    )
}

/**
 * Renews [session] with its `refresh_token` and returns the new session material.
 *
 * Values the response omits keep the previous session's values (in particular the rotated
 * `refresh_token`: rotation is unverified, so a response without it must not forget the old one).
 */
internal suspend fun renewSession(
    http: ProviderHttpClient,
    session: ProviderSessionCredential,
    nowMillis: Long = currentTimeMillis(),
): ProviderSessionCredential {
    val refreshToken = session.refreshToken?.takeIf(String::isNotBlank)
        ?: throw ProviderOperationException(
            ProviderFailure(
                kind = ProviderFailureKind.LoginExpired,
                providerId = QQMusicProviderDefinition.ID,
                technicalMessage = "QQ Music session has no refresh token and cannot be renewed",
            ),
        )
    val data = requestSessionData(
        http = http,
        payload = renewPayload(session, refreshToken),
        operation = "QQ Music session renewal",
    )
    return sessionFromPayload(data, nowMillis = nowMillis, previous = session)
}

/**
 * Cookies QQ Music expects for an authenticated session.
 *
 * The web page and the reference client both map `uin = musicid` (decimal, no legacy `o` prefix) and
 * `qm_keyst = qqmusic_key = musickey`.
 */
internal fun qqSessionCookies(session: ProviderSessionCredential): Map<String, String> = buildMap {
    session.accountId
        ?.let { qqAccountId(mapOf("musicid" to it)) }
        ?.let { put("uin", it) }
    session.authKey?.takeIf(String::isNotBlank)?.let { authKey ->
        put("qm_keyst", authKey)
        put("qqmusic_key", authKey)
    }
}

private suspend fun requestSessionData(
    http: ProviderHttpClient,
    payload: String,
    operation: String,
): JsonObject {
    val body = try {
        http.postJson(
            providerId = QQMusicProviderDefinition.ID,
            url = QQMusicProviderDefinition.VKEY_BASE + QQMusicAuthorization.ENDPOINT_PATH,
            json = payload,
            headers = mapOf(
                "User-Agent" to QQ_MUSIC_USER_AGENT,
                "Referer" to QQMusicAuthorization.REFERER,
            ),
            kind = ProviderRequestKind.Auth,
            cacheKey = null,
        ).value
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        throw failure.asAuthorizationFailure(operation)
    }

    val root = try {
        parseQQMusicObject(QQMusicProviderDefinition.ID, body)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        throw failure.asAuthorizationFailure(operation)
    }
    // The account service answers either {"req":{...}} or the un-wrapped object.
    val envelope = root.obj("req") ?: root
    val code = envelope.int("code")
        ?: throw providerContractException(
            QQMusicProviderDefinition.ID,
            "$operation response is missing req.code",
        )
    if (code != 0) {
        throw qqBusinessException(code, envelope.string("msg").ifBlank { "$operation failed" })
    }
    return envelope.obj("data")
        ?: throw providerContractException(
            QQMusicProviderDefinition.ID,
            "$operation response is missing req.data",
        )
}

private fun exchangePayload(code: String): String = encodePayload(
    mapOf(
        "comm" to commObject(),
        "req" to JsonObject(
            mapOf(
                "module" to JsonPrimitive(QQMusicAuthorization.EXCHANGE_MODULE),
                "method" to JsonPrimitive(QQMusicAuthorization.EXCHANGE_METHOD),
                "param" to JsonObject(mapOf("code" to JsonPrimitive(code))),
            ),
        ),
    ),
)

private fun renewPayload(session: ProviderSessionCredential, refreshToken: String): String = encodePayload(
    mapOf(
        "comm" to commObject(),
        "req" to JsonObject(
            mapOf(
                "module" to JsonPrimitive(QQMusicAuthorization.RENEW_MODULE),
                "method" to JsonPrimitive(QQMusicAuthorization.RENEW_METHOD),
                "param" to JsonObject(
                    mapOf(
                        "openid" to JsonPrimitive(session.openId.orEmpty()),
                        "access_token" to JsonPrimitive(session.accessToken.orEmpty()),
                        "refresh_token" to JsonPrimitive(refreshToken),
                        "expired_in" to JsonPrimitive(session.grantExpiresAtMillis / 1_000L),
                        "musicid" to JsonPrimitive(session.accountId?.toLongOrNull() ?: 0L),
                        "musickey" to JsonPrimitive(session.authKey.orEmpty()),
                        // Verified: this login type returns an empty refresh_key and does not need it.
                        "refresh_key" to JsonPrimitive(""),
                        "loginMode" to JsonPrimitive(QQMusicAuthorization.RENEW_LOGIN_MODE),
                    ),
                ),
            ),
        ),
    ),
)

private fun commObject(): JsonObject = JsonObject(
    mapOf(
        "g_tk" to JsonPrimitive(QQMusicAuthorization.COMM_G_TK),
        "platform" to JsonPrimitive(QQMusicAuthorization.COMM_PLATFORM),
        "ct" to JsonPrimitive(QQMusicAuthorization.COMM_CT),
        "cv" to JsonPrimitive(QQMusicAuthorization.COMM_CV),
    ),
)

private fun encodePayload(root: Map<String, JsonObject>): String =
    providerJson.encodeToString(JsonObject.serializer(), JsonObject(root))

/**
 * Maps a `musicu.fcg` account payload onto the shared session credential.
 *
 * Missing fields fall back to [previous] so a renewal response never erases the refresh token or the
 * grant expiry; a freshly minted key without `musickeyCreateTime` is stamped with [nowMillis].
 */
private fun sessionFromPayload(
    data: JsonObject,
    nowMillis: Long,
    previous: ProviderSessionCredential?,
): ProviderSessionCredential {
    val returnedAuthKey = data.stringOrNull("musickey")
    val authKey = returnedAuthKey ?: previous?.authKey
    if (authKey.isNullOrBlank()) {
        throw providerContractException(
            QQMusicProviderDefinition.ID,
            "QQ Music session response is missing musickey",
        )
    }
    val authKeyCreatedAtMillis = data.long("musickeyCreateTime")
        ?.takeIf { it > 0L }
        ?.times(1_000L)
        ?: when {
            returnedAuthKey != null || previous == null -> nowMillis
            else -> previous.authKeyCreatedAtMillis
        }
    val authKeyExpiresInMillis = data.long("keyExpiresIn")
        ?.takeIf { it > 0L }
        ?.times(1_000L)
        ?: previous?.authKeyExpiresInMillis?.takeIf { it > 0L }
        ?: QQMusicAuthorization.DEFAULT_AUTH_KEY_LIFETIME_MILLIS
    val grantExpiresAtMillis = data.long("expired_at")
        ?.takeIf { it > 0L }
        ?.times(1_000L)
        ?: previous?.grantExpiresAtMillis?.takeIf { it > 0L }
        ?: 0L
    val musicId = data.long("musicid")
    return ProviderSessionCredential(
        authKey = authKey,
        refreshToken = data.stringOrNull("refresh_token") ?: previous?.refreshToken,
        accessToken = data.stringOrNull("access_token") ?: previous?.accessToken,
        openId = data.stringOrNull("openid") ?: previous?.openId,
        unionId = data.stringOrNull("unionid") ?: previous?.unionId,
        accountId = musicId?.toString() ?: previous?.accountId,
        encryptedAccountId = data.stringOrNull("encryptUin")
            ?: data.stringOrNull("encrypt_uin")
            ?: previous?.encryptedAccountId,
        authKeyCreatedAtMillis = authKeyCreatedAtMillis,
        authKeyExpiresInMillis = authKeyExpiresInMillis,
        grantExpiresAtMillis = grantExpiresAtMillis,
    )
}

private fun Throwable.asAuthorizationFailure(operation: String): Throwable =
    this as? ProviderOperationException
        ?: providerFailureOrNull(QQMusicProviderDefinition.ID)?.let { failure ->
            ProviderOperationException(failure, this)
        }
        ?: ProviderOperationException(
            ProviderFailure(
                kind = ProviderFailureKind.Unknown,
                providerId = QQMusicProviderDefinition.ID,
                technicalMessage = "$operation failed: " + message.orEmpty(),
            ),
            this,
        )