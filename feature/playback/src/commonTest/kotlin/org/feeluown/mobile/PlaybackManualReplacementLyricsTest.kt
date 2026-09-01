@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackManualReplacementLyricsTest {
    @Test
    fun manualSourceSwitchPreservesLoadedLyricsAndRefreshesLyricsState() = runTest {
        val original = track("netease:original")
        val selection = SmartReplacementSelection(
            replacementId = "qqmusic:replacement",
            replacementTitle = "Replacement",
            replacementArtists = "Artist",
            replacementSource = "qqmusic",
        )
        val replacement = original.withReplacementSelection(selection)
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(original)
            mainQueueIndex = 0
        }
        val lyrics = "[00:00.00]loaded lyrics"
        val fixture = fixture(
            queue = queue,
            initialPlaybackState = PlaybackState(
                status = PlayerStatus.Playing,
                currentTrack = original,
                lyrics = lyrics,
            ),
        )

        fixture.coordinator.start(
            track = replacement,
            manualSelection = selection,
            rollbackTrack = original,
        )

        assertEquals(lyrics, fixture.playbackState.lyrics)
        assertEquals(original, fixture.playbackState.currentTrack)
        assertEquals(0, fixture.lyricsResetCount)
        assertEquals(original, fixture.lastLyricsLoadTrack)
        val request = assertNotNull(fixture.engine.playedPlan).requests.first()
        assertTrue(request.resolveOnlySelectedReplacement)
        assertEquals(original, request.track)
        assertEquals(replacement, request.resolveTrack)
    }

    @Test
    fun startingDifferentLogicalTrackStillResetsLyrics() = runTest {
        val previous = track("netease:previous")
        val next = track("netease:next")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(next)
            mainQueueIndex = 0
        }
        val fixture = fixture(
            queue = queue,
            initialPlaybackState = PlaybackState(
                status = PlayerStatus.Playing,
                currentTrack = previous,
                lyrics = "[00:00.00]previous lyrics",
            ),
        )

        fixture.coordinator.start(next)

        assertEquals(1, fixture.lyricsResetCount)
        assertNull(fixture.playbackState.lyrics)
        assertEquals(next, fixture.lastLyricsLoadTrack)
    }

    private fun TestScope.fixture(
        queue: PlaybackQueueController,
        initialPlaybackState: PlaybackState,
    ): Fixture {
        var playbackState = initialPlaybackState
        var requestSerial = 0L
        var playbackParts = emptyList<PlaybackPart>()
        var currentPartIndex = -1
        var lyricsResetCount = 0
        var lastLyricsLoadTrack: MusicTrack? = null
        val engine = InternalResolutionPlaybackEngine()
        val coordinator = PlaybackStartCoordinator(
            queue = queue,
            playbackEngine = engine,
            playbackRepository = NoOpPlaybackRepository,
            scope = this,
            currentPlaybackState = { playbackState },
            publishPlaybackState = { playbackState = it },
            prepareTrack = { it },
            unavailablePlaybackPolicy = { UnavailablePlaybackPolicy.SmartReplace },
            smartReplacementProviderIds = { setOf("qqmusic") },
            smartReplacementMinScore = { 0.55 },
            nextRequestSerial = { ++requestSerial },
            currentRequestSerial = { requestSerial },
            playbackParts = { playbackParts },
            setPlaybackParts = { playbackParts = it },
            currentPartIndex = { currentPartIndex },
            setCurrentPartIndex = { currentPartIndex = it },
            prepareSleepTimer = {},
            resetLyricsForPlaybackRequest = { lyricsResetCount += 1 },
            maybeLoadLyrics = { lastLyricsLoadTrack = it },
            persistQueue = {},
            setLoading = {},
            setMessage = {},
            failureMessage = { it.message.orEmpty() },
            onRequestStarted = { _, _ -> },
            onManualSelectionStarted = { _, _, _, _ -> },
            onStartFailure = { _, _, _, _, _ -> },
            prefetchQueue = {},
        )
        return Fixture(
            coordinator = coordinator,
            engine = engine,
            playbackStateProvider = { playbackState },
            lyricsResetCountProvider = { lyricsResetCount },
            lastLyricsLoadTrackProvider = { lastLyricsLoadTrack },
        )
    }

    private fun track(id: String): MusicTrack = MusicTrack(
        id = id,
        title = "Original",
        artists = "Artist",
        album = "Album",
        source = id.substringBefore(':'),
        sourceType = TrackSourceType.Provider,
        providerId = id,
    )

    private class Fixture(
        val coordinator: PlaybackStartCoordinator,
        val engine: InternalResolutionPlaybackEngine,
        private val playbackStateProvider: () -> PlaybackState,
        private val lyricsResetCountProvider: () -> Int,
        private val lastLyricsLoadTrackProvider: () -> MusicTrack?,
    ) {
        val playbackState: PlaybackState get() = playbackStateProvider()
        val lyricsResetCount: Int get() = lyricsResetCountProvider()
        val lastLyricsLoadTrack: MusicTrack? get() = lastLyricsLoadTrackProvider()
    }

    private class InternalResolutionPlaybackEngine : PlaybackEngine {
        private val mutableState = MutableStateFlow(PlaybackState())
        override val state: StateFlow<PlaybackState> = mutableState
        override val resolvesResourcesInternally: Boolean = true

        var playedPlan: PlaybackPlan? = null

        override fun prepareLoading(track: MusicTrack) = Unit

        override fun play(track: MusicTrack, payload: PlaybackPayload) = Unit

        override fun play(plan: PlaybackPlan) {
            playedPlan = plan
        }

        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
        override fun seekTo(positionMs: Long) = Unit
    }

    private object NoOpPlaybackRepository : ProviderPlaybackRepository {
        override suspend fun replacementCandidates(
            track: MusicTrack,
            smartReplacementProviderIds: Set<String>,
            smartReplacementMinScore: Double,
        ): List<ReplacementCandidate> = emptyList()

        override suspend fun resolve(
            track: MusicTrack,
            unavailablePolicy: UnavailablePlaybackPolicy,
            smartReplacementProviderIds: Set<String>,
            smartReplacementMinScore: Double,
            smartReplacementUseOriginalMetadata: Boolean,
            smartReplacementUseOriginalLyrics: Boolean,
        ): PlaybackPayload = error("provider resolver should not be called")

        override suspend fun resolveSelectedReplacement(
            track: MusicTrack,
            smartReplacementUseOriginalMetadata: Boolean,
            smartReplacementUseOriginalLyrics: Boolean,
            smartReplacementProviderIds: Set<String>,
        ): PlaybackPayload = error("provider resolver should not be called")
    }
}
