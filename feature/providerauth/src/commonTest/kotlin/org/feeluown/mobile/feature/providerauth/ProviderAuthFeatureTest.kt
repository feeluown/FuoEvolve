package org.feeluown.mobile.feature.providerauth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderAuthFeatureTest {
    @Test
    fun deviceOAuthFlowOwnsPollingAndSessionLogin() = runTest {
        val session = FakeSessionPort()
        val device = FakeDeviceAuthorizationPort()
        val assistant = FakeAssistant()
        val owner = createProviderAuthFeatureOwner(
            sessionPort = session,
            deviceAuthorizationPort = device,
            deviceCodeAssistant = assistant,
            oauthImportPort = ProviderOAuthImportPort { ProviderOAuthImportResult.Unknown },
            scope = backgroundScope,
            providerId = Provider::id,
            providerName = { "YouTube Music" },
            defaultAuth = { Auth(false) },
            authProviderName = { "YouTube Music" },
            authIsLoggedIn = Auth::loggedIn,
            authUserName = { if (it.loggedIn) "User" else null },
            deviceOAuthProviderId = "ytmusic",
        )
        runCurrent()

        owner.onOAuthClientIdChange("ytmusic", "client")
        owner.onOAuthClientSecretChange("ytmusic", "secret")
        owner.startDeviceOAuthLogin()
        runCurrent()

        assertEquals("CODE", owner.state.value.oauthFlow?.userCode)
        assertEquals("CODE", assistant.shownCode)

        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(session.oauthLoggedIn)
        assertTrue(owner.authStateFor(Provider("ytmusic")).loggedIn)
        assertEquals(null, owner.state.value.oauthFlow)
        assertTrue(owner.state.value.feedback.orEmpty().contains("已通过 OAuth 登录"))
    }

    @Test
    fun oauthCredentialImportUpdatesFeatureOwnedInput() = runTest {
        val owner = createProviderAuthFeatureOwner(
            sessionPort = FakeSessionPort(),
            deviceAuthorizationPort = FakeDeviceAuthorizationPort(),
            deviceCodeAssistant = FakeAssistant(),
            oauthImportPort = ProviderOAuthImportPort {
                ProviderOAuthImportResult.Credentials("imported-id", "imported-secret")
            },
            scope = backgroundScope,
            providerId = Provider::id,
            providerName = { it },
            defaultAuth = { Auth(false) },
            authProviderName = { "Provider" },
            authIsLoggedIn = Auth::loggedIn,
            authUserName = { null },
            deviceOAuthProviderId = "ytmusic",
        )
        runCurrent()

        owner.importOAuthRelatedJson("ytmusic", "{}")

        assertEquals("imported-id", owner.oauthInput("ytmusic").clientId)
        assertEquals("imported-secret", owner.oauthInput("ytmusic").clientSecret)
    }

    private data class Provider(val id: String)
    private data class Auth(val loggedIn: Boolean)
    private data class Session(
        val auth: Map<String, Auth> = emptyMap(),
        val busy: Set<String> = emptySet(),
        val errors: Map<String, String> = emptyMap(),
    )

    private class FakeSessionPort : ProviderAuthSessionPort<Auth, Session> {
        private val mutableState = MutableStateFlow(Session())
        override val state: StateFlow<Session> = mutableState
        var oauthLoggedIn = false

        override fun authState(session: Session, providerId: String): Auth? = session.auth[providerId]
        override fun isBusy(session: Session, providerId: String): Boolean = providerId in session.busy
        override fun error(session: Session, providerId: String): String? = session.errors[providerId]
        override suspend fun refresh(providerId: String, refreshUserInfo: Boolean): Auth = Auth(false)
        override suspend fun loginWithCookies(providerId: String, cookiesJson: String): Auth = Auth(true)
        override suspend fun loginWithHeaders(providerId: String, authorization: String, cookie: String): Auth = Auth(true)
        override suspend fun loginWithHeaderFile(providerId: String, headerFileJson: String): Auth = Auth(true)
        override suspend fun loginWithOAuth(
            providerId: String,
            accessToken: String,
            refreshToken: String,
            expiresAtMillis: Long?,
            scope: String?,
            clientId: String,
            clientSecret: String,
        ): Auth {
            oauthLoggedIn = true
            val auth = Auth(true)
            mutableState.value = Session(auth = mapOf(providerId to auth))
            return auth
        }
        override suspend fun loginWithOAuthJson(
            providerId: String,
            oauthJson: String,
            clientId: String,
            clientSecret: String,
        ): Auth = Auth(true)
        override suspend fun logout(providerId: String): Auth = Auth(false)
    }

    private class FakeDeviceAuthorizationPort : ProviderDeviceAuthorizationPort {
        override suspend fun begin(
            providerId: String,
            clientId: String,
            clientSecret: String,
        ): ProviderDeviceAuthorization = ProviderDeviceAuthorization(
            deviceCode = "device",
            userCode = "CODE",
            verificationUrl = "https://example.test/device",
            verificationUrlWithCode = "https://example.test/device?user_code=CODE",
            expiresInSeconds = 60,
            intervalSeconds = 1,
        )

        override suspend fun poll(
            providerId: String,
            deviceCode: String,
            clientId: String,
            clientSecret: String,
        ): ProviderDeviceAuthorizationPollResult = ProviderDeviceAuthorizationPollResult.Authorized(
            ProviderOAuthToken("access", "refresh"),
        )
    }

    private class FakeAssistant : ProviderDeviceCodeAssistantPort {
        var shownCode: String? = null
        override fun showUserCodeNotification(userCode: String) {
            shownCode = userCode
        }
        override fun copyUserCode(userCode: String) = Unit
        override fun clearUserCodeNotification() = Unit
    }
}
