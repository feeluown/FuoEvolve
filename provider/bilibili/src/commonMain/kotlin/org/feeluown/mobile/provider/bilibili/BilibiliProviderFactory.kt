package org.feeluown.mobile.provider.bilibili

import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.KotlinProviderFactory
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies

/** Compile-time plugin entry for the Bilibili provider module. */
object BilibiliProviderFactory : KotlinProviderFactory {
    override val providerId: String = "bilibili"

    override fun create(dependencies: ProviderRuntimeDependencies): KotlinMusicProvider =
        BilibiliProvider(
            http = dependencies.http,
            credentials = dependencies.credentials,
        )

    fun createContentProvider(dependencies: ProviderRuntimeDependencies): KotlinMusicProvider =
        BilibiliContentProvider(
            http = dependencies.http,
            credentials = dependencies.credentials,
        )
}
