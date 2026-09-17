package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlaylistMigrationCoordinatorTest {
    private val source = MigrationPlaylist("playlist:netease:1", "收藏", "netease")
    private val destination = MigrationPlaylist("playlist:qqmusic:2", "收藏", "qqmusic")
    private val first = MigrationTrack("netease:1", "第一首", "歌手", providerId = "netease")
    private val second = MigrationTrack("netease:2", "第二首", "歌手", providerId = "netease")
    private val firstMatch = MigrationTrack("qqmusic:1", "第一首", "歌手", providerId = "qqmusic")
    private val secondMatch = MigrationTrack("qqmusic:2", "第二首", "歌手", providerId = "qqmusic")

    @Test
    fun loadingResumesAtLastSavedPageWithoutRepeatingEarlierPages() = runTest {
        val store = FakeStore()
        val provider = FakeProvider().apply {
            pages[0] = MigrationPage(listOf(first), 1, true)
            pages[1] = MigrationPage(listOf(second), 2, false)
            failPageOnce = 1
        }
        val coordinator = PlaylistMigrationCoordinator(store, provider)
        coordinator.initialize()
        coordinator.create("task", source, "qqmusic")
        assertEquals(MigrationPhase.Loading, coordinator.step("task").phase)
        val paused = coordinator.step("task")
        assertEquals(MigrationPhase.Paused, paused.phase)
        assertEquals(1, paused.nextOffset)
        assertEquals(listOf(0, 1), provider.requestedPages)

        val restored = PlaylistMigrationCoordinator(store, provider)
        restored.initialize()
        assertEquals(MigrationPhase.Loading, restored.retry("task").phase)
        assertEquals(MigrationPhase.Review, restored.runUntilBlocked("task").phase)
        assertEquals(listOf(0, 1, 1), provider.requestedPages)
        assertEquals(listOf(first.id, second.id), restored.tasks.value.single().entries.map { it.source.id })
    }

    @Test
    fun matchingRetriesOnlyPendingSongsAndRequiresReview() = runTest {
        val store = FakeStore()
        val provider = FakeProvider().apply {
            pages[0] = MigrationPage(listOf(first, second), 2, false)
            matches[first.id] = listOf(MigrationCandidate(firstMatch, 0.99))
            matches[second.id] = listOf(MigrationCandidate(secondMatch, 0.72))
            failMatchOnce = second.id
        }
        val coordinator = PlaylistMigrationCoordinator(store, provider)
        coordinator.initialize()
        coordinator.create("task", source, "qqmusic")
        assertEquals(MigrationPhase.Paused, coordinator.runUntilBlocked("task").phase)
        assertEquals(MigrationTrackStatus.Matched, store.load().single().entries[0].status)

        val restored = PlaylistMigrationCoordinator(store, provider)
        restored.initialize()
        restored.retry("task")
        val review = restored.runUntilBlocked("task")
        assertEquals(MigrationPhase.Review, review.phase)
        assertEquals(listOf(first.id, second.id, second.id), provider.requestedMatches)
        assertEquals(MigrationTrackStatus.NeedsReview, review.entries[1].status)
        assertFailsWith<IllegalArgumentException> { restored.confirmMatches("task") }
        restored.chooseMatch("task", 1, secondMatch)
        assertEquals(MigrationPhase.Destination, restored.confirmMatches("task").phase)
    }

    @Test
    fun anUncertainCreateCannotBeRepeatedAndCanBeResolvedByChoosingPlaylist() = runTest {
        val store = FakeStore()
        val provider = FakeProvider().apply { createFails = true }
        val coordinator = PlaylistMigrationCoordinator(store, provider)
        coordinator.initialize()
        coordinator.create("task", source, "qqmusic")
        coordinator.step("task") // Empty source -> review.
        coordinator.confirmMatches("task")
        val paused = coordinator.createDestination("task", "收藏")
        assertTrue(paused.creationAttempted)
        assertEquals(MigrationPhase.Paused, paused.phase)
        assertEquals(1, provider.createCalls)
        assertFailsWith<IllegalArgumentException> { coordinator.createDestination("task", "收藏") }

        val restored = PlaylistMigrationCoordinator(store, provider)
        restored.initialize()
        assertEquals(MigrationPhase.Writing, restored.chooseDestination("task", destination).phase)
        assertEquals(MigrationPhase.Complete, restored.runUntilBlocked("task").phase)
        assertEquals(1, provider.createCalls)
    }

    @Test
    fun anAmbiguousAddIsReconciledBeforeRetryWithoutDuplicateWrite() = runTest {
        val store = FakeStore()
        val provider = FakeProvider().apply {
            pages[0] = MigrationPage(listOf(first), 1, false)
            matches[first.id] = listOf(MigrationCandidate(firstMatch, 0.99))
            timeoutAfterAddingOnce = firstMatch.id
        }
        val coordinator = PlaylistMigrationCoordinator(store, provider)
        coordinator.initialize()
        coordinator.create("task", source, "qqmusic")
        coordinator.runUntilBlocked("task")
        coordinator.confirmMatches("task")
        coordinator.chooseDestination("task", destination)
        val partial = coordinator.runUntilBlocked("task")
        assertEquals(MigrationPhase.Partial, partial.phase)
        assertEquals(MigrationTrackStatus.Uncertain, partial.entries.single().status)
        assertEquals(1, provider.addCalls)

        val restored = PlaylistMigrationCoordinator(store, provider)
        restored.initialize()
        restored.retry("task")
        val completed = restored.runUntilBlocked("task")
        assertEquals(MigrationPhase.Complete, completed.phase)
        assertEquals(MigrationTrackStatus.Added, completed.entries.single().status)
        assertEquals(1, provider.addCalls)
    }

    @Test
    fun successfulItemsRemainCheckpointedWhenLaterWriteFails() = runTest {
        val store = FakeStore()
        val provider = FakeProvider().apply {
            pages[0] = MigrationPage(listOf(first, second), 2, false)
            matches[first.id] = listOf(MigrationCandidate(firstMatch, 0.99))
            matches[second.id] = listOf(MigrationCandidate(secondMatch, 0.99))
            rejectOnce = secondMatch.id
        }
        val coordinator = PlaylistMigrationCoordinator(store, provider)
        coordinator.initialize()
        coordinator.create("task", source, "qqmusic")
        coordinator.runUntilBlocked("task")
        coordinator.confirmMatches("task")
        coordinator.chooseDestination("task", destination)
        val partial = coordinator.runUntilBlocked("task")
        assertEquals(MigrationPhase.Partial, partial.phase)
        assertEquals(listOf(MigrationTrackStatus.Added, MigrationTrackStatus.Failed), partial.entries.map { it.status })

        val restored = PlaylistMigrationCoordinator(store, provider)
        restored.initialize()
        restored.retry("task")
        val done = restored.runUntilBlocked("task")
        assertEquals(MigrationPhase.Complete, done.phase)
        assertEquals(2, done.addedCount)
        assertEquals(3, provider.addCalls)
        assertEquals(setOf(firstMatch.id, secondMatch.id), provider.written)
    }

    private class FakeStore : PlaylistMigrationStore {
        private val saved = linkedMapOf<String, PlaylistMigrationTask>()
        override suspend fun load(): List<PlaylistMigrationTask> = saved.values.toList()
        override suspend fun save(task: PlaylistMigrationTask) { saved[task.id] = task }
    }

    private class FakeProvider : PlaylistMigrationProvider {
        val pages = mutableMapOf<Int, MigrationPage>()
        val matches = mutableMapOf<String, List<MigrationCandidate>>()
        val written = mutableSetOf<String>()
        val requestedPages = mutableListOf<Int>()
        val requestedMatches = mutableListOf<String>()
        var failPageOnce: Int? = null
        var failMatchOnce: String? = null
        var timeoutAfterAddingOnce: String? = null
        var rejectOnce: String? = null
        var createFails = false
        var createCalls = 0
        var addCalls = 0

        override suspend fun loadPage(playlist: MigrationPlaylist, offset: Int): MigrationPage {
            requestedPages += offset
            if (failPageOnce == offset) {
                failPageOnce = null
                error("网络中断")
            }
            return pages[offset] ?: MigrationPage(emptyList(), offset, false)
        }

        override suspend fun candidates(track: MigrationTrack, targetProviderId: String): List<MigrationCandidate> {
            requestedMatches += track.id
            if (failMatchOnce == track.id) {
                failMatchOnce = null
                error("网络中断")
            }
            return matches[track.id].orEmpty()
        }

        override suspend fun createPlaylist(providerId: String, name: String): MigrationPlaylist {
            createCalls++
            if (createFails) error("响应超时")
            return MigrationPlaylist("playlist:qqmusic:new", name, providerId)
        }

        override suspend fun targetTracks(playlist: MigrationPlaylist): Set<String> = written.toSet()

        override suspend fun addTrack(playlist: MigrationPlaylist, track: MigrationTrack): Boolean {
            addCalls++
            if (rejectOnce == track.id) {
                rejectOnce = null
                return false
            }
            written += track.id
            if (timeoutAfterAddingOnce == track.id) {
                timeoutAfterAddingOnce = null
                error("响应超时")
            }
            return true
        }
    }
}
