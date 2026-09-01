package org.feeluown.mobile.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.feeluown.mobile.LocalMusicScanSettings

class DesktopLocalMusicRepositoryTest {
    @Test
    fun scanPersistsTracksAndAppliesDirectoryAndDurationFilters() = runBlocking {
        val temp = Files.createTempDirectory("fuoevolve-local-test")
        val music = Files.createDirectories(temp.resolve("Music/Album"))
        val song = music.resolve("song.mp3")
        Files.write(song, byteArrayOf(1, 2, 3))
        val storage = temp.resolve("state")
        val reader: (java.nio.file.Path) -> DesktopAudioMetadata = {
            DesktopAudioMetadata("Song", "Artist", "Album", 120_000L)
        }
        val repository = DesktopLocalMusicRepository(listOf(temp.resolve("Music")), storage, reader)

        val tracks = repository.refreshDatabase()

        assertEquals(1, tracks.size)
        assertEquals("Song", tracks.single().title)
        assertEquals("Music/Album/", tracks.single().localDirectoryId)
        assertTrue(repository.isDatabaseReady())
        assertFalse(repository.isDatabaseStale())

        repository.updateScanSettings(LocalMusicScanSettings(minDurationSeconds = 121))
        assertTrue(repository.tracks().isEmpty())

        repository.updateScanSettings(
            LocalMusicScanSettings(excludedDirectoryIds = setOf("Music/Album"), minDurationSeconds = 0),
        )
        assertTrue(repository.tracks().isEmpty())

        val reloaded = DesktopLocalMusicRepository(
            listOf(temp.resolve("Music")),
            storage,
            metadataReader = { error("persisted index should be used") },
        )
        assertEquals("Song", reloaded.tracks().single().title)
    }

    @Test
    fun fingerprintBecomesStaleWhenMusicFilesChange() = runBlocking {
        val temp = Files.createTempDirectory("fuoevolve-local-stale")
        val music = Files.createDirectories(temp.resolve("Music"))
        Files.write(music.resolve("one.mp3"), byteArrayOf(1))
        val repository = DesktopLocalMusicRepository(
            roots = listOf(music),
            storageRoot = temp.resolve("state"),
            metadataReader = { DesktopAudioMetadata(it.fileName.toString(), "", "", 60_000L) },
        )
        repository.refreshDatabase()
        assertFalse(repository.isDatabaseStale())

        Files.write(music.resolve("two.mp3"), byteArrayOf(2))

        assertTrue(repository.isDatabaseStale())
    }
}
