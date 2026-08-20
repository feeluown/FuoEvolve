package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

interface LocalMusicFeatureController : LocalMusicActionPort {
    val uiState: StateFlow<LocalMusicUiState>
    val hasPermission: Boolean

    fun onPermissionChange(hasPermission: Boolean)
    fun ensure()
    fun refresh()
    fun refresh(forceRefresh: Boolean, showLoading: Boolean)
    fun onViewModeChange(value: LocalMusicViewMode)
    fun openDirectory(directoryId: String)
    fun openCollection(mode: LocalMusicViewMode, key: String)
    fun closeCollection()
    fun onDirectoryEnabledChange(directoryId: String, enabled: Boolean)
    fun onMinDurationChange(value: Int)
    fun openMetadataEditor(track: MusicTrack)
    fun closeMetadataEditor()
    fun onMetadataProviderChange(providerId: String)
    fun saveMetadata(track: MusicTrack, title: String, artists: String, album: String)
    fun searchMetadata(title: String, artists: String, album: String)
    fun applyProviderMetadata(track: MusicTrack, providerTrack: MusicTrack)
    fun downloadLyrics(track: MusicTrack, providerTrack: MusicTrack)
    fun refreshDirectories()
    suspend fun updateScanSettings()
}

internal class LocalMusicController(
    private val repository: LocalMusicRepository,
    providerRepository: ProviderMusicRepository,
    private val navigator: AppNavigator,
    private val scope: CoroutineScope,
    val state: LocalMusicControllerState = LocalMusicControllerState(),
    private val providers: () -> List<ProviderInfo>,
    private val selectedSearchProviderId: () -> String?,
    private val isLocalMusicSectionActive: () -> Boolean,
    private val persistSettings: () -> Unit,
    private val setLoading: (Boolean) -> Unit,
    private val setMessage: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onTrackUpdated: (String, MusicTrack) -> Unit,
) : LocalMusicFeatureController {
    private val providerSearchRepository: ProviderSearchRepository = ProviderSearchRepositoryView(providerRepository)
    private val providerPlaybackRepository: ProviderPlaybackRepository = ProviderPlaybackRepositoryView(providerRepository)
    private val mutableUiState = MutableStateFlow(LocalMusicUiState())

    override val uiState: StateFlow<LocalMusicUiState> = mutableUiState.asStateFlow()
    override var hasPermission: Boolean = false
        private set

    private var refreshSerial: Long = 0
    private var activeRefreshLoadingSerial: Long? = null
    private var featureLoading = false
    private var featureMessage: String? = null
    private var featureErrorMessage: String? = null

    init {
        state.observeChanges(::publishUiState)
    }

    fun restore(
        viewMode: LocalMusicViewMode,
        excludedDirectoryIds: Set<String>,
        minDurationSeconds: Int,
    ) {
        state.viewMode = viewMode
        state.excludedDirectoryIds = excludedDirectoryIds
            .mapNotNull(::canonicalLocalMusicDirectoryId)
            .toSet()
        state.minDurationSeconds = minDurationSeconds
    }

    override fun onPermissionChange(hasPermission: Boolean) {
        val wasGranted = this.hasPermission
        this.hasPermission = hasPermission
        if (hasPermission && !wasGranted && isLocalMusicSectionActive()) {
            ensure()
        }
    }

    override fun ensure() {
        if (!hasPermission) return
        refresh(forceRefresh = false, showLoading = true)
    }

    override fun refresh() {
        if (!hasPermission) {
            publishMessage("允许访问音频后可加载本地音乐")
            return
        }
        refresh(forceRefresh = true, showLoading = true)
    }

    override fun refresh(forceRefresh: Boolean, showLoading: Boolean) {
        val serial = ++refreshSerial
        scope.launch {
            if (showLoading) {
                publishLoading(
                    message = if (forceRefresh) "正在刷新本地音乐库" else "正在加载本地音乐",
                    loadingRefreshSerial = serial,
                )
            }
            val result = runCatching {
                updateScanSettings()
                val databaseReady = repository.isDatabaseReady()
                val databaseStale = databaseReady && repository.isDatabaseStale()
                val shouldRefresh = forceRefresh || !databaseReady || databaseStale
                if (showLoading) {
                    publishLoading(
                        message = when {
                            !databaseReady -> "正在建立本地音乐库"
                            shouldRefresh -> "正在更新本地音乐库"
                            else -> "正在加载本地音乐"
                        },
                        loadingRefreshSerial = serial,
                    )
                }
                val tracks = if (shouldRefresh) {
                    repository.refreshDatabase()
                } else {
                    repository.tracks()
                }
                tracks to repository.directories()
            }
            if (serial == refreshSerial) {
                result.onSuccess { (tracks, directories) ->
                    state.tracks = tracks
                    state.directories = directories
                    if (
                        state.selectedDirectoryId != null &&
                        directories.none { it.id == state.selectedDirectoryId }
                    ) {
                        closeCollection()
                    }
                }
            }
            if (showLoading && activeRefreshLoadingSerial == serial) {
                if (serial == refreshSerial) {
                    result
                        .onSuccess { (tracks, _) ->
                            finishLoading(if (tracks.isEmpty()) "未发现本地音乐" else "本地音乐 ${tracks.size} 首")
                        }
                        .onFailure(::publishError)
                } else {
                    clearLoading()
                }
            } else if (!showLoading && serial == refreshSerial) {
                clearSupersededRefreshLoading(serial)
            }
        }
    }

    override fun onViewModeChange(value: LocalMusicViewMode) {
        state.viewMode = value
        closeCollection()
        persistSettings()
    }

    override fun openDirectory(directoryId: String) {
        if (isLocalMusicDirectoryExcluded(directoryId, state.excludedDirectoryIds)) return
        if (state.directories.none { it.id == directoryId }) return
        navigator.navigate(AppRoute.LocalMusicCollection)
        state.selectedCollection = LocalMusicCollectionSelection(LocalMusicViewMode.All, directoryId)
        state.selectedDirectoryId = directoryId
    }

    override fun openCollection(mode: LocalMusicViewMode, key: String) {
        if (key.isBlank()) return
        if (mode == LocalMusicViewMode.All) {
            openDirectory(key)
            return
        }
        navigator.navigate(AppRoute.LocalMusicCollection)
        state.selectedDirectoryId = null
        state.selectedCollection = LocalMusicCollectionSelection(mode, key)
    }

    override fun closeCollection() {
        navigator.pop(AppRoute.LocalMusicCollection)
        state.selectedDirectoryId = null
        state.selectedCollection = null
    }

    override fun onDirectoryEnabledChange(directoryId: String, enabled: Boolean) {
        val canonicalDirectoryId = canonicalLocalMusicDirectoryId(directoryId) ?: directoryId
        val normalizedExcludedIds = state.excludedDirectoryIds.mapNotNull {
            canonicalLocalMusicDirectoryId(it)
        }.toSet()
        state.excludedDirectoryIds = if (enabled) {
            normalizedExcludedIds - canonicalDirectoryId
        } else {
            normalizedExcludedIds + canonicalDirectoryId
        }
        if (!enabled && state.selectedDirectoryId == directoryId) {
            closeCollection()
        }
        persistSettings()
        reload()
    }

    override fun onMinDurationChange(value: Int) {
        state.minDurationSeconds = value
        persistSettings()
        reload()
    }

    override fun openLocalMetadataEditor(track: MusicTrack) = openMetadataEditor(track)

    override fun openMetadataEditor(track: MusicTrack) {
        if (track.sourceType == TrackSourceType.Provider) return
        state.metadataEditorTrack = track
        state.metadataSearchResults = emptyList()
        state.metadataSearchMessage = null
        val availableProviders = providers()
        state.selectedMetadataProviderId = state.selectedMetadataProviderId
            ?.takeIf { providerId -> availableProviders.any { it.providerId == providerId } }
            ?: selectedSearchProviderId()?.takeIf { providerId -> availableProviders.any { it.providerId == providerId } }
            ?: availableProviders.firstOrNull()?.providerId
        featureErrorMessage = null
        publishUiState()
    }

    override fun closeMetadataEditor() {
        state.metadataEditorTrack = null
        state.metadataSearchResults = emptyList()
        state.metadataSearchMessage = null
    }

    override fun onMetadataProviderChange(providerId: String) {
        if (providers().none { it.providerId == providerId }) return
        state.selectedMetadataProviderId = providerId
    }

    override fun saveMetadata(track: MusicTrack, title: String, artists: String, album: String) {
        val metadata = LocalTrackMetadata(
            title = title.trim().ifBlank { track.title },
            artists = artists.trim(),
            album = album.trim(),
        )
        scope.launch {
            publishLoading("正在保存元信息")
            runCatching {
                repository.updateMetadata(track, metadata)
                updateScanSettings()
                repository.refreshDatabase()
            }.onSuccess { tracks ->
                state.tracks = tracks
                val updatedTrack = tracks.firstOrNull { item -> item.id == track.id } ?: track.copy(
                    title = metadata.title,
                    artists = metadata.artists,
                    album = metadata.album,
                )
                onTrackUpdated(track.id, updatedTrack)
                state.metadataEditorTrack = tracks.firstOrNull { item -> item.id == track.id }
                    ?: state.metadataEditorTrack
                finishLoading("已保存元信息：${metadata.title}")
            }.onFailure(::publishError)
        }
    }

    override fun searchMetadata(title: String, artists: String, album: String) {
        val availableProviders = providers()
        val providerId = state.selectedMetadataProviderId ?: availableProviders.firstOrNull()?.providerId
        if (providerId == null) {
            state.metadataSearchMessage = "没有可用音源"
            return
        }
        val keyword = listOf(title, artists, album)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
        if (keyword.isBlank()) {
            state.metadataSearchMessage = "请输入可搜索的信息"
            return
        }
        state.selectedMetadataProviderId = providerId
        scope.launch {
            publishLoading("正在搜索元信息")
            state.metadataSearchMessage = "正在搜索元信息"
            runCatching {
                withTimeout(25_000) {
                    providerSearchRepository.search(keyword, providerId)
                }
            }.onSuccess { results ->
                state.metadataSearchResults = results
                state.metadataSearchMessage = if (results.isEmpty()) "没有搜索结果" else "搜索到 ${results.size} 首"
                finishLoading(state.metadataSearchMessage.orEmpty())
            }.onFailure { throwable ->
                val message = providerFailureMessage(throwable, providerId)
                state.metadataSearchMessage = message
                publishError(throwable, message)
            }
        }
    }

    override fun applyProviderMetadata(track: MusicTrack, providerTrack: MusicTrack) {
        saveMetadata(track, providerTrack.title, providerTrack.artists, providerTrack.album)
    }

    override fun downloadLyrics(track: MusicTrack, providerTrack: MusicTrack) {
        scope.launch {
            publishLoading("正在下载歌词")
            runCatching {
                withTimeout(25_000) {
                    providerPlaybackRepository.lyrics(
                        providerTrack.copy(providerId = providerTrack.providerId ?: providerTrack.id),
                    )
                }
            }.onSuccess { lyricsText ->
                val lyrics = lyricsText?.takeIf { it.isNotBlank() }
                if (lyrics == null) {
                    state.metadataSearchMessage = "未获取到歌词"
                    finishLoading("未获取到歌词")
                } else {
                    runCatching {
                        repository.saveLyrics(track, lyrics)
                        updateScanSettings()
                        repository.refreshDatabase()
                    }.onSuccess { tracks ->
                        state.tracks = tracks
                        val updatedTrack = tracks.firstOrNull { item -> item.id == track.id }
                            ?: track.copy(lyrics = lyrics)
                        onTrackUpdated(track.id, updatedTrack)
                        state.metadataEditorTrack = updatedTrack
                        state.metadataSearchMessage = "歌词已保存"
                        finishLoading("已保存歌词：${track.title}")
                    }.onFailure(::publishError)
                }
            }.onFailure { throwable ->
                val providerId = providerTrack.providerId ?: providerTrack.source
                val message = providerFailureMessage(throwable, providerId)
                state.metadataSearchMessage = message
                publishError(throwable, message)
            }
        }
    }

    override fun refreshDirectories() {
        scope.launch {
            runCatching {
                updateScanSettings()
                repository.directories()
            }.onSuccess { directories ->
                state.directories = directories
                if (
                    state.selectedDirectoryId != null &&
                    directories.none { directory -> directory.id == state.selectedDirectoryId }
                ) {
                    closeCollection()
                }
                featureErrorMessage = null
                publishUiState()
            }.onFailure(::publishError)
        }
    }

    override suspend fun updateScanSettings() {
        repository.updateScanSettings(
            LocalMusicScanSettings(
                excludedDirectoryIds = state.excludedDirectoryIds,
                minDurationSeconds = state.minDurationSeconds,
            )
        )
    }

    private fun reload() {
        if (!hasPermission) return
        val serial = ++refreshSerial
        scope.launch {
            publishLoading("正在更新本地音乐筛选", loadingRefreshSerial = serial)
            val result = runCatching {
                updateScanSettings()
                repository.tracks() to repository.directories()
            }
            if (serial == refreshSerial) {
                result
                    .onSuccess { (tracks, directories) ->
                        state.tracks = tracks
                        state.directories = directories
                        if (
                            state.selectedDirectoryId != null &&
                            directories.none { it.id == state.selectedDirectoryId }
                        ) {
                            closeCollection()
                        }
                        finishLoading(if (tracks.isEmpty()) "未发现本地音乐" else "本地音乐 ${tracks.size} 首")
                    }
                    .onFailure(::publishError)
            } else if (activeRefreshLoadingSerial == serial) {
                clearLoading()
            }
        }
    }

    private fun publishLoading(message: String, loadingRefreshSerial: Long? = null) {
        activeRefreshLoadingSerial = loadingRefreshSerial
        featureLoading = true
        featureMessage = message
        featureErrorMessage = null
        setLoading(true)
        setMessage(message)
        publishUiState()
    }

    private fun finishLoading(message: String) {
        activeRefreshLoadingSerial = null
        featureLoading = false
        featureMessage = message
        featureErrorMessage = null
        setMessage(message)
        setLoading(false)
        publishUiState()
    }

    private fun clearSupersededRefreshLoading(latestRefreshSerial: Long) {
        val loadingSerial = activeRefreshLoadingSerial ?: return
        if (loadingSerial >= latestRefreshSerial) return
        clearLoading()
    }

    private fun clearLoading() {
        activeRefreshLoadingSerial = null
        featureLoading = false
        setLoading(false)
        publishUiState()
    }

    private fun publishMessage(message: String) {
        featureMessage = message
        featureErrorMessage = null
        setMessage(message)
        publishUiState()
    }

    private fun publishError(throwable: Throwable) {
        publishError(
            throwable = throwable,
            message = throwable.message ?: throwable::class.simpleName.orEmpty().ifBlank { "操作失败" },
        )
    }

    private fun publishError(throwable: Throwable, message: String) {
        activeRefreshLoadingSerial = null
        featureLoading = false
        featureMessage = message
        featureErrorMessage = message
        onError(throwable)
        setLoading(false)
        publishUiState()
    }

    private fun providerFailureMessage(throwable: Throwable, providerId: String): String =
        throwable.providerFailureOrNull(providerId)?.userMessage
            ?: throwable.message
            ?: throwable::class.simpleName.orEmpty().ifBlank { "操作失败" }

    private fun publishUiState() {
        mutableUiState.value = state.toUiState(
            LocalMusicUiState(
                metadataProviders = providers(),
                isLoading = featureLoading,
                message = featureMessage,
                errorMessage = featureErrorMessage,
            )
        )
    }
}
