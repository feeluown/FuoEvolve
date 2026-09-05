package org.feeluown.mobile

import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun createDesktopLocalPlaylistRepository(): LocalPlaylistRepository =
    FileBackedLocalPlaylistRepository(
        storage = DesktopLocalPlaylistFileStorage(
            directory = DesktopAppDirectories.data().resolve("collections"),
        ),
    )

private class DesktopLocalPlaylistFileStorage(
    private val directory: Path,
) : LocalPlaylistFileStorage {
    override suspend fun listFileNames(): List<String> = withContext(Dispatchers.IO) {
        ensureDirectory()
        Files.list(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .map { it.fileName.toString() }
                .toList()
        }
    }

    override suspend fun readText(fileName: String): String? = withContext(Dispatchers.IO) {
        val target = fileFor(fileName)
        if (!Files.isRegularFile(target)) return@withContext null
        Files.readString(target, StandardCharsets.UTF_8)
    }

    override suspend fun writeTextAtomically(fileName: String, content: String): Unit = withContext(Dispatchers.IO) {
        ensureDirectory()
        val target = fileFor(fileName)
        val temp = Files.createTempFile(directory, ".${target.fileName}.", ".tmp")
        try {
            FileOutputStream(temp.toFile()).use { output ->
                output.write(content.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override suspend fun delete(fileName: String): Boolean = withContext(Dispatchers.IO) {
        Files.deleteIfExists(fileFor(fileName))
    }

    override suspend fun exists(fileName: String): Boolean = withContext(Dispatchers.IO) {
        Files.exists(fileFor(fileName))
    }

    private fun ensureDirectory() {
        Files.createDirectories(directory)
    }

    private fun fileFor(fileName: String): Path {
        val safeName = Path.of(fileName).fileName.toString()
        return directory.resolve(safeName)
    }
}
