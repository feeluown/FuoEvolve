package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaylistMigrationStoreTest {
    @Test
    fun tasksSurviveStoreRecreationWithoutLosingEarlierHistory() = runTest {
        val storage = FakeDocuments()
        val first = PlaylistMigrationTask(
            id = "one",
            source = MigrationPlaylist("p:1", "来源", "netease"),
            targetProviderId = "qqmusic",
        )
        val second = first.copy(id = "two", phase = MigrationPhase.Review, nextOffset = 3)
        JsonPlaylistMigrationStore(storage).save(first)
        JsonPlaylistMigrationStore(storage).save(second)

        val loaded = JsonPlaylistMigrationStore(storage).load()
        assertEquals(listOf("two", "one"), loaded.map { it.id })
        JsonPlaylistMigrationStore(storage).save(first.copy(nextOffset = 10))
        assertEquals(listOf(3, 10), JsonPlaylistMigrationStore(storage).load().map { it.nextOffset })
    }

    @Test
    fun failedWriteDoesNotReportTaskAsDurable() = runTest {
        val storage = FakeDocuments()
        val store = JsonPlaylistMigrationStore(storage)
        val task = PlaylistMigrationTask(
            id = "one",
            source = MigrationPlaylist("p:1", "来源", "netease"),
            targetProviderId = "qqmusic",
        )
        storage.rejectWrites = true
        assertFailsWith<IllegalStateException> { store.save(task) }
        assertEquals(emptyList(), store.load())
    }

    @Test
    fun corruptDocumentIsNotSilentlyOverwritten() = runTest {
        val storage = FakeDocuments().apply { content = "not-json" }
        val store = JsonPlaylistMigrationStore(storage)
        assertFailsWith<Exception> { store.load() }
        assertEquals("not-json", storage.content)
    }

    private class FakeDocuments : PlaylistMigrationDocumentStore {
        var content: String? = null
        var rejectWrites = false
        override suspend fun read(): String? = content
        override suspend fun write(document: String) {
            if (rejectWrites) error("disk unavailable")
            content = document
        }
    }
}
