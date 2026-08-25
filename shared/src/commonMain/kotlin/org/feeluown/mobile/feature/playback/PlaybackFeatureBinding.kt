package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.feeluown.mobile.provider.core.network.currentTimeMillis

/** App-layer construction of the playback provider policy from narrow provider capabilities. */
fun createAppPlaybackProviderPort(
    providerRegistry: ProviderRegistryRepository,
    providerSearch: ProviderSearchRepository,
    providerCatalog: ProviderCatalogRepository,
    providerPlaybackSource: PlaybackProviderSourcePort,
): PlaybackProviderPort = createPlaybackProviderPort(
    registry = providerRegistry,
    search = providerSearch,
    catalog = providerCatalog,
    source = providerPlaybackSource,
    failureMessage = { throwable, fallback, providerId ->
        throwable.providerFailureOrNull(providerId)?.userMessage
            ?: throwable.message
            ?: throwable::class.simpleName.orEmpty().ifBlank { fallback }
    },
)

/** Application binding kept in :shared while playback business ownership lives in :feature:playback. */
fun createPlaybackFeatureOwner(
    playbackProvider: PlaybackProviderPort,
    playbackEngine: PlaybackEngine,
    playbackQueueStore: PlaybackQueueStore,
    settingsRepository: AppSettingsRepository,
    downloadActions: DownloadActionPort,
    scope: CoroutineScope,
    openTrackDetail: (MusicTrack) -> Unit,
    nowMillis: () -> Long = ::currentTimeMillis,
): PlaybackFeatureOwner = createPlaybackFeatureOwner(
    providerRepository = playbackProvider,
    playbackEngine = playbackEngine,
    playbackQueueStore = playbackQueueStore,
    settings = AppPlaybackSettingsPort(settingsRepository, scope),
    downloads = PlaybackDownloadPort { trackId ->
        (downloadActions.downloadStates[trackId] as? DownloadState.Downloaded)?.uri
    },
    navigation = DefaultPlaybackNavigationPort(),
    scope = scope,
    openTrackDetail = openTrackDetail,
    nowMillis = nowMillis,
)

private class AppPlaybackSettingsPort(
    private val delegate: AppSettingsRepository,
    scope: CoroutineScope,
) : PlaybackSettingsPort {
    override val state: StateFlow<PlaybackFeatureSettings> = delegate.state
        .map { it.settings.toPlaybackFeatureSettings() }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = delegate.state.value.settings.toPlaybackFeatureSettings(),
        )

    override suspend fun awaitSettings(): PlaybackFeatureSettings = delegate.awaitSettings().toPlaybackFeatureSettings()

    override suspend fun storeSmartReplacementSelections(value: Map<String, SmartReplacementSelection>) {
        delegate.update { current -> current.copy(smartReplacementSelections = value) }
    }

    override suspend fun storeLyricsAssociations(value: Map<String, String>) {
        delegate.update { current -> current.copy(lyricsAssociations = value) }
    }

    override suspend fun storeLyricsAlignmentOffsetsMs(value: Map<String, Long>) {
        delegate.update { current -> current.copy(lyricsAlignmentOffsetsMs = value) }
    }
}

private fun AppSettings.toPlaybackFeatureSettings(): PlaybackFeatureSettings = PlaybackFeatureSettings(
    enabledProviderIds = enabledProviderIds.ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS },
    unavailablePlaybackPolicy = unavailablePlaybackPolicy,
    smartReplacementProviderIds = smartReplacementProviderIds,
    smartReplacementMinScore = smartReplacementMinScore,
    smartReplacementSelections = smartReplacementSelections,
    lyricsAssociations = lyricsAssociations,
    lyricsAlignmentOffsetsMs = lyricsAlignmentOffsetsMs,
)
