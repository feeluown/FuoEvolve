package org.feeluown.mobile

import kotlinx.coroutines.flow.StateFlow

/** Provider surface needed by playback resolution and lyrics. */
interface ProviderPlaybackRepository {
    suspend fun replacementCandidates(
        track: MusicTrack,
        smartReplacementProviderIds: Set<String> = emptySet(),
        smartReplacementMinScore: Double = DEFAULT_SMART_REPLACEMENT_MIN_SCORE,
    ): List<ReplacementCandidate> = emptyList()

    suspend fun resolve(
        track: MusicTrack,
        unavailablePolicy: UnavailablePlaybackPolicy = DEFAULT_UNAVAILABLE_PLAYBACK_POLICY,
        smartReplacementProviderIds: Set<String> = emptySet(),
        smartReplacementMinScore: Double = DEFAULT_SMART_REPLACEMENT_MIN_SCORE,
        smartReplacementUseOriginalMetadata: Boolean = true,
        smartReplacementUseOriginalLyrics: Boolean = true,
    ): PlaybackPayload

    suspend fun resolveSelectedReplacement(
        track: MusicTrack,
        smartReplacementUseOriginalMetadata: Boolean = true,
        smartReplacementUseOriginalLyrics: Boolean = true,
        smartReplacementProviderIds: Set<String> = emptySet(),
    ): PlaybackPayload = resolve(
        track = track,
        unavailablePolicy = UnavailablePlaybackPolicy.Skip,
        smartReplacementProviderIds = smartReplacementProviderIds,
        smartReplacementUseOriginalMetadata = smartReplacementUseOriginalMetadata,
        smartReplacementUseOriginalLyrics = smartReplacementUseOriginalLyrics,
    )

    suspend fun lyrics(track: MusicTrack): String? = null

    suspend fun lyricsSearchKeyword(track: MusicTrack): String? = null
}

/**
 * Playback-owned provider port. It composes only stable provider capabilities actually required by
 * queue extension, lyrics and playback resolution; the application aggregate repository stays in
 * the integration layer.
 */
interface PlaybackProviderPort :
    ProviderRegistryRepository,
    ProviderSearchRepository,
    ProviderCatalogRepository,
    ProviderPlaybackRepository {
    fun failureMessage(throwable: Throwable, fallback: String, providerId: String? = null): String
}

data class PlaybackFeatureSettings(
    val enabledProviderIds: Set<String> = emptySet(),
    val unavailablePlaybackPolicy: UnavailablePlaybackPolicy = DEFAULT_UNAVAILABLE_PLAYBACK_POLICY,
    val smartReplacementProviderIds: Set<String> = emptySet(),
    val smartReplacementMinScore: Double = DEFAULT_SMART_REPLACEMENT_MIN_SCORE,
    val smartReplacementSelections: Map<String, SmartReplacementSelection> = emptyMap(),
    val lyricsAssociations: Map<String, String> = emptyMap(),
    val lyricsAlignmentOffsetsMs: Map<String, Long> = emptyMap(),
)

/** Playback-owned preference projection and persistence commands. */
interface PlaybackSettingsPort {
    val state: StateFlow<PlaybackFeatureSettings>

    suspend fun awaitSettings(): PlaybackFeatureSettings
    suspend fun storeSmartReplacementSelections(value: Map<String, SmartReplacementSelection>)
    suspend fun storeLyricsAssociations(value: Map<String, String>)
    suspend fun storeLyricsAlignmentOffsetsMs(value: Map<String, Long>)
}

/** Download capability required only to prefer an already-downloaded source during playback. */
fun interface PlaybackDownloadPort {
    fun downloadedUri(trackId: String): String?
}
