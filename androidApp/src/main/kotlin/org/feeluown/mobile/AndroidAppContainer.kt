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
    private var bydInstrumentLyricsPublisher: BydInstrumentLyricsPublisher? = null

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

    private val recognitionController: RecognitionFeatureController by lazy {
        createRecognitionFeatureController(
            repository = audioRecognitionRepository,
            scope = appScope,
            isPlaybackActive = { playbackEngine.state.value.status == PlayerStatus.Playing },
            pausePlayback = playbackEngine::pause,
        )
    }

    private val providerCatalogFeatureController: ProviderCatalogFeatureController by lazy {
        createProviderCatalogFeatureController(
            providerRepository = providerRepository,
            sessionRepository = providerSessionRepository,
            settingsRepository = settingsRepository,
            scope = appScope,
        )
    }

    private val localPlaylistFeatureController: LocalPlaylistFeatureOwner by lazy {
        createLocalPlaylistFeatureController(
            repository = localPlaylistRepository,
            navigator = navigator,
            scope = appScope,
            providers = { providerCatalogFeatureController.uiState.value.providers },
        )
    }

    private val localMusicFeatureController: LocalMusicFeatureController by lazy {
        createLocalMusicFeatureController(
            repository = localRepository,
            providerRepository = providerRepository,
            navigator = navigator,
            settingsRepository = settingsRepository,
            providers = { providerCatalogFeatureController.uiState.value.providers },
            isLocalMusicSectionActive = {
                val state = homeFeatureController.uiState.value
                state.homeSection == HomeSection.Mine && state.mineSection == MineSection.LocalMusic
            },
            scope = appScope,
            onTrackUpdated = { trackId, track -> playbackFeatureOwner.updateTrackCopies(trackId, track) },
        )
    }

    private val downloadActionPort: DownloadActionPort by lazy {
        createDownloadActionPort(
            providerRepository = providerRepository,
            downloadRepository = downloadRepository,
            localRepository = localRepository,
            localMusicController = localMusicFeatureController,
            settingsRepository = settingsRepository,
            scope = appScope,
            isLocalMusicSectionActive = {
                val state = homeFeatureController.uiState.value
                state.homeSection == HomeSection.Mine && state.mineSection == MineSection.LocalMusic
            },
        )
    }

    private val playbackFeatureOwner: PlaybackFeatureOwner by lazy {
        createPlaybackFeatureOwner(
            providerRepository = providerRepository,
            playbackEngine = playbackEngine,
            playbackQueueStore = playbackQueueStore,
            settingsRepository = settingsRepository,
            downloadActions = downloadActionPort,
            scope = appScope,
            openTrackDetail = { track -> providerDetailOwners.track.open(track) },
        )
    }

    private val providerDetailOwners: ProviderDetailOwners by lazy {
        createProviderDetailOwners(
            providerRepository = providerRepository,
            playbackQueue = playbackFeatureOwner.transport,
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
            playbackQueue = playbackFeatureOwner.transport,
            localPlaylist = localPlaylistFeatureController,
            localMusic = localMusicFeatureController,
            settingsRepository = settingsRepository,
            navigator = navigator,
            scope = appScope,
        )
    }

    private val playlistActionPort: PlaylistActionPort by lazy {
        createPlaylistActionPort(
            providerRepository = providerRepository,
            providerCatalog = providerCatalogFeatureController,
            providerDetails = providerDetailOwners,
            localPlaylist = localPlaylistFeatureController,
            scope = appScope,
            onProviderMutation = { homeFeatureController.refreshMine() },
        )
    }

    private val providerTrackActionPort: ProviderTrackActionPort by lazy {
        createProviderTrackActionPort(
            providerRepository = providerRepository,
            providerCatalog = providerCatalogFeatureController,
            providerDetails = providerDetailOwners,
            searchController = searchController,
            playbackNavigation = playbackFeatureOwner.navigation,
            playbackQueue = playbackFeatureOwner.transport,
            scope = appScope,
            refreshMineContent = homeFeatureController::refreshMine,
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
            bydInstrumentLyricsAvailable = isBydInstrumentLyricsAvailable(),
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

    private val sharedResourceActionPort: SharedResourceActionPort by lazy {
        createSharedResourceActionPort(
            providerRepository = providerRepository,
            providerCatalog = providerCatalogFeatureController,
            providerDetails = providerDetailOwners,
            searchController = searchController,
            settingsRepository = settingsRepository,
            scope = appScope,
        )
    }

    private val searchAppPort: SearchAppPort by lazy {
        DefaultSearchAppPort(
            searchController = searchController,
            providerSessions = { providerSessionRepository.state.value },
            playbackQueue = playbackFeatureOwner.transport,
            downloads = downloadActionPort,
            playlists = playlistActionPort,
            providerTrackActions = providerTrackActionPort,
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
            queuePort = playbackFeatureOwner.transport,
            settingsRepository = settingsRepository,
            scope = appScope,
        )
    }

    private val playbackSession: PlaybackSession by lazy(::wirePlaybackRuntime)

    val appViewModel: FuoAppViewModel by lazy {
        FuoAppViewModel(
            playbackSession = playbackSession,
            playbackNavigationPort = playbackFeatureOwner.navigation,
            playbackPresentationPort = playbackPresentationPort,
            playbackQueueUiPort = playbackFeatureOwner.transport,
            playbackSleepTimerPort = playbackFeatureOwner.sleepTimer,
            downloadActionPort = downloadActionPort,
            playlistActionPort = playlistActionPort,
            providerTrackActionPort = providerTrackActionPort,
            localMusicActionPort = localMusicFeatureController,
            replacementActionPort = playbackFeatureOwner.replacement,
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

    private fun wirePlaybackRuntime(): PlaybackSession {
        val session = createPlaybackRuntimeSession(
            playbackState = playbackFeatureOwner.playbackState,
            playbackEngine = playbackEngine,
            transportCoordinator = playbackFeatureOwner.transport,
            startFailureSource = playbackFeatureOwner.startFailureSource,
            scope = appScope,
        )
        settingsFeatureController.setStatusBarLyricsAvailability(isLyriconInstalled(context))
        lyriconLyricsPublisher = LyriconLyricsPublisher(
            context = context,
            playbackSession = session,
            statusBarLyricsEnabled = settingsRepository.state
                .map { state -> state.settings.statusBarLyricsEnabled }
                .distinctUntilChanged(),
            scope = appScope,
        ).also(LyriconLyricsPublisher::start)
        if (isBydInstrumentLyricsAvailable()) {
            bydInstrumentLyricsPublisher = BydInstrumentLyricsPublisher(
                context = context,
                playbackSession = session,
                enabled = settingsRepository.state
                    .map { state -> state.settings.bydInstrumentLyricsEnabled }
                    .distinctUntilChanged(),
                scope = appScope,
            ).also(BydInstrumentLyricsPublisher::start)
        }

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
        return session
    }

    override fun close() {
        FuoPlaybackService.transportControls = null
        bydInstrumentLyricsPublisher?.close()
        bydInstrumentLyricsPublisher = null
        lyriconLyricsPublisher?.close()
        lyriconLyricsPublisher = null
        appScope.cancel()
    }
}
