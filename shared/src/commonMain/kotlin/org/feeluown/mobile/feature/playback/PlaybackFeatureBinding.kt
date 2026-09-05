package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    listeningHistorySink: ListeningHistorySink,
    providerPlaybackReporting: ProviderPlaybackReportingRepository? = null,
    nowMillis: () -> Long = ::currentTimeMillis,
): PlaybackFeatureOwner {
    val owner = createPlaybackFeatureOwner(
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
    val effectiveHistorySink = providerPlaybackReporting?.let { reporting ->
        ProviderPlaybackReportingSink(
            delegate = listeningHistorySink,
            reporting = reporting,
            settingsRepository = settingsRepository,
            scope = scope,
        )
    } ?: listeningHistorySink
    val listeningHistoryRecorder = ListeningHistoryRecorder(
        sink = effectiveHistorySink,
        scope = scope,
        nowMillis = nowMillis,
    )
    scope.launch {
        owner.playbackState.collect { state ->
            listeningHistoryRecorder.onPlaybackState(
                state = state,
                queueState = owner.transport.queueStateFlow?.value,
            )
        }
    }
    val listeningHistoryRepository = (listeningHistorySink as? ListeningHistoryRepository)?.let { repository ->
        LegacyPlaylistStatsMigratingRepository(
            delegate = repository,
            settingsRepository = settingsRepository,
            scope = scope,
        )
    }
    val wrappedTransport = ListeningHistoryPlaybackTransport(
        delegate = owner.transport,
        repository = listeningHistoryRepository,
    )
    return object : PlaybackFeatureOwner by owner {
        override val transport: PlaybackTransportCoordinator = wrappedTransport
    }
}

/**
 * Composition-only decorator: write ownership stays in playback while read access and rich playback
 * context adaptation are exposed without a mutable global service locator or persistence dependency.
 */
private class ListeningHistoryPlaybackTransport(
    private val delegate: PlaybackTransportCoordinator,
    private val repository: ListeningHistoryRepository?,
) : PlaybackTransportCoordinator by delegate {
    private var contextHint: PlaybackContextSnapshot? = null

    override val listeningHistoryRepository: ListeningHistoryRepository?
        get() = repository

    override fun setPlaybackContextHint(context: PlaybackContextSnapshot?) {
        contextHint = context
    }

    override fun playTracks(tracks: List<MusicTrack>, index: Int) {
        val context = contextHint
        if (context != null && context.type != PlaybackContextType.Playlist) {
            delegate.playTracks(tracks, index, context)
        } else {
            delegate.playTracks(tracks, index)
        }
    }

    override fun playPlaylistTracks(tracks: List<MusicTrack>, index: Int, sourcePlaylistId: String) {
        val context = contextHint?.takeIf {
            it.type == PlaybackContextType.Playlist && it.resourceId == sourcePlaylistId
        }
        if (context != null) {
            delegate.playPlaylistTracks(tracks, index, sourcePlaylistId, context)
        } else {
            delegate.playPlaylistTracks(tracks, index, sourcePlaylistId)
        }
    }

    override fun playAllPlaylistTracks(tracks: List<MusicTrack>, sourcePlaylistId: String) {
        val context = contextHint?.takeIf {
            it.type == PlaybackContextType.Playlist && it.resourceId == sourcePlaylistId
        }
        if (context != null) {
            delegate.playAllPlaylistTracks(tracks, sourcePlaylistId, context)
        } else {
            delegate.playAllPlaylistTracks(tracks, sourcePlaylistId)
        }
    }
}

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
