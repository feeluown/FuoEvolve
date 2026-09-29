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

    override fun onCreate() {
        super.onCreate()
        installAndroidAppLogger(this)
        installPlaylistMigrationBackgroundScheduler { request ->
            AndroidPlaylistMigrationWorker.enqueue(applicationContext, request)
        }
        // Admit this app into the ColorOS lock-screen lyric module's player union. The module reads
        // the admission long before playback starts (media history, AOD panel, lyric entrance), so
        // it must not wait for the playback service.
        ColorOsBridgeBindings.publish(this, "application-start")
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
        installPlaylistMigrationBackgroundScheduler(null)
        containerHolder?.close()
        containerHolder = null
        super.onTerminate()
    }
}
