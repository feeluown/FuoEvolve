package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

enum class SearchScope {
    Local,
    Provider,
    All,
}

enum class HomeSection {
    Recommend,
    Music,
    Mine,
}

enum class MineSection {
    Playlists,
    Favorites,
    LocalMusic,
}

enum class LocalMusicViewMode {
    All,
    Artist,
    Album,
}

private const val DYNAMIC_QUEUE_PREFETCH_REMAINING = 2

class FuoPlayerController(
    private val providerRepository: ProviderMusicRepository,
    private val localRepository: LocalMusicRepository,
    private val downloadRepository: DownloadRepository,
    private val playbackEngine: PlaybackEngine,
    private val settingsStore: AppSettingsStore = NoOpAppSettingsStore,
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
    var selectedMediaItemError by mutableStateOf<String?>(null)
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
    var providerLoginMode by mutableStateOf(ProviderLoginMode.WebView)
        private set
    var searchResults by mutableStateOf<List<MusicTrack>>(emptyList())
        private set
    var homeSection by mutableStateOf(HomeSection.Recommend)
        private set
    var mineSection by mutableStateOf(MineSection.Playlists)
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
    var debugLogLines by mutableStateOf<List<String>>(emptyList())
        private set
    var debugLogError by mutableStateOf<String?>(null)
        private set
    val isDebugLogViewerAvailable: Boolean
        get() = debugLogRepository.isAvailable
    val canNavigateBack: Boolean
        get() = isFullPlayerOpen ||
            isDebugLogOpen ||
            isSettingsOpen ||
            isSearchOpen ||
            selectedFeature != null ||
            selectedMediaItem != null ||
            selectedPlaylist != null

    private var queue: List<MusicTrack> = emptyList()
    private var queueIndex: Int = -1
    private var queueFeature: ProviderFeature? = null
    private var appendQueueFeatureTask: Deferred<Int>? = null
    private var lastEndedTrackId: String? = null
    private var playRequestSerial: Long = 0

    init {
        scope.launch {
            val loadedSettings = runCatching { settingsStore.load() }
            loadedSettings.onSuccess { applySettings(it) }
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
                if (engineState.status == PlayerStatus.Ended) {
                    val endedTrackId = queue.getOrNull(queueIndex)?.id
                    if (endedTrackId != null && endedTrackId != lastEndedTrackId) {
                        lastEndedTrackId = endedTrackId
                        next()
                    }
                } else {
                    lastEndedTrackId = null
                }
                playbackState = engineState.copy(
                    queue = queue,
                    queueIndex = queueIndex,
                    currentTrack = queue.getOrNull(queueIndex) ?: engineState.currentTrack,
                )
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

    fun refreshLocalMusic() {
        scope.launch {
            isLoading = true
            message = "正在扫描本地音乐"
            runCatching {
                updateLocalMusicScanSettings()
                localMusicDirectories = localRepository.directories()
                localRepository.scan()
            }
                .onSuccess {
                    localTracks = it
                    message = if (it.isEmpty()) "未发现本地音乐" else "本地音乐 ${it.size} 首"
                }
                .onFailure { setError(it) }
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
            isSettingsOpen -> {
                closeSettings()
                true
            }
            isSearchOpen -> {
                closeSearch()
                true
            }
            selectedFeature != null -> {
                closeFeature()
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
            else -> false
        }
    }

    fun openSettings() {
        isSettingsOpen = true
        refreshAllProviderAuthStates()
        refreshResourceCacheUsage()
        refreshLocalMusicDirectories()
    }

    fun closeSettings() {
        isSettingsOpen = false
        isDebugLogOpen = false
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
            MineSection.Favorites -> if (mineSections.isEmpty()) refreshMineContent()
            MineSection.LocalMusic -> if (localTracks.isEmpty()) refreshLocalMusic()
        }
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
        refreshLocalMusic()
    }

    fun onLocalMusicMinDurationChange(value: Int) {
        localMusicMinDurationSeconds = value
        persistSettings()
        refreshLocalMusic()
    }

    fun search() {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            searchResults = emptyList()
            message = "请输入关键词"
            return
        }
        scope.launch {
            isLoading = true
            message = "正在搜索：$keyword"
            runCatching {
                withTimeout(25_000) {
                    when (searchScope) {
                        SearchScope.Local -> localRepository.search(keyword)
                        SearchScope.Provider -> providerRepository.search(keyword, selectedSearchProviderId)
                        SearchScope.All -> mergeResults(
                            localRepository.search(keyword),
                            providerRepository.search(keyword),
                        )
                    }
                }
            }.onSuccess {
                searchResults = it
                message = if (it.isEmpty()) "没有搜索结果" else "搜索到 ${it.size} 首"
            }.onFailure {
                setError(it)
            }
            isLoading = false
        }
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
            message = "正在加载我的收藏"
            runCatching {
                refreshProviderCatalog()
                loadProviderSections(ProviderFeatureCategory.Mine)
            }.onSuccess {
                mineSections = it
                message = if (it.isEmpty()) "我的收藏暂无内容" else "我的收藏已更新"
            }.onFailure {
                setError(it)
            }
            isLoading = false
        }
    }

    private suspend fun loadProviderSections(category: ProviderFeatureCategory): List<ProviderContentSection> {
        return withTimeout(30_000) {
            providerFeatures.filter { it.category == category }.map { feature ->
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
            MineSection.Favorites -> if (mineSections.isEmpty()) refreshMineContent()
            MineSection.LocalMusic -> if (localTracks.isEmpty()) refreshLocalMusic()
        }
    }

    private fun refreshActiveMineSection() {
        when (mineSection) {
            MineSection.Playlists -> refreshMinePlaylistContent()
            MineSection.Favorites -> refreshMineContent()
            MineSection.LocalMusic -> refreshLocalMusic()
        }
    }

    private fun refreshActiveMineProviderContent() {
        when (mineSection) {
            MineSection.Playlists -> refreshMinePlaylistContent()
            MineSection.Favorites -> refreshMineContent()
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

    fun openPlaylist(playlist: ProviderPlaylist) {
        selectedPlaylist = playlist
        selectedPlaylistTracks = emptyList()
        selectedPlaylistError = null
        scope.launch {
            isLoading = true
            message = "正在加载：${playlist.title}"
            val deferred = scope.async { providerRepository.playlistTracks(playlist) }
            val result = withTimeoutOrNull(30_000) {
                runCatching { deferred.await() }
            }
            if (result == null) {
                deferred.cancel()
                selectedPlaylistError = "加载超时，请检查网络后重试"
                message = selectedPlaylistError.orEmpty()
            } else {
                result.onSuccess {
                    if (selectedPlaylist == playlist) {
                        selectedPlaylistTracks = it
                        selectedPlaylistError = null
                        message = if (it.isEmpty()) "歌单暂无歌曲" else "${playlist.title} · ${it.size} 首"
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
        selectedPlaylistTracks = emptyList()
        selectedPlaylistError = null
    }

    fun openMediaItem(item: ProviderMediaItem) {
        selectedMediaItem = item
        selectedMediaItemTracks = emptyList()
        selectedMediaItemError = null
        scope.launch {
            isLoading = true
            message = "正在加载：${item.title}"
            val deferred = scope.async { providerRepository.mediaItemTracks(item) }
            val result = withTimeoutOrNull(30_000) {
                runCatching { deferred.await() }
            }
            if (result == null) {
                deferred.cancel()
                selectedMediaItemError = "加载超时，请检查网络后重试"
                message = selectedMediaItemError.orEmpty()
            } else {
                result.onSuccess {
                    if (selectedMediaItem == item) {
                        selectedMediaItemTracks = it
                        selectedMediaItemError = null
                        message = if (it.isEmpty()) "${item.title} 暂无歌曲" else "${item.title} · ${it.size} 首"
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

    fun playQueueIndex(index: Int) {
        val track = queue.getOrNull(index) ?: return
        play(track, queue, index, queueFeature)
    }

    fun toggle() {
        when (playbackState.status) {
            PlayerStatus.Playing -> playbackEngine.pause()
            PlayerStatus.Paused, PlayerStatus.Idle, PlayerStatus.Ended -> {
                if (playbackState.currentTrack != null) playbackEngine.resume()
            }
            else -> Unit
        }
    }

    fun next() {
        if (queue.isEmpty()) return
        val feature = queueFeature
        if (feature != null && queueIndex >= queue.lastIndex) {
            scope.launch {
                val nextIndex = queue.size
                val appendedCount = appendFeatureQueue(feature)
                if (appendedCount > 0 && queueFeature == feature) {
                    playQueueIndex(nextIndex)
                } else if (queueFeature == feature) {
                    message = "${feature.title} 暂无后续歌曲"
                }
            }
            return
        }
        val nextIndex = (queueIndex + 1).floorMod(queue.size)
        playQueueIndex(nextIndex)
    }

    fun previous() {
        if (queue.isEmpty()) return
        val previousIndex = (queueIndex - 1).floorMod(queue.size)
        playQueueIndex(previousIndex)
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
        val index = queue.indexOfFirst { it.id == track.id }
        if (index < 0) return
        queue = queue.filterIndexed { currentIndex, _ -> currentIndex != index }
        queueIndex = when {
            queue.isEmpty() -> -1
            index < queueIndex -> queueIndex - 1
            index == queueIndex -> queueIndex.coerceAtMost(queue.lastIndex)
            else -> queueIndex
        }
        playbackState = playbackState.copy(queue = queue, queueIndex = queueIndex)
    }

    fun download(track: MusicTrack) {
        if (track.sourceType != TrackSourceType.Provider) return
        scope.launch {
            runCatching { downloadRepository.download(track) }
                .onSuccess {
                    refreshLocalMusic()
                    message = "已下载：${track.title}"
                }
                .onFailure { setError(it) }
        }
    }

    fun deleteDownload(track: MusicTrack) {
        scope.launch {
            runCatching { downloadRepository.deleteDownloaded(track) }
                .onSuccess {
                    refreshLocalMusic()
                    message = "已删除下载：${track.title}"
                }
                .onFailure { setError(it) }
        }
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
        providerFeatures = providerRepository.features().sortedFeaturesByOrder()
        refreshProviderAuthStates()
    }

    private fun clearProviderContent() {
        recommendSections = emptyList()
        musicSections = emptyList()
        mineSections = emptyList()
        minePlaylistSections = emptyList()
        mineFavoritePlaylistSections = emptyList()
        selectedFeature = null
        selectedPlaylist = null
        selectedMediaItem = null
        selectedFeatureTracks = emptyList()
        selectedPlaylistTracks = emptyList()
        selectedMediaItemTracks = emptyList()
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

    private fun play(
        track: MusicTrack,
        sourceQueue: List<MusicTrack>,
        index: Int,
        sourceFeature: ProviderFeature? = null,
        skippedUnavailableCount: Int = 0,
    ) {
        val requestSerial = ++playRequestSerial
        val playbackTrack = track.preferDownloaded()
        queue = sourceQueue.mapIndexed { itemIndex, item ->
            if (itemIndex == index) playbackTrack else item
        }
        queueIndex = index
        queueFeature = sourceFeature
        playbackState = playbackState.copy(
            status = PlayerStatus.Loading,
            currentTrack = playbackTrack,
            queue = queue,
            queueIndex = queueIndex,
            positionMs = 0,
            errorMessage = null,
        )
        scope.launch playRequest@{
            isLoading = true
            message = "正在播放：${track.title}"
            runCatching {
                val payload = playbackTrack.toPayload()
                    ?: providerRepository.resolve(playbackTrack, unavailablePlaybackPolicy)
                if (requestSerial != playRequestSerial) return@playRequest
                val playableTrack = playbackTrack.copy(
                    title = payload.title.ifBlank { playbackTrack.title },
                    artists = payload.artists.ifBlank { playbackTrack.artists },
                    album = payload.album.ifBlank { playbackTrack.album },
                    source = payload.source.ifBlank { playbackTrack.source },
                    coverUrl = payload.coverUrl ?: playbackTrack.coverUrl,
                    durationMs = payload.durationMs ?: playbackTrack.durationMs,
                    providerName = payload.providerName ?: playbackTrack.providerName,
                    isSmartReplacement = payload.isSmartReplacement,
                    originalTitle = payload.originalTitle,
                    originalProviderName = payload.originalProviderName,
                )
                queue = sourceQueue.mapIndexed { itemIndex, item ->
                    if (itemIndex == index) playableTrack else item
                }
                queueIndex = index
                queueFeature = sourceFeature
                playbackEngine.play(playableTrack, payload)
                playbackState = playbackState.copy(
                    status = PlayerStatus.Loading,
                    currentTrack = playableTrack,
                    queue = queue,
                    queueIndex = queueIndex,
                    lyrics = payload.lyrics,
                    audioQuality = payload.audioQuality,
                )
                message = "${playableTrack.title} - ${playableTrack.artists}"
                prefetchFeatureQueueIfNeeded()
            }.onFailure {
                if (requestSerial != playRequestSerial) return@playRequest
                if (!skipUnavailableTrack(track, sourceQueue, index, sourceFeature, skippedUnavailableCount, it)) {
                    setError(it)
                }
            }
            if (requestSerial == playRequestSerial) {
                isLoading = false
            }
        }
    }

    private fun playFirst(sourceQueue: List<MusicTrack>, sourceFeature: ProviderFeature? = null) {
        val track = sourceQueue.firstOrNull() ?: return
        play(track, sourceQueue, 0, sourceFeature)
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
        if (queueIndex < 0) return
        val remaining = queue.size - queueIndex
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
            val seenQueueIds = queue.mapTo(mutableSetOf()) { it.id }
            val newTracks = tracks.filter { seenQueueIds.add(it.id) }
            if (newTracks.isNotEmpty()) {
                queue = queue + newTracks
                playbackState = playbackState.copy(queue = queue, queueIndex = queueIndex)
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
        )
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

    private fun applySettings(settings: AppSettings) {
        homeSection = settings.homeSection
        mineSection = settings.mineSection
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
    }

    private fun persistSettings() {
        val settings = AppSettings(
            homeSection = homeSection,
            mineSection = mineSection,
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

    private fun skipUnavailableTrack(
        track: MusicTrack,
        sourceQueue: List<MusicTrack>,
        index: Int,
        sourceFeature: ProviderFeature?,
        skippedUnavailableCount: Int,
        throwable: Throwable,
    ): Boolean {
        if (unavailablePlaybackPolicy != UnavailablePlaybackPolicy.Skip || !throwable.isMediaNotFound()) {
            return false
        }
        if (sourceQueue.size <= 1 || skippedUnavailableCount >= sourceQueue.lastIndex) {
            return false
        }
        val nextIndex = (index + 1).floorMod(sourceQueue.size)
        message = "已跳过不可用资源：${track.title}"
        play(
            track = sourceQueue[nextIndex],
            sourceQueue = sourceQueue,
            index = nextIndex,
            sourceFeature = sourceFeature,
            skippedUnavailableCount = skippedUnavailableCount + 1,
        )
        return true
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

    private fun Int.floorMod(size: Int): Int = ((this % size) + size) % size

    private fun Int.mbToBytes(): Long = this.toLong() * 1024L * 1024L
}
