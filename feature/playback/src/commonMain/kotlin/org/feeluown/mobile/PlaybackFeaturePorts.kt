package org.feeluown.mobile

import kotlinx.coroutines.flow.StateFlow

/**
 * Provider primitive used by playback. Implementations resolve one concrete provider track only;
 * replacement search/ranking/fallback policy belongs to :feature:playback.
 */
interface PlaybackProviderSourcePort {
    suspend fun resolveTrack(track: MusicTrack): PlaybackPayload?
    suspend fun lyrics(track: MusicTrack): String? = null
    suspend fun lyricsSearchKeyword(track: MusicTrack): String? = null
}

/**
 * Playback-owned provider surface. Stable provider capabilities are composed with a concrete-track
 * resolver, while unavailable-track and smart-replacement policy stays inside this feature.
 */
interface PlaybackProviderPort :
    ProviderRegistryRepository,
    ProviderSearchRepository,
    ProviderCatalogRepository {
    suspend fun replacementCandidates(
        track: MusicTrack,
        smartReplacementProviderIds: Set<String> = emptySet(),
        smartReplacementMinScore: Double = DEFAULT_SMART_REPLACEMENT_MIN_SCORE,
    ): List<ReplacementCandidate>

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
    ): PlaybackPayload

    suspend fun lyrics(track: MusicTrack): String? = null
    suspend fun lyricsSearchKeyword(track: MusicTrack): String? = null

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
