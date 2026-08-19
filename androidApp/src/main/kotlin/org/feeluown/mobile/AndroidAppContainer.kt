package org.feeluown.mobile

import android.app.Application
import android.content.Context
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

/**
 * Android composition root.
 *
 * Keeps platform service wiring and repository construction out of [FuoEvolveApplication],
 * matching the iOS-side container pattern while preserving the existing runtime behavior.
 */
internal class AndroidAppContainer(
    private val application: Application,
) : AutoCloseable {
    private val context: Context = application.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lyriconLyricsPublisher: LyriconLyricsPublisher? = null

    val providerRepository: ProviderMusicRepository by lazy {
        createFuoProviderRepository(
            credentials = AndroidProviderCredentialStore(context),
            persistentCache = AndroidProviderCacheStore(context),
            isCellularConnection = ::isCellularConnection,
        )
    }

    private fun isCellularConnection(): Boolean {
        val connectivityManager = application.getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private val offlineAssetStore: AndroidOfflineAssetStore by lazy {
        AndroidOfflineAssetStore(context)
    }

    private val indexedLocalRepository: LocalMusicRepository by lazy {
        AndroidIndexedLocalMusicRepository(
            context = context,
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
        AndroidLocalPlaylistRepository(context)
    }

    private val rawDownloadRepository: AndroidDownloadRepository by lazy {
        AndroidDownloadRepository(context, providerRepository) { tasks ->
            FuoDownloadService.update(context, tasks)
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
        AndroidNativeAudioEngine(context, appScope)
    }

    val settingsRepository: AppSettingsRepository by lazy {
        createAndroidAppSettingsRepository(context, appScope)
    }

    private val providerSessionRepository: ProviderSessionRepository by lazy {
        DefaultProviderSessionRepository(providerRepository)
    }

    private val navigator by lazy { AppNavigator() }

    private val playbackQueueStore: AndroidPlaybackQueueStore by lazy {
        AndroidPlaybackQueueStore(context)
    }

    private val playbackResumeStore: AndroidPlaybackResumeStore by lazy {
        AndroidPlaybackResumeStore(context)
    }

    private val resourceCacheRepository: AndroidResourceCacheRepository by lazy {
        AndroidResourceCacheRepository(context)
    }

    private val debugLogRepository: AndroidDebugLogRepository by lazy {
        AndroidDebugLogRepository(
            context,
            (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
    }

    private val audioRecognitionRepository: AndroidAudioRecognitionRepository by lazy {
        AndroidAudioRecognitionRepository(context)
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
            oauthDeviceCodeAssistant = AndroidOAuthDeviceCodeAssistant(context),
            scope = appScope,
        ).also(::wireController)
    }

    val appViewModel: FuoAppViewModel by lazy {
        FuoAppViewModel(
            controller = controller,
            settingsRepository = settingsRepository,
            providerSessionRepository = providerSessionRepository,
            navigator = navigator,
        )
    }

    private fun wireController(controller: FuoPlayerController) {
        controller.updateStatusBarLyricsAvailability(isLyriconInstalled(context))
        lyriconLyricsPublisher = LyriconLyricsPublisher(
            context = context,
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
                    playbackEngine.republishRestoredState()
                    if (
                        trackId != null &&
                        (status == PlayerStatus.Playing || status == PlayerStatus.Paused)
                    ) {
                        playbackQueueStore.flushLatest()
                        playbackResumeStore.flush()
                    }
                }
        }
    }

    override fun close() {
        FuoPlaybackService.transportControls = null
        lyriconLyricsPublisher?.close()
        lyriconLyricsPublisher = null
        appScope.cancel()
    }
}
