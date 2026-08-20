package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class LocalMusicController(
    private val repository: LocalMusicRepository,
    private val providerRepository: ProviderMusicRepository,
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
) : LocalMusicActionPort {
    var hasPermission: Boolean = false
        private set

    private var refreshSerial: Long = 0

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

    fun onPermissionChange(hasPermission: Boolean) {
        val wasGranted = this.hasPermission
        this.hasPermission = hasPermission
        if (hasPermission && !wasGranted && isLocalMusicSectionActive()) {
            ensure()
        }
    }

    fun ensure() {
        if (!hasPermission) return
        refresh(forceRefresh = false, showLoading = true)
    }

    fun refresh() {
        if (!hasPermission) {
            setMessage("允许访问音频后可加载本地音乐")
            return
        }
        refresh(forceRefresh = true, showLoading = true)
    }

    fun refresh(forceRefresh: Boolean, showLoading: Boolean) {
        val serial = ++refreshSerial
        scope.launch {
            if (showLoading) {
                setLoading(true)
                setMessage(if (forceRefresh) "正在刷新本地音乐库" else "正在加载本地音乐")
            }
            val result = runCatching {
                updateScanSettings()
                val databaseReady = repository.isDatabaseReady()
                val databaseStale = databaseReady && repository.isDatabaseStale()
                val shouldRefresh = forceRefresh || !databaseReady || databaseStale
                if (showLoading) {
                    setMessage(
                        when {
                            !databaseReady -> "正在建立本地音乐库"
                            shouldRefresh -> "正在更新本地音乐库"
                            else -> "正在加载本地音乐"
                        }
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
                        if (showLoading) {
                            setMessage(if (tracks.isEmpty()) "未发现本地音乐" else "本地音乐 ${tracks.size} 首")
                        }
                    }
                    .onFailure {
                        if (showLoading) onError(it)
                    }
            }
            if (showLoading) setLoading(false)
        }
    }

    fun onViewModeChange(value: LocalMusicViewMode) {
        state.viewMode = value
        closeCollection()
        persistSettings()
    }

    fun openDirectory(directoryId: String) {
        if (isLocalMusicDirectoryExcluded(directoryId, state.excludedDirectoryIds)) return
        if (state.directories.none { it.id == directoryId }) return
        navigator.navigate(AppRoute.LocalMusicCollection)
        state.selectedCollection = LocalMusicCollectionSelection(LocalMusicViewMode.All, directoryId)
        state.selectedDirectoryId = directoryId
    }

    fun openCollection(mode: LocalMusicViewMode, key: String) {
        if (key.isBlank()) return
        if (mode == LocalMusicViewMode.All) {
            openDirectory(key)
            return
        }
        navigator.navigate(AppRoute.LocalMusicCollection)
        state.selectedDirectoryId = null
        state.selectedCollection = LocalMusicCollectionSelection(mode, key)
    }

    fun closeCollection() {
        navigator.pop(AppRoute.LocalMusicCollection)
        state.selectedDirectoryId = null
        state.selectedCollection = null
    }

    fun onDirectoryEnabledChange(directoryId: String, enabled: Boolean) {
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

    fun onMinDurationChange(value: Int) {
        state.minDurationSeconds = value
        persistSettings()
        reload()
    }

    override fun openLocalMetadataEditor(track: MusicTrack) = openMetadataEditor(track)

    fun openMetadataEditor(track: MusicTrack) {
        if (track.sourceType == TrackSourceType.Provider) return
        state.metadataEditorTrack = track
        state.metadataSearchResults = emptyList()
        state.metadataSearchMessage = null
        val availableProviders = providers()
        state.selectedMetadataProviderId = state.selectedMetadataProviderId
            ?.takeIf { providerId -> availableProviders.any { it.providerId == providerId } }
            ?: selectedSearchProviderId()?.takeIf { providerId -> availableProviders.any { it.providerId == providerId } }
            ?: availableProviders.firstOrNull()?.providerId
    }

    fun closeMetadataEditor() {
        state.metadataEditorTrack = null
        state.metadataSearchResults = emptyList()
        state.metadataSearchMessage = null
    }

    fun onMetadataProviderChange(providerId: String) {
        state.selectedMetadataProviderId = providerId
    }

    fun saveMetadata(track: MusicTrack, title: String, artists: String, album: String) {
        val metadata = LocalTrackMetadata(
            title = title.trim().ifBlank { track.title },
            artists = artists.trim(),
            album = album.trim(),
        )
        scope.launch {
            setLoading(true)
            setMessage("正在保存元信息")
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
                setMessage("已保存元信息：${metadata.title}")
            }.onFailure(onError)
            setLoading(false)
        }
    }

    fun searchMetadata(title: String, artists: String, album: String) {
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
            setLoading(true)
            state.metadataSearchMessage = "正在搜索元信息"
            runCatching {
                withTimeout(25_000) {
                    providerRepository.search(keyword, providerId)
                }
            }.onSuccess {
                state.metadataSearchResults = it
                state.metadataSearchMessage = if (it.isEmpty()) "没有搜索结果" else "搜索到 ${it.size} 首"
            }.onFailure {
                state.metadataSearchMessage = it.message ?: it::class.simpleName.orEmpty()
                onError(it)
            }
            setLoading(false)
        }
    }

    fun applyProviderMetadata(track: MusicTrack, providerTrack: MusicTrack) {
        saveMetadata(track, providerTrack.title, providerTrack.artists, providerTrack.album)
    }

    fun downloadLyrics(track: MusicTrack, providerTrack: MusicTrack) {
        scope.launch {
            setLoading(true)
            setMessage("正在下载歌词")
            runCatching {
                withTimeout(25_000) {
                    providerRepository.lyrics(
                        providerTrack.copy(providerId = providerTrack.providerId ?: providerTrack.id),
                    )
                }
            }.onSuccess { lyricsText ->
                val lyrics = lyricsText?.takeIf { it.isNotBlank() }
                if (lyrics == null) {
                    setMessage("未获取到歌词")
                    state.metadataSearchMessage = "未获取到歌词"
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
                        setMessage("已保存歌词：${track.title}")
                        state.metadataSearchMessage = "歌词已保存"
                    }.onFailure(onError)
                }
            }.onFailure {
                state.metadataSearchMessage = it.message ?: it::class.simpleName.orEmpty()
                onError(it)
            }
            setLoading(false)
        }
    }

    fun refreshDirectories() {
        scope.launch {
            runCatching {
                updateScanSettings()
                repository.directories()
            }.onSuccess {
                state.directories = it
                if (
                    state.selectedDirectoryId != null &&
                    it.none { directory -> directory.id == state.selectedDirectoryId }
                ) {
                    closeCollection()
                }
            }.onFailure(onError)
        }
    }

    suspend fun updateScanSettings() {
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
            setLoading(true)
            setMessage("正在更新本地音乐筛选")
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
                        setMessage(if (tracks.isEmpty()) "未发现本地音乐" else "本地音乐 ${tracks.size} 首")
                    }
                    .onFailure(onError)
            }
            setLoading(false)
        }
    }
}
