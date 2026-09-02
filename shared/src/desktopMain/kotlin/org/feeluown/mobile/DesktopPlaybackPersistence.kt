package org.feeluown.mobile

import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun createDesktopPlaybackResumeStore(): PlaybackResumeStore =
    DesktopPlaybackResumeStore(DesktopAppDirectories.state().resolve("playback-resume.txt"))

internal fun createDesktopPlaybackQueueStore(
    resumeStore: PlaybackResumeStore,
): DesktopPlaybackQueueStore = DesktopPlaybackQueueStore(
    file = DesktopAppDirectories.state().resolve("playback-queue.txt"),
    resumeStore = resumeStore,
)

internal class DesktopPlaybackQueueStore(
    private val file: Path,
    private val resumeStore: PlaybackResumeStore,
) : PlaybackQueueStore {
    @Volatile
    private var latestSnapshot: PlaybackQueueSnapshot? = null

    @Volatile
    private var loadCompleted = false

    override suspend fun load(): PlaybackQueueSnapshot = withContext(Dispatchers.IO) {
        val persisted = readTextIfExists(file)
            ?.let { raw -> runCatching { PlaybackQueueCodec.decode(raw) }.getOrNull() }
            ?: PlaybackQueueSnapshot()
        val restoredSession = resumeStore.load()
        val snapshot = persisted.reconcileRestoredPlayback(
            plan = null,
            currentTrack = restoredSession?.currentTrack,
        )
        latestSnapshot = snapshot
        loadCompleted = true
        snapshot
    }

    override suspend fun save(snapshot: PlaybackQueueSnapshot) {
        if (!loadCompleted) return
        latestSnapshot = snapshot
        withContext(Dispatchers.IO) {
            writeAtomicText(file, PlaybackQueueCodec.encode(snapshot))
        }
    }

    fun flushLatest() {
        if (!loadCompleted) return
        latestSnapshot?.let { snapshot ->
            writeAtomicText(file, PlaybackQueueCodec.encode(snapshot))
        }
    }
}

private class DesktopPlaybackResumeStore(
    private val file: Path,
) : PlaybackResumeStore {
    private val lock = Any()
    private var latest: PlaybackResumeSnapshot? = null
    private var loaded = false

    override fun load(): PlaybackResumeSnapshot? = synchronized(lock) {
        if (!loaded) {
            latest = readTextIfExists(file)
                ?.let { raw -> runCatching { PlaybackResumeCodec.decode(raw) }.getOrNull() }
            loaded = true
        }
        latest
    }

    override fun saveSession(state: PlaybackState) = synchronized(lock) {
        val currentTrack = state.currentTrack ?: return@synchronized
        val snapshot = PlaybackResumeSnapshot(
            currentTrack = currentTrack.logicalPlaybackTrack(),
            positionMs = state.positionMs.coerceAtLeast(0L),
            durationMs = state.durationMs.coerceAtLeast(0L),
            playbackParts = state.playbackParts,
            currentPartIndex = state.currentPartIndex,
        )
        latest = snapshot
        loaded = true
        writeSnapshot(snapshot)
    }

    override fun savePosition(positionMs: Long, durationMs: Long) = synchronized(lock) {
        val current = load() ?: return@synchronized
        val updated = current.copy(
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
        )
        latest = updated
        writeSnapshot(updated)
    }

    override fun flush() = synchronized(lock) {
        latest?.let(::writeSnapshot)
    }

    override fun clear() = synchronized(lock) {
        latest = null
        loaded = true
        runCatching { Files.deleteIfExists(file) }
    }

    private fun writeSnapshot(snapshot: PlaybackResumeSnapshot) {
        writeAtomicText(file, PlaybackResumeCodec.encode(snapshot))
    }
}

private fun readTextIfExists(file: Path): String? {
    if (!Files.isRegularFile(file)) return null
    return Files.readString(file, StandardCharsets.UTF_8)
}

private fun writeAtomicText(file: Path, content: String) {
    val directory = requireNotNull(file.parent) { "Desktop persistence file must have a parent directory" }
    Files.createDirectories(directory)
    val temp = Files.createTempFile(directory, ".${file.fileName}.", ".tmp")
    try {
        FileOutputStream(temp.toFile()).use { output ->
            output.write(content.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(
                temp,
                file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temp)
    }
}
