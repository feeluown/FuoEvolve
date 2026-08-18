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
    fun higherLegacyScoreStillWinsWhenRecordingStructureIsEquivalent() {
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
    fun studioVersionBeatsHigherScoredOfficialLiveUpload() {
        val original = track(
            source = "netease",
            title = "学不会",
            artists = "林俊杰",
            durationMs = 240_000,
        )
        val officialLive = track(
            source = "bilibili",
            title = "学不会 LIVE",
            artists = "林俊杰",
            durationMs = 240_000,
            id = "live",
        )
        val studioReupload = track(
            source = "bilibili",
            title = "林俊杰 - 学不会 Hi-Res",
            artists = "音乐搬运",
            durationMs = 240_000,
            id = "studio",
        )
        val liveScore = bilibiliReplacementScore(original, officialLive)
        val studioScore = bilibiliReplacementScore(original, studioReupload)

        assertTrue(liveScore > studioScore)

        val sorted = sortReplacementScoreTies(
            original,
            listOf(
                ReplacementCandidate(officialLive, liveScore),
                ReplacementCandidate(studioReupload, studioScore),
            ),
        )

        assertEquals("studio", sorted.first().track.id)
    }

    @Test
    fun fullCollaborationBeatsHigherScoredSingleArtistUpload() {
        val original = track(
            source = "netease",
            title = "雀跃",
            artists = "任然 / 小来",
            durationMs = 215_000,
        )
        val officialSolo = track(
            source = "bilibili",
            title = "雀跃",
            artists = "任然",
            durationMs = 215_000,
            id = "solo",
        )
        val duetReupload = track(
            source = "bilibili",
            title = "任然 / 小来 - 雀跃 Hi-Res",
            artists = "音乐搬运",
            durationMs = 215_000,
            id = "duet",
        )
        val soloScore = bilibiliReplacementScore(original, officialSolo)
        val duetScore = bilibiliReplacementScore(original, duetReupload)

        assertTrue(soloScore > duetScore)

        val sorted = sortReplacementScoreTies(
            original,
            listOf(
                ReplacementCandidate(officialSolo, soloScore),
                ReplacementCandidate(duetReupload, duetScore),
            ),
        )

        assertEquals("duet", sorted.first().track.id)
    }

    @Test
    fun explicitVersionOriginStillPrefersSameVersion() {
        val original = track("netease", "学不会 Live", "林俊杰", durationMs = 240_000)
        val matchingLive = ReplacementCandidate(
            track("bilibili", "学不会 LIVE", "林俊杰", durationMs = 240_000, id = "live"),
            0.75,
        )
        val studio = ReplacementCandidate(
            track("bilibili", "林俊杰 - 学不会", "音乐搬运", durationMs = 240_000, id = "studio"),
            0.90,
        )

        val sorted = sortReplacementScoreTies(original, listOf(studio, matchingLive))

        assertEquals("live", sorted.first().track.id)
    }

    @Test
    fun candidateAlbumVersionMarkerPreventsFalseStudioMatch() {
        val original = track("netease", "Song", "Alice")
        val albumMarkedLive = ReplacementCandidate(
            track(
                source = "ytmusic",
                title = "Song",
                artists = "Alice",
                album = "Live at Arena",
                id = "album-live",
            ),
            0.90,
        )
        val studio = ReplacementCandidate(
            track("qqmusic", "Song", "Alice", id = "studio"),
            0.80,
        )

        val sorted = sortReplacementScoreTies(original, listOf(albumMarkedLive, studio))

        assertEquals("studio", sorted.first().track.id)
    }

    @Test
    fun candidateTagsAreCombinedWithTitleVersionMarkers() {
        val original = track("netease", "Song Live", "Alice")
        val liveCover = ReplacementCandidate(
            track(
                source = "bilibili",
                title = "Song LIVE",
                artists = "Alice",
                id = "live-cover",
                providerTags = listOf("翻唱"),
            ),
            0.90,
        )
        val matchingLive = ReplacementCandidate(
            track("bilibili", "Song LIVE", "Alice", id = "live"),
            0.80,
        )

        val sorted = sortReplacementScoreTies(original, listOf(liveCover, matchingLive))

        assertEquals("live", sorted.first().track.id)
    }

    @Test
    fun yoasobiOfficialOriginalStillBeatsTaggedCover() {
        val original = track(
            source = "netease",
            title = "ハルジオン",
            artists = "YOASOBI",
            durationMs = 198_000,
        )
        val official = track(
            source = "bilibili",
            title = "YOASOBI ハルジオン(Halzion) Official Music Video",
            artists = "Ayase-YOASOBI",
            durationMs = 203_000,
            id = "official",
        )
        val cover = track(
            source = "bilibili",
            title = "【七海】ハルジオン／春紫菀 【YOASOBI】（人声增强）",
            artists = "七海Nana7mi",
            durationMs = 197_000,
            id = "cover",
            providerTags = listOf("YOASOBI", "ハルジオン", "翻唱"),
        )

        val sorted = sortReplacementScoreTies(
            original,
            listOf(
                ReplacementCandidate(cover, bilibiliReplacementScore(original, cover)),
                ReplacementCandidate(official, bilibiliReplacementScore(original, official)),
            ),
        )

        assertEquals("official", sorted.first().track.id)
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
        providerTags: List<String> = emptyList(),
    ): MusicTrack = MusicTrack(
        id = id,
        title = title,
        artists = artists,
        album = album,
        source = source,
        sourceType = TrackSourceType.Provider,
        durationMs = durationMs,
        providerId = id,
        providerTags = providerTags,
    )
}
