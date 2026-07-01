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
    Local,
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
    private val scope: CoroutineScope,
) {
    var providers by mutableStateOf<List<ProviderInfo>>(emptyList())
        private set
    var providerFeatures by mutableStateOf<List<ProviderFeature>>(emptyList())
        private set
    var providerAuthStates by mutableStateOf<Map<String, ProviderAuthState>>(emptyMap())
        private set
    var providerCookieInputs by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var recommendSections by mutableStateOf<List<ProviderContentSection>>(emptyList())
        private set
    var musicSections by mutableStateOf<List<ProviderContentSection>>(emptyList())
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
    val canNavigateBack: Boolean
        get() = isFullPlayerOpen ||
            isSettingsOpen ||
            isSearchOpen ||
            selectedFeature != null ||
            selectedPlaylist != null

    private var queue: List<MusicTrack> = emptyList()
    private var queueIndex: Int = -1
    private var queueFeature: ProviderFeature? = null
    private var appendQueueFeatureTask: Deferred<Int>? = null
    private var lastEndedTrackId: String? = null

    init {
        scope.launch {
            val loadedSettings = runCatching { settingsStore.load() }
            loadedSettings.onSuccess { applySettings(it) }
            updateLocalMusicScanSettings()
            updateResourceCacheLimit()
            updateAudioQualityPolicies()
            resourceCacheRepository.refreshUsage()
            runCatching {
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

    fun selectedSettingsProvider(): ProviderInfo? {
        return providers.firstOrNull { it.providerId == selectedSettingsProviderId } ?: providers.firstOrNull()
    }

    fun contentSectionsFor(section: HomeSection): List<ProviderContentSection> {
        return when (section) {
            HomeSection.Recommend -> recommendSections
            HomeSection.Music -> musicSections
            HomeSection.Local -> emptyList()
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
    }

    fun onProviderCookiesChange(providerId: String, value: String) {
        providerCookieInputs = providerCookieInputs + (providerId to value)
        persistSettings()
    }

    fun onSettingsProviderChange(providerId: String) {
        selectedSettingsProviderId = providerId
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
                    if (homeSection != HomeSection.Local) {
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
                    persistSettings()
                    message = "${it.providerName} 已退出登录"
                    if (homeSection != HomeSection.Local) {
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
        if (value != HomeSection.Local && contentSectionsFor(value).isEmpty()) {
            refreshHomeContent(value)
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
        if (section == HomeSection.Local) {
            refreshLocalMusic()
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
                    HomeSection.Local -> error("local section has no provider content")
                }
                withTimeout(30_000) {
                    providerFeatures.filter { it.category == category }.map { feature ->
                        if (feature.isDeferredHomeFeature()) {
                            ProviderContentSection(feature)
                        } else {
                            runCatching { providerRepository.loadFeature(feature) }
                                .getOrElse { ProviderContentSection(feature, errorMessage = it.message ?: "加载失败") }
                        }
                    }
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
        val section = (recommendSections + musicSections).firstOrNull { it.feature.id == featureId }
        val tracks = section?.tracks.orEmpty()
        val track = tracks.getOrNull(index) ?: return
        play(track, tracks, index, section?.feature?.takeIf { it.isDynamicQueueFeature() })
    }

    fun playAllFromFeature(featureId: String) {
        val section = (recommendSections + musicSections).firstOrNull { it.feature.id == featureId }
        playFirst(section?.tracks.orEmpty(), section?.feature?.takeIf { it.isDynamicQueueFeature() })
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
        val loadedProviders = providerRepository.providers()
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
        providerFeatures = providerRepository.features()
    }

    private fun refreshAllProviderAuthStates() {
        scope.launch {
            providers.forEach { provider ->
                runCatching { providerRepository.authState(provider.providerId) }
                    .onSuccess { providerAuthStates = providerAuthStates + (provider.providerId to it) }
            }
        }
    }

    private fun play(
        track: MusicTrack,
        sourceQueue: List<MusicTrack>,
        index: Int,
        sourceFeature: ProviderFeature? = null,
    ) {
        scope.launch {
            isLoading = true
            message = "正在播放：${track.title}"
            runCatching {
                val playbackTrack = track.preferDownloaded()
                val payload = playbackTrack.toPayload() ?: providerRepository.resolve(playbackTrack)
                val playableTrack = playbackTrack.copy(
                    coverUrl = payload.coverUrl ?: playbackTrack.coverUrl,
                    durationMs = payload.durationMs ?: playbackTrack.durationMs,
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
                setError(it)
            }
            isLoading = false
        }
    }

    private fun playFirst(sourceQueue: List<MusicTrack>, sourceFeature: ProviderFeature? = null) {
        val track = sourceQueue.firstOrNull() ?: return
        play(track, sourceQueue, 0, sourceFeature)
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
            audioQuality = null,
        )
    }

    private fun mergeResults(local: List<MusicTrack>, provider: List<MusicTrack>): List<MusicTrack> {
        val seen = linkedSetOf<String>()
        return (local + provider).filter { seen.add(it.id) }
    }

    private fun providerName(providerId: String): String {
        return providers.firstOrNull { it.providerId == providerId }?.providerName ?: providerId
    }

    private fun ProviderFeature.isDeferredHomeFeature(): Boolean {
        return category == ProviderFeatureCategory.Recommend &&
            (id.endsWith("_daily_songs") || isDynamicQueueFeature())
    }

    private fun ProviderFeature.isDynamicQueueFeature(): Boolean {
        return id.endsWith("_radio")
    }

    private fun applySettings(settings: AppSettings) {
        homeSection = settings.homeSection
        localMusicViewMode = settings.localMusicViewMode
        excludedLocalMusicDirectoryIds = settings.excludedLocalMusicDirectoryIds
        localMusicMinDurationSeconds = settings.localMusicMinDurationSeconds
        searchScope = settings.searchScope
        selectedSearchProviderId = settings.selectedSearchProviderId
        selectedSettingsProviderId = settings.selectedSettingsProviderId
        providerLoginMode = settings.providerLoginMode
        providerCookieInputs = settings.providerCookieInputs
        audioCacheLimitMb = settings.audioCacheLimitMb
        imageCacheLimitMb = settings.imageCacheLimitMb
        wifiAudioQualityPolicy = settings.wifiAudioQualityPolicy
        cellularAudioQualityPolicy = settings.cellularAudioQualityPolicy
    }

    private fun persistSettings() {
        val settings = AppSettings(
            homeSection = homeSection,
            localMusicViewMode = localMusicViewMode,
            excludedLocalMusicDirectoryIds = excludedLocalMusicDirectoryIds,
            localMusicMinDurationSeconds = localMusicMinDurationSeconds,
            searchScope = searchScope,
            selectedSearchProviderId = selectedSearchProviderId,
            selectedSettingsProviderId = selectedSettingsProviderId,
            providerLoginMode = providerLoginMode,
            providerCookieInputs = providerCookieInputs,
            audioCacheLimitMb = audioCacheLimitMb,
            imageCacheLimitMb = imageCacheLimitMb,
            wifiAudioQualityPolicy = wifiAudioQualityPolicy,
            cellularAudioQualityPolicy = cellularAudioQualityPolicy,
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

    private fun Int.floorMod(size: Int): Int = ((this % size) + size) % size

    private fun Int.mbToBytes(): Long = this.toLong() * 1024L * 1024L
}
