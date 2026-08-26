package org.feeluown.mobile

import org.feeluown.mobile.playback.api.PlaybackSession

/** App-shell composition wiring. This graph owns no business state or lifecycle policy. */
data class AppUiGraph(
    val playbackSession: PlaybackSession,
    val playback: PlaybackUiGraph,
    val providerDetail: ProviderDetailUiGraph,
    val home: HomeFeatureUiGraph,
    val search: SearchRouteGraph,
    val recognition: RecognitionRouteGraph,
    val debugLogs: DebugLogFeatureController,
    val providerCatalog: ProviderCatalogFeatureController,
    val providerAuth: ProviderAuthFeatureController,
    val settings: SettingsFeatureController,
    val onboarding: OnboardingFeatureController?,
    val localMusic: LocalMusicFeatureController,
    val localPlaylist: LocalPlaylistFeatureController,
    val sharedResources: SharedResourceActionPort,
)

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
    return AppUiGraph(
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
