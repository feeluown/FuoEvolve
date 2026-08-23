package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackStartCoordinatorTest {
    @Test
    fun directResolutionStartsEngineAndPublishesResolvedTrack() = runTest {
        val track = track("netease:1")
        val payload = payload(title = "Resolved title")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(track)
            mainQueueIndex = 0
        }
        val engine = FakePlaybackEngine()
        val repository = FakePlaybackRepository(resolvePayload = payload)
        val fixture = fixture(queue, engine, repository)

        fixture.coordinator.start(track)
        advanceUntilIdle()

        assertEquals(track, engine.preparedTrack)
        assertEquals(1, repository.resolveCalls)
        assertEquals(payload, engine.playedPayload)
        assertEquals("Resolved title", engine.playedTrack?.title)
        assertEquals("Resolved title", queue.currentTrack()?.title)
        assertEquals(PlayerStatus.Loading, fixture.playbackState.status)
        assertEquals("Resolved title", fixture.playbackState.currentTrack?.title)
        assertNull(fixture.coordinator.startFailure.value)
        assertEquals(0, fixture.failureCount)
    }

    @Test
    fun directResolutionFailurePublishesPlaybackOwnedFailure() = runTest {
        val track = track("netease:1")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(track)
            mainQueueIndex = 0
        }
        val engine = FakePlaybackEngine()
        val repository = FakePlaybackRepository(resolveError = IllegalStateException("missing media"))
        val fixture = fixture(queue, engine, repository)

        fixture.coordinator.start(track)
        advanceUntilIdle()

        val failure = assertNotNull(fixture.coordinator.startFailure.value)
        assertEquals(track.id, failure.trackId)
        assertEquals("start failed: missing media", failure.message)
        assertEquals(1, fixture.failureCount)
        assertNull(engine.playedPayload)
        assertEquals(PlayerStatus.Loading, fixture.playbackState.status)
    }

    @Test
    fun internalResolutionBuildsPlanWithoutCallingProviderResolver() = runTest {
        val current = track("netease:1")
        val next = track("netease:2")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(current, next)
            mainQueueIndex = 0
        }
        val engine = FakePlaybackEngine(resolvesInternally = true)
        val repository = FakePlaybackRepository(resolveError = IllegalStateException("resolver should not be called"))
        val fixture = fixture(queue, engine, repository)

        fixture.coordinator.start(current)

        val plan = assertNotNull(engine.playedPlan)
        assertEquals(1L, plan.generation)
        assertEquals(listOf(current.id, next.id), plan.requests.map { it.track.id })
        assertEquals(0, repository.resolveCalls)
        assertNull(fixture.coordinator.startFailure.value)
    }

    @Test
    fun activeSelectionPreparesEngineBeforePublishingNewPlaybackState() = runTest {
        val restored = track("netease:restored")
        val selected = track("qqmusic:selected")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(selected)
            mainQueueIndex = 0
            markNextPlaybackStart(PlaybackStartReason.PLAYLIST_REPLACE)
        }
        val engine = FakePlaybackEngine(resolvesInternally = true)
        val repository = FakePlaybackRepository(resolveError = IllegalStateException("resolver should not be called"))
        var published = false
        val fixture = fixture(
            queue = queue,
            engine = engine,
            repository = repository,
            initialPlaybackState = PlaybackState(status = PlayerStatus.Paused, currentTrack = restored),
            onPublishPlaybackState = {
                assertEquals(selected, engine.preparedTrack)
                assertEquals(PlaybackStartReason.PLAYLIST_REPLACE, engine.preparedReason)
                published = true
            },
        )

        fixture.coordinator.start(selected)

        assertTrue(published)
        assertEquals(selected, fixture.playbackState.currentTrack)
        assertEquals(PlayerStatus.Loading, fixture.playbackState.status)
    }

    private fun TestScope.fixture(
        queue: PlaybackQueueController,
        engine: FakePlaybackEngine,
        repository: FakePlaybackRepository,
        initialPlaybackState: PlaybackState = PlaybackState(),
        onPublishPlaybackState: (PlaybackState) -> Unit = {},
    ): Fixture {
        var playbackState = initialPlaybackState
        var requestSerial = 0L
        var parts = emptyList<PlaybackPart>()
        var currentPartIndex = -1
        var failureCount = 0
        val coordinator = PlaybackStartCoordinator(
            queue = queue,
            playbackEngine = engine,
            playbackRepository = repository,
            scope = this,
            currentPlaybackState = { playbackState },
            publishPlaybackState = {
                onPublishPlaybackState(it)
                playbackState = it
            },
            prepareTrack = { it },
            unavailablePlaybackPolicy = { UnavailablePlaybackPolicy.Skip },
            smartReplacementProviderIds = { setOf("bilibili") },
            smartReplacementMinScore = { 0.7 },
            nextRequestSerial = { ++requestSerial },
            currentRequestSerial = { requestSerial },
            playbackParts = { parts },
            setPlaybackParts = { parts = it },
            currentPartIndex = { currentPartIndex },
            setCurrentPartIndex = { currentPartIndex = it },
            prepareSleepTimer = {},
            resetLyricsForPlaybackRequest = {},
            maybeLoadLyrics = {},
            persistQueue = {},
            setLoading = {},
            setMessage = {},
            failureMessage = { "start failed: ${it.message}" },
            onRequestStarted = { _, _ -> },
            onManualSelectionStarted = { _, _, _, _ -> },
            onStartFailure = { _, _, _, _, _ -> failureCount += 1 },
            prefetchQueue = {},
        )
        return Fixture(
            coordinator = coordinator,
            playbackStateProvider = { playbackState },
            failureCountProvider = { failureCount },
        )
    }

    private fun track(id: String): MusicTrack = MusicTrack(
        id = id,
        title = "Original title",
        artists = "Artist",
        album = "Album",
        source = id.substringBefore(':'),
        sourceType = TrackSourceType.Provider,
        providerId = id,
    )

    private fun payload(title: String): PlaybackPayload = PlaybackPayload(
        url = "https://example.test/audio.mp3",
        title = title,
        artists = "Resolved artist",
        album = "Resolved album",
        source = "netease",
        durationMs = 123_000,
    )

    private class Fixture(
        val coordinator: PlaybackStartCoordinator,
        private val playbackStateProvider: () -> PlaybackState,
        private val failureCountProvider: () -> Int,
    ) {
        val playbackState: PlaybackState get() = playbackStateProvider()
        val failureCount: Int get() = failureCountProvider()
    }

    private class FakePlaybackEngine(
        private val resolvesInternally: Boolean = false,
    ) : PlaybackEngine, PlaybackStartReasonAwareEngine {
        private val mutableState = MutableStateFlow(PlaybackState())
        override val state: StateFlow<PlaybackState> = mutableState
        override val resolvesResourcesInternally: Boolean get() = resolvesInternally

        var preparedTrack: MusicTrack? = null
        var preparedReason: PlaybackStartReason? = null
        var playedTrack: MusicTrack? = null
        var playedPayload: PlaybackPayload? = null
        var playedPlan: PlaybackPlan? = null

        override fun prepareLoading(track: MusicTrack) {
            preparedTrack = track
        }

        override fun prepareLoading(track: MusicTrack, reason: PlaybackStartReason) {
            preparedTrack = track
            preparedReason = reason
        }

        override fun play(track: MusicTrack, payload: PlaybackPayload) {
            playedTrack = track
            playedPayload = payload
        }

        override fun play(plan: PlaybackPlan) {
            playedPlan = plan
        }

        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
        override fun seekTo(positionMs: Long) = Unit
    }

    private class FakePlaybackRepository(
        private val resolvePayload: PlaybackPayload? = null,
        private val resolveError: Throwable? = null,
    ) : ProviderPlaybackRepository {
        var resolveCalls: Int = 0

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
        ): PlaybackPayload {
            resolveCalls += 1
            resolveError?.let { throw it }
            return requireNotNull(resolvePayload)
        }

        override suspend fun resolveSelectedReplacement(
            track: MusicTrack,
            smartReplacementUseOriginalMetadata: Boolean,
            smartReplacementUseOriginalLyrics: Boolean,
            smartReplacementProviderIds: Set<String>,
        ): PlaybackPayload = resolve(
            track = track,
            unavailablePolicy = UnavailablePlaybackPolicy.SmartReplace,
            smartReplacementProviderIds = smartReplacementProviderIds,
            smartReplacementMinScore = 0.0,
            smartReplacementUseOriginalMetadata = smartReplacementUseOriginalMetadata,
            smartReplacementUseOriginalLyrics = smartReplacementUseOriginalLyrics,
        )

        override suspend fun lyrics(track: MusicTrack): String? = null
    }
}