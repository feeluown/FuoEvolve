package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplacementTieBreakTest {
    @Test
    fun tieBreakDoesNotChangeLegacyScoresOrCandidateCount() {
        val original = track("netease", "Together", "Alice / Bob", album = "Album", durationMs = 180_000)
        val lessCorroborated = ReplacementCandidate(
            track("ytmusic", "Together", "Alice", album = "", durationMs = null, id = "less"),
            0.80,
        )
        val corroborated = ReplacementCandidate(
            track("qqmusic", "Together", "Bob & Alice", album = "Album", durationMs = 181_000, id = "better"),
            0.80,
        )

        val ranked = listOf(lessCorroborated, corroborated)
        val sorted = sortReplacementScoreTies(original, ranked)

        assertEquals(ranked.size, sorted.size)
        assertEquals(ranked.map { it.score }.sorted(), sorted.map { it.score }.sorted())
        assertEquals("better", sorted.first().track.id)
    }

    @Test
    fun higherLegacyScoreAlwaysWinsOverTieBreakConfidence() {
        val original = track("netease", "Song", "Alice", album = "Album", durationMs = 180_000)
        val higherLegacy = ReplacementCandidate(
            track("ytmusic", "Different", "Other", id = "higher"),
            0.81,
        )
        val lowerLegacyButPerfectMetadata = ReplacementCandidate(
            track("qqmusic", "Song", "Alice", album = "Album", durationMs = 180_000, id = "lower"),
            0.80,
        )

        val sorted = sortReplacementScoreTies(original, listOf(lowerLegacyButPerfectMetadata, higherLegacy))

        assertEquals("higher", sorted.first().track.id)
        assertEquals(0.81, sorted.first().score, 0.0001)
    }

    @Test
    fun orderedTitleSimilarityDistinguishesReorderedTextForTies() {
        val original = track("netease", "abc", "Artist")
        val ordered = track("qqmusic", "abc", "Artist", id = "ordered")
        val reordered = track("ytmusic", "cba", "Artist", id = "reordered")

        assertTrue(
            replacementTieBreakConfidence(original, ordered) >
                replacementTieBreakConfidence(original, reordered),
        )
    }

    private fun track(
        source: String,
        title: String,
        artists: String,
        album: String = "",
        durationMs: Long? = null,
        id: String = "$source:$title",
    ): MusicTrack = MusicTrack(
        id = id,
        title = title,
        artists = artists,
        album = album,
        source = source,
        sourceType = TrackSourceType.Provider,
        durationMs = durationMs,
        providerId = id,
    )
}
