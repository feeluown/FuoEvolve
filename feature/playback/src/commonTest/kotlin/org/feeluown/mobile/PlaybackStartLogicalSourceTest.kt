package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlaybackStartLogicalSourceTest {
    @Test
    fun manualReplacementKeepsPlanTrackLogicalAndResolverTrackPhysical() = runTest {
        val logical = track("netease:logical", "Logical")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(logical)
            mainQueueIndex = 0
        }
        val engine = PlanEngine()
        val fixture = fixture(queue, engine)
        val selection = SmartReplacementSelection(
            replacementId = "qqmusic:physical",
            replacementTitle = "Physical",
            replacementArtists = "Artist",
            replacementSource = "qqmusic",
            replacementProviderName = "QQ Music",
            replacementScore = 0.99,
        )

        fixture.start(
            track = logical,
            manualSelection = selection,
            rollbackTrack = logical,
        )

        val request = assertNotNull(engine.plan).requests.first()
        assertEquals(logical.id, request.track.id)
        assertFalse(request.track.isSmartReplacement)
        assertEquals(logical.id, queue.currentTrack()?.id)
        assertFalse(queue.currentTrack()?.isSmartReplacement == true)
        assertTrue(request.resolveTrack.isSmartReplacement)
        assertEquals("qqmusic:physical", request.resolveTrack.replacementId)
    }

    private fun TestScope.fixture(
        queue: PlaybackQueueController,
        engine: PlanEngine,
    ): PlaybackStartCoordinator {
        var serial = 0L
        var state = PlaybackState()
        return PlaybackStartCoordinator(
            queue = queue,
            playbackEngine = engine,
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
            scope = this,
            currentPlaybackState = { state },
            publishPlaybackState = { state = it },
            prepareTrack = { it },
            unavailablePlaybackPolicy = { UnavailablePlaybackPolicy.SmartReplace },
            smartReplacementProviderIds = { setOf("qqmusic") },
            smartReplacementMinScore = { 0.8 },
            nextRequestSerial = { ++serial },
            currentRequestSerial = { serial },
            playbackParts = { emptyList() },
            setPlaybackParts = {},
            currentPartIndex = { -1 },
            setCurrentPartIndex = {},
            prepareSleepTimer = {},
            resetLyricsForPlaybackRequest = {},
            maybeLoadLyrics = {},
            persistQueue = {},
            setLoading = {},
            setMessage = {},
            failureMessage = { it.message.orEmpty() },
            onRequestStarted = { _, _ -> },
            onManualSelectionStarted = { _, _, _, _ -> },
            onStartFailure = { _, _, _, _, _ -> },
            prefetchQueue = {},
        )
    }

    private fun track(id: String, title: String): MusicTrack = MusicTrack(
        id = id,
        title = title,
        artists = "Artist",
        album = "Album",
        source = id.substringBefore(':'),
        sourceType = TrackSourceType.Provider,
        providerId = id,
    )

    private class PlanEngine : PlaybackEngine {
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
        override val resolvesResourcesInternally: Boolean = true
        var plan: PlaybackPlan? = null

        override fun play(track: MusicTrack, payload: PlaybackPayload) = Unit
        override fun play(plan: PlaybackPlan) {
            this.plan = plan
        }
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
        override fun seekTo(positionMs: Long) = Unit
    }
}
