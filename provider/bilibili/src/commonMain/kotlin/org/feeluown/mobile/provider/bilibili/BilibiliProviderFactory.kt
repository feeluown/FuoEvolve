package org.feeluown.mobile.provider.bilibili

import org.feeluown.mobile.provider.core.CapabilityDelegatingProvider
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.KotlinProviderFactory
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies

/** Compile-time plugin entry for the Bilibili provider module. */
object BilibiliProviderFactory : KotlinProviderFactory {
    override val providerId: String = "bilibili"

    override fun create(dependencies: ProviderRuntimeDependencies): KotlinMusicProvider {
        val base = BilibiliProvider(
            http = dependencies.http,
            credentials = dependencies.credentials,
        )
        val content = BilibiliContentProvider(
            http = dependencies.http,
            credentials = dependencies.credentials,
            delegate = base,
        )
        val discovery = BilibiliComprehensiveSearchProvider(
            delegate = content,
            http = dependencies.http,
            credentials = dependencies.credentials,
        )
        return CapabilityDelegatingProvider(
            base = base,
            presentation = content,
            account = base,
            discovery = discovery,
            content = content,
            library = content,
            playback = base,
        )
    }

    fun createContentProvider(dependencies: ProviderRuntimeDependencies): KotlinMusicProvider {
        val base = BilibiliProvider(
            http = dependencies.http,
            credentials = dependencies.credentials,
        )
        return BilibiliContentProvider(
            http = dependencies.http,
            credentials = dependencies.credentials,
            delegate = base,
        )
    }
}
