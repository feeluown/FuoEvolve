package org.feeluown.mobile.provider.netease

import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.KotlinProviderFactory
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies

/** Compile-time plugin entry for the NetEase provider module. */
object NeteaseProviderFactory : KotlinProviderFactory {
    override val providerId: String = "netease"

    override fun create(dependencies: ProviderRuntimeDependencies): KotlinMusicProvider {
        val base = NeteaseProvider(
            http = dependencies.http,
            credentials = dependencies.credentials,
        )
        return NeteaseComprehensiveSearchProvider(
            delegate = base,
            http = dependencies.http,
            credentials = dependencies.credentials,
        )
    }
}