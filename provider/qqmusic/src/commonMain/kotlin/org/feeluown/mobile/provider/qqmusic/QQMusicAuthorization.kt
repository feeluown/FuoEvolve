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

    /**
     * Identity of QQ Music's own **Android client**, required by every scan-login call.
     *
     * `ct`/`cv` tell the gateway which client build is calling. The web identity every other request
     * in this module uses (`ct = 24`, `cv = 0`) is explicitly rejected by these methods: the service
     * answers `req.code = 104610` ("can NOT scan the qrCode with this APP, not in cts-white-list").
     * `CreateQRCode`, `GetQRCodeStatus` and the QR `Login` exchange therefore impersonate the phone
     * client and send these numbers in *both* the `comm` block and the method `param`, exactly as the
     * app itself does.
     */
    const val QR_CLIENT_CT = 11
    const val QR_CLIENT_CV = 20090008

    /**
     * Scan-login module/methods.
     *
     * `Login` is the *QR* exchange (it takes `musicid` + `qrCodeID` + `token`); `QRCodeLogin` is what
     * QQ Music's authorize H5 calls with its own OAuth code and is not needed here.
     */
    const val QR_MODULE = "music.login.LoginServer"
    const val QR_CREATE_METHOD = "CreateQRCode"
    const val QR_STATUS_METHOD = "GetQRCodeStatus"
    const val QR_LOGIN_METHOD = "Login"

    /**
     * QQ Music's OAuth callback page for the scan-login session.
     *
     * `authorize.html` (the page QQ Music itself shows with a QR code) is NOT usable here: its own QQ
     * and WeChat buttons take `client_id`/`appid` from `info.oauth_appid` returned by
     * [QR_STATUS_METHOD], and that field is a verified-empty string for sessions created by this
     * client - the buttons would navigate to `client_id=undefined`.
     *
     * `redirect.html` is the other entry point in the same bundle: it reads the OAuth `code` from its
     * own query and calls `QRCodeLogin` to confirm the session. The app therefore builds the OAuth
     * authorize URL itself and points `redirect_uri` at this page.
     */
    const val QR_REDIRECT_PAGE = "https://y.qq.com/m/client/qr_code_login/redirect.html"

    /**
     * `client_id` of the QQ-account OAuth app y.qq.com itself logs in with.
     *
     * Verified live: the authorize URL built with this id answers 200, lands on QQ's `ptlogin2` page
     * and keeps `redirect_uri` intact.
     */
    const val QQ_OAUTH_CLIENT_ID = "100497308"

    /** QQ OAuth authorize endpoint. */
    const val QQ_OAUTH_AUTHORIZE_URL = "https://graph.qq.com/oauth2.0/authorize"

    /** WeChat OAuth authorize endpoint (the mobile `snsapi_userinfo` flow). */
    const val WECHAT_OAUTH_AUTHORIZE_URL = "https://open.weixin.qq.com/connect/oauth2/authorize"

    /** QQ OAuth scope QQ Music's own login requests. */
    const val QQ_OAUTH_SCOPE = "get_user_info,get_app_friends"

    /** WeChat OAuth scope for the scan login. */
    const val WECHAT_OAUTH_SCOPE = "snsapi_userinfo"

    /** Server-side session lifetime in seconds, used when `CreateQRCode` omits `expiresIn`. */
    const val DEFAULT_QR_SESSION_SECONDS = 900L

    /** How often the app should poll `GetQRCodeStatus`. */
    const val QR_POLL_INTERVAL_MILLIS = 2_000L

    /**
     * `info.status` values treated as "the user confirmed the login".
     *
     * INFERRED, not captured: the live service was only observed in the pre-confirmation phase
     * (`"scaned"`). QQ Music's page switches on this string too, so the set is kept in one place and
     * compared case-insensitively; add values here as real confirmations are observed.
     */
    val QR_CONFIRMED_STATUSES: Set<String> = setOf(
        "confirmed",
        "confirmedlogin",
        "loginsuccess",
        "loginok",
        "success",
        "done",
    )
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
 * A scan-login session created by `music.login.LoginServer/CreateQRCode`.
 *
 * [sessionId] is the 91 character `qrcodeID` QQ Music issued; [authorizeUrl] is the OAuth authorize
 * URL the user opens to approve it. Nothing about the account is known yet - the account arrives
 * with the later status polls.
 */
internal data class QQMusicQrSession(
    val sessionId: String,
    /** OAuth authorize URL (QQ or WeChat) that returns to [QQMusicAuthorization.QR_REDIRECT_PAGE]. */
    val authorizeUrl: String,
    /** Absolute wall-clock deadline of the session (from `CreateQRCode`'s `expiresIn`). */
    val expiresAtMillis: Long,
    /** Login server token from the immediately following status poll; also embedded in the URL. */
    val confirmToken: String,
)

/**
 * One poll of a [QQMusicQrSession], i.e. the scan-login state machine before credentials exist.
 *
 * [Approved] cannot carry a session yet: minting credentials is a second call
 * ([loginWithQrSession]) that only the provider layer performs, because only it can persist them.
 */
internal sealed interface QQMusicQrStatus {
    /** The session is live but no account has been recognized yet. */
    data object Pending : QQMusicQrStatus

    /** An account was recognized and is waiting for the user to confirm in the browser page. */
    data object Scanned : QQMusicQrStatus

    /**
     * The user confirmed; [musicId] + a token are what the QR `Login` exchange needs.
     *
     * [token] is the confirmation token this poll returned, or `null` when the poll omitted it - the
     * caller then reuses the token [createQrSession] obtained right after `CreateQRCode`.
     */
    data class Approved(
        val musicId: Long,
        val token: String?,
        /** `scanUser.loginType`; 1 = QQ account, 2 = WeChat account, `null` when absent. */
        val loginType: Int?,
    ) : QQMusicQrStatus

    /** The session timed out or the login server forgot it; the app must create a new one. */
    data object Expired : QQMusicQrStatus

    /** The login server refused the poll; [message] is user-presentable. */
    data class Failed(val message: String) : QQMusicQrStatus
}

/**
 * Creates a scan-login session and returns its id plus the OAuth authorize URL to open.
 *
 * Two requests, in this order and both required:
 *
 * 1. `CreateQRCode` mints the `qrcodeID`;
 * 2. `GetQRCodeStatus` immediately afterwards - it already carries `data.token` (72 characters,
 *    verified live) before anybody scans, and `redirect.html` needs that token to confirm the
 *    session after the OAuth round trip.
 *
 * The authorize URL is built here rather than by QQ Music's own `authorize.html`, whose QQ/WeChat
 * buttons take their `client_id`/`appid` from `info.oauth_appid` - a verified-empty string for
 * sessions this client creates. See [QQMusicAuthorization.QR_REDIRECT_PAGE].
 *
 * [loginMethod] selects the account ecosystem ([QQMusicLoginMethod.Qq] by default), which decides
 * the OAuth endpoint, client id and scope; both variants return to the same redirect page.
 *
 * @throws ProviderOperationException when the login server refuses to create a session or answers
 *   without the confirmation token.
 */
internal suspend fun createQrSession(
    http: ProviderHttpClient,
    loginMethod: QQMusicLoginMethod = QQMusicLoginMethod.Qq,
    nowMillis: Long = currentTimeMillis(),
): QQMusicQrSession {
    val data = requestSessionData(
        http = http,
        payload = qrCreatePayload(),
        operation = "QQ Music scan-login session creation",
    )
    val sessionId = data.stringOrNull("qrcodeID")
        ?: data.stringOrNull("qrcodeId")
        ?: throw providerContractException(
            QQMusicProviderDefinition.ID,
            "QQ Music CreateQRCode response is missing qrcodeID",
        )
    val lifetimeSeconds = data.qrNumber("expiresIn")?.takeIf { it > 0L }
        ?: QQMusicAuthorization.DEFAULT_QR_SESSION_SECONDS
    val confirmToken = requestSessionData(
        http = http,
        payload = qrStatusPayload(sessionId),
        operation = "QQ Music scan-login status",
    ).stringOrNull("token")
        ?: throw providerContractException(
            QQMusicProviderDefinition.ID,
            "QQ Music GetQRCodeStatus response is missing the confirmation token",
        )
    return QQMusicQrSession(
        sessionId = sessionId,
        authorizeUrl = qrOAuthUrl(loginMethod, sessionId, confirmToken),
        expiresAtMillis = nowMillis + lifetimeSeconds * 1_000L,
        confirmToken = confirmToken,
    )
}

/**
 * Polls a scan-login session and maps it onto [QQMusicQrStatus].
 *
 * Verified against the live service in the pre-confirmation phase: the poll answers `req.code = 0`
 * with a 72 character `token` and `info.scanUser`, but `info.status` is the string `"scaned"` and
 * `scanUser.userID` is `0` - i.e. those fields alone mean "recognized, not confirmed yet", not
 * "approved". The confirmed state was therefore modelled on QQ Music's own page logic:
 *
 * - `info.status` (case-insensitive) in [QQMusicAuthorization.QR_CONFIRMED_STATUSES] means approved;
 * - otherwise `scanUser.userID > 0` / a non-blank `scanUser.musicId` means [QQMusicQrStatus.Scanned].
 *
 * The confirmed literals are *inferred* (no live sample was captured) and deliberately kept in one
 * extensible set. Session-expiry business codes surface as [QQMusicQrStatus.Expired] and every other
 * refusal as [QQMusicQrStatus.Failed], so a temporary upstream problem never loses the flow.
 */
internal suspend fun qrSessionStatus(
    http: ProviderHttpClient,
    sessionId: String,
): QQMusicQrStatus {
    val qrCodeId = sessionId.trim()
    if (qrCodeId.isEmpty()) return QQMusicQrStatus.Expired
    val data = try {
        requestSessionData(
            http = http,
            payload = qrStatusPayload(qrCodeId),
            operation = "QQ Music scan-login status",
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        if (failure.isQrSessionExpired()) return QQMusicQrStatus.Expired
        return QQMusicQrStatus.Failed(failure.qrFailureMessage())
    }
    val info = data.obj("info")
    val scanUser = info?.obj("scanUser") ?: data.obj("scanUser")
    val status = info?.string("status").orEmpty()
    val musicId = scanUser?.qrNumber("userID")
    val token = data.stringOrNull("token")
    val loginType = scanUser?.int("loginType")
    val confirmed = status.trim().lowercase() in QQMusicAuthorization.QR_CONFIRMED_STATUSES
    val recognized = (musicId ?: 0L) > 0L || scanUser?.stringOrNull("musicId") != null
    return when {
        confirmed -> {
            val id = musicId?.takeIf { it > 0L }
                ?: scanUser?.qrNumber("musicId")
            if (id == null) {
                QQMusicQrStatus.Failed("QQ Music scan-login confirmation carried no account id")
            } else {
                QQMusicQrStatus.Approved(musicId = id, token = token, loginType = loginType)
            }
        }

        recognized -> QQMusicQrStatus.Scanned
        else -> QQMusicQrStatus.Pending
    }
}

/**
 * Exchanges an approved [QQMusicQrStatus.Approved] for a renewable session.
 *
 * The response uses the *same* credential schema as the authorization-code exchange, so it goes
 * through [sessionFromPayload] - one mapping, one set of renewable-session semantics. The login
 * method is taken from `scanUser.loginType` (1 = QQ, 2 = WeChat) and, when the login server omits
 * it, from the minted key's prefix (`W_X…` is WeChat's), so the later renewal picks the right
 * request shape.
 *
 * @throws ProviderOperationException with [ProviderFailureKind.LoginExpired] when the session is not
 *   confirmed yet - the service answers `req.code = 104400` for exactly that.
 */
internal suspend fun loginWithQrSession(
    http: ProviderHttpClient,
    sessionId: String,
    musicId: Long,
    token: String,
    loginType: Int? = null,
    nowMillis: Long = currentTimeMillis(),
): QQMusicAuthorizationResult {
    val qrCodeId = sessionId.trim()
    if (qrCodeId.isEmpty()) {
        throw providerContractException(
            QQMusicProviderDefinition.ID,
            "QQ Music scan-login session id is empty",
        )
    }
    val data = requestSessionData(
        http = http,
        payload = qrLoginPayload(musicId, qrCodeId, token),
        operation = "QQ Music scan-login exchange",
    )
    val authKey = data.stringOrNull("musickey").orEmpty()
    return QQMusicAuthorizationResult(
        session = sessionFromPayload(
            data = data,
            nowMillis = nowMillis,
            previous = null,
            loginMethod = qrLoginMethod(loginType, authKey),
        ),
    )
}

/**
 * The OAuth callback URL `redirect.html` receives: the session it must confirm.
 *
 * Keys are exactly what the page's own serializer emits, including the **camel-case** `qrcodeID`
 * (this page, unlike `authorize.html`, reads the camel-case spelling). `appName` is sent empty
 * because the page tolerates it and this client has no app name to contribute.
 */
internal fun qrRedirectUrl(sessionId: String, token: String): String =
    QQMusicAuthorization.QR_REDIRECT_PAGE +
        "?qrcodeID=" +
        encodeUrlComponent(sessionId) +
        "&tmeAppID=" +
        encodeUrlComponent(QQMusicAuthorization.TME_APP_ID) +
        "&appName=" +
        "&token=" +
        encodeUrlComponent(token)

/**
 * Builds the OAuth authorize URL the user opens to approve [sessionId].
 *
 * Both ecosystems end on [qrRedirectUrl]; only the provider endpoint, client id and scope differ:
 *
 * - QQ: `graph.qq.com/oauth2.0/authorize` with y.qq.com's own `client_id` and `display=mobile`.
 *   Verified live: HTTP 200, lands on `ptlogin2`, keeps `redirect_uri` intact.
 * - WeChat: `open.weixin.qq.com/connect/oauth2/authorize` with `scope=snsapi_userinfo`. WeChat
 *   renders only inside its in-app browser and otherwise shows "请在微信客户端打开链接", which is
 *   the intended guidance for a user who picked WeChat in a normal mobile browser.
 *
 * The trailing `#wechat_redirect` fragment is part of the WeChat protocol and must stay.
 */
internal fun qrOAuthUrl(
    loginMethod: QQMusicLoginMethod,
    sessionId: String,
    token: String,
): String {
    val redirectUrl = encodeUrlComponent(qrRedirectUrl(sessionId, token))
    return when (loginMethod) {
        QQMusicLoginMethod.Qq -> buildString {
            append(QQMusicAuthorization.QQ_OAUTH_AUTHORIZE_URL)
            append("?response_type=code")
            append("&client_id=")
            append(QQMusicAuthorization.QQ_OAUTH_CLIENT_ID)
            append("&redirect_uri=")
            append(redirectUrl)
            append("&state=state")
            append("&display=mobile")
            append("&scope=")
            append(encodeUrlComponent(QQMusicAuthorization.QQ_OAUTH_SCOPE))
        }

        QQMusicLoginMethod.Wechat -> buildString {
            append(QQMusicAuthorization.WECHAT_OAUTH_AUTHORIZE_URL)
            append("?appid=")
            append(QQMusicAuthorization.WECHAT_APP_ID)
            append("&redirect_uri=")
            append(redirectUrl)
            append("&response_type=code")
            append("&scope=")
            append(QQMusicAuthorization.WECHAT_OAUTH_SCOPE)
            append("&state=state")
            append("#wechat_redirect")
        }
    }
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

/**
 * `comm` block for the scan-login calls.
 *
 * These calls impersonate QQ Music's Android client: the web identity (`ct = 24, cv = 0`) is
 * rejected by the login server, and the scan methods read the client identity from *both* the
 * `comm` block and their own `param`, so both carry [QQMusicAuthorization.QR_CLIENT_CT] /
 * [QQMusicAuthorization.QR_CLIENT_CV].
 */
private fun qrCommObject(): JsonObject = JsonObject(
    mapOf(
        "ct" to JsonPrimitive(QQMusicAuthorization.QR_CLIENT_CT),
        "cv" to JsonPrimitive(QQMusicAuthorization.QR_CLIENT_CV),
    ),
)

/** The client identity a scan-login `param` repeats: `tmeAppID`, `ct`, `cv`. */
private fun qrParamIdentity(): Map<String, JsonPrimitive> = mapOf(
    "tmeAppID" to JsonPrimitive(QQMusicAuthorization.TME_APP_ID),
    "ct" to JsonPrimitive(QQMusicAuthorization.QR_CLIENT_CT),
    "cv" to JsonPrimitive(QQMusicAuthorization.QR_CLIENT_CV),
)

/** `music.login.LoginServer/CreateQRCode`: verified live. */
private fun qrCreatePayload(): String = encodePayload(
    mapOf(
        "comm" to qrCommObject(),
        "req" to loginRequest(
            module = QQMusicAuthorization.QR_MODULE,
            method = QQMusicAuthorization.QR_CREATE_METHOD,
            param = qrParamIdentity(),
        ),
    ),
)

/** `music.login.LoginServer/GetQRCodeStatus`: verified live. */
private fun qrStatusPayload(qrCodeId: String): String = encodePayload(
    mapOf(
        "comm" to qrCommObject(),
        "req" to loginRequest(
            module = QQMusicAuthorization.QR_MODULE,
            method = QQMusicAuthorization.QR_STATUS_METHOD,
            param = qrParamIdentity() + mapOf("qrCodeID" to JsonPrimitive(qrCodeId)),
        ),
    ),
)

/**
 * `music.login.LoginServer/Login` for a confirmed scan session: verified parameter schema.
 *
 * This is the QR flavour of `Login` - it takes `musicid` + `qrCodeID` + `token` instead of an OAuth
 * code, and answers with the same credential fields as the authorization-code exchange.
 */
private fun qrLoginPayload(musicId: Long, qrCodeId: String, token: String): String = encodePayload(
    mapOf(
        "comm" to qrCommObject(),
        "req" to loginRequest(
            module = QQMusicAuthorization.QR_MODULE,
            method = QQMusicAuthorization.QR_LOGIN_METHOD,
            param = mapOf(
                "musicid" to JsonPrimitive(musicId),
                "qrCodeID" to JsonPrimitive(qrCodeId),
                "token" to JsonPrimitive(token),
            ),
        ),
    ),
)

/**
 * Login method for a scan session.
 *
 * `scanUser.loginType` wins (1 = QQ, 2 = WeChat). When the login server omits it the minted key's
 * prefix decides: this module already treats `W_X…` as WeChat and `Q_H_L…` as QQ (see
 * [ProviderSessionCredential.authKey]), which matches what QQ Music issues for the two ecosystems.
 */
private fun qrLoginMethod(loginType: Int?, authKey: String): QQMusicLoginMethod {
    if (loginType != null) {
        QQMusicLoginMethod.entries.firstOrNull { it.redirectLoginType == loginType }?.let { return it }
    }
    return if (authKey.startsWith("W_X")) QQMusicLoginMethod.Wechat else QQMusicLoginMethod.Qq
}

/** A number that may arrive as a JSON number *or* a numeric string (`userID`/`loginType` do both). */
private fun JsonObject.qrNumber(key: String): Long? =
    long(key) ?: stringOrNull(key)?.trim()?.toLongOrNull()

/**
 * True when a refused poll means "this QR session is gone" rather than a transient upstream problem.
 *
 * The login server reports it as a business code; QQ Music's session-expiry codes are already mapped
 * to [ProviderFailureKind.LoginExpired] by [qqBusinessException], which the scan payloads reuse.
 */
private fun Throwable.isQrSessionExpired(): Boolean =
    providerFailureOrNull(QQMusicProviderDefinition.ID)?.kind == ProviderFailureKind.LoginExpired

/** User-presentable text for a refused poll; the provider never logs the session token. */
private fun Throwable.qrFailureMessage(): String =
    message?.takeIf(String::isNotBlank)?.let { "QQ Music scan-login failed: $it" }
        ?: "QQ Music scan-login failed"

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
