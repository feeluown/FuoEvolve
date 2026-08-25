package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlaybackSmartReplacementPolicyTest {
    @Test
    fun bilibiliScoreStillMatchesTitleArtistAndQualityKeywords() {
        val origin = track("netease", "Night Song", "Alice")
        val candidate = track("bilibili", "ALICE - night song Hi-Res MV", "Uploader")
        assertEquals(0.69, bilibiliReplacementScore(origin, candidate), 0.0001)
    }

    @Test
    fun bilibiliScoreRewardsUploaderArtistMatchMoreThanTitleMention() {
        val origin = track("netease", "人间芳菲", "音阙诗听")
        val officialUploader = track("bilibili", "人间芳菲", "音阙诗听")
        val titleMentionOnly = track("bilibili", "音阙诗听 人间芳菲", "Uploader")
        assertEquals(0.85, bilibiliReplacementScore(origin, officialUploader), 0.0001)
        assertTrue(bilibiliReplacementScore(origin, officialUploader) > bilibiliReplacementScore(origin, titleMentionOnly))
    }

    @Test
    fun bilibiliScorePenalizesCoverVersionConflict() {
        val origin = track("netease", "晴天", "周杰伦")
        val candidate = track("bilibili", "周杰伦 晴天 Cover 翻唱", "Uploader")
        assertEquals(0.42, bilibiliReplacementScore(origin, candidate), 0.0001)
    }

    @Test
    fun bilibiliScoreKeepsMatchingRemixVersion() {
        val origin = track("netease", "晴天 Remix", "周杰伦")
        val candidate = track("bilibili", "周杰伦 晴天 REMIX", "Uploader")
        assertEquals(0.67, bilibiliReplacementScore(origin, candidate), 0.0001)
    }

    @Test
    fun bilibiliScoreTreatsDurationAsSupportingEvidence() {
        val origin = track("netease", "Night Song", "Alice", durationMs = 200_000)
        val candidate = track("bilibili", "ALICE - night song Hi-Res MV", "Uploader", durationMs = 300_000)
        assertEquals(0.64, bilibiliReplacementScore(origin, candidate), 0.0001)
    }

    @Test
    fun bilibiliScorePrefersYoasobiOfficialVideoOverTaggedCover() {
        val origin = track("netease", "ハルジオン", "YOASOBI", durationMs = 198_000)
        val official = track(
            "bilibili", "YOASOBI ハルジオン(Halzion) Official Music Video", "Ayase-YOASOBI", durationMs = 203_000,
        )
        val cover = track(
            "bilibili", "【七海】ハルジオン／春紫菀 【YOASOBI】（人声增强）", "七海Nana7mi",
            durationMs = 197_000, providerTags = listOf("YOASOBI", "ハルジオン", "翻唱"),
        )
        assertEquals(0.965, bilibiliReplacementScore(origin, official), 0.0001)
        assertEquals(0.47, bilibiliReplacementScore(origin, cover), 0.0001)
        assertTrue(bilibiliReplacementScore(origin, official) > bilibiliReplacementScore(origin, cover))
    }

    @Test
    fun bilibiliScoreUsesTitleVersionBeforeConflictingTags() {
        val origin = track("netease", "Night Song Live", "Alice", durationMs = 200_000)
        val candidate = track("bilibili", "Alice Night Song Live", "Alice Official", durationMs = 201_000)
        val candidateWithNoisyTags = candidate.copy(providerTags = listOf("翻唱", "音乐"))
        assertEquals(
            bilibiliReplacementScore(origin, candidate),
            bilibiliReplacementScore(origin, candidateWithNoisyTags),
            0.0001,
        )
    }

    @Test
    fun bilibiliScorePreservesCoverOriginVersion() {
        val origin = track("netease", "ハルジオン (Cover)", "Aimer", durationMs = 198_000)
        val matchingCover = track("bilibili", "Aimer ハルジオン 歌ってみた", "Aimer Official", durationMs = 199_000)
        val originalArtistOfficial = track(
            "bilibili", "YOASOBI ハルジオン Official Music Video", "Ayase-YOASOBI", durationMs = 203_000,
        )
        assertEquals(0.87, bilibiliReplacementScore(origin, matchingCover), 0.0001)
        assertEquals(0.415, bilibiliReplacementScore(origin, originalArtistOfficial), 0.0001)
        assertTrue(bilibiliReplacementScore(origin, matchingCover) > bilibiliReplacementScore(origin, originalArtistOfficial))
    }

    @Test
    fun smartReplacementTreatsMultipartCandidateAsSingleQueueTrack() {
        val original = track("netease", "Night Song", "Alice", id = "netease:night-song")
        val candidate = track("bilibili", "Night Song", "Alice", id = "bilibili:BV1multi")
        val multipartPayload = PlaybackPayload(
            url = "https://example.test/p1.m4a",
            title = candidate.title,
            artists = candidate.artists,
            album = candidate.album,
            source = candidate.source,
            parts = listOf(
                PlaybackPart("bilibili:paged_BV1multi__1", "P1", 200_000),
                PlaybackPart("bilibili:paged_BV1multi__2", "P2", 180_000),
            ),
            currentPartIndex = 0,
        )
        val annotated = annotateSmartReplacement(
            payload = multipartPayload,
            original = original,
            candidate = candidate,
            score = 0.9,
            useOriginalMetadata = true,
            useOriginalLyrics = true,
        )
        assertEquals("https://example.test/p1.m4a", annotated.url)
        assertTrue(annotated.parts.isEmpty())
        assertEquals(-1, annotated.currentPartIndex)
        assertTrue(annotated.isSmartReplacement)
        assertEquals(original.id, annotated.originalId)
        assertEquals(candidate.id, annotated.replacementId)
    }

    @Test
    fun replacementSelectionResolvesInScoreOrderAndStopsAtFirstPlayableCandidate() = runTest {
        val low = track("bilibili", "Low", "Uploader", id = "low")
        val medium = track("ytmusic", "Medium", "Artist", id = "medium")
        val high = track("qqmusic", "High", "Artist", id = "high")
        val scores = mapOf(low.id to 0.40, medium.id to 0.80, high.id to 0.95)
        val attempts = mutableListOf<String>()
        val selected = selectReplacementCandidate(
            candidates = listOf(low, medium, high),
            minScore = 0.55,
            scoreOf = { scores.getValue(it.id) },
            resolve = { candidate ->
                attempts += candidate.id
                when (candidate.id) {
                    high.id -> null
                    medium.id -> "playable"
                    else -> error("below-threshold candidate should not be resolved")
                }
            },
        )
        assertNotNull(selected)
        assertEquals(listOf(high.id, medium.id), attempts)
        assertEquals(medium.id, selected.first.id)
        assertEquals(0.80, selected.second, 0.0001)
        assertEquals("playable", selected.third)
    }

    @Test
    fun replacementCandidatesAreDeduplicatedFilteredAndStableForEqualScores() {
        val duplicateFirst = track("qqmusic", "First result", "Artist", id = "duplicate")
        val duplicateLater = track("qqmusic", "Later result", "Artist", id = "duplicate")
        val tieFirst = track("bilibili", "Tie first", "Artist", id = "tie-first")
        val tieSecond = track("ytmusic", "Tie second", "Artist", id = "tie-second")
        val ranked = rankReplacementCandidates(
            candidates = listOf(
                track("qqmusic", "Below threshold", "Artist", id = "below"),
                tieFirst, duplicateFirst, tieSecond, duplicateLater,
                track("bilibili", "Best", "Artist", id = "best"),
            ),
            minScore = 0.5,
            scoreOf = { candidate ->
                mapOf("below" to 0.4, "tie-first" to 0.8, "tie-second" to 0.8, "duplicate" to 0.7, "best" to 0.95)
                    .getValue(candidate.id)
            },
        )
        assertEquals(listOf("best", "tie-first", "tie-second", "duplicate"), ranked.map { it.track.id })
        assertEquals("First result", ranked.last().track.title)
    }

    @Test
    fun rankedCandidateResolverDoesNotTryCandidatesAfterFirstPlayableResult() = runTest {
        val first = ReplacementCandidate(track("qqmusic", "First", "Artist", id = "first"), 0.9)
        val second = ReplacementCandidate(track("bilibili", "Second", "Artist", id = "second"), 0.8)
        val attempts = mutableListOf<String>()
        val selected = selectRankedReplacementCandidate(
            candidates = listOf(first, second),
            resolve = { candidate -> attempts += candidate.id; "payload:${candidate.id}" },
        )
        assertEquals(listOf("first"), attempts)
        assertEquals("first", selected?.first?.id)
    }

    private fun track(
        source: String,
        title: String,
        artists: String,
        durationMs: Long? = null,
        id: String = "$source:$title",
        providerTags: List<String> = emptyList(),
    ): MusicTrack = MusicTrack(
        id = id,
        title = title,
        artists = artists,
        album = "",
        source = source,
        sourceType = TrackSourceType.Provider,
        durationMs = durationMs,
        providerId = id,
        providerTags = providerTags,
    )
}
