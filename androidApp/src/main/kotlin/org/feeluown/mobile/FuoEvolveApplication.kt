package org.feeluown.mobile

import android.app.Application
import org.feeluown.mobile.persistence.listening.AndroidListeningHistoryDriverFactory
import org.feeluown.mobile.persistence.listening.SqlDelightListeningHistoryStore

/**
 * Thin Android process host.
 *
 * Runtime dependency construction lives in [AndroidAppContainer] so the Application class only
 * exposes process-scoped entry points required by activities and services.
 */
class FuoEvolveApplication : Application() {
    private var containerHolder: AndroidAppContainer? = null

    override fun onCreate() {
        super.onCreate()
        installAppListeningHistorySink(
            SqlDelightListeningHistoryStore(
                AndroidListeningHistoryDriverFactory(this),
            )
        )
    }

    private fun container(): AndroidAppContainer =
        containerHolder ?: AndroidAppContainer(this).also { containerHolder = it }

    /** Playback-service provider surface; smart replacement policy is owned by :feature:playback. */
    internal val providerRepository: PlaybackProviderPort
        get() = container().playbackProvider

    internal val providerCredentialBackup: AndroidProviderCredentialBackup
        get() = container().providerCredentialBackup

    internal val settingsRepository: AppSettingsRepository
        get() = container().settingsRepository

    internal val appUiGraph: AppUiGraph
        get() = container().appUiGraph

    val appViewModel: FuoAppViewModel
        get() = container().appViewModel

    override fun onTerminate() {
        containerHolder?.close()
        containerHolder = null
        resetAppListeningHistorySink()
        super.onTerminate()
    }
}
