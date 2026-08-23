package org.feeluown.mobile

/**
 * Application compatibility aggregate retained while callers finish migrating to narrow provider
 * capabilities. Provider-neutral contracts live in :provider:api; only app playback policy coupling
 * remains application-owned here.
 */
interface ProviderMusicRepository :
    ProviderRegistryRepository,
    ProviderSearchRepository,
    ProviderAuthRepository,
    ProviderCatalogRepository,
    ProviderLibraryRepository,
    ProviderPlaybackRepository {
    suspend fun updateAudioQualityPolicies(
        wifiPolicy: AudioQualityPolicy,
        cellularPolicy: AudioQualityPolicy,
    )
}
