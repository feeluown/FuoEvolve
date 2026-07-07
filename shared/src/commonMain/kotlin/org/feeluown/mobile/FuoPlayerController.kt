package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

enum class SearchScope {
    Local,
    Provider,
    All,
}

enum class ProviderSearchTab {
    Songs,
    Artists,
    Albums,
    Playlists,
    Videos,
}

enum class HomeSection {
    Recommend,
    Music,
    Mine,
}

enum class MineSection {
    Playlists,
    Artists,
    Albums,
    LocalMusic,
}

enum class PlaylistFilter {
    All,
    UserPlaylists,
    FavoritePlaylists,
}

enum class LocalMusicViewMode {
    All,
    Artist,
    Album,
}

private const val DYNAMIC_QUEUE_PREFETCH_REMAINING = 2
private val DEFAULT_DEBUG_LOG_LEVEL_FILTERS = setOf(DebugLogLevel.Info, DebugLogLevel.Warning, DebugLogLevel.Error)

class FuoPlayerController(
    private val providerRepository: ProviderMusicRepository,
    private val localRepository: LocalMusicRepository,
    private val downloadRepository: DownloadRepository,
    private val playbackEngine: PlaybackEngine,
    private val settingsStore: AppSettingsStore = NoOpAppSettingsStore,
    private val playbackQueueStore: PlaybackQueueStore = NoOpPlaybackQueueStore,
    private val resourceCacheRepository: ResourceCacheRepository = NoOpResourceCacheRepository,
    private val debugLogRepository: DebugLogRepository = NoOpDebugLogRepository,
    private val scope: CoroutineScope,
) {
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
    var selectedPlaylistError by mutableStateOf<String?>(null)
        private set
    var selectedFeature by mutableStateOf<ProviderFeature?>(null)
        private set
    var selectedFeatureTracks by mutableStateOf<List<MusicTrack>>(emptyList())
        private set
    var selectedFeatureError by mutableStateOf<String?>(null)
        private set
    var selectedMediaItem by mutableStateOf<ProviderMediaItem?>(null)
        private set
    var selectedMediaItemTracks by mutableStateOf<List<MusicTrack>>(emptyList())
        private set
    var selectedMediaItemAlbums by mutableStateOf<List<ProviderMediaItem>>(emptyList())
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
    var isSearchOpen by mutableStateOf(false)
        private set
    var isFullPlayerOpen by mutableStateOf(false)
        private set
    var isSettingsOpen by mutableStateOf(false)
        private set
    var isDebugLogOpen by mutableStateOf(false)
        private set
    var isQueueOpen by mutableStateOf(false)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var message by mutableStateOf("正在初始化 FeelUOwn")
        private set
    var downloadStates by mutableStateOf<Map<String, DownloadState>>(emptyMap())
        private set
    var playbackState by mutableStateOf(PlaybackState())
        private set
    var cacheUsage by mutableStateOf(CacheUsage())
        private set
    var audioCacheLimitMb by mutableStateOf(DEFAULT_AUDIO_CACHE_LIMIT_MB)
        private set
    var imageCacheLimitMb by mutableStateOf(DEFAULT_IMAGE_CACHE_LIMIT_MB)
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
        get() = shuffleEnabled
    val repeatMode: RepeatMode
        get() = _repeatMode
    val isFmQueueActive: Boolean
        get() = isFmQueue
    val displayUpNextCount: Int
        get() = upNextQueue.size
    val canNavigateBack: Boolean
        get() = isFullPlayerOpen ||
            isDebugLogOpen ||
            isSettingsOpen ||
            isSearchOpen ||
            selectedFeature != null ||
            selectedTrack != null ||
            selectedVideo != null ||
            selectedMediaItem != null ||
            selectedPlaylist != null

    private var mainQueue: List<MusicTrack> = emptyList()
    private var originalMainQueue: List<MusicTrack> = emptyList()
    private var upNextQueue: List<MusicTrack> = emptyList()
    private var mainQueueIndex: Int = -1
    private var currentUpNextTrack: MusicTrack? = null
    private var currentIsUpNext: Boolean = false
    private var queueFeature: ProviderFeature? = null
    private var shuffleEnabled: Boolean = false
    private var _repeatMode: RepeatMode = RepeatMode.QUEUE
    private var isFmQueue: Boolean = false
    private var shuffleBeforeFm: Boolean? = null
    private var appendQueueFeatureTask: Deferred<Int>? = null
    private var lastEndedTrackId: String? = null
    private var autoAdvanceEligibleTrackId: String? = null
    private var lastRecoveredPlaybackErrorKey: String? = null
    private var playRequestSerial: Long = 0
    private var localMusicRefreshSerial: Long = 0
    private var hasLocalMusicPermission: Boolean = false
    private var pendingLocalMusicMediaRefresh: Job? = null
    private var playbackParts: List<PlaybackPart> = emptyList()
    private var currentPartIndex: Int = -1

    init {
        scope.launch {
            val loadedSettings = runCatching { settingsStore.load() }
            loadedSettings.onSuccess { applySettings(it) }
            runCatching { playbackQueueStore.load() }
                .onSuccess { restorePlaybackQueue(it) }
            updateLocalMusicScanSettings()
            updateResourceCacheLimit()
            updateAudioQualityPolicies()
            resourceCacheRepository.refreshUsage()
            runCatching {
                providerRepository.updateEnabledProviders(enabledProviderIds)
                providerRepository.initialize()
                refreshProviderCatalog()
                downloadRepository.load()
            }.onSuccess {
                message = "音乐服务已就绪"
                refreshAllProviderAuthStates()
                refreshHomeContent(homeSection)
            }.onFailure {
                setError(it)
            }
        }
        scope.launch {
            downloadRepository.states.collect {
                downloadStates = it
            }
        }
        scope.launch {
            resourceCacheRepository.usage.collect {
                cacheUsage = it
            }
        }
        scope.launch {
            playbackEngine.state.collect { engineState ->
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
                    playbackParts = playbackParts,
                    currentPartIndex = currentPartIndex,
                )
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
        return providerAuthStates[provider.providerId] ?: ProviderAuthState(
            providerId = provider.providerId,
            providerName = provider.providerName,
            isLoggedIn = false,
        )
    }

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
        isSearchOpen = true
    }

    fun closeSearch() {
        isSearchOpen = false
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
            isDebugLogOpen -> {
                closeDebugLogs()
                true
            }
            isSettingsOpen && settingsLoginProviderId != null -> {
                closeSettingsProviderLogin()
                true
            }
            isSettingsOpen -> {
                closeSettings()
                true
            }
            selectedVideo != null -> {
                closeVideo()
                true
            }
            selectedTrack != null -> {
                closeTrack()
                true
            }
            selectedMediaItem != null -> {
                closeMediaItem()
                true
            }
            selectedPlaylist != null -> {
                closePlaylist()
                true
            }
            selectedFeature != null -> {
                closeFeature()
                true
            }
            isSearchOpen -> {
                closeSearch()
                true
            }
            else -> false
        }
    }

    fun openSettings(providerId: String? = null) {
        providerId?.takeIf { it.isNotBlank() }?.let { selectedSettingsProviderId = it }
        providerId?.takeIf { it.isNotBlank() }?.let { settingsLoginProviderId = it }
        isSettingsOpen = true
        refreshAllProviderAuthStates()
        refreshResourceCacheUsage()
        refreshLocalMusicDirectories()
    }

    fun closeSettings() {
        isSettingsOpen = false
        isDebugLogOpen = false
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
        isDebugLogOpen = true
        refreshDebugLogs()
    }

    fun closeDebugLogs() {
        isDebugLogOpen = false
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
        persistSettings()
    }

    fun onProviderHeaderAuthorizationChange(providerId: String, value: String) {
        val input = providerHeaderInputFor(providerId).copy(authorization = value)
        providerHeaderInputs = providerHeaderInputs + (providerId to input)
        persistSettings()
    }

    fun onProviderHeaderCookieChange(providerId: String, value: String) {
        val input = providerHeaderInputFor(providerId).copy(cookie = value)
        providerHeaderInputs = providerHeaderInputs + (providerId to input)
        persistSettings()
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
            isLoading = true
            message = "正在登录 $providerName"
            runCatching { providerRepository.loginWithCookies(providerId, cookies) }
                .onSuccess {
                    providerAuthStates = providerAuthStates + (providerId to it)
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
            isLoading = false
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
            isLoading = true
            message = "正在登录 $providerName"
            runCatching { providerRepository.loginWithHeaders(providerId, authorization, cookie) }
                .onSuccess {
                    providerAuthStates = providerAuthStates + (providerId to it)
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
            isLoading = false
        }
    }

    fun logoutProvider(providerId: String) {
        val providerName = providerName(providerId)
        scope.launch {
            isLoading = true
            message = "正在退出 $providerName"
            runCatching { providerRepository.logout(providerId) }
                .onSuccess {
                    providerAuthStates = providerAuthStates + (providerId to it)
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
            isLoading = false
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

    fun isSmartReplacementProviderEnabled(providerId: String): Boolean {
        return providerId in selectedSmartReplacementProviderIds()
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
                            val provider = providerRepository.searchAll(keyword)
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
        isSearchOpen = true
        search()
    }

    fun refreshHomeContent(section: HomeSection = homeSection) {
        if (section == HomeSection.Mine) {
            refreshActiveMineSection()
            return
        }
        scope.launch {
            isLoading = true
            val title = if (section == HomeSection.Recommend) "推荐" else "探索"
            message = "正在加载$title"
            runCatching {
                refreshProviderCatalog()
                val category = when (section) {
                    HomeSection.Recommend -> ProviderFeatureCategory.Recommend
                    HomeSection.Music -> ProviderFeatureCategory.Music
                    HomeSection.Mine -> error("mine section is loaded separately")
                }
                withTimeout(30_000) {
                    providerFeatures.filter { it.category == category }.map { feature ->
                        if (feature.requiresLogin && !isProviderLoggedIn(feature.providerId)) {
                            ProviderContentSection(feature, isLoginRequired = true)
                        } else if (feature.isDeferredHomeFeature()) {
                            ProviderContentSection(feature)
                        } else {
                            runCatching { providerRepository.loadFeature(feature) }
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
        scope.launch {
            isLoading = true
            message = "正在加载我的歌单"
            runCatching {
                refreshProviderCatalog()
                val userPlaylists = loadProviderSections(ProviderFeatureCategory.MinePlaylists)
                val favoritePlaylists = loadProviderSections(ProviderFeatureCategory.MineFavoritePlaylists)
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
        scope.launch {
            isLoading = true
            message = "正在加载歌手和专辑"
            runCatching {
                refreshProviderCatalog()
                loadProviderSections(ProviderFeatureCategory.Mine) {
                    it.contentType == ProviderContentType.Artists || it.contentType == ProviderContentType.Albums
                }
            }.onSuccess {
                mineSections = it
                message = if (it.isEmpty()) "歌手和专辑暂无内容" else "歌手和专辑已更新"
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
                    runCatching { providerRepository.loadFeature(feature) }
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
            MineSection.Artists,
            MineSection.Albums -> if (mineSections.isEmpty()) refreshMineContent()
            MineSection.LocalMusic -> ensureLocalMusic()
        }
    }

    private fun refreshActiveMineSection() {
        when (mineSection) {
            MineSection.Playlists -> refreshMinePlaylistContent()
            MineSection.Artists,
            MineSection.Albums -> refreshMineContent()
            MineSection.LocalMusic -> ensureLocalMusic()
        }
    }

    private fun refreshActiveMineProviderContent() {
        when (mineSection) {
            MineSection.Playlists -> refreshMinePlaylistContent()
            MineSection.Artists,
            MineSection.Albums -> refreshMineContent()
            MineSection.LocalMusic -> Unit
        }
    }

    fun openFeature(feature: ProviderFeature) {
        selectedFeature = feature
        selectedFeatureTracks = emptyList()
        selectedFeatureError = null
        scope.launch {
            isLoading = true
            message = "正在加载：${feature.title}"
            val deferred = scope.async { providerRepository.loadFeature(feature) }
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
                        selectedFeatureTracks = section.tracks
                        selectedFeatureError = when {
                            section.isLoginRequired -> "登录后显示 ${section.feature.providerName} 的个性化内容"
                            section.errorMessage != null -> section.errorMessage
                            else -> null
                        }
                        message = when {
                            selectedFeatureError != null -> selectedFeatureError.orEmpty()
                            section.tracks.isEmpty() -> "${feature.title} 暂无歌曲"
                            else -> "${feature.title} · ${section.tracks.size} 首"
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
        selectedFeature = null
        selectedFeatureTracks = emptyList()
        selectedFeatureError = null
    }

    fun openTrackDetail(track: MusicTrack) {
        if (track.sourceType != TrackSourceType.Provider) return
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
        selectedVideo = video
        selectedVideoPayload = null
        selectedVideoError = null
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
        selectedVideo = null
        selectedVideoPayload = null
        selectedVideoError = null
    }

    fun canAddTrackToProviderPlaylist(track: MusicTrack): Boolean {
        val providerId = trackProviderId(track) ?: return false
        return track.sourceType == TrackSourceType.Provider &&
            isProviderLoggedIn(providerId) &&
            providerCapabilities[providerId]?.canAddSongToPlaylist == true
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
                        closePlaylistTargetPicker()
                        refreshAfterProviderMutation(playlist.providerId)
                    } else {
                        playlistOperationError = result.message.ifBlank { "添加失败" }
                        message = playlistOperationError.orEmpty()
                    }
                }
                .onFailure {
                    playlistOperationError = it.message ?: it::class.simpleName.orEmpty()
                    setError(it)
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
                        refreshAfterProviderMutation(playlist.providerId)
                    } else {
                        selectedPlaylistError = result.message.ifBlank { "移除失败" }
                        message = selectedPlaylistError.orEmpty()
                    }
                }
                .onFailure { setError(it) }
            isLoading = false
        }
    }

    fun openPlaylist(playlist: ProviderPlaylist, category: ProviderFeatureCategory? = null) {
        selectedPlaylist = playlist
        selectedPlaylistCategory = category
        selectedPlaylistTracks = emptyList()
        selectedPlaylistError = null
        scope.launch {
            isLoading = true
            message = "正在加载：${playlist.title}"
            val deferred = scope.async { providerRepository.playlistDetail(playlist) }
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
        selectedPlaylist = null
        selectedPlaylistCategory = null
        selectedPlaylistTracks = emptyList()
        selectedPlaylistError = null
    }

    fun openMediaItem(item: ProviderMediaItem) {
        selectedMediaItem = item
        selectedMediaItemTracks = emptyList()
        selectedMediaItemAlbums = emptyList()
        selectedMediaItemError = null
        scope.launch {
            isLoading = true
            message = "正在加载：${item.title}"
            val deferred = scope.async { providerRepository.mediaItemDetail(item) }
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
                        selectedMediaItemAlbums = detail.albums
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
        selectedMediaItem = null
        selectedMediaItemTracks = emptyList()
        selectedMediaItemAlbums = emptyList()
        selectedMediaItemError = null
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
        } else {
            playFirst(section.tracks, feature.takeIf { it.isDynamicQueueFeature() })
        }
    }

    fun playFromSelectedPlaylist(index: Int) {
        val track = selectedPlaylistTracks.getOrNull(index) ?: return
        play(track, selectedPlaylistTracks, index)
    }

    fun playAllFromSelectedPlaylist() {
        playFirst(selectedPlaylistTracks)
    }

    fun playFromSelectedFeature(index: Int) {
        val track = selectedFeatureTracks.getOrNull(index) ?: return
        play(track, selectedFeatureTracks, index, selectedFeature?.takeIf { it.isDynamicQueueFeature() })
    }

    fun playAllFromSelectedFeature() {
        playFirst(selectedFeatureTracks, selectedFeature?.takeIf { it.isDynamicQueueFeature() })
    }

    fun playFromSelectedMediaItem(index: Int) {
        val track = selectedMediaItemTracks.getOrNull(index) ?: return
        play(track, selectedMediaItemTracks, index)
    }

    fun playAllFromSelectedMediaItem() {
        playFirst(selectedMediaItemTracks)
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
            PlayerStatus.Playing -> playbackEngine.pause()
            PlayerStatus.Paused -> {
                if (playbackState.currentTrack != null) playbackEngine.resume()
            }
            PlayerStatus.Idle, PlayerStatus.Ended -> {
                (currentQueueTrack() ?: playbackState.currentTrack)?.let(::startPlayback)
            }
            else -> Unit
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
        _repeatMode = when (_repeatMode) {
            RepeatMode.OFF -> RepeatMode.QUEUE
            RepeatMode.QUEUE -> RepeatMode.SINGLE
            RepeatMode.SINGLE -> RepeatMode.OFF
        }
        updatePlaybackQueueState()
        persistPlaybackQueue()
    }

    fun download(track: MusicTrack) {
        if (track.sourceType != TrackSourceType.Provider) return
        scope.launch {
            runCatching { downloadRepository.download(track) }
                .onSuccess {
                    if (hasLocalMusicPermission) {
                        refreshLocalMusic(forceRefresh = true, showLoading = homeSection == HomeSection.Mine && mineSection == MineSection.LocalMusic)
                    }
                    message = "已下载：${track.title}"
                }
                .onFailure { setError(it) }
        }
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

    fun openTrackArtist(track: MusicTrack) {
        val artistName = track.artists.substringBefore("·").substringBefore(",").trim()
        val providerId = track.source.takeIf { it.isNotBlank() }
        val providerName = track.providerName ?: providerId.orEmpty()
        val itemId = track.artistItemId
        if (!itemId.isNullOrBlank() && providerId != null && artistName.isNotBlank()) {
            openMediaItem(
                ProviderMediaItem(
                    id = itemId,
                    title = artistName,
                    providerId = providerId,
                    providerName = providerName,
                    type = ProviderMediaItemType.Artist,
                )
            )
            return
        }
        searchTrackText(artistName.ifBlank { track.artists }, providerId)
    }

    fun openTrackAlbum(track: MusicTrack) {
        val albumName = track.album.trim()
        val providerId = track.source.takeIf { it.isNotBlank() }
        val providerName = track.providerName ?: providerId.orEmpty()
        val itemId = track.albumItemId
        if (!itemId.isNullOrBlank() && providerId != null && albumName.isNotBlank()) {
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
        searchTrackText(albumName, providerId)
    }

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
        providerAuthStates = loadedProviders.associate { provider ->
            provider.providerId to (providerAuthStates[provider.providerId] ?: ProviderAuthState(
                providerId = provider.providerId,
                providerName = provider.providerName,
                isLoggedIn = false,
            ))
        }
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
        recommendSections = emptyList()
        musicSections = emptyList()
        mineSections = emptyList()
        minePlaylistSections = emptyList()
        mineFavoritePlaylistSections = emptyList()
        selectedFeature = null
        selectedTrack = null
        selectedPlaylist = null
        selectedPlaylistCategory = null
        selectedMediaItem = null
        selectedFeatureTracks = emptyList()
        selectedTrackError = null
        selectedPlaylistTracks = emptyList()
        selectedMediaItemTracks = emptyList()
        selectedMediaItemAlbums = emptyList()
        closePlaylistTargetPicker()
    }

    private fun reorderProviderContent() {
        recommendSections = recommendSections.sortedSectionsByOrder()
        musicSections = musicSections.sortedSectionsByOrder()
        mineSections = mineSections.sortedSectionsByOrder()
        minePlaylistSections = minePlaylistSections.sortedSectionsByOrder()
        mineFavoritePlaylistSections = mineFavoritePlaylistSections.sortedSectionsByOrder()
    }

    private fun refreshAllProviderAuthStates() {
        scope.launch {
            refreshProviderAuthStates()
        }
    }

    private suspend fun refreshProviderAuthStates() {
        providers.forEach { provider ->
            runCatching { providerRepository.authState(provider.providerId) }
                .onSuccess { providerAuthStates = providerAuthStates + (provider.providerId to it) }
        }
    }

    private fun isProviderLoggedIn(providerId: String): Boolean {
        return providerAuthStates[providerId]?.isLoggedIn == true
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
        skippedUnavailableCount: Int = 0,
    ) {
        var playbackIndex = index
        if (skippedUnavailableCount == 0) {
            playbackIndex = replaceMainQueue(sourceQueue, index, sourceFeature, keepSelectedTrack = true)
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
        scope.launch playRequest@{
            isLoading = true
            message = "正在播放：${track.title}"
            runCatching {
                val partTrack = requestedPartIndex
                    ?.let { index -> playbackParts.getOrNull(index) }
                    ?.toTrack(playbackTrack)
                val resolveTrack = partTrack ?: playbackTrack
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
                val partLabel = currentPlaybackPartLabel()
                message = if (partLabel != null) {
                    "${playableTrack.title} · $partLabel"
                } else {
                    "${playableTrack.title} - ${playableTrack.artists}"
                }
                prefetchFeatureQueueIfNeeded()
            }.onFailure {
                if (requestSerial != playRequestSerial) return@playRequest
                if (!skipUnavailableTrack(track, skippedUnavailableCount, it)) {
                    setError(it)
                }
            }
            if (requestSerial == playRequestSerial) {
                isLoading = false
            }
        }
    }

    private fun playFirst(sourceQueue: List<MusicTrack>, sourceFeature: ProviderFeature? = null) {
        if (sourceQueue.isEmpty()) return
        val playbackIndex = replaceMainQueue(sourceQueue, 0, sourceFeature, keepSelectedTrack = false)
        playMainIndex(playbackIndex)
    }

    private fun replaceMainQueue(
        sourceQueue: List<MusicTrack>,
        index: Int,
        sourceFeature: ProviderFeature?,
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
        shuffleEnabled = snapshot.shuffleEnabled
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
            shuffleEnabled = shuffleEnabled,
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

    private fun ProviderFeature.isDeferredHomeFeature(): Boolean {
        return category == ProviderFeatureCategory.Recommend &&
            (id.endsWith("_daily_songs") || isDynamicQueueFeature())
    }

    private fun ProviderFeature.isDynamicQueueFeature(): Boolean {
        return id.endsWith("_radio")
    }

    private fun ProviderSearchResults.totalCount(): Int {
        return tracks.size + playlists.size + artists.size + albums.size + videos.size
    }

    private fun localOnlyCount(allTracks: List<MusicTrack>, providerTracks: List<MusicTrack>): Int {
        if (providerTracks.isEmpty()) return allTracks.size
        val providerIds = providerTracks.map { it.id }.toSet()
        return allTracks.count { it.id !in providerIds }
    }

    private fun applySettings(settings: AppSettings) {
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
        providerCookieInputs = settings.providerCookieInputs
        providerHeaderInputs = settings.providerHeaderInputs
        enabledProviderIds = settings.enabledProviderIds.ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS }
        providerOrderIds = settings.providerOrderIds.ifEmpty { DEFAULT_PROVIDER_ORDER_IDS }
        audioCacheLimitMb = settings.audioCacheLimitMb
        imageCacheLimitMb = settings.imageCacheLimitMb
        wifiAudioQualityPolicy = settings.wifiAudioQualityPolicy
        cellularAudioQualityPolicy = settings.cellularAudioQualityPolicy
        unavailablePlaybackPolicy = settings.unavailablePlaybackPolicy
        smartReplacementProviderIds = settings.smartReplacementProviderIds
        smartReplacementMinScore = settings.smartReplacementMinScore.coerceIn(0.0, 1.0)
        smartReplacementUseReplacementMetadata = settings.smartReplacementUseReplacementMetadata
        smartReplacementUseReplacementLyrics = settings.smartReplacementUseReplacementLyrics
        lyricFontSize = settings.lyricFontSize
        themeMode = settings.themeMode
        themeColorScheme = settings.themeColorScheme
    }

    private fun persistSettings() {
        val settings = AppSettings(
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
            providerCookieInputs = providerCookieInputs,
            providerHeaderInputs = providerHeaderInputs,
            enabledProviderIds = enabledProviderIds,
            providerOrderIds = providerOrderIds,
            audioCacheLimitMb = audioCacheLimitMb,
            imageCacheLimitMb = imageCacheLimitMb,
            wifiAudioQualityPolicy = wifiAudioQualityPolicy,
            cellularAudioQualityPolicy = cellularAudioQualityPolicy,
            unavailablePlaybackPolicy = unavailablePlaybackPolicy,
            smartReplacementProviderIds = smartReplacementProviderIds,
            smartReplacementMinScore = smartReplacementMinScore,
            smartReplacementUseReplacementMetadata = smartReplacementUseReplacementMetadata,
            smartReplacementUseReplacementLyrics = smartReplacementUseReplacementLyrics,
            lyricFontSize = lyricFontSize,
            themeMode = themeMode,
            themeColorScheme = themeColorScheme,
        )
        scope.launch {
            settingsStore.save(settings)
        }
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
        if (!shouldRecoverPlaybackEngineError(failedTrack)) return
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

    private fun shouldRecoverPlaybackEngineError(track: MusicTrack): Boolean {
        return track.sourceType == TrackSourceType.Provider &&
            (
                unavailablePlaybackPolicy == UnavailablePlaybackPolicy.Skip ||
                    unavailablePlaybackPolicy == UnavailablePlaybackPolicy.SmartReplace
                )
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
        val text = messages.joinToString(" ")
        return text.contains("media not found", ignoreCase = true) ||
            text.contains("MediaNotFound", ignoreCase = true)
    }

    private fun Int.mbToBytes(): Long = this.toLong() * 1024L * 1024L
}
