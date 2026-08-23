package org.feeluown.mobile.provider.core

import org.feeluown.mobile.ProviderAuthState
import org.feeluown.mobile.ProviderDeviceAuthorization
import org.feeluown.mobile.ProviderDeviceAuthorizationPollResult

/** Optional provider-neutral capability for OAuth device-code login flows. */
interface ProviderDeviceAuthorizationCapability {
    suspend fun beginDeviceAuthorization(
        clientId: String,
        clientSecret: String,
    ): ProviderDeviceAuthorization

    suspend fun pollDeviceAuthorization(
        deviceCode: String,
        clientId: String,
        clientSecret: String,
    ): ProviderDeviceAuthorizationPollResult

    suspend fun loginWithOAuthJson(
        oauthJson: String,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState
}
