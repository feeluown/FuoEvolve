package org.feeluown.mobile.provider.qqmusic

import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.KotlinProviderFactory
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies

/** Compile-time plugin entry for the QQ Music provider module. */
object QQMusicProviderFactory : KotlinProviderFactory {
    override val providerId: String = "qqmusic"

    override fun create(dependencies: ProviderRuntimeDependencies): KotlinMusicProvider =
        QQMusicCompositeProvider(dependencies)
}
