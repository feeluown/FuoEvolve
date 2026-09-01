@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PlaybackDirectResolvedEngineContractTest {
    @Test
    fun directEngineReceivesLogicalTrackAndActualResolverInputSeparately() = runTest {
        val logical = MusicTrack(
            id = "netease:logical",
            title = "Song",
            artists = "Artist",
            album = "Album",
            source = "netease",
            sourceType = TrackSourceType.Provider,
            providerId = "netease:logical",
        )
        val downloaded = logical.copy(
            sourceType = TrackSourceType.Downloaded,
            localUri = "file:///song.mp3",
        )
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(logical)
            mainQueueIndex = 0
        }
        val engine = SourceAwareEngine()
        var state = PlaybackState()
        var serial = 0L
        val coordinator = PlaybackStartCoordinator(
            queue = queue,
            playbackEngine = engine,
            playbackRepository = UnusedRepository,
            scope = this,
            currentPlaybackState = { state },
            publishPlaybackState = { state = it },
            prepareTrack = { downloaded },
            unavailablePlaybackPolicy = { UnavailablePlaybackPolicy.Skip },
            smartReplacementProviderIds = { emptySet() },
            smartReplacementMinScore = { 0.7 },
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
            onStartFailure = { _, _, _, _, throwable -> throw throwable },
            prefetchQueue = {},
        )

        coordinator.start(logical)
        advanceUntilIdle()

        assertEquals(logical, assertNotNull(engine.logicalTrack))
        assertEquals(TrackSourceType.Provider, engine.logicalTrack?.sourceType)
        assertEquals(downloaded, assertNotNull(engine.resolveTrack))
        assertEquals(TrackSourceType.Downloaded, engine.resolveTrack?.sourceType)
        assertEquals("file:///song.mp3", engine.payload?.url)
        assertEquals(logical, queue.currentTrack())
        assertEquals(TrackSourceType.Downloaded, state.resolvedSource?.sourceType)
    }

    private class SourceAwareEngine : PlaybackEngine, ResolvedPlaybackSourceAwareEngine {
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())
        var logicalTrack: MusicTrack? = null
        var resolveTrack: MusicTrack? = null
        var payload: PlaybackPayload? = null

        override fun play(track: MusicTrack, payload: PlaybackPayload) {
            error("source-aware direct playback should use playResolved")
        }

        override fun playResolved(
            logicalTrack: MusicTrack,
            resolveTrack: MusicTrack,
            payload: PlaybackPayload,
        ) {
            this.logicalTrack = logicalTrack
            this.resolveTrack = resolveTrack
            this.payload = payload
        }

        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
        override fun seekTo(positionMs: Long) = Unit
    }

    private object UnusedRepository : ProviderPlaybackRepository {
        override suspend fun resolve(
            track: MusicTrack,
            unavailablePolicy: UnavailablePlaybackPolicy,
            smartReplacementProviderIds: Set<String>,
            smartReplacementMinScore: Double,
            smartReplacementUseOriginalMetadata: Boolean,
            smartReplacementUseOriginalLyrics: Boolean,
        ): PlaybackPayload = error("local resolver input should bypass provider resolution")

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
    }
}
