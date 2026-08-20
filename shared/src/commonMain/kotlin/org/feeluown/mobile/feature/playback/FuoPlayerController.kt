package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.feeluown.mobile.provider.core.network.currentTimeMillis

private const val DYNAMIC_QUEUE_PREFETCH_REMAINING = 2
private const val LIST_PREFETCH_REMAINING = 8
private const val PLAYBACK_PLAN_LOOKAHEAD = 8
private const val PLAYLIST_BACKGROUND_PAGE_INTERVAL_MS = 3_000L
private const val MAX_PLAYLIST_PLAYBACK_STATS = 500
private const val MAX_PLAYLIST_STATS_KEY_LENGTH = 2_048
private const val MAX_PLAYLIST_PLAY_COUNT = 1_000_000_000L
private const val PLAYLIST_PLAYBACK_STATS_VERSION = 1
private const val PLAYLIST_STATS_KEY_SEPARATOR = "::"
private val DEFAULT_DEBUG_LOG_LEVEL_FILTERS = setOf(DebugLogLevel.Info, DebugLogLevel.Warning, DebugLogLevel.Error)

private data class PendingManualReplacementSwitch(
    val requestSerial: Long,
    val previousTrack: MusicTrack?,
    val originalTrackId: String,
    val selection: SmartReplacementSelection,
)

class FuoPlayerController(
    private val providerRepository: ProviderMusicRepository,
    private val localRepository: LocalMusicRepository,
    private val downloadRepository: DownloadRepository,
    private val localPlaylistRepository: LocalPlaylistRepository = NoOpLocalPlaylistRepository,
    private val playbackEngine: PlaybackEngine,
    private val settingsRepository: AppSettingsRepository = InMemoryAppSettingsRepository(),
    private val providerSessionRepository: ProviderSessionRepository =
        DefaultProviderSessionRepository(providerRepository),
    private val navigator: AppNavigator = AppNavigator(),
    private val playbackQueueStore: PlaybackQueueStore = NoOpPlaybackQueueStore,
    private val resourceCacheRepository: ResourceCacheRepository = NoOpResourceCacheRepository,
    private val debugLogRepository: DebugLogRepository = NoOpDebugLogRepository,
    private val audioRecognitionRepository: AudioRecognitionRepository = UnsupportedAudioRecognitionRepository,
    private val oauthDeviceCodeAssistant: OAuthDeviceCodeAssistant = NoOpOAuthDeviceCodeAssistant,
    private val scope: CoroutineScope,
    searchFeatureController: SearchFeatureController? = null,
    recognitionFeatureController: RecognitionFeatureController? = null,
    private val nowMillis: () -> Long = ::currentTimeMillis,
) {
    private val providerState = ProviderControllerState()
    private val providerAuthState = ProviderAuthControllerState()
    private val localMusicState = LocalMusicControllerState()
    private val downloadState = DownloadControllerState()
    private val playlistState = PlaylistControllerState()
    private val settingsUiState = SettingsControllerState()
    private val playbackQueueController = PlaybackQueueController()
    private val playbackNavigationController = DefaultPlaybackNavigationPort()
    private val playbackProviderRepository: ProviderPlaybackRepository = ProviderPlaybackRepositoryView(providerRepository)

    var isSettingsLoaded by mutableStateOf(false)
        private set
    var onboardingCompleted by mutableStateOf(false)
        private set
    var availableProviders by providerState::availableProviders
        private set
    var providers by providerState::providers
        private set
    var providerFeatures by providerState::features
        private set
    var providerCapabilities by providerState::capabilities
        private set
    var providerAuthStates by providerAuthState::authStates
        private set
    var providerAuthOperations by providerAuthState::authOperations
        private set
    var providerAuthErrors by providerAuthState::authErrors
        private set
    var providerCookieInputs by providerAuthState::cookieInputs
        private set
    var providerHeaderInputs by providerAuthState::headerInputs
    var playlistPlaybackStats by mutableStateOf<Map<String, PlaylistPlaybackStat>>(emptyMap())
        private set
    var providerOAuthInputs by providerAuthState::oauthInputs
    var ytmusicOAuthFlow by providerAuthState::ytmusicOAuthFlow
        private set
    var enabledProviderIds by mutableStateOf(DEFAULT_ENABLED_PROVIDER_IDS)
        private set
    var providerOrderIds by mutableStateOf(DEFAULT_PROVIDER_ORDER_IDS)
        private set
    var searchProviderIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var recommendProviderIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var exploreProviderIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var mineProviderIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var recommendSections by providerState::recommendSections
        private set
    var musicSections by providerState::musicSections
        private set
    var mineSections by providerState::mineSections
        private set
    var minePlaylistSections by providerState::minePlaylistSections
        private set
    var mineFavoritePlaylistSections by providerState::mineFavoritePlaylistSections
        private set
    val lastProviderFailure: ProviderFailure?
        get() = providerState.lastFailure
    var selectedPlaylist by mutableStateOf<ProviderPlaylist?>(null)
        private set
    var selectedPlaylistCategory by mutableStateOf<ProviderFeatureCategory?>(null)
        private set
    var selectedPlaylistTracks by mutableStateOf<List<MusicTrack>>(emptyList())
        private set
    var selectedPlaylistTracksHasMore by mutableStateOf(false)
        private set
    var selectedPlaylistError by mutableStateOf<String?>(null)
        private set
    var localPlaylists by playlistState::localPlaylists
        private set
    var selectedLocalPlaylist by playlistState::selectedLocalPlaylist
        private set
    var selectedLocalPlaylistTracks by playlistState::selectedLocalPlaylistTracks
        private set
    var selectedLocalPlaylistError by playlistState::selectedLocalPlaylistError
        private set
    var selectedFeature by mutableStateOf<ProviderFeature?>(null)
        private set
    var selectedFeatureContent by mutableStateOf<ProviderContentSection?>(null)
        private set
    var selectedFeatureTracks by mutableStateOf<List<MusicTrack>>(emptyList())
        private set
    var selectedFeatureTracksHasMore by mutableStateOf(false)
        private set
    var selectedFeatureError by mutableStateOf<String?>(null)
        private set
    var selectedMediaItem by mutableStateOf<ProviderMediaItem?>(null)
        private set
    var selectedMediaItemTracks by mutableStateOf<List<MusicTrack>>(emptyList())
        private set
    var selectedMediaItemTracksHasMore by mutableStateOf(false)
        private set
    var selectedMediaItemAlbums by mutableStateOf<List<ProviderMediaItem>>(emptyList())
        private set
    var selectedMediaItemAlbumsHasMore by mutableStateOf(false)
        private set
    var selectedMediaItemError by mutableStateOf<String?>(null)
        private set
    var selectedTrack by mutableStateOf<MusicTrack?>(null)
        private set
    var selectedTrackError by mutableStateOf<String?>(null)
        private set
    var selectedTrackSimilar by mutableStateOf<List<MusicTrack>>(emptyList())
        private set
    var selectedTrackComments by mutableStateOf<List<ProviderComment>>(emptyList())
        private set
    var selectedTrackVideo by mutableStateOf<ProviderVideo?>(null)
        private set
    var selectedTrackRelatedError by mutableStateOf<String?>(null)
        private set
    var selectedVideo by mutableStateOf<ProviderVideo?>(null)
        private set
    var selectedVideoPayload by mutableStateOf<VideoPlaybackPayload?>(null)
        private set
    var selectedVideoError by mutableStateOf<String?>(null)
        private set
    var playlistTargetTrack by playlistState::playlistTargetTrack
        private set
    var playlistTargetType by playlistState::playlistTargetType
        private set
    var playlistTargetPickerShowSwitcher by playlistState::playlistTargetPickerShowSwitcher
        private set
    var playlistOperationTargets by playlistState::playlistOperationTargets
        private set
    var playlistOperationError by playlistState::playlistOperationError
        private set
    var playlistOperationFeedback by playlistState::playlistOperationFeedback
        private set
    var playbackFeedback by mutableStateOf<String?>(null)
        private set
    var localPlaylistOperationError by playlistState::localPlaylistOperationError
        private set
    var localPlaylistImportPreview by playlistState::localPlaylistImportPreview
        private set
    val artistTargetTrack: MusicTrack?
        get() = providerTrackActionController.artistTargetTrack
    val artistTargets: List<TrackArtistTarget>
        get() = providerTrackActionController.artistTargets
    var localTracks by localMusicState::tracks
        private set
    val query: String
        get() = searchController.uiState.value.query
    val searchScope: SearchScope
        get() = searchController.uiState.value.searchScope
    val selectedSearchProviderId: String?
        get() = searchController.uiState.value.selectedSearchProviderId
    var selectedSettingsProviderId by mutableStateOf<String?>(null)
        private set
    var settingsLoginProviderId by mutableStateOf<String?>(null)
        private set
    var providerLoginMode by mutableStateOf(ProviderLoginMode.WebView)
        private set
    val searchResults: List<MusicTrack>
        get() = searchController.uiState.value.searchResults
    val providerSearchResults: ProviderSearchResults
        get() = searchController.uiState.value.providerSearchResults
    val providerSearchTab: ProviderSearchTab
        get() = searchController.uiState.value.providerSearchTab
    val searchUiState
        get() = searchController.uiState
    var homeSection by mutableStateOf(HomeSection.Recommend)
        private set
    var mineSection by mutableStateOf(MineSection.Playlists)
        private set
    var playlistFilter by mutableStateOf(PlaylistFilter.All)
        private set
    var localMusicViewMode by localMusicState::viewMode
        private set
    var localMusicDirectories by localMusicState::directories
        private set
    var selectedLocalMusicDirectoryId by localMusicState::selectedDirectoryId
        private set
    var selectedLocalMusicCollection by localMusicState::selectedCollection
        private set
    var excludedLocalMusicDirectoryIds by localMusicState::excludedDirectoryIds
        private set
    var localMusicMinDurationSeconds by localMusicState::minDurationSeconds
        private set
    val isSearchOpen: Boolean
        get() = navigator.contains(AppRoute.Search)
    val isRecognitionOpen: Boolean
        get() = navigator.contains(AppRoute.AudioRecognition)
    val recognitionUiState: RecognitionUiState
        get() = recognitionController.uiState.value
    val isFullPlayerOpen: Boolean
        get() = playbackNavigationController.isFullPlayerOpen
    var isVideoFullscreen by mutableStateOf(false)
        private set
    val isSettingsOpen: Boolean
        get() = navigator.contains(AppRoute.Settings)
    val isDebugLogOpen: Boolean
        get() = navigator.contains(AppRoute.DebugLogs)
    val isDownloadManagerOpen: Boolean
        get() = navigator.contains(AppRoute.DownloadManager)
    val isQueueOpen: Boolean
        get() = playbackNavigationController.isQueueOpen
    var isLoading by mutableStateOf(false)
        private set
    var message by mutableStateOf("正在初始化 FeelUOwn")
        private set

    fun showMessage(text: String) {
        message = text
    }
    var downloadStates by downloadState::states
        private set
    var downloadTasks by downloadState::tasks
        private set
    var downloadQueueFeedback by downloadState::queueFeedback
        private set
    var playbackState by mutableStateOf(PlaybackState())
        private set
    val sleepTimerState: SleepTimerState
        get() = sleepTimerController.sleepTimerState
    var trackChangeDirection by mutableStateOf(TrackChangeDirection.Next)
        private set
    var cacheUsage by settingsUiState::cacheUsage
        private set
    var audioCacheLimitMb by settingsUiState::audioCacheLimitMb
        private set
    var imageCacheLimitMb by settingsUiState::imageCacheLimitMb
        private set
    var downloadParallelism by downloadState::parallelism
        private set
    var wifiAudioQualityPolicy by settingsUiState::wifiAudioQualityPolicy
        private set
    var cellularAudioQualityPolicy by settingsUiState::cellularAudioQualityPolicy
        private set
    var unavailablePlaybackPolicy by settingsUiState::unavailablePlaybackPolicy
        private set
    var smartReplacementProviderIds by settingsUiState::smartReplacementProviderIds
        private set
    var smartReplacementMinScore by settingsUiState::smartReplacementMinScore
        private set
    val replacementCandidateState: ReplacementCandidateState
        get() = playbackReplacementController.replacementCandidateState
    var lyricFontSize by settingsUiState::lyricFontSize
        private set
    var statusBarLyricsEnabled by mutableStateOf(false)
        private set
    var isStatusBarLyricsAvailable by mutableStateOf(false)
        private set
    var themeMode by settingsUiState::themeMode
        private set
    var themeColorScheme by settingsUiState::themeColorScheme
        private set
    var dynamicCoverColorEnabled by settingsUiState::dynamicCoverColorEnabled
        private set
    var pauseOnOtherAppPlayback by settingsUiState::pauseOnOtherAppPlayback
        private set
    var debugLogLines by settingsUiState::debugLogLines
        private set
    var debugLogLevelFilters by settingsUiState::debugLogLevelFilters
        private set
    var debugLogError by settingsUiState::debugLogError
        private set
    var localMetadataEditorTrack by localMusicState::metadataEditorTrack
        private set
    var selectedLocalMetadataProviderId by localMusicState::selectedMetadataProviderId
        private set
    var localMetadataSearchResults by localMusicState::metadataSearchResults
        private set
    var localMetadataSearchMessage by localMusicState::localMetadataSearchMessage
        private set
    val isDebugLogViewerAvailable: Boolean
        get() = debugLogController.isAvailable
    val isShuffleEnabled: Boolean
        get() = shuffleEnabled
    val repeatMode: RepeatMode
        get() = _repeatMode
    val isFmQueueActive: Boolean
        get() = isFmQueue
    val displayUpNextCount: Int
        get() = upNextQueue.size
    val playbackTransportCoordinator: PlaybackTransportCoordinator
        get() = playbackQueueCoordinator
    val playbackQueueUiPort: PlaybackQueueUiPort
        get() = playbackQueueCoordinator
    val playbackStartFailureSource: PlaybackStartFailureSource
        get() = playbackStartCoordinator
    val playbackNavigationPort: PlaybackNavigationPort
        get() = playbackNavigationController
    val playbackSleepTimerPort: PlaybackSleepTimerPort
        get() = sleepTimerController
    val downloadActionPort: DownloadActionPort
        get() = downloadController
    val playlistActionPort: PlaylistActionPort
        get() = playlistActionController
    val providerTrackActionPort: ProviderTrackActionPort
        get() = providerTrackActionController
    val localMusicActionPort: LocalMusicActionPort
        get() = localMusicController
    val replacementActionPort: ReplacementActionPort
        get() = playbackReplacementController
    val canNavigateBack: Boolean
        get() = isFullPlayerOpen ||
            selectedLocalMusicCollection != null ||
            selectedLocalMusicDirectoryId != null ||
            navigator.backStack.value.size > 1

    private var mainQueue by playbackQueueController::mainQueue
    private var originalMainQueue by playbackQueueController::originalMainQueue
    private var upNextQueue by playbackQueueController::upNextQueue
    private var mainQueueIndex by playbackQueueController::mainQueueIndex
    private var currentUpNextTrack by playbackQueueController::currentUpNextTrack
    private var currentIsUpNext by playbackQueueController::currentIsUpNext
    private var queueFeature by playbackQueueController::queueFeature
    private var queuePlaylistId by playbackQueueController::queuePlaylistId
    private var shuffleEnabled by playbackQueueController::shuffleEnabled
    private var _repeatMode by playbackQueueController::repeatMode
    private var isFmQueue by playbackQueueController::isFmQueue
    private var shuffleBeforeFm by playbackQueueController::shuffleBeforeFm
    private var appendQueueFeatureTask: Deferred<Int>? = null
    private var selectedFeatureTracksNextOffset = 0
    private var selectedFeatureLoadMoreJob: Job? = null
    private var selectedPlaylistTracksNextOffset = 0
    private var selectedPlaylistLoadMoreJob: Job? = null
    private var selectedPlaylistBackgroundLoadJob: Job? = null
    private var selectedMediaItemTracksNextOffset = 0
    private var selectedMediaItemAlbumsNextOffset = 0
    private var selectedMediaItemTracksLoadMoreJob: Job? = null
    private var selectedMediaItemAlbumsLoadMoreJob: Job? = null
    private var lastRecoveredPlaybackErrorKey: String? = null
    private var playRequestSerial: Long = 0
    private var smartReplacementSelections: Map<String, SmartReplacementSelection> = emptyMap()
    private var pendingManualReplacementSwitch: PendingManualReplacementSwitch? = null
    private var suppressPlaybackRecoveryRequestSerial: Long? = null
    private var playbackParts: List<PlaybackPart> = emptyList()
    private var currentPartIndex: Int = -1
    private val settingsUpdates = Channel<AppSettings>(capacity = Channel.UNLIMITED)
    private val recognitionController: RecognitionFeatureController = recognitionFeatureController
        ?: createRecognitionFeatureController(
            repository = audioRecognitionRepository,
            scope = scope,
            isPlaybackActive = { playbackState.status == PlayerStatus.Playing },
            pausePlayback = { playbackEngine.pause() },
        )
    private val resourceCacheController = ResourceCacheController(
        repository = resourceCacheRepository,
        state = settingsUiState,
        scope = scope,
        persistSettings = ::persistSettings,
        setLoading = { isLoading = it },
        setMessage = { message = it },
        onError = { setError(it) },
    )
    private val debugLogController = DebugLogController(
        repository = debugLogRepository,
        state = settingsUiState,
        scope = scope,
        setLoading = { isLoading = it },
        setMessage = { message = it },
        onError = { setError(it) },
    )
    private val providerAuthController = ProviderAuthController(
        providerRepository = providerRepository,
        sessionRepository = providerSessionRepository,
        oauthDeviceCodeAssistant = oauthDeviceCodeAssistant,
        scope = scope,
        state = providerAuthState,
        providerName = ::providerName,
        persistSettings = ::persistSettings,
        onSessionChanged = {
            if (homeSection == HomeSection.Mine && mineSection != MineSection.LocalMusic) {
                refreshActiveMineProviderContent()
            } else {
                refreshHomeContent(homeSection)
            }
        },
        setMessage = { message = it },
        onError = { setError(it) },
    )
    private val searchController: SearchFeatureController = searchFeatureController ?: SearchController(
        providerRepository = ProviderSearchRepositoryView(providerRepository),
        localRepository = localRepository,
        scope = scope,
        providerIdsForSearch = ::searchProviderIdsForSearch,
        providerExists = { providerId -> providers.any { it.providerId == providerId } },
        openSearch = { navigator.navigate(AppRoute.Search) },
        onPreferencesChanged = { _, _ -> persistSettings() },
    )
    private val localMusicController = LocalMusicController(
        repository = localRepository,
        providerRepository = providerRepository,
        navigator = navigator,
        scope = scope,
        state = localMusicState,
        providers = { providers },
        selectedSearchProviderId = { selectedSearchProviderId },
        isLocalMusicSectionActive = { homeSection == HomeSection.Mine && mineSection == MineSection.LocalMusic },
        persistSettings = ::persistSettings,
        setLoading = { isLoading = it },
        setMessage = { message = it },
        onError = { setError(it) },
        onTrackUpdated = ::updateLocalTrackCopies,
    )
    private val downloadController = DownloadController(
        providerRepository = providerRepository,
        downloadRepository = downloadRepository,
        localRepository = localRepository,
        localMusicController = localMusicController,
        scope = scope,
        state = downloadState,
        unavailablePlaybackPolicy = { unavailablePlaybackPolicy },
        smartReplacementProviderIds = ::selectedSmartReplacementProviderIds,
        smartReplacementMinScore = { smartReplacementMinScore },
        isLocalMusicSectionActive = { homeSection == HomeSection.Mine && mineSection == MineSection.LocalMusic },
        persistSettings = ::persistSettings,
        setMessage = { message = it },
        onError = { setError(it) },
    )
    private val settingsController = SettingsController(
        providerRepository = providerRepository,
        state = settingsUiState,
        scope = scope,
        persistSettings = ::persistSettings,
    )
    private val localPlaylistController = LocalPlaylistController(
        repository = localPlaylistRepository,
        navigator = navigator,
        scope = scope,
        state = playlistState,
        toMusicTracks = { playlist -> playlist.toMusicTracks() },
        toLocalPlaylistTrack = { track -> track.toLocalPlaylistTrack() },
        setLoading = { isLoading = it },
        setMessage = { message = it },
        onError = { setError(it) },
    )
    private val providerContentController = ProviderContentController(
        providerRepository = providerRepository,
        state = providerState,
        localPlaylistController = localPlaylistController,
        scope = scope,
        mineSection = { mineSection },
        selectedProviderIdsFor = ::selectedProviderIdsFor,
        isProviderLoggedIn = ::isProviderLoggedIn,
        refreshProviderCatalog = ::refreshProviderCatalog,
        ensureLocalMusic = ::ensureLocalMusic,
        sortSections = { sections -> sections.sortedSectionsByOrder() },
        isDeferredHomeFeature = { feature -> feature.isDeferredHomeFeature() },
        providerErrorMessage = { throwable, fallback, providerId ->
            providerErrorMessage(throwable, fallback, providerId)
        },
        setLoading = { isLoading = it },
        setMessage = { message = it },
        onError = { setError(it) },
    )
    private val sleepTimerController = PlaybackSleepTimerController(
        playbackEngine = playbackEngine,
        scope = scope,
        currentTrackId = { currentQueueTrack()?.id ?: playbackState.currentTrack?.id },
        nowMillis = nowMillis,
        onFeedback = { playbackFeedback = it },
    )
    private val playbackLyricsController = PlaybackLyricsController(
        providerRepository = providerRepository,
        scope = scope,
        currentRequestSerial = { playRequestSerial },
        currentTrackId = { currentQueueTrack()?.id ?: playbackState.currentTrack?.id },
        currentLyrics = { playbackState.lyrics },
        updateLyrics = { lyrics -> playbackState = playbackState.copy(lyrics = lyrics) },
    )
    private val playbackStartCoordinator = PlaybackStartCoordinator(
        queue = playbackQueueController,
        playbackEngine = playbackEngine,
        playbackRepository = playbackProviderRepository,
        scope = scope,
        currentPlaybackState = { playbackState },
        publishPlaybackState = { playbackState = it },
        prepareTrack = { track -> track.withRememberedReplacement().preferDownloaded() },
        unavailablePlaybackPolicy = { unavailablePlaybackPolicy },
        smartReplacementProviderIds = ::selectedSmartReplacementProviderIds,
        smartReplacementMinScore = { smartReplacementMinScore },
        nextRequestSerial = { ++playRequestSerial },
        currentRequestSerial = { playRequestSerial },
        playbackParts = { playbackParts },
        setPlaybackParts = { playbackParts = it },
        currentPartIndex = { currentPartIndex },
        setCurrentPartIndex = { currentPartIndex = it },
        prepareSleepTimer = sleepTimerController::prepareForTrack,
        resetLyricsForPlaybackRequest = playbackLyricsController::resetForPlaybackRequest,
        maybeLoadLyrics = playbackLyricsController::maybeLoad,
        persistQueue = ::persistPlaybackQueue,
        setLoading = { isLoading = it },
        setMessage = { message = it },
        failureMessage = { throwable ->
            providerErrorMessage(throwable, throwable::class.simpleName.orEmpty())
        },
        onRequestStarted = { serial, suppressRecovery ->
            suppressPlaybackRecoveryRequestSerial = serial.takeIf { suppressRecovery }
        },
        onManualSelectionStarted = { serial, playbackTrack, selection, rollbackTrack ->
            pendingManualReplacementSwitch = PendingManualReplacementSwitch(
                requestSerial = serial,
                previousTrack = rollbackTrack,
                originalTrackId = playbackTrack.originalId ?: playbackTrack.id,
                selection = selection,
            )
        },
        onStartFailure = startFailure@{ serial, playbackTrack, skippedUnavailableCount, manualSelection, throwable ->
            if (manualSelection != null && rollbackManualReplacement(serial, throwable.message)) {
                return@startFailure
            }
            if (suppressPlaybackRecoveryRequestSerial == serial) {
                showManualReplacementRestoreFailure(throwable.message)
            } else if (!skipUnavailableTrack(playbackTrack, skippedUnavailableCount, throwable)) {
                setError(throwable)
            }
        },
        prefetchQueue = ::prefetchFeatureQueueIfNeeded,
    )
    private val playbackQueueCoordinator = PlaybackQueueCoordinator(
        queue = playbackQueueController,
        scope = scope,
        fallbackTrack = { playbackState.currentTrack },
        playbackParts = { playbackParts },
        currentPartIndex = { currentPartIndex },
        startPlayback = { track, skippedUnavailableCount, requestedPartIndex ->
            startPlayback(track, skippedUnavailableCount, requestedPartIndex)
        },
        stopPlayback = playbackEngine::stop,
        persistQueue = ::persistPlaybackQueue,
        updateQueueState = ::updatePlaybackQueueState,
        appendFeatureQueue = ::appendFeatureQueue,
        setTrackChangeDirection = { trackChangeDirection = it },
        setMessage = { message = it },
    )
    private val playlistActionController = PlaylistActionController(
        providerRepository = providerRepository,
        localPlaylistController = localPlaylistController,
        state = playlistState,
        scope = scope,
        selectedPlaylist = { selectedPlaylist },
        selectedPlaylistCategory = { selectedPlaylistCategory },
        selectedPlaylistTracks = { selectedPlaylistTracks },
        updateSelectedPlaylistTracks = { selectedPlaylistTracks = it },
        updateSelectedPlaylistError = { selectedPlaylistError = it },
        providerCapabilities = { providerCapabilities },
        isProviderLoggedIn = ::isProviderLoggedIn,
        providerName = ::providerName,
        refreshAfterProviderMutation = ::refreshAfterProviderMutation,
        setLoading = { isLoading = it },
        setMessage = { message = it },
        onError = { setError(it) },
    )
    private val providerTrackActionController = ProviderTrackActionController(
        providerRepository = providerRepository,
        scope = scope,
        navigation = playbackNavigationController,
        providerCapabilities = { providerCapabilities },
        isProviderLoggedIn = ::isProviderLoggedIn,
        openMediaItem = ::openMediaItem,
        openTrackDetail = ::openTrackDetail,
        searchTrackText = ::searchTrackText,
        removeDislikedTrack = ::removeDislikedTrack,
        refreshMineContent = ::refreshMineContent,
        setLoading = { isLoading = it },
        setMessage = { message = it },
        onError = { setError(it) },
    )
    private val playbackReplacementController = PlaybackReplacementController(
        playbackRepository = playbackProviderRepository,
        scope = scope,
        smartReplacementProviderIds = ::selectedSmartReplacementProviderIds,
        smartReplacementMinScore = { smartReplacementMinScore },
        currentTrack = { currentQueueTrack() ?: playbackState.currentTrack },
        startManualReplacement = { track, selection, rollbackTrack ->
            startPlayback(
                track = track,
                manualSelection = selection,
                rollbackTrack = rollbackTrack,
            )
        },
        closePlayer = playbackNavigationController::closeFullPlayer,
        openTrackDetail = ::openTrackDetail,
        failureMessage = { throwable, fallback, providerId ->
            providerErrorMessage(throwable, fallback, providerId)
        },
    )
    private val playbackLifecycleCoordinator = PlaybackLifecycleCoordinator(
        sleepTimer = sleepTimerController,
        fallbackPlaybackParts = { playbackParts },
        fallbackCurrentPartIndex = { currentPartIndex },
        autoAdvance = playbackQueueCoordinator::next,
    )

    init {
        scope.launch {
            refreshLocalPlaylistsInternal(showMessage = false)
        }
        scope.launch {
            for (settings in settingsUpdates) {
                runCatching { settingsRepository.update { settings } }
                    .onFailure { setError(it) }
            }
        }
        scope.launch {
            val loadedSettings = runCatching { settingsRepository.awaitSettings() }
            loadedSettings.getOrNull()?.let {
                applySettings(it)
                downloadRepository.updateParallelism(downloadParallelism)
            }
            isSettingsLoaded = true
            runCatching { playbackQueueStore.load() }
                .onSuccess { restorePlaybackQueue(it) }
            localMusicController.updateScanSettings()
            resourceCacheController.updateLimit()
            settingsController.updateAudioQualityPoliciesNow()
            resourceCacheController.refreshUsageNow()
            runCatching { providerRepository.availableProviders() }
                .onSuccess { loadedProviders ->
                    availableProviders = loadedProviders.sortedProvidersByOrder()
                }
            runCatching {
                providerRepository.updateEnabledProviders(enabledProviderIds)
                providerRepository.initialize()
                refreshProviderCatalog()
                downloadRepository.load()
            }.onSuccess {
                message = "音乐服务已就绪"
                refreshHomeContent(homeSection, refreshCatalog = false)
            }.onFailure {
                setError(it)
            }
        }
        providerAuthController.start()
        scope.launch {
            settingsRepository.state.collect { settingsState ->
                if (settingsState.isLoaded) {
                    applySettings(settingsState.settings)
                }
            }
        }
        downloadController.start()
        resourceCacheController.startUsageCollection()
        scope.launch {
            playbackEngine.state.collect { engineState ->
                engineState.currentTrack?.let(::synchronizePlaybackTrack)
                val queueTrackId = currentQueueTrack()?.id
                val playbackEndAction = playbackLifecycleCoordinator.evaluate(
                    engineState = engineState,
                    currentQueueTrackId = queueTrackId,
                )
                if (engineState.status == PlayerStatus.Playing) {
                    lastRecoveredPlaybackErrorKey = null
                }
                playbackState = engineState.copy(
                    queue = displayQueue(),
                    queueIndex = displayQueueIndex(),
                    currentTrack = currentQueueTrack() ?: engineState.currentTrack,
                    playbackParts = engineState.playbackParts.ifEmpty { playbackParts },
                    currentPartIndex = engineState.currentPartIndex.takeIf { it >= 0 } ?: currentPartIndex,
                    lyrics = playbackLyricsController.mergedLyrics(
                        engineState = engineState,
                        currentQueueTrackId = currentQueueTrack()?.id,
                        previousPlaybackState = playbackState,
                    ),
                )
                playbackLyricsController.maybeLoad(playbackState.currentTrack)
                if (engineState.playbackParts.isNotEmpty()) {
                    playbackParts = engineState.playbackParts
                    currentPartIndex = engineState.currentPartIndex
                }
                isLoading = engineState.status == PlayerStatus.Loading
                if (playbackEndAction != PlaybackEndAction.None) {
                    playbackLifecycleCoordinator.execute(playbackEndAction)
                } else if (engineState.status == PlayerStatus.Error) {
                    if (!rollbackManualReplacement(playRequestSerial, engineState.errorMessage)) {
                        if (suppressPlaybackRecoveryRequestSerial == playRequestSerial) {
                            showManualReplacementRestoreFailure(engineState.errorMessage)
                        } else {
                            recoverPlaybackEngineError(engineState)
                        }
                    }
                }
                if (engineState.status == PlayerStatus.Playing || engineState.status == PlayerStatus.Paused) {
                    if (suppressPlaybackRecoveryRequestSerial == playRequestSerial) {
                        suppressPlaybackRecoveryRequestSerial = null
                    }
                    commitManualReplacementIfReady(engineState)
                }
            }
        }
    }

    fun authStateFor(provider: ProviderInfo): ProviderAuthState =
        providerAuthController.authStateFor(provider)

    fun isProviderAuthBusy(providerId: String): Boolean = providerAuthController.isBusy(providerId)

    fun providerAuthError(providerId: String): String? = providerAuthController.authError(providerId)

    fun cookieInputFor(providerId: String): String = providerAuthController.cookieInput(providerId)

    fun providerHeaderInputFor(providerId: String): ProviderHeaderInput =
        providerAuthController.headerInput(providerId)

    fun providerOAuthInputFor(providerId: String): ProviderOAuthInput =
        providerAuthController.oauthInput(providerId)

    fun isProviderEnabled(providerId: String): Boolean = providerId in enabledProviderIds

    fun orderedAvailableProviders(): List<ProviderInfo> = availableProviders.sortedProvidersByOrder()

    fun orderedProviders(): List<ProviderInfo> = providers.sortedProvidersByOrder()

    fun selectedSettingsProvider(): ProviderInfo? {
        return providers.firstOrNull { it.providerId == selectedSettingsProviderId } ?: providers.firstOrNull()
    }

    fun contentSectionsFor(section: HomeSection): List<ProviderContentSection> {
        return when (section) {
            HomeSection.Recommend -> recommendSections
            HomeSection.Music -> musicSections
            HomeSection.Mine -> minePlaylistSections + mineFavoritePlaylistSections + mineSections
        }
    }

    fun onLocalMusicPermissionChange(hasPermission: Boolean) =
        localMusicController.onPermissionChange(hasPermission)

    fun ensureLocalMusic() = localMusicController.ensure()

    fun refreshLocalMusic() = localMusicController.refresh()

    private fun refreshLocalMusic(forceRefresh: Boolean, showLoading: Boolean) =
        localMusicController.refresh(forceRefresh, showLoading)

    fun openSearch() {
        navigator.navigate(AppRoute.Search)
    }

    fun closeSearch() {
        navigator.pop(AppRoute.Search)
    }

    fun openRecognition() {
        recognitionController.dispatch(RecognitionAction.Reset)
        navigator.navigate(AppRoute.AudioRecognition)
    }

    fun onMicrophonePermissionChange(hasPermission: Boolean) {
        if (hasPermission && isRecognitionOpen && recognitionUiState == RecognitionUiState.Idle) {
            recognitionController.dispatch(RecognitionAction.Start)
        }
    }

    fun startRecognition() {
        recognitionController.dispatch(RecognitionAction.Start)
    }

    fun cancelRecognition() {
        recognitionController.dispatch(RecognitionAction.Cancel)
    }

    fun retryRecognition() {
        recognitionController.dispatch(RecognitionAction.Retry)
    }

    fun closeRecognition() {
        recognitionController.dispatch(RecognitionAction.Close)
        navigator.pop(AppRoute.AudioRecognition)
    }

    fun onRecognitionScreenDisposed() {
        recognitionController.dispatch(RecognitionAction.CancelIfInProgress)
    }

    fun onAppBackgrounded() {
        recognitionController.dispatch(RecognitionAction.CancelIfInProgress)
    }

    fun searchRecognizedSong(song: RecognizedSong) = searchController.searchRecognizedSong(song)

    fun canOpenRecognizedNeteaseDetail(song: RecognizedSong): Boolean =
        "netease" in enabledProviderIds && !song.neteaseSongId.isNullOrBlank()

    fun openRecognizedNeteaseDetail(song: RecognizedSong) {
        val songId = song.neteaseSongId?.takeIf { it.isNotBlank() } ?: return
        if ("netease" !in enabledProviderIds) return
        val trackId = "netease:$songId"
        val routeTrack = MusicTrack(
            id = trackId,
            title = song.title,
            artists = song.artists.joinToString(" / "),
            album = song.album,
            source = "netease",
            sourceType = TrackSourceType.Provider,
            coverUrl = song.coverUrl,
            providerId = trackId,
            providerName = "网易云音乐",
        )
        navigator.navigate(AppRoute.TrackDetail(routeTrack.toNavigationTrack()))
        selectedTrack = routeTrack
        selectedTrackError = null
        scope.launch {
            isLoading = true
            runCatching { providerRepository.trackDetail(trackId) }
                .onSuccess {
                    selectedTrack = it
                    loadSelectedTrackRelated(it)
                    message = it.title.ifBlank { "歌曲已加载" }
                }
                .onFailure {
                    selectedTrackError = it.message ?: "资源加载失败"
                    message = "资源加载失败"
                }
            isLoading = false
        }
    }

    fun activateRoute(route: AppRoute) {
        when (route) {
            is AppRoute.FeatureDetail -> {
                val feature = route.feature.toProviderFeature()
                if (selectedFeature?.id != feature.id) openFeature(feature)
            }
            is AppRoute.TrackDetail -> {
                val track = route.track.toMusicTrack()
                if (selectedTrack?.id != track.id) openTrackDetail(track)
            }
            is AppRoute.VideoDetail -> {
                val video = route.video.toProviderVideo()
                if (selectedVideo?.id != video.id) openVideo(video)
            }
            is AppRoute.PlaylistDetail -> {
                val playlist = route.playlist.toProviderPlaylist()
                if (selectedPlaylist?.id != playlist.id) {
                    openPlaylist(
                        playlist,
                        route.category?.let { runCatching { ProviderFeatureCategory.valueOf(it) }.getOrNull() },
                    )
                }
            }
            is AppRoute.MediaItemDetail -> {
                val item = route.item.toProviderMediaItem()
                if (selectedMediaItem?.id != item.id) openMediaItem(item)
            }
            else -> Unit
        }
    }

    fun navigateBack(): Boolean {
        return when {
            isFullPlayerOpen && isQueueOpen -> {
                playbackNavigationController.toggleQueue()
                true
            }
            isFullPlayerOpen -> {
                closeFullPlayer()
                true
            }
            isVideoFullscreen -> {
                toggleVideoFullscreen()
                true
            }
            (selectedLocalMusicCollection != null || selectedLocalMusicDirectoryId != null) &&
                navigator.currentRoute == AppRoute.Home -> {
                closeLocalMusicCollection()
                true
            }
            else -> when (navigator.currentRoute) {
                AppRoute.DebugLogs -> {
                    closeDebugLogs()
                    true
                }
                AppRoute.DownloadManager -> {
                    closeDownloadManager()
                    true
                }
                AppRoute.Settings -> {
                    if (settingsLoginProviderId != null) {
                        closeSettingsProviderLogin()
                    } else {
                        closeSettings()
                    }
                    true
                }
                AppRoute.Video -> {
                    closeVideo()
                    true
                }
                AppRoute.Track -> {
                    closeTrack()
                    true
                }
                AppRoute.MediaItem -> {
                    closeMediaItem()
                    true
                }
                AppRoute.Playlist -> {
                    closePlaylist()
                    true
                }
                AppRoute.LocalPlaylist -> {
                    closeLocalPlaylist()
                    true
                }
                AppRoute.LocalMusicCollection -> {
                    closeLocalMusicCollection()
                    true
                }
                AppRoute.Feature -> {
                    closeFeature()
                    true
                }
                AppRoute.Search -> {
                    closeSearch()
                    true
                }
                AppRoute.AudioRecognition -> {
                    closeRecognition()
                    true
                }
                AppRoute.Home -> false
                is AppRoute.FeatureDetail,
                is AppRoute.TrackDetail,
                is AppRoute.VideoDetail,
                is AppRoute.PlaylistDetail,
                is AppRoute.MediaItemDetail -> false
            }
        }
    }

    fun openSettings(providerId: String? = null) {
        providerId?.takeIf { it.isNotBlank() }?.let { selectedSettingsProviderId = it }
        providerId?.takeIf { it.isNotBlank() }?.let { settingsLoginProviderId = it }
        navigator.navigate(AppRoute.Settings)
        refreshAllProviderAuthStates(refreshUserInfo = true)
        refreshResourceCacheUsage()
        refreshLocalMusicDirectories()
    }

    fun closeSettings() {
        navigator.pop(AppRoute.Settings)
        settingsLoginProviderId = null
    }

    fun openSettingsProviderLogin(providerId: String) {
        settingsLoginProviderId = providerId
        selectedSettingsProviderId = providerId
        persistSettings()
    }

    fun closeSettingsProviderLogin() {
        settingsLoginProviderId = null
    }

    fun openDebugLogs() {
        if (!debugLogController.isAvailable) return
        navigator.navigate(AppRoute.DebugLogs)
        refreshDebugLogs()
    }

    fun closeDebugLogs() {
        navigator.pop(AppRoute.DebugLogs)
    }

    fun openDownloadManager() {
        navigator.navigate(AppRoute.DownloadManager)
    }

    fun closeDownloadManager() {
        navigator.pop(AppRoute.DownloadManager)
    }

    fun dismissDownloadQueueFeedback(feedback: String) =
        downloadController.dismissQueueFeedback(feedback)

    fun dismissPlaybackFeedback(feedback: String) {
        if (playbackFeedback == feedback) playbackFeedback = null
    }

    fun setSleepTimerDurationMinutes(minutes: Int) =
        sleepTimerController.setSleepTimerDurationMinutes(minutes)

    fun setSleepTimerToEndOfTrack() = sleepTimerController.setSleepTimerToEndOfTrack()

    fun clearSleepTimer() = sleepTimerController.clearSleepTimer()

    fun onDownloadParallelismChange(value: Int) = downloadController.onParallelismChange(value)

    fun refreshDebugLogs() {
        debugLogController.refresh()
    }

    fun onDebugLogLevelFilterChange(level: DebugLogLevel, selected: Boolean) {
        debugLogController.onLevelFilterChange(level, selected)
    }

    fun exportDebugLogs(lines: List<String>) {
        debugLogController.export(lines)
    }

    fun onProviderCookiesChange(providerId: String, value: String) =
        providerAuthController.onCookiesChange(providerId, value)

    fun onProviderHeaderAuthorizationChange(providerId: String, value: String) =
        providerAuthController.onHeaderAuthorizationChange(providerId, value)

    fun onProviderHeaderCookieChange(providerId: String, value: String) =
        providerAuthController.onHeaderCookieChange(providerId, value)

    fun onProviderOAuthClientIdChange(providerId: String, value: String) =
        providerAuthController.onOAuthClientIdChange(providerId, value)

    fun onProviderOAuthClientSecretChange(providerId: String, value: String) =
        providerAuthController.onOAuthClientSecretChange(providerId, value)

    fun onSettingsProviderChange(providerId: String) {
        selectedSettingsProviderId = providerId
        persistSettings()
    }

    fun onProviderEnabledChange(providerId: String, enabled: Boolean) {
        val next = if (enabled) {
            enabledProviderIds + providerId
        } else {
            enabledProviderIds - providerId
        }
        if (next.isEmpty()) {
            message = "至少保留一个音源"
            return
        }
        enabledProviderIds = next
        persistSettings()
        scope.launch {
            isLoading = true
            message = "正在更新音源"
            runCatching {
                providerRepository.updateEnabledProviders(enabledProviderIds)
                clearProviderContent()
                refreshProviderCatalog()
            }.onSuccess {
                message = "音源已更新"
                refreshHomeContent(homeSection)
            }.onFailure {
                setError(it)
            }
            isLoading = false
        }
    }

    suspend fun configureOnboardingProviders(
        selectedProviderIds: Set<String>,
        bilibiliReplacementOnly: Boolean,
    ): Boolean {
        val availableProviderIds = availableProviders.map { it.providerId }.toSet()
        val selectedIds = selectedProviderIds.intersect(availableProviderIds)
        if (selectedIds.isEmpty()) {
            message = "请至少选择一个音源"
            return false
        }
        if (bilibiliReplacementOnly && selectedIds == setOf("bilibili")) {
            message = "Bilibili 仅作为替换音源时，请再选择一个常规音源"
            return false
        }

        val previousSettings = currentSettings()
        isLoading = true
        message = "正在初始化音源"
        return runCatching {
            enabledProviderIds = selectedIds
            if (bilibiliReplacementOnly && "bilibili" in selectedIds) {
                val regularProviderIds = selectedIds - "bilibili"
                searchProviderIds = regularProviderIds
                recommendProviderIds = regularProviderIds
                exploreProviderIds = regularProviderIds
                mineProviderIds = regularProviderIds
                smartReplacementProviderIds = setOf("bilibili")
                unavailablePlaybackPolicy = UnavailablePlaybackPolicy.SmartReplace
            } else {
                searchProviderIds = emptySet()
                recommendProviderIds = emptySet()
                exploreProviderIds = emptySet()
                mineProviderIds = emptySet()
                smartReplacementProviderIds = emptySet()
            }
            providerRepository.updateEnabledProviders(enabledProviderIds)
            clearProviderContent()
            refreshProviderCatalog()
            settingsRepository.update { currentSettings() }
        }.fold(
            onSuccess = {
                message = "音源初始化完成"
                refreshHomeContent(homeSection)
                true
            },
            onFailure = { throwable ->
                applySettings(previousSettings)
                runCatching {
                    providerRepository.updateEnabledProviders(enabledProviderIds)
                    clearProviderContent()
                    refreshProviderCatalog()
                    settingsRepository.update { previousSettings }
                }
                setError(throwable)
                false
            },
        ).also {
            isLoading = false
        }
    }

    suspend fun completeOnboarding(): Boolean {
        if (onboardingCompleted) return true
        isLoading = true
        message = "正在保存初始设置"
        return runCatching {
            settingsRepository.update { currentSettings().copy(onboardingCompleted = true) }
        }.fold(
            onSuccess = {
                onboardingCompleted = true
                message = "初始设置已完成"
                true
            },
            onFailure = {
                setError(it)
                false
            },
        ).also {
            isLoading = false
        }
    }

    fun moveProvider(providerId: String, offset: Int) {
        val availableIds = availableProviders.map { it.providerId }.toSet()
        val orderedIds = normalizedProviderOrder(availableIds).toMutableList()
        val index = orderedIds.indexOf(providerId)
        val targetIndex = (index + offset).coerceIn(0, orderedIds.lastIndex)
        if (index < 0 || index == targetIndex) return
        val moved = orderedIds.removeAt(index)
        orderedIds.add(targetIndex, moved)
        providerOrderIds = orderedIds
        availableProviders = availableProviders.sortedProvidersByOrder()
        providers = providers.sortedProvidersByOrder()
        providerFeatures = providerFeatures.sortedFeaturesByOrder()
        reorderProviderContent()
        persistSettings()
    }

    fun isProviderShownIn(providerId: String, section: ProviderDisplaySection): Boolean =
        providerId in selectedProviderIdsFor(section)

    fun onProviderShownInChange(providerId: String, section: ProviderDisplaySection, shown: Boolean) {
        if (section == ProviderDisplaySection.Replace) {
            onSmartReplacementProviderEnabledChange(providerId, shown)
            return
        }
        val current = configuredProviderIdsFor(section).ifEmpty {
            availableProviders.map { it.providerId }.toSet()
        }
        if (!shown && current.size <= 1 && providerId in current) {
            message = "至少保留一个${section.label}音源"
            return
        }
        val updated = current.toMutableSet().apply {
            if (shown) add(providerId) else remove(providerId)
        }
        when (section) {
            ProviderDisplaySection.Search -> searchProviderIds = updated
            ProviderDisplaySection.Recommend -> recommendProviderIds = updated
            ProviderDisplaySection.Explore -> exploreProviderIds = updated
            ProviderDisplaySection.Mine -> mineProviderIds = updated
            ProviderDisplaySection.Replace -> Unit
        }
        persistSettings()
        when (section) {
            ProviderDisplaySection.Recommend -> refreshHomeContent(HomeSection.Recommend)
            ProviderDisplaySection.Explore -> refreshHomeContent(HomeSection.Music)
            ProviderDisplaySection.Mine -> refreshActiveMineProviderContent()
            else -> Unit
        }
    }

    fun onProviderLoginModeChange(value: ProviderLoginMode) {
        providerLoginMode = value
        persistSettings()
    }

    fun onAudioCacheLimitChange(value: Int) {
        resourceCacheController.onAudioCacheLimitChange(value)
    }

    fun onImageCacheLimitChange(value: Int) {
        resourceCacheController.onImageCacheLimitChange(value)
    }

    fun refreshResourceCacheUsage() {
        resourceCacheController.refreshUsage()
    }

    fun clearResourceCache() {
        resourceCacheController.clear()
    }

    fun loginProviderWithCookies(providerId: String, cookiesJson: String) =
        providerAuthController.loginWithCookies(providerId, cookiesJson)

    fun loginProviderWithHeaders(providerId: String) = providerAuthController.loginWithHeaders(providerId)

    fun loginYtmusicWithHeaderFile(headerFileJson: String) =
        providerAuthController.loginYtmusicWithHeaderFile(headerFileJson)

    fun startYtmusicTvOAuthLogin() = providerAuthController.startYtmusicTvOAuthLogin()

    fun markYtmusicOAuthBrowserOpened() = providerAuthController.markYtmusicOAuthBrowserOpened()

    fun copyYtmusicOAuthUserCode() = providerAuthController.copyYtmusicOAuthUserCode()

    fun cancelYtmusicTvOAuthLogin() = providerAuthController.cancelYtmusicTvOAuthLogin()

    fun loginYtmusicWithOAuthJson(oauthJson: String) =
        providerAuthController.loginYtmusicWithOAuthJson(oauthJson)

    fun importYtmusicOAuthRelatedJson(json: String) =
        providerAuthController.importYtmusicOAuthRelatedJson(json)

    fun logoutProvider(providerId: String) = providerAuthController.logout(providerId)

    fun onWifiAudioQualityPolicyChange(value: AudioQualityPolicy) =
        settingsController.onWifiAudioQualityPolicyChange(value)

    fun onCellularAudioQualityPolicyChange(value: AudioQualityPolicy) =
        settingsController.onCellularAudioQualityPolicyChange(value)

    fun onUnavailablePlaybackPolicyChange(value: UnavailablePlaybackPolicy) {
        unavailablePlaybackPolicy = value
        persistSettings()
    }

    fun onPauseOnOtherAppPlaybackChange(value: Boolean) =
        settingsController.onPauseOnOtherAppPlaybackChange(value)

    fun onSmartReplacementProviderEnabledChange(providerId: String, enabled: Boolean) {
        val current = selectedSmartReplacementProviderIds().toMutableSet()
        if (enabled) {
            current += providerId
        } else {
            current -= providerId
        }
        if (current.isEmpty()) {
            message = "至少保留一个替换音源"
            return
        }
        smartReplacementProviderIds = current
        persistSettings()
    }

    fun onSmartReplacementMinScoreChange(value: Double) {
        smartReplacementMinScore = value.coerceIn(0.0, 1.0)
        persistSettings()
    }

    fun onLyricFontSizeChange(value: LyricFontSize) =
        settingsController.onLyricFontSizeChange(value)

    fun onStatusBarLyricsEnabledChange(value: Boolean) {
        statusBarLyricsEnabled = value
        persistSettings()
    }

    fun updateStatusBarLyricsAvailability(value: Boolean) {
        isStatusBarLyricsAvailable = value
    }

    fun onThemeModeChange(value: ThemeMode) = settingsController.onThemeModeChange(value)

    fun onThemeColorSchemeChange(value: ThemeColorScheme) =
        settingsController.onThemeColorSchemeChange(value)

    fun onDynamicCoverColorEnabledChange(value: Boolean) =
        settingsController.onDynamicCoverColorEnabledChange(value)

    fun onQueryChange(value: String) = searchController.dispatch(SearchAction.QueryChanged(value))

    fun onSearchScopeChange(value: SearchScope) = searchController.dispatch(SearchAction.ScopeChanged(value))

    fun onSearchProviderChange(providerId: String) = searchController.dispatch(SearchAction.ProviderChanged(providerId))

    fun onProviderSearchTabChange(value: ProviderSearchTab) =
        searchController.dispatch(SearchAction.ProviderTabChanged(value))

    fun onHomeSectionChange(value: HomeSection) {
        homeSection = value
        if (value != HomeSection.Mine) closeLocalMusicDirectory()
        persistSettings()
        if (value == HomeSection.Mine) {
            refreshActiveMineSectionIfNeeded()
        } else if (value != HomeSection.Mine && contentSectionsFor(value).isEmpty()) {
            refreshHomeContent(value)
        }
    }

    fun onMineSectionChange(value: MineSection) {
        mineSection = value
        if (value != MineSection.LocalMusic) closeLocalMusicDirectory()
        persistSettings()
        when (value) {
            MineSection.Playlists -> {
                refreshLocalPlaylists()
                if (minePlaylistSections.isEmpty()) refreshMinePlaylistContent()
            }
            MineSection.Songs,
            MineSection.Artists,
            MineSection.Albums -> if (mineSections.isEmpty()) refreshMineContent()
            MineSection.LocalMusic -> ensureLocalMusic()
        }
    }

    fun onPlaylistFilterChange(value: PlaylistFilter) {
        playlistFilter = value
        persistSettings()
    }

    fun refreshLocalPlaylists() = localPlaylistController.refresh()

    private suspend fun refreshLocalPlaylistsInternal(showMessage: Boolean) {
        localPlaylistController.refreshInternal(showMessage)
    }

    fun onLocalMusicViewModeChange(value: LocalMusicViewMode) =
        localMusicController.onViewModeChange(value)

    fun openLocalMusicDirectory(directoryId: String) = localMusicController.openDirectory(directoryId)

    fun openLocalMusicCollection(mode: LocalMusicViewMode, key: String) =
        localMusicController.openCollection(mode, key)

    fun closeLocalMusicCollection() = localMusicController.closeCollection()

    fun closeLocalMusicDirectory() = localMusicController.closeCollection()

    fun onLocalMusicDirectoryEnabledChange(directoryId: String, enabled: Boolean) =
        localMusicController.onDirectoryEnabledChange(directoryId, enabled)

    fun onLocalMusicMinDurationChange(value: Int) = localMusicController.onMinDurationChange(value)

    fun openLocalMetadataEditor(track: MusicTrack) = localMusicController.openMetadataEditor(track)

    fun closeLocalMetadataEditor() = localMusicController.closeMetadataEditor()

    fun onLocalMetadataProviderChange(providerId: String) =
        localMusicController.onMetadataProviderChange(providerId)

    fun saveLocalMetadata(track: MusicTrack, title: String, artists: String, album: String) =
        localMusicController.saveMetadata(track, title, artists, album)

    fun searchLocalMetadata(title: String, artists: String, album: String) =
        localMusicController.searchMetadata(title, artists, album)

    fun applyProviderMetadata(track: MusicTrack, providerTrack: MusicTrack) =
        localMusicController.applyProviderMetadata(track, providerTrack)

    fun downloadLocalLyrics(track: MusicTrack, providerTrack: MusicTrack) =
        localMusicController.downloadLyrics(track, providerTrack)

    fun search() = searchController.dispatch(SearchAction.Submit)

    private fun searchTrackText(text: String, providerId: String?) =
        searchController.searchText(text, providerId)

    fun refreshHomeContent(section: HomeSection = homeSection) =
        providerContentController.refreshHomeContent(section)

    private fun refreshHomeContent(section: HomeSection, refreshCatalog: Boolean) =
        providerContentController.refreshHomeContent(section, refreshCatalog)

    fun refreshMinePlaylistContent() = providerContentController.refreshMinePlaylistContent()

    private fun refreshMinePlaylistContent(refreshCatalog: Boolean) =
        providerContentController.refreshMinePlaylistContent(refreshCatalog)

    fun refreshMineContent() = providerContentController.refreshMineContent()

    private fun refreshMineContent(refreshCatalog: Boolean) =
        providerContentController.refreshMineContent(refreshCatalog)

    private fun refreshActiveMineSectionIfNeeded() =
        providerContentController.refreshActiveMineSectionIfNeeded()

    private fun refreshActiveMineSection(refreshCatalog: Boolean = true) =
        providerContentController.refreshActiveMineSection(refreshCatalog)

    private fun refreshActiveMineProviderContent() =
        providerContentController.refreshActiveMineProviderContent()

    fun openFeature(feature: ProviderFeature) {
        navigator.navigate(AppRoute.FeatureDetail(feature.toNavigationFeature()))
        selectedFeature = feature
        selectedFeatureContent = null
        selectedFeatureTracks = emptyList()
        selectedFeatureTracksNextOffset = 0
        selectedFeatureTracksHasMore = false
        selectedFeatureLoadMoreJob = null
        selectedFeatureError = null
        scope.launch {
            isLoading = true
            message = "正在加载：${feature.title}"
            val deferred = scope.async { providerRepository.loadFeaturePage(feature, offset = 0) }
            val result = withTimeoutOrNull(30_000) {
                runCatching { deferred.await() }
            }
            if (result == null) {
                deferred.cancel()
                selectedFeatureError = "加载超时，请检查网络后重试"
                message = selectedFeatureError.orEmpty()
            } else {
                result.onSuccess { section ->
                    if (selectedFeature == feature) {
                        selectedFeatureContent = section
                        selectedFeatureTracks = section.tracks
                        selectedFeatureTracksNextOffset = section.nextOffset
                        selectedFeatureTracksHasMore = section.hasMore
                        selectedFeatureError = when {
                            section.isLoginRequired -> "登录后显示 ${section.feature.providerName} 的个性化内容"
                            section.errorMessage != null -> section.errorMessage
                            else -> null
                        }
                        message = when {
                            selectedFeatureError != null -> selectedFeatureError.orEmpty()
                            section.contentCount() == 0 -> "${feature.title} 暂无内容"
                            else -> "${feature.title} · ${section.contentCount()} 项"
                        }
                    }
                }.onFailure {
                    selectedFeatureError = providerErrorMessage(it, "加载失败", feature.providerId)
                    setError(it, feature.providerId)
                }
            }
            isLoading = false
        }
    }

    fun closeFeature() {
        navigator.pop(AppRoute.Feature)
        selectedFeature = null
        selectedFeatureContent = null
        selectedFeatureTracks = emptyList()
        selectedFeatureTracksNextOffset = 0
        selectedFeatureTracksHasMore = false
        selectedFeatureLoadMoreJob = null
        selectedFeatureError = null
    }

    fun openTrackDetail(track: MusicTrack) {
        if (track.sourceType != TrackSourceType.Provider) return
        navigator.navigate(AppRoute.TrackDetail(track.toNavigationTrack()))
        selectedTrack = track
        selectedTrackError = null
        selectedTrackSimilar = emptyList()
        selectedTrackComments = emptyList()
        selectedTrackVideo = null
        selectedTrackRelatedError = null
        loadSelectedTrackRelated(track)
    }

    fun openOriginalTrackDetail(track: MusicTrack) =
        providerTrackActionController.openOriginalTrackDetail(track)

    fun openReplacementTrackDetail(track: MusicTrack) =
        playbackReplacementController.openReplacementTrackDetail(track)

    fun loadReplacementCandidates(track: MusicTrack) =
        playbackReplacementController.loadReplacementCandidates(track)

    fun selectReplacementCandidate(track: MusicTrack, candidate: ReplacementCandidate) =
        playbackReplacementController.selectReplacementCandidate(track, candidate)

    private fun ReplacementCandidate.toSmartReplacementSelection(): SmartReplacementSelection {
        return SmartReplacementSelection(
            replacementId = track.id,
            replacementTitle = track.title,
            replacementArtists = track.artists,
            replacementAlbum = track.album,
            replacementSource = track.source,
            replacementProviderName = track.providerName,
            replacementCoverUrl = track.coverUrl,
            replacementDurationMs = track.durationMs,
            replacementScore = score,
        )
    }

    private fun MusicTrack.withReplacementSelection(selection: SmartReplacementSelection): MusicTrack {
        return copy(
            id = id,
            providerId = providerId ?: id,
            sourceType = TrackSourceType.Provider,
            localUri = null,
            isSmartReplacement = true,
            originalId = id,
            originalTitle = title,
            originalArtists = artists,
            originalAlbum = album,
            originalSource = source,
            originalProviderName = providerName,
            originalCoverUrl = coverUrl,
            replacementId = selection.replacementId,
            replacementTitle = selection.replacementTitle,
            replacementArtists = selection.replacementArtists,
            replacementAlbum = selection.replacementAlbum,
            replacementSource = selection.replacementSource,
            replacementProviderName = selection.replacementProviderName,
            replacementCoverUrl = selection.replacementCoverUrl,
            replacementStrategy = "user_selected",
            replacementScore = selection.replacementScore,
            isUnavailable = false,
        )
    }

    private fun MusicTrack.originalDetailTrack(): MusicTrack {
        if (!isSmartReplacement) return this
        val detailId = originalId ?: providerId ?: id
        val detailSource = originalSource
            ?: detailId.substringBefore(':').takeIf { it.isNotBlank() }
            ?: source
        return copy(
            id = detailId,
            title = originalTitle ?: title,
            artists = originalArtists ?: artists,
            album = originalAlbum ?: album,
            source = detailSource,
            sourceType = TrackSourceType.Provider,
            localUri = null,
            coverUrl = originalCoverUrl ?: coverUrl,
            providerId = detailId,
            providerName = originalProviderName ?: detailSource,
            isSmartReplacement = false,
            originalId = null,
            originalTitle = null,
            originalArtists = null,
            originalAlbum = null,
            originalSource = null,
            originalProviderName = null,
            originalCoverUrl = null,
            replacementId = null,
            replacementTitle = null,
            replacementArtists = null,
            replacementAlbum = null,
            replacementSource = null,
            replacementProviderName = null,
            replacementCoverUrl = null,
            replacementStrategy = null,
            replacementScore = null,
        )
    }

    private fun MusicTrack.replacementDetailTrack(): MusicTrack? {
        val detailId = replacementId?.takeIf { it.isNotBlank() } ?: return null
        val detailSource = replacementSource
            ?: detailId.substringBefore(':').takeIf { it.isNotBlank() }
            ?: return null
        return copy(
            id = detailId,
            title = replacementTitle ?: title,
            artists = replacementArtists ?: artists,
            album = replacementAlbum ?: album,
            source = detailSource,
            sourceType = TrackSourceType.Provider,
            localUri = null,
            coverUrl = replacementCoverUrl ?: coverUrl,
            providerId = detailId,
            providerName = replacementProviderName ?: detailSource,
            isSmartReplacement = false,
            originalId = null,
            originalTitle = null,
            originalArtists = null,
            originalAlbum = null,
            originalSource = null,
            originalProviderName = null,
            originalCoverUrl = null,
            replacementId = null,
            replacementTitle = null,
            replacementArtists = null,
            replacementAlbum = null,
            replacementSource = null,
            replacementProviderName = null,
            replacementCoverUrl = null,
            replacementStrategy = null,
            replacementScore = null,
        )
    }

    fun openSharedResource(text: String) {
        val resource = parseSharedResource(text)
        if (resource == null) {
            message = "无法识别分享链接"
            return
        }
        scope.launch {
            if (availableProviders.isEmpty()) {
                runCatching { refreshProviderCatalog() }
                    .onFailure {
                        setError(it)
                        return@launch
                    }
            }
            val knownProviderIds = availableProviders.map { it.providerId }.toSet()
            if (knownProviderIds.isNotEmpty() && resource.providerId !in knownProviderIds) {
                message = "未找到 provider：${resource.providerId}"
                return@launch
            }
            if (resource.providerId !in enabledProviderIds && resource.providerId in knownProviderIds) {
                enabledProviderIds = enabledProviderIds + resource.providerId
                persistSettings()
                runCatching {
                    providerRepository.updateEnabledProviders(enabledProviderIds)
                    refreshProviderCatalog()
                }.onFailure {
                    setError(it)
                    return@launch
                }
            }
            when (resource.type) {
                ShareResourceType.Song -> openSharedTrack(resource)
                ShareResourceType.Playlist -> openPlaylist(resource.toProviderPlaylist())
                ShareResourceType.Artist,
                ShareResourceType.Album -> openMediaItem(resource.toProviderMediaItem())
            }
        }
    }

    private suspend fun openSharedTrack(resource: ShareResourceRef) {
        val placeholder = MusicTrack(
            id = resource.toProviderTrackId(),
            title = resource.title,
            artists = resource.artists,
            album = resource.album,
            source = resource.providerId,
            sourceType = TrackSourceType.Provider,
            providerName = resource.providerName,
        )
        navigator.navigate(AppRoute.TrackDetail(placeholder.toNavigationTrack()))
        selectedTrack = placeholder
        selectedTrackError = null
        isLoading = true
        message = "正在加载：${resource.providerId}"
        runCatching { providerRepository.trackDetail(resource.toProviderTrackId()) }
            .onSuccess {
                selectedTrack = it
                selectedTrackError = null
                message = it.title.ifBlank { "歌曲已加载" }
                loadSelectedTrackRelated(it)
            }
            .onFailure {
                selectedTrackError = it.message ?: "资源加载失败"
                message = "资源加载失败"
            }
        isLoading = false
    }

    fun closeTrack() {
        navigator.pop(AppRoute.Track)
        selectedTrack = null
        selectedTrackError = null
        selectedTrackSimilar = emptyList()
        selectedTrackComments = emptyList()
        selectedTrackVideo = null
        selectedTrackRelatedError = null
    }

    private fun loadSelectedTrackRelated(track: MusicTrack) {
        scope.launch {
            val related = withTimeoutOrNull(20_000) {
                runCatching {
                    val similar = async { providerRepository.similarTracks(track) }
                    val comments = async { providerRepository.hotComments(track) }
                    val video = async { providerRepository.trackVideo(track) }
                    Triple(similar.await(), comments.await(), video.await())
                }
            }
            if (selectedTrack?.id != track.id) return@launch
            if (related == null) {
                selectedTrackRelatedError = "播放周边加载超时"
            } else {
                related
                    .onSuccess {
                        selectedTrackSimilar = it.first
                        selectedTrackComments = it.second
                        selectedTrackVideo = it.third
                        selectedTrackRelatedError = null
                    }
                    .onFailure {
                        selectedTrackRelatedError = it.message ?: it::class.simpleName.orEmpty()
                    }
            }
        }
    }

    fun openVideo(video: ProviderVideo) {
        navigator.navigate(AppRoute.VideoDetail(video.toNavigationVideo()))
        selectedVideo = video
        selectedVideoPayload = null
        selectedVideoError = null
        isVideoFullscreen = false
        scope.launch {
            isLoading = true
            message = "正在加载视频：${video.title}"
            runCatching {
                withTimeout(25_000) {
                    providerRepository.videoPlaybackPayload(video)
                }
            }.onSuccess {
                if (selectedVideo?.id == video.id) {
                    selectedVideo = it.video
                    selectedVideoPayload = it
                    selectedVideoError = null
                    message = "正在播放视频：${it.video.title}"
                }
            }.onFailure {
                selectedVideoError = it.message ?: it::class.simpleName.orEmpty()
                message = "视频加载失败"
            }
            isLoading = false
        }
    }

    fun openSelectedTrackVideo() {
        selectedTrackVideo?.let(::openVideo)
    }

    fun closeVideo() {
        navigator.pop(AppRoute.Video)
        isVideoFullscreen = false
        selectedVideo = null
        selectedVideoPayload = null
        selectedVideoError = null
    }

    fun toggleVideoFullscreen() {
        isVideoFullscreen = !isVideoFullscreen
    }

    fun canAddTrackToProviderPlaylist(track: MusicTrack): Boolean =
        playlistActionController.canAddTrackToProviderPlaylist(track)

    fun canAddTrackToLocalPlaylist(track: MusicTrack): Boolean =
        playlistActionController.canAddTrackToLocalPlaylist(track)

    fun canAddTrackToPlaylist(track: MusicTrack): Boolean =
        playlistActionController.canAddTrackToPlaylist(track)

    fun playlistProviderName(track: MusicTrack): String =
        playlistActionController.playlistProviderName(track)

    fun selectPlaylistTargetType(type: PlaylistTargetType) =
        playlistActionController.selectPlaylistTargetType(type)

    fun openLocalPlaylistTargetPicker(track: MusicTrack) =
        localPlaylistController.openTargetPicker(track)

    fun closeLocalPlaylistTargetPicker() = localPlaylistController.closeTargetPicker()

    fun createLocalPlaylist(title: String) = localPlaylistController.create(title)

    fun addTrackToLocalPlaylist(playlist: LocalPlaylist) =
        playlistActionController.addTrackToLocalPlaylist(playlist)

    fun openLocalPlaylist(playlist: LocalPlaylist) = localPlaylistController.open(playlist)

    fun closeLocalPlaylist() = localPlaylistController.close()

    fun playFromSelectedLocalPlaylist(index: Int) {
        val track = selectedLocalPlaylistTracks.getOrNull(index) ?: return
        play(track, selectedLocalPlaylistTracks, index, sourcePlaylistId = selectedLocalPlaylist?.id)
    }

    fun playAllFromSelectedLocalPlaylist() {
        if (selectedLocalPlaylistTracks.isEmpty()) return
        playFirst(selectedLocalPlaylistTracks, sourcePlaylistId = selectedLocalPlaylist?.id)
    }

    fun canRemoveTrackFromSelectedLocalPlaylist(track: MusicTrack): Boolean =
        localPlaylistController.canRemove(track)

    fun removeTrackFromSelectedLocalPlaylist(track: MusicTrack) = localPlaylistController.remove(track)

    fun canDeleteSelectedLocalPlaylist(): Boolean = localPlaylistController.canDeleteSelected()

    fun deleteSelectedLocalPlaylist() = localPlaylistController.deleteSelected()

    fun prepareLocalPlaylistImport(fileName: String, content: String) =
        localPlaylistController.prepareImport(fileName, content)

    fun existingLocalPlaylistForImport(preview: LocalPlaylistImportPreview): LocalPlaylist? =
        localPlaylistController.existingForImport(preview)

    fun cancelLocalPlaylistImport() = localPlaylistController.cancelImport()

    fun importLocalPlaylist(
        mode: LocalPlaylistImportMode,
        replacePlaylistId: String? = null,
    ) = localPlaylistController.importPlaylist(mode, replacePlaylistId)

    fun exportSelectedLocalPlaylist(onReady: (LocalPlaylistFile) -> Unit) =
        localPlaylistController.exportSelected(onReady)

    fun creatablePlaylistProviders(): List<ProviderInfo> = providers.filter { provider ->
        isProviderLoggedIn(provider.providerId) &&
            providerCapabilities[provider.providerId]?.canCreatePlaylist == true
    }

    fun createPlaylist(providerId: String, name: String) {
        if (name.isBlank() || providerId !in creatablePlaylistProviders().map { it.providerId }) return
        scope.launch {
            isLoading = true
            message = "正在新建歌单"
            runCatching { providerRepository.createPlaylist(providerId, name.trim()) }
                .onSuccess { result ->
                    message = result.message.ifBlank { if (result.success) "歌单已新建" else "新建歌单失败" }
                    playlistOperationFeedback = message
                    if (result.success) refreshAfterProviderMutation(providerId)
                }
                .onFailure {
                    setError(it)
                    playlistOperationFeedback = message
                }
            isLoading = false
        }
    }

    fun canDeleteSelectedPlaylist(): Boolean {
        val playlist = selectedPlaylist ?: return false
        return selectedPlaylistCategory == ProviderFeatureCategory.MinePlaylists &&
            isProviderLoggedIn(playlist.providerId) &&
            providerCapabilities[playlist.providerId]?.canDeletePlaylist == true
    }

    fun deleteSelectedPlaylist() {
        val playlist = selectedPlaylist ?: return
        if (!canDeleteSelectedPlaylist()) return
        scope.launch {
            isLoading = true
            message = "正在删除歌单"
            runCatching { providerRepository.deletePlaylist(playlist) }
                .onSuccess { result ->
                    message = result.message.ifBlank { if (result.success) "歌单已删除" else "删除歌单失败" }
                    playlistOperationFeedback = message
                    if (result.success) {
                        closePlaylist()
                        refreshAfterProviderMutation(playlist.providerId)
                    }
                }
                .onFailure {
                    setError(it)
                    playlistOperationFeedback = message
                }
            isLoading = false
        }
    }

    fun openPlaylistTargetPicker(track: MusicTrack) =
        playlistActionController.openPlaylistTargetPicker(track)

    fun closePlaylistTargetPicker() = playlistActionController.closePlaylistTargetPicker()

    fun addTrackToProviderPlaylist(playlist: ProviderPlaylist) =
        playlistActionController.addTrackToProviderPlaylist(playlist)

    fun canRemoveTrackFromSelectedPlaylist(track: MusicTrack): Boolean =
        playlistActionController.canRemoveTrackFromSelectedPlaylist(track)

    fun canSetSongDisliked(track: MusicTrack, disliked: Boolean): Boolean =
        providerTrackActionController.canSetSongDisliked(track, disliked)

    fun setSongDisliked(track: MusicTrack, disliked: Boolean) =
        providerTrackActionController.setSongDisliked(track, disliked)

    private fun removeDislikedTrack(track: MusicTrack) {
        val isCurrent = currentQueueTrack()?.id == track.id
        if (isCurrent) {
            val hasNext = upNextQueue.isNotEmpty() || mainQueueIndex + 1 < mainQueue.size
            if (hasNext) next() else playbackEngine.stop()
        }
        val removedBeforeCurrent = mainQueue
            .take(mainQueueIndex.coerceIn(0, mainQueue.size))
            .count { it.id == track.id }
        mainQueue = mainQueue.filterNot { it.id == track.id }
        originalMainQueue = originalMainQueue.filterNot { it.id == track.id }
        upNextQueue = upNextQueue.filterNot { it.id == track.id }
        mainQueueIndex = (mainQueueIndex - removedBeforeCurrent).coerceIn(-1, mainQueue.lastIndex)
        recommendSections = recommendSections.withoutTrack(track.id)
        musicSections = musicSections.withoutTrack(track.id)
        mineSections = mineSections.withoutTrack(track.id)
        selectedFeatureTracks = selectedFeatureTracks.filterNot { it.id == track.id }
        selectedFeatureContent = selectedFeatureContent?.copy(
            tracks = selectedFeatureContent.orEmptyTracks().filterNot { it.id == track.id },
        )
        selectedPlaylistTracks = selectedPlaylistTracks.filterNot { it.id == track.id }
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    fun removeTrackFromSelectedPlaylist(track: MusicTrack) =
        playlistActionController.removeTrackFromSelectedPlaylist(track)

    fun dismissPlaylistOperationFeedback(feedback: String) {
        if (playlistOperationFeedback == feedback) {
            playlistOperationFeedback = null
        }
    }

    fun openPlaylist(playlist: ProviderPlaylist, category: ProviderFeatureCategory? = null) {
        navigator.navigate(
            AppRoute.PlaylistDetail(
                playlist = playlist.toNavigationPlaylist(),
                category = category?.name,
            )
        )
        selectedPlaylistBackgroundLoadJob?.cancel()
        selectedPlaylist = playlist
        selectedPlaylistCategory = category
        selectedPlaylistTracks = emptyList()
        selectedPlaylistTracksNextOffset = 0
        selectedPlaylistTracksHasMore = false
        selectedPlaylistLoadMoreJob = null
        selectedPlaylistBackgroundLoadJob = null
        selectedPlaylistError = null
        scope.launch {
            isLoading = true
            message = "正在加载：${playlist.title}"
            val deferred = scope.async { providerRepository.playlistDetailPage(playlist, offset = 0) }
            val result = withTimeoutOrNull(30_000) {
                runCatching { deferred.await() }
            }
            if (result == null) {
                deferred.cancel()
                selectedPlaylistError = "加载超时，请检查网络后重试"
                message = selectedPlaylistError.orEmpty()
            } else {
                result.onSuccess { detail ->
                    if (selectedPlaylist?.id == playlist.id) {
                        selectedPlaylist = detail.playlist
                        selectedPlaylistTracks = detail.tracks
                        selectedPlaylistTracksNextOffset = detail.tracksNextOffset
                        selectedPlaylistTracksHasMore = detail.tracksHasMore
                        selectedPlaylistError = null
                        message = if (detail.tracks.isEmpty()) {
                            "歌单暂无歌曲"
                        } else {
                            "${detail.playlist.title} · ${detail.tracks.size} 首"
                        }
                    }
                }.onFailure {
                    selectedPlaylistError = it.message ?: it::class.simpleName.orEmpty()
                    setError(it)
                }
            }
            isLoading = false
        }
    }

    fun closePlaylist() {
        navigator.pop(AppRoute.Playlist)
        selectedPlaylistBackgroundLoadJob?.cancel()
        selectedPlaylist = null
        selectedPlaylistCategory = null
        selectedPlaylistTracks = emptyList()
        selectedPlaylistTracksNextOffset = 0
        selectedPlaylistTracksHasMore = false
        selectedPlaylistLoadMoreJob = null
        selectedPlaylistBackgroundLoadJob = null
        selectedPlaylistError = null
    }

    fun openMediaItem(item: ProviderMediaItem) {
        navigator.navigate(AppRoute.MediaItemDetail(item.toNavigationMediaItem()))
        selectedMediaItem = item
        selectedMediaItemTracks = emptyList()
        selectedMediaItemTracksNextOffset = 0
        selectedMediaItemTracksHasMore = false
        selectedMediaItemAlbums = emptyList()
        selectedMediaItemAlbumsNextOffset = 0
        selectedMediaItemAlbumsHasMore = false
        selectedMediaItemTracksLoadMoreJob = null
        selectedMediaItemAlbumsLoadMoreJob = null
        selectedMediaItemError = null
        scope.launch {
            isLoading = true
            message = "正在加载：${item.title}"
            val deferred = scope.async {
                providerRepository.mediaItemDetailPage(item, tracksOffset = 0, albumsOffset = 0)
            }
            val result = withTimeoutOrNull(30_000) {
                runCatching { deferred.await() }
            }
            if (result == null) {
                deferred.cancel()
                selectedMediaItemError = "加载超时，请检查网络后重试"
                message = selectedMediaItemError.orEmpty()
            } else {
                result.onSuccess { detail ->
                    if (selectedMediaItem?.id == item.id) {
                        selectedMediaItem = detail.item
                        selectedMediaItemTracks = detail.tracks
                        selectedMediaItemTracksNextOffset = detail.tracksNextOffset
                        selectedMediaItemTracksHasMore = detail.tracksHasMore
                        selectedMediaItemAlbums = detail.albums
                        selectedMediaItemAlbumsNextOffset = detail.albumsNextOffset
                        selectedMediaItemAlbumsHasMore = detail.albumsHasMore
                        selectedMediaItemError = null
                        val loadedParts = buildList {
                            if (detail.tracks.isNotEmpty()) add("${detail.tracks.size} 首")
                            if (detail.albums.isNotEmpty()) add("${detail.albums.size} 张专辑")
                        }.joinToString(" · ")
                        message = loadedParts.ifBlank { "${detail.item.title} 暂无内容" }
                    }
                }.onFailure {
                    selectedMediaItemError = it.message ?: it::class.simpleName.orEmpty()
                    setError(it)
                }
            }
            isLoading = false
        }
    }

    fun closeMediaItem() {
        navigator.pop(AppRoute.MediaItem)
        selectedMediaItem = null
        selectedMediaItemTracks = emptyList()
        selectedMediaItemTracksNextOffset = 0
        selectedMediaItemTracksHasMore = false
        selectedMediaItemAlbums = emptyList()
        selectedMediaItemAlbumsNextOffset = 0
        selectedMediaItemAlbumsHasMore = false
        selectedMediaItemTracksLoadMoreJob = null
        selectedMediaItemAlbumsLoadMoreJob = null
        selectedMediaItemError = null
    }

    fun prefetchSelectedFeatureIfNeeded(visibleIndex: Int) {
        val contentCount = selectedFeatureContent?.contentCount() ?: selectedFeatureTracks.size
        if (contentCount - visibleIndex <= LIST_PREFETCH_REMAINING) {
            loadMoreSelectedFeatureTracks()
        }
    }

    fun loadMoreSelectedFeature() {
        loadMoreSelectedFeatureTracks()
    }

    fun prefetchSelectedPlaylistIfNeeded(visibleIndex: Int) {
        if (selectedPlaylistTracks.size - visibleIndex <= LIST_PREFETCH_REMAINING) {
            loadMoreSelectedPlaylistTracks()
        }
    }

    fun prefetchSelectedMediaItemTracksIfNeeded(visibleIndex: Int) {
        if (selectedMediaItemTracks.size - visibleIndex <= LIST_PREFETCH_REMAINING) {
            loadMoreSelectedMediaItemTracks()
        }
    }

    fun prefetchSelectedMediaItemAlbumsIfNeeded(visibleIndex: Int) {
        if (selectedMediaItemAlbums.size - visibleIndex <= LIST_PREFETCH_REMAINING) {
            loadMoreSelectedMediaItemAlbums()
        }
    }

    private fun loadMoreSelectedFeatureTracks() {
        val feature = selectedFeature ?: return
        if (feature.isDynamicQueueFeature() || !selectedFeatureTracksHasMore) return
        if (selectedFeatureLoadMoreJob?.isActive == true) return
        selectedFeatureLoadMoreJob = scope.launch {
            appendSelectedFeatureTracksPage()
        }
    }

    private suspend fun appendSelectedFeatureTracksPage(): Boolean {
        val feature = selectedFeature ?: return false
        if (feature.isDynamicQueueFeature() || !selectedFeatureTracksHasMore) return false
        val offset = selectedFeatureTracksNextOffset
        return runCatching {
            withTimeout(30_000) {
                providerRepository.loadFeaturePage(feature, offset)
            }
        }.fold(
            onSuccess = { section ->
                if (selectedFeature != feature) return false
                val current = selectedFeatureContent ?: ProviderContentSection(feature)
                val seenTrackIds = current.tracks.mapTo(mutableSetOf()) { it.id }
                val newTracks = section.tracks.filter { seenTrackIds.add(it.id) }
                val seenPlaylistIds = current.playlists.mapTo(mutableSetOf()) { it.id }
                val newPlaylists = section.playlists.filter { seenPlaylistIds.add(it.id) }
                val seenMediaItemIds = current.mediaItems.mapTo(mutableSetOf()) { it.id }
                val newMediaItems = section.mediaItems.filter { seenMediaItemIds.add(it.id) }
                val seenVideoIds = current.videos.mapTo(mutableSetOf()) { it.id }
                val newVideos = section.videos.filter { seenVideoIds.add(it.id) }

                val updatedSection = section.copy(
                    tracks = current.tracks + newTracks,
                    playlists = current.playlists + newPlaylists,
                    mediaItems = current.mediaItems + newMediaItems,
                    videos = current.videos + newVideos,
                    nextOffset = section.nextOffset,
                    hasMore = section.hasMore,
                )
                selectedFeatureTracks = updatedSection.tracks
                selectedFeatureContent = updatedSection
                updateHomeFeatureSection(updatedSection)
                selectedFeatureTracksNextOffset = section.nextOffset
                selectedFeatureTracksHasMore = section.hasMore
                val contentChanged = newTracks.isNotEmpty() ||
                    newPlaylists.isNotEmpty() ||
                    newMediaItems.isNotEmpty() ||
                    newVideos.isNotEmpty()
                section.nextOffset != offset || contentChanged
            },
            onFailure = {
                selectedFeatureError = it.message ?: it::class.simpleName.orEmpty()
                false
            },
        )
    }

    private fun loadMoreSelectedPlaylistTracks() {
        if (selectedPlaylist == null || !selectedPlaylistTracksHasMore) return
        if (selectedPlaylistBackgroundLoadJob?.isActive == true) return
        if (selectedPlaylistLoadMoreJob?.isActive == true) return
        selectedPlaylistLoadMoreJob = scope.launch {
            appendSelectedPlaylistTracksPage()
        }
    }

    private suspend fun appendSelectedPlaylistTracksPage(): Boolean {
        val playlist = selectedPlaylist ?: return false
        if (!selectedPlaylistTracksHasMore) return false
        val offset = selectedPlaylistTracksNextOffset
        return runCatching {
            withTimeout(30_000) {
                providerRepository.playlistDetailPage(playlist, offset)
            }
        }.fold(
            onSuccess = { detail ->
                if (selectedPlaylist?.id != playlist.id) return false
                val seenIds = selectedPlaylistTracks.mapTo(mutableSetOf()) { it.id }
                val newTracks = detail.tracks.filter { seenIds.add(it.id) }
                selectedPlaylist = detail.playlist
                if (newTracks.isNotEmpty()) {
                    selectedPlaylistTracks = selectedPlaylistTracks + newTracks
                }
                selectedPlaylistTracksNextOffset = detail.tracksNextOffset
                selectedPlaylistTracksHasMore = detail.tracksHasMore
                selectedPlaylistError = null
                detail.tracksNextOffset != offset || newTracks.isNotEmpty()
            },
            onFailure = {
                selectedPlaylistError = it.message ?: it::class.simpleName.orEmpty()
                false
            },
        )
    }

    private fun loadMoreSelectedMediaItemTracks() {
        if (selectedMediaItem == null || !selectedMediaItemTracksHasMore) return
        if (selectedMediaItemTracksLoadMoreJob?.isActive == true) return
        selectedMediaItemTracksLoadMoreJob = scope.launch {
            appendSelectedMediaItemTracksPage()
        }
    }

    private suspend fun appendSelectedMediaItemTracksPage(): Boolean {
        val item = selectedMediaItem ?: return false
        if (!selectedMediaItemTracksHasMore) return false
        val offset = selectedMediaItemTracksNextOffset
        return runCatching {
            withTimeout(30_000) {
                providerRepository.mediaItemDetailPage(item, offset, selectedMediaItemAlbumsNextOffset)
            }
        }.fold(
            onSuccess = { detail ->
                if (selectedMediaItem?.id != item.id) return false
                val seenIds = selectedMediaItemTracks.mapTo(mutableSetOf()) { it.id }
                val newTracks = detail.tracks.filter { seenIds.add(it.id) }
                selectedMediaItem = detail.item
                if (newTracks.isNotEmpty()) {
                    selectedMediaItemTracks = selectedMediaItemTracks + newTracks
                }
                selectedMediaItemTracksNextOffset = detail.tracksNextOffset
                selectedMediaItemTracksHasMore = detail.tracksHasMore
                selectedMediaItemError = null
                detail.tracksNextOffset != offset || newTracks.isNotEmpty()
            },
            onFailure = {
                selectedMediaItemError = it.message ?: it::class.simpleName.orEmpty()
                false
            },
        )
    }

    private fun loadMoreSelectedMediaItemAlbums() {
        if (selectedMediaItem == null || !selectedMediaItemAlbumsHasMore) return
        if (selectedMediaItemAlbumsLoadMoreJob?.isActive == true) return
        selectedMediaItemAlbumsLoadMoreJob = scope.launch {
            appendSelectedMediaItemAlbumsPage()
        }
    }

    private suspend fun appendSelectedMediaItemAlbumsPage(): Boolean {
        val item = selectedMediaItem ?: return false
        if (!selectedMediaItemAlbumsHasMore) return false
        val offset = selectedMediaItemAlbumsNextOffset
        return runCatching {
            withTimeout(30_000) {
                providerRepository.mediaItemDetailPage(item, selectedMediaItemTracksNextOffset, offset)
            }
        }.fold(
            onSuccess = { detail ->
                if (selectedMediaItem?.id != item.id) return false
                val seenIds = selectedMediaItemAlbums.mapTo(mutableSetOf()) { it.id }
                val newAlbums = detail.albums.filter { seenIds.add(it.id) }
                selectedMediaItem = detail.item
                if (newAlbums.isNotEmpty()) {
                    selectedMediaItemAlbums = selectedMediaItemAlbums + newAlbums
                }
                selectedMediaItemAlbumsNextOffset = detail.albumsNextOffset
                selectedMediaItemAlbumsHasMore = detail.albumsHasMore
                selectedMediaItemError = null
                detail.albumsNextOffset != offset || newAlbums.isNotEmpty()
            },
            onFailure = {
                selectedMediaItemError = it.message ?: it::class.simpleName.orEmpty()
                false
            },
        )
    }

    private suspend fun ensureAllSelectedFeatureTracks() {
        selectedFeatureLoadMoreJob?.join()
        while (selectedFeatureTracksHasMore) {
            if (!appendSelectedFeatureTracksPage()) break
        }
    }

    private suspend fun ensureAllSelectedMediaItemTracks() {
        selectedMediaItemTracksLoadMoreJob?.join()
        while (selectedMediaItemTracksHasMore) {
            if (!appendSelectedMediaItemTracksPage()) break
        }
    }

    private suspend fun loadCompleteFeatureSection(section: ProviderContentSection): ProviderContentSection {
        var nextOffset = section.nextOffset
        var hasMore = section.hasMore
        var currentSection = section
        var tracks = section.tracks
        val seenIds = tracks.mapTo(mutableSetOf()) { it.id }
        while (hasMore) {
            val pageResult = runCatching {
                withTimeout(30_000) {
                    providerRepository.loadFeaturePage(section.feature, nextOffset)
                }
            }
            if (pageResult.isFailure) {
                setError(pageResult.exceptionOrNull() ?: RuntimeException("加载失败"))
                break
            }
            val page = pageResult.getOrThrow()
            val newTracks = page.tracks.filter { seenIds.add(it.id) }
            if (newTracks.isNotEmpty()) {
                tracks = tracks + newTracks
            }
            val progressed = page.nextOffset != nextOffset || newTracks.isNotEmpty()
            nextOffset = page.nextOffset
            hasMore = page.hasMore
            currentSection = page
            if (!progressed) break
        }
        return currentSection.copy(
            tracks = tracks,
            nextOffset = nextOffset,
            hasMore = hasMore,
        )
    }

    fun playFromLocal(index: Int) {
        val track = localTracks.getOrNull(index) ?: return
        play(track, localTracks, index)
    }

    fun playLocalTrack(track: MusicTrack, sourceQueue: List<MusicTrack>) {
        val index = sourceQueue.indexOfFirst { it.id == track.id }
        if (index >= 0) play(track, sourceQueue, index)
    }

    fun playAllLocalTracks(sourceQueue: List<MusicTrack>) {
        playFirst(sourceQueue)
    }

    fun playFromSearch(index: Int) {
        val track = searchResults.getOrNull(index) ?: return
        play(track, searchResults, index)
        closeSearch()
    }

    fun playFromFeature(featureId: String, index: Int) {
        val section = (recommendSections + musicSections + minePlaylistSections + mineFavoritePlaylistSections + mineSections)
            .firstOrNull { it.feature.id == featureId }
        val tracks = section?.tracks.orEmpty()
        val track = tracks.getOrNull(index) ?: return
        play(track, tracks, index, section?.feature?.takeIf { it.isDynamicQueueFeature() })
    }

    fun playAllFromFeature(featureId: String) {
        val section = (recommendSections + musicSections + minePlaylistSections + mineFavoritePlaylistSections + mineSections)
            .firstOrNull { it.feature.id == featureId }
        val feature = section?.feature ?: return
        if (section.tracks.isEmpty() && feature.isDynamicQueueFeature()) {
            loadFeatureAndPlayAll(feature)
        } else if (feature.isDynamicQueueFeature()) {
            playFirst(section.tracks, feature)
        } else {
            scope.launch {
                isLoading = true
                message = "正在加载完整列表：${feature.title}"
                val completeSection = loadCompleteFeatureSection(section)
                updateHomeFeatureSection(completeSection)
                playFirst(completeSection.tracks)
                isLoading = false
            }
        }
    }

    fun playFromSelectedPlaylist(index: Int) {
        if (selectedPlaylistTracks.getOrNull(index) == null) return
        playSelectedPlaylistFrom(index)
    }

    fun playAllFromSelectedPlaylist() {
        playSelectedPlaylistFrom(0)
    }

    private fun playSelectedPlaylistFrom(index: Int) {
        val playlist = selectedPlaylist ?: return
        val loadedTracks = selectedPlaylistTracks
        val track = loadedTracks.getOrNull(index) ?: return
        selectedPlaylistBackgroundLoadJob?.cancel()
        recordPlaylistPlayback(playlist)
        play(track, loadedTracks, index, sourcePlaylistId = playlist.id)
        if (selectedPlaylistTracksHasMore && queuePlaylistId == playlist.id) {
            selectedPlaylistBackgroundLoadJob = scope.launch {
                val queuedTrackIds = loadedTracks.mapTo(mutableSetOf()) { it.id }
                while (
                    selectedPlaylistTracksHasMore &&
                    queuePlaylistId == playlist.id &&
                    selectedPlaylist?.id == playlist.id
                ) {
                    delay(PLAYLIST_BACKGROUND_PAGE_INTERVAL_MS)
                    selectedPlaylistLoadMoreJob?.join()
                    if (queuePlaylistId != playlist.id || selectedPlaylist?.id != playlist.id) break
                    val progressed = appendSelectedPlaylistTracksPage()
                    val newTracks = selectedPlaylistTracks.filter { queuedTrackIds.add(it.id) }
                    appendPlaylistPlaybackQueue(playlist, newTracks)
                    if (!progressed) break
                }
                if (!selectedPlaylistTracksHasMore) {
                    reshuffleCompletedPlaylistQueue(playlist)
                }
            }
        }
    }

    fun sortedMinePlaylists(playlists: List<ProviderPlaylist>): List<ProviderPlaylist> =
        playlists.withIndex().sortedWith(
            compareByDescending<IndexedValue<ProviderPlaylist>> {
                playlistPlaybackStats[it.value.playbackStatsKey()]?.lastPlayedAtMillis ?: 0
            }.thenBy { it.index },
        ).map { it.value }

    fun frequentlyPlayedPlaylists(): List<ProviderPlaylist> {
        val playlists = (minePlaylistSections + mineFavoritePlaylistSections)
            .filterNot { it.isLoginRequired }
            .flatMap { it.playlists }
            .distinctBy { it.playbackStatsKey() }
        return playlists.sortedWith(
            compareByDescending<ProviderPlaylist> {
                playlistPlaybackStats[it.playbackStatsKey()]?.playCount ?: 0
            }.thenByDescending {
                playlistPlaybackStats[it.playbackStatsKey()]?.lastPlayedAtMillis ?: 0
            },
        ).filter { (playlistPlaybackStats[it.playbackStatsKey()]?.playCount ?: 0) > 0 }
    }

    fun categoryForMinePlaylist(playlist: ProviderPlaylist): ProviderFeatureCategory =
        if (mineFavoritePlaylistSections.any { section ->
                section.playlists.any { it.playbackStatsKey() == playlist.playbackStatsKey() }
            }
        ) ProviderFeatureCategory.MineFavoritePlaylists else ProviderFeatureCategory.MinePlaylists

    private fun recordPlaylistPlayback(playlist: ProviderPlaylist) {
        val key = playlist.playbackStatsKey()
        val previous = playlistPlaybackStats[key] ?: PlaylistPlaybackStat()
        playlistPlaybackStats = (playlistPlaybackStats + (
            key to previous.copy(
                playCount = previous.playCount + 1,
                lastPlayedAtMillis = currentTimeMillis(),
            )
        )).entries
            .sortedByDescending { it.value.lastPlayedAtMillis }
            .take(MAX_PLAYLIST_PLAYBACK_STATS)
            .associate { it.toPair() }
        persistSettings()
    }

    fun playFromSelectedFeature(index: Int) {
        val track = selectedFeatureTracks.getOrNull(index) ?: return
        play(track, selectedFeatureTracks, index, selectedFeature?.takeIf { it.isDynamicQueueFeature() })
    }

    fun playAllFromSelectedFeature() {
        val feature = selectedFeature ?: return
        if (feature.isDynamicQueueFeature()) {
            playFirst(selectedFeatureTracks, feature)
            return
        }
        scope.launch {
            isLoading = true
            message = "正在加载完整列表：${feature.title}"
            ensureAllSelectedFeatureTracks()
            playFirst(selectedFeatureTracks)
            isLoading = false
        }
    }

    fun playFromSelectedMediaItem(index: Int) {
        val track = selectedMediaItemTracks.getOrNull(index) ?: return
        play(track, selectedMediaItemTracks, index)
    }

    fun playAllFromSelectedMediaItem() {
        val item = selectedMediaItem ?: return
        scope.launch {
            isLoading = true
            message = "正在加载完整列表：${item.title}"
            ensureAllSelectedMediaItemTracks()
            playFirst(selectedMediaItemTracks)
            isLoading = false
        }
    }

    fun playSelectedTrack() {
        val track = selectedTrack ?: return
        play(track, listOf(track), 0)
    }

    fun playSelectedTrackSimilar(index: Int) {
        val track = selectedTrackSimilar.getOrNull(index) ?: return
        play(track, selectedTrackSimilar, index)
    }

    fun playQueueIndex(index: Int) = playbackQueueCoordinator.playQueueIndex(index)

    fun playPlaybackPart(index: Int) = playbackQueueCoordinator.playPlaybackPart(index)

    fun toggle() {
        when (playbackState.status) {
            PlayerStatus.Playing -> playbackEngine.pause()
            PlayerStatus.Paused -> {
                if (playbackState.currentTrack != null) playbackEngine.resume()
            }
            PlayerStatus.Idle, PlayerStatus.Ended, PlayerStatus.Loading, PlayerStatus.Error -> {
                playbackQueueCoordinator.startCurrent()
            }
        }
    }

    fun next() = playbackQueueCoordinator.next()

    fun previous() = playbackQueueCoordinator.previous()

    fun seekTo(positionMs: Long) {
        val normalizedPosition = positionMs.coerceAtLeast(0).let { position ->
            playbackState.durationMs.takeIf { it > 0 }?.let(position::coerceAtMost) ?: position
        }
        playbackEngine.seekTo(normalizedPosition)
    }

    fun openFullPlayer() = playbackNavigationController.openFullPlayer()

    fun closeFullPlayer() = playbackNavigationController.closeFullPlayer()

    fun toggleQueue() = playbackNavigationController.toggleQueue()

    fun removeFromQueue(track: MusicTrack) {
        if (currentUpNextTrack?.id == track.id) {
            currentUpNextTrack = null
            currentIsUpNext = false
            updatePlaybackQueueState()
            persistPlaybackQueue()
            return
        }
        val upNextIndex = upNextQueue.indexOfFirst { it.id == track.id }
        if (upNextIndex >= 0) {
            upNextQueue = upNextQueue.filterIndexed { index, _ -> index != upNextIndex }
            updatePlaybackQueueState()
            persistPlaybackQueue()
            return
        }
        val mainIndex = mainQueue.indexOfFirst { it.id == track.id }
        if (mainIndex < 0) return
        mainQueue = mainQueue.filterIndexed { index, _ -> index != mainIndex }
        originalMainQueue = originalMainQueue.filterNot { it.id == track.id }
        mainQueueIndex = when {
            mainQueue.isEmpty() -> -1
            mainIndex < mainQueueIndex -> mainQueueIndex - 1
            mainIndex == mainQueueIndex -> mainQueueIndex.coerceAtMost(mainQueue.lastIndex)
            else -> mainQueueIndex
        }
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    fun clearQueue() {
        val currentTrack = currentQueueTrack()
        mainQueue = emptyList()
        originalMainQueue = emptyList()
        upNextQueue = emptyList()
        currentUpNextTrack = null
        currentIsUpNext = false
        mainQueueIndex = -1
        queueFeature = null
        queuePlaylistId = null
        isFmQueue = false
        shuffleBeforeFm = null
        if (currentTrack != null) {
            mainQueue = listOf(currentTrack)
            mainQueueIndex = 0
        }
        updatePlaybackQueueState()
        persistPlaybackQueue()
        message = if (currentTrack != null) "已清空播放队列" else "播放队列已清空"
    }

    fun addToUpNext(track: MusicTrack) {
        upNextQueue = upNextQueue + track
        message = "已加入接下来播放：${track.title}"
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    fun toggleShuffle() {
        if (isFmQueue) return
        if (shuffleEnabled) {
            disableShuffle()
        } else {
            enableShuffle()
        }
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    fun toggleRepeat() {
        if (isFmQueue) return
        _repeatMode = when (_repeatMode) {
            RepeatMode.OFF -> RepeatMode.QUEUE
            RepeatMode.QUEUE -> RepeatMode.SINGLE
            RepeatMode.SINGLE -> RepeatMode.OFF
        }
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    fun download(track: MusicTrack) = downloadController.download(track)

    fun pauseDownload(taskId: String) = downloadController.pause(taskId)

    fun resumeDownload(taskId: String) = downloadController.resume(taskId)

    fun retryDownload(taskId: String) = downloadController.retry(taskId)

    fun deleteDownloadTask(taskId: String, deleteFile: Boolean) =
        downloadController.deleteTask(taskId, deleteFile)

    fun deleteDownload(track: MusicTrack) = downloadController.deleteDownload(track)

    fun openTrackArtist(track: MusicTrack) = providerTrackActionController.openTrackArtist(track)

    fun closeArtistTargetPicker() = providerTrackActionController.closeArtistTargetPicker()

    fun openArtistTarget(target: TrackArtistTarget) = providerTrackActionController.openArtistTarget(target)

    fun openTrackAlbum(track: MusicTrack) = providerTrackActionController.openTrackAlbum(track)

    private suspend fun refreshProviderCatalog() {
        val loadedAvailableProviders = providerRepository.availableProviders()
        val availableProviderIds = loadedAvailableProviders.map { it.providerId }.toSet()
        val normalizedProviderOrderIds = normalizedProviderOrder(availableProviderIds)
        if (normalizedProviderOrderIds != providerOrderIds) {
            providerOrderIds = normalizedProviderOrderIds
            persistSettings()
        }
        availableProviders = loadedAvailableProviders.sortedProvidersByOrder()
        if (availableProviderIds.isNotEmpty()) {
            val normalizedEnabledProviderIds = enabledProviderIds.intersect(availableProviderIds)
                .ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS.intersect(availableProviderIds) }
                .ifEmpty { setOf(loadedAvailableProviders.first().providerId) }
            if (normalizedEnabledProviderIds != enabledProviderIds) {
                enabledProviderIds = normalizedEnabledProviderIds
                persistSettings()
                providerRepository.updateEnabledProviders(enabledProviderIds)
            }
        }
        val loadedProviders = providerRepository.providers().sortedProvidersByOrder()
        providers = loadedProviders
        localPlaylistController.refreshTrackPresentation()
        val providerIds = loadedProviders.map { it.providerId }.toSet()
        if (selectedSettingsProviderId !in providerIds) {
            selectedSettingsProviderId = loadedProviders.firstOrNull()?.providerId
        }
        if (settingsLoginProviderId !in providerIds) {
            settingsLoginProviderId = null
        }
        searchController.normalizeProviderSelection(providerIds)
        providerSessionRepository.updateProviders(loadedProviders)
        providerCapabilities = providerRepository.providerCapabilities()
            .associateBy { it.providerId }
        providerFeatures = providerRepository.features().sortedFeaturesByOrder()
        refreshProviderAuthStates()
    }

    private fun refreshAfterProviderMutation(providerId: String) {
        val knownPlaylistSections = minePlaylistSections + mineFavoritePlaylistSections
        if (
            homeSection == HomeSection.Mine && mineSection == MineSection.Playlists ||
            knownPlaylistSections.any { it.feature.providerId == providerId }
        ) {
            refreshMinePlaylistContent()
        }
    }

    private fun clearProviderContent() {
        navigator.remove(
            setOf(
                AppRoute.Feature,
                AppRoute.Track,
                AppRoute.Video,
                AppRoute.Playlist,
                AppRoute.MediaItem,
            ),
        )
        selectedPlaylistBackgroundLoadJob?.cancel()
        recommendSections = emptyList()
        musicSections = emptyList()
        mineSections = emptyList()
        minePlaylistSections = emptyList()
        mineFavoritePlaylistSections = emptyList()
        selectedFeature = null
        selectedFeatureContent = null
        selectedTrack = null
        selectedPlaylist = null
        selectedPlaylistCategory = null
        selectedMediaItem = null
        selectedFeatureTracks = emptyList()
        selectedFeatureTracksNextOffset = 0
        selectedFeatureTracksHasMore = false
        selectedFeatureLoadMoreJob = null
        selectedTrackError = null
        selectedPlaylistTracks = emptyList()
        selectedPlaylistTracksNextOffset = 0
        selectedPlaylistTracksHasMore = false
        selectedPlaylistLoadMoreJob = null
        selectedPlaylistBackgroundLoadJob = null
        selectedMediaItemTracks = emptyList()
        selectedMediaItemTracksNextOffset = 0
        selectedMediaItemTracksHasMore = false
        selectedMediaItemAlbums = emptyList()
        selectedMediaItemAlbumsNextOffset = 0
        selectedMediaItemAlbumsHasMore = false
        selectedMediaItemTracksLoadMoreJob = null
        selectedMediaItemAlbumsLoadMoreJob = null
        closePlaylistTargetPicker()
        closeLocalPlaylistTargetPicker()
        closeArtistTargetPicker()
    }

    private fun reorderProviderContent() {
        recommendSections = recommendSections.sortedSectionsByOrder()
        musicSections = musicSections.sortedSectionsByOrder()
        mineSections = mineSections.sortedSectionsByOrder()
        minePlaylistSections = minePlaylistSections.sortedSectionsByOrder()
        mineFavoritePlaylistSections = mineFavoritePlaylistSections.sortedSectionsByOrder()
    }

    private fun refreshAllProviderAuthStates(refreshUserInfo: Boolean = false) =
        providerAuthController.refreshAll(providers, refreshUserInfo)

    private suspend fun refreshProviderAuthStates(refreshUserInfo: Boolean = false) =
        providerAuthController.refresh(providers, refreshUserInfo)

    private fun isProviderLoggedIn(providerId: String): Boolean =
        providerAuthController.isLoggedIn(providerId)

    private fun MusicTrack.toLocalPlaylistTrack(): LocalPlaylistTrack? {
        if (!isProviderBacked()) return null
        val providerId = trackProviderId(this) ?: return null
        val rawId = this.providerId?.takeIf { it.isNotBlank() } ?: id
        val identifier = rawId.removePrefix("$providerId:").trim()
        if (
            !providerId.matches(Regex("[A-Za-z0-9_]+")) ||
                !identifier.matches(Regex("[A-Za-z0-9_-]+"))
        ) {
            return null
        }
        return LocalPlaylistTrack(
            uri = LocalPlaylistFileCodec.normalizeSongUri(providerId, identifier),
            providerId = providerId,
            identifier = identifier,
            title = title,
            artists = artists,
            album = album,
            durationMs = durationMs,
        )
    }

    private fun MusicTrack.isProviderBacked(): Boolean {
        return sourceType == TrackSourceType.Provider || sourceType == TrackSourceType.Downloaded
    }

    private fun LocalPlaylist.toMusicTracks(): List<MusicTrack> {
        val knownProviders = providers.associateBy { it.providerId }
        return tracks.map { localTrack ->
            val provider = knownProviders[localTrack.providerId]
            val trackId = "${localTrack.providerId}:${localTrack.identifier}"
            MusicTrack(
                id = trackId,
                title = localTrack.title.ifBlank { localTrack.identifier },
                artists = localTrack.artists,
                album = localTrack.album,
                source = localTrack.providerId,
                sourceType = TrackSourceType.Provider,
                durationMs = localTrack.durationMs,
                providerId = trackId,
                providerName = provider?.providerName ?: localTrack.providerId,
                isUnavailable = provider == null,
            )
        }
    }

    private fun trackProviderId(track: MusicTrack): String? {
        return track.source.takeIf { it.isNotBlank() }
            ?: track.providerId?.substringBefore(":")?.takeIf { it.isNotBlank() }
    }

    private fun play(
        track: MusicTrack,
        sourceQueue: List<MusicTrack>,
        index: Int,
        sourceFeature: ProviderFeature? = null,
        sourcePlaylistId: String? = null,
        skippedUnavailableCount: Int = 0,
    ) {
        trackChangeDirection = TrackChangeDirection.Next
        var playbackIndex = index
        if (skippedUnavailableCount == 0) {
            playbackIndex = replaceMainQueue(
                sourceQueue,
                index,
                sourceFeature,
                sourcePlaylistId,
                keepSelectedTrack = true,
            )
        }
        playMainIndex(playbackIndex, skippedUnavailableCount)
    }

    private fun startPlayback(
        track: MusicTrack,
        skippedUnavailableCount: Int = 0,
        requestedPartIndex: Int? = null,
        manualSelection: SmartReplacementSelection? = null,
        rollbackTrack: MusicTrack? = null,
        messageAfterStart: String? = null,
        suppressPlaybackRecovery: Boolean = false,
    ) {
        playbackStartCoordinator.start(
            track = track,
            skippedUnavailableCount = skippedUnavailableCount,
            requestedPartIndex = requestedPartIndex,
            manualSelection = manualSelection,
            rollbackTrack = rollbackTrack,
            messageAfterStart = messageAfterStart,
            suppressPlaybackRecovery = suppressPlaybackRecovery,
        )
    }

    private fun playFirst(
        sourceQueue: List<MusicTrack>,
        sourceFeature: ProviderFeature? = null,
        sourcePlaylistId: String? = null,
    ) {
        if (sourceQueue.isEmpty()) return
        trackChangeDirection = TrackChangeDirection.Next
        val playbackIndex = replaceMainQueue(
            sourceQueue,
            0,
            sourceFeature,
            sourcePlaylistId,
            keepSelectedTrack = false,
        )
        playMainIndex(playbackIndex)
    }

    private fun replaceMainQueue(
        sourceQueue: List<MusicTrack>,
        index: Int,
        sourceFeature: ProviderFeature?,
        sourcePlaylistId: String?,
        keepSelectedTrack: Boolean,
    ): Int {
        if (sourceQueue.isEmpty()) return -1
        val normalizedIndex = index.coerceIn(0, sourceQueue.lastIndex)
        val enteringFm = sourceFeature?.isDynamicQueueFeature() == true
        val restoreShuffle = if (isFmQueue && !enteringFm) shuffleBeforeFm else null
        if (enteringFm && !isFmQueue) {
            shuffleBeforeFm = shuffleEnabled
            shuffleEnabled = false
        } else if (!enteringFm && restoreShuffle != null) {
            shuffleEnabled = restoreShuffle
            shuffleBeforeFm = null
        }
        isFmQueue = enteringFm
        queueFeature = sourceFeature
        queuePlaylistId = sourcePlaylistId
        currentUpNextTrack = null
        currentIsUpNext = false
        originalMainQueue = emptyList()
        mainQueue = sourceQueue
        mainQueueIndex = normalizedIndex
        if (shuffleEnabled && !enteringFm) {
            if (keepSelectedTrack) {
                enableShuffle()
            } else {
                originalMainQueue = mainQueue
                mainQueue = mainQueue.shuffledForPlaybackStart()
                mainQueueIndex = 0
            }
        }
        updatePlaybackQueueState()
        persistPlaybackQueue()
        return mainQueueIndex
    }

    private fun appendPlaylistPlaybackQueue(
        playlist: ProviderPlaylist,
        tracks: List<MusicTrack>,
    ) {
        if (
            tracks.isEmpty() ||
            queuePlaylistId != playlist.id ||
            selectedPlaylist?.id != playlist.id
        ) {
            return
        }
        val existingIds = (mainQueue + originalMainQueue).mapTo(mutableSetOf()) { it.id }
        val newTracks = tracks.filter { existingIds.add(it.id) }
        if (newTracks.isEmpty()) return
        if (shuffleEnabled) {
            val sourceQueue = originalMainQueue.ifEmpty { mainQueue }
            originalMainQueue = sourceQueue + newTracks
        }
        mainQueue = mainQueue + newTracks
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    private fun reshuffleCompletedPlaylistQueue(playlist: ProviderPlaylist) {
        if (queuePlaylistId != playlist.id || selectedPlaylist?.id != playlist.id) return
        if (!shuffleEnabled || mainQueue.isEmpty()) return
        val nextIndex = (mainQueueIndex + 1).coerceIn(0, mainQueue.size)
        mainQueue = mainQueue.take(nextIndex) + mainQueue.drop(nextIndex).shuffledForPlaybackStart()
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    private fun playMainIndex(index: Int, skippedUnavailableCount: Int = 0) =
        playbackQueueCoordinator.playMainIndex(index, skippedUnavailableCount)

    private fun playUpNextIndex(index: Int) = playbackQueueCoordinator.playUpNextIndex(index)

    private fun loadFeatureAndPlayAll(feature: ProviderFeature) {
        scope.launch {
            isLoading = true
            message = "正在加载：${feature.title}"
            runCatching {
                withTimeout(30_000) {
                    providerRepository.loadFeature(feature)
                }
            }.onSuccess { section ->
                updateHomeFeatureSection(section)
                if (section.tracks.isEmpty()) {
                    message = "${feature.title} 暂无歌曲"
                } else {
                    playFirst(section.tracks, feature)
                }
            }.onFailure {
                setError(it)
            }
            isLoading = false
        }
    }

    private fun updateHomeFeatureSection(section: ProviderContentSection) {
        recommendSections = recommendSections.replaceFeatureSection(section)
        musicSections = musicSections.replaceFeatureSection(section)
        minePlaylistSections = minePlaylistSections.replaceFeatureSection(section)
        mineFavoritePlaylistSections = mineFavoritePlaylistSections.replaceFeatureSection(section)
        mineSections = mineSections.replaceFeatureSection(section)
    }

    private fun List<ProviderContentSection>.replaceFeatureSection(
        section: ProviderContentSection,
    ): List<ProviderContentSection> {
        return map { existing ->
            if (existing.feature.id == section.feature.id) section else existing
        }
    }

    private fun prefetchFeatureQueueIfNeeded() {
        val feature = queueFeature ?: return
        if (currentIsUpNext || upNextQueue.isNotEmpty()) return
        if (mainQueueIndex < 0) return
        val remaining = mainQueue.size - mainQueueIndex
        if (remaining <= DYNAMIC_QUEUE_PREFETCH_REMAINING) {
            scope.launch {
                appendFeatureQueue(feature)
            }
        }
    }

    private suspend fun appendFeatureQueue(feature: ProviderFeature): Int {
        if (queueFeature != feature) return 0
        val activeTask = appendQueueFeatureTask?.takeIf { it.isActive }
        if (activeTask != null) return activeTask.await()
        val task = scope.async { appendFeatureQueueOnce(feature) }
        appendQueueFeatureTask = task
        return try {
            task.await()
        } finally {
            if (appendQueueFeatureTask == task) {
                appendQueueFeatureTask = null
            }
        }
    }

    private suspend fun appendFeatureQueueOnce(feature: ProviderFeature): Int {
        return try {
            val tracks = withTimeout(30_000) {
                providerRepository.loadMoreFeatureTracks(feature)
            }
            if (queueFeature != feature) return 0
            val seenQueueIds = mainQueue.mapTo(mutableSetOf()) { it.id }
            val newTracks = tracks.filter { seenQueueIds.add(it.id) }
            if (newTracks.isNotEmpty()) {
                mainQueue = mainQueue + newTracks
                updatePlaybackQueueState()
                persistPlaybackQueue()
                if (selectedFeature == feature) {
                    val seenSelectedIds = selectedFeatureTracks.mapTo(mutableSetOf()) { it.id }
                    val newSelectedTracks = newTracks.filter { seenSelectedIds.add(it.id) }
                    if (newSelectedTracks.isNotEmpty()) {
                        selectedFeatureTracks = selectedFeatureTracks + newSelectedTracks
                    }
                }
            }
            newTracks.size
        } catch (throwable: Throwable) {
            if (queueFeature == feature) {
                message = when (throwable) {
                    is TimeoutCancellationException -> "加载后续歌曲超时，请检查网络后重试"
                    else -> throwable.message ?: throwable::class.simpleName.orEmpty()
                }
            }
            0
        }
    }

    private fun MusicTrack.preferDownloaded(): MusicTrack {
        if (isSmartReplacement) return this
        val downloaded = downloadStates[id] as? DownloadState.Downloaded ?: return this
        return copy(
            sourceType = TrackSourceType.Downloaded,
            localUri = downloaded.uri,
            providerId = providerId ?: id,
        )
    }

    private fun MusicTrack.withRememberedReplacement(): MusicTrack {
        val originalTrack = originalDetailTrack()
        val selection = smartReplacementSelections[originalTrack.id] ?: return this
        val enabledReplacementProviderIds = selectedSmartReplacementProviderIds()
        if (selection.replacementSource !in enabledReplacementProviderIds) {
            return if (isSmartReplacement) originalTrack else this
        }
        return originalTrack.withReplacementSelection(selection)
    }

    private fun commitManualReplacementIfReady(engineState: PlaybackState) {
        val pending = pendingManualReplacementSwitch ?: return
        if (pending.requestSerial != playRequestSerial) return
        val currentTrack = engineState.currentTrack ?: currentQueueTrack() ?: return
        val currentOriginalId = currentTrack.originalId ?: currentTrack.id
        if (
            currentOriginalId != pending.originalTrackId ||
                currentTrack.replacementId != pending.selection.replacementId
        ) {
            return
        }
        smartReplacementSelections = smartReplacementSelections +
            (pending.originalTrackId to pending.selection)
        pendingManualReplacementSwitch = null
        persistSettings()
    }

    private fun rollbackManualReplacement(requestSerial: Long, errorMessage: String?): Boolean {
        val pending = pendingManualReplacementSwitch
            ?.takeIf { it.requestSerial == requestSerial }
            ?: return false
        pendingManualReplacementSwitch = null
        val previousTrack = pending.previousTrack
        if (previousTrack == null) {
            message = "手动换源失败，当前歌曲无法恢复${errorMessage?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()}"
            isLoading = false
            return true
        }
        startPlayback(
            track = previousTrack,
            messageAfterStart = "手动换源失败，已恢复原播放源：${previousTrack.title}",
            suppressPlaybackRecovery = true,
        )
        return true
    }

    private fun showManualReplacementRestoreFailure(errorMessage: String?) {
        val detail = errorMessage?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
        message = "手动换源失败，无法恢复原播放源$detail"
        playbackState = playbackState.copy(status = PlayerStatus.Error, errorMessage = message)
        isLoading = false
    }

    private fun MusicTrack.toPayload(): PlaybackPayload? {
        val uri = localUri ?: return null
        return PlaybackPayload(
            url = uri,
            title = title,
            artists = artists,
            album = album,
            source = source,
            coverUrl = coverUrl,
            durationMs = durationMs,
            lyrics = lyrics,
            audioQuality = null,
            providerName = providerName,
            isSmartReplacement = isSmartReplacement,
            originalId = originalId,
            originalTitle = originalTitle,
            originalArtists = originalArtists,
            originalAlbum = originalAlbum,
            originalSource = originalSource,
            originalProviderName = originalProviderName,
            originalCoverUrl = originalCoverUrl,
            replacementId = replacementId,
            replacementTitle = replacementTitle,
            replacementArtists = replacementArtists,
            replacementAlbum = replacementAlbum,
            replacementSource = replacementSource,
            replacementProviderName = replacementProviderName,
            replacementCoverUrl = replacementCoverUrl,
            replacementStrategy = replacementStrategy,
            replacementScore = replacementScore,
        )
    }

    private fun PlaybackPart.toTrack(parent: MusicTrack): MusicTrack {
        return parent.copy(
            id = id,
            title = title.ifBlank { parent.title },
            durationMs = durationMs ?: parent.durationMs,
            providerId = id,
        )
    }

    private fun playPlaybackPartOffset(offset: Int, wrap: Boolean = false): Boolean =
        playbackQueueCoordinator.playPlaybackPartOffset(offset, wrap)

    private fun Int.floorMod(divisor: Int): Int {
        return ((this % divisor) + divisor) % divisor
    }

    private fun currentPlaybackPartLabel(): String? {
        val part = playbackParts.getOrNull(currentPartIndex) ?: return null
        return "第 ${currentPartIndex + 1}P · ${part.title.ifBlank { "未命名分段" }}"
    }

    private fun currentQueueTrack(): MusicTrack? = playbackQueueController.currentTrack()

    private fun displayQueue(): List<MusicTrack> = playbackQueueController.displayQueue()

    private fun displayQueueIndex(): Int = playbackQueueController.displayQueueIndex()

    private fun updateCurrentTrack(track: MusicTrack) = playbackQueueController.updateCurrentTrack(track)

    private fun synchronizePlaybackTrack(track: MusicTrack) {
        val current = currentQueueTrack()
        var changed = current != track
        if (current?.id != track.id) {
            val upNextIndex = upNextQueue.indexOfFirst { it.id == track.id }
            if (upNextIndex >= 0) {
                currentUpNextTrack = upNextQueue[upNextIndex]
                upNextQueue = upNextQueue.filterIndexed { index, _ -> index != upNextIndex }
                currentIsUpNext = true
            } else {
                val mainIndex = mainQueue.indexOfFirst { it.id == track.id }
                if (mainIndex >= 0) {
                    mainQueueIndex = mainIndex
                    currentUpNextTrack = null
                    currentIsUpNext = false
                    changed = true
                }
            }
        }
        updateCurrentTrack(track)
        if (changed) persistPlaybackQueue()
    }

    private fun updateLocalTrackCopies(trackId: String, updatedTrack: MusicTrack) {
        mainQueue = mainQueue.map { if (it.id == trackId) updatedTrack else it }
        originalMainQueue = originalMainQueue.map { if (it.id == trackId) updatedTrack else it }
        upNextQueue = upNextQueue.map { if (it.id == trackId) updatedTrack else it }
        if (currentUpNextTrack?.id == trackId) {
            currentUpNextTrack = updatedTrack
        }
        if (playbackState.currentTrack?.id == trackId) {
            playbackState = playbackState.copy(
                currentTrack = updatedTrack,
                lyrics = updatedTrack.lyrics ?: playbackState.lyrics,
            )
        }
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    private fun updatePlaybackQueueState() {
        playbackState = playbackState.copy(
            queue = displayQueue(),
            queueIndex = displayQueueIndex(),
            currentTrack = currentQueueTrack() ?: playbackState.currentTrack,
            playbackParts = playbackParts,
            currentPartIndex = currentPartIndex,
        )
    }

    private fun restorePlaybackQueue(snapshot: PlaybackQueueSnapshot) {
        playbackQueueController.restore(snapshot)
        playbackParts = emptyList()
        currentPartIndex = -1
        playbackState = playbackState.copy(
            currentTrack = mainQueue.getOrNull(mainQueueIndex),
            queue = displayQueue(),
            queueIndex = displayQueueIndex(),
            playbackParts = playbackParts,
            currentPartIndex = currentPartIndex,
        )
    }

    private fun persistPlaybackQueue() {
        val snapshot = playbackQueueController.snapshot()
        scope.launch {
            playbackQueueStore.save(snapshot)
        }
    }

    private fun enableShuffle() {
        if (isFmQueue || mainQueue.size <= 1) {
            shuffleEnabled = !isFmQueue
            return
        }
        val current = currentQueueTrack()
        originalMainQueue = if (originalMainQueue.isEmpty()) mainQueue else originalMainQueue
        val currentInMain = current?.let { track -> mainQueue.firstOrNull { it.id == track.id } }
        val shuffledRest = mainQueue.filterNot { it.id == currentInMain?.id }.shuffled()
        mainQueue = listOfNotNull(currentInMain) + shuffledRest
        mainQueueIndex = currentInMain?.let { 0 } ?: mainQueueIndex.coerceIn(0, mainQueue.lastIndex)
        shuffleEnabled = true
    }

    private fun List<MusicTrack>.shuffledForPlaybackStart(): List<MusicTrack> {
        if (size <= 1) return this
        val shuffled = shuffled()
        return if (shuffled.first().id == first().id) {
            shuffled.drop(1) + shuffled.first()
        } else {
            shuffled
        }
    }

    private fun disableShuffle() {
        val current = currentQueueTrack()
        if (originalMainQueue.isNotEmpty()) {
            mainQueue = originalMainQueue
            mainQueueIndex = current?.let { track -> mainQueue.indexOfFirst { it.id == track.id } }
                ?.takeIf { it >= 0 }
                ?: mainQueueIndex.coerceIn(-1, mainQueue.lastIndex)
        }
        originalMainQueue = emptyList()
        shuffleEnabled = false
    }

    private fun providerName(providerId: String): String {
        return providers.firstOrNull { it.providerId == providerId }?.providerName
            ?: availableProviders.firstOrNull { it.providerId == providerId }?.providerName
            ?: providerId
    }

    private fun normalizedProviderOrder(availableProviderIds: Set<String>): List<String> {
        val orderedIds = (providerOrderIds + DEFAULT_PROVIDER_ORDER_IDS + availableProviderIds)
            .filter { it in availableProviderIds }
            .distinct()
        return orderedIds.ifEmpty { availableProviderIds.toList() }
    }

    private fun providerOrderIndex(providerId: String): Int {
        val normalizedOrder = (providerOrderIds + DEFAULT_PROVIDER_ORDER_IDS).distinct()
        val index = normalizedOrder.indexOf(providerId)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    private fun List<ProviderInfo>.sortedProvidersByOrder(): List<ProviderInfo> =
        sortedWith(compareBy<ProviderInfo> { providerOrderIndex(it.providerId) }.thenBy { it.providerName })

    private fun List<ProviderFeature>.sortedFeaturesByOrder(): List<ProviderFeature> =
        sortedWith(compareBy<ProviderFeature> { providerOrderIndex(it.providerId) }.thenBy { it.id })

    private fun List<ProviderContentSection>.sortedSectionsByOrder(): List<ProviderContentSection> =
        sortedWith(compareBy<ProviderContentSection> { providerOrderIndex(it.feature.providerId) }.thenBy { it.feature.id })

    private fun List<ProviderContentSection>.withoutTrack(trackId: String): List<ProviderContentSection> =
        map { section -> section.copy(tracks = section.tracks.filterNot { it.id == trackId }) }

    private fun ProviderFeature.isDeferredHomeFeature(): Boolean {
        val deferredMusicFeature = category == ProviderFeatureCategory.Music &&
            (
                contentType == ProviderContentType.Songs ||
                    contentType == ProviderContentType.Videos ||
                    isBilibiliWeeklyMustWatch()
            )
        val deferredRecommendFeature = category == ProviderFeatureCategory.Recommend &&
            (id.endsWith("_daily_songs") || isDynamicQueueFeature() || isBilibiliRecommendedVideos())
        return deferredMusicFeature || deferredRecommendFeature
    }

    private fun ProviderFeature.isDynamicQueueFeature(): Boolean {
        return id.endsWith("_radio")
    }

    private fun ProviderContentSection.contentCount(): Int = maxOf(
        tracks.size,
        playlists.size,
        mediaItems.size,
        videos.size,
    )

    private fun ProviderContentSection?.orEmptyTracks(): List<MusicTrack> = this?.tracks.orEmpty()

    private fun applySettings(settings: AppSettings) {
        onboardingCompleted = settings.onboardingCompleted
        homeSection = settings.homeSection
        mineSection = settings.mineSection
        playlistFilter = settings.playlistFilter
        localMusicViewMode = settings.localMusicViewMode
        excludedLocalMusicDirectoryIds = settings.excludedLocalMusicDirectoryIds
            .mapNotNull(::canonicalLocalMusicDirectoryId)
            .toSet()
        localMusicMinDurationSeconds = settings.localMusicMinDurationSeconds
        searchController.applyPreferences(
            searchScope = settings.searchScope,
            selectedSearchProviderId = settings.selectedSearchProviderId,
        )
        selectedSettingsProviderId = settings.selectedSettingsProviderId
        providerLoginMode = settings.providerLoginMode
        enabledProviderIds = settings.enabledProviderIds.ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS }
        providerOrderIds = settings.providerOrderIds.ifEmpty { DEFAULT_PROVIDER_ORDER_IDS }
        searchProviderIds = settings.searchProviderIds
        recommendProviderIds = settings.recommendProviderIds
        exploreProviderIds = settings.exploreProviderIds
        mineProviderIds = settings.mineProviderIds
        audioCacheLimitMb = settings.audioCacheLimitMb
        imageCacheLimitMb = settings.imageCacheLimitMb
        downloadParallelism = settings.downloadParallelism.coerceIn(1, 5)
        wifiAudioQualityPolicy = settings.wifiAudioQualityPolicy
        cellularAudioQualityPolicy = settings.cellularAudioQualityPolicy
        unavailablePlaybackPolicy = settings.unavailablePlaybackPolicy
        smartReplacementProviderIds = settings.smartReplacementProviderIds
        smartReplacementMinScore = settings.smartReplacementMinScore.coerceIn(0.0, 1.0)
        smartReplacementSelections = settings.smartReplacementSelections
        lyricFontSize = settings.lyricFontSize
        statusBarLyricsEnabled = settings.statusBarLyricsEnabled
        themeMode = settings.themeMode
        themeColorScheme = settings.themeColorScheme
        dynamicCoverColorEnabled = settings.dynamicCoverColorEnabled
        playlistPlaybackStats = normalizedPlaylistPlaybackStats(settings)
        pauseOnOtherAppPlayback = settings.pauseOnOtherAppPlayback
    }

    private fun persistSettings() {
        val settings = currentSettings()
        settingsUpdates.trySend(settings)
    }

    private fun currentSettings(): AppSettings {
        return AppSettings(
            onboardingCompleted = onboardingCompleted,
            homeSection = homeSection,
            mineSection = mineSection,
            playlistFilter = playlistFilter,
            localMusicViewMode = localMusicViewMode,
            excludedLocalMusicDirectoryIds = excludedLocalMusicDirectoryIds
                .mapNotNull(::canonicalLocalMusicDirectoryId)
                .toSet(),
            localMusicMinDurationSeconds = localMusicMinDurationSeconds,
            searchScope = searchScope,
            selectedSearchProviderId = selectedSearchProviderId,
            selectedSettingsProviderId = selectedSettingsProviderId,
            providerLoginMode = providerLoginMode,
            enabledProviderIds = enabledProviderIds,
            providerOrderIds = providerOrderIds,
            searchProviderIds = searchProviderIds,
            recommendProviderIds = recommendProviderIds,
            exploreProviderIds = exploreProviderIds,
            mineProviderIds = mineProviderIds,
            audioCacheLimitMb = audioCacheLimitMb,
            imageCacheLimitMb = imageCacheLimitMb,
            downloadParallelism = downloadParallelism,
            wifiAudioQualityPolicy = wifiAudioQualityPolicy,
            cellularAudioQualityPolicy = cellularAudioQualityPolicy,
            unavailablePlaybackPolicy = unavailablePlaybackPolicy,
            smartReplacementProviderIds = smartReplacementProviderIds,
            smartReplacementMinScore = smartReplacementMinScore,
            smartReplacementSelections = smartReplacementSelections,
            lyricFontSize = lyricFontSize,
            statusBarLyricsEnabled = statusBarLyricsEnabled,
            themeMode = themeMode,
            themeColorScheme = themeColorScheme,
            dynamicCoverColorEnabled = dynamicCoverColorEnabled,
            playlistPlaybackStatsVersion = PLAYLIST_PLAYBACK_STATS_VERSION,
            playlistPlaybackStats = playlistPlaybackStats,
            pauseOnOtherAppPlayback = pauseOnOtherAppPlayback,
        )
    }

    private fun ProviderPlaylist.playbackStatsKey(): String =
        "$providerId$PLAYLIST_STATS_KEY_SEPARATOR$id"

    private fun selectedSmartReplacementProviderIds(): Set<String> {
        val availableEnabledIds = enabledProviderIds.intersect(availableProviders.map { it.providerId }.toSet())
            .ifEmpty { enabledProviderIds }
        return smartReplacementProviderIds.intersect(availableEnabledIds).ifEmpty { availableEnabledIds }
    }

    private fun selectedProviderIdsFor(section: ProviderDisplaySection): Set<String> {
        val configured = configuredProviderIdsFor(section)
        return if (configured.isEmpty() && section != ProviderDisplaySection.Replace) {
            availableProviders.map { it.providerId }.toSet()
        } else {
            configured.intersect(enabledProviderIds)
        }
    }

    private fun configuredProviderIdsFor(section: ProviderDisplaySection): Set<String> {
        return when (section) {
            ProviderDisplaySection.Search -> searchProviderIds
            ProviderDisplaySection.Recommend -> recommendProviderIds
            ProviderDisplaySection.Explore -> exploreProviderIds
            ProviderDisplaySection.Mine -> mineProviderIds
            ProviderDisplaySection.Replace -> selectedSmartReplacementProviderIds()
        }
    }

    private fun searchProviderIdsForSearch(): List<String> =
        orderedProviders().map { it.providerId }.filter { it in selectedProviderIdsFor(ProviderDisplaySection.Search) }

    private fun isMineProviderFeature(feature: ProviderFeature): Boolean =
        feature.providerId in selectedProviderIdsFor(ProviderDisplaySection.Mine)

    private fun List<ProviderSearchResults>.mergeSearchResults(): ProviderSearchResults = ProviderSearchResults(
        tracks = flatMap { it.tracks },
        playlists = flatMap { it.playlists },
        artists = flatMap { it.artists },
        albums = flatMap { it.albums },
        videos = flatMap { it.videos },
        errorMessage = firstNotNullOfOrNull { it.errorMessage },
    )

    private fun refreshLocalMusicDirectories() = localMusicController.refreshDirectories()

    private suspend fun updateLocalMusicScanSettings() = localMusicController.updateScanSettings()

    private fun setError(throwable: Throwable, providerId: String? = null) {
        message = providerErrorMessage(throwable, throwable::class.simpleName.orEmpty(), providerId)
        playbackState = playbackState.copy(status = PlayerStatus.Error, errorMessage = message)
        isLoading = false
    }

    private fun providerErrorMessage(
        throwable: Throwable,
        fallback: String,
        providerId: String? = null,
    ): String = providerState.userMessage(throwable, fallback, providerId)

    private fun recoverPlaybackEngineError(engineState: PlaybackState) {
        val failedTrack = engineState.currentTrack ?: currentQueueTrack() ?: return
        val activeTrackId = currentQueueTrack()?.id ?: playbackState.currentTrack?.id
        if (activeTrackId != null && activeTrackId != failedTrack.id) return
        val errorMessage = engineState.errorMessage.orEmpty()
        val recoveryKey = "$playRequestSerial:${failedTrack.id}:$errorMessage"
        if (lastRecoveredPlaybackErrorKey == recoveryKey) return
        lastRecoveredPlaybackErrorKey = recoveryKey
        message = if (errorMessage.isBlank()) {
            "播放失败：${failedTrack.title}"
        } else {
            "播放失败：${failedTrack.title}（$errorMessage）"
        }
        if (!shouldRecoverPlaybackEngineError(failedTrack, errorMessage)) return
        val playableCount = upNextQueue.size + mainQueue.size
        if (playableCount <= 1 || _repeatMode == RepeatMode.SINGLE) return
        updateCurrentTrack(failedTrack.copy(isUnavailable = true))
        playbackState = playbackState.copy(
            queue = displayQueue(),
            queueIndex = displayQueueIndex(),
            currentTrack = currentQueueTrack(),
        )
        persistPlaybackQueue()
        message = "播放失败，已切换下一首：${failedTrack.title}"
        if (currentIsUpNext) {
            currentUpNextTrack = null
            currentIsUpNext = false
            persistPlaybackQueue()
        }
        if (upNextQueue.isNotEmpty()) {
            playUpNextIndex(0)
            return
        }
        val nextIndex = mainQueueIndex + 1
        if (nextIndex < mainQueue.size) {
            playMainIndex(nextIndex)
        } else if (_repeatMode == RepeatMode.QUEUE) {
            playMainIndex(0)
        }
    }

    private fun shouldRecoverPlaybackEngineError(track: MusicTrack, errorMessage: String): Boolean {
        if (track.sourceType != TrackSourceType.Provider) return false
        return when (unavailablePlaybackPolicy) {
            UnavailablePlaybackPolicy.Skip -> true
            UnavailablePlaybackPolicy.SmartReplace -> errorMessage.isMediaNotFoundMessage()
        }
    }

    private fun skipUnavailableTrack(
        track: MusicTrack,
        skippedUnavailableCount: Int,
        throwable: Throwable,
    ): Boolean {
        if (!shouldSkipUnavailable(throwable)) {
            return false
        }
        updateCurrentTrack(track.copy(isUnavailable = true))
        playbackState = playbackState.copy(
            queue = displayQueue(),
            queueIndex = displayQueueIndex(),
            currentTrack = currentQueueTrack(),
        )
        persistPlaybackQueue()
        val playableCount = upNextQueue.size + mainQueue.size
        if (playableCount <= 1 || skippedUnavailableCount >= playableCount) {
            return false
        }
        message = "已跳过不可用资源：${track.title}"
        if (upNextQueue.isNotEmpty()) {
            playUpNextIndex(0)
        } else {
            val nextIndex = mainQueueIndex + 1
            if (nextIndex < mainQueue.size) {
                playMainIndex(nextIndex, skippedUnavailableCount + 1)
            } else if (_repeatMode == RepeatMode.QUEUE) {
                playMainIndex(0, skippedUnavailableCount + 1)
            } else {
                return false
            }
        }
        return true
    }

    private fun shouldSkipUnavailable(throwable: Throwable): Boolean {
        if (!throwable.isMediaNotFound()) return false
        return unavailablePlaybackPolicy == UnavailablePlaybackPolicy.Skip ||
            unavailablePlaybackPolicy == UnavailablePlaybackPolicy.SmartReplace
    }

    private fun Throwable.isMediaNotFound(): Boolean {
        val messages = mutableListOf<String>()
        var current: Throwable? = this
        while (current != null) {
            current.message?.let { messages += it }
            current = current.cause
        }
        return messages.joinToString(" ").isMediaNotFoundMessage()
    }

    private fun String.isMediaNotFoundMessage(): Boolean =
        contains("media not found", ignoreCase = true) || contains("MediaNotFound", ignoreCase = true)
}

internal fun normalizedPlaylistPlaybackStats(settings: AppSettings): Map<String, PlaylistPlaybackStat> {
    if (settings.playlistPlaybackStatsVersion != PLAYLIST_PLAYBACK_STATS_VERSION) return emptyMap()
    return settings.playlistPlaybackStats.entries
        .asSequence()
        .filter { (key, stat) ->
            key.isNotBlank() &&
                key.length <= MAX_PLAYLIST_STATS_KEY_LENGTH &&
                key.none(Char::isISOControl) &&
                stat.playCount in 1..MAX_PLAYLIST_PLAY_COUNT &&
                stat.lastPlayedAtMillis > 0
        }
        .sortedByDescending { it.value.lastPlayedAtMillis }
        .take(MAX_PLAYLIST_PLAYBACK_STATS)
        .associate { it.toPair() }
}
