package org.feeluown.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplacementIntelligenceTest {
    @Test
    fun providerSearchesUseBothQueriesAndIsolateSingleQueryFailure() = runTest {
        val calls = mutableListOf<String>()
        val lock = Mutex()
        val result = searchReplacementCandidatesByProvider(
            providerIds = listOf("qqmusic", "bilibili"),
            queries = listOf("song artist", "song"),
            search = { providerId, query ->
                lock.withLock { calls += "$providerId:$query" }
                if (providerId == "qqmusic" && query == "song artist") error("temporary failure")
                listOf(track("$providerId:$query"))
            },
        )

        assertEquals(
            listOf(
                "bilibili:song",
                "bilibili:song artist",
                "qqmusic:song",
                "qqmusic:song artist",
            ),
            calls.sorted(),
        )
        assertEquals(3, result.size)
        assertTrue(result.any { it.id == "qqmusic:song" })
        assertTrue(result.any { it.id == "bilibili:song artist" })
    }

    @Test
    fun replacementQueriesKeepOriginalMetadataAndRemoveVersionDecorations() {
        val queries = replacementSearchQueries(
            track(
                id = "netease:origin",
                title = "Song（Live 2024） feat. Guest",
            ).copy(artists = "Artist ft. Guest"),
        )

        assertEquals(
            listOf(
                "Song（Live 2024） feat. Guest Artist ft. Guest",
                "Song Artist",
            ),
            queries,
        )
    }

    @Test
    fun providerSearchesDeduplicateAndCapEachSourceAtTwenty() = runTest {
        val result = searchReplacementCandidatesByProvider(
            providerIds = listOf("qqmusic", "bilibili"),
            queries = listOf("full", "title"),
            search = { providerId, query ->
                (0 until 30).map { index ->
                    track("$providerId:${if (query == "title") index + 10 else index}")
                }
            },
        )

        assertEquals(40, result.size)
        assertEquals(20, result.count { it.source == "qqmusic" })
        assertEquals(20, result.count { it.source == "bilibili" })
    }

    @Test
    fun rankingPoolKeepsOnlyBestThirtyTwoCandidates() {
        val result = replacementRankingPool(
            (0 until 40).map { index ->
                ReplacementCandidate(
                    track = track("qqmusic:$index"),
                    score = index / 40.0,
                )
            },
        )

        assertEquals(32, result.size)
        assertEquals("qqmusic:39", result.first().track.id)
        assertEquals("qqmusic:8", result.last().track.id)
    }

    @Test
    fun rankingPoolPreservesTopCandidatesFromEachSource() {
        val highScoringSource = (0 until 40).map { index ->
            ReplacementCandidate(
                track = track("qqmusic:$index"),
                score = 0.5 + index / 100.0,
            )
        }
        val lowScoringSource = (0 until 2).map { index ->
            ReplacementCandidate(
                track = track("bilibili:$index"),
                score = index / 100.0,
            )
        }

        val result = replacementRankingPool(highScoringSource + lowScoringSource)

        assertEquals(32, result.size)
        assertTrue(result.any { it.track.id == "bilibili:0" })
        assertTrue(result.any { it.track.id == "bilibili:1" })
    }

    @Test
    fun onDeviceRankingFallsBackWhenRankerThrows() = runTest {
        val request = rankingRequest()
        val ranked = rankReplacementCandidatesWithFallback(
            request = request,
            ranker = object : ReplacementRanker {
                override suspend fun rank(request: ReplacementRankingRequest): List<ReplacementCandidate> {
                    error("model failure")
                }
            },
        )

        assertEquals(listOf("qqmusic:high", "qqmusic:low"), ranked.map { it.track.id })
        assertTrue(ranked.all { it.rankingStrategy == ReplacementRankingStrategy.LegacyFallback })
    }

    @Test
    fun onDeviceRankingFallsBackOnTimeout() = runTest {
        val ranked = rankReplacementCandidatesWithFallback(
            request = rankingRequest(),
            ranker = object : ReplacementRanker {
                override suspend fun rank(request: ReplacementRankingRequest): List<ReplacementCandidate> {
                    delay(100)
                    return request.candidates
                }
            },
            timeoutMs = 10,
        )

        assertTrue(ranked.all { it.rankingStrategy == ReplacementRankingStrategy.LegacyFallback })
    }

    @Test
    fun onDeviceRankingRethrowsCancellation() = runTest {
        assertFailsWith<CancellationException> {
            rankReplacementCandidatesWithFallback(
                request = rankingRequest(),
                ranker = object : ReplacementRanker {
                    override suspend fun rank(request: ReplacementRankingRequest): List<ReplacementCandidate> {
                        throw CancellationException("cancelled")
                    }
                },
            )
        }
    }

    @Test
    fun prepareTimeoutDoesNotConsumeRankingBudget() = runTest {
        var rankCalls = 0
        val manager = object : ReplacementModelManager {
            override val capability = ReplacementIntelligenceCapability(
                modelIncluded = true,
                onDeviceAvailable = true,
            )
            override val activeTier: ReplacementModelTier? = null
            override val state = ReplacementModelState.NotPrepared

            override suspend fun prepare(tier: ReplacementModelTier): ReplacementModelState {
                delay(40)
                return ReplacementModelState.Ready
            }
        }

        val ranked = rankReplacementCandidatesWithFallback(
            request = rankingRequest(),
            ranker = object : ReplacementRanker {
                override suspend fun rank(request: ReplacementRankingRequest): List<ReplacementCandidate> {
                    rankCalls += 1
                    return request.candidates.mapIndexed { index, candidate ->
                        val score = if (index == 0) 0.72 else 0.93
                        candidate.copy(
                            score = score,
                            modelScore = score,
                            sameSongConfidence = 0.9,
                        )
                    }
                }
            },
            modelManager = manager,
            timeoutMs = 20,
            prepareTimeoutMs = 200,
        )

        assertEquals(1, rankCalls)
        assertEquals(ReplacementRankingStrategy.OnDeviceLite, ranked.first().rankingStrategy)
    }

    @Test
    fun rankingScoreBelowStrictnessThresholdIsNotAutoEligible() = runTest {
        val ranked = rankReplacementCandidatesWithFallback(
            request = rankingRequest(),
            ranker = object : ReplacementRanker {
                override suspend fun rank(request: ReplacementRankingRequest): List<ReplacementCandidate> =
                    request.candidates.map { candidate ->
                        candidate.copy(
                            score = 0.2,
                            modelScore = 0.2,
                            sameSongConfidence = 0.2,
                        )
                    }
            },
        )

        assertEquals(ReplacementRankingStrategy.OnDeviceLite, ranked.first().rankingStrategy)
        assertTrue(ranked.none { it.autoEligible })
        assertTrue(ranked.all { it.score < SmartReplacementStrictness.Balanced.identityThreshold })
        assertEquals(0.2, ranked.first().modelScore)
    }

    @Test
    fun autoReplaceUsesTheSameDisplayedScoreAsTheCandidateList() = runTest {
        val original = track("netease:origin", title = "秋分", durationMs = 200_000).copy(
            artists = "音阙诗听",
        )
        val lexicalBoosted = ReplacementCandidate(
            track = track("bilibili:lexical", title = "秋分", durationMs = 198_000).copy(
                artists = "音阙诗听",
            ),
            score = 0.60,
        )
        val weakerLexical = ReplacementCandidate(
            track = track("bilibili:weaker", title = "秋分", durationMs = 198_000).copy(
                artists = "游戏人生超好玩",
            ),
            score = 0.60,
        )
        val ranked = rankReplacementCandidatesWithFallback(
            request = ReplacementRankingRequest(
                original = original,
                candidates = listOf(weakerLexical, lexicalBoosted),
                mode = ReplacementRankingMode.OnDevice,
                strictness = SmartReplacementStrictness.Balanced,
                modelTier = ReplacementModelTier.Lite,
            ),
            ranker = object : ReplacementRanker {
                override suspend fun rank(request: ReplacementRankingRequest): List<ReplacementCandidate> =
                    request.candidates.map { candidate ->
                        val semantic = if (candidate.track.id == "bilibili:lexical") 0.50 else 0.80
                        candidate.copy(
                            score = semantic,
                            modelScore = semantic,
                            sameSongConfidence = semantic,
                        )
                    }
            },
        )

        val weakerScore = mixedScore(original, weakerLexical.track, semantic = 0.80)
        val lexicalScore = mixedScore(original, lexicalBoosted.track, semantic = 0.50)
        assertEquals("bilibili:weaker", ranked.first().track.id)
        assertEquals(weakerScore, ranked.first().score)
        assertEquals(0.80, ranked.first().modelScore)
        assertEquals(weakerScore, ranked.first().sameSongConfidence)
        assertEquals(ranked.first().autoEligible, weakerScore >= SmartReplacementStrictness.Balanced.identityThreshold)
        assertEquals("bilibili:lexical", ranked.last().track.id)
        assertEquals(lexicalScore, ranked.last().score)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun closeTopCandidateScoresKeepTheHighestModelResult() = runTest {
        val request = rankingRequest()
        val ranked = rankReplacementCandidatesWithFallback(
            request = request,
            ranker = object : ReplacementRanker {
                override suspend fun rank(request: ReplacementRankingRequest): List<ReplacementCandidate> =
                    request.candidates.mapIndexed { index, candidate ->
                        candidate.copy(
                            score = if (index == 0) 0.74 else 0.72,
                            modelScore = if (index == 0) 0.74 else 0.72,
                            sameSongConfidence = 0.9,
                        )
                    }
            },
        )
        val expectedFirst = mixedScore(request.original, request.candidates[0].track, semantic = 0.74)
        val expectedLast = mixedScore(request.original, request.candidates[1].track, semantic = 0.72)

        assertEquals("qqmusic:low", ranked.first().track.id)
        assertEquals(expectedFirst, ranked.first().score)
        assertEquals(0.74, ranked.first().modelScore)
        assertEquals(expectedFirst, ranked.first().sameSongConfidence)
        assertEquals(ReplacementRankingStrategy.OnDeviceLite, ranked.first().rankingStrategy)
        assertTrue(ranked.first().autoEligible)
        assertEquals(expectedLast, ranked.last().score)
        assertTrue(ranked.last().autoEligible)
    }

    @Test
    fun durationMismatchIsDownrankedAndNotAutoEligible() = runTest {
        val original = track("netease:origin", durationMs = 200_000)
        val close = ReplacementCandidate(track("qqmusic:close", durationMs = 198_000), score = 0.40)
        val far = ReplacementCandidate(track("qqmusic:far", durationMs = 400_000), score = 0.40)
        val ranked = rankReplacementCandidatesWithFallback(
            request = ReplacementRankingRequest(
                original = original,
                candidates = listOf(far, close),
                mode = ReplacementRankingMode.OnDevice,
                strictness = SmartReplacementStrictness.Balanced,
                modelTier = ReplacementModelTier.Lite,
            ),
            ranker = object : ReplacementRanker {
                override suspend fun rank(request: ReplacementRankingRequest): List<ReplacementCandidate> =
                    request.candidates.map { candidate ->
                        val score = if (candidate.track.id == "qqmusic:far") 0.95 else 0.90
                        candidate.copy(
                            score = score,
                            modelScore = score,
                            sameSongConfidence = score,
                        )
                    }
            },
        )

        assertEquals("qqmusic:close", ranked.first().track.id)
        assertEquals(mixedScore(original, close.track, semantic = 0.90), ranked.first().score)
        assertTrue(ranked.first().autoEligible)
        assertEquals("qqmusic:far", ranked.last().track.id)
        assertEquals(0.95, ranked.last().modelScore)
        assertTrue(ranked.last().score < ranked.first().score)
        assertFalse(ranked.last().autoEligible)
        assertEquals(ReplacementRankingStrategy.OnDeviceLite, ranked.first().rankingStrategy)
    }

    @Test
    fun modelManagerCapabilityAndPrepareFailureFallBackBeforeRanking() = runTest {
        var rankCalls = 0
        val manager = object : ReplacementModelManager {
            override val capability = ReplacementIntelligenceCapability(
                modelIncluded = true,
                onDeviceAvailable = true,
            )
            override val activeTier: ReplacementModelTier? = null
            override val state = ReplacementModelState.NotPrepared

            override suspend fun prepare(tier: ReplacementModelTier): ReplacementModelState =
                ReplacementModelState.Failed
        }

        val ranked = rankReplacementCandidatesWithFallback(
            request = rankingRequest(),
            ranker = object : ReplacementRanker {
                override suspend fun rank(request: ReplacementRankingRequest): List<ReplacementCandidate> {
                    rankCalls += 1
                    return request.candidates
                }
            },
            modelManager = manager,
        )

        assertEquals(0, rankCalls)
        assertTrue(ranked.all { it.rankingStrategy == ReplacementRankingStrategy.LegacyFallback })
    }

    @Test
    fun incompleteModelOutputFallsBackToLegacy() = runTest {
        val ranked = rankReplacementCandidatesWithFallback(
            request = rankingRequest(),
            ranker = object : ReplacementRanker {
                override suspend fun rank(request: ReplacementRankingRequest): List<ReplacementCandidate> =
                    listOf(request.candidates.first().copy(sameSongConfidence = 0.95, modelScore = 0.95))
            },
        )

        assertTrue(ranked.all { it.rankingStrategy == ReplacementRankingStrategy.LegacyFallback })
    }

    @Test
    fun durationGateRejectsDifferenceAboveThirtyFivePercent() {
        val original = track("netease:origin", durationMs = 200_000)

        assertTrue(track("qqmusic:close", durationMs = 270_000).passesReplacementIdentityGate(original))
        assertFalse(track("qqmusic:far", durationMs = 271_000).passesReplacementIdentityGate(original))
        assertFalse(track("qqmusic:blank", title = "").passesReplacementIdentityGate(original))
        assertFalse(track("qqmusic:unknown", durationMs = null).passesReplacementIdentityGate(original))
        assertTrue(
            track("qqmusic:ok").passesReplacementIdentityGate(original.copy(durationMs = null)),
        )
    }

    @Test
    fun versionClassifierAndCompatibilityKeepVariantBoundary() {
        val live = track("qqmusic:live", title = "Song (Live)")
        val remaster = track("qqmusic:remaster", title = "Song Remastered 2024")

        assertEquals(ReplacementVersionKind.Live, classifyReplacementVersion(live))
        assertEquals(ReplacementVersionKind.Remaster, classifyReplacementVersion(remaster))
        assertEquals(
            0.45,
            replacementVersionCompatibility(ReplacementVersionKind.StudioOriginal, ReplacementVersionKind.Live),
        )
        assertEquals(
            0.90,
            replacementVersionCompatibility(ReplacementVersionKind.StudioOriginal, ReplacementVersionKind.Remaster),
        )
    }

    @Test
    fun identitySignalsScoreTitleArtistDurationAndAlbum() {
        val original = track("netease:origin", title = "晴天", durationMs = 200_000).copy(
            artists = "周杰伦",
            album = "叶惠美",
        )
        val close = track("qqmusic:close", title = "晴天", durationMs = 198_000).copy(
            artists = "周杰伦",
            album = "叶惠美",
        )
        val far = track("qqmusic:far", title = "另一首歌", durationMs = 320_000).copy(
            artists = "路人甲",
            album = "合集",
        )

        val closeSignals = replacementIdentitySignals(original, close)
        val farSignals = replacementIdentitySignals(original, far)

        assertEquals(1.0, closeSignals.titleScore)
        assertEquals(1.0, closeSignals.artistScore)
        assertEquals(1.0, closeSignals.albumScore)
        assertTrue(closeSignals.durationScore > 0.9)
        assertTrue(closeSignals.lexicalScore > farSignals.lexicalScore)
        assertTrue(
            replacementRelativeDurationScore(200_000, 198_000) >
                replacementRelativeDurationScore(200_000, 320_000),
        )
    }

    @Test
    fun rankingHeadMixesDurationVersionAndQualityWithSemantic() {
        val original = track("netease:origin", title = "晴天", durationMs = 200_000).copy(
            artists = "周杰伦",
            album = "叶惠美",
        )
        val close = track("qqmusic:close", title = "晴天", durationMs = 198_000).copy(
            artists = "周杰伦",
            album = "叶惠美",
        )
        val farDuration = track("qqmusic:far-duration", title = "晴天", durationMs = 320_000).copy(
            artists = "周杰伦",
            album = "叶惠美",
        )
        val official = track("qqmusic:official", title = "晴天 官方完整版 无损", durationMs = 198_000).copy(
            artists = "周杰伦",
            album = "叶惠美",
        )
        val live = track("qqmusic:live", title = "晴天 Live", durationMs = 198_000).copy(
            artists = "周杰伦",
            album = "叶惠美",
        )

        val closeScore = mixedScore(original, close, semantic = 0.80)
        val farDurationScore = mixedScore(original, farDuration, semantic = 0.80)
        val officialScore = mixedScore(original, official, semantic = 0.80)
        val liveScore = mixedScore(original, live, semantic = 0.80)

        assertTrue(closeScore > farDurationScore)
        assertTrue(officialScore > closeScore)
        assertTrue(closeScore > liveScore)
        assertEquals(1.0, replacementQualitySignals(official).official)
        assertEquals(1.0, replacementQualitySignals(official).complete)
        assertEquals(1.0, replacementQualitySignals(official).quality)
    }

    @Test
    fun onDeviceRankingMixesStructuredFeaturesWithSemantic() = runTest {
        val original = track("netease:origin", title = "晴天", durationMs = 200_000).copy(
            artists = "周杰伦",
            album = "叶惠美",
        )
        val close = ReplacementCandidate(
            track = track("qqmusic:close", title = "晴天", durationMs = 198_000).copy(
                artists = "周杰伦",
                album = "叶惠美",
            ),
            score = 0.60,
        )
        val far = ReplacementCandidate(
            track = track("qqmusic:far", title = "另一首歌", durationMs = 198_000).copy(
                artists = "路人甲",
                album = "合集",
            ),
            score = 0.90,
        )
        val request = ReplacementRankingRequest(
            original = original,
            candidates = listOf(far, close),
            mode = ReplacementRankingMode.OnDevice,
            strictness = SmartReplacementStrictness.Balanced,
            modelTier = ReplacementModelTier.Lite,
        )
        val modelRanker = object : ReplacementRanker {
            override suspend fun rank(request: ReplacementRankingRequest): List<ReplacementCandidate> =
                request.candidates.map { candidate ->
                    val score = if (candidate.track.id == "qqmusic:close") 0.90 else 0.50
                    candidate.copy(
                        score = score,
                        modelScore = score,
                        sameSongConfidence = score,
                    )
                }
        }

        val onDevice = rankReplacementCandidatesWithFallback(request, modelRanker)
        val legacy = rankReplacementCandidatesWithFallback(
            request.copy(mode = ReplacementRankingMode.Legacy),
            modelRanker,
        )
        val expectedClose = mixedScore(original, close.track, semantic = 0.90)

        assertEquals("qqmusic:close", onDevice.first().track.id)
        assertEquals(expectedClose, onDevice.first().score)
        assertEquals(0.90, onDevice.first().modelScore)
        assertEquals(expectedClose, onDevice.first().sameSongConfidence)
        assertTrue(onDevice.first().autoEligible)
        assertTrue(onDevice.first().score > onDevice.last().score)
        assertTrue(onDevice.first().reasons.any { "歌名相近" in it && "时长接近" in it })
        assertEquals("qqmusic:far", legacy.first().track.id)
        assertEquals(ReplacementRankingStrategy.LegacyRules, legacy.first().rankingStrategy)
    }

    private fun rankingRequest(): ReplacementRankingRequest = ReplacementRankingRequest(
        original = track("netease:origin"),
        candidates = listOf(
            ReplacementCandidate(track("qqmusic:low"), 0.6),
            ReplacementCandidate(track("qqmusic:high"), 0.9),
        ),
        mode = ReplacementRankingMode.OnDevice,
        strictness = SmartReplacementStrictness.Balanced,
        modelTier = ReplacementModelTier.Lite,
    )

    private fun mixedScore(
        original: MusicTrack,
        candidate: MusicTrack,
        semantic: Double,
    ): Double = mixReplacementRankingScore(
        replacementPairFeatures(
            original = original,
            candidate = candidate,
            semantic = semantic,
            versionCompatibility = replacementVersionCompatibility(
                original = classifyReplacementVersion(original),
                candidate = classifyReplacementVersion(candidate),
            ),
        ),
    )

    private fun track(
        id: String,
        title: String = "Song",
        durationMs: Long? = 200_000,
    ): MusicTrack = MusicTrack(
        id = id,
        title = title,
        artists = "Artist",
        album = "Album",
        source = id.substringBefore(':'),
        sourceType = TrackSourceType.Provider,
        durationMs = durationMs,
        providerId = id,
    )
}
