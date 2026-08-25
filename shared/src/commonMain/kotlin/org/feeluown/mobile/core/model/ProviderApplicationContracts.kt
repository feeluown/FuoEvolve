package org.feeluown.mobile

/** Content-domain provider surface: detail/catalog reads plus library mutations only. */
interface ProviderContentRepository : ProviderCatalogRepository, ProviderLibraryRepository

/** App-owned command for applying network-specific provider audio-quality preferences. */
interface ProviderAudioQualityPort {
    suspend fun updateAudioQualityPolicies(
        wifiPolicy: AudioQualityPolicy,
        cellularPolicy: AudioQualityPolicy,
    )
}
