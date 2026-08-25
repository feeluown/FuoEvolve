package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope

fun createProviderTrackActionPort(
    providerCatalogRepository: ProviderCatalogRepository,
    providerLibrary: ProviderLibraryRepository,
    providerCatalog: ProviderCatalogFeatureController,
    providerDetails: ProviderDetailOwners,
    searchController: SearchFeatureController,
    playbackNavigation: PlaybackNavigationPort,
    playbackQueue: PlaybackQueueUiPort,
    scope: CoroutineScope,
    refreshMineContent: () -> Unit = {},
): ProviderTrackActionPort = ProviderTrackActionController(
    providerCatalogRepository = providerCatalogRepository,
    providerLibrary = providerLibrary,
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
