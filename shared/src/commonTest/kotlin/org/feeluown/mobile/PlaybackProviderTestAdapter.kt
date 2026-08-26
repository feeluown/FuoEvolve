package org.feeluown.mobile

/**
 * Test-only bridge for provider integration fixtures that exercise playback replacement policy.
 * Production provider repositories intentionally do not own unavailable-track replacement.
 *
 * These legacy shared fixtures reconstruct the logical playback track directly from the returned
 * payload instead of going through PlaybackStartCoordinator.withResolvedPayload(). Mirror the
 * coordinator's smart-replacement source normalization here so the fixture exercises the same
 * lyrics-routing semantics as the real playback chain.
 */
internal suspend fun KotlinProviderRepository.resolve(
    track: MusicTrack,
    unavailablePolicy: UnavailablePlaybackPolicy,
    smartReplacementProviderIds: Set<String>,
    smartReplacementMinScore: Double,
    smartReplacementUseOriginalMetadata: Boolean,
    smartReplacementUseOriginalLyrics: Boolean,
): PlaybackPayload {
    val payload = createPlaybackProviderPort(
        registry = this,
        search = this,
        catalog = this,
        source = this,
        failureMessage = { throwable, fallback, _ -> throwable.message ?: fallback },
    ).resolve(
        track = track,
        unavailablePolicy = unavailablePolicy,
        smartReplacementProviderIds = smartReplacementProviderIds,
        smartReplacementMinScore = smartReplacementMinScore,
        smartReplacementUseOriginalMetadata = smartReplacementUseOriginalMetadata,
        smartReplacementUseOriginalLyrics = smartReplacementUseOriginalLyrics,
    )
    return if (payload.isSmartReplacement) {
        payload.copy(source = payload.originalSource?.takeIf { it.isNotBlank() } ?: payload.source)
    } else {
        payload
    }
}
