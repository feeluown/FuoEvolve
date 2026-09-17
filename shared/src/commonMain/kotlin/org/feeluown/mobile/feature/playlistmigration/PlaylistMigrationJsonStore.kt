package org.feeluown.mobile

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Platform persistence writes a whole document atomically before returning. Use a dedicated
 * file, never the app settings DataStore, so large playlists do not rewrite app preferences.
 */
interface PlaylistMigrationDocumentStore {
    suspend fun read(): String?
    suspend fun write(document: String)
}

@Serializable
private data class MigrationDocument(
    val version: Int = 1,
    val tasks: List<PlaylistMigrationTask> = emptyList(),
)

/** Keeps task history and each operation's checkpoint across process restarts. */
class JsonPlaylistMigrationStore(
    private val documents: PlaylistMigrationDocumentStore,
) : PlaylistMigrationStore {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun load(): List<PlaylistMigrationTask> = mutex.withLock { readDocument().tasks }

    override suspend fun save(task: PlaylistMigrationTask) = mutex.withLock {
        val current = readDocument()
        val tasks = if (current.tasks.any { it.id == task.id }) {
            current.tasks.map { if (it.id == task.id) task else it }
        } else {
            listOf(task) + current.tasks
        }
        // Update memory only after storage acknowledges the entire new snapshot.
        documents.write(json.encodeToString(current.copy(tasks = tasks)))
    }

    private suspend fun readDocument(): MigrationDocument {
        val raw = documents.read()?.takeIf { it.isNotBlank() } ?: return MigrationDocument()
        val document = json.decodeFromString<MigrationDocument>(raw)
        require(document.version == 1) { "不支持当前迁移记录版本" }
        return document
    }
}
