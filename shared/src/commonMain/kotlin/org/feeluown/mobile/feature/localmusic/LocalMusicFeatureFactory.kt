package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

typealias CoreLocalMusicFeatureController = LocalMusicFeatureOwner<
    MusicTrack,
    ProviderInfo,
    LocalMusicDirectory,
    LocalMusicViewMode,
    LocalMusicCollectionSelection
>

interface LocalMusicFeatureController : CoreLocalMusicFeatureController, LocalMusicActionPort

typealias LocalMusicUiState = LocalMusicFeatureState<
    MusicTrack,
    ProviderInfo,
    LocalMusicDirectory,
    LocalMusicViewMode,
    LocalMusicCollectionSelection
>

fun createLocalMusicFeatureController(
    repository: LocalMusicRepository,
    providerSearch: ProviderSearchRepository,
    providerPlaybackSource: PlaybackProviderSourcePort,
    navigator: AppNavigator,
    settingsRepository: AppSettingsRepository,
    providers: () -> List<ProviderInfo>,
    isLocalMusicSectionActive: () -> Boolean,
    scope: CoroutineScope,
    onTrackUpdated: (String, MusicTrack) -> Unit = { _, _ -> },
): LocalMusicFeatureController {
    val includedDirectoryIds = MutableStateFlow<Set<String>>(emptySet())
    val directoryPolicy = repository.directoryPolicy
    val featureRepository = object : LocalMusicRepositoryPort<MusicTrack, LocalMusicDirectory> {
        override val mediaChangeEvents = repository.mediaChangeEvents
        override suspend fun updateScanSettings(excludedDirectoryIds: Set<String>, minDurationSeconds: Int) {
            repository.updateScanSettings(
                LocalMusicScanSettings(
                    excludedDirectoryIds = excludedDirectoryIds,
                    minDurationSeconds = minDurationSeconds,
                    includedDirectoryIds = includedDirectoryIds.value,
                    directoryPolicy = directoryPolicy,
                ),
            )
        }
        override suspend fun isDatabaseReady(): Boolean = repository.isDatabaseReady()
        override suspend fun isDatabaseStale(): Boolean = repository.isDatabaseStale()
        override suspend fun refreshDatabase(): List<MusicTrack> = repository.refreshDatabase()
        override suspend fun tracks(): List<MusicTrack> = repository.tracks()
        override suspend fun directories(): List<LocalMusicDirectory> = repository.directories()
        override suspend fun updateMetadata(track: MusicTrack, title: String, artists: String, album: String) {
            repository.updateMetadata(track, LocalTrackMetadata(title, artists, album))
        }
        override suspend fun saveLyrics(track: MusicTrack, lyrics: String) { repository.saveLyrics(track, lyrics) }
    }

    val providerPort = object : LocalMusicProviderPort<MusicTrack> {
        override suspend fun search(keyword: String, providerId: String): List<MusicTrack> =
            providerSearch.search(keyword, providerId)
        override suspend fun lyrics(track: MusicTrack): String? = providerPlaybackSource.lyrics(track)
    }

    val operations = object : LocalMusicFeatureOperations<MusicTrack, ProviderInfo, LocalMusicDirectory, LocalMusicViewMode, LocalMusicCollectionSelection> {
        override fun trackId(track: MusicTrack) = track.id
        override fun trackTitle(track: MusicTrack) = track.title
        override fun trackArtists(track: MusicTrack) = track.artists
        override fun trackAlbum(track: MusicTrack) = track.album
        override fun isProviderTrack(track: MusicTrack) = track.sourceType == TrackSourceType.Provider
        override fun providerTrackId(track: MusicTrack) = track.providerId ?: track.id
        override fun withProviderTrackId(track: MusicTrack, providerTrackId: String) = track.copy(providerId = providerTrackId)
        override fun withMetadata(track: MusicTrack, title: String, artists: String, album: String) =
            track.copy(title = title, artists = artists, album = album)
        override fun withLyrics(track: MusicTrack, lyrics: String) = track.copy(lyrics = lyrics)
        override fun providerId(provider: ProviderInfo) = provider.providerId
        override fun directoryId(directory: LocalMusicDirectory) = directory.id
        override fun defaultViewMode() = LocalMusicViewMode.All
        override fun isAllViewMode(viewMode: LocalMusicViewMode) = viewMode == LocalMusicViewMode.All
        override fun collection(viewMode: LocalMusicViewMode, key: String) = LocalMusicCollectionSelection(viewMode, key)
    }

    val owner = createLocalMusicFeatureOwner(
        repository = featureRepository,
        providerRepository = providerPort,
        operations = operations,
        providers = providers,
        selectedSearchProviderId = { settingsRepository.state.value.settings.selectedSearchProviderId },
        isLocalMusicSectionActive = isLocalMusicSectionActive,
        scope = scope,
        openCollectionRoute = { navigator.navigate(AppRoute.LocalMusicCollection) },
        closeCollectionRoute = { navigator.pop(AppRoute.LocalMusicCollection) },
        persistSettings = { viewMode, excludedDirectoryIds, minDurationSeconds ->
            scope.launch {
                settingsRepository.update { current ->
                    current.copy(
                        localMusicViewMode = viewMode,
                        excludedLocalMusicDirectoryIds = excludedDirectoryIds.mapNotNull(::canonicalLocalMusicDirectoryId).toSet(),
                        includedLocalMusicDirectoryIds = includedDirectoryIds.value,
                        localMusicMinDurationSeconds = minDurationSeconds,
                    )
                }
            }
        },
        failureMessage = { throwable, providerId ->
            throwable.providerFailureOrNull(providerId?.substringBefore(":"))?.userMessage
                ?: throwable.message
                ?: throwable::class.simpleName.orEmpty().ifBlank { "操作失败" }
        },
        onTrackUpdated = onTrackUpdated,
    )

    scope.launch {
        val settings = settingsRepository.awaitSettings()
        includedDirectoryIds.value = settings.includedLocalMusicDirectoryIds
            .mapNotNull(::canonicalLocalMusicDirectoryId)
            .toSet()
        val defaultExclusions = settings.excludedLocalMusicDirectoryIds
            .mapNotNull(::canonicalLocalMusicDirectoryId)
            .filter { directoryId -> isDefaultDirectoryEnabled(directoryId, directoryPolicy) }
            .toSet()
        owner.restore(
            viewMode = settings.localMusicViewMode,
            excludedDirectoryIds = defaultExclusions,
            minDurationSeconds = settings.localMusicMinDurationSeconds,
        )
        owner.updateScanSettings()
    }
    return BoundLocalMusicFeatureController(
        delegate = owner,
        includedDirectoryIds = includedDirectoryIds,
        directoryPolicy = directoryPolicy,
        settingsRepository = settingsRepository,
        scope = scope,
    )
}

private class BoundLocalMusicFeatureController(
    private val delegate: CoreLocalMusicFeatureController,
    private val includedDirectoryIds: MutableStateFlow<Set<String>>,
    private val directoryPolicy: LocalMusicDirectoryPolicy,
    private val settingsRepository: AppSettingsRepository,
    private val scope: CoroutineScope,
) : LocalMusicFeatureController, CoreLocalMusicFeatureController by delegate {
    override val uiState: StateFlow<LocalMusicUiState> = combine(
        delegate.uiState,
        includedDirectoryIds,
    ) { state, included ->
        state.withEffectiveDirectoryExclusions(included, directoryPolicy)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = delegate.uiState.value.withEffectiveDirectoryExclusions(
            includedDirectoryIds.value,
            directoryPolicy,
        ),
    )

    override fun openDirectory(directoryId: String) {
        if (isLocalMusicDirectoryExcluded(directoryId, uiState.value.excludedDirectoryIds)) return
        delegate.openDirectory(directoryId)
    }

    override fun openCollection(mode: LocalMusicViewMode, key: String) {
        val directory = uiState.value.directories.firstOrNull { it.id == key }
        if (directory != null && isLocalMusicDirectoryExcluded(directory.id, uiState.value.excludedDirectoryIds)) return
        delegate.openCollection(mode, key)
    }

    override fun onDirectoryEnabledChange(directoryId: String, enabled: Boolean) {
        if (isDefaultDirectoryEnabled(directoryId, directoryPolicy)) {
            delegate.onDirectoryEnabledChange(directoryId, enabled)
            return
        }
        val canonical = canonicalLocalMusicDirectoryId(directoryId) ?: return
        val current = includedDirectoryIds.value.mapNotNull(::canonicalLocalMusicDirectoryId).toSet()
        val updated = if (enabled) current + canonical else current - canonical
        if (updated == current) return
        includedDirectoryIds.value = updated
        scope.launch {
            settingsRepository.update { settings ->
                settings.copy(includedLocalMusicDirectoryIds = updated)
            }
        }
        delegate.refresh(forceRefresh = false, showLoading = false)
    }

    override fun openLocalMetadataEditor(track: MusicTrack) = delegate.openLocalMetadataEditor(track)
}

private fun isDefaultDirectoryEnabled(
    directoryId: String,
    directoryPolicy: LocalMusicDirectoryPolicy,
): Boolean = isLocalMusicDirectoryEnabled(
    directoryId,
    LocalMusicScanSettings(directoryPolicy = directoryPolicy),
)

private fun LocalMusicUiState.withEffectiveDirectoryExclusions(
    includedDirectoryIds: Set<String>,
    directoryPolicy: LocalMusicDirectoryPolicy,
): LocalMusicUiState {
    val baseExcluded = excludedDirectoryIds.mapNotNull(::canonicalLocalMusicDirectoryId).toSet()
    val scanSettings = LocalMusicScanSettings(
        excludedDirectoryIds = baseExcluded,
        includedDirectoryIds = includedDirectoryIds,
        directoryPolicy = directoryPolicy,
    )
    val effectiveExcluded = buildSet {
        addAll(baseExcluded)
        directories.forEach { directory ->
            if (!isLocalMusicDirectoryEnabled(directory.id, scanSettings)) {
                add(canonicalLocalMusicDirectoryId(directory.id) ?: directory.id)
            }
        }
    }
    return copy(excludedDirectoryIds = effectiveExcluded)
}
