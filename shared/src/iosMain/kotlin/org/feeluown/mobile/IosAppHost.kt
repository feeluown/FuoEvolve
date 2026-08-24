package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.UIKit.UIViewController

internal fun handleIosLocalPlaylistImportResult(
    fileName: String?,
    content: String?,
    onImport: (String, String) -> Unit,
    onReadFailure: () -> Unit,
) {
    if (fileName == null && content == null) return
    val validFileName = fileName?.takeIf { it.isNotBlank() }
    val validContent = content?.takeIf { it.isNotBlank() }
    if (validFileName != null && validContent != null) onImport(validFileName, validContent) else onReadFailure()
}

internal fun handleIosJsonImportResult(
    content: String?,
    onImport: (String) -> Unit,
    onReadFailure: () -> Unit,
) {
    val validContent = content?.takeIf { it.isNotBlank() }
    if (validContent != null) onImport(validContent) else onReadFailure()
}

fun MainViewController(
    audioOutput: IosAudioOutput,
    videoOutput: IosVideoOutput,
    mediaLibraryOutput: IosMediaLibraryOutput,
    downloadOutput: IosDownloadOutput,
    webLoginOutput: IosWebLoginOutput,
    shareOutput: IosShareOutput,
    localPlaylistFileOutput: IosLocalPlaylistFileOutput,
    networkStatusOutput: IosNetworkStatusOutput,
    audioRecognitionOutput: IosAudioRecognitionOutput,
    oauthDeviceCodeOutput: IosOAuthDeviceCodeOutput,
): UIViewController = ComposeUIViewController {
    IosApp(
        audioOutput, videoOutput, mediaLibraryOutput, downloadOutput, webLoginOutput,
        shareOutput, localPlaylistFileOutput, networkStatusOutput, audioRecognitionOutput, oauthDeviceCodeOutput,
    )
}

@Composable
private fun IosApp(
    audioOutput: IosAudioOutput,
    videoOutput: IosVideoOutput,
    mediaLibraryOutput: IosMediaLibraryOutput,
    downloadOutput: IosDownloadOutput,
    webLoginOutput: IosWebLoginOutput,
    shareOutput: IosShareOutput,
    localPlaylistFileOutput: IosLocalPlaylistFileOutput,
    networkStatusOutput: IosNetworkStatusOutput,
    audioRecognitionOutput: IosAudioRecognitionOutput,
    oauthDeviceCodeOutput: IosOAuthDeviceCodeOutput,
) {
    IosVideoOutputHolder.output = videoOutput
    val container = remember {
        IosAppContainer(
            audioOutput, mediaLibraryOutput, downloadOutput, webLoginOutput,
            networkStatusOutput, audioRecognitionOutput, oauthDeviceCodeOutput,
        )
    }
    AppRoot(
        appViewModel = container.appViewModel,
        hasAudioPermission = container.hasAudioPermission,
        onRequestAudioPermission = container::requestAudioPermission,
        hasMicrophonePermission = container.hasMicrophonePermission,
        onRequestMicrophonePermission = container::requestMicrophonePermission,
        onOpenProviderWebLogin = container::openProviderWebLogin,
        onLogoutProvider = container::logoutProvider,
        onImportYtmusicHeaderFile = {
            localPlaylistFileOutput.importFile { _, content ->
                handleIosJsonImportResult(
                    content = content,
                    onImport = container.appViewModel.providerAuthFeatureController::loginYtmusicWithHeaderFile,
                    onReadFailure = { container.appViewModel.showFeedback("无法读取 ytmusic_header.json") },
                )
            }
        },
        onImportYtmusicOAuthFile = {
            localPlaylistFileOutput.importFile { _, content ->
                handleIosJsonImportResult(
                    content = content,
                    onImport = container.appViewModel.providerAuthFeatureController::importYtmusicOAuthRelatedJson,
                    onReadFailure = { container.appViewModel.showFeedback("无法读取 OAuth JSON") },
                )
            }
        },
        onStartYtmusicOAuth = container.appViewModel.providerAuthFeatureController::startYtmusicTvOAuthLogin,
        onImportLocalPlaylistFile = {
            localPlaylistFileOutput.importFile { fileName, content ->
                handleIosLocalPlaylistImportResult(
                    fileName = fileName,
                    content = content,
                    onImport = container.appViewModel.localPlaylistFeatureController::prepareImport,
                    onReadFailure = { container.appViewModel.showFeedback("无法读取本地歌单文件") },
                )
            }
        },
        onExportLocalPlaylistFile = localPlaylistFileOutput::exportFile,
        onShareLocalPlaylistFile = localPlaylistFileOutput::shareFile,
        onShareText = shareOutput::share,
    )
}

private class IosAppContainer(
    audioOutput: IosAudioOutput,
    mediaLibraryOutput: IosMediaLibraryOutput,
    downloadOutput: IosDownloadOutput,
    private val webLoginOutput: IosWebLoginOutput,
    networkStatusOutput: IosNetworkStatusOutput,
    private val audioRecognitionOutput: IosAudioRecognitionOutput,
    oauthDeviceCodeOutput: IosOAuthDeviceCodeOutput,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val providerRepository = createFuoProviderRepository(
        credentials = IosProviderCredentialStore(),
        persistentCache = IosProviderCacheStore(),
        isCellularConnection = networkStatusOutput::isCellularConnection,
    )
    private val localRepository = IosLocalMusicRepository(mediaLibraryOutput)
    private val localPlaylistRepository = IosLocalPlaylistRepository()
    private val downloadRepository = IosDownloadRepository(providerRepository, downloadOutput)
    private val settingsRepository = createIosAppSettingsRepository(scope)
    private val playbackEngine = IosNativeAudioEngine(scope, audioOutput, settingsRepository)
    private val providerSessionRepository = DefaultProviderSessionRepository(providerRepository)
    private val navigator = AppNavigator()
    private val oauthDeviceCodeAssistant: OAuthDeviceCodeAssistant = IosOAuthDeviceCodeAssistant(oauthDeviceCodeOutput)
    private val playbackQueueStore = IosPlaybackQueueStore()
    private val resourceCacheRepository = IosResourceCacheRepository()
    private val debugLogFeatureController by lazy { createDebugLogFeatureController(NoOpDebugLogRepository, scope) }
    private val audioRecognitionRepository = IosAudioRecognitionRepository(audioRecognitionOutput)

    private val searchController: SearchFeatureController by lazy {
        val initialSettings = settingsRepository.state.value.settings
        createSearchFeatureController(
            providerRepository = providerRepository,
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
                    settingsRepository.update { it.copy(searchScope = searchScope, selectedSearchProviderId = selectedProviderId) }
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
        createProviderCatalogFeatureController(providerRepository, providerSessionRepository, settingsRepository, scope)
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
            providerRepository = providerRepository,
            navigator = navigator,
            settingsRepository = settingsRepository,
            providers = { providerCatalogFeatureController.uiState.value.providers },
            isLocalMusicSectionActive = {
                val state = homeFeatureController.uiState.value
                state.homeSection == HomeSection.Mine && state.mineSection == MineSection.LocalMusic
            },
            scope = scope,
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
            scope = scope,
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
            scope = scope,
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
            scope = scope,
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
            scope = scope,
        )
    }

    private val playlistActionPort: PlaylistActionPort by lazy {
        createPlaylistActionPort(
            providerRepository = providerRepository,
            providerCatalog = providerCatalogFeatureController,
            providerDetails = providerDetailOwners,
            localPlaylist = localPlaylistFeatureController,
            scope = scope,
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
            scope = scope,
            refreshMineContent = homeFeatureController::refreshMine,
        )
    }

    private val providerAuthFeatureController by lazy {
        createProviderAuthFeatureController(
            providerRepository = providerRepository,
            sessionRepository = providerSessionRepository,
            oauthDeviceCodeAssistant = oauthDeviceCodeAssistant,
            scope = scope,
            providerName = { providerId ->
                providerCatalogFeatureController.uiState.value.availableProviders.firstOrNull { it.providerId == providerId }?.providerName
                    ?: providerId
            },
            onSessionChanged = {
                homeFeatureController.refreshHome(HomeSection.Recommend)
                homeFeatureController.refreshHome(HomeSection.Music)
                homeFeatureController.refreshMine()
            },
        )
    }

    private val settingsFeatureController by lazy {
        createSettingsFeatureController(
            settingsRepository = settingsRepository,
            providerRepository = providerRepository,
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
            providerRepository = providerRepository,
            settingsRepository = settingsRepository,
            providerCatalog = providerCatalogFeatureController,
            scope = scope,
        )
    }

    private val sharedResourceActionPort: SharedResourceActionPort by lazy {
        createSharedResourceActionPort(
            providerRepository = providerRepository,
            providerCatalog = providerCatalogFeatureController,
            providerDetails = providerDetailOwners,
            searchController = searchController,
            settingsRepository = settingsRepository,
            scope = scope,
        )
    }

    private val playbackSession by lazy {
        createIosPlaybackRuntimeSession(
            playbackState = playbackFeatureOwner.playbackState,
            playbackEngine = playbackEngine,
            transportCoordinator = playbackFeatureOwner.transport,
            startFailureSource = playbackFeatureOwner.startFailureSource,
            scope = scope,
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
            isProviderEnabled = { id -> providerSessionRepository.state.value.providers.any { it.providerId == id } },
            loadTrackDetail = providerRepository::trackDetail,
            navigator = navigator,
        )
    }

    private val playbackPresentationPort by lazy {
        DefaultPlaybackPresentationPort(playbackEngine, playbackFeatureOwner.transport, settingsRepository, scope)
    }

    val appViewModel = FuoAppViewModel(
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
        recognitionController = recognitionController,
        searchAppPort = searchAppPort,
        recognitionAppPort = recognitionAppPort,
        settingsRepository = settingsRepository,
        providerSessionRepository = providerSessionRepository,
        navigator = navigator,
    )

    var hasMicrophonePermission by mutableStateOf(audioRecognitionOutput.hasPermission())
        private set

    val hasAudioPermission: Boolean get() = localRepository.hasPermission

    fun requestAudioPermission() {
        localRepository.requestPermission {
            localMusicFeatureController.onPermissionChange(true)
            localMusicFeatureController.refresh()
        }
    }

    fun requestMicrophonePermission() {
        audioRecognitionOutput.requestPermission { granted ->
            hasMicrophonePermission = granted
            appViewModel.onMicrophonePermissionChange(granted)
        }
    }

    fun openProviderWebLogin(provider: ProviderInfo) {
        val loginConfig = provider.loginConfig ?: return
        val groupsJson = loginConfig.cookieKeyGroups.joinToString(prefix = "[", postfix = "]") { group ->
            group.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
        }
        webLoginOutput.open(provider.providerId, provider.providerName, loginConfig.loginUrl, groupsJson) { cookiesJson ->
            if (!cookiesJson.isNullOrBlank()) providerAuthFeatureController.loginWithCookies(provider.providerId, cookiesJson)
        }
    }

    fun logoutProvider(provider: ProviderInfo) {
        webLoginOutput.clear()
        providerAuthFeatureController.logout(provider.providerId)
    }
}
