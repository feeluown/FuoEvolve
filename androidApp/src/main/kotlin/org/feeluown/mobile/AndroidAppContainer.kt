package org.feeluown.mobile

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionStatus

internal class AndroidAppContainer(
    private val application: Application,
) : AutoCloseable {
    private val context: Context = application.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lyriconLyricsPublisher: LyriconLyricsPublisher? = null
    private var playbackSessionHolder: PlaybackSession? = null
    private val playbackSession: PlaybackSession
        get() = checkNotNull(playbackSessionHolder) { "Playback runtime is not wired" }

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

    private val offlineAssetStore: AndroidOfflineAssetStore by lazy { AndroidOfflineAssetStore(context) }
    private val indexedLocalRepository: LocalMusicRepository by lazy {
        AndroidIndexedLocalMusicRepository(context = context, assetStore = offlineAssetStore)
    }
    private val localRepository: LocalMusicRepository by lazy {
        AndroidCoalescingLocalMusicRepository(
            delegate = indexedLocalRepository,
            assetStore = offlineAssetStore,
        )
    }
    private val localPlaylistRepository: AndroidLocalPlaylistRepository by lazy { AndroidLocalPlaylistRepository(context) }
    private val rawDownloadRepository: AndroidDownloadRepository by lazy {
        AndroidDownloadRepository(context, providerRepository) { tasks -> FuoDownloadService.update(context, tasks) }
    }
    private val downloadRepository: DownloadRepository by lazy {
        AndroidOfflineAwareDownloadRepository(delegate = rawDownloadRepository, assetStore = offlineAssetStore, scope = appScope)
    }
    private val playbackEngine: AndroidNativeAudioEngine by lazy { AndroidNativeAudioEngine(context, appScope) }
    val settingsRepository: AppSettingsRepository by lazy { createAndroidAppSettingsRepository(context, appScope) }
    private val providerSessionRepository: ProviderSessionRepository by lazy { DefaultProviderSessionRepository(providerRepository) }
    private val navigator by lazy { AppNavigator() }
    private val oauthDeviceCodeAssistant: OAuthDeviceCodeAssistant by lazy { AndroidOAuthDeviceCodeAssistant(context) }

    private val searchController: SearchFeatureController by lazy {
        val initialSettings = settingsRepository.state.value.settings
        createSearchFeatureController(
            providerRepository = providerRepository,
            localRepository = localRepository,
            scope = appScope,
            providerIdsForSearch = {
                val activeProviderIds = providerSessionRepository.state.value.authStates.keys
                settingsRepository.state.value.settings.searchProviderIdsForFeature().filter(activeProviderIds::contains)
            },
            providerExists = { it in providerSessionRepository.state.value.authStates },
            openSearch = { navigator.navigate(AppRoute.Search) },
            onPreferencesChanged = { searchScope, selectedProviderId ->
                appScope.launch {
                    settingsRepository.update { settings ->
                        settings.copy(searchScope = searchScope, selectedSearchProviderId = selectedProviderId)
                    }
                }
            },
            initialState = SearchUiState(
                searchScope = initialSettings.searchScope,
                selectedSearchProviderId = initialSettings.selectedSearchProviderId,
            ),
        )
    }

    private val playbackQueueStore: AndroidPlaybackQueueStore by lazy { AndroidPlaybackQueueStore(context) }
    private val playbackResumeStore: AndroidPlaybackResumeStore by lazy { AndroidPlaybackResumeStore(context) }
    private val resourceCacheRepository: AndroidResourceCacheRepository by lazy { AndroidResourceCacheRepository(context) }
    private val debugLogRepository: AndroidDebugLogRepository by lazy {
        AndroidDebugLogRepository(context, (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
    }
    private val debugLogFeatureController: DebugLogFeatureController by lazy {
        createDebugLogFeatureController(debugLogRepository, appScope)
    }
    private val audioRecognitionRepository: AndroidAudioRecognitionRepository by lazy { AndroidAudioRecognitionRepository(context) }
    private val recognitionController: RecognitionFeatureController by lazy {
        createRecognitionFeatureController(
            repository = audioRecognitionRepository,
            scope = appScope,
            isPlaybackActive = { playbackEngine.state.value.status == PlayerStatus.Playing },
            pausePlayback = playbackEngine::pause,
        )
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
            oauthDeviceCodeAssistant = oauthDeviceCodeAssistant,
            scope = appScope,
            searchFeatureController = searchController,
            recognitionFeatureController = recognitionController,
        ).also(::wireController)
    }

    private val localMusicFeatureController: LocalMusicFeatureController by lazy {
        controller.localMusicActionPort as? LocalMusicFeatureController
            ?: error("Local Music feature owner is not installed")
    }

    private val providerCatalogFeatureController: ProviderCatalogFeatureController by lazy {
        createProviderCatalogFeatureController(
            providerRepository = providerRepository,
            sessionRepository = providerSessionRepository,
            settingsRepository = settingsRepository,
            scope = appScope,
        )
    }

    private val localPlaylistFeatureController: LocalPlaylistFeatureController by lazy {
        createLocalPlaylistFeatureController(
            repository = localPlaylistRepository,
            navigator = navigator,
            scope = appScope,
            providers = { providerCatalogFeatureController.uiState.value.providers },
        )
    }

    private val providerAuthFeatureController: ProviderAuthFeatureController by lazy {
        createProviderAuthFeatureController(
            providerRepository = providerRepository,
            sessionRepository = providerSessionRepository,
            oauthDeviceCodeAssistant = oauthDeviceCodeAssistant,
            scope = appScope,
            providerName = { providerId ->
                providerCatalogFeatureController.uiState.value.availableProviders
                    .firstOrNull { it.providerId == providerId }?.providerName ?: providerId
            },
            onSessionChanged = {
                homeFeatureController.refreshHome(HomeSection.Recommend)
                homeFeatureController.refreshHome(HomeSection.Music)
                homeFeatureController.refreshMine()
            },
        )
    }

    private val settingsFeatureController: SettingsFeatureController by lazy {
        createSettingsFeatureController(
            settingsRepository = settingsRepository,
            providerRepository = providerRepository,
            downloadRepository = downloadRepository,
            resourceCacheRepository = resourceCacheRepository,
            localMusicController = localMusicFeatureController,
            debugLogViewerAvailable = debugLogFeatureController.isAvailable,
            navigator = navigator,
            scope = appScope,
        )
    }

    private val onboardingFeatureController: OnboardingFeatureController by lazy {
        createOnboardingFeatureController(
            providerRepository = providerRepository,
            settingsRepository = settingsRepository,
            providerCatalog = providerCatalogFeatureController,
            scope = appScope,
        )
    }

    private val providerDetailOwners: ProviderDetailOwners by lazy {
        createProviderDetailOwners(
            providerRepository = providerRepository,
            playbackQueue = controller.playbackQueueUiPort,
            settingsRepository = settingsRepository,
            providerCatalog = providerCatalogFeatureController,
            navigator = navigator,
            scope = appScope,
            onProviderMutation = { homeFeatureController.refreshMine() },
        )
    }

    private val homeFeatureController: HomeFeatureController by lazy {
        createHomeFeatureController(
            providerRepository = providerRepository,
            providerCatalog = providerCatalogFeatureController,
            providerDetails = providerDetailOwners,
            playbackQueue = controller.playbackQueueUiPort,
            localPlaylist = localPlaylistFeatureController,
            localMusic = localMusicFeatureController,
            settingsRepository = settingsRepository,
            navigator = navigator,
            scope = appScope,
        )
    }

    private val sharedResourceActionPort: SharedResourceActionPort by lazy {
        createSharedResourceActionPort(
            providerRepository = providerRepository,
            providerCatalog = providerCatalogFeatureController,
            providerDetails = providerDetailOwners,
            settingsRepository = settingsRepository,
            scope = appScope,
        )
    }

    private val searchAppPort: SearchAppPort by lazy {
        val wiredController = controller
        DefaultSearchAppPort(
            searchController = searchController,
            providerSessions = { providerSessionRepository.state.value },
            playbackQueue = wiredController.playbackQueueUiPort,
            downloads = wiredController.downloadActionPort,
            playlists = wiredController.playlistActionPort,
            providerTrackActions = wiredController.providerTrackActionPort,
            navigator = navigator,
        )
    }

    private val recognitionAppPort: RecognitionAppPort by lazy {
        DefaultRecognitionAppPort(
            isProviderEnabled = { providerId -> providerSessionRepository.state.value.providers.any { it.providerId == providerId } },
            loadTrackDetail = providerRepository::trackDetail,
            navigator = navigator,
        )
    }

    private val playbackPresentationPort: PlaybackPresentationPort by lazy {
        DefaultPlaybackPresentationPort(
            playbackEngine = playbackEngine,
            queuePort = controller.playbackQueueUiPort,
            settingsRepository = settingsRepository,
            scope = appScope,
        )
    }

    val appViewModel: FuoAppViewModel by lazy {
        val wiredController = controller
        FuoAppViewModel(
            playbackSession = playbackSession,
            playbackNavigationPort = wiredController.playbackNavigationPort,
            playbackPresentationPort = playbackPresentationPort,
            playbackQueueUiPort = wiredController.playbackQueueUiPort,
            playbackSleepTimerPort = wiredController.playbackSleepTimerPort,
            downloadActionPort = wiredController.downloadActionPort,
            playlistActionPort = wiredController.playlistActionPort,
            providerTrackActionPort = wiredController.providerTrackActionPort,
            localMusicActionPort = localMusicFeatureController,
            replacementActionPort = wiredController.replacementActionPort,
            debugLogFeatureController = debugLogFeatureController,
            providerCatalogFeatureController = providerCatalogFeatureController,
            providerAuthFeatureController = providerAuthFeatureController,
            settingsFeatureController = settingsFeatureController,
            onboardingFeatureController = onboardingFeatureController,
            providerDetailOwners = providerDetailOwners,
            localMusicFeatureController = localMusicFeatureController,
            localPlaylistFeatureController = localPlaylistFeatureController,
            homeFeatureController = homeFeatureController,
            sharedResourceActionPort = sharedResourceActionPort,
            searchController = searchController,
            recognitionController = recognitionController,
            searchAppPort = searchAppPort,
            recognitionAppPort = recognitionAppPort,
            settingsRepository = settingsRepository,
            providerSessionRepository = providerSessionRepository,
            navigator = navigator,
        )
    }

    private fun wireController(controller: FuoPlayerController) {
        val session = createPlaybackRuntimeSession(
            controller = controller,
            playbackEngine = playbackEngine,
            transportCoordinator = controller.playbackTransportCoordinator,
            startFailureSource = controller.playbackStartFailureSource,
            scope = appScope,
        )
        playbackSessionHolder = session
        controller.updateStatusBarLyricsAvailability(isLyriconInstalled(context))
        lyriconLyricsPublisher = LyriconLyricsPublisher(
            context = context,
            playbackSession = session,
            statusBarLyricsEnabled = { controller.statusBarLyricsEnabled },
            scope = appScope,
        ).also(LyriconLyricsPublisher::start)

        FuoPlaybackService.transportControls = object : FuoPlaybackService.TransportControls {
            override fun toggle() = session.toggle()
            override fun play() = session.play()
            override fun pause() = session.pause()
            override fun previous() = session.previous()
            override fun next() = session.next()
        }

        appScope.launch {
            session.state.map { it.currentTrack?.id to it.lyrics }.distinctUntilChanged().collect { (trackId, lyrics) ->
                if (trackId != null) playbackEngine.publishLockScreenLyrics(trackId, lyrics)
            }
        }
        appScope.launch {
            session.state.map { state ->
                Triple(state.currentTrack?.id, state.status, state.queueTrackIds to state.queueIndex)
            }.distinctUntilChanged().collect { (trackId, status, _) ->
                playbackEngine.republishRestoredState()
                if (trackId != null && (status == PlaybackSessionStatus.Playing || status == PlaybackSessionStatus.Paused)) {
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
        playbackSessionHolder = null
        appScope.cancel()
    }
}
