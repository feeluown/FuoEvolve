package org.feeluown.mobile

internal fun createPlaybackUiGraph(
    navigation: PlaybackNavigationPort,
    presentation: PlaybackPresentationPort,
    queue: PlaybackQueueUiPort,
    sleepTimer: PlaybackSleepTimerPort,
    downloads: DownloadActionPort,
    playlists: PlaylistActionPort,
    providerTrackActions: ProviderTrackActionPort,
    localMusicActions: LocalMusicActionPort,
    lyrics: PlaybackLyricsPort,
    replacement: ReplacementActionPort,
): PlaybackUiGraph = PlaybackUiGraph(
    navigation = navigation,
    presentation = presentation,
    queue = queue,
    sleepTimer = sleepTimer,
    downloads = downloads,
    playlists = playlists,
    providerTrackActions = providerTrackActions,
    localMusicActions = localMusicActions,
    lyrics = lyrics,
    replacement = replacement,
)

internal fun createProviderDetailUiGraph(
    owners: ProviderDetailOwners,
    playbackQueue: PlaybackQueueUiPort,
    downloads: DownloadActionPort,
    playlists: PlaylistActionPort,
    providerTrackActions: ProviderTrackActionPort,
): ProviderDetailUiGraph = ProviderDetailUiGraph(
    owners = owners,
    playbackQueue = playbackQueue,
    downloads = downloads,
    playlists = playlists,
    providerTrackActions = providerTrackActions,
)

internal fun createHomeUiGraph(
    home: HomeFeatureController,
    providerCatalog: ProviderCatalogFeatureController,
    playbackQueue: PlaybackQueueUiPort,
    downloads: DownloadActionPort,
    playlists: PlaylistActionPort,
    providerTrackActions: ProviderTrackActionPort,
    localPlaylist: LocalPlaylistFeatureController,
    localMusic: LocalMusicFeatureController,
): HomeFeatureUiGraph {
    val listeningHistory = ListeningHistoryPlaylistMetadataRepository(
        delegate = playbackQueue.listeningHistoryRepository ?: NoOpListeningHistoryRepository,
        home = home,
    )
    return HomeFeatureUiGraph(
        home = home,
        providerCatalog = providerCatalog,
        playbackQueue = playbackQueue,
        listeningHistory = listeningHistory,
        downloads = downloads,
        playlists = playlists,
        providerTrackActions = providerTrackActions,
        localPlaylist = localPlaylist,
        localMusic = localMusic,
    )
}
