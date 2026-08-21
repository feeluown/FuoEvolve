package org.feeluown.mobile

import android.app.Application

/**
 * Thin Android process host.
 *
 * Runtime dependency construction lives in [AndroidAppContainer] so the Application class only
 * exposes process-scoped entry points required by activities and services.
 */
class FuoEvolveApplication : Application() {
    private var containerHolder: AndroidAppContainer? = null

    private fun container(): AndroidAppContainer =
        containerHolder ?: AndroidAppContainer(this).also { containerHolder = it }

    internal val providerRepository: ProviderMusicRepository
        get() = container().providerRepository

    internal val settingsRepository: AppSettingsRepository
        get() = container().settingsRepository

    val appViewModel: FuoAppViewModel
        get() = container().appViewModel

    override fun onTerminate() {
        containerHolder?.close()
        containerHolder = null
        super.onTerminate()
    }
}
