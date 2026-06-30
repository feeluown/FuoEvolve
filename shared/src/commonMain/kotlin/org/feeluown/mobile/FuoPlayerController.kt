package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
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

class FuoPlayerController(
    private val providerRepository: ProviderMusicRepository,
    private val localRepository: LocalMusicRepository,
    private val downloadRepository: DownloadRepository,
    private val playbackEngine: PlaybackEngine,
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
    var localTracks by mutableStateOf<List<MusicTrack>>(emptyList())
        private set
    var query by mutableStateOf("")
        private set
    var searchScope by mutableStateOf(SearchScope.All)
        private set
    var selectedSearchProviderId by mutableStateOf<String?>(null)
        private set
    var searchResults by mutableStateOf<List<MusicTrack>>(emptyList())
        private set
    var homeSection by mutableStateOf(HomeSection.Recommend)
        private set
    var localMusicViewMode by mutableStateOf(LocalMusicViewMode.All)
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

    private var queue: List<MusicTrack> = emptyList()
    private var queueIndex: Int = -1
    private var lastEndedTrackId: String? = null

    init {
        scope.launch {
            runCatching {
                providerRepository.initialize()
                refreshProviderCatalog()
                downloadRepository.load()
            }.onSuccess {
                message = "音乐服务已就绪"
                refreshAllProviderAuthStates()
                refreshHomeContent(HomeSection.Recommend)
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
            runCatching { localRepository.scan() }
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

    fun openSettings() {
        isSettingsOpen = true
        refreshAllProviderAuthStates()
    }

    fun closeSettings() {
        isSettingsOpen = false
    }

    fun onProviderCookiesChange(providerId: String, value: String) {
        providerCookieInputs = providerCookieInputs + (providerId to value)
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

    fun onQueryChange(value: String) {
        query = value
    }

    fun onSearchScopeChange(value: SearchScope) {
        searchScope = value
        if (value != SearchScope.Provider) {
            selectedSearchProviderId = null
        }
        if (query.isNotBlank()) search()
    }

    fun onSearchProviderChange(providerId: String) {
        searchScope = SearchScope.Provider
        selectedSearchProviderId = providerId
        if (query.isNotBlank()) search()
    }

    fun onHomeSectionChange(value: HomeSection) {
        homeSection = value
        if (value != HomeSection.Local && contentSectionsFor(value).isEmpty()) {
            refreshHomeContent(value)
        }
    }

    fun onLocalMusicViewModeChange(value: LocalMusicViewMode) {
        localMusicViewMode = value
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
            val title = if (section == HomeSection.Recommend) "推荐" else "音乐"
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
                        runCatching { providerRepository.loadFeature(feature) }
                            .getOrElse { ProviderContentSection(feature, errorMessage = it.message ?: "加载失败") }
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
        val tracks = (recommendSections + musicSections).firstOrNull { it.feature.id == featureId }?.tracks.orEmpty()
        val track = tracks.getOrNull(index) ?: return
        play(track, tracks, index)
    }

    fun playAllFromFeature(featureId: String) {
        val tracks = (recommendSections + musicSections).firstOrNull { it.feature.id == featureId }?.tracks.orEmpty()
        playFirst(tracks)
    }

    fun playFromSelectedPlaylist(index: Int) {
        val track = selectedPlaylistTracks.getOrNull(index) ?: return
        play(track, selectedPlaylistTracks, index)
    }

    fun playAllFromSelectedPlaylist() {
        playFirst(selectedPlaylistTracks)
    }

    fun playQueueIndex(index: Int) {
        val track = queue.getOrNull(index) ?: return
        play(track, queue, index)
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

    private fun play(track: MusicTrack, sourceQueue: List<MusicTrack>, index: Int) {
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
                playbackEngine.play(playableTrack, payload)
                playbackState = playbackState.copy(
                    status = PlayerStatus.Loading,
                    currentTrack = playableTrack,
                    queue = queue,
                    queueIndex = queueIndex,
                    lyrics = payload.lyrics,
                )
                message = "${playableTrack.title} - ${playableTrack.artists}"
            }.onFailure {
                setError(it)
            }
            isLoading = false
        }
    }

    private fun playFirst(sourceQueue: List<MusicTrack>) {
        val track = sourceQueue.firstOrNull() ?: return
        play(track, sourceQueue, 0)
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
        )
    }

    private fun mergeResults(local: List<MusicTrack>, provider: List<MusicTrack>): List<MusicTrack> {
        val seen = linkedSetOf<String>()
        return (local + provider).filter { seen.add(it.id) }
    }

    private fun providerName(providerId: String): String {
        return providers.firstOrNull { it.providerId == providerId }?.providerName ?: providerId
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
}
