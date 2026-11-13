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
 * QQ Music accepts two account ecosystems and each has its own protocol: QQ accounts use the
 * `QQConnectLogin.LoginServer/QQLogin` exchange, WeChat accounts use `music.login.LoginServer/Login`
 * with `strAppid`; their renewal request shapes differ too (see [QQMusicLoginMethod]).
 *
 * Everything below was verified against the live service (QQ path):
 *
 * 1. exchange - the `code` is single-use; replaying it returns `req.code == 1000`.
 * 2. renewal - does not require `refresh_key` (that field is empty for this login type) and returns
 *    a brand new `musickey` together with a rotated `refresh_token`/`access_token`.
 * 3. transport quirk - the body is a JSON *string*, not a form, and at least one verified capture
 *    advertised `Content-Type: application/x-www-form-urlencoded`. A live probe (same payload with
 *    `form-urlencoded` and `application/json`) returned the identical 668-byte `req.code = 1000`
 *    answer, so the gateway parses the raw body and the client's default JSON content type is fine.
 *
 * The WeChat payloads were captured from QQ Music's own redirect page / reference client and were
 * NOT executed against the live service; they follow the same response schema as the QQ exchange.
 *
 * Session cookies (`uin`, `qm_keyst`, `qqmusic_key`, `login_type` and the `wx*` identity cookies for
 * WeChat sessions) are synthesized from the session so every existing QQ Music request path keeps
 * working unchanged.
 */
internal object QQMusicAuthorization {
    /** `musicu.fcg` path appended to [QQMusicProviderDefinition.VKEY_BASE]. */
    const val ENDPOINT_PATH = "/cgi-bin/musicu.fcg"

    /** QQ account exchange (verified live). */
    const val EXCHANGE_MODULE = "QQConnectLogin.LoginServer"
    const val EXCHANGE_METHOD = "QQLogin"

    /** WeChat account exchange reuses the login server module (captured from y.qq.com). */
    const val WECHAT_EXCHANGE_MODULE = "music.login.LoginServer"
    const val WECHAT_EXCHANGE_METHOD = "Login"

    /** Renewal module shared by both login methods. */
    const val RENEW_MODULE = "music.login.LoginServer"
    const val RENEW_METHOD = "Login"

    /**
     * WeChat open-platform app id QQ Music's own `wx_redirect.html?login_type=2` login uses; sent as
     * `strAppid` on the WeChat exchange.
     */
    const val WECHAT_APP_ID = "wx48db31d50e334801"

    /** `comm.tmeAppID` value the WeChat exchange sends. */
    const val TME_APP_ID = "qqmusic"

    /** `login_type` cookie / redirect value for a QQ account login. */
    const val LOGIN_TYPE_QQ = 1

    /** `login_type` cookie / redirect value for a WeChat account login. */
    const val LOGIN_TYPE_WECHAT = 2

    /** `comm` block shared by the calls (matches the verified live requests). */
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
 * QQ Music account ecosystem behind an authorization code.
 *
 * The two ecosystems differ in three places, all captured from QQ Music's own web login:
 * - [redirectLoginType] is the `login_type` value QQ Music appends to its own redirect
 *   (`y.qq.com/portal/wx_redirect.html?login_type=...`); it is also the `login_type` cookie every
 *   request path uses to tell a WeChat session from a QQ one.
 * - [tmeLoginType] is the `comm.tmeLoginType` value of the renewal request. The reference client
 *   sends it as an int for QQ and as a string for WeChat, exactly as encoded below.
 * - [tag] is what gets persisted in [ProviderSessionCredential.loginMethod] so a later renewal knows
 *   which request shape to use.
 */
internal enum class QQMusicLoginMethod(
    val redirectLoginType: Int,
    /** `comm.tmeLoginType` the renewal sends for this ecosystem. */
    val tmeLoginType: Int,
    val tag: String,
) {
    /** QQ account: `QQConnectLogin.LoginServer/QQLogin` exchange, QQ renewal shape. */
    Qq(
        redirectLoginType = QQMusicAuthorization.LOGIN_TYPE_QQ,
        tmeLoginType = 2,
        tag = "qq",
    ),

    /** WeChat account: `music.login.LoginServer/Login` + `strAppid` exchange, `str_musicid` renewal. */
    Wechat(
        redirectLoginType = QQMusicAuthorization.LOGIN_TYPE_WECHAT,
        tmeLoginType = 1,
        tag = "wechat",
    ),
    ;

    companion object {
        /** Method carried by the redirect's `login_type`; anything unknown (or absent) is QQ. */
        fun fromRedirectLoginType(loginType: Int?): QQMusicLoginMethod =
            entries.firstOrNull { it.redirectLoginType == loginType } ?: Qq

        /** Method persisted in a session; a legacy session without a tag is a QQ session. */
        fun fromTag(tag: String?): QQMusicLoginMethod =
            entries.firstOrNull { it.tag == tag } ?: Qq
    }
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
 * [loginType] is the redirect's `login_type` (`1` = QQ, `2` = WeChat); it selects both the exchange
 * payload and the cookies synthesized from the response, and is recorded in
 * [ProviderSessionCredential.loginMethod] so the renewal later uses the matching shape.
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
    val method = QQMusicLoginMethod.fromRedirectLoginType(loginType)
    val data = requestSessionData(
        http = http,
        payload = exchangePayload(authorizationCode, method),
        operation = exchangeOperation(method),
    )
    return QQMusicAuthorizationResult(
        session = sessionFromPayload(
            data = data,
            nowMillis = nowMillis,
            previous = null,
            loginMethod = method,
        ),
    )
}

/**
 * Renews [session] with its `refresh_token` and returns the new session material.
 *
 * The request shape follows [ProviderSessionCredential.loginMethod]: QQ sessions send
 * `access_token`/`expired_in`/`musicid`, WeChat sessions send `str_musicid`/`unionid` instead.
 * A legacy session without a tag renews as QQ, so already-stored sessions keep working.
 *
 * Values the response omits keep the previous session's values (in particular the rotated
 * `refresh_token`: rotation is unverified, so a response without it must not forget the old one).
 */
internal suspend fun renewSession(
    http: ProviderHttpClient,
    session: ProviderSessionCredential,
    nowMillis: Long = currentTimeMillis(),
): ProviderSessionCredential {
    val method = QQMusicLoginMethod.fromTag(session.loginMethod)
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
        payload = renewPayload(session, refreshToken, method),
        operation = "QQ Music session renewal",
    )
    return sessionFromPayload(
        data = data,
        nowMillis = nowMillis,
        previous = session,
        loginMethod = method,
    )
}

/**
 * Cookies QQ Music expects for an authenticated session.
 *
 * The web page and the reference client both map `uin = musicid` (decimal, no legacy `o` prefix) and
 * `qm_keyst = qqmusic_key = musickey`. `login_type` tells the provider's own account-scoped request
 * paths which ecosystem the session belongs to (1 = QQ, 2 = WeChat). A WeChat session additionally
 * needs its `wx*` identity cookies, because the musicu account APIs read `wxopenid`/`wxunionid` and
 * the numeric id from `str_musicid`/`musicid`.
 *
 * [method] defaults to the session's persisted [ProviderSessionCredential.loginMethod], so a legacy
 * session without that tag still gets QQ cookies.
 */
internal fun qqSessionCookies(
    session: ProviderSessionCredential,
    method: QQMusicLoginMethod = QQMusicLoginMethod.fromTag(session.loginMethod),
): Map<String, String> = buildMap {
    val accountId = session.accountId
        ?.let { qqAccountId(mapOf("musicid" to it)) }
    session.authKey?.takeIf(String::isNotBlank)?.let { authKey ->
        put("qm_keyst", authKey)
        put("qqmusic_key", authKey)
    }
    accountId?.let { put("uin", it) }
    put("login_type", method.redirectLoginType.toString())
    if (method == QQMusicLoginMethod.Wechat) {
        accountId?.let {
            put("wxuin", it)
            put("str_musicid", it)
            put("musicid", it)
        }
        session.openId?.takeIf(String::isNotBlank)?.let { put("wxopenid", it) }
        session.unionId?.takeIf(String::isNotBlank)?.let { put("wxunionid", it) }
    }
}

private fun exchangeOperation(method: QQMusicLoginMethod): String = when (method) {
    QQMusicLoginMethod.Qq -> "QQ Music authorization code exchange"
    QQMusicLoginMethod.Wechat -> "QQ Music WeChat authorization code exchange"
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

private fun loginRequest(module: String, method: String, param: Map<String, JsonPrimitive>): JsonObject =
    JsonObject(
        mapOf(
            "module" to JsonPrimitive(module),
            "method" to JsonPrimitive(method),
            "param" to JsonObject(param),
        ),
    )

private fun exchangePayload(code: String, method: QQMusicLoginMethod): String = when (method) {
    QQMusicLoginMethod.Qq -> encodePayload(
        mapOf(
            "comm" to commObject(),
            "req" to loginRequest(
                module = QQMusicAuthorization.EXCHANGE_MODULE,
                method = QQMusicAuthorization.EXCHANGE_METHOD,
                param = mapOf("code" to JsonPrimitive(code)),
            ),
        ),
    )

    QQMusicLoginMethod.Wechat -> encodePayload(
        mapOf(
            "comm" to commObject(
                mapOf(
                    "tmeAppID" to JsonPrimitive(QQMusicAuthorization.TME_APP_ID),
                    "tmeLoginType" to JsonPrimitive(method.tmeLoginType.toString()),
                ),
            ),
            "req" to loginRequest(
                module = QQMusicAuthorization.WECHAT_EXCHANGE_MODULE,
                method = QQMusicAuthorization.WECHAT_EXCHANGE_METHOD,
                param = mapOf(
                    "strAppid" to JsonPrimitive(QQMusicAuthorization.WECHAT_APP_ID),
                    "code" to JsonPrimitive(code),
                ),
            ),
        ),
    )
}

private fun renewPayload(
    session: ProviderSessionCredential,
    refreshToken: String,
    method: QQMusicLoginMethod,
): String = when (method) {
    QQMusicLoginMethod.Qq -> encodePayload(
        mapOf(
            // QQ Music's reference client sends the credential login type here as an int.
            "comm" to commObject(
                mapOf("tmeLoginType" to JsonPrimitive(method.tmeLoginType)),
            ),
            "req" to loginRequest(
                module = QQMusicAuthorization.RENEW_MODULE,
                method = QQMusicAuthorization.RENEW_METHOD,
                param = mapOf(
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
    )

    QQMusicLoginMethod.Wechat -> encodePayload(
        mapOf(
            // Captured from QQ Music's own page: a *string* here, unlike the QQ renewal.
            "comm" to commObject(
                mapOf("tmeLoginType" to JsonPrimitive(method.tmeLoginType.toString())),
            ),
            "req" to loginRequest(
                module = QQMusicAuthorization.RENEW_MODULE,
                method = QQMusicAuthorization.RENEW_METHOD,
                param = mapOf(
                    "openid" to JsonPrimitive(session.openId.orEmpty()),
                    "refresh_token" to JsonPrimitive(refreshToken),
                    // WeChat sends the account id as a string and never sends access_token/expired_in.
                    "str_musicid" to JsonPrimitive(session.accountId.orEmpty()),
                    "musickey" to JsonPrimitive(session.authKey.orEmpty()),
                    "unionid" to JsonPrimitive(session.unionId.orEmpty()),
                    "refresh_key" to JsonPrimitive(""),
                    "loginMode" to JsonPrimitive(QQMusicAuthorization.RENEW_LOGIN_MODE),
                ),
            ),
        ),
    )
}

private fun commObject(extras: Map<String, JsonPrimitive> = emptyMap()): JsonObject = JsonObject(
    buildMap {
        put("g_tk", JsonPrimitive(QQMusicAuthorization.COMM_G_TK))
        put("platform", JsonPrimitive(QQMusicAuthorization.COMM_PLATFORM))
        put("ct", JsonPrimitive(QQMusicAuthorization.COMM_CT))
        put("cv", JsonPrimitive(QQMusicAuthorization.COMM_CV))
        putAll(extras)
    },
)

private fun encodePayload(root: Map<String, JsonObject>): String =
    providerJson.encodeToString(JsonObject.serializer(), JsonObject(root))

/**
 * Maps a `musicu.fcg` account payload onto the shared session credential.
 *
 * Missing fields fall back to [previous] so a renewal response never erases the refresh token, the
 * login method or the grant expiry; a freshly minted key without `musickeyCreateTime` is stamped
 * with [nowMillis].
 */
private fun sessionFromPayload(
    data: JsonObject,
    nowMillis: Long,
    previous: ProviderSessionCredential?,
    loginMethod: QQMusicLoginMethod,
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
        // The response's own `loginType` does not identify the ecosystem (a QQ exchange answers
        // loginType=2), so the method always comes from the redirect/session.
        loginMethod = loginMethod.tag,
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
