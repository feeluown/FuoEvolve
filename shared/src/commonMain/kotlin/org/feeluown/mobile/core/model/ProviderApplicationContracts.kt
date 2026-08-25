package org.feeluown.mobile

/** App-owned command for applying network-specific provider audio-quality preferences. */
interface ProviderAudioQualityPort {
    suspend fun updateAudioQualityPolicies(
        wifiPolicy: AudioQualityPolicy,
        cellularPolicy: AudioQualityPolicy,
    )
}
