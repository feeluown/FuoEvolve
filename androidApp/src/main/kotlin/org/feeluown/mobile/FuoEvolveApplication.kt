package org.feeluown.mobile

import android.app.Application
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class FuoEvolveApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lyriconLyricsPublisher: LyriconLyricsPublisher? = null

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

    private val offlineAssetStore: AndroidOfflineAssetStore by lazy {
        AndroidOfflineAssetStore(applicationContext)
    }

    private val indexedLocalRepository: LocalMusicRepository by lazy {
        AndroidIndexedLocalMusicRepository(
            context = applicationContext,
            assetStore = offlineAssetStore,
        )
    }

    private val localRepository: LocalMusicRepository by lazy {
        AndroidCoalescingLocalMusicRepository(
            delegate = indexedLocalRepository,
            assetStore = offlineAssetStore,
        )
    }

    private val localPlaylistRepository: AndroidLocalPlaylistRepository by lazy {
        AndroidLocalPlaylistRepository(applicationContext)
    }

    private val rawDownloadRepository: AndroidDownloadRepository by lazy {
        AndroidDownloadRepository(applicationContext, providerRepository) { tasks ->
            FuoDownloadService.update(applicationContext, tasks)
        }
    }

    private val downloadRepository: DownloadRepository by lazy {
        AndroidOfflineAwareDownloadRepository(
            delegate = rawDownloadRepository,
            assetStore = offlineAssetStore,
            scope = appScope,
        )
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

    private val playbackResumeStore: AndroidPlaybackResumeStore by lazy {
        AndroidPlaybackResumeStore(applicationContext)
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
            controller.updateStatusBarLyricsAvailability(isLyriconInstalled(applicationContext))
            lyriconLyricsPublisher = LyriconLyricsPublisher(
                context = applicationContext,
                controller = controller,
                scope = appScope,
            ).also(LyriconLyricsPublisher::start)
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
            appScope.launch {
                snapshotFlow {
                    controller.playbackState.let { state ->
                        state.currentTrack?.id to state.lyrics
                    }
                }
                    .distinctUntilChanged()
                    .collect { (trackId, lyrics) ->
                        if (trackId != null) {
                            playbackEngine.publishLockScreenLyrics(trackId, lyrics)
                        }
                    }
            }
            appScope.launch {
                snapshotFlow {
                    controller.playbackState.let { state ->
                        Triple(
                            state.currentTrack?.id,
                            state.status,
                            state.queue.map { it.id } to state.queueIndex,
                        )
                    }
                }
                    .distinctUntilChanged()
                    .collect { (trackId, status, _) ->
                        // Queue restoration can briefly replace a valid restored currentTrack with
                        // null. Re-publish the Android resume snapshot for that transition too, not
                        // only when a non-empty queue appears, so the mini player remains visible.
                        playbackEngine.republishRestoredState()

                        if (
                            trackId != null &&
                            (status == PlayerStatus.Playing || status == PlayerStatus.Paused)
                        ) {
                            // The store captured the controller's complete queue before its async IO
                            // suspension. Flush that exact snapshot rather than reconstructing from
                            // the UI display queue, which would lose already-played items.
                            playbackQueueStore.flushLatest()
                            playbackResumeStore.flush()
                        }
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
        lyriconLyricsPublisher?.close()
        lyriconLyricsPublisher = null
        appScope.cancel()
        super.onTerminate()
    }
}
