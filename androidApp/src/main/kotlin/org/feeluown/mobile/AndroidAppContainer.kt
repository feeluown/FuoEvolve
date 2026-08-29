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
import org.feeluown.mobile.persistence.listening.AndroidListeningHistoryDriverFactory
import org.feeluown.mobile.persistence.listening.SqlDelightListeningHistoryStore
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionStatus

internal class AndroidAppContainer(
    private val application: Application,
) : AutoCloseable {
    private val context: Context = application.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lyriconLyricsPublisher: LyriconLyricsPublisher? = null
    private var bydInstrumentLyricsPublisher: BydInstrumentLyricsPublisher? = null

    private val providerCredentialStore: AndroidProviderCredentialStore by lazy {
        AndroidProviderCredentialStore(context)
    }

    private val providerGraph: FuoProviderGraph by lazy {
        createFuoProviderGraph(
            credentials = providerCredentialStore,
            persistentCache = AndroidProviderCacheStore(context),
            isCellularConnection = ::isCellularConnection,
        )
    }

    val playbackProvider: PlaybackProviderPort by lazy {
        createAppPlaybackProviderPort(
            providerRegistry = providerGraph.registry,
            providerSearch = providerGraph.search,
            providerCatalog = providerGraph.content,
            providerPlaybackSource = providerGraph.playbackSource,
        )
    }

    val providerCredentialBackup: AndroidProviderCredentialBackup by lazy {
        AndroidProviderCredentialBackup(
            credentialStore = providerCredentialStore,
            providerRegistry = providerGraph.registry,
            providerAuth = providerGraph.auth,
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
        AndroidDownloadRepository(context, playbackProvider) { tasks -> FuoDownloadService.update(context, tasks) }
    }
    private val downloadRepository: DownloadRepository by lazy {
        AndroidOfflineAwareDownloadRepository(delegate = rawDownloadRepository, assetStore = offlineAssetStore, scope = appScope)
    }
    private val playbackEngine: AndroidNativeAudioEngine by lazy { AndroidNativeAudioEngine(context, appScope) }
    val settingsRepository: AppSettingsRepository by lazy { createAndroidAppSettingsRepository(context, appScope) }
    private val appUpdateController: AppUpdateController by lazy {
        AndroidAppUpdateController(
            context = context,
            settingsRepository = settingsRepository,
            scope = appScope,
        )
    }
    private val providerSessionRepository: ProviderSessionRepository by lazy { DefaultProviderSessionRepository(providerGraph.auth) }
    private val navigator by lazy { AppNavigator() }
    private val trackNavigationPort: TrackNavigationPort by lazy { createTrackNavigationPort(navigator) }
    private val homeRefreshPort: HomeRefreshPort by lazy { createHomeRefreshPort { homeFeatureController } }
    private val oauthDeviceCodeAssistant: OAuthDeviceCodeAssistant by lazy { AndroidOAuthDeviceCodeAssistant(context) }

    private val playbackQueueStore: AndroidPlaybackQueueStore by lazy { AndroidPlaybackQueueStore(context) }
    private val listeningHistorySink: ListeningHistorySink by lazy {
        SqlDelightListeningHistoryStore(AndroidListeningHistoryDriverFactory(context))
    }
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
            providerRepository = providerGraph.search,
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
            providerRegistry = providerGraph.registry,
            providerCatalog = providerGraph.content,
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
            providerSearch = providerGraph.search,
            providerPlaybackSource = providerGraph.playbackSource,
            navigator = navigator,
            settingsRepository = settingsRepository,
            providers = { providerCatalogFeatureController.uiState.value.providers },
            isLocalMusicSectionActive = {
                val settings = settingsRepository.state.value.settings
                settings.homeSection == HomeSection.Mine && settings.mineSection == MineSection.LocalMusic
            },
            scope = appScope,
            onTrackUpdated = { trackId, track -> playbackFeatureOwner.updateTrackCopies(trackId, track) },
        )
    }

    private val downloadActionPort: DownloadActionPort by lazy {
        createDownloadActionPort(
            playbackProvider = playbackProvider,
            downloadRepository = downloadRepository,
            localRepository = localRepository,
            localMusicController = localMusicFeatureController,
            settingsRepository = settingsRepository,
            scope = appScope,
            isLocalMusicSectionActive = {
                val settings = settingsRepository.state.value.settings
                settings.homeSection == HomeSection.Mine && settings.mineSection == MineSection.LocalMusic
            },
        )
    }

    private val playbackFeatureOwner: PlaybackFeatureOwner by lazy {
        createPlaybackFeatureOwner(
            playbackProvider = playbackProvider,
            playbackEngine = playbackEngine,
            playbackQueueStore = playbackQueueStore,
            settingsRepository = settingsRepository,
            downloadActions = downloadActionPort,
            scope = appScope,
            openTrackDetail = trackNavigationPort::open,
            listeningHistorySink = listeningHistorySink,
        )
    }

    private val providerDetailOwners: ProviderDetailOwners by lazy {
        createProviderDetailOwners(
            providerRepository = providerGraph.content,
            playbackQueue = playbackFeatureOwner.transport,
            settingsRepository = settingsRepository,
            providerCatalog = providerCatalogFeatureController,
            navigator = navigator,
            scope = appScope,
            onProviderMutation = { homeRefreshPort.refreshMine() },
        )
    }

    private val homeFeatureController: HomeFeatureController by lazy {
        createHomeFeatureController(
            providerRepository = providerGraph.content,
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
            providerLibrary = providerGraph.content,
            providerCatalog = providerCatalogFeatureController,
            providerDetails = providerDetailOwners,
            localPlaylist = localPlaylistFeatureController,
            scope = appScope,
            onProviderMutation = { homeRefreshPort.refreshMine() },
        )
    }

    private val providerTrackActionPort: ProviderTrackActionPort by lazy {
        createProviderTrackActionPort(
            providerCatalogRepository = providerGraph.content,
            providerLibrary = providerGraph.content,
            providerCatalog = providerCatalogFeatureController,
            providerDetails = providerDetailOwners,
            searchController = searchController,
            playbackNavigation = playbackFeatureOwner.navigation,
            playbackQueue = playbackFeatureOwner.transport,
            scope = appScope,
            refreshMineContent = homeRefreshPort::refreshMine,
        )
    }

    private val providerAuthFeatureController: ProviderAuthFeatureController by lazy {
        createProviderAuthFeatureController(
            providerAuth = providerGraph.auth,
            sessionRepository = providerSessionRepository,
            oauthDeviceCodeAssistant = oauthDeviceCodeAssistant,
            scope = appScope,
            providerName = { providerId ->
                providerCatalogFeatureController.uiState.value.availableProviders
                    .firstOrNull { it.providerId == providerId }?.providerName ?: providerId
            },
            onSessionChanged = homeRefreshPort::refreshAll,
        )
    }

    private val settingsFeatureController: SettingsFeatureController by lazy {
        createSettingsFeatureController(
            settingsRepository = settingsRepository,
            providerAudioQuality = providerGraph.audioQuality,
            downloadRepository = downloadRepository,
            resourceCacheRepository = resourceCacheRepository,
            localMusicController = localMusicFeatureController,
            debugLogViewerAvailable = debugLogFeatureController.isAvailable,
            navigator = navigator,
            scope = appScope,
            bydInstrumentLyricsAvailable = isBydInstrumentLyricsAvailable(),
            appUpdateController = appUpdateController,
        )
    }

    private val onboardingFeatureController: OnboardingFeatureController by lazy {
        createOnboardingFeatureController(
            providerRegistry = providerGraph.registry,
            settingsRepository = settingsRepository,
            providerCatalog = providerCatalogFeatureController,
            scope = appScope,
        )
    }

    private val sharedResourceActionPort: SharedResourceActionPort by lazy {
        createSharedResourceActionPort(
            providerRegistry = providerGraph.registry,
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
            loadTrackDetail = providerGraph.content::trackDetail,
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

    val appUiGraph: AppUiGraph by lazy {
        createAppUiGraph(
            playbackSession = playbackSession,
            playbackNavigationPort = playbackFeatureOwner.navigation,
            playbackPresentationPort = playbackPresentationPort,
            playbackQueueUiPort = playbackFeatureOwner.transport,
            playbackSleepTimerPort = playbackFeatureOwner.sleepTimer,
            downloadActionPort = downloadActionPort,
            playlistActionPort = playlistActionPort,
            providerTrackActionPort = providerTrackActionPort,
            localMusicActionPort = localMusicFeatureController,
            playbackLyricsPort = playbackFeatureOwner.lyrics,
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
            searchAppPort = searchAppPort,
            recognitionController = recognitionController,
            recognitionAppPort = recognitionAppPort,
        )
    }

    private val appBackCoordinator: AppBackCoordinator by lazy {
        createAppBackCoordinator(
            navigator = navigator,
            playbackNavigationPort = playbackFeatureOwner.navigation,
            playlistActionPort = playlistActionPort,
            providerTrackActionPort = providerTrackActionPort,
            localMusicFeatureController = localMusicFeatureController,
            providerDetailOwners = providerDetailOwners,
            searchAppPort = searchAppPort,
            recognitionController = recognitionController,
            settingsFeatureController = settingsFeatureController,
            localPlaylistFeatureController = localPlaylistFeatureController,
        )
    }

    val appViewModel: FuoAppViewModel by lazy {
        FuoAppViewModel(
            settingsRepository = settingsRepository,
            navigator = navigator,
            recognitionController = recognitionController,
            backCoordinator = appBackCoordinator,
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
            session.state.map { state ->
                Triple(state.currentTrack?.id, state.lyrics, state.lyricsAlignmentOffsetMs)
            }.distinctUntilChanged().collect { (trackId, lyrics, alignmentOffsetMs) ->
                if (trackId != null) {
                    val platformLyrics = toTimedLineLrc(lyrics, alignmentOffsetMs) ?: lyrics
                    playbackEngine.publishLockScreenLyrics(trackId, platformLyrics)
                }
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