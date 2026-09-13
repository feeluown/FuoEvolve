package org.feeluown.mobile

import org.feeluown.mobile.playback.api.PlaybackSession

/** App-shell composition wiring. This graph owns no business state or lifecycle policy. */
interface AppUiGraph {
    val playbackSession: PlaybackSession
    val playback: PlaybackUiGraph
    val providerDetail: ProviderDetailUiGraph
    val home: HomeFeatureUiGraph
    val search: SearchRouteGraph
    val recognition: RecognitionRouteGraph
    val debugLogs: DebugLogFeatureController
    val providerCatalog: ProviderCatalogFeatureController
    val providerAuth: ProviderAuthFeatureController
    val settings: SettingsFeatureController
    val onboarding: OnboardingFeatureController?
    val localMusic: LocalMusicFeatureController
    val localPlaylist: LocalPlaylistFeatureController
    val sharedResources: SharedResourceActionPort
}

data class SearchRouteGraph(
    val controller: SearchFeatureController,
    val appPort: SearchAppPort,
)

data class RecognitionRouteGraph(
    val controller: RecognitionFeatureController,
    val appPort: RecognitionAppPort,
)

fun createAppUiGraph(
    playbackSession: PlaybackSession,
    playbackNavigationPort: PlaybackNavigationPort,
    playbackPresentationPort: PlaybackPresentationPort,
    playbackQueueUiPort: PlaybackQueueUiPort,
    playbackSleepTimerPort: PlaybackSleepTimerPort,
    downloadActionPort: DownloadActionPort,
    playlistActionPort: PlaylistActionPort,
    providerTrackActionPort: ProviderTrackActionPort,
    localMusicActionPort: LocalMusicActionPort,
    playbackLyricsPort: PlaybackLyricsPort,
    replacementActionPort: ReplacementActionPort,
    debugLogFeatureController: DebugLogFeatureController,
    providerCatalogFeatureController: ProviderCatalogFeatureController,
    providerAuthFeatureController: ProviderAuthFeatureController,
    settingsFeatureController: SettingsFeatureController,
    onboardingFeatureController: OnboardingFeatureController?,
    providerDetailOwners: ProviderDetailOwners,
    localMusicFeatureController: LocalMusicFeatureController,
    localPlaylistFeatureController: LocalPlaylistFeatureController,
    homeFeatureController: HomeFeatureController,
    sharedResourceActionPort: SharedResourceActionPort,
    searchController: SearchFeatureController,
    searchAppPort: SearchAppPort,
    recognitionController: RecognitionFeatureController,
    recognitionAppPort: RecognitionAppPort,
): AppUiGraph {
    val playback = PlaybackUiGraph(
        navigation = playbackNavigationPort,
        presentation = playbackPresentationPort,
        queue = playbackQueueUiPort,
        sleepTimer = playbackSleepTimerPort,
        downloads = downloadActionPort,
        playlists = playlistActionPort,
        providerTrackActions = providerTrackActionPort,
        localMusicActions = localMusicActionPort,
        lyrics = playbackLyricsPort,
        replacement = replacementActionPort,
    )
    val listeningHistory = ListeningHistoryPlaylistMetadataRepository(
        delegate = playbackQueueUiPort.listeningHistoryRepository ?: NoOpListeningHistoryRepository,
        home = homeFeatureController,
    )
    return EagerAppUiGraph(
        playbackSession = playbackSession,
        playback = playback,
        providerDetail = ProviderDetailUiGraph(
            owners = providerDetailOwners,
            playbackQueue = playbackQueueUiPort,
            downloads = downloadActionPort,
            playlists = playlistActionPort,
            providerTrackActions = providerTrackActionPort,
        ),
        home = HomeFeatureUiGraph(
            home = homeFeatureController,
            providerCatalog = providerCatalogFeatureController,
            playbackQueue = playbackQueueUiPort,
            listeningHistory = listeningHistory,
            downloads = downloadActionPort,
            playlists = playlistActionPort,
            providerTrackActions = providerTrackActionPort,
            localPlaylist = localPlaylistFeatureController,
            localMusic = localMusicFeatureController,
        ),
        search = SearchRouteGraph(searchController, searchAppPort),
        recognition = RecognitionRouteGraph(recognitionController, recognitionAppPort),
        debugLogs = debugLogFeatureController,
        providerCatalog = providerCatalogFeatureController,
        providerAuth = providerAuthFeatureController,
        settings = settingsFeatureController,
        onboarding = onboardingFeatureController,
        localMusic = localMusicFeatureController,
        localPlaylist = localPlaylistFeatureController,
        sharedResources = sharedResourceActionPort,
    )
}

/**
 * Creates a graph whose feature owners are resolved only when the corresponding UI surface reads
 * them. Desktop uses this to keep onboarding and the initial loading screen small.
 */
internal fun createLazyAppUiGraph(
    playbackSession: () -> PlaybackSession,
    playback: () -> PlaybackUiGraph,
    providerDetail: () -> ProviderDetailUiGraph,
    home: () -> HomeFeatureUiGraph,
    search: () -> SearchRouteGraph,
    recognition: () -> RecognitionRouteGraph,
    debugLogs: () -> DebugLogFeatureController,
    providerCatalog: () -> ProviderCatalogFeatureController,
    providerAuth: () -> ProviderAuthFeatureController,
    settings: () -> SettingsFeatureController,
    onboarding: () -> OnboardingFeatureController?,
    localMusic: () -> LocalMusicFeatureController,
    localPlaylist: () -> LocalPlaylistFeatureController,
    sharedResources: () -> SharedResourceActionPort,
): AppUiGraph = LazyAppUiGraph(
    playbackSession = playbackSession,
    playback = playback,
    providerDetail = providerDetail,
    home = home,
    search = search,
    recognition = recognition,
    debugLogs = debugLogs,
    providerCatalog = providerCatalog,
    providerAuth = providerAuth,
    settings = settings,
    onboarding = onboarding,
    localMusic = localMusic,
    localPlaylist = localPlaylist,
    sharedResources = sharedResources,
)

private data class EagerAppUiGraph(
    override val playbackSession: PlaybackSession,
    override val playback: PlaybackUiGraph,
    override val providerDetail: ProviderDetailUiGraph,
    override val home: HomeFeatureUiGraph,
    override val search: SearchRouteGraph,
    override val recognition: RecognitionRouteGraph,
    override val debugLogs: DebugLogFeatureController,
    override val providerCatalog: ProviderCatalogFeatureController,
    override val providerAuth: ProviderAuthFeatureController,
    override val settings: SettingsFeatureController,
    override val onboarding: OnboardingFeatureController?,
    override val localMusic: LocalMusicFeatureController,
    override val localPlaylist: LocalPlaylistFeatureController,
    override val sharedResources: SharedResourceActionPort,
) : AppUiGraph

private class LazyAppUiGraph(
    playbackSession: () -> PlaybackSession,
    playback: () -> PlaybackUiGraph,
    providerDetail: () -> ProviderDetailUiGraph,
    home: () -> HomeFeatureUiGraph,
    search: () -> SearchRouteGraph,
    recognition: () -> RecognitionRouteGraph,
    debugLogs: () -> DebugLogFeatureController,
    providerCatalog: () -> ProviderCatalogFeatureController,
    providerAuth: () -> ProviderAuthFeatureController,
    settings: () -> SettingsFeatureController,
    onboarding: () -> OnboardingFeatureController?,
    localMusic: () -> LocalMusicFeatureController,
    localPlaylist: () -> LocalPlaylistFeatureController,
    sharedResources: () -> SharedResourceActionPort,
) : AppUiGraph {
    override val playbackSession: PlaybackSession by lazy(playbackSession)
    override val playback: PlaybackUiGraph by lazy(playback)
    override val providerDetail: ProviderDetailUiGraph by lazy(providerDetail)
    override val home: HomeFeatureUiGraph by lazy(home)
    override val search: SearchRouteGraph by lazy(search)
    override val recognition: RecognitionRouteGraph by lazy(recognition)
    override val debugLogs: DebugLogFeatureController by lazy(debugLogs)
    override val providerCatalog: ProviderCatalogFeatureController by lazy(providerCatalog)
    override val providerAuth: ProviderAuthFeatureController by lazy(providerAuth)
    override val settings: SettingsFeatureController by lazy(settings)
    override val onboarding: OnboardingFeatureController? by lazy(onboarding)
    override val localMusic: LocalMusicFeatureController by lazy(localMusic)
    override val localPlaylist: LocalPlaylistFeatureController by lazy(localPlaylist)
    override val sharedResources: SharedResourceActionPort by lazy(sharedResources)
}
