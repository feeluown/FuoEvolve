package org.feeluown.mobile

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.feeluown.mobile.provider.core.BaseKotlinProvider
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentials
import org.feeluown.mobile.provider.core.network.ProviderHttpClient

class ProviderOAuthTest {
    @Test
    fun oauthLoginStoresTokenAndBuildsBearerHeader() = runTest {
        val credentials = InMemoryProviderCredentialStore()
        val httpClient = ProviderHttpClient(
            HttpClient(MockEngine) {
                engine {
                    addHandler { respond("") }
                }
            },
        )
        val provider = OAuthProbeProvider(httpClient, credentials)

        val state = provider.loginWithOAuth(
            accessToken = "  access-token  ",
            expiresAtMillis = 123_456L,
            grantedScopes = setOf("https://www.googleapis.com/auth/youtube"),
        )

        assertTrue(state.isLoggedIn)
        assertEquals("Bearer access-token", provider.authorizationHeader())
        assertEquals(
            ProviderCredentials(
                oauthAccessToken = "access-token",
                oauthTokenExpiresAtMillis = 123_456L,
                oauthGrantedScopes = setOf("https://www.googleapis.com/auth/youtube"),
            ),
            credentials.read("oauth-test"),
        )

        httpClient.close()
    }

    private class OAuthProbeProvider(
        http: ProviderHttpClient,
        credentials: ProviderCredentialStore,
    ) : BaseKotlinProvider(
        http = http,
        credentials = credentials,
        id = "oauth-test",
        name = "OAuth Test",
        info = ProviderInfo(
            providerId = "oauth-test",
            providerName = "OAuth Test",
            supportedLoginModes = setOf(ProviderLoginMode.OAuth),
        ),
        capabilities = ProviderCapabilities("oauth-test", "OAuth Test"),
        features = emptyList(),
    ) {
        suspend fun authorizationHeader(): String? = authenticatedHeaders()["Authorization"]
    }
}
