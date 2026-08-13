package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplacementLearningTest {
    @Test
    fun pairwiseFeedbackRaisesSelectedChoiceAndClampsAdjustment() {
        val learner = FtrlReplacementPreferenceLearner()
        val selected = choice("qqmusic:selected", featureVector(0))
        val rejected = choice("bilibili:rejected", featureVector(1))
        val feedback = feedback(selected = selected, rejected = listOf(rejected))

        repeat(250) { index ->
            learner.recordPlaybackFeedback(
                feedback = feedback,
                status = PlayerStatus.Playing,
                observedAtMillis = index.toLong(),
            )
        }

        assertEquals(
            REPLACEMENT_PREFERENCE_LOGIT_LIMIT,
            learner.adjustment(selected.vector),
            absoluteTolerance = 0.000_001,
        )
        assertEquals(
            -REPLACEMENT_PREFERENCE_LOGIT_LIMIT,
            learner.adjustment(rejected.vector),
            absoluteTolerance = 0.000_001,
        )
        assertEquals(250, learner.snapshot().updateCount)
    }

    @Test
    fun failureAndCancellationDoNotLearnWhilePausedPlaybackDoes() {
        val learner = FtrlReplacementPreferenceLearner()
        val selected = choice("qqmusic:selected", featureVector(0))
        val rejected = choice("bilibili:rejected", featureVector(1))
        val feedback = feedback(selected = selected, rejected = listOf(rejected))

        listOf(
            PlayerStatus.Loading,
            PlayerStatus.Error,
            PlayerStatus.Idle,
            PlayerStatus.Ended,
        ).forEach { status ->
            learner.recordPlaybackFeedback(feedback, status, observedAtMillis = 10)
        }

        assertEquals(0, learner.snapshot().updateCount)
        assertNull(learner.exactReplacementKey("netease:origin"))
        assertEquals(0.0, learner.adjustment(selected.vector))

        learner.recordPlaybackFeedback(feedback, PlayerStatus.Paused, observedAtMillis = 20)

        assertEquals(1, learner.snapshot().updateCount)
        assertEquals(selected.key, learner.exactReplacementKey("netease:origin"))
        assertTrue(learner.adjustment(selected.vector) > 0.0)
    }

    @Test
    fun exactSelectionsKeepAtMostFiveHundredMostRecentMappings() {
        val learner = FtrlReplacementPreferenceLearner()

        repeat(MAX_REPLACEMENT_EXACT_MAPPINGS + 1) { index ->
            learner.recordPlaybackFeedback(
                feedback = feedback(
                    originalKey = "origin:$index",
                    selected = choice("replacement:$index", featureVector(index % 2)),
                ),
                status = PlayerStatus.Playing,
                observedAtMillis = index.toLong(),
            )
        }

        assertEquals(MAX_REPLACEMENT_EXACT_MAPPINGS, learner.snapshot().exactSelections.size)
        assertNull(learner.exactReplacementKey("origin:0"))
        assertEquals("replacement:500", learner.exactReplacementKey("origin:500"))

        learner.recordPlaybackFeedback(
            feedback = feedback(
                originalKey = "origin:1",
                selected = choice("replacement:refreshed", featureVector(0)),
            ),
            status = PlayerStatus.Playing,
            observedAtMillis = 1_000,
        )
        learner.recordPlaybackFeedback(
            feedback = feedback(
                originalKey = "origin:501",
                selected = choice("replacement:501", featureVector(1)),
            ),
            status = PlayerStatus.Playing,
            observedAtMillis = 1_001,
        )

        assertNull(learner.exactReplacementKey("origin:2"))
        assertEquals("replacement:refreshed", learner.exactReplacementKey("origin:1"))
        assertEquals(MAX_REPLACEMENT_EXACT_MAPPINGS, learner.snapshot().exactSelections.size)
    }

    @Test
    fun codecRoundTripsLearnedWeightsAndSanitizesInvalidState() {
        val learner = FtrlReplacementPreferenceLearner()
        val feedback = feedback(
            selected = choice("qqmusic:selected", featureVector(0)),
            rejected = listOf(choice("bilibili:rejected", featureVector(1))),
        )
        repeat(3) { learner.recordPlaybackFeedback(feedback, PlayerStatus.Playing, it.toLong()) }
        val state = learner.snapshot()

        assertEquals(state, ReplacementLearningCodec.decode(ReplacementLearningCodec.encode(state)))
        assertEquals(ReplacementLearningState(), ReplacementLearningCodec.decode("not-json"))

        val sanitized = ReplacementLearningCodec.decode(
            ReplacementLearningCodec.encode(
                ReplacementLearningState(
                    z = listOf(1.0),
                    n = listOf(-1.0),
                    updateCount = -1,
                    exactSelections = listOf(
                        ReplacementExactSelection("origin", selection("replacement"), -10),
                    ),
                ),
            ),
        )
        assertEquals(List(REPLACEMENT_PREFERENCE_FEATURE_COUNT) { 0.0 }, sanitized.z)
        assertEquals(List(REPLACEMENT_PREFERENCE_FEATURE_COUNT) { 0.0 }, sanitized.n)
        assertEquals(0, sanitized.updateCount)
        assertEquals(0, sanitized.exactSelections.single().selectedAtMillis)
    }

    @Test
    fun storeExtensionsPersistCodecPayload() = runTest {
        val store = InMemoryReplacementLearningStore()
        val state = ReplacementLearningState(
            exactSelections = listOf(
                ReplacementExactSelection("origin", selection("replacement"), 42),
            ),
        )

        store.saveState(state)

        assertEquals(state, store.loadState())
    }

    @Test
    fun repositoryMigratesLegacySelectionsPersistsFeedbackAndResets() = runTest {
        val store = InMemoryReplacementLearningStore()
        val repository = ReplacementLearningRepository(store)
        val legacy = selection("qqmusic:legacy")

        repository.initialize(mapOf("netease:legacy" to legacy))

        assertEquals(legacy, repository.exactSelection("netease:legacy"))
        val selected = choice("qqmusic:selected", featureVector(0))
        repository.record(
            feedback = feedback(
                selected = selected,
                rejected = listOf(choice("bilibili:automatic", featureVector(1))),
            ),
            status = PlayerStatus.Playing,
            observedAtMillis = 42,
        )
        val restored = ReplacementLearningRepository(store)
        restored.initialize()

        assertEquals(selected.key, restored.exactReplacementKey("netease:origin"))
        assertEquals(1, restored.state.value.updateCount)
        restored.reset()
        assertEquals(ReplacementLearningState(), restored.state.value)
        assertEquals(ReplacementLearningState(), store.loadState())
    }

    @Test
    fun repositoryCanMergeLegacySelectionsAfterBackgroundInitialization() = runTest {
        val store = InMemoryReplacementLearningStore()
        val repository = ReplacementLearningRepository(store)
        val legacy = selection("qqmusic:legacy")

        repository.initialize()
        repository.initialize(mapOf("netease:legacy" to legacy))

        assertEquals(legacy, repository.exactSelection("netease:legacy"))
        val restored = ReplacementLearningRepository(store)
        restored.initialize()
        assertEquals(legacy, restored.exactSelection("netease:legacy"))
    }

    @Test
    fun disabledGeneralizedLearningKeepsExactChoiceWithoutUpdatingFtrlHead() = runTest {
        val repository = ReplacementLearningRepository(InMemoryReplacementLearningStore())
        repository.initialize()
        repository.setGeneralizedLearningEnabled(false)
        val selected = choice("qqmusic:selected", featureVector(0))
        val feedback = feedback(
            selected = selected,
            rejected = listOf(choice("bilibili:automatic", featureVector(1))),
        )

        repository.record(feedback, PlayerStatus.Playing, observedAtMillis = 42)

        assertEquals(selected.key, repository.exactReplacementKey("netease:origin"))
        assertEquals(0, repository.state.value.updateCount)
        assertEquals(0.0, repository.adjustment(selected.vector))
    }

    @Test
    fun synchronousFeedbackApiAlsoHonorsDisabledGeneralizedLearning() {
        val repository = ReplacementLearningRepository()
        repository.setGeneralizedLearningEnabled(false)
        val selected = choice("qqmusic:selected", featureVector(0))

        repository.recordPlaybackFeedback(
            feedback = feedback(
                selected = selected,
                rejected = listOf(choice("bilibili:automatic", featureVector(1))),
            ),
            status = PlayerStatus.Playing,
            observedAtMillis = 42,
        )

        assertEquals(selected.key, repository.exactReplacementKey("netease:origin"))
        assertEquals(0, repository.state.value.updateCount)
    }

    @Test
    fun selectionFeedbackUsesOnlyPreviouslyPlayedAutomaticReplacementAsNegative() {
        val original = track("netease:origin", "netease", "Song", "Artist", "Album")
        val selected = track("qqmusic:selected", "qqmusic", "Song", "Artist", "Album")
        val automatic = track("bilibili:auto", "bilibili", "Song Live", "Artist", "Album")
        val exactSelection = selection(selected.id)

        val feedback = replacementSelectionFeedback(
            original = original,
            selected = selected,
            previousAutomatic = automatic,
            exactSelection = exactSelection,
        )

        assertEquals(listOf(automatic.id), feedback.rejected.map { it.key })
        assertEquals(exactSelection, feedback.exactSelection)
        assertTrue(
            replacementSelectionFeedback(original, selected, null, exactSelection).rejected.isEmpty(),
        )
    }

    @Test
    fun vectorRejectsWrongDimensionAndNonFiniteValues() {
        assertFailsWith<IllegalArgumentException> {
            ReplacementPreferenceVector(listOf(1.0))
        }
        assertFailsWith<IllegalArgumentException> {
            ReplacementPreferenceVector(
                List(REPLACEMENT_PREFERENCE_FEATURE_COUNT) { index ->
                    if (index == 0) Double.NaN else 0.0
                },
            )
        }
    }

    @Test
    fun metadataVectorIsStableAndDistinguishesVersionSignals() {
        val original = track(
            id = "netease:origin",
            source = "netease",
            title = "Example Song",
            artists = "Artist",
            album = "Album",
        )
        val plain = track(
            id = "qqmusic:plain",
            source = "qqmusic",
            title = "Example Song",
            artists = "Artist",
            album = "Album",
        )
        val live = plain.copy(
            id = "bilibili:live",
            source = "bilibili",
            title = "Example Song (Live Remix)",
            album = "官方无损现场版",
        )

        val plainVector = replacementPreferenceVector(original, plain)
        val liveVector = replacementPreferenceVector(original, live)

        assertEquals(REPLACEMENT_PREFERENCE_FEATURE_COUNT, plainVector.values.size)
        assertEquals(plainVector, replacementPreferenceVector(original, plain))
        assertTrue(plainVector != liveVector)
    }

    private fun feedback(
        originalKey: String = "netease:origin",
        selected: ReplacementPreferenceChoice,
        rejected: List<ReplacementPreferenceChoice> = emptyList(),
    ): ReplacementSelectionFeedback = ReplacementSelectionFeedback(
        originalKey = originalKey,
        selected = selected,
        rejected = rejected,
        exactSelection = selection(selected.key),
    )

    private fun selection(key: String): SmartReplacementSelection = SmartReplacementSelection(
        replacementId = key,
        replacementTitle = "Replacement",
        replacementArtists = "Artist",
        replacementSource = key.substringBefore(':'),
    )

    private fun choice(key: String, vector: ReplacementPreferenceVector): ReplacementPreferenceChoice =
        ReplacementPreferenceChoice(key = key, vector = vector)

    private fun featureVector(index: Int): ReplacementPreferenceVector =
        ReplacementPreferenceVector(
            List(REPLACEMENT_PREFERENCE_FEATURE_COUNT) { featureIndex ->
                if (featureIndex == index) 1.0 else 0.0
            },
        )

    private fun track(
        id: String,
        source: String,
        title: String,
        artists: String,
        album: String,
    ): MusicTrack = MusicTrack(
        id = id,
        title = title,
        artists = artists,
        album = album,
        source = source,
        sourceType = TrackSourceType.Provider,
        durationMs = 180_000,
    )

    private class InMemoryReplacementLearningStore : ReplacementLearningStore {
        private var encodedState: String? = null

        override suspend fun load(): String? = encodedState

        override suspend fun save(encodedState: String) {
            this.encodedState = encodedState
        }
    }
}
