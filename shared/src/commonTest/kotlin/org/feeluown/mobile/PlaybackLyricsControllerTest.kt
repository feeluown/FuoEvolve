package org.feeluown.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackLyricsControllerTest {
    @Test
    fun rememberedAssociationLoadsBeforeNativeLyrics() = runTest {
        val source = providerTrack("bilibili:BVdemo", "视频标题", "bilibili")
        val target = providerTrack("netease:123", "歌词歌曲", "netease")
        val repository = FakePlaybackLyricsRepository(
            details = mapOf(target.id to target),
            lyrics = mapOf(
                target.id to "[00:00.00]关联歌词",
                source.id to "[00:00.00]不应优先使用",
            ),
        )
        var currentLyrics: String? = null
        val controller = PlaybackLyricsController(
            repository = repository,
            scope = this,
            currentRequestSerial = { 1L },
            currentTrackId = { source.id },
            currentLyrics = { currentLyrics },
            updateLyrics = { currentLyrics = it },
            associationForTrackId = { if (it == source.id) target.id else null },
            rememberAssociation = { _, _ -> },
        )

        controller.maybeLoad(source)
        advanceUntilIdle()

        assertEquals("[00:00.00]关联歌词", currentLyrics)
        assertEquals(target.id, controller.associationState.value.associatedTrackId)
        assertTrue(controller.associationState.value.isManualAssociation)
        assertEquals(listOf(target.id), repository.lyricRequests)
    }

    @Test
    fun unavailableLyricsCanSearchSelectAndRememberAssociation() = runTest {
        val source = providerTrack("bilibili:BVdemo", "视频标题", "bilibili")
        val target = providerTrack("qqmusic:42", "BGM 歌曲", "qqmusic")
        val repository = FakePlaybackLyricsRepository(
            searchKeyword = "BGM 歌曲",
            searchResults = listOf(target),
            lyrics = mapOf(target.id to "[00:00.00]目标歌词"),
        )
        var currentLyrics: String? = null
        var remembered: Pair<String, String?>? = null
        val controller = PlaybackLyricsController(
            repository = repository,
            scope = this,
            currentRequestSerial = { 7L },
            currentTrackId = { source.id },
            currentLyrics = { currentLyrics },
            updateLyrics = { currentLyrics = it },
            associationForTrackId = { null },
            rememberAssociation = { sourceId, targetId -> remembered = sourceId to targetId },
        )

        controller.maybeLoad(source)
        advanceUntilIdle()
        assertTrue(controller.associationState.value.isLyricsUnavailable)

        controller.openAssociationSearch(source)
        advanceUntilIdle()
        assertEquals("BGM 歌曲", controller.associationState.value.query)
        assertEquals(listOf(target), controller.associationState.value.results)

        controller.selectAssociation(target)
        advanceUntilIdle()

        assertEquals(source.id to target.id, remembered)
        assertEquals("[00:00.00]目标歌词", currentLyrics)
        assertTrue(controller.associationState.value.isManualAssociation)
        assertFalse(controller.associationState.value.isSearchOpen)
    }

    @Test
    fun rememberedAssociationIsPreservedWhenLookupFails() = runTest {
        val source = providerTrack("bilibili:BVdemo", "视频标题", "bilibili")
        val repository = FakePlaybackLyricsRepository()
        val rememberedCalls = mutableListOf<Pair<String, String?>>()
        val controller = PlaybackLyricsController(
            repository = repository,
            scope = this,
            currentRequestSerial = { 1L },
            currentTrackId = { source.id },
            currentLyrics = { null },
            updateLyrics = {},
            associationForTrackId = { if (it == source.id) "netease:missing" else null },
            rememberAssociation = { sourceId, targetId -> rememberedCalls += sourceId to targetId },
        )

        controller.maybeLoad(source)
        advanceUntilIdle()

        assertTrue(controller.associationState.value.isLyricsUnavailable)
        assertTrue(rememberedCalls.isEmpty())
    }

    @Test
    fun userQueryIsNotOverwrittenWhileDefaultKeywordLoads() = runTest {
        val source = providerTrack("bilibili:BVdemo", "视频标题", "bilibili")
        val keywordGate = CompletableDeferred<String?>()
        val repository = FakePlaybackLyricsRepository(searchKeywordGate = keywordGate)
        val controller = PlaybackLyricsController(
            repository = repository,
            scope = this,
            currentRequestSerial = { 1L },
            currentTrackId = { source.id },
            currentLyrics = { null },
            updateLyrics = {},
            associationForTrackId = { null },
            rememberAssociation = { _, _ -> },
        )

        controller.openAssociationSearch(source)
        runCurrent()
        controller.updateAssociationQuery("用户输入")
        keywordGate.complete("异步 BGM 标题")
        advanceUntilIdle()

        assertEquals("用户输入", controller.associationState.value.query)
        assertFalse(controller.associationState.value.isSearching)
        assertTrue(repository.searchRequests.isEmpty())
    }

    @Test
    fun searchFallsBackToTrackTitleWhenProviderHasNoHint() = runTest {
        val source = providerTrack("other:1", "原始标题", "other")
        val repository = FakePlaybackLyricsRepository(
            searchResults = emptyList(),
        )
        val controller = PlaybackLyricsController(
            repository = repository,
            scope = this,
            currentRequestSerial = { 1L },
            currentTrackId = { source.id },
            currentLyrics = { null },
            updateLyrics = {},
            associationForTrackId = { null },
            rememberAssociation = { _, _ -> },
        )

        controller.openAssociationSearch(source)
        advanceUntilIdle()

        assertEquals("原始标题", controller.associationState.value.query)
        assertEquals(listOf("原始标题"), repository.searchRequests)
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

    private class FakePlaybackLyricsRepository(
        private val details: Map<String, MusicTrack> = emptyMap(),
        private val lyrics: Map<String, String> = emptyMap(),
        private val searchKeyword: String? = null,
        private val searchResults: List<MusicTrack> = emptyList(),
        private val searchKeywordGate: CompletableDeferred<String?>? = null,
    ) : PlaybackLyricsRepository {
        val lyricRequests = mutableListOf<String>()
        val searchRequests = mutableListOf<String>()

        override suspend fun lyrics(track: MusicTrack): String? {
            lyricRequests += track.id
            return lyrics[track.id]
        }

        override suspend fun search(keyword: String): List<MusicTrack> {
            searchRequests += keyword
            return searchResults
        }

        override suspend fun trackDetail(trackId: String): MusicTrack? = details[trackId]

        override suspend fun searchKeyword(track: MusicTrack): String? =
            searchKeywordGate?.await() ?: searchKeyword
    }
}
