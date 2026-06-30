package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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

class FuoPlayerController(
    private val providerRepository: ProviderMusicRepository,
    private val localRepository: LocalMusicRepository,
    private val downloadRepository: DownloadRepository,
    private val playbackEngine: PlaybackEngine,
    private val scope: CoroutineScope,
) {
    var localTracks by mutableStateOf<List<MusicTrack>>(emptyList())
        private set
    var query by mutableStateOf("")
        private set
    var searchScope by mutableStateOf(SearchScope.All)
        private set
    var searchResults by mutableStateOf<List<MusicTrack>>(emptyList())
        private set
    var homeSection by mutableStateOf(HomeSection.Recommend)
        private set
    var isSearchOpen by mutableStateOf(false)
        private set
    var isFullPlayerOpen by mutableStateOf(false)
        private set
    var isSettingsOpen by mutableStateOf(false)
        private set
    var isQueueOpen by mutableStateOf(false)
        private set
    var neteaseCookies by mutableStateOf("")
        private set
    var providerAuthState by mutableStateOf(
        ProviderAuthState(
            providerId = NETEASE_PROVIDER_ID,
            providerName = "网易云音乐",
            isLoggedIn = false,
        )
    )
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
                downloadRepository.load()
            }.onSuccess {
                message = "音乐服务已就绪"
                refreshProviderAuthState()
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
        refreshProviderAuthState()
    }

    fun closeSettings() {
        isSettingsOpen = false
    }

    fun onNeteaseCookiesChange(value: String) {
        neteaseCookies = value
    }

    fun loginNeteaseWithCookies() {
        val cookies = neteaseCookies.trim()
        if (cookies.isEmpty()) {
            message = "请输入网易云 cookies JSON"
            return
        }
        scope.launch {
            isLoading = true
            message = "正在登录网易云音乐"
            runCatching { providerRepository.loginWithCookies(NETEASE_PROVIDER_ID, cookies) }
                .onSuccess {
                    providerAuthState = it
                    neteaseCookies = ""
                    message = if (it.isLoggedIn) {
                        "网易云音乐已登录：${it.userName.orEmpty()}"
                    } else {
                        "网易云音乐未登录"
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
        if (query.isNotBlank()) search()
    }

    fun onHomeSectionChange(value: HomeSection) {
        homeSection = value
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
                        SearchScope.Provider -> providerRepository.search(keyword)
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

    private fun refreshProviderAuthState() {
        scope.launch {
            runCatching { providerRepository.authState(NETEASE_PROVIDER_ID) }
                .onSuccess { providerAuthState = it }
        }
    }

    fun playFromLocal(index: Int) {
        val track = localTracks.getOrNull(index) ?: return
        play(track, localTracks, index)
    }

    fun playFromSearch(index: Int) {
        val track = searchResults.getOrNull(index) ?: return
        play(track, searchResults, index)
        closeSearch()
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

    private fun setError(throwable: Throwable) {
        message = when (throwable) {
            is TimeoutCancellationException -> "操作超时，请检查网络后重试"
            else -> throwable.message ?: throwable::class.simpleName.orEmpty()
        }
        playbackState = playbackState.copy(status = PlayerStatus.Error, errorMessage = message)
        isLoading = false
    }

    private fun Int.floorMod(size: Int): Int = ((this % size) + size) % size

    private companion object {
        private const val NETEASE_PROVIDER_ID = "netease"
    }
}
