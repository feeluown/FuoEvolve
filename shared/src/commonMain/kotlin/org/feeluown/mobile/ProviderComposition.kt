package org.feeluown.mobile

import org.feeluown.mobile.provider.bilibili.BilibiliProviderFactory
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.KotlinProviderFactory
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.ProviderPersistentCache
import org.feeluown.mobile.provider.netease.NeteaseProviderFactory
import org.feeluown.mobile.provider.qqmusic.QQMusicProviderFactory
import org.feeluown.mobile.provider.ytmusic.YtMusicProviderFactory

/** Single compile-time composition point that knows concrete provider plugins. */
internal object ProviderComposition {
    private val factories: List<KotlinProviderFactory> = listOf(
        NeteaseProviderFactory,
        QQMusicProviderFactory,
        BilibiliProviderFactory,
        YtMusicProviderFactory,
    )

    fun createProviders(
        http: ProviderHttpClient,
        credentials: ProviderCredentialStore,
    ): Map<String, KotlinMusicProvider> {
        val dependencies = ProviderRuntimeDependencies(http = http, credentials = credentials)
        return factories.associate { factory ->
            factory.providerId to factory.create(dependencies)
        }
    }

    fun createBilibiliContentProvider(
        credentials: ProviderCredentialStore,
        persistentCache: ProviderPersistentCache?,
    ): KotlinMusicProvider = BilibiliProviderFactory.createContentProvider(
        ProviderRuntimeDependencies(
            http = ProviderHttpClient(persistentCache = persistentCache),
            credentials = credentials,
        ),
    )
}
