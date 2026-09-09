package org.feeluown.mobile

import android.app.Application
import android.os.Handler
import android.os.Looper
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
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun container(): AndroidAppContainer {
        containerHolder?.let { return it }
        return AndroidAppContainer(this).also { holder ->
            containerHolder = holder
            // A playback service can be the process entry point. Defer graph wiring until the
            // current service/activity initialization stack has completed to avoid recursively
            // connecting a MediaController while FuoPlaybackService is still creating its session.
            mainHandler.post {
                if (containerHolder === holder) ensureBluetoothLyricsPublisher(holder)
            }
        }
    }

    private fun ensureBluetoothLyricsPublisher(holder: AndroidAppContainer) {
        val graph = holder.appUiGraph
        graph.settings.setBluetoothLyricsAvailability(true)
        if (bluetoothLyricsPublisher != null) return
        bluetoothLyricsPublisher = BluetoothLyricsPublisher(
            context = this,
            playbackSession = graph.playbackSession,
            enabled = holder.settingsRepository.state
                .map { state -> state.settings.bluetoothLyricsEnabled }
                .distinctUntilChanged(),
        ).also(BluetoothLyricsPublisher::start)
    }

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
            ensureBluetoothLyricsPublisher(holder)
            return holder.appUiGraph
        }

    val appViewModel: FuoAppViewModel
        get() = container().appViewModel

    override fun onTerminate() {
        mainHandler.removeCallbacksAndMessages(null)
        bluetoothLyricsPublisher?.close()
        bluetoothLyricsPublisher = null
        containerHolder?.close()
        containerHolder = null
        super.onTerminate()
    }
}
