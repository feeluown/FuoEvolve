package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlaybackQueueEntryIdentityTest {
    @Test
    fun duplicateTracksKeepDistinctQueueOccurrenceIdentity() {
        val firstA = track("track:a", "A first")
        val trackB = track("track:b", "B")
        val secondA = track("track:a", "A second")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(firstA, trackB, secondA)
            mainQueueIndex = 0
        }

        val entries = queue.displayQueueEntries()

        assertEquals(listOf("track:a", "track:b", "track:a"), entries.map { it.track.id })
        assertNotEquals(entries[0].id, entries[2].id)
        assertEquals(entries[0].id, queue.currentQueueEntryId())
    }

    @Test
    fun engineTransitionToSecondDuplicateSelectsExactOccurrence() {
        val firstA = track("track:a", "A first")
        val trackB = track("track:b", "B")
        val secondA = track("track:a", "A second")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(firstA, trackB, secondA)
            mainQueueIndex = 0
        }
        val secondAEntry = assertNotNull(queue.displayQueueEntries().getOrNull(2))

        assertTrue(queue.synchronizePlaybackEntry(secondAEntry.id, secondA) == true)

        assertEquals(2, queue.mainQueueIndex)
        assertEquals(secondAEntry.id, queue.currentQueueEntryId())
        assertEquals("A second", queue.currentTrack()?.title)
    }

    @Test
    fun queueMutationPreservesExistingOccurrenceIdsInOrder() {
        val firstA = track("track:a", "A first")
        val trackB = track("track:b", "B")
        val secondA = track("track:a", "A second")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(firstA, trackB, secondA)
            mainQueueIndex = 0
        }
        val before = queue.displayQueueEntries()

        queue.mainQueue = queue.mainQueue + track("track:c", "C")
        val after = queue.displayQueueEntries()

        assertEquals(before.map { it.id }, after.take(3).map { it.id })
        assertNotEquals(after[2].id, after[3].id)
    }

    @Test
    fun originalShuffleViewSharesOccurrenceIdsWithMainQueue() {
        val firstA = track("track:a", "A first")
        val trackB = track("track:b", "B")
        val secondA = track("track:a", "A second")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(firstA, trackB, secondA)
            mainQueueIndex = 1
        }
        val mainEntries = queue.mainQueueEntries()

        queue.replaceOriginalMainQueueEntries(mainEntries)

        assertEquals(mainEntries.map { it.id }, queue.originalMainQueueEntries().map { it.id })
    }

    @Test
    fun snapshotCodecPreservesOccurrenceIdsAcrossRestart() {
        val firstA = track("track:a", "A first")
        val trackB = track("track:b", "B")
        val secondA = track("track:a", "A second")
        val source = PlaybackQueueController().apply {
            mainQueue = listOf(firstA, trackB, secondA)
            mainQueueIndex = 2
            replaceOriginalMainQueueEntries(mainQueueEntries())
            upNextQueue = listOf(track("track:c", "C"))
            shuffleEnabled = true
        }
        val snapshot = source.snapshot()

        val decoded = PlaybackQueueCodec.decode(PlaybackQueueCodec.encode(snapshot))
        val restored = PlaybackQueueController()
        assertTrue(restored.restore(decoded))

        assertEquals(snapshot.mainQueueEntryIds, decoded.mainQueueEntryIds)
        assertEquals(snapshot.originalMainQueueEntryIds, decoded.originalMainQueueEntryIds)
        assertEquals(snapshot.upNextQueueEntryIds, decoded.upNextQueueEntryIds)
        assertEquals(snapshot.queueEntrySequence, decoded.queueEntrySequence)
        assertEquals(source.mainQueueEntries().map { it.id }, restored.mainQueueEntries().map { it.id })
        assertEquals(source.originalMainQueueEntries().map { it.id }, restored.originalMainQueueEntries().map { it.id })
        assertEquals(source.currentQueueEntryId(), restored.currentQueueEntryId())
        assertNotEquals(restored.mainQueueEntries()[0].id, restored.mainQueueEntries()[2].id)
    }

    @Test
    fun legacySnapshotWithoutEntryIdsAllocatesDistinctOccurrences() {
        val firstA = track("track:a", "A first")
        val secondA = track("track:a", "A second")
        val restored = PlaybackQueueController()

        assertTrue(
            restored.restore(
                PlaybackQueueSnapshot(
                    mainQueue = listOf(firstA, secondA),
                    queueIndex = 0,
                )
            )
        )

        val entries = restored.mainQueueEntries()
        assertEquals(2, entries.size)
        assertNotEquals(entries[0].id, entries[1].id)
    }

    private fun track(id: String, title: String) = MusicTrack(
        id = id,
        title = title,
        artists = "Artist",
        album = "Album",
        source = "test",
        sourceType = TrackSourceType.Provider,
        providerId = id,
    )
}
