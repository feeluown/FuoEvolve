package org.feeluown.mobile

import android.app.Application
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class FuoEvolveApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    internal val providerRepository: ProviderMusicRepository by lazy {
        createFuoProviderRepository(
            credentials = AndroidProviderCredentialStore(applicationContext),
            persistentCache = AndroidProviderCacheStore(applicationContext),
            isCellularConnection = ::isCellularConnection,
        )
    }

    private fun isCellularConnection(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private val localRepository: AndroidLocalMusicRepository by lazy {
        AndroidLocalMusicRepository(applicationContext)
    }

    private val localPlaylistRepository: AndroidLocalPlaylistRepository by lazy {
        AndroidLocalPlaylistRepository(applicationContext)
    }

    private val downloadRepository: AndroidDownloadRepository by lazy {
        AndroidDownloadRepository(applicationContext, providerRepository) { tasks ->
            FuoDownloadService.update(applicationContext, tasks)
        }
    }

    private val playbackEngine: AndroidNativeAudioEngine by lazy {
        AndroidNativeAudioEngine(applicationContext, appScope)
    }

    internal val settingsRepository: AppSettingsRepository by lazy {
        createAndroidAppSettingsRepository(applicationContext, appScope)
    }

    private val providerSessionRepository: ProviderSessionRepository by lazy {
        DefaultProviderSessionRepository(providerRepository)
    }

    private val navigator by lazy { AppNavigator() }

    private val playbackQueueStore: AndroidPlaybackQueueStore by lazy {
        AndroidPlaybackQueueStore(applicationContext)
    }

    private val resourceCacheRepository: AndroidResourceCacheRepository by lazy {
        AndroidResourceCacheRepository(applicationContext)
    }

    private val debugLogRepository: AndroidDebugLogRepository by lazy {
        AndroidDebugLogRepository(applicationContext, (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
    }

    private val audioRecognitionRepository: AndroidAudioRecognitionRepository by lazy {
        AndroidAudioRecognitionRepository(applicationContext)
    }

    val controller: FuoPlayerController by lazy {
        FuoPlayerController(
            providerRepository = providerRepository,
            localRepository = localRepository,
            localPlaylistRepository = localPlaylistRepository,
            downloadRepository = downloadRepository,
            playbackEngine = playbackEngine,
            settingsRepository = settingsRepository,
            providerSessionRepository = providerSessionRepository,
            navigator = navigator,
            playbackQueueStore = playbackQueueStore,
            resourceCacheRepository = resourceCacheRepository,
            debugLogRepository = debugLogRepository,
            audioRecognitionRepository = audioRecognitionRepository,
            oauthDeviceCodeAssistant = AndroidOAuthDeviceCodeAssistant(applicationContext),
            scope = appScope,
        ).also { controller ->
            FuoPlaybackService.transportControls = object : FuoPlaybackService.TransportControls {
                override fun toggle() {
                    controller.toggle()
                }

                override fun play() {
                    if (controller.playbackState.status != PlayerStatus.Playing) {
                        controller.toggle()
                    }
                }

                override fun pause() {
                    if (controller.playbackState.status == PlayerStatus.Playing) {
                        controller.toggle()
                    }
                }

                override fun previous() {
                    controller.previous()
                }

                override fun next() {
                    controller.next()
                }
            }
        }
    }

    val appViewModel: FuoAppViewModel by lazy {
        FuoAppViewModel(
            controller = controller,
            settingsRepository = settingsRepository,
            providerSessionRepository = providerSessionRepository,
            navigator = navigator,
        )
    }

    override fun onTerminate() {
        FuoPlaybackService.transportControls = null
        appScope.cancel()
        super.onTerminate()
    }
}
