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
    fun lowConfidenceKeepsModelRankingButIsNotAutoEligible() = runTest {
        val ranked = rankReplacementCandidatesWithFallback(
            request = rankingRequest(),
            ranker = modelRanker(confidence = 0.4),
        )

        assertEquals("qqmusic:low", ranked.first().track.id)
        assertEquals(ReplacementRankingStrategy.OnDeviceLite, ranked.first().rankingStrategy)
        assertTrue(ranked.none { it.autoEligible })
    }

    @Test
    fun closeTopCandidateScoresKeepTheHighestModelResult() = runTest {
        val ranked = rankReplacementCandidatesWithFallback(
            request = rankingRequest(),
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

        assertEquals("qqmusic:low", ranked.first().track.id)
        assertEquals(ReplacementRankingStrategy.OnDeviceLite, ranked.first().rankingStrategy)
        assertTrue(ranked.first().autoEligible)
    }

    @Test
    fun modelCanRerankCandidatesButCannotBypassIdentityGate() = runTest {
        val request = rankingRequest().let { original ->
            original.copy(
                candidates = original.candidates.mapIndexed { index, candidate ->
                    candidate.copy(autoEligible = index == 0)
                },
            )
        }
        val ranked = rankReplacementCandidatesWithFallback(
            request = request,
            ranker = modelRanker(confidence = 0.95),
        )

        assertEquals("qqmusic:low", ranked.first().track.id)
        assertTrue(ranked.first().autoEligible)
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
    fun studioOriginalSystemPolicyPrefersOfficialCompleteCandidates() {
        val original = track("netease:origin")
        val plain = track("qqmusic:plain")
        val official = track(
            id = "qqmusic:official",
            title = "Song 官方完整版",
        )
        val lossless = track(
            id = "qqmusic:lossless",
            title = "Song 无损",
        )

        assertEquals(0.0, replacementSystemPreferenceAdjustment(original, plain))
        assertTrue(replacementSystemPreferenceAdjustment(original, official) > 0.0)
        assertTrue(replacementSystemPreferenceAdjustment(original, lossless) > 0.0)
        assertEquals(
            0.0,
            replacementSystemPreferenceAdjustment(
                original.copy(title = "Song Live"),
                official,
            ),
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
        assertEquals(
            listOf("歌名相近 · 歌手相近 · 时长接近 · 专辑相近"),
            closeSignals.reasonLabels(),
        )
        assertTrue(blendOnDeviceReplacementScore(0.8, closeSignals) > blendOnDeviceReplacementScore(0.8, farSignals))
    }

    @Test
    fun onDeviceRankingBlendsLexicalIdentityWithoutChangingLegacyOrder() = runTest {
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
            track = track("qqmusic:far", title = "另一首歌", durationMs = 320_000).copy(
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
        val evenSemanticRanker = object : ReplacementRanker {
            override suspend fun rank(request: ReplacementRankingRequest): List<ReplacementCandidate> =
                request.candidates.map { candidate ->
                    candidate.copy(
                        score = 0.80,
                        modelScore = 0.80,
                        sameSongConfidence = 0.80,
                    )
                }
        }

        val onDevice = rankReplacementCandidatesWithFallback(request, evenSemanticRanker)
        val legacy = rankReplacementCandidatesWithFallback(
            request.copy(mode = ReplacementRankingMode.Legacy),
            evenSemanticRanker,
        )

        assertEquals("qqmusic:close", onDevice.first().track.id)
        assertEquals(0.80, onDevice.first().modelScore)
        assertEquals(0.80, onDevice.first().sameSongConfidence)
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

    private fun modelRanker(confidence: Double): ReplacementRanker = object : ReplacementRanker {
        override suspend fun rank(request: ReplacementRankingRequest): List<ReplacementCandidate> =
            request.candidates.mapIndexed { index, candidate ->
                candidate.copy(
                    score = if (index == 0) 0.95 else 0.5,
                    modelScore = if (index == 0) 0.95 else 0.5,
                    sameSongConfidence = confidence,
                    rankingStrategy = ReplacementRankingStrategy.OnDeviceLite,
                )
            }
    }

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
