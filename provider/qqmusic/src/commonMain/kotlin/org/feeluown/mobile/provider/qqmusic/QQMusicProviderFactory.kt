package org.feeluown.mobile.provider.qqmusic

import org.feeluown.mobile.provider.core.CapabilityDelegatingProvider
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.KotlinProviderFactory
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies

/** Compile-time plugin entry for the QQ Music provider module. */
object QQMusicProviderFactory : KotlinProviderFactory {
    override val providerId: String = "qqmusic"

    override fun create(dependencies: ProviderRuntimeDependencies): KotlinMusicProvider {
        val base = QQMusicProvider(
            http = dependencies.http,
            credentials = dependencies.credentials,
        )
        val content = QQMusicContentProvider(
            http = dependencies.http,
            credentials = dependencies.credentials,
            delegate = base,
        )
        val composite = QQMusicCompositeProvider(
            dependencies = dependencies,
            content = content,
        )
        val discovery = QQMusicComprehensiveSearchProvider(
            delegate = composite,
            http = dependencies.http,
            credentials = dependencies.credentials,
        )
        return CapabilityDelegatingProvider(
            base = base,
            presentation = composite,
            account = composite,
            discovery = discovery,
            content = composite,
            library = composite,
            playback = base,
        )
    }
}
