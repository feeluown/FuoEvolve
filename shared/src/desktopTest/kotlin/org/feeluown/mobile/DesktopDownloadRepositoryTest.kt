package org.feeluown.mobile

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopDownloadRepositoryTest {
    @Test
    fun downloadPersistsReloadsAndDeletesCompletedFile() = runBlocking {
        val root = Files.createTempDirectory("fuoevolve-desktop-download-test")
        val source = Files.createTempFile("fuoevolve-source", ".mp3")
        val bytes = ByteArray(128 * 1024) { index -> (index % 251).toByte() }
        Files.write(source, bytes)
        val track = providerTrack("provider:test")
        val payload = PlaybackPayload(
            url = source.toUri().toString(),
            title = track.title,
            artists = track.artists,
            album = track.album,
            source = track.source,
            durationMs = track.durationMs,
            lyrics = "[00:00.00]Desktop lyrics",
        )
        val resolverCalls = AtomicInteger(0)
        val repository = DesktopDownloadRepository(
            resolvePayload = {
                resolverCalls.incrementAndGet()
                payload
            },
            storageRoot = root,
        )

        try {
            repository.load()
            repository.download(track)
            val completed = withTimeout(5_000) {
                repository.tasks.first { tasks ->
                    tasks.any { it.id == track.id && it.status == DownloadTaskStatus.Completed }
                }.first { it.id == track.id }
            }

            assertEquals(1, resolverCalls.get())
            assertEquals(bytes.size.toLong(), completed.downloadedBytes)
            val completedUri = requireNotNull(completed.completedUri)
            val completedPath = Path.of(URI(completedUri))
            assertTrue(Files.isRegularFile(completedPath))
            assertContentEquals(bytes, Files.readAllBytes(completedPath))
            assertEquals(
                "[00:00.00]Desktop lyrics",
                Files.readString(completedPath.resolveSibling("${completedPath.fileName}.lrc")),
            )
            val downloadedState = assertIs<DownloadState.Downloaded>(repository.states.value.getValue(track.id))
            assertEquals(completedUri, downloadedState.uri)

            repository.close()

            val reloaded = DesktopDownloadRepository(
                resolvePayload = { error("completed download must not re-resolve") },
                storageRoot = root,
            )
            try {
                reloaded.load()
                val restored = reloaded.tasks.value.single { it.id == track.id }
                assertEquals(DownloadTaskStatus.Completed, restored.status)
                assertEquals(completedUri, restored.completedUri)
                assertIs<DownloadState.Downloaded>(reloaded.states.value.getValue(track.id))

                reloaded.deleteTask(track.id, deleteFile = true)
                assertTrue(reloaded.tasks.value.none { it.id == track.id })
                assertFalse(Files.exists(completedPath))
                assertFalse(Files.exists(completedPath.resolveSibling("${completedPath.fileName}.lrc")))
            } finally {
                reloaded.close()
            }
        } finally {
            repository.close()
            Files.deleteIfExists(source)
            root.toFile().deleteRecursively()
        }
    }

    private fun providerTrack(id: String): MusicTrack = MusicTrack(
        id = id,
        title = "Desktop Track",
        artists = "Desktop Artist",
        album = "Desktop Album",
        source = "provider",
        sourceType = TrackSourceType.Provider,
        durationMs = 180_000,
        providerId = id,
        providerName = "Provider",
    )
}
