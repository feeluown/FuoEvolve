package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlaylistMigrationCoordinatorTest {
    private val source = MigrationPlaylist("playlist:netease:1", "收藏", "netease")
    private val destination = MigrationPlaylist("playlist:qqmusic:2", "收藏", "qqmusic")
    private val first = MigrationTrack("netease:1", "第一首", "歌手", providerId = "netease")
    private val second = MigrationTrack("netease:2", "第二首", "歌手", providerId = "netease")
    private val firstMatch = MigrationTrack("qqmusic:1", "第一首", "歌手", providerId = "qqmusic")
    private val secondMatch = MigrationTrack("qqmusic:2", "第二首", "歌手", providerId = "qqmusic")

    @Test
    fun loadingResumesFromSavedPage() = runTest {
        val store = FakeStore()
        val provider = FakeProvider().apply {
            pages[0] = MigrationPage(listOf(first), 1, true)
            pages[1] = MigrationPage(listOf(second), 2, false)
            failPageOnce = 1
        }
        val workflow = PlaylistMigrationCoordinator(store, provider)
        workflow.initialize()
        workflow.create("task", source, "qqmusic")
        assertEquals(MigrationPhase.Loading, workflow.step("task").phase)
        val paused = workflow.step("task")
        assertEquals(MigrationPhase.Paused, paused.phase)
        assertEquals(1, paused.nextOffset)

        val restored = PlaylistMigrationCoordinator(store, provider)
        restored.initialize()
        restored.retry("task")
        assertEquals(MigrationPhase.Review, restored.runUntilBlocked("task").phase)
        assertEquals(listOf(0, 1, 1), provider.requestedPages)
        assertEquals(listOf(first.id, second.id), restored.tasks.value.single().entries.map { it.source.id })
    }

    @Test
    fun matchingRetriesOnlyPendingEntriesAndRequiresReview() = runTest {
        val store = FakeStore()
        val provider = FakeProvider().apply {
            pages[0] = MigrationPage(listOf(first, second), 2, false)
            matches[first.id] = listOf(MigrationCandidate(firstMatch, 0.99))
            matches[second.id] = listOf(MigrationCandidate(secondMatch, 0.72))
            failMatchOnce = second.id
        }
        val workflow = PlaylistMigrationCoordinator(store, provider)
        workflow.initialize()
        workflow.create("task", source, "qqmusic")
        assertEquals(MigrationPhase.Paused, workflow.runUntilBlocked("task").phase)
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
    fun ambiguousCreationRequiresReconciliationWithoutCreatingAgain() = runTest {
        val store = FakeStore()
        val provider = FakeProvider().apply { createFails = true }
        val workflow = PlaylistMigrationCoordinator(store, provider)
        workflow.initialize()
        workflow.create("task", source, "qqmusic")
        assertEquals(MigrationPhase.Review, workflow.runUntilBlocked("task").phase)
        workflow.confirmMatches("task")
        val paused = workflow.createDestination("task", "收藏")
        assertTrue(paused.creationAttempted)
        assertEquals(MigrationPhase.Paused, paused.phase)
        assertEquals(1, provider.createCalls)
        assertFailsWith<IllegalArgumentException> { workflow.createDestination("task", "收藏") }
        val restored = PlaylistMigrationCoordinator(store, provider)
        restored.initialize()
        restored.chooseDestination("task", destination)
        assertEquals(MigrationPhase.Complete, restored.runUntilBlocked("task").phase)
        assertEquals(1, provider.createCalls)
    }

    @Test
    fun uncertainAddIsReconciledBeforeRetry() = runTest {
        val store = FakeStore()
        val provider = FakeProvider().apply {
            pages[0] = MigrationPage(listOf(first), 1, false)
            matches[first.id] = listOf(MigrationCandidate(firstMatch, 0.99))
            timeoutAfterAddingOnce = firstMatch.id
        }
        val workflow = PlaylistMigrationCoordinator(store, provider)
        workflow.initialize()
        workflow.create("task", source, "qqmusic")
        workflow.runUntilBlocked("task")
        workflow.confirmMatches("task")
        workflow.chooseDestination("task", destination)
        assertEquals(MigrationTrackStatus.Uncertain, workflow.runUntilBlocked("task").entries.single().status)
        val restored = PlaylistMigrationCoordinator(store, provider)
        restored.initialize()
        restored.retry("task")
        val completed = restored.runUntilBlocked("task")
        assertEquals(MigrationPhase.Complete, completed.phase)
        assertEquals(MigrationTrackStatus.Added, completed.entries.single().status)
        assertEquals(1, provider.addCalls)
    }

    @Test
    fun writePassAttemptsRemainingTracksBeforeReportingPartial() = runTest {
        val store = FakeStore()
        val provider = FakeProvider().apply {
            pages[0] = MigrationPage(listOf(first, second), 2, false)
            matches[first.id] = listOf(MigrationCandidate(firstMatch, 0.99))
            matches[second.id] = listOf(MigrationCandidate(secondMatch, 0.99))
            rejectOnce = firstMatch.id
        }
        val workflow = PlaylistMigrationCoordinator(store, provider)
        workflow.initialize()
        workflow.create("task", source, "qqmusic")
        workflow.runUntilBlocked("task")
        workflow.confirmMatches("task")
        workflow.chooseDestination("task", destination)

        val partial = workflow.runUntilBlocked("task")

        assertEquals(MigrationPhase.Partial, partial.phase)
        assertEquals(0, partial.writePass)
        assertEquals(MigrationTrackStatus.Failed, partial.entries[0].status)
        assertEquals(MigrationTrackStatus.Added, partial.entries[1].status)
        assertEquals(2, provider.addCalls)

        workflow.retry("task")
        assertEquals(1, workflow.tasks.value.single().writePass)
        val completed = workflow.runUntilBlocked("task")
        assertEquals(MigrationPhase.Complete, completed.phase)
        assertEquals(2, completed.addedCount)
        assertEquals(3, provider.addCalls)
        assertEquals(listOf(0, 0, 1), provider.targetWritePasses)
    }

    @Test
    fun retryDoesNotRepeatSuccessfulWrites() = runTest {
        val store = FakeStore()
        val provider = FakeProvider().apply {
            pages[0] = MigrationPage(listOf(first, second), 2, false)
            matches[first.id] = listOf(MigrationCandidate(firstMatch, 0.99))
            matches[second.id] = listOf(MigrationCandidate(secondMatch, 0.99))
            rejectOnce = secondMatch.id
        }
        val workflow = PlaylistMigrationCoordinator(store, provider)
        workflow.initialize()
        workflow.create("task", source, "qqmusic")
        workflow.runUntilBlocked("task")
        workflow.confirmMatches("task")
        workflow.chooseDestination("task", destination)
        assertEquals(MigrationPhase.Partial, workflow.runUntilBlocked("task").phase)
        val restored = PlaylistMigrationCoordinator(store, provider)
        restored.initialize()
        restored.retry("task")
        val completed = restored.runUntilBlocked("task")
        assertEquals(MigrationPhase.Complete, completed.phase)
        assertEquals(2, completed.addedCount)
        assertEquals(3, provider.addCalls)
        assertEquals(setOf(firstMatch.id, secondMatch.id), provider.written)
    }

    private class FakeStore : PlaylistMigrationStore {
        private val snapshots = linkedMapOf<String, PlaylistMigrationTask>()
        override suspend fun load(): List<PlaylistMigrationTask> = snapshots.values.toList()
        override suspend fun save(task: PlaylistMigrationTask) { snapshots[task.id] = task }
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
        val targetWritePasses = mutableListOf<Int>()
        override suspend fun loadPage(playlist: MigrationPlaylist, offset: Int): MigrationPage {
            requestedPages += offset
            if (failPageOnce == offset) { failPageOnce = null; error("网络中断") }
            return pages[offset] ?: MigrationPage(emptyList(), offset, false)
        }
        override suspend fun candidates(track: MigrationTrack, targetProviderId: String): List<MigrationCandidate> {
            requestedMatches += track.id
            if (failMatchOnce == track.id) { failMatchOnce = null; error("网络中断") }
            return matches[track.id].orEmpty()
        }
        override suspend fun createPlaylist(providerId: String, name: String): MigrationPlaylist {
            createCalls++
            if (createFails) error("响应超时")
            return MigrationPlaylist("playlist:qqmusic:new", name, providerId)
        }
        override suspend fun targetTracks(
            taskId: String,
            writePass: Int,
            playlist: MigrationPlaylist,
        ): Set<String> {
            targetWritePasses += writePass
            return written.toSet()
        }
        override suspend fun addTrack(
            taskId: String,
            writePass: Int,
            playlist: MigrationPlaylist,
            track: MigrationTrack,
        ): Boolean {
            addCalls++
            if (rejectOnce == track.id) { rejectOnce = null; return false }
            written += track.id
            if (timeoutAfterAddingOnce == track.id) { timeoutAfterAddingOnce = null; error("响应超时") }
            return true
        }
    }
}
