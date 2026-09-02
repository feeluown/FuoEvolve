package org.feeluown.mobile

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RollingFileOutputStreamTest {
    @Test
    fun rotatesDuringWritesAndBoundsActiveFile() {
        val directory = Files.createTempDirectory("fuoevolve-log-test")
        try {
            val active = directory.resolve("application.log")
            val previous = directory.resolve("application.previous.log")

            RollingFileOutputStream(active, previous, maxBytes = 8L).use { output ->
                output.write("123456".toByteArray(StandardCharsets.UTF_8))
                output.write("7890".toByteArray(StandardCharsets.UTF_8))
            }

            assertEquals("12345678", Files.readString(previous, StandardCharsets.UTF_8))
            assertEquals("90", Files.readString(active, StandardCharsets.UTF_8))
            assertTrue(Files.size(active) <= 8L)
            assertTrue(Files.size(previous) <= 8L)
        } finally {
            Files.walk(directory).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
