package org.feeluown.mobile.persistence.listening

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopListeningHistoryDriverFactoryTest {
    @Test
    fun createsSqliteDatabaseAndCommonStoreCanQueryIt() {
        val directory = Files.createTempDirectory("fuoevolve-listening-history-test")
        val database = directory.resolve("listening_history.db")
        try {
            val store = SqlDelightListeningHistoryStore(
                DesktopListeningHistoryDriverFactory(database),
            )

            assertEquals(0L, kotlinx.coroutines.runBlocking { store.eventCount() })
            kotlin.test.assertTrue(Files.isRegularFile(database))
        } finally {
            runCatching {
                Files.walk(directory).use { stream ->
                    stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }
}
