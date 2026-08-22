package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

typealias LocalMusicFeatureController = LocalMusicFeatureOwner<
    MusicTrack,
    ProviderInfo,
    LocalMusicDirectory,
    LocalMusicViewMode,
    LocalMusicCollectionSelection
>
typealias LocalMusicUiState = LocalMusicFeatureState<
    MusicTrack,
    ProviderInfo,
    LocalMusicDirectory,
    LocalMusicViewMode,
    LocalMusicCollectionSelection
>

@Suppress("FunctionName")
fun LocalMusicUiState(
    tracks: List<MusicTrack> = emptyList(),
    viewMode: LocalMusicViewMode = LocalMusicViewMode.All,
    directories: List<LocalMusicDirectory> = emptyList(),
    selectedDirectoryId: String? = null,
    selectedCollection: LocalMusicCollectionSelection? = null,
    excludedDirectoryIds: Set<String> = emptySet(),
    minDurationSeconds: Int = DEFAULT_LOCAL_MUSIC_MIN_DURATION_SECONDS,
    metadataEditorTrack: MusicTrack? = null,
    metadataProviders: List<ProviderInfo> = emptyList(),
    selectedMetadataProviderId: String? = null,
    metadataSearchResults: List<MusicTrack> = emptyList(),
    metadataSearchMessage: String? = null,
    isLoading: Boolean = false,
    message: String? = null,
    errorMessage: String? = null,
): LocalMusicUiState = LocalMusicFeatureState(
    tracks = tracks,
    viewMode = viewMode,
    directories = directories,
    selectedDirectoryId = selectedDirectoryId,
    selectedCollection = selectedCollection,
    excludedDirectoryIds = excludedDirectoryIds,
    minDurationSeconds = minDurationSeconds,
    metadataEditorTrack = metadataEditorTrack,
    metadataProviders = metadataProviders,
    selectedMetadataProviderId = selectedMetadataProviderId,
    metadataSearchResults = metadataSearchResults,
    metadataSearchMessage = metadataSearchMessage,
    isLoading = isLoading,
    message = message,
    errorMessage = errorMessage,
)

fun createLocalMusicFeatureController(
    repository: LocalMusicRepository,
    providerRepository: ProviderMusicRepository,
    navigator: AppNavigator,
    settingsRepository: AppSettingsRepository,
    providers: () -> List<ProviderInfo>,
    isLocalMusicSectionActive: () -> Boolean,
    scope: CoroutineScope,
    onTrackUpdated: (String, MusicTrack) -> Unit = { _, _ -> },
): LocalMusicFeatureController {
    val providerSearchRepository = ProviderSearchRepositoryView(providerRepository)
    val providerPlaybackRepository = ProviderPlaybackRepositoryView(providerRepository)

    val featureRepository = object : LocalMusicRepositoryPort<MusicTrack, LocalMusicDirectory> {
        override val mediaChangeEvents = repository.mediaChangeEvents

        override suspend fun updateScanSettings(excludedDirectoryIds: Set<String>, minDurationSeconds: Int) {
            repository.updateScanSettings(
                LocalMusicScanSettings(
                    excludedDirectoryIds = excludedDirectoryIds,
                    minDurationSeconds = minDurationSeconds,
                ),
            )
        }

        override suspend fun isDatabaseReady(): Boolean = repository.isDatabaseReady()
        override suspend fun isDatabaseStale(): Boolean = repository.isDatabaseStale()
        override suspend fun refreshDatabase(): List<MusicTrack> = repository.refreshDatabase()
        override suspend fun tracks(): List<MusicTrack> = repository.tracks()
        override suspend fun directories(): List<LocalMusicDirectory> = repository.directories()

        override suspend fun updateMetadata(
            track: MusicTrack,
            title: String,
            artists: String,
            album: String,
        ) {
            repository.updateMetadata(track, LocalTrackMetadata(title = title, artists = artists, album = album))
        }

        override suspend fun saveLyrics(track: MusicTrack, lyrics: String) {
            repository.saveLyrics(track, lyrics)
        }
    }

    val providerPort = object : LocalMusicProviderPort<MusicTrack> {
        override suspend fun search(keyword: String, providerId: String): List<MusicTrack> =
            providerSearchRepository.search(keyword, providerId)

        override suspend fun lyrics(track: MusicTrack): String? = providerPlaybackRepository.lyrics(track)
    }

    val operations = object : LocalMusicFeatureOperations<
        MusicTrack,
        ProviderInfo,
        LocalMusicDirectory,
        LocalMusicViewMode,
        LocalMusicCollectionSelection
    > {
        override fun trackId(track: MusicTrack): String = track.id
        override fun trackTitle(track: MusicTrack): String = track.title
        override fun trackArtists(track: MusicTrack): String = track.artists
        override fun trackAlbum(track: MusicTrack): String = track.album
        override fun isProviderTrack(track: MusicTrack): Boolean = track.sourceType == TrackSourceType.Provider
        override fun providerTrackId(track: MusicTrack): String? = track.providerId ?: track.id
        override fun withProviderTrackId(track: MusicTrack, providerTrackId: String): MusicTrack =
            track.copy(providerId = providerTrackId)
        override fun withMetadata(
            track: MusicTrack,
            title: String,
            artists: String,
            album: String,
        ): MusicTrack = track.copy(title = title, artists = artists, album = album)
        override fun withLyrics(track: MusicTrack, lyrics: String): MusicTrack = track.copy(lyrics = lyrics)
        override fun providerId(provider: ProviderInfo): String = provider.providerId
        override fun directoryId(directory: LocalMusicDirectory): String = directory.id
        override fun defaultViewMode(): LocalMusicViewMode = LocalMusicViewMode.All
        override fun isAllViewMode(viewMode: LocalMusicViewMode): Boolean = viewMode == LocalMusicViewMode.All
        override fun collection(viewMode: LocalMusicViewMode, key: String): LocalMusicCollectionSelection =
            LocalMusicCollectionSelection(viewMode, key)
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
                        excludedLocalMusicDirectoryIds = excludedDirectoryIds
                            .mapNotNull(::canonicalLocalMusicDirectoryId)
                            .toSet(),
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
        owner.restore(
            viewMode = settings.localMusicViewMode,
            excludedDirectoryIds = settings.excludedLocalMusicDirectoryIds,
            minDurationSeconds = settings.localMusicMinDurationSeconds,
        )
        owner.updateScanSettings()
    }
    return owner
}

fun LocalMusicFeatureController.asLocalMusicActionPort(): LocalMusicActionPort = object : LocalMusicActionPort {
    override fun openLocalMetadataEditor(track: MusicTrack) {
        this@asLocalMusicActionPort.openLocalMetadataEditor(track)
    }
}
