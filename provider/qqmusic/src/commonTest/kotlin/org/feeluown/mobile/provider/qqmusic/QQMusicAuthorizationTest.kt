package org.feeluown.mobile.provider.qqmusic

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.feeluown.mobile.ProviderFailureKind
import org.feeluown.mobile.ProviderLoginMode
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentials
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies
import org.feeluown.mobile.provider.core.ProviderSessionCredential
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.string

/**
 * Covers the verified QQ Music authorization-code protocol:
 * exchange, renewable-session persistence, automatic renewal and the local expiry verdict.
 */
class QQMusicAuthorizationTest {
    private val accountId = "1152921505375946065"

    /** Fixed clock used by every session decision in these tests. */
    private val now = 1_780_000_000_000L

    private data class RecordedRequest(
        val method: HttpMethod,
        val host: String,
        val path: String,
        val contentType: String?,
        val body: String,
    )

    private fun providerHttp(
        requests: MutableList<RecordedRequest> = mutableListOf(),
        onPost: (String) -> String = { EXCHANGE_RESPONSE },
        onGet: (String) -> String = { PROFILE_RESPONSE },
    ): ProviderHttpClient = ProviderHttpClient(
        HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val body = (request.body as? TextContent)?.text.orEmpty()
                    requests += RecordedRequest(
                        method = request.method,
                        host = request.url.host,
                        path = request.url.encodedPath,
                        contentType = (request.body as? TextContent)?.contentType?.toString(),
                        body = body,
                    )
                    val response = if (request.method == HttpMethod.Post) onPost(body) else onGet(request.url.toString())
                    respond(response)
                }
            }
        },
    )

    private fun storedSession(
        authKey: String = "Q_H_L_OLD",
        refreshToken: String? = "REFRESH_OLD",
        createdAt: Long = now,
        lifetimeMillis: Long = THREE_DAYS_MILLIS,
        grantExpiresAt: Long = now + 60 * DAY_MILLIS,
        loginMethod: String? = null,
    ) = ProviderSessionCredential(
        authKey = authKey,
        refreshToken = refreshToken,
        accessToken = "ACCESS_OLD",
        openId = "OPENID",
        unionId = "UNIONID",
        accountId = accountId,
        encryptedAccountId = "ENC_OLD",
        loginMethod = loginMethod,
        authKeyCreatedAtMillis = createdAt,
        authKeyExpiresInMillis = lifetimeMillis,
        grantExpiresAtMillis = grantExpiresAt,
    )

    private fun payload(requests: List<RecordedRequest>, module: String): JsonObject {
        val request = requests.firstOrNull { it.method == HttpMethod.Post && it.body.contains(module) }
        assertNotNull(request, "no $module request was sent: ${requests.map { it.body }}")
        return providerJson.parseToJsonElement(request.body).jsonObject
    }

    @Test
    fun authorizationCodeLoginPersistsRenewableSessionAndSynthesizesCookies() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val http = providerHttp(requests)
        val credentials = InMemoryProviderCredentialStore()
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val auth = provider.loginWithAuthorizationCode("  CODE123  ", loginType = 1)

        assertTrue(auth.isLoggedIn, auth.failure?.technicalMessage)
        assertEquals("测试用户", auth.userName)
        val stored = assertNotNull(credentials.read("qqmusic"))
        val session = assertNotNull(stored.session)
        assertEquals("Q_H_L_NEW", session.authKey)
        assertEquals("REFRESH_NEW", session.refreshToken)
        assertEquals("ACCESS_NEW", session.accessToken)
        assertEquals("OPENID", session.openId)
        assertEquals(accountId, session.accountId)
        assertEquals("ENC_NEW", session.encryptedAccountId)
        assertEquals(now, session.authKeyCreatedAtMillis)
        assertEquals(THREE_DAYS_MILLIS, session.authKeyExpiresInMillis)
        assertEquals(GRANT_EXPIRES_AT_MILLIS, session.grantExpiresAtMillis)
        // Cookies every existing QQ Music request path depends on.
        assertEquals(accountId, stored.cookies["uin"])
        assertEquals("Q_H_L_NEW", stored.cookies["qm_keyst"])
        assertEquals("Q_H_L_NEW", stored.cookies["qqmusic_key"])

        val exchange = payload(requests, QQMusicAuthorization.EXCHANGE_MODULE)
        assertEquals("u.y.qq.com", requests.first { it.method == HttpMethod.Post }.host)
        assertEquals("/cgi-bin/musicu.fcg", requests.first { it.method == HttpMethod.Post }.path)
        // Verified transport quirk: the body is a raw JSON string (the gateway ignores the content
        // type: the live endpoint answers identically for form-urlencoded and application/json).
        assertEquals("application/json", requests.first { it.method == HttpMethod.Post }.contentType)
        assertEquals("QQLogin", exchange.obj("req")?.string("method"))
        assertEquals("CODE123", exchange.obj("req")?.obj("param")?.string("code"))
        assertEquals(5381L, exchange.obj("comm")?.long("g_tk"))
        assertEquals("yqq", exchange.obj("comm")?.string("platform"))
        assertEquals(24, exchange.obj("comm")?.int("ct"))
        assertEquals(0, exchange.obj("comm")?.int("cv"))
        http.close()
    }

    @Test
    fun authorizationCodeFailureIsReportedAsLoginExpiredAndPersistsNothing() = runTest {
        val http = providerHttp(onPost = { """{"code":0,"req":{"code":1000,"msg":"authorization code expired"}}""" })
        val credentials = InMemoryProviderCredentialStore()
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val auth = provider.loginWithAuthorizationCode("REPLAYED_CODE", loginType = 1)

        assertFalse(auth.isLoggedIn)
        assertEquals(ProviderFailureKind.LoginExpired, auth.failure?.kind)
        assertTrue(auth.failure?.technicalMessage.orEmpty().contains("code=1000"))
        assertNull(credentials.read("qqmusic"))
        http.close()
    }

    @Test
    fun renewSessionReturnsNewAuthKeyAndKeepsRefreshTokenWhenResponseOmitsIt() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val http = providerHttp(
            requests,
            onPost = {
                """{"req":{"code":0,"data":{"musickey":"Q_H_L_ROTATED","access_token":"ACCESS_ROTATED"}}}"""
            },
        )
        val previous = storedSession(
            authKey = "Q_H_L_OLD",
            refreshToken = "REFRESH_OLD",
            createdAt = now - 2 * DAY_MILLIS,
        )

        val renewed = renewSession(http, previous, nowMillis = now)

        assertEquals("Q_H_L_ROTATED", renewed.authKey)
        // Rotation is unverified: an absent refresh_token must keep the previous one.
        assertEquals("REFRESH_OLD", renewed.refreshToken)
        assertEquals("ACCESS_ROTATED", renewed.accessToken)
        assertEquals(now, renewed.authKeyCreatedAtMillis)
        assertTrue(renewed.authKeyCreatedAtMillis > previous.authKeyCreatedAtMillis)
        assertEquals(previous.authKeyExpiresInMillis, renewed.authKeyExpiresInMillis)
        assertEquals(previous.grantExpiresAtMillis, renewed.grantExpiresAtMillis)
        assertEquals(accountId, renewed.accountId)
        assertEquals("OPENID", renewed.openId)
        assertEquals("ENC_OLD", renewed.encryptedAccountId)

        val request = payload(requests, QQMusicAuthorization.RENEW_MODULE)
        assertEquals("Login", request.obj("req")?.string("method"))
        val param = assertNotNull(request.obj("req")?.obj("param"))
        assertEquals("OPENID", param.string("openid"))
        assertEquals("ACCESS_OLD", param.string("access_token"))
        assertEquals("REFRESH_OLD", param.string("refresh_token"))
        assertEquals("Q_H_L_OLD", param.string("musickey"))
        assertEquals(previous.grantExpiresAtMillis / 1_000L, param.long("expired_in"))
        assertEquals(accountId.toLong(), param.long("musicid"))
        // refresh_key is empty for this login type and not required by the server.
        assertEquals("", param.string("refresh_key"))
        assertEquals(2, param.int("loginMode"))
        // QQ Music's reference client sends the credential login type as an int here.
        val comm = assertNotNull(request.obj("comm"))
        assertEquals(2, comm.int("tmeLoginType"))
        assertFalse((comm["tmeLoginType"] as JsonPrimitive).isString)
        http.close()
    }

    @Test
    fun verifySessionRenewsSessionPastHalfLifeAndPersistsNewCookies() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val http = providerHttp(
            requests,
            onPost = {
                """{"req":{"code":0,"data":{"musickey":"Q_H_L_ROTATED","refresh_token":"REFRESH_ROTATED","access_token":"ACCESS_ROTATED","expired_at":1800000000,"keyExpiresIn":259200}}}"""
            },
        )
        val credentials = InMemoryProviderCredentialStore()
        val stale = storedSession(createdAt = now - 2 * DAY_MILLIS)
        credentials.write(ID, ProviderCredentials(cookies = qqSessionCookies(stale), session = stale))
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val auth = provider.verifySession()

        assertTrue(auth.isLoggedIn, auth.failure?.technicalMessage)
        val stored = assertNotNull(credentials.read(ID))
        val session = assertNotNull(stored.session)
        assertEquals("Q_H_L_ROTATED", session.authKey)
        assertEquals("REFRESH_ROTATED", session.refreshToken)
        assertEquals(now, session.authKeyCreatedAtMillis)
        assertEquals(accountId, stored.cookies["uin"])
        assertEquals("Q_H_L_ROTATED", stored.cookies["qm_keyst"])
        assertEquals("Q_H_L_ROTATED", stored.cookies["qqmusic_key"])
        assertTrue(requests.any { it.body.contains(QQMusicAuthorization.RENEW_MODULE) })
        http.close()
    }

    @Test
    fun verifySessionReportsLoggedOutWhenRenewalFailsWithoutLosingCredentials() = runTest {
        val http = providerHttp(onPost = { """{"req":{"code":1000}}""" })
        val credentials = InMemoryProviderCredentialStore()
        val session = storedSession(createdAt = now - 2 * DAY_MILLIS)
        credentials.write(ID, ProviderCredentials(cookies = qqSessionCookies(session), session = session))
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val auth = provider.verifySession()

        assertFalse(auth.isLoggedIn)
        assertEquals(ProviderFailureKind.LoginExpired, auth.failure?.kind)
        // The old session stays on disk so a later network hiccup can still renew it.
        assertEquals("Q_H_L_OLD", credentials.read(ID)?.session?.authKey)
        http.close()
    }

    @Test
    fun verifySessionReportsLoggedOutWhenGrantExpiredWithoutNetworkCall() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val http = providerHttp(requests)
        val credentials = InMemoryProviderCredentialStore()
        val session = storedSession(grantExpiresAt = now - 1_000L)
        credentials.write(ID, ProviderCredentials(cookies = qqSessionCookies(session), session = session))
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val auth = provider.verifySession()

        assertFalse(auth.isLoggedIn)
        assertEquals(ProviderFailureKind.LoginExpired, auth.failure?.kind)
        assertTrue(requests.isEmpty())
        http.close()
    }

    @Test
    fun verifySessionKeepsFreshSessionWithoutRenewing() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val http = providerHttp(requests)
        val credentials = InMemoryProviderCredentialStore()
        val session = storedSession(createdAt = now - DAY_MILLIS / 2)
        credentials.write(ID, ProviderCredentials(cookies = qqSessionCookies(session), session = session))
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val auth = provider.verifySession()

        assertTrue(auth.isLoggedIn)
        assertTrue(requests.none { it.method == HttpMethod.Post })
        assertEquals("Q_H_L_OLD", credentials.read(ID)?.session?.authKey)
        http.close()
    }

    @Test
    fun authStateReportsLoggedOutForUnrenewableExpiredKey() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val http = providerHttp(requests)
        val credentials = InMemoryProviderCredentialStore()
        val session = storedSession(
            refreshToken = null,
            createdAt = now - 4 * DAY_MILLIS,
            grantExpiresAt = now + 30 * DAY_MILLIS,
        )
        credentials.write(ID, ProviderCredentials(cookies = qqSessionCookies(session), session = session))
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val auth = provider.authState()

        assertFalse(auth.isLoggedIn)
        assertEquals(ProviderFailureKind.LoginExpired, auth.failure?.kind)
        // authState stays a purely local question.
        assertTrue(requests.isEmpty())
        http.close()
    }

    @Test
    fun authStateStaysLoggedInWhileRenewalIsStillPossible() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val http = providerHttp(requests)
        val credentials = InMemoryProviderCredentialStore()
        val session = storedSession(
            refreshToken = "REFRESH_OLD",
            createdAt = now - 4 * DAY_MILLIS,
            grantExpiresAt = now + 30 * DAY_MILLIS,
        )
        credentials.write(ID, ProviderCredentials(cookies = qqSessionCookies(session), session = session))
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val auth = provider.authState()

        assertTrue(auth.isLoggedIn, auth.failure?.technicalMessage)
        assertEquals("测试用户", auth.userName)
        // Still local: renewal only happens in verifySession.
        assertTrue(requests.none { it.method == HttpMethod.Post })
        http.close()
    }

    @Test
    fun loginConfigUsesWebViewFriendlyEntryWithInterceptableRedirect() {
        val config = assertNotNull(QQMusicProviderDefinition.info.loginConfig)

        // QQ's standalone authorize page is blank in Android WebView, so the QQ-account entry is the
        // site itself; the interception of the shared redirect is what yields renewable credentials.
        assertEquals("https://y.qq.com/", config.loginUrl)
        // Cookie key groups stay as the manual-cookie fallback.
        assertTrue(config.cookieKeyGroups.isNotEmpty())
        assertEquals(setOf(ProviderLoginMode.WebView), QQMusicProviderDefinition.info.supportedLoginModes)
    }

    @Test
    fun synthesizedCookiesNormalizeLegacyOUinPrefix() {
        val cookies = qqSessionCookies(
            ProviderSessionCredential(authKey = "Q_H_L_KEY", accountId = "o1152921505375946065"),
        )

        assertEquals("1152921505375946065", cookies["uin"])
        assertEquals("Q_H_L_KEY", cookies["qm_keyst"])
        assertEquals("Q_H_L_KEY", cookies["qqmusic_key"])
        // A legacy (qq) session is a QQ session: login_type 1 and no WeChat identity cookies.
        assertEquals("1", cookies["login_type"])
        assertNull(cookies["wxuin"])
        assertNull(cookies["wxopenid"])
        assertNull(cookies["wxunionid"])
    }

    @Test
    fun wechatAuthorizationCodeExchangeSendsWechatPayloadAndPersistsWechatSession() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val http = providerHttp(requests)
        val credentials = InMemoryProviderCredentialStore()
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val auth = provider.loginWithAuthorizationCode("WXCODE", loginType = 2)

        assertTrue(auth.isLoggedIn, auth.failure?.technicalMessage)
        val stored = assertNotNull(credentials.read(ID))
        val session = assertNotNull(stored.session)
        // The method is persisted so the renewal later picks the WeChat request shape.
        assertEquals("wechat", session.loginMethod)
        assertEquals("Q_H_L_NEW", session.authKey)
        assertEquals("REFRESH_NEW", session.refreshToken)
        assertEquals(accountId, session.accountId)
        // Cookies QQ Music's account-scoped request paths need for a WeChat session.
        assertEquals(accountId, stored.cookies["uin"])
        assertEquals(accountId, stored.cookies["wxuin"])
        assertEquals(accountId, stored.cookies["str_musicid"])
        assertEquals(accountId, stored.cookies["musicid"])
        assertEquals("OPENID", stored.cookies["wxopenid"])
        assertEquals("UNIONID", stored.cookies["wxunionid"])
        assertEquals("2", stored.cookies["login_type"])
        assertEquals("Q_H_L_NEW", stored.cookies["qm_keyst"])
        assertEquals("Q_H_L_NEW", stored.cookies["qqmusic_key"])
        // The provider's own account-id resolution prefers wxuin; it must agree.
        assertEquals(accountId, qqAccountId(stored.cookies))

        val exchange = payload(requests, QQMusicAuthorization.WECHAT_EXCHANGE_MODULE)
        val comm = assertNotNull(exchange.obj("comm"))
        assertEquals("qqmusic", comm.string("tmeAppID"))
        assertEquals("1", comm.string("tmeLoginType"))
        assertTrue((comm["tmeLoginType"] as JsonPrimitive).isString)
        assertEquals(5381L, comm.long("g_tk"))
        assertEquals("yqq", comm.string("platform"))
        val req = assertNotNull(exchange.obj("req"))
        assertEquals("music.login.LoginServer", req.string("module"))
        assertEquals("Login", req.string("method"))
        val param = assertNotNull(req.obj("param"))
        assertEquals("wx48db31d50e334801", param.string("strAppid"))
        assertEquals("WXCODE", param.string("code"))
        http.close()
    }

    @Test
    fun wechatRenewalSendsStrMusicIdAndUnionIdWithoutAccessToken() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val http = providerHttp(
            requests,
            onPost = { """{"req":{"code":0,"data":{"musickey":"W_X_ROTATED"}}}""" },
        )
        val previous = storedSession(
            authKey = "W_X_OLD",
            createdAt = now - 2 * DAY_MILLIS,
            loginMethod = "wechat",
        )

        val renewed = renewSession(http, previous, nowMillis = now)

        assertEquals("wechat", renewed.loginMethod)
        assertEquals("W_X_ROTATED", renewed.authKey)
        assertEquals("REFRESH_OLD", renewed.refreshToken)
        val request = payload(requests, QQMusicAuthorization.RENEW_MODULE)
        val param = assertNotNull(request.obj("req")?.obj("param"))
        // WeChat sends the account id as a string and identifies the account by openid/unionid.
        assertEquals(accountId, param.string("str_musicid"))
        assertEquals("UNIONID", param.string("unionid"))
        assertEquals("OPENID", param.string("openid"))
        assertEquals("REFRESH_OLD", param.string("refresh_token"))
        assertEquals("W_X_OLD", param.string("musickey"))
        assertEquals("", param.string("refresh_key"))
        assertEquals(2, param.int("loginMode"))
        // The QQ-only fields are absent from the WeChat renewal, notably access_token/expired_in.
        assertNull(param["access_token"])
        assertNull(param["expired_in"])
        assertNull(param["musicid"])
        val comm = assertNotNull(request.obj("comm"))
        assertEquals("1", comm.string("tmeLoginType"))
        assertTrue((comm["tmeLoginType"] as JsonPrimitive).isString)
        // Cookies synthesized after a WeChat renewal stay WeChat.
        val cookies = qqSessionCookies(renewed)
        assertEquals("2", cookies["login_type"])
        assertEquals(accountId, cookies["wxuin"])
        assertEquals("OPENID", cookies["wxopenid"])
        assertEquals("UNIONID", cookies["wxunionid"])
        http.close()
    }

    @Test
    fun legacySessionWithoutLoginMethodRenewsWithTheQqShape() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val http = providerHttp(
            requests,
            onPost = { """{"req":{"code":0,"data":{"musickey":"Q_H_L_ROTATED"}}}""" },
        )
        // Sessions stored before WeChat support existed carry no login method at all.
        val legacy = storedSession(loginMethod = null, createdAt = now - 2 * DAY_MILLIS)

        val renewed = renewSession(http, legacy, nowMillis = now)

        // The tag is filled in so every later renewal keeps using the QQ shape.
        assertEquals("qq", renewed.loginMethod)
        assertEquals("Q_H_L_ROTATED", renewed.authKey)
        val request = payload(requests, QQMusicAuthorization.RENEW_MODULE)
        val param = assertNotNull(request.obj("req")?.obj("param"))
        assertEquals("ACCESS_OLD", param.string("access_token"))
        assertEquals(accountId.toLong(), param.long("musicid"))
        assertEquals((now + 60 * DAY_MILLIS) / 1_000L, param.long("expired_in"))
        assertNull(param["str_musicid"])
        assertEquals(2, request.obj("comm")?.int("tmeLoginType"))
        http.close()
    }

    @Test
    fun verifySessionRenewsAWechatSessionWithTheWechatShapeAndKeepsWechatCookies() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val http = providerHttp(
            requests,
            onPost = {
                """{"req":{"code":0,"data":{"musickey":"W_X_ROTATED","refresh_token":"REFRESH_ROTATED","keyExpiresIn":259200}}}"""
            },
        )
        val credentials = InMemoryProviderCredentialStore()
        val stale = storedSession(
            authKey = "W_X_OLD",
            createdAt = now - 2 * DAY_MILLIS,
            loginMethod = "wechat",
        )
        credentials.write(ID, ProviderCredentials(cookies = qqSessionCookies(stale), session = stale))
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val auth = provider.verifySession()

        assertTrue(auth.isLoggedIn, auth.failure?.technicalMessage)
        val stored = assertNotNull(credentials.read(ID))
        val session = assertNotNull(stored.session)
        assertEquals("wechat", session.loginMethod)
        assertEquals("W_X_ROTATED", session.authKey)
        assertEquals("REFRESH_ROTATED", session.refreshToken)
        assertEquals("2", stored.cookies["login_type"])
        assertEquals(accountId, stored.cookies["wxuin"])
        assertEquals("OPENID", stored.cookies["wxopenid"])
        assertEquals("UNIONID", stored.cookies["wxunionid"])
        assertEquals("W_X_ROTATED", stored.cookies["qm_keyst"])
        val renewal = payload(requests, QQMusicAuthorization.RENEW_MODULE)
        assertEquals(accountId, renewal.obj("req")?.obj("param")?.string("str_musicid"))
        http.close()
    }

    @Test
    fun wechatExchangeFailureIsReportedAsLoginExpiredAndPersistsNothing() = runTest {
        val http = providerHttp(onPost = { """{"code":0,"req":{"code":1000,"msg":"authorization code expired"}}""" })
        val credentials = InMemoryProviderCredentialStore()
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val auth = provider.loginWithAuthorizationCode("REPLAYED_WX_CODE", loginType = 2)

        assertFalse(auth.isLoggedIn)
        assertEquals(ProviderFailureKind.LoginExpired, auth.failure?.kind)
        assertTrue(auth.failure?.technicalMessage.orEmpty().contains("code=1000"))
        assertNull(credentials.read(ID))
        http.close()
    }

    @Test
    fun loginConfigExposesQqAndWechatLoginChoices() {
        val config = assertNotNull(QQMusicProviderDefinition.info.loginConfig)

        assertEquals(2, config.loginChoices.size)
        val choices = config.effectiveLoginChoices
        assertEquals(listOf("qq", "wechat"), choices.map { it.id })
        assertEquals(listOf("QQ 账号", "微信账号"), choices.map { it.label })
        // The QQ-account entry is QQ Music's own site: QQ's standalone authorize page is blank in web views.
        assertEquals(QQMusicProviderDefinition.WEB_LOGIN_URL, choices[0].loginUrl)
        assertEquals("https://y.qq.com/", choices[0].loginUrl)
        assertEquals(
            "https://open.weixin.qq.com/connect/qrconnect?appid=wx48db31d50e334801&scope=snsapi_login" +
                "&redirect_uri=https%3A%2F%2Fy.qq.com%2Fportal%2Fwx_redirect.html" +
                "%3Flogin_type%3D2%26surl%3Dhttps%3A%2F%2Fy.qq.com%2F" +
                "&state=state#wechat_redirect",
            choices[1].loginUrl,
        )
        // The default entry stays QQ so a platform build that only knows loginUrl keeps working.
        assertEquals(choices[0].loginUrl, config.loginUrl)
        // Both ecosystems finish on the same redirect; the platforms intercept exactly this one.
        val redirect = assertNotNull(config.authorizationCodeRedirect)
        assertEquals("y.qq.com", redirect.host)
        assertEquals("/portal/wx_redirect.html", redirect.path)
        assertEquals("code", redirect.codeParameter)
        assertEquals("login_type", redirect.loginTypeParameter)
    }

    private companion object {
        const val ID = "qqmusic"
        const val DAY_MILLIS = 24 * 60 * 60 * 1_000L
        const val THREE_DAYS_MILLIS = 3 * DAY_MILLIS
        const val GRANT_EXPIRES_AT_MILLIS = 1_800_000_000_000L

        const val EXCHANGE_RESPONSE =
            """{"code":0,"req":{"code":0,"data":{"musickey":"Q_H_L_NEW","refresh_token":"REFRESH_NEW","access_token":"ACCESS_NEW","openid":"OPENID","unionid":"UNIONID","musicid":1152921505375946065,"encryptUin":"ENC_NEW","expired_at":1800000000,"musickeyCreateTime":1780000000,"keyExpiresIn":259200,"refresh_key":"","loginType":2}}}"""

        const val PROFILE_RESPONSE =
            """{"code":0,"data":{"creator":{"nick":"测试用户","encrypt_uin":"ENC_NEW"},"mymusic":[],"mydiss":{"list":[]}}}"""
    }
}
