package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.serialization.Serializable

@Serializable
enum class SearchScope {
    Local,
    Provider,
    All,
}

@Serializable
enum class ProviderSearchTab {
    Songs,
    Artists,
    Albums,
    Playlists,
    Videos,
}

@Serializable
enum class HomeSection {
    Recommend,
    Music,
    Mine,
}

@Serializable
enum class ProviderDisplaySection(val label: String) {
    Search("搜索"),
    Recommend("推荐"),
    Explore("探索"),
    Mine("我的"),
    Replace("替换"),
}

@Serializable
enum class MineSection {
    Playlists,
    Songs,
    Artists,
    Albums,
    LocalMusic,
}

@Serializable
enum class PlaylistFilter {
    All,
    UserPlaylists,
    FavoritePlaylists,
}

@Serializable
enum class LocalMusicViewMode {
    All,
    Artist,
    Album,
}

data class TrackArtistTarget(
    val name: String,
    val mediaItem: ProviderMediaItem? = null,
)

private const val DYNAMIC_QUEUE_PREFETCH_REMAINING = 2
private const val LIST_PREFETCH_REMAINING = 8
private const val PLAYLIST_PLAYBACK_INITIAL_TRACK_COUNT = 200
private val DEFAULT_DEBUG_LOG_LEVEL_FILTERS = setOf(DebugLogLevel.Info, DebugLogLevel.Warning, DebugLogLevel.Error)

class FuoPlayerController(
    private val providerRepository: ProviderMusicRepository,
    private val localRepository: LocalMusicRepository,
    private val downloadRepository: DownloadRepository,
    private val playbackEngine: PlaybackEngine,
    private val settingsRepository: AppSettingsRepository = InMemoryAppSettingsRepository(),
    private val providerSessionRepository: ProviderSessionRepository =
        DefaultProviderSessionRepository(providerRepository),
    private val navigator: AppNavigator = AppNavigator(),
    private val playbackQueueStore: PlaybackQueueStore = NoOpPlaybackQueueStore,
    private val resourceCacheRepository: ResourceCacheRepository = NoOpResourceCacheRepository,
    private val debugLogRepository: DebugLogRepository = NoOpDebugLogRepository,
    private val scope: CoroutineScope,
) {
    var isSettingsLoaded by mutableStateOf(false)
        private set
    var onboardingCompleted by mutableStateOf(false)
        private set
    var availableProviders by mutableStateOf<List<ProviderInfo>>(emptyList())
        private set
    var providers by mutableStateOf<List<ProviderInfo>>(emptyList())
        private set
    var providerFeatures by mutableStateOf<List<ProviderFeature>>(emptyList())
        private set
    var providerCapabilities by mutableStateOf<Map<String, ProviderCapabilities>>(emptyMap())
        private set
    var providerAuthStates by mutableStateOf<Map<String, ProviderAuthState>>(emptyMap())
        private set
    var providerCookieInputs by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var providerHeaderInputs by mutableStateOf<Map<String, ProviderHeaderInput>>(emptyMap())
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
    var recommendSections by mutableStateOf<List<ProviderContentSection>>(emptyList())
        private set
    var musicSections by mutableStateOf<List<ProviderContentSection>>(emptyList())
        private set
    var mineSections by mutableStateOf<List<ProviderContentSection>>(emptyList())
        private set
    var minePlaylistSections by mutableStateOf<List<ProviderContentSection>>(emptyList())
        private set
    var mineFavoritePlaylistSections by mutableStateOf<List<ProviderContentSection>>(emptyList())
        private set
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
    var playlistTargetTrack by mutableStateOf<MusicTrack?>(null)
        private set
    var playlistOperationTargets by mutableStateOf<List<ProviderPlaylist>>(emptyList())
        private set
    var playlistOperationError by mutableStateOf<String?>(null)
        private set
    var playlistOperationFeedback by mutableStateOf<String?>(null)
        private set
    var artistTargetTrack by mutableStateOf<MusicTrack?>(null)
        private set
    var artistTargets by mutableStateOf<List<TrackArtistTarget>>(emptyList())
        private set
    var localTracks by mutableStateOf<List<MusicTrack>>(emptyList())
        private set
    var query by mutableStateOf("")
        private set
    var searchScope by mutableStateOf(SearchScope.All)
        private set
    var selectedSearchProviderId by mutableStateOf<String?>(null)
        private set
    var selectedSettingsProviderId by mutableStateOf<String?>(null)
        private set
    var settingsLoginProviderId by mutableStateOf<String?>(null)
        private set
    var providerLoginMode by mutableStateOf(ProviderLoginMode.WebView)
        private set
    var searchResults by mutableStateOf<List<MusicTrack>>(emptyList())
        private set
    var providerSearchResults by mutableStateOf(ProviderSearchResults())
        private set
    var providerSearchTab by mutableStateOf(ProviderSearchTab.Songs)
        private set
    var homeSection by mutableStateOf(HomeSection.Recommend)
        private set
    var mineSection by mutableStateOf(MineSection.Playlists)
        private set
    var playlistFilter by mutableStateOf(PlaylistFilter.All)
        private set
    var localMusicViewMode by mutableStateOf(LocalMusicViewMode.All)
        private set
    var localMusicDirectories by mutableStateOf<List<LocalMusicDirectory>>(emptyList())
        private set
    var excludedLocalMusicDirectoryIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var localMusicMinDurationSeconds by mutableStateOf(DEFAULT_LOCAL_MUSIC_MIN_DURATION_SECONDS)
        private set
    val isSearchOpen: Boolean
        get() = navigator.contains(AppRoute.Search)
    var isFullPlayerOpen by mutableStateOf(false)
        private set
    var isVideoFullscreen by mutableStateOf(false)
        private set
    val isSettingsOpen: Boolean
        get() = navigator.contains(AppRoute.Settings)
    val isDebugLogOpen: Boolean
        get() = navigator.contains(AppRoute.DebugLogs)
    val isDownloadManagerOpen: Boolean
        get() = navigator.contains(AppRoute.DownloadManager)
    var isQueueOpen by mutableStateOf(false)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var message by mutableStateOf("正在初始化 FeelUOwn")
        private set
    var downloadStates by mutableStateOf<Map<String, DownloadState>>(emptyMap())
        private set
    var downloadTasks by mutableStateOf<List<DownloadTask>>(emptyList())
        private set
    var downloadQueueFeedback by mutableStateOf<String?>(null)
        private set
    var playbackState by mutableStateOf(PlaybackState())
        private set
    var cacheUsage by mutableStateOf(CacheUsage())
        private set
    var audioCacheLimitMb by mutableStateOf(DEFAULT_AUDIO_CACHE_LIMIT_MB)
        private set
    var imageCacheLimitMb by mutableStateOf(DEFAULT_IMAGE_CACHE_LIMIT_MB)
        private set
    var downloadParallelism by mutableStateOf(DEFAULT_DOWNLOAD_PARALLELISM)
        private set
    var wifiAudioQualityPolicy by mutableStateOf(DEFAULT_WIFI_AUDIO_QUALITY_POLICY)
        private set
    var cellularAudioQualityPolicy by mutableStateOf(DEFAULT_CELLULAR_AUDIO_QUALITY_POLICY)
        private set
    var unavailablePlaybackPolicy by mutableStateOf(DEFAULT_UNAVAILABLE_PLAYBACK_POLICY)
        private set
    var smartReplacementProviderIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var smartReplacementMinScore by mutableStateOf(DEFAULT_SMART_REPLACEMENT_MIN_SCORE)
        private set
    var smartReplacementUseReplacementMetadata by mutableStateOf(false)
        private set
    var smartReplacementUseReplacementLyrics by mutableStateOf(false)
        private set
    var lyricFontSize by mutableStateOf(LyricFontSize.Small)
        private set
    var playbackSpectrumStyle by mutableStateOf(PlaybackSpectrumStyle.None)
        private set
    var themeMode by mutableStateOf(ThemeMode.System)
        private set
    var themeColorScheme by mutableStateOf(ThemeColorScheme.Dynamic)
        private set
    var debugLogLines by mutableStateOf<List<String>>(emptyList())
        private set
    var debugLogLevelFilters by mutableStateOf(DEFAULT_DEBUG_LOG_LEVEL_FILTERS)
        private set
    var debugLogError by mutableStateOf<String?>(null)
        private set
    var localMetadataEditorTrack by mutableStateOf<MusicTrack?>(null)
        private set
    var selectedLocalMetadataProviderId by mutableStateOf<String?>(null)
        private set
    var localMetadataSearchResults by mutableStateOf<List<MusicTrack>>(emptyList())
        private set
    var localMetadataSearchMessage by mutableStateOf<String?>(null)
        private set
    val isDebugLogViewerAvailable: Boolean
        get() = debugLogRepository.isAvailable
    val isShuffleEnabled: Boolean
        get() = shuffleEnabledState
    val repeatMode: RepeatMode
        get() = _repeatMode
    val isFmQueueActive: Boolean
        get() = isFmQueue
    val displayUpNextCount: Int
        get() = upNextQueue.size
    val canNavigateBack: Boolean
        get() = isFullPlayerOpen || navigator.backStack.value.size > 1

    private var mainQueue: List<MusicTrack> = emptyList()
    private var originalMainQueue: List<MusicTrack> = emptyList()
    private var upNextQueue: List<MusicTrack> = emptyList()
    private var mainQueueIndex: Int = -1
    private var currentUpNextTrack: MusicTrack? = null
    private var currentIsUpNext: Boolean = false
    private var queueFeature: ProviderFeature? = null
    private var queuePlaylistId: String? = null
    private var shuffleEnabledState by mutableStateOf(false)
    private var _repeatMode by mutableStateOf(RepeatMode.QUEUE)
    private var isFmQueue: Boolean = false
    private var shuffleBeforeFm: Boolean? = null
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
    private var lastEndedTrackId: String? = null
    private var autoAdvanceEligibleTrackId: String? = null
    private var lastRecoveredPlaybackErrorKey: String? = null
    private var playRequestSerial: Long = 0
    private var localMusicRefreshSerial: Long = 0
    private var hasLocalMusicPermission: Boolean = false
    private var observedCompletedDownloadTaskIds = emptySet<String>()
    private var pendingLocalMusicMediaRefresh: Job? = null
    private var playbackParts: List<PlaybackPart> = emptyList()
    private var currentPartIndex: Int = -1
    private val settingsUpdates = Channel<AppSettings>(capacity = Channel.UNLIMITED)

    init {
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
            updateLocalMusicScanSettings()
            updateResourceCacheLimit()
            updateAudioQualityPolicies()
            resourceCacheRepository.refreshUsage()
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
        scope.launch {
            providerSessionRepository.state.collect { sessionState ->
                providerAuthStates = sessionState.authStates
            }
        }
        scope.launch {
            settingsRepository.state.collect { settingsState ->
                if (settingsState.isLoaded) {
                    applySettings(settingsState.settings)
                }
            }
        }
        scope.launch {
            downloadRepository.states.collect {
                downloadStates = it
            }
        }
        scope.launch {
            downloadRepository.tasks.collect {
                downloadTasks = it
                val completedIds = it.filter { task -> task.status == DownloadTaskStatus.Completed }.map { task -> task.id }.toSet()
                val newlyCompleted = completedIds - observedCompletedDownloadTaskIds
                observedCompletedDownloadTaskIds = completedIds
                if (newlyCompleted.isNotEmpty() && hasLocalMusicPermission) {
                    refreshLocalMusic(
                        forceRefresh = true,
                        showLoading = homeSection == HomeSection.Mine && mineSection == MineSection.LocalMusic,
                    )
                }
            }
        }
        scope.launch {
            resourceCacheRepository.usage.collect {
                cacheUsage = it
            }
        }
        scope.launch {
            playbackEngine.state.collect { engineState ->
                engineState.currentTrack?.let(::synchronizePlaybackTrack)
                val queueTrackId = currentQueueTrack()?.id
                var shouldAutoAdvance = false
                when (engineState.status) {
                    PlayerStatus.Playing -> {
                        autoAdvanceEligibleTrackId = queueTrackId ?: engineState.currentTrack?.id
                        lastEndedTrackId = null
                        lastRecoveredPlaybackErrorKey = null
                    }
                    PlayerStatus.Ended -> {
                        val endedTrackId = queueTrackId
                        if (
                            endedTrackId != null &&
                            endedTrackId == autoAdvanceEligibleTrackId &&
                            endedTrackId != lastEndedTrackId
                        ) {
                            autoAdvanceEligibleTrackId = null
                            lastEndedTrackId = endedTrackId
                            shouldAutoAdvance = true
                        }
                    }
                    else -> {
                        lastEndedTrackId = null
                    }
                }
                playbackState = engineState.copy(
                    queue = displayQueue(),
                    queueIndex = displayQueueIndex(),
                    currentTrack = currentQueueTrack() ?: engineState.currentTrack,
                    playbackParts = engineState.playbackParts.ifEmpty { playbackParts },
                    currentPartIndex = engineState.currentPartIndex.takeIf { it >= 0 } ?: currentPartIndex,
                )
                if (engineState.playbackParts.isNotEmpty()) {
                    playbackParts = engineState.playbackParts
                    currentPartIndex = engineState.currentPartIndex
                }
                isLoading = engineState.status == PlayerStatus.Loading
                if (shouldAutoAdvance) {
                    next()
                } else if (engineState.status == PlayerStatus.Error) {
                    recoverPlaybackEngineError(engineState)
                }
            }
        }
        scope.launch {
            localRepository.mediaChangeEvents.collect {
                pendingLocalMusicMediaRefresh?.cancel()
                pendingLocalMusicMediaRefresh = launch {
                    delay(750)
                    if (hasLocalMusicPermission && localRepository.isDatabaseReady()) {
                        val showLoading = homeSection == HomeSection.Mine && mineSection == MineSection.LocalMusic
                        refreshLocalMusic(forceRefresh = true, showLoading = showLoading)
                    }
                }
            }
        }
    }

    fun authStateFor(provider: ProviderInfo): ProviderAuthState {
        return providerSessionRepository.state.value.authStates[provider.providerId] ?: ProviderAuthState(
            providerId = provider.providerId,
            providerName = provider.providerName,
            isLoggedIn = false,
        )
    }

    fun isProviderAuthBusy(providerId: String): Boolean =
        providerId in providerSessionRepository.state.value.operations

    fun providerAuthError(providerId: String): String? =
        providerSessionRepository.state.value.errors[providerId]

    fun cookieInputFor(providerId: String): String = providerCookieInputs[providerId].orEmpty()

    fun providerHeaderInputFor(providerId: String): ProviderHeaderInput = providerHeaderInputs[providerId] ?: ProviderHeaderInput()

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

    fun onLocalMusicPermissionChange(hasPermission: Boolean) {
        val wasGranted = hasLocalMusicPermission
        hasLocalMusicPermission = hasPermission
        if (hasPermission && !wasGranted && homeSection == HomeSection.Mine && mineSection == MineSection.LocalMusic) {
            ensureLocalMusic()
        }
    }

    fun ensureLocalMusic() {
        if (!hasLocalMusicPermission) return
        refreshLocalMusic(forceRefresh = false, showLoading = true)
    }

    fun refreshLocalMusic() {
        if (!hasLocalMusicPermission) {
            message = "允许访问音频后可加载本地音乐"
            return
        }
        refreshLocalMusic(forceRefresh = true, showLoading = true)
    }

    private fun refreshLocalMusic(forceRefresh: Boolean, showLoading: Boolean) {
        val refreshSerial = ++localMusicRefreshSerial
        scope.launch {
            if (showLoading) {
                isLoading = true
                message = if (forceRefresh) "正在刷新本地音乐库" else "正在加载本地音乐"
            }
            val result = runCatching {
                updateLocalMusicScanSettings()
                val databaseReady = localRepository.isDatabaseReady()
                val databaseStale = databaseReady && localRepository.isDatabaseStale()
                val shouldRefresh = forceRefresh || !databaseReady || databaseStale
                if (showLoading) {
                    message = when {
                        !databaseReady -> "正在建立本地音乐库"
                        shouldRefresh -> "正在更新本地音乐库"
                        else -> "正在加载本地音乐"
                    }
                }
                val tracks = if (shouldRefresh) {
                    localRepository.refreshDatabase()
                } else {
                    localRepository.tracks()
                }
                localMusicDirectories = localRepository.directories()
                tracks
            }
            if (refreshSerial == localMusicRefreshSerial) {
                result
                    .onSuccess {
                        localTracks = it
                        if (showLoading) {
                            message = if (it.isEmpty()) "未发现本地音乐" else "本地音乐 ${it.size} 首"
                        }
                    }
                    .onFailure {
                        if (showLoading) {
                            setError(it)
                        }
                    }
            }
            if (showLoading) isLoading = false
        }
    }

    private fun reloadLocalMusic() {
        if (!hasLocalMusicPermission) return
        val refreshSerial = ++localMusicRefreshSerial
        scope.launch {
            isLoading = true
            message = "正在更新本地音乐筛选"
            val result = runCatching {
                updateLocalMusicScanSettings()
                val tracks = localRepository.tracks()
                localMusicDirectories = localRepository.directories()
                tracks
            }
            if (refreshSerial == localMusicRefreshSerial) {
                result
                    .onSuccess {
                        localTracks = it
                        message = if (it.isEmpty()) "未发现本地音乐" else "本地音乐 ${it.size} 首"
                    }
                    .onFailure { setError(it) }
            }
            isLoading = false
        }
    }

    fun openSearch() {
        navigator.navigate(AppRoute.Search)
    }

    fun closeSearch() {
        navigator.pop(AppRoute.Search)
    }

    fun navigateBack(): Boolean {
        return when {
            isFullPlayerOpen && isQueueOpen -> {
                isQueueOpen = false
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
                AppRoute.Feature -> {
                    closeFeature()
                    true
                }
                AppRoute.Search -> {
                    closeSearch()
                    true
                }
                AppRoute.Home -> false
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
        if (!debugLogRepository.isAvailable) return
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

    fun dismissDownloadQueueFeedback(feedback: String) {
        if (downloadQueueFeedback == feedback) downloadQueueFeedback = null
    }

    fun onDownloadParallelismChange(value: Int) {
        downloadParallelism = value.coerceIn(1, 5)
        persistSettings()
        scope.launch { downloadRepository.updateParallelism(downloadParallelism) }
    }

    fun refreshDebugLogs() {
        if (!debugLogRepository.isAvailable) return
        scope.launch {
            isLoading = true
            debugLogError = null
            runCatching { debugLogRepository.logLines() }
                .onSuccess { debugLogLines = it }
                .onFailure { debugLogError = it.message ?: it::class.simpleName.orEmpty() }
            isLoading = false
        }
    }

    fun onDebugLogLevelFilterChange(level: DebugLogLevel, selected: Boolean) {
        debugLogLevelFilters = if (selected) {
            debugLogLevelFilters + level
        } else {
            debugLogLevelFilters - level
        }
    }

    fun exportDebugLogs(lines: List<String>) {
        if (!debugLogRepository.isAvailable || lines.isEmpty()) return
        scope.launch {
            isLoading = true
            runCatching { debugLogRepository.exportLogFile(lines) }
                .onSuccess { message = it }
                .onFailure { setError(it) }
            isLoading = false
        }
    }

    fun onProviderCookiesChange(providerId: String, value: String) {
        providerCookieInputs = providerCookieInputs + (providerId to value)
    }

    fun onProviderHeaderAuthorizationChange(providerId: String, value: String) {
        val input = providerHeaderInputFor(providerId).copy(authorization = value)
        providerHeaderInputs = providerHeaderInputs + (providerId to input)
    }

    fun onProviderHeaderCookieChange(providerId: String, value: String) {
        val input = providerHeaderInputFor(providerId).copy(cookie = value)
        providerHeaderInputs = providerHeaderInputs + (providerId to input)
    }

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
        audioCacheLimitMb = value
        persistSettings()
        scope.launch {
            updateResourceCacheLimit()
            resourceCacheRepository.refreshUsage()
        }
    }

    fun onImageCacheLimitChange(value: Int) {
        imageCacheLimitMb = value
        persistSettings()
        scope.launch {
            updateResourceCacheLimit()
            resourceCacheRepository.refreshUsage()
        }
    }

    fun refreshResourceCacheUsage() {
        scope.launch {
            runCatching { resourceCacheRepository.refreshUsage() }
                .onFailure { setError(it) }
        }
    }

    fun clearResourceCache() {
        scope.launch {
            isLoading = true
            message = "正在清空缓存"
            runCatching {
                resourceCacheRepository.clearAll()
                resourceCacheRepository.refreshUsage()
            }.onSuccess {
                message = "缓存已清空"
            }.onFailure {
                setError(it)
            }
            isLoading = false
        }
    }

    fun loginProviderWithCookies(providerId: String, cookiesJson: String) {
        val cookies = cookiesJson.trim()
        val providerName = providerName(providerId)
        if (cookies.isEmpty()) {
            message = "请输入 $providerName cookies"
            return
        }
        scope.launch {
            message = "正在登录 $providerName"
            runCatching { providerSessionRepository.loginWithCookies(providerId, cookies) }
                .onSuccess {
                    providerCookieInputs = providerCookieInputs - providerId
                    persistSettings()
                    message = if (it.isLoggedIn) {
                        "${it.providerName} 已登录：${it.userName.orEmpty()}"
                    } else {
                        "${it.providerName} 未登录"
                    }
                    if (homeSection == HomeSection.Mine && mineSection != MineSection.LocalMusic) {
                        refreshActiveMineProviderContent()
                    } else {
                        refreshHomeContent(homeSection)
                    }
                }
                .onFailure { setError(it) }
        }
    }

    fun loginProviderWithHeaders(providerId: String) {
        val input = providerHeaderInputFor(providerId)
        val authorization = input.authorization.trim()
        val cookie = input.cookie.trim()
        val providerName = providerName(providerId)
        if (authorization.isEmpty() || cookie.isEmpty()) {
            message = "请输入 $providerName Authorization 和 Cookie"
            return
        }
        scope.launch {
            message = "正在登录 $providerName"
            runCatching { providerSessionRepository.loginWithHeaders(providerId, authorization, cookie) }
                .onSuccess {
                    persistSettings()
                    message = if (it.isLoggedIn) {
                        "${it.providerName} 已登录：${it.userName.orEmpty()}"
                    } else {
                        "${it.providerName} 未登录"
                    }
                    if (homeSection == HomeSection.Mine && mineSection != MineSection.LocalMusic) {
                        refreshActiveMineProviderContent()
                    } else {
                        refreshHomeContent(homeSection)
                    }
                }
                .onFailure { setError(it) }
        }
    }

    fun loginYtmusicWithHeaderFile(headerFileJson: String) {
        if (headerFileJson.isBlank()) {
            message = "无法读取 ytmusic_header.json"
            return
        }
        val providerName = providerName("ytmusic")
        scope.launch {
            message = "正在登录 $providerName"
            runCatching { providerSessionRepository.loginWithYtmusicHeaderFile(headerFileJson) }
                .onSuccess {
                    message = if (it.isLoggedIn) {
                        "${it.providerName} 已登录：${it.userName.orEmpty()}"
                    } else {
                        "${it.providerName} 未登录"
                    }
                    if (homeSection == HomeSection.Mine && mineSection != MineSection.LocalMusic) {
                        refreshActiveMineProviderContent()
                    } else {
                        refreshHomeContent(homeSection)
                    }
                }
                .onFailure { setError(it) }
        }
    }

    fun logoutProvider(providerId: String) {
        val providerName = providerName(providerId)
        scope.launch {
            message = "正在退出 $providerName"
            runCatching { providerSessionRepository.logout(providerId) }
                .onSuccess {
                    providerCookieInputs = providerCookieInputs - providerId
                    providerHeaderInputs = providerHeaderInputs - providerId
                    persistSettings()
                    message = "${it.providerName} 已退出登录"
                    if (homeSection == HomeSection.Mine && mineSection != MineSection.LocalMusic) {
                        refreshActiveMineProviderContent()
                    } else {
                        refreshHomeContent(homeSection)
                    }
                }
                .onFailure { setError(it) }
        }
    }

    fun onWifiAudioQualityPolicyChange(value: AudioQualityPolicy) {
        wifiAudioQualityPolicy = value
        persistSettings()
        scope.launch { updateAudioQualityPolicies() }
    }

    fun onCellularAudioQualityPolicyChange(value: AudioQualityPolicy) {
        cellularAudioQualityPolicy = value
        persistSettings()
        scope.launch { updateAudioQualityPolicies() }
    }

    fun onUnavailablePlaybackPolicyChange(value: UnavailablePlaybackPolicy) {
        unavailablePlaybackPolicy = value
        persistSettings()
    }

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

    fun onSmartReplacementUseReplacementMetadataChange(value: Boolean) {
        smartReplacementUseReplacementMetadata = value
        persistSettings()
    }

    fun onSmartReplacementUseReplacementLyricsChange(value: Boolean) {
        smartReplacementUseReplacementLyrics = value
        persistSettings()
    }

    fun onLyricFontSizeChange(value: LyricFontSize) {
        lyricFontSize = value
        persistSettings()
    }

    fun onPlaybackSpectrumStyleChange(value: PlaybackSpectrumStyle) {
        playbackSpectrumStyle = value
        persistSettings()
    }

    fun onThemeModeChange(value: ThemeMode) {
        themeMode = value
        persistSettings()
    }

    fun onThemeColorSchemeChange(value: ThemeColorScheme) {
        themeColorScheme = value
        persistSettings()
    }

    fun onQueryChange(value: String) {
        query = value
    }

    fun onSearchScopeChange(value: SearchScope) {
        searchScope = value
        if (value != SearchScope.Provider) {
            selectedSearchProviderId = null
        }
        persistSettings()
        if (query.isNotBlank()) search()
    }

    fun onSearchProviderChange(providerId: String) {
        searchScope = SearchScope.Provider
        selectedSearchProviderId = providerId
        persistSettings()
        if (query.isNotBlank()) search()
    }

    fun onProviderSearchTabChange(value: ProviderSearchTab) {
        providerSearchTab = value
    }

    fun onHomeSectionChange(value: HomeSection) {
        homeSection = value
        persistSettings()
        if (value == HomeSection.Mine) {
            refreshActiveMineSectionIfNeeded()
        } else if (value != HomeSection.Mine && contentSectionsFor(value).isEmpty()) {
            refreshHomeContent(value)
        }
    }

    fun onMineSectionChange(value: MineSection) {
        mineSection = value
        persistSettings()
        when (value) {
            MineSection.Playlists -> if (minePlaylistSections.isEmpty()) refreshMinePlaylistContent()
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

    fun onLocalMusicViewModeChange(value: LocalMusicViewMode) {
        localMusicViewMode = value
        persistSettings()
    }

    fun onLocalMusicDirectoryEnabledChange(directoryId: String, enabled: Boolean) {
        excludedLocalMusicDirectoryIds = if (enabled) {
            excludedLocalMusicDirectoryIds - directoryId
        } else {
            excludedLocalMusicDirectoryIds + directoryId
        }
        persistSettings()
        reloadLocalMusic()
    }

    fun onLocalMusicMinDurationChange(value: Int) {
        localMusicMinDurationSeconds = value
        persistSettings()
        reloadLocalMusic()
    }

    fun openLocalMetadataEditor(track: MusicTrack) {
        if (track.sourceType == TrackSourceType.Provider) return
        localMetadataEditorTrack = track
        localMetadataSearchResults = emptyList()
        localMetadataSearchMessage = null
        selectedLocalMetadataProviderId = selectedLocalMetadataProviderId
            ?.takeIf { providerId -> providers.any { it.providerId == providerId } }
            ?: selectedSearchProviderId?.takeIf { providerId -> providers.any { it.providerId == providerId } }
            ?: providers.firstOrNull()?.providerId
    }

    fun closeLocalMetadataEditor() {
        localMetadataEditorTrack = null
        localMetadataSearchResults = emptyList()
        localMetadataSearchMessage = null
    }

    fun onLocalMetadataProviderChange(providerId: String) {
        selectedLocalMetadataProviderId = providerId
    }

    fun saveLocalMetadata(track: MusicTrack, title: String, artists: String, album: String) {
        val metadata = LocalTrackMetadata(
            title = title.trim().ifBlank { track.title },
            artists = artists.trim(),
            album = album.trim(),
        )
        scope.launch {
            isLoading = true
            message = "正在保存元信息"
            runCatching {
                localRepository.updateMetadata(track, metadata)
                updateLocalMusicScanSettings()
                localRepository.refreshDatabase()
            }.onSuccess {
                localTracks = it
                updateLocalTrackCopies(track.id, it.firstOrNull { item -> item.id == track.id } ?: track.copy(
                    title = metadata.title,
                    artists = metadata.artists,
                    album = metadata.album,
                ))
                localMetadataEditorTrack = localTracks.firstOrNull { item -> item.id == track.id }
                    ?: localMetadataEditorTrack
                message = "已保存元信息：${metadata.title}"
            }.onFailure {
                setError(it)
            }
            isLoading = false
        }
    }

    fun searchLocalMetadata(title: String, artists: String, album: String) {
        val providerId = selectedLocalMetadataProviderId ?: providers.firstOrNull()?.providerId
        if (providerId == null) {
            localMetadataSearchMessage = "没有可用音源"
            return
        }
        val keyword = listOf(title, artists, album)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
        if (keyword.isBlank()) {
            localMetadataSearchMessage = "请输入可搜索的信息"
            return
        }
        selectedLocalMetadataProviderId = providerId
        scope.launch {
            isLoading = true
            localMetadataSearchMessage = "正在搜索元信息"
            runCatching {
                withTimeout(25_000) {
                    providerRepository.search(keyword, providerId)
                }
            }.onSuccess {
                localMetadataSearchResults = it
                localMetadataSearchMessage = if (it.isEmpty()) "没有搜索结果" else "搜索到 ${it.size} 首"
            }.onFailure {
                localMetadataSearchMessage = it.message ?: it::class.simpleName.orEmpty()
                setError(it)
            }
            isLoading = false
        }
    }

    fun applyProviderMetadata(track: MusicTrack, providerTrack: MusicTrack) {
        saveLocalMetadata(track, providerTrack.title, providerTrack.artists, providerTrack.album)
    }

    fun downloadLocalLyrics(track: MusicTrack, providerTrack: MusicTrack) {
        val providerId = providerTrack.source.takeIf { it.isNotBlank() } ?: providerTrack.providerId
        scope.launch {
            isLoading = true
            message = "正在下载歌词"
            runCatching {
                withTimeout(25_000) {
                    providerRepository.resolve(
                        providerTrack.copy(providerId = providerTrack.providerId ?: providerTrack.id),
                        unavailablePlaybackPolicy,
                        providerId?.let(::setOf).orEmpty(),
                        smartReplacementMinScore,
                        smartReplacementUseOriginalMetadata = false,
                        smartReplacementUseOriginalLyrics = false,
                    )
                }
            }.onSuccess { payload ->
                val lyrics = payload.lyrics?.takeIf { it.isNotBlank() }
                if (lyrics == null) {
                    message = "未获取到歌词"
                    localMetadataSearchMessage = "未获取到歌词"
                } else {
                        runCatching {
                            localRepository.saveLyrics(track, lyrics)
                            updateLocalMusicScanSettings()
                            localRepository.refreshDatabase()
                        }.onSuccess {
                        localTracks = it
                        val updatedTrack = it.firstOrNull { item -> item.id == track.id } ?: track.copy(lyrics = lyrics)
                        updateLocalTrackCopies(track.id, updatedTrack)
                        localMetadataEditorTrack = updatedTrack
                        message = "已保存歌词：${track.title}"
                        localMetadataSearchMessage = "歌词已保存"
                    }.onFailure {
                        setError(it)
                    }
                }
            }.onFailure {
                localMetadataSearchMessage = it.message ?: it::class.simpleName.orEmpty()
                setError(it)
            }
            isLoading = false
        }
    }

    fun search() {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            searchResults = emptyList()
            providerSearchResults = ProviderSearchResults()
            message = "请输入关键词"
            return
        }
        scope.launch {
            isLoading = true
            message = "正在搜索：$keyword"
            runCatching {
                withTimeout(25_000) {
                    when (searchScope) {
                        SearchScope.Local -> {
                            val local = localRepository.search(keyword)
                            providerSearchResults = ProviderSearchResults()
                            local
                        }
                        SearchScope.Provider -> {
                            val provider = providerRepository.searchAll(keyword, selectedSearchProviderId)
                            providerSearchResults = provider
                            provider.tracks
                        }
                        SearchScope.All -> {
                            val local = localRepository.search(keyword)
                            val provider = searchProviderIdsForSearch().map { providerId ->
                                providerRepository.searchAll(keyword, providerId)
                            }.mergeSearchResults()
                            providerSearchResults = provider
                            mergeResults(local, provider.tracks)
                        }
                    }
                }
            }.onSuccess {
                searchResults = it
                val total = when (searchScope) {
                    SearchScope.Local -> it.size
                    SearchScope.Provider,
                    SearchScope.All -> providerSearchResults.totalCount() + if (searchScope == SearchScope.All) {
                        localOnlyCount(it, providerSearchResults.tracks)
                    } else {
                        0
                    }
                }
                message = when {
                    total == 0 && providerSearchResults.errorMessage != null -> providerSearchResults.errorMessage.orEmpty()
                    total == 0 -> "没有搜索结果"
                    else -> "搜索到 $total 项"
                }
            }.onFailure {
                setError(it)
            }
            isLoading = false
        }
    }

    private fun searchTrackText(text: String, providerId: String?) {
        val keyword = text.trim()
        if (keyword.isBlank()) {
            message = "没有可搜索的信息"
            return
        }
        query = keyword
        if (providerId != null && providers.any { it.providerId == providerId }) {
            searchScope = SearchScope.Provider
            selectedSearchProviderId = providerId
        }
        navigator.navigate(AppRoute.Search)
        search()
    }

    fun refreshHomeContent(section: HomeSection = homeSection) {
        refreshHomeContent(section, refreshCatalog = true)
    }

    private fun refreshHomeContent(section: HomeSection, refreshCatalog: Boolean) {
        if (section == HomeSection.Mine) {
            refreshActiveMineSection(refreshCatalog)
            return
        }
        scope.launch {
            isLoading = true
            val title = if (section == HomeSection.Recommend) "推荐" else "探索"
            message = "正在加载$title"
            runCatching {
                if (refreshCatalog) refreshProviderCatalog()
                val category = when (section) {
                    HomeSection.Recommend -> ProviderFeatureCategory.Recommend
                    HomeSection.Music -> ProviderFeatureCategory.Music
                    HomeSection.Mine -> error("mine section is loaded separately")
                }
                withTimeout(30_000) {
                    providerFeatures.filter {
                        it.category == category && it.providerId in selectedProviderIdsFor(
                            if (section == HomeSection.Recommend) ProviderDisplaySection.Recommend else ProviderDisplaySection.Explore,
                        )
                    }.map { feature ->
                        if (feature.requiresLogin && !isProviderLoggedIn(feature.providerId)) {
                            ProviderContentSection(feature, isLoginRequired = true)
                        } else if (feature.isDeferredHomeFeature()) {
                            ProviderContentSection(feature)
                        } else {
                            runCatching { providerRepository.loadFeaturePage(feature, offset = 0) }
                                .getOrElse { ProviderContentSection(feature, errorMessage = it.message ?: "加载失败") }
                        }
                    }.sortedSectionsByOrder()
                }
            }.onSuccess {
                if (section == HomeSection.Recommend) {
                    recommendSections = it
                } else {
                    musicSections = it
                }
                message = if (it.isEmpty()) "$title 暂无内容" else "$title 已更新"
            }.onFailure {
                setError(it)
            }
            isLoading = false
        }
    }

    fun refreshMinePlaylistContent() {
        refreshMinePlaylistContent(refreshCatalog = true)
    }

    private fun refreshMinePlaylistContent(refreshCatalog: Boolean) {
        scope.launch {
            isLoading = true
            message = "正在加载我的歌单"
            runCatching {
                if (refreshCatalog) refreshProviderCatalog()
                val userPlaylists = loadProviderSections(ProviderFeatureCategory.MinePlaylists, ::isMineProviderFeature)
                val favoritePlaylists = loadProviderSections(ProviderFeatureCategory.MineFavoritePlaylists, ::isMineProviderFeature)
                userPlaylists to favoritePlaylists
            }.onSuccess {
                minePlaylistSections = it.first
                mineFavoritePlaylistSections = it.second
                message = if (it.first.isEmpty() && it.second.isEmpty()) "歌单暂无内容" else "歌单已更新"
            }.onFailure {
                setError(it)
            }
            isLoading = false
        }
    }

    fun refreshMineContent() {
        refreshMineContent(refreshCatalog = true)
    }

    private fun refreshMineContent(refreshCatalog: Boolean) {
        scope.launch {
            isLoading = true
            message = "正在加载我的内容"
            runCatching {
                if (refreshCatalog) refreshProviderCatalog()
                loadProviderSections(ProviderFeatureCategory.Mine, ::isMineProviderFeature)
            }.onSuccess {
                mineSections = it
                message = if (it.isEmpty()) "我的内容暂无内容" else "我的内容已更新"
            }.onFailure {
                setError(it)
            }
            isLoading = false
        }
    }

    private suspend fun loadProviderSections(
        category: ProviderFeatureCategory,
        includeFeature: (ProviderFeature) -> Boolean = { true },
    ): List<ProviderContentSection> {
        return withTimeout(30_000) {
            providerFeatures.filter { it.category == category && includeFeature(it) }.map { feature ->
                if (feature.requiresLogin && !isProviderLoggedIn(feature.providerId)) {
                    ProviderContentSection(feature, isLoginRequired = true)
                } else {
                    runCatching { providerRepository.loadFeaturePage(feature, offset = 0) }
                        .getOrElse { ProviderContentSection(feature, errorMessage = it.message ?: "加载失败") }
                }
            }.sortedSectionsByOrder()
        }
    }

    private fun refreshActiveMineSectionIfNeeded() {
        when (mineSection) {
            MineSection.Playlists -> if (minePlaylistSections.isEmpty() && mineFavoritePlaylistSections.isEmpty()) {
                refreshMinePlaylistContent()
            }
            MineSection.Songs,
            MineSection.Artists,
            MineSection.Albums -> if (mineSections.isEmpty()) refreshMineContent()
            MineSection.LocalMusic -> ensureLocalMusic()
        }
    }

    private fun refreshActiveMineSection(refreshCatalog: Boolean = true) {
        when (mineSection) {
            MineSection.Playlists -> refreshMinePlaylistContent(refreshCatalog)
            MineSection.Songs,
            MineSection.Artists,
            MineSection.Albums -> refreshMineContent(refreshCatalog)
            MineSection.LocalMusic -> ensureLocalMusic()
        }
    }

    private fun refreshActiveMineProviderContent() {
        when (mineSection) {
            MineSection.Playlists -> refreshMinePlaylistContent()
            MineSection.Songs,
            MineSection.Artists,
            MineSection.Albums -> refreshMineContent()
            MineSection.LocalMusic -> Unit
        }
    }

    fun openFeature(feature: ProviderFeature) {
        navigator.navigate(AppRoute.Feature)
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
                    selectedFeatureError = it.message ?: it::class.simpleName.orEmpty()
                    setError(it)
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
        navigator.navigate(AppRoute.Track)
        selectedTrack = track
        selectedTrackError = null
        selectedTrackSimilar = emptyList()
        selectedTrackComments = emptyList()
        selectedTrackVideo = null
        selectedTrackRelatedError = null
        loadSelectedTrackRelated(track)
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
        navigator.navigate(AppRoute.Track)
        val placeholder = MusicTrack(
            id = resource.toProviderTrackId(),
            title = resource.title,
            artists = resource.artists,
            album = resource.album,
            source = resource.providerId,
            sourceType = TrackSourceType.Provider,
            providerName = resource.providerName,
        )
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
        navigator.navigate(AppRoute.Video)
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

    fun canAddTrackToProviderPlaylist(track: MusicTrack): Boolean {
        val providerId = trackProviderId(track) ?: return false
        return track.sourceType == TrackSourceType.Provider &&
            isProviderLoggedIn(providerId) &&
            providerCapabilities[providerId]?.canAddSongToPlaylist == true
    }

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
                    if (result.success) refreshAfterProviderMutation(providerId)
                }
                .onFailure(::setError)
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
                    if (result.success) {
                        closePlaylist()
                        refreshAfterProviderMutation(playlist.providerId)
                    }
                }
                .onFailure(::setError)
            isLoading = false
        }
    }

    fun openPlaylistTargetPicker(track: MusicTrack) {
        if (!canAddTrackToProviderPlaylist(track)) return
        playlistTargetTrack = track
        playlistOperationTargets = emptyList()
        playlistOperationError = null
        scope.launch {
            isLoading = true
            message = "正在加载可添加歌单"
            runCatching { providerRepository.playlistOperationTargets(track) }
                .onSuccess {
                    playlistOperationTargets = it
                    playlistOperationError = if (it.isEmpty()) "没有可添加的歌单" else null
                    message = if (it.isEmpty()) "没有可添加的歌单" else "请选择目标歌单"
                }
                .onFailure {
                    playlistOperationError = it.message ?: it::class.simpleName.orEmpty()
                    setError(it)
                }
            isLoading = false
        }
    }

    fun closePlaylistTargetPicker() {
        playlistTargetTrack = null
        playlistOperationTargets = emptyList()
        playlistOperationError = null
    }

    fun addTrackToProviderPlaylist(playlist: ProviderPlaylist) {
        val track = playlistTargetTrack ?: return
        scope.launch {
            isLoading = true
            message = "正在添加到歌单"
            runCatching { providerRepository.addTrackToPlaylist(playlist, track) }
                .onSuccess { result ->
                    if (result.success) {
                        message = result.message.ifBlank { "已添加到：${playlist.title}" }
                        playlistOperationFeedback = message
                        closePlaylistTargetPicker()
                        refreshAfterProviderMutation(playlist.providerId)
                    } else {
                        playlistOperationError = result.message.ifBlank { "添加失败" }
                        message = playlistOperationError.orEmpty()
                        playlistOperationFeedback = message
                    }
                }
                .onFailure {
                    playlistOperationError = it.message ?: it::class.simpleName.orEmpty()
                    setError(it)
                    playlistOperationFeedback = message
                }
            isLoading = false
        }
    }

    fun canRemoveTrackFromSelectedPlaylist(track: MusicTrack): Boolean {
        val playlist = selectedPlaylist ?: return false
        return track.sourceType == TrackSourceType.Provider &&
            selectedPlaylistCategory == ProviderFeatureCategory.MinePlaylists &&
            trackProviderId(track) == playlist.providerId &&
            isProviderLoggedIn(playlist.providerId) &&
            providerCapabilities[playlist.providerId]?.canRemoveSongFromPlaylist == true
    }

    fun canSetSongDisliked(track: MusicTrack, disliked: Boolean): Boolean {
        val providerId = trackProviderId(track) ?: return false
        val capabilities = providerCapabilities[providerId] ?: return false
        return track.sourceType == TrackSourceType.Provider &&
            isProviderLoggedIn(providerId) &&
            if (disliked) capabilities.canAddDislikedSong else capabilities.canRemoveDislikedSong
    }

    fun setSongDisliked(track: MusicTrack, disliked: Boolean) {
        if (!canSetSongDisliked(track, disliked)) return
        scope.launch {
            isLoading = true
            message = if (disliked) "正在设为不喜欢" else "正在取消不喜欢"
            runCatching { providerRepository.setSongDisliked(track, disliked) }
                .onSuccess { result ->
                    if (result.success) {
                        if (disliked) removeDislikedTrack(track) else refreshMineContent()
                        message = result.message.ifBlank { if (disliked) "已设为不喜欢" else "已取消不喜欢" }
                    } else {
                        message = result.message.ifBlank { "操作失败" }
                    }
                }
                .onFailure(::setError)
            isLoading = false
        }
    }

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

    fun removeTrackFromSelectedPlaylist(track: MusicTrack) {
        val playlist = selectedPlaylist ?: return
        if (!canRemoveTrackFromSelectedPlaylist(track)) return
        scope.launch {
            isLoading = true
            message = "正在从歌单移除"
            runCatching { providerRepository.removeTrackFromPlaylist(playlist, track) }
                .onSuccess { result ->
                    if (result.success) {
                        selectedPlaylistTracks = selectedPlaylistTracks.filterNot { it.id == track.id }
                        message = result.message.ifBlank { "已从歌单移除：${track.title}" }
                        playlistOperationFeedback = message
                        refreshAfterProviderMutation(playlist.providerId)
                    } else {
                        selectedPlaylistError = result.message.ifBlank { "移除失败" }
                        message = selectedPlaylistError.orEmpty()
                        playlistOperationFeedback = message
                    }
                }
                .onFailure {
                    setError(it)
                    playlistOperationFeedback = message
                }
            isLoading = false
        }
    }

    fun dismissPlaylistOperationFeedback(feedback: String) {
        if (playlistOperationFeedback == feedback) {
            playlistOperationFeedback = null
        }
    }

    fun openPlaylist(playlist: ProviderPlaylist, category: ProviderFeatureCategory? = null) {
        navigator.navigate(AppRoute.Playlist)
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
        navigator.navigate(AppRoute.MediaItem)
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
        if (selectedFeatureTracks.size - visibleIndex <= LIST_PREFETCH_REMAINING) {
            loadMoreSelectedFeatureTracks()
        }
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
                val seenIds = selectedFeatureTracks.mapTo(mutableSetOf()) { it.id }
                val newTracks = section.tracks.filter { seenIds.add(it.id) }
                if (newTracks.isNotEmpty()) {
                    selectedFeatureTracks = selectedFeatureTracks + newTracks
                    val updatedSection = section.copy(
                        tracks = selectedFeatureTracks,
                        nextOffset = section.nextOffset,
                        hasMore = section.hasMore,
                    )
                    selectedFeatureContent = updatedSection
                    updateHomeFeatureSection(updatedSection)
                }
                selectedFeatureTracksNextOffset = section.nextOffset
                selectedFeatureTracksHasMore = section.hasMore
                section.nextOffset != offset || newTracks.isNotEmpty()
            },
            onFailure = {
                selectedFeatureError = it.message ?: it::class.simpleName.orEmpty()
                false
            },
        )
    }

    private fun loadMoreSelectedPlaylistTracks() {
        if (selectedPlaylist == null || !selectedPlaylistTracksHasMore) return
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

    private suspend fun ensureAllSelectedPlaylistTracks() {
        selectedPlaylistLoadMoreJob?.join()
        while (selectedPlaylistTracksHasMore) {
            if (!appendSelectedPlaylistTracksPage()) break
        }
    }

    private suspend fun ensureSelectedPlaylistTracksAtLeast(count: Int) {
        selectedPlaylistLoadMoreJob?.join()
        while (selectedPlaylistTracksHasMore && selectedPlaylistTracks.size < count) {
            if (!appendSelectedPlaylistTracksPage()) break
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
        selectedPlaylistBackgroundLoadJob?.cancel()
        scope.launch {
            isLoading = true
            message = "正在加载前 $PLAYLIST_PLAYBACK_INITIAL_TRACK_COUNT 首：${playlist.title}"
            ensureSelectedPlaylistTracksAtLeast(maxOf(PLAYLIST_PLAYBACK_INITIAL_TRACK_COUNT, index + 1))
            selectedPlaylistTracks.getOrNull(index)?.let { track ->
                play(track, selectedPlaylistTracks, index, sourcePlaylistId = playlist.id)
            }
            isLoading = false
            if (selectedPlaylistTracksHasMore && queuePlaylistId == playlist.id) {
                selectedPlaylistBackgroundLoadJob = scope.launch {
                    ensureAllSelectedPlaylistTracks()
                    if (!selectedPlaylistTracksHasMore) {
                        syncPlaylistPlaybackQueue(playlist)
                    }
                }
            }
        }
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

    fun playQueueIndex(index: Int) {
        val currentOffset = if (currentQueueTrack() != null) 1 else 0
        if (index == 0 && currentOffset == 1) {
            currentQueueTrack()?.let(::startPlayback)
            return
        }
        val pendingOffset = currentOffset
        val pendingEnd = pendingOffset + upNextQueue.size
        when {
            index in pendingOffset until pendingEnd -> {
                val upNextIndex = index - pendingOffset
                playUpNextIndex(upNextIndex)
            }
            else -> {
                val mainStartIndex = when {
                    currentIsUpNext -> mainQueueIndex + 1
                    mainQueueIndex >= 0 -> mainQueueIndex + 1
                    else -> 0
                }
                val mainIndex = mainStartIndex + (index - pendingEnd)
                playMainIndex(mainIndex)
            }
        }
    }

    fun playPlaybackPart(index: Int) {
        if (index !in playbackParts.indices) return
        currentQueueTrack()?.let { startPlayback(it, requestedPartIndex = index) }
    }

    fun toggle() {
        when (playbackState.status) {
            PlayerStatus.Playing -> pause()
            PlayerStatus.Paused, PlayerStatus.Idle, PlayerStatus.Ended -> play()
            else -> Unit
        }
    }

    fun play() {
        when (playbackState.status) {
            PlayerStatus.Paused -> {
                if (playbackState.currentTrack != null) playbackEngine.resume()
            }
            PlayerStatus.Idle, PlayerStatus.Ended -> {
                (currentQueueTrack() ?: playbackState.currentTrack)?.let(::startPlayback)
            }
            else -> Unit
        }
    }

    fun pause() {
        if (playbackState.status == PlayerStatus.Playing) {
            playbackEngine.pause()
        }
    }

    fun stop() {
        if (playbackState.status != PlayerStatus.Idle) {
            playbackEngine.stop()
        }
    }

    fun next() {
        if (_repeatMode == RepeatMode.SINGLE) {
            if (playPlaybackPartOffset(1, wrap = true)) return
            (currentQueueTrack() ?: playbackState.currentTrack)?.let { startPlayback(it) }
            return
        }
        if (playPlaybackPartOffset(1)) return
        if (currentIsUpNext) {
            currentUpNextTrack = null
            currentIsUpNext = false
            persistPlaybackQueue()
        }
        if (upNextQueue.isNotEmpty()) {
            playUpNextIndex(0)
            return
        }
        if (mainQueue.isEmpty()) return
        val feature = queueFeature
        if (feature != null && mainQueueIndex >= mainQueue.lastIndex) {
            scope.launch {
                val nextIndex = mainQueue.size
                val appendedCount = appendFeatureQueue(feature)
                if (appendedCount > 0 && queueFeature == feature) {
                    playMainIndex(nextIndex)
                } else if (queueFeature == feature) {
                    message = "${feature.title} 暂无后续歌曲"
                }
            }
            return
        }
        val nextIndex = mainQueueIndex + 1
        if (nextIndex < mainQueue.size) {
            playMainIndex(nextIndex)
        } else if (_repeatMode == RepeatMode.QUEUE) {
            playMainIndex(0)
        }
    }

    fun previous() {
        if (_repeatMode == RepeatMode.SINGLE) {
            if (playPlaybackPartOffset(-1, wrap = true)) return
            (currentQueueTrack() ?: playbackState.currentTrack)?.let { startPlayback(it) }
            return
        }
        if (playPlaybackPartOffset(-1)) return
        if (currentIsUpNext) {
            currentUpNextTrack = null
            currentIsUpNext = false
            persistPlaybackQueue()
            playMainIndex(mainQueueIndex.coerceAtLeast(0))
            return
        }
        if (mainQueue.isEmpty()) return
        val previousIndex = mainQueueIndex - 1
        if (previousIndex >= 0) {
            playMainIndex(previousIndex)
        } else if (_repeatMode == RepeatMode.QUEUE) {
            playMainIndex(mainQueue.lastIndex)
        }
    }

    fun seekTo(positionMs: Long) {
        playbackEngine.seekTo(positionMs)
    }

    fun setVolume(volume: Double) {
        playbackEngine.setVolume(volume.coerceIn(0.0, 1.0))
    }

    fun openFullPlayer() {
        isFullPlayerOpen = true
    }

    fun closeFullPlayer() {
        isFullPlayerOpen = false
    }

    fun toggleQueue() {
        isQueueOpen = !isQueueOpen
    }

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
        setShuffleEnabled(!shuffleEnabledState)
    }

    fun setShuffleEnabled(enabled: Boolean) {
        if (isFmQueue || shuffleEnabledState == enabled) return
        if (enabled) {
            enableShuffle()
        } else {
            disableShuffle()
        }
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    fun toggleRepeat() {
        setRepeatMode(
            when (_repeatMode) {
                RepeatMode.OFF -> RepeatMode.QUEUE
                RepeatMode.QUEUE -> RepeatMode.SINGLE
                RepeatMode.SINGLE -> RepeatMode.OFF
            },
        )
    }

    fun setRepeatMode(repeatMode: RepeatMode) {
        if (isFmQueue || _repeatMode == repeatMode) return
        _repeatMode = repeatMode
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    fun download(track: MusicTrack) {
        if (track.sourceType != TrackSourceType.Provider) return
        downloadQueueFeedback = "已加入下载队列：${track.title}"
        scope.launch {
            runCatching {
                val payload = providerRepository.resolve(
                    track,
                    unavailablePlaybackPolicy,
                    selectedSmartReplacementProviderIds(),
                    smartReplacementMinScore,
                    !smartReplacementUseReplacementMetadata,
                    !smartReplacementUseReplacementLyrics,
                )
                downloadRepository.download(track, payload)
            }
                .onFailure { setError(it) }
        }
    }

    fun pauseDownload(taskId: String) = runDownloadAction { downloadRepository.pause(taskId) }
    fun resumeDownload(taskId: String) = runDownloadAction { downloadRepository.resume(taskId) }
    fun retryDownload(taskId: String) = runDownloadAction { downloadRepository.retry(taskId) }

    fun deleteDownloadTask(taskId: String, deleteFile: Boolean) = runDownloadAction {
        downloadRepository.deleteTask(taskId, deleteFile)
    }

    fun deleteDownload(track: MusicTrack) {
        scope.launch {
            runCatching { downloadRepository.deleteDownloaded(track) }
                .onSuccess {
                    if (hasLocalMusicPermission) {
                        refreshLocalMusic(forceRefresh = true, showLoading = homeSection == HomeSection.Mine && mineSection == MineSection.LocalMusic)
                    }
                    message = "已删除下载：${track.title}"
                }
                .onFailure { setError(it) }
        }
    }

    private fun runDownloadAction(action: suspend () -> Unit) {
        scope.launch {
            runCatching { action() }
                .onFailure { setError(it) }
        }
    }

    fun openTrackArtist(track: MusicTrack) {
        openTrackArtist(track, loadDetailWhenMissing = true)
    }

    fun closeArtistTargetPicker() {
        artistTargetTrack = null
        artistTargets = emptyList()
    }

    fun openArtistTarget(target: TrackArtistTarget) {
        val track = artistTargetTrack ?: return
        closeArtistTargetPicker()
        closeFullPlayer()
        target.mediaItem?.let(::openMediaItem)
            ?: searchTrackText(target.name, track.source.takeIf { it.isNotBlank() })
    }

    private fun openTrackArtist(track: MusicTrack, loadDetailWhenMissing: Boolean) {
        val targets = track.artistNavigationTargets()
        if (
            loadDetailWhenMissing &&
            targets.any { it.mediaItem == null } &&
            track.canLoadProviderDetail()
        ) {
            scope.launch {
                isLoading = true
                val detail = runCatching { providerRepository.trackDetail(track.providerTrackId()) }
                isLoading = false
                detail
                    .onSuccess { openTrackArtist(it, loadDetailWhenMissing = false) }
                    .onFailure { openTrackArtist(track, loadDetailWhenMissing = false) }
            }
            return
        }
        if (targets.size > 1) {
            artistTargetTrack = track
            artistTargets = targets
            return
        }
        targets.singleOrNull()?.mediaItem?.let {
            closeFullPlayer()
            openMediaItem(it)
            return
        }
        val target = targets.singleOrNull()?.name.orEmpty().ifBlank { track.artists.trim() }
        closeFullPlayer()
        searchTrackText(target, track.source.takeIf { it.isNotBlank() })
    }

    fun openTrackAlbum(track: MusicTrack) {
        openTrackAlbum(track, loadDetailWhenMissing = true)
    }

    private fun openTrackAlbum(track: MusicTrack, loadDetailWhenMissing: Boolean) {
        val albumName = track.album.trim()
        val providerId = track.source.takeIf { it.isNotBlank() }
        val providerName = track.providerName ?: providerId.orEmpty()
        val itemId = track.albumItemId
        if (!itemId.isNullOrBlank() && providerId != null && albumName.isNotBlank()) {
            closeFullPlayer()
            openMediaItem(
                ProviderMediaItem(
                    id = itemId,
                    title = albumName,
                    providerId = providerId,
                    providerName = providerName,
                    type = ProviderMediaItemType.Album,
                )
            )
            return
        }
        if (loadDetailWhenMissing && track.canLoadProviderDetail()) {
            scope.launch {
                isLoading = true
                val detail = runCatching { providerRepository.trackDetail(track.providerTrackId()) }
                isLoading = false
                detail
                    .onSuccess { openTrackAlbum(it, loadDetailWhenMissing = false) }
                    .onFailure { openTrackAlbum(track, loadDetailWhenMissing = false) }
            }
            return
        }
        closeFullPlayer()
        searchTrackText(albumName, providerId)
    }

    private fun MusicTrack.artistNavigationTargets(): List<TrackArtistTarget> {
        if (artistItems.isNotEmpty()) {
            return artistItems.distinctBy { it.id }.map { TrackArtistTarget(it.title, it) }
        }
        val names = artists
            .split(" / ", "/", "·", ",", "，", "、")
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        val providerId = source.takeIf { it.isNotBlank() }
        val firstItem = if (!artistItemId.isNullOrBlank() && providerId != null && names.isNotEmpty()) {
            ProviderMediaItem(
                id = artistItemId,
                title = names.first(),
                providerId = providerId,
                providerName = providerName ?: providerId,
                type = ProviderMediaItemType.Artist,
            )
        } else {
            null
        }
        return names.mapIndexed { index, name ->
            TrackArtistTarget(name, firstItem.takeIf { index == 0 })
        }
    }

    private fun MusicTrack.canLoadProviderDetail(): Boolean =
        sourceType != TrackSourceType.LocalMediaStore && providerTrackId().isNotBlank()

    private fun MusicTrack.providerTrackId(): String = providerId?.takeIf { it.isNotBlank() } ?: id

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
        val providerIds = loadedProviders.map { it.providerId }.toSet()
        if (selectedSettingsProviderId !in providerIds) {
            selectedSettingsProviderId = loadedProviders.firstOrNull()?.providerId
        }
        if (settingsLoginProviderId !in providerIds) {
            settingsLoginProviderId = null
        }
        if (selectedSearchProviderId !in providerIds) {
            selectedSearchProviderId = null
            if (searchScope == SearchScope.Provider) {
                searchScope = SearchScope.All
            }
        }
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
        closeArtistTargetPicker()
    }

    private fun reorderProviderContent() {
        recommendSections = recommendSections.sortedSectionsByOrder()
        musicSections = musicSections.sortedSectionsByOrder()
        mineSections = mineSections.sortedSectionsByOrder()
        minePlaylistSections = minePlaylistSections.sortedSectionsByOrder()
        mineFavoritePlaylistSections = mineFavoritePlaylistSections.sortedSectionsByOrder()
    }

    private fun refreshAllProviderAuthStates(refreshUserInfo: Boolean = false) {
        scope.launch {
            refreshProviderAuthStates(refreshUserInfo)
        }
    }

    private suspend fun refreshProviderAuthStates(refreshUserInfo: Boolean = false) {
        providers.forEach { provider ->
            runCatching {
                if (refreshUserInfo) {
                    providerSessionRepository.refresh(provider.providerId, refreshUserInfo = true)
                } else {
                    providerSessionRepository.refresh(provider.providerId)
                }
            }
                .onFailure { setError(it) }
        }
    }

    private fun isProviderLoggedIn(providerId: String): Boolean {
        return providerSessionRepository.state.value.authStates[providerId]?.isLoggedIn == true
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
    ) {
        val requestSerial = ++playRequestSerial
        val playbackTrack = track.preferDownloaded()
        val isPlaybackPartRequest = requestedPartIndex != null && playbackParts.isNotEmpty()
        if (!isPlaybackPartRequest) {
            playbackParts = emptyList()
            currentPartIndex = -1
        }
        updateCurrentTrack(playbackTrack)
        playbackState = playbackState.copy(
            status = PlayerStatus.Loading,
            currentTrack = playbackTrack,
            queue = displayQueue(),
            queueIndex = displayQueueIndex(),
            positionMs = 0,
            playbackParts = playbackParts,
            currentPartIndex = if (isPlaybackPartRequest) requestedPartIndex ?: -1 else -1,
            errorMessage = null,
        )
        persistPlaybackQueue()
        playbackEngine.prepareLoading(playbackTrack)
        isLoading = true
        message = "正在播放：${track.title}"
        val resolveTrack = requestedPartIndex
            ?.let { index -> playbackParts.getOrNull(index) }
            ?.toTrack(playbackTrack)
            ?: playbackTrack
        if (!playbackEngine.resolvesResourcesInternally) {
            scope.launch playRequest@{
                runCatching {
                    val payload = resolveTrack.toPayload()
                        ?: providerRepository.resolve(
                            resolveTrack,
                            unavailablePlaybackPolicy,
                            selectedSmartReplacementProviderIds(),
                            smartReplacementMinScore,
                            !smartReplacementUseReplacementMetadata,
                            !smartReplacementUseReplacementLyrics,
                        )
                    if (requestSerial != playRequestSerial) return@playRequest
                    val nextParts = payload.parts
                    val nextPartIndex = when {
                        nextParts.isEmpty() -> -1
                        payload.currentPartIndex in nextParts.indices -> payload.currentPartIndex
                        requestedPartIndex != null && requestedPartIndex in nextParts.indices -> requestedPartIndex
                        else -> -1
                    }
                    playbackParts = nextParts
                    currentPartIndex = nextPartIndex
                    val isMultipartPlayback = playbackParts.isNotEmpty()
                    val playableTrack = playbackTrack.copy(
                        title = if (isMultipartPlayback) playbackTrack.title else payload.title.ifBlank { playbackTrack.title },
                        artists = payload.artists.ifBlank { playbackTrack.artists },
                        album = payload.album.ifBlank { playbackTrack.album },
                        source = payload.source.ifBlank { playbackTrack.source },
                        coverUrl = payload.coverUrl ?: playbackTrack.coverUrl,
                        durationMs = if (isMultipartPlayback) playbackTrack.durationMs else payload.durationMs ?: playbackTrack.durationMs,
                        providerName = payload.providerName ?: playbackTrack.providerName,
                        isSmartReplacement = payload.isSmartReplacement,
                        originalTitle = payload.originalTitle,
                        originalProviderName = payload.originalProviderName,
                        originalCoverUrl = payload.originalCoverUrl,
                        replacementTitle = payload.replacementTitle,
                        replacementArtists = payload.replacementArtists,
                        replacementSource = payload.replacementSource,
                        replacementProviderName = payload.replacementProviderName,
                        replacementCoverUrl = payload.replacementCoverUrl,
                        replacementStrategy = payload.replacementStrategy,
                        replacementScore = payload.replacementScore,
                        isUnavailable = false,
                    )
                    updateCurrentTrack(playableTrack)
                    playbackEngine.play(playableTrack, payload)
                    playbackState = playbackState.copy(
                        status = PlayerStatus.Loading,
                        currentTrack = playableTrack,
                        queue = displayQueue(),
                        queueIndex = displayQueueIndex(),
                        lyrics = payload.lyrics,
                        audioQuality = payload.audioQuality,
                        playbackParts = playbackParts,
                        currentPartIndex = currentPartIndex,
                    )
                    persistPlaybackQueue()
                    message = currentPlaybackPartLabel()?.let { "${playableTrack.title} · $it" }
                        ?: "${playableTrack.title} - ${playableTrack.artists}"
                    prefetchFeatureQueueIfNeeded()
                }.onFailure { throwable ->
                    if (requestSerial == playRequestSerial && !skipUnavailableTrack(track, skippedUnavailableCount, throwable)) {
                        setError(throwable)
                    }
                }
                if (requestSerial == playRequestSerial) isLoading = false
            }
            return
        }
        playbackEngine.play(
            PlaybackPlan(
                generation = requestSerial,
                requests = buildList {
                    add(
                        PlaybackRequest(
                            track = playbackTrack,
                            resolveTrack = resolveTrack,
                            requestedPartIndex = requestedPartIndex,
                            unavailablePolicy = unavailablePlaybackPolicy,
                            smartReplacementProviderIds = selectedSmartReplacementProviderIds(),
                            smartReplacementMinScore = smartReplacementMinScore,
                            smartReplacementUseOriginalMetadata = !smartReplacementUseReplacementMetadata,
                            smartReplacementUseOriginalLyrics = !smartReplacementUseReplacementLyrics,
                        ),
                    )
                    displayQueue()
                        .drop(1)
                        .forEach { queuedTrack ->
                            val nextTrack = queuedTrack.preferDownloaded()
                            add(
                                PlaybackRequest(
                                    track = nextTrack,
                                    unavailablePolicy = unavailablePlaybackPolicy,
                                    smartReplacementProviderIds = selectedSmartReplacementProviderIds(),
                                    smartReplacementMinScore = smartReplacementMinScore,
                                    smartReplacementUseOriginalMetadata = !smartReplacementUseReplacementMetadata,
                                    smartReplacementUseOriginalLyrics = !smartReplacementUseReplacementLyrics,
                                ),
                            )
                        }
                },
            ),
        )
    }

    private fun playFirst(
        sourceQueue: List<MusicTrack>,
        sourceFeature: ProviderFeature? = null,
        sourcePlaylistId: String? = null,
    ) {
        if (sourceQueue.isEmpty()) return
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
            shuffleBeforeFm = shuffleEnabledState
            shuffleEnabledState = false
        } else if (!enteringFm && restoreShuffle != null) {
            shuffleEnabledState = restoreShuffle
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
        if (shuffleEnabledState && !enteringFm) {
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

    private fun syncPlaylistPlaybackQueue(playlist: ProviderPlaylist) {
        if (queuePlaylistId != playlist.id || selectedPlaylist?.id != playlist.id) return
        val currentMainTrack = mainQueue.getOrNull(mainQueueIndex)
        originalMainQueue = selectedPlaylistTracks
        if (shuffleEnabledState) {
            mainQueue = listOfNotNull(currentMainTrack) + selectedPlaylistTracks
                .filterNot { it.id == currentMainTrack?.id }
                .shuffled()
            mainQueueIndex = currentMainTrack?.let { 0 } ?: mainQueueIndex.coerceIn(0, mainQueue.lastIndex)
        } else {
            mainQueue = selectedPlaylistTracks.map { track ->
                currentMainTrack?.takeIf { it.id == track.id } ?: track
            }
            mainQueueIndex = currentMainTrack?.let { track ->
                mainQueue.indexOfFirst { it.id == track.id }
            }?.takeIf { it >= 0 } ?: mainQueueIndex.coerceIn(-1, mainQueue.lastIndex)
            originalMainQueue = emptyList()
        }
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    private fun playMainIndex(index: Int, skippedUnavailableCount: Int = 0) {
        val track = mainQueue.getOrNull(index) ?: return
        currentUpNextTrack = null
        currentIsUpNext = false
        mainQueueIndex = index
        startPlayback(track, skippedUnavailableCount)
    }

    private fun playUpNextIndex(index: Int) {
        val track = upNextQueue.getOrNull(index) ?: return
        upNextQueue = upNextQueue.filterIndexed { itemIndex, _ -> itemIndex != index }
        currentUpNextTrack = track
        currentIsUpNext = true
        updatePlaybackQueueState()
        persistPlaybackQueue()
        startPlayback(track)
    }

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
        val downloaded = downloadStates[id] as? DownloadState.Downloaded ?: return this
        return copy(
            sourceType = TrackSourceType.Downloaded,
            localUri = downloaded.uri,
            providerId = providerId ?: id,
        )
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
            originalTitle = originalTitle,
            originalProviderName = originalProviderName,
            originalCoverUrl = originalCoverUrl,
            replacementTitle = replacementTitle,
            replacementArtists = replacementArtists,
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

    private fun playPlaybackPartOffset(offset: Int, wrap: Boolean = false): Boolean {
        if (playbackParts.isEmpty() || currentPartIndex < 0) return false
        val nextPartIndex = currentPartIndex + offset
        val targetPartIndex = if (wrap) {
            nextPartIndex.floorMod(playbackParts.size)
        } else {
            nextPartIndex.takeIf { it in playbackParts.indices } ?: return false
        }
        currentQueueTrack()?.let { startPlayback(it, requestedPartIndex = targetPartIndex) } ?: return false
        return true
    }

    private fun Int.floorMod(divisor: Int): Int {
        return ((this % divisor) + divisor) % divisor
    }

    private fun currentPlaybackPartLabel(): String? {
        val part = playbackParts.getOrNull(currentPartIndex) ?: return null
        return "第 ${currentPartIndex + 1}P · ${part.title.ifBlank { "未命名分段" }}"
    }

    private fun currentQueueTrack(): MusicTrack? {
        return if (currentIsUpNext) currentUpNextTrack else mainQueue.getOrNull(mainQueueIndex)
    }

    private fun displayQueue(): List<MusicTrack> {
        return buildList {
            currentQueueTrack()?.let { add(it) }
            addAll(upNextQueue)
            val nextMainIndex = when {
                currentIsUpNext -> mainQueueIndex + 1
                mainQueueIndex >= 0 -> mainQueueIndex + 1
                else -> 0
            }
            if (nextMainIndex in 0..mainQueue.size) {
                addAll(mainQueue.drop(nextMainIndex))
            }
        }
    }

    private fun displayQueueIndex(): Int {
        return if (currentQueueTrack() != null) 0 else -1
    }

    private fun updateCurrentTrack(track: MusicTrack) {
        if (currentIsUpNext) {
            currentUpNextTrack = track
        } else if (mainQueueIndex in mainQueue.indices) {
            mainQueue = mainQueue.mapIndexed { index, item -> if (index == mainQueueIndex) track else item }
            originalMainQueue = originalMainQueue.map { item -> if (item.id == track.id) track else item }
        }
    }

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
        mainQueue = snapshot.mainQueue
        originalMainQueue = snapshot.originalMainQueue
        upNextQueue = snapshot.upNextQueue
        mainQueueIndex = snapshot.queueIndex.coerceIn(-1, mainQueue.lastIndex)
        shuffleEnabledState = snapshot.shuffleEnabled
        _repeatMode = snapshot.repeatMode
        isFmQueue = snapshot.isFmQueue
        shuffleBeforeFm = snapshot.shuffleBeforeFm
        currentUpNextTrack = null
        currentIsUpNext = false
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
        val snapshot = PlaybackQueueSnapshot(
            mainQueue = mainQueue,
            originalMainQueue = originalMainQueue,
            upNextQueue = upNextQueue,
            queueIndex = mainQueueIndex,
            shuffleEnabled = shuffleEnabledState,
            repeatMode = _repeatMode,
            isFmQueue = isFmQueue,
            shuffleBeforeFm = shuffleBeforeFm,
        )
        scope.launch {
            playbackQueueStore.save(snapshot)
        }
    }

    private fun enableShuffle() {
        if (isFmQueue || mainQueue.size <= 1) {
            shuffleEnabledState = !isFmQueue
            return
        }
        val current = currentQueueTrack()
        originalMainQueue = if (originalMainQueue.isEmpty()) mainQueue else originalMainQueue
        val currentInMain = current?.let { track -> mainQueue.firstOrNull { it.id == track.id } }
        val shuffledRest = mainQueue.filterNot { it.id == currentInMain?.id }.shuffled()
        mainQueue = listOfNotNull(currentInMain) + shuffledRest
        mainQueueIndex = currentInMain?.let { 0 } ?: mainQueueIndex.coerceIn(0, mainQueue.lastIndex)
        shuffleEnabledState = true
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
        shuffleEnabledState = false
    }

    private fun mergeResults(local: List<MusicTrack>, provider: List<MusicTrack>): List<MusicTrack> {
        val seen = linkedSetOf<String>()
        return (local + provider).filter { seen.add(it.id) }
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
        return category == ProviderFeatureCategory.Music ||
            (category == ProviderFeatureCategory.Recommend &&
                (id.endsWith("_daily_songs") || isDynamicQueueFeature() || isBilibiliRecommendedVideos()))
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

    private fun ProviderSearchResults.totalCount(): Int {
        return tracks.size + playlists.size + artists.size + albums.size + videos.size
    }

    private fun localOnlyCount(allTracks: List<MusicTrack>, providerTracks: List<MusicTrack>): Int {
        if (providerTracks.isEmpty()) return allTracks.size
        val providerIds = providerTracks.map { it.id }.toSet()
        return allTracks.count { it.id !in providerIds }
    }

    private fun applySettings(settings: AppSettings) {
        onboardingCompleted = settings.onboardingCompleted
        homeSection = settings.homeSection
        mineSection = settings.mineSection
        playlistFilter = settings.playlistFilter
        localMusicViewMode = settings.localMusicViewMode
        excludedLocalMusicDirectoryIds = settings.excludedLocalMusicDirectoryIds
        localMusicMinDurationSeconds = settings.localMusicMinDurationSeconds
        searchScope = settings.searchScope
        selectedSearchProviderId = settings.selectedSearchProviderId
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
        smartReplacementUseReplacementMetadata = settings.smartReplacementUseReplacementMetadata
        smartReplacementUseReplacementLyrics = settings.smartReplacementUseReplacementLyrics
        lyricFontSize = settings.lyricFontSize
        playbackSpectrumStyle = settings.playbackSpectrumStyle
        themeMode = settings.themeMode
        themeColorScheme = settings.themeColorScheme
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
            excludedLocalMusicDirectoryIds = excludedLocalMusicDirectoryIds,
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
            smartReplacementUseReplacementMetadata = smartReplacementUseReplacementMetadata,
            smartReplacementUseReplacementLyrics = smartReplacementUseReplacementLyrics,
            lyricFontSize = lyricFontSize,
            playbackSpectrumStyle = playbackSpectrumStyle,
            themeMode = themeMode,
            themeColorScheme = themeColorScheme,
        )
    }

    private suspend fun updateResourceCacheLimit() {
        resourceCacheRepository.updateLimit(
            CacheLimit(
                audioMaxBytes = audioCacheLimitMb.mbToBytes(),
                imageMaxBytes = imageCacheLimitMb.mbToBytes(),
            ),
        )
    }

    private suspend fun updateAudioQualityPolicies() {
        providerRepository.updateAudioQualityPolicies(wifiAudioQualityPolicy, cellularAudioQualityPolicy)
    }

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

    private fun refreshLocalMusicDirectories() {
        scope.launch {
            runCatching {
                updateLocalMusicScanSettings()
                localRepository.directories()
            }.onSuccess {
                localMusicDirectories = it
            }.onFailure {
                setError(it)
            }
        }
    }

    private suspend fun updateLocalMusicScanSettings() {
        localRepository.updateScanSettings(
            LocalMusicScanSettings(
                excludedDirectoryIds = excludedLocalMusicDirectoryIds,
                minDurationSeconds = localMusicMinDurationSeconds,
            )
        )
    }

    private fun setError(throwable: Throwable) {
        message = when (throwable) {
            is TimeoutCancellationException -> "操作超时，请检查网络后重试"
            else -> throwable.message ?: throwable::class.simpleName.orEmpty()
        }
        playbackState = playbackState.copy(status = PlayerStatus.Error, errorMessage = message)
        isLoading = false
    }

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

    private fun Int.mbToBytes(): Long = this.toLong() * 1024L * 1024L
}
