package org.feeluown.mobile

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager

/**
 * Thin Android process host.
 *
 * Runtime dependency construction lives in [AndroidAppContainer] so the Application class only
 * exposes process-scoped entry points required by activities and services.
 */
class FuoEvolveApplication : Application() {
    private var containerHolder: AndroidAppContainer? = null

    // The playback service keeps this process alive during background playback. Register at the
    // process level so disconnects are handled even if the activity has been destroyed.
    private val noisyAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            if (FuoPlaybackService.playbackState.value.status == PlayerStatus.Playing) {
                FuoPlaybackService.pause(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        installAndroidAppLogger(this)
        registerReceiver(
            noisyAudioReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
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
        unregisterReceiver(noisyAudioReceiver)
        containerHolder?.close()
        containerHolder = null
        super.onTerminate()
    }
}
