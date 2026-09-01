@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.feeluown.mobile

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackLyricsReplacementAlignmentTest {
    @Test
    fun nativeLyricsOffsetUsesActualReplacementSource() = runTest {
        val original = providerTrack("netease:original", "原曲", "netease")
        val source = replacementSource("ytmusic:replacement-a")
        val rememberedOffsets = mutableListOf<Pair<String, Long>>()
        var currentLyrics: String? = null
        val controller = PlaybackLyricsController(
            repository = FakePlaybackLyricsRepository(
                lyrics = mapOf(original.id to "[00:00.00]原曲歌词"),
            ),
            scope = this,
            currentRequestSerial = { 1L },
            currentTrackId = { original.id },
            currentLyrics = { currentLyrics },
            updateLyrics = { currentLyrics = it },
            associationForTrackId = { null },
            rememberAssociation = { _, _ -> },
            rememberAlignmentOffset = { key, offsetMs -> rememberedOffsets += key to offsetMs },
            currentResolvedSource = { source },
        )

        controller.maybeLoad(original)
        advanceUntilIdle()
        controller.updateAlignmentOffset(750L)

        assertEquals("[00:00.00]原曲歌词", currentLyrics)
        assertEquals(
            listOf(lyricsAlignmentPersistenceKey("ytmusic:replacement-a", original.id) to 750L),
            rememberedOffsets,
        )
        assertEquals(750L, controller.associationState.value.alignmentOffsetMs)
    }

    @Test
    fun switchingReplacementSourceRestoresEachSourcesOffset() = runTest {
        val original = providerTrack("netease:original", "原曲", "netease")
        var currentSource = replacementSource("ytmusic:replacement-a")
        var currentLyrics: String? = null
        val savedOffsets = mapOf(
            lyricsAlignmentPersistenceKey("ytmusic:replacement-a", original.id) to 750L,
            lyricsAlignmentPersistenceKey("qqmusic:replacement-b", original.id) to -500L,
        )
        val controller = PlaybackLyricsController(
            repository = FakePlaybackLyricsRepository(
                lyrics = mapOf(original.id to "[00:00.00]原曲歌词"),
            ),
            scope = this,
            currentRequestSerial = { 1L },
            currentTrackId = { original.id },
            currentLyrics = { currentLyrics },
            updateLyrics = { currentLyrics = it },
            associationForTrackId = { null },
            rememberAssociation = { _, _ -> },
            alignmentOffsetForTrackId = { savedOffsets[it] ?: 0L },
            currentResolvedSource = { currentSource },
        )

        controller.maybeLoad(original)
        advanceUntilIdle()
        assertEquals(750L, controller.associationState.value.alignmentOffsetMs)

        currentSource = replacementSource("qqmusic:replacement-b")
        controller.maybeLoad(original)
        advanceUntilIdle()

        assertEquals(-500L, controller.associationState.value.alignmentOffsetMs)
    }

    @Test
    fun manualAssociationOnReplacementAlsoUsesActualPlaybackSource() = runTest {
        val original = providerTrack("netease:original", "原曲", "netease")
        val source = replacementSource("ytmusic:replacement-a")
        val associated = providerTrack("qqmusic:lyrics", "歌词歌曲", "qqmusic")
        val rememberedOffsets = mutableListOf<Pair<String, Long>>()
        val alignmentKey = lyricsAlignmentPersistenceKey("ytmusic:replacement-a", associated.id)
        var currentLyrics: String? = null
        val controller = PlaybackLyricsController(
            repository = FakePlaybackLyricsRepository(
                details = mapOf(associated.id to associated),
                lyrics = mapOf(associated.id to "[00:00.00]关联歌词"),
            ),
            scope = this,
            currentRequestSerial = { 1L },
            currentTrackId = { original.id },
            currentLyrics = { currentLyrics },
            updateLyrics = { currentLyrics = it },
            associationForTrackId = { if (it == original.id) associated.id else null },
            rememberAssociation = { _, _ -> },
            alignmentOffsetForTrackId = { if (it == alignmentKey) 1_000L else 0L },
            rememberAlignmentOffset = { key, offsetMs -> rememberedOffsets += key to offsetMs },
            currentResolvedSource = { source },
        )

        controller.maybeLoad(original)
        advanceUntilIdle()

        assertTrue(controller.associationState.value.isManualAssociation)
        assertEquals(1_000L, controller.associationState.value.alignmentOffsetMs)

        controller.updateAlignmentOffset(-250L)

        assertEquals(listOf(alignmentKey to -250L), rememberedOffsets)
    }

    private fun providerTrack(id: String, title: String, source: String) = MusicTrack(
        id = id,
        title = title,
        artists = "artist",
        album = "",
        source = source,
        sourceType = TrackSourceType.Provider,
        providerId = id,
        providerName = source,
    )

    private fun replacementSource(id: String) = ResolvedPlaybackSource(
        trackId = id,
        title = "替代音源",
        artists = "artist",
        album = "",
        source = id.substringBefore(':'),
        sourceType = TrackSourceType.Provider,
        providerName = id.substringBefore(':'),
        isReplacement = true,
    )

    private class FakePlaybackLyricsRepository(
        private val details: Map<String, MusicTrack> = emptyMap(),
        private val lyrics: Map<String, String> = emptyMap(),
    ) : PlaybackLyricsRepository {
        override suspend fun lyrics(track: MusicTrack): String? = lyrics[track.id]
        override suspend fun search(keyword: String): List<MusicTrack> = emptyList()
        override suspend fun trackDetail(trackId: String): MusicTrack? = details[trackId]
        override suspend fun searchKeyword(track: MusicTrack): String? = null
    }
}
