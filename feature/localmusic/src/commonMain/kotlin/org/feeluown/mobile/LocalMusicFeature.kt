package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class LocalMusicFeatureState<Track, Provider, Directory, ViewMode, Selection>(
    val tracks: List<Track> = emptyList(),
    val viewMode: ViewMode,
    val directories: List<Directory> = emptyList(),
    val selectedDirectoryId: String? = null,
    val selectedCollection: Selection? = null,
    val excludedDirectoryIds: Set<String> = emptySet(),
    val minDurationSeconds: Int = 0,
    val metadataEditorTrack: Track? = null,
    val metadataProviders: List<Provider> = emptyList(),
    val selectedMetadataProviderId: String? = null,
    val metadataSearchResults: List<Track> = emptyList(),
    val metadataSearchMessage: String? = null,
    val isLoading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

interface LocalMusicRepositoryPort<Track, Directory> {
    val mediaChangeEvents: Flow<Unit>
    suspend fun updateScanSettings(excludedDirectoryIds: Set<String>, minDurationSeconds: Int)
    suspend fun isDatabaseReady(): Boolean
    suspend fun isDatabaseStale(): Boolean
    suspend fun refreshDatabase(): List<Track>
    suspend fun tracks(): List<Track>
    suspend fun directories(): List<Directory>
    suspend fun updateMetadata(track: Track, title: String, artists: String, album: String)
    suspend fun saveLyrics(track: Track, lyrics: String)
}

interface LocalMusicProviderPort<Track> {
    suspend fun search(keyword: String, providerId: String): List<Track>
    suspend fun lyrics(track: Track): String?
}

interface LocalMusicFeatureOperations<Track, Provider, Directory, ViewMode, Selection> {
    fun trackId(track: Track): String
    fun trackTitle(track: Track): String
    fun trackArtists(track: Track): String
    fun trackAlbum(track: Track): String
    fun isProviderTrack(track: Track): Boolean
    fun providerTrackId(track: Track): String?
    fun withProviderTrackId(track: Track, providerTrackId: String): Track
    fun withMetadata(track: Track, title: String, artists: String, album: String): Track
    fun withLyrics(track: Track, lyrics: String): Track

    fun providerId(provider: Provider): String
    fun directoryId(directory: Directory): String
    fun defaultViewMode(): ViewMode
    fun isAllViewMode(viewMode: ViewMode): Boolean
    fun collection(viewMode: ViewMode, key: String): Selection
}

interface LocalMusicFeatureOwner<Track, Provider, Directory, ViewMode, Selection> {
    val uiState: StateFlow<LocalMusicFeatureState<Track, Provider, Directory, ViewMode, Selection>>
    val hasPermission: Boolean

    fun restore(viewMode: ViewMode, excludedDirectoryIds: Set<String>, minDurationSeconds: Int)
    fun onPermissionChange(hasPermission: Boolean)
    fun ensure()
    fun refresh()
    fun refresh(forceRefresh: Boolean, showLoading: Boolean)
    fun onViewModeChange(value: ViewMode)
    fun openDirectory(directoryId: String)
    fun openCollection(mode: ViewMode, key: String)
    fun closeCollection()
    fun onDirectoryEnabledChange(directoryId: String, enabled: Boolean)
    fun onMinDurationChange(value: Int)
    fun openLocalMetadataEditor(track: Track)
    fun openMetadataEditor(track: Track)
    fun closeMetadataEditor()
    fun onMetadataProviderChange(providerId: String)
    fun saveMetadata(track: Track, title: String, artists: String, album: String)
    fun searchMetadata(title: String, artists: String, album: String)
    fun applyProviderMetadata(track: Track, providerTrack: Track)
    fun downloadLyrics(track: Track, providerTrack: Track)
    fun refreshDirectories()
    suspend fun updateScanSettings()
}

fun <Track, Provider, Directory, ViewMode, Selection> createLocalMusicFeatureOwner(
    repository: LocalMusicRepositoryPort<Track, Directory>,
    providerRepository: LocalMusicProviderPort<Track>,
    operations: LocalMusicFeatureOperations<Track, Provider, Directory, ViewMode, Selection>,
    providers: () -> List<Provider>,
    selectedSearchProviderId: () -> String?,
    isLocalMusicSectionActive: () -> Boolean,
    scope: CoroutineScope,
    openCollectionRoute: () -> Unit,
    closeCollectionRoute: () -> Unit,
    persistSettings: (ViewMode, Set<String>, Int) -> Unit,
    onMessage: (String) -> Unit = {},
    failureMessage: (Throwable, String?) -> String = { throwable, _ ->
        throwable.message ?: throwable::class.simpleName.orEmpty().ifBlank { "操作失败" }
    },
    onError: (Throwable) -> Unit = {},
    onTrackUpdated: (String, Track) -> Unit = { _, _ -> },
): LocalMusicFeatureOwner<Track, Provider, Directory, ViewMode, Selection> =
    DefaultLocalMusicFeatureOwner(
        repository = repository,
        providerRepository = providerRepository,
        operations = operations,
        providers = providers,
        selectedSearchProviderId = selectedSearchProviderId,
        isLocalMusicSectionActive = isLocalMusicSectionActive,
        scope = scope,
        openCollectionRoute = openCollectionRoute,
        closeCollectionRoute = closeCollectionRoute,
        persistSettings = persistSettings,
        onMessage = onMessage,
        failureMessage = failureMessage,
        onError = onError,
        onTrackUpdated = onTrackUpdated,
    )

private class DefaultLocalMusicFeatureOwner<Track, Provider, Directory, ViewMode, Selection>(
    private val repository: LocalMusicRepositoryPort<Track, Directory>,
    private val providerRepository: LocalMusicProviderPort<Track>,
    private val operations: LocalMusicFeatureOperations<Track, Provider, Directory, ViewMode, Selection>,
    private val providers: () -> List<Provider>,
    private val selectedSearchProviderId: () -> String?,
    private val isLocalMusicSectionActive: () -> Boolean,
    private val scope: CoroutineScope,
    private val openCollectionRoute: () -> Unit,
    private val closeCollectionRoute: () -> Unit,
    private val persistSettings: (ViewMode, Set<String>, Int) -> Unit,
    private val onMessage: (String) -> Unit,
    private val failureMessage: (Throwable, String?) -> String,
    private val onError: (Throwable) -> Unit,
    private val onTrackUpdated: (String, Track) -> Unit,
) : LocalMusicFeatureOwner<Track, Provider, Directory, ViewMode, Selection> {
    private val mutableUiState = MutableStateFlow(
        LocalMusicFeatureState<Track, Provider, Directory, ViewMode, Selection>(
            viewMode = operations.defaultViewMode(),
        ),
    )
    override val uiState: StateFlow<LocalMusicFeatureState<Track, Provider, Directory, ViewMode, Selection>> =
        mutableUiState.asStateFlow()

    override var hasPermission: Boolean = false
        private set

    private var refreshSerial = 0L
    private var activeRefreshLoadingSerial: Long? = null

    override fun restore(viewMode: ViewMode, excludedDirectoryIds: Set<String>, minDurationSeconds: Int) {
        mutableUiState.value = uiState.value.copy(
            viewMode = viewMode,
            excludedDirectoryIds = excludedDirectoryIds.mapNotNull(::canonicalDirectoryId).toSet(),
            minDurationSeconds = minDurationSeconds,
        )
    }

    override fun onPermissionChange(hasPermission: Boolean) {
        val wasGranted = this.hasPermission
        this.hasPermission = hasPermission
        if (hasPermission && !wasGranted && isLocalMusicSectionActive()) ensure()
    }

    override fun ensure() {
        if (hasPermission) refresh(forceRefresh = false, showLoading = true)
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
                    if (forceRefresh) "正在刷新本地音乐库" else "正在加载本地音乐",
                    serial,
                )
            }
            val result = runCatching {
                updateScanSettings()
                val databaseReady = repository.isDatabaseReady()
                val databaseStale = databaseReady && repository.isDatabaseStale()
                val shouldRefresh = forceRefresh || !databaseReady || databaseStale
                if (showLoading) {
                    publishLoading(
                        when {
                            !databaseReady -> "正在建立本地音乐库"
                            shouldRefresh -> "正在更新本地音乐库"
                            else -> "正在加载本地音乐"
                        },
                        serial,
                    )
                }
                val tracks = if (shouldRefresh) repository.refreshDatabase() else repository.tracks()
                tracks to repository.directories()
            }
            if (serial == refreshSerial) {
                result.onSuccess { (tracks, directories) ->
                    mutableUiState.value = uiState.value.copy(tracks = tracks, directories = directories)
                    val selectedDirectoryId = uiState.value.selectedDirectoryId
                    if (selectedDirectoryId != null && directories.none { operations.directoryId(it) == selectedDirectoryId }) {
                        closeCollection()
                    }
                }
            }
            if (showLoading && activeRefreshLoadingSerial == serial) {
                if (serial == refreshSerial) {
                    result
                        .onSuccess { (tracks, _) -> finishLoading(if (tracks.isEmpty()) "未发现本地音乐" else "本地音乐 ${tracks.size} 首") }
                        .onFailure(::publishError)
                } else {
                    clearLoading()
                }
            } else if (!showLoading && serial == refreshSerial) {
                clearSupersededRefreshLoading(serial)
            }
        }
    }

    override fun onViewModeChange(value: ViewMode) {
        mutableUiState.value = uiState.value.copy(viewMode = value)
        closeCollection()
        persistCurrentSettings()
    }

    override fun openDirectory(directoryId: String) {
        if (isDirectoryExcluded(directoryId, uiState.value.excludedDirectoryIds)) return
        if (uiState.value.directories.none { operations.directoryId(it) == directoryId }) return
        openCollectionRoute()
        mutableUiState.value = uiState.value.copy(
            selectedCollection = operations.collection(operations.defaultViewMode(), directoryId),
            selectedDirectoryId = directoryId,
        )
    }

    override fun openCollection(mode: ViewMode, key: String) {
        if (key.isBlank()) return
        if (operations.isAllViewMode(mode)) {
            openDirectory(key)
            return
        }
        openCollectionRoute()
        mutableUiState.value = uiState.value.copy(
            selectedDirectoryId = null,
            selectedCollection = operations.collection(mode, key),
        )
    }

    override fun closeCollection() {
        closeCollectionRoute()
        mutableUiState.value = uiState.value.copy(selectedDirectoryId = null, selectedCollection = null)
    }

    override fun onDirectoryEnabledChange(directoryId: String, enabled: Boolean) {
        val canonical = canonicalDirectoryId(directoryId) ?: directoryId
        val normalized = uiState.value.excludedDirectoryIds.mapNotNull(::canonicalDirectoryId).toSet()
        val excluded = if (enabled) normalized - canonical else normalized + canonical
        mutableUiState.value = uiState.value.copy(excludedDirectoryIds = excluded)
        if (!enabled && uiState.value.selectedDirectoryId == directoryId) closeCollection()
        persistCurrentSettings()
        reload()
    }

    override fun onMinDurationChange(value: Int) {
        mutableUiState.value = uiState.value.copy(minDurationSeconds = value)
        persistCurrentSettings()
        reload()
    }

    override fun openLocalMetadataEditor(track: Track) = openMetadataEditor(track)

    override fun openMetadataEditor(track: Track) {
        if (operations.isProviderTrack(track)) return
        val availableProviders = providers()
        val availableIds = availableProviders.map(operations::providerId).toSet()
        val selected = uiState.value.selectedMetadataProviderId?.takeIf(availableIds::contains)
            ?: selectedSearchProviderId()?.takeIf(availableIds::contains)
            ?: availableProviders.firstOrNull()?.let(operations::providerId)
        mutableUiState.value = uiState.value.copy(
            metadataEditorTrack = track,
            metadataProviders = availableProviders,
            metadataSearchResults = emptyList(),
            metadataSearchMessage = null,
            selectedMetadataProviderId = selected,
            errorMessage = null,
        )
    }

    override fun closeMetadataEditor() {
        mutableUiState.value = uiState.value.copy(
            metadataEditorTrack = null,
            metadataSearchResults = emptyList(),
            metadataSearchMessage = null,
        )
    }

    override fun onMetadataProviderChange(providerId: String) {
        if (providers().none { operations.providerId(it) == providerId }) return
        mutableUiState.value = uiState.value.copy(selectedMetadataProviderId = providerId)
    }

    override fun saveMetadata(track: Track, title: String, artists: String, album: String) {
        val normalizedTitle = title.trim().ifBlank { operations.trackTitle(track) }
        val normalizedArtists = artists.trim()
        val normalizedAlbum = album.trim()
        scope.launch {
            publishLoading("正在保存元信息")
            runCatching {
                repository.updateMetadata(track, normalizedTitle, normalizedArtists, normalizedAlbum)
                updateScanSettings()
                repository.refreshDatabase()
            }.onSuccess { tracks ->
                val trackId = operations.trackId(track)
                val updatedTrack = tracks.firstOrNull { operations.trackId(it) == trackId }
                    ?: operations.withMetadata(track, normalizedTitle, normalizedArtists, normalizedAlbum)
                onTrackUpdated(trackId, updatedTrack)
                mutableUiState.value = uiState.value.copy(
                    tracks = tracks,
                    metadataEditorTrack = tracks.firstOrNull { operations.trackId(it) == trackId } ?: uiState.value.metadataEditorTrack,
                )
                finishLoading("已保存元信息：$normalizedTitle")
            }.onFailure(::publishError)
        }
    }

    override fun searchMetadata(title: String, artists: String, album: String) {
        val availableProviders = providers()
        val providerId = uiState.value.selectedMetadataProviderId
            ?: availableProviders.firstOrNull()?.let(operations::providerId)
        if (providerId == null) {
            mutableUiState.value = uiState.value.copy(metadataSearchMessage = "没有可用音源")
            return
        }
        val keyword = listOf(title, artists, album).map(String::trim).filter(String::isNotBlank).distinct().joinToString(" ")
        if (keyword.isBlank()) {
            mutableUiState.value = uiState.value.copy(metadataSearchMessage = "请输入可搜索的信息")
            return
        }
        mutableUiState.value = uiState.value.copy(selectedMetadataProviderId = providerId)
        scope.launch {
            publishLoading("正在搜索元信息")
            mutableUiState.value = uiState.value.copy(metadataSearchMessage = "正在搜索元信息")
            runCatching { withTimeout(25_000) { providerRepository.search(keyword, providerId) } }
                .onSuccess { results ->
                    val message = if (results.isEmpty()) "没有搜索结果" else "搜索到 ${results.size} 首"
                    mutableUiState.value = uiState.value.copy(metadataSearchResults = results, metadataSearchMessage = message)
                    finishLoading(message)
                }
                .onFailure { throwable ->
                    val message = failureMessage(throwable, providerId)
                    mutableUiState.value = uiState.value.copy(metadataSearchMessage = message)
                    publishError(throwable, message)
                }
        }
    }

    override fun applyProviderMetadata(track: Track, providerTrack: Track) {
        saveMetadata(
            track,
            operations.trackTitle(providerTrack),
            operations.trackArtists(providerTrack),
            operations.trackAlbum(providerTrack),
        )
    }

    override fun downloadLyrics(track: Track, providerTrack: Track) {
        scope.launch {
            publishLoading("正在下载歌词")
            val providerId = operations.providerTrackId(providerTrack)
            val resolveTrack = providerId?.let { operations.withProviderTrackId(providerTrack, it) } ?: providerTrack
            runCatching { withTimeout(25_000) { providerRepository.lyrics(resolveTrack) } }
                .onSuccess { lyricsText ->
                    val lyrics = lyricsText?.takeIf(String::isNotBlank)
                    if (lyrics == null) {
                        mutableUiState.value = uiState.value.copy(metadataSearchMessage = "未获取到歌词")
                        finishLoading("未获取到歌词")
                    } else {
                        runCatching {
                            repository.saveLyrics(track, lyrics)
                            updateScanSettings()
                            repository.refreshDatabase()
                        }.onSuccess { tracks ->
                            val trackId = operations.trackId(track)
                            val updated = tracks.firstOrNull { operations.trackId(it) == trackId }
                                ?: operations.withLyrics(track, lyrics)
                            onTrackUpdated(trackId, updated)
                            mutableUiState.value = uiState.value.copy(
                                tracks = tracks,
                                metadataEditorTrack = updated,
                                metadataSearchMessage = "歌词已保存",
                            )
                            finishLoading("已保存歌词：${operations.trackTitle(track)}")
                        }.onFailure(::publishError)
                    }
                }
                .onFailure { throwable ->
                    val message = failureMessage(throwable, providerId)
                    mutableUiState.value = uiState.value.copy(metadataSearchMessage = message)
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
                mutableUiState.value = uiState.value.copy(directories = directories, errorMessage = null)
                val selected = uiState.value.selectedDirectoryId
                if (selected != null && directories.none { operations.directoryId(it) == selected }) closeCollection()
            }.onFailure(::publishError)
        }
    }

    override suspend fun updateScanSettings() {
        repository.updateScanSettings(uiState.value.excludedDirectoryIds, uiState.value.minDurationSeconds)
    }

    private fun reload() {
        if (!hasPermission) return
        val serial = ++refreshSerial
        scope.launch {
            publishLoading("正在更新本地音乐筛选", serial)
            val result = runCatching {
                updateScanSettings()
                repository.tracks() to repository.directories()
            }
            if (serial == refreshSerial) {
                result
                    .onSuccess { (tracks, directories) ->
                        mutableUiState.value = uiState.value.copy(tracks = tracks, directories = directories)
                        val selected = uiState.value.selectedDirectoryId
                        if (selected != null && directories.none { operations.directoryId(it) == selected }) closeCollection()
                        finishLoading(if (tracks.isEmpty()) "未发现本地音乐" else "本地音乐 ${tracks.size} 首")
                    }
                    .onFailure(::publishError)
            } else if (activeRefreshLoadingSerial == serial) {
                clearLoading()
            }
        }
    }

    private fun persistCurrentSettings() {
        val state = uiState.value
        persistSettings(state.viewMode, state.excludedDirectoryIds, state.minDurationSeconds)
    }

    private fun publishLoading(message: String, refreshSerial: Long? = null) {
        activeRefreshLoadingSerial = refreshSerial
        onMessage(message)
        mutableUiState.value = uiState.value.copy(isLoading = true, message = message, errorMessage = null)
    }

    private fun finishLoading(message: String) {
        activeRefreshLoadingSerial = null
        onMessage(message)
        mutableUiState.value = uiState.value.copy(isLoading = false, message = message, errorMessage = null)
    }

    private fun clearSupersededRefreshLoading(latestRefreshSerial: Long) {
        val loadingSerial = activeRefreshLoadingSerial ?: return
        if (loadingSerial < latestRefreshSerial) clearLoading()
    }

    private fun clearLoading() {
        activeRefreshLoadingSerial = null
        mutableUiState.value = uiState.value.copy(isLoading = false)
    }

    private fun publishMessage(message: String) {
        onMessage(message)
        mutableUiState.value = uiState.value.copy(message = message, errorMessage = null)
    }

    private fun publishError(throwable: Throwable) = publishError(throwable, failureMessage(throwable, null))

    private fun publishError(throwable: Throwable, message: String) {
        activeRefreshLoadingSerial = null
        onError(throwable)
        mutableUiState.value = uiState.value.copy(
            isLoading = false,
            message = message,
            errorMessage = message,
        )
    }

    private fun canonicalDirectoryId(id: String): String? = id.trim('/').takeIf(String::isNotBlank)?.let { "$it/" }

    private fun isDirectoryExcluded(directoryId: String, excludedIds: Set<String>): Boolean {
        val canonical = canonicalDirectoryId(directoryId) ?: return directoryId in excludedIds
        val aliases = setOf(directoryId, canonical, canonical.removeSuffix("/"))
        return excludedIds.any { excluded ->
            val excludedCanonical = canonicalDirectoryId(excluded)
            excluded in aliases || excludedCanonical == canonical
        }
    }
}
