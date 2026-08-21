package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope

fun createProviderTrackActionPort(
    providerRepository: ProviderMusicRepository,
    providerCatalog: ProviderCatalogFeatureController,
    providerDetails: ProviderDetailOwners,
    searchController: SearchFeatureController,
    playbackNavigation: PlaybackNavigationPort,
    playbackQueue: PlaybackQueueUiPort,
    scope: CoroutineScope,
    refreshMineContent: () -> Unit = {},
): ProviderTrackActionPort = ProviderTrackActionController(
    providerRepository = providerRepository,
    scope = scope,
    navigation = playbackNavigation,
    providerCapabilities = { providerCatalog.uiState.value.capabilities },
    isProviderLoggedIn = { providerId ->
        providerCatalog.uiState.value.sessions.authStates[providerId]?.isLoggedIn == true
    },
    openMediaItem = providerDetails.mediaItem::open,
    openTrackDetail = providerDetails.track::open,
    searchTrackText = searchController::searchText,
    removeDislikedTrack = { track ->
        playbackQueue.removeFromQueue(track)
        refreshMineContent()
    },
    refreshMineContent = refreshMineContent,
    setLoading = {},
    setMessage = {},
    onError = {},
)
