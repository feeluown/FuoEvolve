package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackReplacementRollbackSourceTest {
    @Test
    fun manualSwitchCarriesPreviousResolvedReplacementIntoRollbackInput() = runTest {
        val logical = MusicTrack(
            id = "netease:logical",
            title = "Logical",
            artists = "Artist",
            album = "",
            source = "netease",
            sourceType = TrackSourceType.Provider,
            providerId = "netease:logical",
        )
        var rollbackTrack: MusicTrack? = null
        val controller = PlaybackReplacementController(
            playbackRepository = object : ProviderPlaybackRepository {
                override suspend fun resolve(
                    track: MusicTrack,
                    unavailablePolicy: UnavailablePlaybackPolicy,
                    smartReplacementProviderIds: Set<String>,
                    smartReplacementMinScore: Double,
                    smartReplacementUseOriginalMetadata: Boolean,
                    smartReplacementUseOriginalLyrics: Boolean,
                ): PlaybackPayload = error("unused")

                override suspend fun resolveSelectedReplacement(
                    track: MusicTrack,
                    smartReplacementUseOriginalMetadata: Boolean,
                    smartReplacementUseOriginalLyrics: Boolean,
                    smartReplacementProviderIds: Set<String>,
                ): PlaybackPayload = error("unused")

                override suspend fun replacementCandidates(
                    track: MusicTrack,
                    smartReplacementProviderIds: Set<String>,
                    smartReplacementMinScore: Double,
                ): List<ReplacementCandidate> = emptyList()
            },
            scope = backgroundScope,
            smartReplacementProviderIds = { setOf("qqmusic") },
            smartReplacementMinScore = { 0.8 },
            currentTrack = { logical },
            currentResolvedSource = {
                ResolvedPlaybackSource(
                    trackId = "qqmusic:previous",
                    title = "Previous",
                    artists = "Artist",
                    album = "",
                    source = "qqmusic",
                    sourceType = TrackSourceType.Provider,
                    providerName = "QQ Music",
                    isReplacement = true,
                    replacementScore = 0.95,
                )
            },
            startManualReplacement = { _, _, rollback -> rollbackTrack = rollback },
            closePlayer = {},
            openTrackDetail = {},
            failureMessage = { throwable, fallback, _ -> throwable.message ?: fallback },
        )

        controller.selectReplacementCandidate(
            logical,
            ReplacementCandidate(
                track = MusicTrack(
                    id = "qqmusic:new",
                    title = "New",
                    artists = "Artist",
                    album = "",
                    source = "qqmusic",
                    sourceType = TrackSourceType.Provider,
                    providerId = "qqmusic:new",
                ),
                score = 0.99,
            ),
        )

        val rollback = requireNotNull(rollbackTrack)
        assertEquals(logical.id, rollback.logicalPlaybackTrack().id)
        assertTrue(rollback.isSmartReplacement)
        assertEquals("qqmusic:previous", rollback.replacementId)
    }
}
