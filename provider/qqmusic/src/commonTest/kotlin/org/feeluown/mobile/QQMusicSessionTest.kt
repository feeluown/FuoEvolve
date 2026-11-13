package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentials
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.qqmusic.QQMusicCompositeProvider
import org.feeluown.mobile.provider.qqmusic.QQMusicSessionValidator
import org.feeluown.mobile.provider.qqmusic.qqAccountId

class QQMusicSessionTest {
    private fun sessionHttp(responseBody: String): ProviderHttpClient = ProviderHttpClient(
        HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    assertEquals("u.y.qq.com", request.url.host)
                    assertEquals("/cgi-bin/musicu.fcg", request.url.encodedPath)
                    val data = request.url.parameters["data"].orEmpty()
                    assertTrue(data.contains("music.UserInfo.userInfoServer"))
                    assertTrue(data.contains("GetLoginUserInfo"))
                    respond(responseBody)
                }
            }
        },
    )

    private suspend fun loggedInCredentials() = InMemoryProviderCredentialStore().apply {
        write("qqmusic", ProviderCredentials(cookies = mapOf("uin" to "123456", "qqmusic_key" to "test-key")))
    }

    @Test
    fun validatorReportsExpiredSessionForCode1000() = runTest {
        val http = sessionHttp("""{"code":0,"req_0":{"code":1000}}""")
        val validator = QQMusicSessionValidator(http, loggedInCredentials())

        assertFalse(validator.isSessionAlive()!!)
        http.close()
    }

    @Test
    fun validatorReportsLiveSessionForCode0() = runTest {
        val http = sessionHttp("""{"code":0,"req_0":{"code":0,"data":{"uin":123456}}}""")
        val validator = QQMusicSessionValidator(http, loggedInCredentials())

        assertTrue(validator.isSessionAlive()!!)
        http.close()
    }

    @Test
    fun validatorStaysUndecidedForUnknownBusinessCode() = runTest {
        val http = sessionHttp("""{"code":0,"req_0":{"code":500003}}""")
        val validator = QQMusicSessionValidator(http, loggedInCredentials())

        assertNull(validator.isSessionAlive())
        http.close()
    }

    @Test
    fun unknownBusinessCodeIsNotReportedAsUpstreamContractChange() = runTest {
        // Issue #307: an ordinary QQ Music account/session business code must never surface the
        // "音源接口响应已变化，请更新应用或稍后重试" message.
        val failure = runCatching { throw org.feeluown.mobile.providerBusinessException(
            "qqmusic",
            500003,
            "",
            loginExpiredCodes = setOf(1000, 104400, 104401),
        ) }.exceptionOrNull()

        assertEquals(ProviderFailureKind.UpstreamContractChanged, (failure as org.feeluown.mobile.ProviderOperationException).failure.kind)
    }

    @Test
    fun declaredLoginExpiryCodeBecomesLoginExpired() = runTest {
        val failure = runCatching { throw org.feeluown.mobile.providerBusinessException(
            "qqmusic",
            1000,
            "",
            loginExpiredCodes = setOf(1000, 104400, 104401),
        ) }.exceptionOrNull()

        assertEquals(ProviderFailureKind.LoginExpired, (failure as org.feeluown.mobile.ProviderOperationException).failure.kind)
    }

    @Test
    fun verifySessionReportsLoggedOutWhenServerRejectedStoredSession() = runTest {
        val http = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val data = request.url.parameters["data"].orEmpty()
                        if (data.contains("GetLoginUserInfo")) {
                            respond("""{"code":0,"req_0":{"code":1000}}""")
                        } else {
                            error("unexpected QQ Music request: ${request.url}")
                        }
                    }
                }
            },
        )
        val credentials = loggedInCredentials()
        val provider = QQMusicCompositeProvider(ProviderRuntimeDependencies(http, credentials))

        val auth = provider.verifySession()

        assertFalse(auth.isLoggedIn)
        assertEquals(ProviderFailureKind.LoginExpired, auth.failure?.kind)
        http.close()
    }

    @Test
    fun verifySessionKeepsSessionWhenProbeCannotDecide() = runTest {
        val http = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val data = request.url.parameters["data"].orEmpty()
                        when {
                            data.contains("GetLoginUserInfo") -> respond("""{"code":0,"req_0":{"code":500003}}""")
                            else -> error("unexpected QQ Music request: ${request.url}")
                        }
                    }
                }
            },
        )
        val provider = QQMusicCompositeProvider(ProviderRuntimeDependencies(http, loggedInCredentials()))

        val auth = provider.verifySession()

        assertTrue(auth.isLoggedIn)
        http.close()
    }
    @Test
    fun accountIdNormalizationStripsLegacyOPrefix() {
        assertEquals("123456", qqAccountId(mapOf("uin" to "o123456")))
        assertEquals("123456", qqAccountId(mapOf("uin" to "123456")))
        assertEquals("1152921505375946065", qqAccountId(mapOf("wxuin" to "o1152921505375946065")))
        assertNull(qqAccountId(emptyMap()))
    }

    @Test
    fun nonJsonResponseIsTransportFailureNotContractChange() {
        val failure = runCatching {
            org.feeluown.mobile.provider.qqmusic.parseQQMusicObject(
                "qqmusic",
                "<html><body>risk control</body></html>",
            )
        }.exceptionOrNull()

        assertEquals(
            ProviderFailureKind.ContentUnavailable,
            (failure as org.feeluown.mobile.ProviderOperationException).failure.kind,
        )
    }

    @Test
    fun jsonResponseParsesNormally() {
        val value = org.feeluown.mobile.provider.qqmusic.parseQQMusicObject(
            "qqmusic",
            """{"code":0}""",
        )

        assertEquals(0, value["code"]?.toString()?.toIntOrNull())
    }
}
