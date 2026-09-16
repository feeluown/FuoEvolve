package org.feeluown.mobile

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupLogFilesTest {
    @Test
    fun eachLaunchCreatesAUniqueFileAndExportsNewestThree() = withLogDirectory { directory ->
        val sessions = (1..4).map {
            StartupLogFiles.start(directory).also { file -> Files.writeString(file, "session-$it\n") }
            // The filenames include a timestamp and a random suffix for collision safety.
        }
        assertEquals(4, sessions.distinct().size)
        val selected = StartupLogFiles.latest(directory)
        assertEquals(3, selected.size)
        assertFalse(sessions.first() in selected)
        assertEquals(sessions.drop(1).toSet(), selected.toSet())
    }

    @Test
    fun startupPrunesOldSessionsButLeavesUnrelatedFilesAlone() = withLogDirectory { directory ->
        val unrelated = directory.resolve("debug-not-a-session.log")
        Files.writeString(unrelated, "keep")
        val oldSessions = (0 until 12).map { index ->
            directory.resolve("application-20200101-000000-${index.toString().padStart(3, '0')}-deadbeef.log")
                .also { Files.writeString(it, "old") }
        }
        StartupLogFiles.start(directory)
        assertEquals(10, StartupLogFiles.latest(directory, Int.MAX_VALUE).size)
        assertFalse(Files.exists(oldSessions[0]))
        assertTrue(Files.exists(unrelated))
    }

    @Test
    fun logFileKeepsLatestLinesUnderByteLimit() = withLogDirectory { directory ->
        val file = StartupLogFiles.start(directory)
        StartupLogOutputStream(file, maxBytes = 32).use { out ->
            out.write("old-one\nold-two\n".toByteArray(StandardCharsets.UTF_8))
            out.write("recent-one\nrecent-two\n".toByteArray(StandardCharsets.UTF_8))
            out.write("latest-line\n".toByteArray(StandardCharsets.UTF_8))
        }
        assertTrue(Files.size(file) <= 32)
        assertTrue(Files.readString(file).endsWith("latest-line\n"))
        assertEquals(1, StartupLogFiles.latest(directory).size)
    }

    @Test
    fun archiveContainsExactlyThreeMostRecentStartupLogsAndSummary() = withLogDirectory { directory ->
        val files = (0..4).map { index ->
            directory.resolve("application-20200101-000000-${index.toString().padStart(3, '0')}-deadbeef.log")
                .also { Files.writeString(it, "session $index") }
        }
        Files.writeString(directory.resolve("application.previous.log"), "legacy")
        val archive = createDesktopDiagnosticsArchive(directory, "diagnostics")
        try {
            ZipFile(archive.toFile()).use { zip ->
                val names = mutableListOf<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) names += entries.nextElement().name
                assertEquals(setOf("diagnostics.txt") + files.takeLast(3).map { it.fileName.toString() }, names.toSet())
                assertEquals(4, names.size)
                assertEquals("diagnostics", zip.getInputStream(zip.getEntry("diagnostics.txt")).bufferedReader().use { it.readText() })
            }
        } finally {
            Files.deleteIfExists(archive)
        }
    }

    private fun withLogDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("fuoevolve-startup-logs")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
