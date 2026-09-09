package org.feeluown.mobile

import android.app.Application
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Thin Android process host.
 *
 * Runtime dependency construction lives in [AndroidAppContainer] so the Application class only
 * exposes process-scoped entry points required by activities and services.
 */
class FuoEvolveApplication : Application() {
    private var containerHolder: AndroidAppContainer? = null
    private var bluetoothLyricsPublisher: BluetoothLyricsPublisher? = null

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
        get() {
            val holder = container()
            val graph = holder.appUiGraph
            graph.settings.setBluetoothLyricsAvailability(true)
            if (bluetoothLyricsPublisher == null) {
                bluetoothLyricsPublisher = BluetoothLyricsPublisher(
                    context = this,
                    playbackSession = graph.playbackSession,
                    enabled = holder.settingsRepository.state
                        .map { state -> state.settings.bluetoothLyricsEnabled }
                        .distinctUntilChanged(),
                ).also(BluetoothLyricsPublisher::start)
            }
            return graph
        }

    val appViewModel: FuoAppViewModel
        get() = container().appViewModel

    override fun onTerminate() {
        bluetoothLyricsPublisher?.close()
        bluetoothLyricsPublisher = null
        containerHolder?.close()
        containerHolder = null
        super.onTerminate()
    }
}
