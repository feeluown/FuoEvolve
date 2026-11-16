package org.feeluown.mobile.provider.qqmusic

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.feeluown.mobile.ProviderScanLoginResult
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentials
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies
import org.feeluown.mobile.provider.core.int
import org.feeluown.mobile.provider.core.long
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.obj
import org.feeluown.mobile.provider.core.providerJson
import org.feeluown.mobile.provider.core.string

/**
 * Covers QQ Music's phone-client scan login.
 *
 * The flow: CreateQRCode -> GetQRCodeStatus (its token goes into the OAuth redirect_uri) -> the user
 * approves in a browser via graph.qq.com / open.weixin.qq.com -> the app polls GetQRCodeStatus ->
 * the QR Login exchange mints the same renewable session the authorization-code login persists.
 *
 * `authorize.html` is deliberately not used: its own QQ/WeChat buttons take `client_id`/`appid`
 * from `info.oauth_appid`, which the login server returns empty for these sessions.
 */
class QQMusicScanLoginTest {
    private val accountId = 1152921505375946065L

    /** Fixed clock used by every session decision in these tests. */
    private val now = 1_780_000_000_000L

    private data class RecordedRequest(
        val method: HttpMethod,
        val host: String,
        val path: String,
        val body: String,
    ) {
        /** req.method of the musicu payload, e.g. CreateQRCode. */
        val apiMethod: String
            get() = runCatching {
                providerJson.parseToJsonElement(body).jsonObject.obj("req")?.string("method").orEmpty()
            }.getOrDefault("")
    }

    private fun apiMethodOf(body: String): String =
        providerJson.parseToJsonElement(body).jsonObject.obj("req")?.string("method").orEmpty()

    /**
     * Answers by API method. [statusResponses] are consumed in order, and index 0 is the token fetch
     * `beginScanLogin` performs right after `CreateQRCode`, so every list starts with a
     * token-bearing answer.
     */
    private fun sequenced(
        statusResponses: List<String>,
        createResponse: String = CREATE_RESPONSE,
        loginResponse: String = SCAN_LOGIN_RESPONSE,
    ): (String) -> String {
        var statusIndex = 0
        return { body ->
            when (apiMethodOf(body)) {
                QQMusicAuthorization.QR_CREATE_METHOD -> createResponse
                QQMusicAuthorization.QR_STATUS_METHOD ->
                    statusResponses[minOf(statusIndex++, statusResponses.lastIndex)]
                else -> loginResponse
            }
        }
    }

    private fun providerHttp(
        requests: MutableList<RecordedRequest> = mutableListOf(),
        onGet: (String) -> String = { PROFILE_RESPONSE },
        onPost: (String) -> String,
    ): ProviderHttpClient = ProviderHttpClient(
        HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val body = (request.body as? TextContent)?.text.orEmpty()
                    requests += RecordedRequest(
                        method = request.method,
                        host = request.url.host,
                        path = request.url.encodedPath,
                        body = body,
                    )
                    respond(
                        if (request.method == HttpMethod.Post) onPost(body) else onGet(request.url.toString()),
                    )
                }
            }
        },
    )

    private fun payload(requests: List<RecordedRequest>, apiMethod: String): JsonObject {
        val request = requests.firstOrNull { it.method == HttpMethod.Post && it.apiMethod == apiMethod }
        assertNotNull(request, "no $apiMethod request was sent: ${requests.map { it.apiMethod }}")
        return providerJson.parseToJsonElement(request.body).jsonObject
    }

    /** The decoded `redirect_uri` query value of an authorize URL. */
    private fun redirectUriOf(authorizeUrl: String): String =
        decodeUrlComponent(
            assertNotNull(authorizeUrl.substringAfter("redirect_uri=", "").substringBefore("&").takeIf {
                it.isNotEmpty()
            }, "no redirect_uri in $authorizeUrl"),
        )

    @Test
    fun beginScanLoginCreatesASessionThenReadsItsConfirmationTokenInThatOrder() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val http = providerHttp(requests, onPost = sequenced(listOf(STATUS_PENDING_RESPONSE)))
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, InMemoryProviderCredentialStore()),
            nowMillis = { now },
        )

        provider.beginScanLogin(null)

        // Two requests, in this order: CreateQRCode mints the id, GetQRCodeStatus yields the token
        // redirect.html needs. Both impersonate QQ Music's Android client.
        assertEquals(
            listOf(
                QQMusicAuthorization.QR_CREATE_METHOD,
                QQMusicAuthorization.QR_STATUS_METHOD,
            ),
            requests.map { it.apiMethod },
        )
        assertEquals("u.y.qq.com", requests.first().host)
        assertEquals("/cgi-bin/musicu.fcg", requests.first().path)
        assertEquals(
            """{"comm":{"ct":11,"cv":20090008},"req":{"module":"music.login.LoginServer",""" +
                """"method":"CreateQRCode","param":{"tmeAppID":"qqmusic","ct":11,"cv":20090008}}}""",
            requests[0].body,
        )
        assertEquals(
            """{"comm":{"ct":11,"cv":20090008},"req":{"module":"music.login.LoginServer",""" +
                """"method":"GetQRCodeStatus","param":{"tmeAppID":"qqmusic","ct":11,"cv":20090008,""" +
                """"qrCodeID":"$QR_SESSION_ID"}}}""",
            requests[1].body,
        )
        http.close()
    }

    @Test
    fun beginScanLoginDefaultAndExplicitQqProduceTheQqOAuthUrl() = runTest {
        for (loginType in listOf(null, 1)) {
            val http = providerHttp(
                mutableListOf(),
                onPost = sequenced(listOf(STATUS_PENDING_RESPONSE)),
            )
            val provider = QQMusicCompositeProvider(
                ProviderRuntimeDependencies(http, InMemoryProviderCredentialStore()),
                nowMillis = { now },
            )

            val session = provider.beginScanLogin(loginType)

            assertEquals(QR_SESSION_ID, session.sessionId)
            assertEquals(900_000L, session.expiresInMillis)
            assertEquals(QQMusicAuthorization.QR_POLL_INTERVAL_MILLIS, session.pollIntervalMillis)
            // QQ: y.qq.com's own OAuth client, mobile display, decoded exactly as QQ Music sends it.
            assertEquals(
                "https://graph.qq.com/oauth2.0/authorize?response_type=code&client_id=100497308" +
                    "&redirect_uri=" + encodeUrlComponent(EXPECTED_REDIRECT_URI) +
                    "&state=state&display=mobile&scope=get_user_info%2Cget_app_friends",
                session.authorizeUrl,
            )
            // The redirect_uri carries the session and the token from GetQRCodeStatus.
            assertEquals(EXPECTED_REDIRECT_URI, redirectUriOf(session.authorizeUrl))
            http.close()
        }
    }

    @Test
    fun beginScanLoginForWechatProducesTheWechatOAuthUrl() = runTest {
        val http = providerHttp(
            mutableListOf(),
            onPost = sequenced(listOf(STATUS_PENDING_RESPONSE)),
        )
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, InMemoryProviderCredentialStore()),
            nowMillis = { now },
        )

        val session = provider.beginScanLogin(2)

        assertEquals(
            "https://open.weixin.qq.com/connect/oauth2/authorize?appid=wx48db31d50e334801" +
                "&redirect_uri=" + encodeUrlComponent(EXPECTED_REDIRECT_URI) +
                "&response_type=code&scope=snsapi_userinfo&state=state#wechat_redirect",
            session.authorizeUrl,
        )
        // Same redirect page and the same token as the QQ variant.
        assertEquals(EXPECTED_REDIRECT_URI, redirectUriOf(session.authorizeUrl))
        http.close()
    }

    @Test
    fun beginScanLoginUsesTheTokenReturnedByItsOwnStatusPoll() = runTest {
        val http = providerHttp(
            mutableListOf(),
            onPost = sequenced(listOf(STATUS_PENDING_OTHER_TOKEN_RESPONSE)),
        )
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, InMemoryProviderCredentialStore()),
            nowMillis = { now },
        )

        val session = provider.beginScanLogin(1)

        // The token is not a client-side invention: it is whatever GetQRCodeStatus answered.
        assertTrue(redirectUriOf(session.authorizeUrl).endsWith("&token=$TOKEN_FROM_STATUS"))
        http.close()
    }

    @Test
    fun scanLoginWalksPendingScannedApprovedAndPersistsTheRenewableSession() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val http = providerHttp(
            requests,
            onPost = sequenced(
                listOf(
                    // Consumed by beginScanLogin for the confirmation token.
                    STATUS_PENDING_RESPONSE,
                    STATUS_PENDING_RESPONSE,
                    STATUS_SCANNED_RESPONSE,
                    STATUS_APPROVED_RESPONSE,
                ),
            ),
        )
        val credentials = InMemoryProviderCredentialStore()
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val session = provider.beginScanLogin(null)

        assertEquals(ProviderScanLoginResult.Pending, provider.pollScanLogin(session.sessionId))
        assertEquals(ProviderScanLoginResult.Scanned, provider.pollScanLogin(session.sessionId))

        val approvedResult = assertIs<ProviderScanLoginResult.Approved>(
            provider.pollScanLogin(session.sessionId),
        )
        assertTrue(approvedResult.authState.isLoggedIn, approvedResult.authState.failure?.technicalMessage)
        assertEquals("测试用户", approvedResult.authState.userName)

        // The QR exchange persists exactly what the authorization-code login persists.
        val stored = assertNotNull(credentials.read("qqmusic"))
        val persisted = assertNotNull(stored.session)
        assertEquals("Q_H_L_NEW", persisted.authKey)
        assertEquals("REFRESH_NEW", persisted.refreshToken)
        assertEquals("ACCESS_NEW", persisted.accessToken)
        assertEquals("OPENID", persisted.openId)
        assertEquals("UNIONID", persisted.unionId)
        assertEquals(accountId.toString(), persisted.accountId)
        assertEquals("ENC_NEW", persisted.encryptedAccountId)
        assertEquals(now, persisted.authKeyCreatedAtMillis)
        assertEquals(THREE_DAYS_MILLIS, persisted.authKeyExpiresInMillis)
        assertEquals(GRANT_EXPIRES_AT_MILLIS, persisted.grantExpiresAtMillis)
        assertEquals("qq", persisted.loginMethod)
        // Cookies every existing QQ Music request path depends on.
        assertEquals(accountId.toString(), stored.cookies["uin"])
        assertEquals("Q_H_L_NEW", stored.cookies["qm_keyst"])
        assertEquals("Q_H_L_NEW", stored.cookies["qqmusic_key"])
        assertEquals("1", stored.cookies["login_type"])
        // The stored session survives as a normal renewable login.
        assertTrue(provider.authState().isLoggedIn)
        // The confirmation token sent to Login is the approved poll's, not the creation-time one.
        val exchange = payload(requests, QQMusicAuthorization.QR_LOGIN_METHOD)
        assertEquals(CONFIRM_TOKEN, exchange.obj("req")?.obj("param")?.string("token"))
        http.close()
    }

    @Test
    fun scanLoginExchangeCarriesMusicIdSessionIdAndToken() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val http = providerHttp(
            requests,
            onPost = sequenced(listOf(STATUS_PENDING_RESPONSE, STATUS_APPROVED_RESPONSE)),
        )
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, InMemoryProviderCredentialStore()),
            nowMillis = { now },
        )

        val session = provider.beginScanLogin(1)
        assertIs<ProviderScanLoginResult.Approved>(provider.pollScanLogin(session.sessionId))

        // The QR flavour of Login (not QRCodeLogin) exchanges musicid + qrCodeID + token.
        val exchange = payload(requests, QQMusicAuthorization.QR_LOGIN_METHOD)
        val req = assertNotNull(exchange.obj("req"))
        assertEquals("music.login.LoginServer", req.string("module"))
        val param = assertNotNull(req.obj("param"))
        assertEquals(accountId, param.long("musicid"))
        assertEquals(QR_SESSION_ID, param.string("qrCodeID"))
        assertEquals(CONFIRM_TOKEN, param.string("token"))
        assertEquals(11, exchange.obj("comm")?.int("ct"))
        assertEquals(20_090_008, exchange.obj("comm")?.int("cv"))
        assertEquals(
            """{"comm":{"ct":11,"cv":20090008},"req":{"module":"music.login.LoginServer",""" +
                """"method":"Login","param":{"musicid":$accountId,""" +
                """"qrCodeID":"$QR_SESSION_ID","token":"$CONFIRM_TOKEN"}}}""",
            requests.first { it.apiMethod == QQMusicAuthorization.QR_LOGIN_METHOD }.body,
        )
        assertTrue(requests.none { it.body.contains("QRCodeLogin") })
        http.close()
    }

    @Test
    fun scanLoginFallsBackToTheCreationTokenWhenTheApprovedPollOmitsIt() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val http = providerHttp(
            requests,
            onPost = sequenced(listOf(STATUS_PENDING_RESPONSE, STATUS_APPROVED_NO_TOKEN_RESPONSE)),
        )
        val credentials = InMemoryProviderCredentialStore()
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val session = provider.beginScanLogin(1)
        assertIs<ProviderScanLoginResult.Approved>(provider.pollScanLogin(session.sessionId))

        // The token that opened the OAuth URL is still the one that confirms the session.
        assertEquals(TOKEN_72, payload(requests, QQMusicAuthorization.QR_LOGIN_METHOD).obj("req")
            ?.obj("param")?.string("token"))
        assertNotNull(credentials.read("qqmusic")?.session)
        http.close()
    }

    @Test
    fun scanLoginDerivesTheWechatLoginMethodFromLoginTypeTwo() = runTest {
        val http = providerHttp(
            mutableListOf(),
            onPost = sequenced(
                listOf(STATUS_PENDING_RESPONSE, STATUS_APPROVED_WECHAT_RESPONSE),
                loginResponse = WECHAT_SCAN_LOGIN_RESPONSE,
            ),
        )
        val credentials = InMemoryProviderCredentialStore()
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val session = provider.beginScanLogin(2)
        assertIs<ProviderScanLoginResult.Approved>(provider.pollScanLogin(session.sessionId))

        val stored = assertNotNull(credentials.read("qqmusic"))
        val persisted = assertNotNull(stored.session)
        // The renewal later picks the WeChat request shape from this tag.
        assertEquals("wechat", persisted.loginMethod)
        assertEquals("W_X_NEW", persisted.authKey)
        assertEquals("2", stored.cookies["login_type"])
        assertEquals(accountId.toString(), stored.cookies["wxuin"])
        assertEquals("OPENID", stored.cookies["wxopenid"])
        http.close()
    }

    @Test
    fun scanLoginDerivesTheLoginMethodFromTheMintedKeyWhenLoginTypeIsMissing() = runTest {
        val http = providerHttp(
            mutableListOf(),
            onPost = sequenced(
                listOf(STATUS_PENDING_RESPONSE, STATUS_APPROVED_NO_LOGIN_TYPE_RESPONSE),
                loginResponse = WECHAT_SCAN_LOGIN_RESPONSE,
            ),
        )
        val credentials = InMemoryProviderCredentialStore()
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val session = provider.beginScanLogin(1)
        assertIs<ProviderScanLoginResult.Approved>(provider.pollScanLogin(session.sessionId))

        // scanUser.loginType was absent, so the W_X key prefix decides it is a WeChat session.
        assertEquals("wechat", credentials.read("qqmusic")?.session?.loginMethod)
        http.close()
    }

    @Test
    fun scanLoginMapsLoginServerExpiryCodesToExpired() = runTest {
        val http = providerHttp(
            mutableListOf(),
            onPost = sequenced(
                listOf(STATUS_PENDING_RESPONSE, """{"code":0,"req":{"code":1000,"msg":"qrcode expired"}}"""),
            ),
        )
        val credentials = InMemoryProviderCredentialStore()
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val session = provider.beginScanLogin(null)

        assertEquals(ProviderScanLoginResult.Expired, provider.pollScanLogin(session.sessionId))
        // An expired session must never touch a stored account.
        assertNull(credentials.read("qqmusic"))
        http.close()
    }

    @Test
    fun scanLoginReportsExpiredOnceTheLocalDeadlinePassedWithoutPolling() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val http = providerHttp(
            requests,
            onPost = sequenced(listOf(STATUS_PENDING_RESPONSE)),
        )
        var clock = now
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, InMemoryProviderCredentialStore()),
            nowMillis = { clock },
        )

        val session = provider.beginScanLogin(null)
        clock = now + session.expiresInMillis + 1L
        val before = requests.size

        assertEquals(ProviderScanLoginResult.Expired, provider.pollScanLogin(session.sessionId))
        // The deadline is checked locally, so a dead session costs no request.
        assertEquals(before, requests.size)
        http.close()
    }

    @Test
    fun scanLoginMapsOtherRefusalsToFailedWithTheProviderMessage() = runTest {
        val http = providerHttp(
            mutableListOf(),
            onPost = sequenced(listOf(STATUS_PENDING_RESPONSE, REFUSED_RESPONSE)),
        )
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, InMemoryProviderCredentialStore()),
            nowMillis = { now },
        )

        val session = provider.beginScanLogin(null)
        val failed = assertIs<ProviderScanLoginResult.Failed>(provider.pollScanLogin(session.sessionId))

        assertTrue(failed.message.contains("104610"), failed.message)
        assertTrue(failed.message.contains("not in cts-white-list"), failed.message)
        http.close()
    }

    @Test
    fun scanLoginFailsWhenTheLoginServerDoesNotConfirmTheAccount() = runTest {
        val http = providerHttp(
            mutableListOf(),
            onPost = sequenced(
                listOf(STATUS_PENDING_RESPONSE, STATUS_APPROVED_RESPONSE),
                // Verified live answer for an unconfirmed session.
                loginResponse = """{"code":0,"req":{"code":104400,"msg":"not login"}}""",
            ),
        )
        val credentials = InMemoryProviderCredentialStore()
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val session = provider.beginScanLogin(null)

        assertIs<ProviderScanLoginResult.Failed>(provider.pollScanLogin(session.sessionId))
        assertNull(credentials.read("qqmusic"))
        http.close()
    }

    @Test
    fun scanLoginTreatsThePreConfirmationStateAsPending() = runTest {
        val http = providerHttp(
            mutableListOf(),
            onPost = sequenced(listOf(STATUS_PENDING_RESPONSE, STATUS_PENDING_RESPONSE)),
        )
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, InMemoryProviderCredentialStore()),
            nowMillis = { now },
        )

        val session = provider.beginScanLogin(null)

        // Verified live: info.status is "scaned" and scanUser.userID is 0 before confirmation, so
        // neither field alone may be read as "approved".
        assertEquals(ProviderScanLoginResult.Pending, provider.pollScanLogin(session.sessionId))
        http.close()
    }

    @Test
    fun scanLoginPersistsNothingWhileTheSessionIsUnconfirmed() = runTest {
        val http = providerHttp(
            mutableListOf(),
            onPost = sequenced(listOf(STATUS_PENDING_RESPONSE, STATUS_SCANNED_RESPONSE)),
        )
        val credentials = InMemoryProviderCredentialStore()
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val session = provider.beginScanLogin(null)

        assertEquals(ProviderScanLoginResult.Scanned, provider.pollScanLogin(session.sessionId))
        assertFalse(provider.authState().isLoggedIn)
        assertNull(credentials.read("qqmusic"))
        http.close()
    }

    @Test
    fun scanLoginReplacesACapturedHeaderAndThePreviousAccountsCookies() = runTest {
        val http = providerHttp(
            mutableListOf(),
            onPost = sequenced(listOf(STATUS_PENDING_RESPONSE, STATUS_APPROVED_RESPONSE)),
        )
        val credentials = InMemoryProviderCredentialStore()
        credentials.write(
            "qqmusic",
            ProviderCredentials(
                cookies = mapOf("qqmusic_key" to "STALE_KEY", "uin" to "999", "unrelated" to "keep"),
                cookieHeader = "qqmusic_key=STALE_KEY",
            ),
        )
        val provider = QQMusicCompositeProvider(
            ProviderRuntimeDependencies(http, credentials),
            nowMillis = { now },
        )

        val session = provider.beginScanLogin(null)
        assertIs<ProviderScanLoginResult.Approved>(provider.pollScanLogin(session.sessionId))

        val stored = assertNotNull(credentials.read("qqmusic"))
        assertEquals("Q_H_L_NEW", stored.cookies["qqmusic_key"])
        assertEquals(accountId.toString(), stored.cookies["uin"])
        assertEquals("keep", stored.cookies["unrelated"])
        // Identical to the authorization-code path, which drops a stale captured header.
        assertNull(stored.cookieHeader)
        http.close()
    }

    private companion object {
        const val QR_SESSION_ID =
            "0BC5F8D7A9A0F0F72F3C2A6B4B5C1E9D0A1B2C3D4E5F60718293A4B5C6D7E8F901A2B3C4D5E6F701"

        const val TOKEN_72 =
            "TOKEN0A1B2C3D4E5F60718293A4B5C6D7E8F901A2B3C4D5E6F7018293A4B5C6D7E8F901A2"

        const val TOKEN_FROM_STATUS = "TOKEN_FROM_STATUS_POLL"

        /** Exactly what the OAuth provider must hand back to QQ Music's redirect page. */
        val EXPECTED_REDIRECT_URI =
            "https://y.qq.com/m/client/qr_code_login/redirect.html" +
                "?qrcodeID=" + QR_SESSION_ID +
                "&tmeAppID=qqmusic&appName=&token=" + TOKEN_72

        const val CONFIRM_TOKEN =
            "Q_H_L_A1B2C3D4E5F60718293A4B5C6D7E8F901A2B3C4D5E6F7018293A4B5C6D7E8F901A2B3C4D5E6F"

        const val DAY_MILLIS = 24 * 60 * 60 * 1_000L
        const val THREE_DAYS_MILLIS = 3 * DAY_MILLIS
        const val GRANT_EXPIRES_AT_MILLIS = 1_800_000_000_000L

        const val CREATE_RESPONSE =
            """{"code":0,"req":{"code":0,"data":{"qrcode":"data:image/png;base64,iVBORw0KGgo=","qrcodeID":"0BC5F8D7A9A0F0F72F3C2A6B4B5C1E9D0A1B2C3D4E5F60718293A4B5C6D7E8F901A2B3C4D5E6F701","expiresIn":900,"webConf":{},"queryKey":"qrcode_id"}}}"""

        const val PROFILE_RESPONSE =
            """{"code":0,"data":{"creator":{"nick":"测试用户","encrypt_uin":"ENC_NEW"},"mymusic":[],"mydiss":{"list":[]}}}"""

        const val STATUS_PENDING_RESPONSE =
            """{"code":0,"req":{"code":0,"data":{"errMsg":"","token":"TOKEN0A1B2C3D4E5F60718293A4B5C6D7E8F901A2B3C4D5E6F7018293A4B5C6D7E8F901A2","info":{"status":"scaned","scanUser":{"tmeAppID":"qqmusic","loginType":1,"userID":0,"nick":"","musicId":"","oauth_appid":""}},"downloads":[],"webConf":{},"agreements":[]}}}"""

        const val STATUS_PENDING_OTHER_TOKEN_RESPONSE =
            """{"code":0,"req":{"code":0,"data":{"errMsg":"","token":"TOKEN_FROM_STATUS_POLL","info":{"status":"","scanUser":{"tmeAppID":"qqmusic","loginType":1,"userID":0}},"downloads":[],"webConf":{},"agreements":[]}}}"""

        const val STATUS_SCANNED_RESPONSE =
            """{"code":0,"req":{"code":0,"data":{"errMsg":"","token":"TOKEN0A1B2C3D4E5F60718293A4B5C6D7E8F901A2B3C4D5E6F7018293A4B5C6D7E8F901A2","info":{"status":"scaned","scanUser":{"tmeAppID":"qqmusic","loginType":1,"userID":1152921505375946065,"nick":"测试用户","musicId":"1152921505375946065"}},"downloads":[],"webConf":{},"agreements":[]}}}"""

        const val STATUS_APPROVED_RESPONSE =
            """{"code":0,"req":{"code":0,"data":{"errMsg":"","token":"Q_H_L_A1B2C3D4E5F60718293A4B5C6D7E8F901A2B3C4D5E6F7018293A4B5C6D7E8F901A2B3C4D5E6F","info":{"status":"confirmed","scanUser":{"tmeAppID":"qqmusic","loginType":1,"userID":1152921505375946065,"nick":"测试用户","openid":"OPENID","unionid":"UNIONID","musicId":"1152921505375946065"}},"downloads":[],"webConf":{},"agreements":[]}}}"""

        const val STATUS_APPROVED_WECHAT_RESPONSE =
            """{"code":0,"req":{"code":0,"data":{"errMsg":"","token":"W_X_CONFIRM_TOKEN","info":{"status":"CONFIRMEDLOGIN","scanUser":{"tmeAppID":"qqmusic","loginType":2,"userID":1152921505375946065,"nick":"测试用户","openid":"OPENID","unionid":"UNIONID","musicId":"1152921505375946065"}},"downloads":[],"webConf":{},"agreements":[]}}}"""

        const val STATUS_APPROVED_NO_LOGIN_TYPE_RESPONSE =
            """{"code":0,"req":{"code":0,"data":{"errMsg":"","token":"W_X_CONFIRM_TOKEN","info":{"status":"loginsuccess","scanUser":{"tmeAppID":"qqmusic","userID":1152921505375946065,"musicId":"1152921505375946065"}},"downloads":[],"webConf":{},"agreements":[]}}}"""

        const val STATUS_APPROVED_NO_TOKEN_RESPONSE =
            """{"code":0,"req":{"code":0,"data":{"errMsg":"","info":{"status":"confirmed","scanUser":{"tmeAppID":"qqmusic","loginType":1,"userID":1152921505375946065,"musicId":"1152921505375946065"}},"downloads":[],"webConf":{},"agreements":[]}}}"""

        const val REFUSED_RESPONSE =
            """{"code":0,"req":{"code":104610,"msg":"can NOT scan the qrCode with this APP, not in cts-white-list"}}"""

        const val SCAN_LOGIN_RESPONSE =
            """{"code":0,"req":{"code":0,"data":{"musickey":"Q_H_L_NEW","refresh_token":"REFRESH_NEW","access_token":"ACCESS_NEW","openid":"OPENID","unionid":"UNIONID","musicid":1152921505375946065,"encryptUin":"ENC_NEW","expired_at":1800000000,"musickeyCreateTime":1780000000,"keyExpiresIn":259200,"refresh_key":"","loginType":1}}}"""

        const val WECHAT_SCAN_LOGIN_RESPONSE =
            """{"code":0,"req":{"code":0,"data":{"musickey":"W_X_NEW","refresh_token":"REFRESH_NEW","access_token":"ACCESS_NEW","openid":"OPENID","unionid":"UNIONID","musicid":1152921505375946065,"encryptUin":"ENC_NEW","expired_at":1800000000,"musickeyCreateTime":1780000000,"keyExpiresIn":259200,"loginType":2}}}"""
    }
}

/** Decodes the percent-encoded query values these URLs carry (ASCII only; the tests are ASCII). */
private fun decodeUrlComponent(value: String): String = buildString {
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character == '%' && index + 2 < value.length) {
            val code = value.substring(index + 1, index + 3).toIntOrNull(16)
            if (code != null) {
                append(code.toChar())
                index += 3
                continue
            }
        }
        append(character)
        index++
    }
}
