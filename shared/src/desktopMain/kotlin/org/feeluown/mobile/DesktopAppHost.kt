package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.feeluown.mobile.provider.core.InMemoryProviderCredentialStore

/** Desktop composition root. It hosts the same AppRoot/UI graph used by Android and iOS. */
@Composable
fun DesktopAppHost() {
    val container = remember { DesktopAppContainer() }
    DisposableEffect(container) {
        onDispose(container::close)
    }
    AppRoot(
        appViewModel = container.appViewModel,
        uiGraph = container.appUiGraph,
        platform = AppPlatformBindings(
            hasAudioPermission = true,
            onRequestAudioPermission = {},
            hasMicrophonePermission = false,
            onRequestMicrophonePermission = {
                container.appViewModel.showFeedback("桌面端听歌识曲将在后续开发阶段接入")
            },
            onOpenProviderWebLogin = container::openProviderWebLogin,
            onLogoutProvider = container::logoutProvider,
            appVersionInfo = "Desktop development build",
        ),
    )
}

private class DesktopAppContainer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val providerGraph = createFuoProviderGraph(
        credentials = InMemoryProviderCredentialStore(),
        persistentCache = null,
        isCellularConnection = { false },
    )
    private val playbackProvider = createAppPlaybackProviderPort(
        providerRegistry = providerGraph.registry,
        providerSearch = providerGraph.search,
        providerCatalog = providerGraph.content,
        providerPlaybackSource = providerGraph.playbackSource,
    )
    private val localRepository: LocalMusicRepository = DesktopUnsupportedLocalMusicRepository
    private val localPlaylistRepository: LocalPlaylistRepository = NoOpLocalPlaylistRepository
    private val desktopDownloadRepository = DesktopDownloadRepository(
        resolvePayload = { track -> playbackProvider.resolve(track) },
    )
    private val downloadRepository: DownloadRepository = desktopDownloadRepository
    private val settingsRepository: AppSettingsRepository = PersistentAppSettingsRepository(
        store = createDesktopSettingsSnapshotStore(),
        legacyLoader = null,
        scope = scope,
    )
    private val playbackEngine = DesktopUnsupportedPlaybackEngine()
    private val providerSessionRepository = DefaultProviderSessionRepository(providerGraph.auth)
    private val navigator = AppNavigator()
    private val trackNavigationPort: TrackNavigationPort = createTrackNavigationPort(navigator)
    private val homeRefreshPort: HomeRefreshPort by lazy { createHomeRefreshPort { homeFeatureController } }
    private val playbackQueueStore: PlaybackQueueStore = NoOpPlaybackQueueStore
    private val listeningHistorySink: ListeningHistorySink = NoOpListeningHistorySink
    private val resourceCacheRepository: ResourceCacheRepository = NoOpResourceCacheRepository
    private val debugLogFeatureController by lazy {
        createDebugLogFeatureController(NoOpDebugLogRepository, scope)
    }
    private val audioRecognitionRepository: AudioRecognitionRepository = UnsupportedAudioRecognitionRepository

    private val searchController: SearchFeatureController by lazy {
        val initialSettings = settingsRepository.state.value.settings
        createSearchFeatureController(
            providerRepository = providerGraph.search,
            localRepository = localRepository,
            scope = scope,
            providerIdsForSearch = {
                val active = providerSessionRepository.state.value.authStates.keys
                settingsRepository.state.value.settings.searchProviderIdsForFeature().filter(active::contains)
            },
            providerExists = { it in providerSessionRepository.state.value.authStates },
            openSearch = { navigator.navigate(AppRoute.Search) },
            onPreferencesChanged = { searchScope, selectedProviderId ->
                scope.launch {
                    settingsRepository.update {
                        it.copy(
                            searchScope = searchScope,
                            selectedSearchProviderId = selectedProviderId,
                        )
                    }
                }
            },
            initialState = SearchUiState(
                searchScope = initialSettings.searchScope,
                selectedSearchProviderId = initialSettings.selectedSearchProviderId,
            ),
        )
    }

    private val recognitionController by lazy {
        createRecognitionFeatureController(
            repository = audioRecognitionRepository,
            scope = scope,
            isPlaybackActive = { playbackEngine.state.value.status == PlayerStatus.Playing },
            pausePlayback = playbackEngine::pause,
        )
    }

    private val providerCatalogFeatureController by lazy {
        createProviderCatalogFeatureController(
            providerRegistry = providerGraph.registry,
            providerCatalog = providerGraph.content,
            sessionRepository = providerSessionRepository,
            settingsRepository = settingsRepository,
            scope = scope,
        )
    }

    private val localPlaylistFeatureController: LocalPlaylistFeatureOwner by lazy {
        createLocalPlaylistFeatureController(
            repository = localPlaylistRepository,
            navigator = navigator,
            scope = scope,
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
            scope = scope,
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
            scope = scope,
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
            scope = scope,
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
            scope = scope,
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
            scope = scope,
        )
    }

    private val playlistActionPort: PlaylistActionPort by lazy {
        createPlaylistActionPort(
            providerLibrary = providerGraph.content,
            providerCatalog = providerCatalogFeatureController,
            providerDetails = providerDetailOwners,
            localPlaylist = localPlaylistFeatureController,
            scope = scope,
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
            scope = scope,
            refreshMineContent = homeRefreshPort::refreshMine,
        )
    }

    private val providerAuthFeatureController by lazy {
        createProviderAuthFeatureController(
            providerAuth = providerGraph.auth,
            sessionRepository = providerSessionRepository,
            oauthDeviceCodeAssistant = NoOpOAuthDeviceCodeAssistant,
            scope = scope,
            providerName = { providerId ->
                providerCatalogFeatureController.uiState.value.availableProviders
                    .firstOrNull { it.providerId == providerId }
                    ?.providerName
                    ?: providerId
            },
            onSessionChanged = homeRefreshPort::refreshAll,
        )
    }

    private val settingsFeatureController by lazy {
        createSettingsFeatureController(
            settingsRepository = settingsRepository,
            providerAudioQuality = providerGraph.audioQuality,
            downloadRepository = downloadRepository,
            resourceCacheRepository = resourceCacheRepository,
            localMusicController = localMusicFeatureController,
            debugLogViewerAvailable = debugLogFeatureController.isAvailable,
            navigator = navigator,
            scope = scope,
        )
    }

    private val onboardingFeatureController: OnboardingFeatureController by lazy {
        createOnboardingFeatureController(
            providerRegistry = providerGraph.registry,
            settingsRepository = settingsRepository,
            providerCatalog = providerCatalogFeatureController,
            scope = scope,
        )
    }

    private val sharedResourceActionPort: SharedResourceActionPort by lazy {
        createSharedResourceActionPort(
            providerRegistry = providerGraph.registry,
            providerCatalog = providerCatalogFeatureController,
            providerDetails = providerDetailOwners,
            searchController = searchController,
            settingsRepository = settingsRepository,
            scope = scope,
        )
    }

    private val playbackSession by lazy {
        createSharedPlaybackRuntimeSession(
            playbackState = playbackFeatureOwner.playbackState,
            playbackEngine = playbackEngine,
            transportCoordinator = playbackFeatureOwner.transport,
            startFailureSource = playbackFeatureOwner.startFailureSource,
            scope = scope,
            resumePlayback = playbackFeatureOwner.transport::startCurrent,
        )
    }

    private val searchAppPort by lazy {
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

    private val recognitionAppPort by lazy {
        DefaultRecognitionAppPort(
            isProviderEnabled = { id ->
                providerSessionRepository.state.value.providers.any { it.providerId == id }
            },
            loadTrackDetail = providerGraph.content::trackDetail,
            navigator = navigator,
        )
    }

    private val playbackPresentationPort by lazy {
        DefaultPlaybackPresentationPort(
            playbackEngine,
            playbackFeatureOwner.transport,
            settingsRepository,
            scope,
        )
    }

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

    val appViewModel = FuoAppViewModel(
        settingsRepository = settingsRepository,
        navigator = navigator,
        recognitionController = recognitionController,
        backCoordinator = appBackCoordinator,
    )

    fun openProviderWebLogin(provider: ProviderInfo) {
        appViewModel.showFeedback("${provider.providerName} 的桌面网页登录将在后续开发阶段接入")
    }

    fun logoutProvider(provider: ProviderInfo) {
        providerAuthFeatureController.logout(provider.providerId)
    }

    fun close() {
        scope.cancel()
        desktopDownloadRepository.close()
        playbackEngine.close()
    }
}
