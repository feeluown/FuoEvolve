package org.feeluown.mobile.provider.core

import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.ProviderInfo

/** Runtime dependencies supplied by the application composition root to concrete providers. */
data class ProviderRuntimeDependencies(
    val http: ProviderHttpClient,
    val credentials: ProviderCredentialStore,
)

/** Construction SPI for independently compiled concrete provider modules. */
interface KotlinProviderFactory {
    val providerId: String
    val info: ProviderInfo

    fun create(dependencies: ProviderRuntimeDependencies): KotlinMusicProvider
}
