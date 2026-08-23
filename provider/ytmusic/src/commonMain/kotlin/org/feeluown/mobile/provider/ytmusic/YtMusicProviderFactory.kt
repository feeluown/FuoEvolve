package org.feeluown.mobile.provider.ytmusic

import org.feeluown.mobile.ProviderDeviceAuthorization
import org.feeluown.mobile.ProviderDeviceAuthorizationPollResult
import org.feeluown.mobile.ProviderOAuthToken
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.KotlinProviderFactory
import org.feeluown.mobile.provider.core.ProviderDeviceAuthorizationCapability
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies

/** Compile-time plugin entry for the YouTube Music provider module. */
object YtMusicProviderFactory : KotlinProviderFactory {
    override val providerId: String = "ytmusic"

    override fun create(dependencies: ProviderRuntimeDependencies): KotlinMusicProvider {
        val base = YtMusicProvider(dependencies.http, dependencies.credentials)
        val content = YtMusicContentProvider(base, dependencies.http, dependencies.credentials)
        return YtMusicApplicationProvider(content)
    }
}

private class YtMusicApplicationProvider(
    private val content: YtMusicContentProvider,
) : KotlinMusicProvider by content, ProviderDeviceAuthorizationCapability {
    override suspend fun beginDeviceAuthorization(
        clientId: String,
        clientSecret: String,
    ): ProviderDeviceAuthorization = content.beginOAuth(clientId, clientSecret).let { code ->
        ProviderDeviceAuthorization(
            providerId = YtMusicProvider.ID,
            deviceCode = code.deviceCode,
            userCode = code.userCode,
            verificationUrl = code.verificationUrl,
            expiresInSeconds = code.expiresInSeconds,
            intervalSeconds = code.intervalSeconds,
        )
    }

    override suspend fun pollDeviceAuthorization(
        deviceCode: String,
        clientId: String,
        clientSecret: String,
    ): ProviderDeviceAuthorizationPollResult = when (
        val result = content.pollOAuth(deviceCode, clientId, clientSecret)
    ) {
        is YtMusicOAuthPollResult.Authorized -> ProviderDeviceAuthorizationPollResult.Authorized(
            ProviderOAuthToken(
                accessToken = result.token.accessToken,
                refreshToken = result.token.refreshToken,
                scope = result.token.scope,
                expiresAtMillis = result.token.expiresAtEpochSeconds * 1_000L,
            ),
        )
        YtMusicOAuthPollResult.Pending -> ProviderDeviceAuthorizationPollResult.Pending
        YtMusicOAuthPollResult.SlowDown -> ProviderDeviceAuthorizationPollResult.SlowDown
        is YtMusicOAuthPollResult.Denied -> ProviderDeviceAuthorizationPollResult.Denied(result.message)
    }

    override suspend fun loginWithOAuthJson(
        oauthJson: String,
        clientId: String,
        clientSecret: String,
    ) = content.loginWithOAuthJson(oauthJson, clientId, clientSecret)
}
