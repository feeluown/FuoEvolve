package org.feeluown.mobile

import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun createDesktopPlaybackResumeStore(): PlaybackResumeStore =
    DesktopPlaybackResumeStore(DesktopAppDirectories.state().resolve("playback-resume.txt"))

internal fun createDesktopPlaybackQueueStore(
    resumeStore: PlaybackResumeStore,
): DesktopPlaybackQueueStore = DesktopPlaybackQueueStore(
    file = DesktopAppDirectories.state().resolve("playback-queue.txt"),
    identityFile = DesktopAppDirectories.state().resolve("playback-queue-identity.txt"),
    fingerprintFile = DesktopAppDirectories.state().resolve("playback-queue-identity-fingerprint.txt"),
    resumeStore = resumeStore,
)

internal class DesktopPlaybackQueueStore(
    private val file: Path,
    private val identityFile: Path,
    private val fingerprintFile: Path,
    private val resumeStore: PlaybackResumeStore,
) : PlaybackQueueStore {
    private val writeVersionLock = Any()
    private val fileWriteLock = Any()

    @Volatile
    private var latestSnapshot: PlaybackQueueSnapshot? = null

    @Volatile
    private var loadCompleted = false

    private var nextWriteVersion = 0L
    private var lastWrittenVersion = 0L

    override suspend fun load(): PlaybackQueueSnapshot = withContext(Dispatchers.IO) {
        val persisted = readTextIfExists(file)
            ?.let { raw -> runCatching { PlaybackQueueCodec.decode(raw) }.getOrNull() }
            ?: PlaybackQueueSnapshot()
        val identity = readTextIfExists(identityFile)
            ?.let { raw -> runCatching { PlaybackQueueIdentityCodec.decode(raw) }.getOrNull() }
        val fingerprint = readTextIfExists(fingerprintFile)?.takeIf { it.isNotBlank() }
        val restoredSession = resumeStore.load()
        val snapshot = persisted
            .withMatchingIdentitySnapshot(identity, fingerprint)
            .reconcileRestoredPlayback(
                plan = null,
                currentTrack = restoredSession?.currentTrack,
            )
        synchronized(writeVersionLock) {
            latestSnapshot = snapshot
        }
        loadCompleted = true
        snapshot
    }

    override suspend fun save(snapshot: PlaybackQueueSnapshot) {
        if (!loadCompleted) return
        val version = synchronized(writeVersionLock) {
            latestSnapshot = snapshot
            ++nextWriteVersion
        }
        withContext(Dispatchers.IO) {
            synchronized(fileWriteLock) {
                if (version <= lastWrittenVersion) return@synchronized
                writeQueueSnapshot(snapshot)
                lastWrittenVersion = version
            }
        }
    }

    fun flushLatest() {
        if (!loadCompleted) return
        val pending = synchronized(writeVersionLock) {
            val snapshot = latestSnapshot ?: return
            val version = ++nextWriteVersion
            version to snapshot
        }
        synchronized(fileWriteLock) {
            if (pending.first <= lastWrittenVersion) return
            writeQueueSnapshot(pending.second)
            lastWrittenVersion = pending.first
        }
    }

    private fun writeQueueSnapshot(snapshot: PlaybackQueueSnapshot) {
        writeAtomicText(file, PlaybackQueueCodec.encode(snapshot))
        writeAtomicText(
            identityFile,
            PlaybackQueueIdentityCodec.encode(snapshot.toIdentitySnapshot()),
        )
        writeAtomicText(fingerprintFile, snapshot.queueIdentityFingerprint())
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

    override fun flush(): Unit = synchronized(lock) {
        latest?.let(::writeSnapshot)
    }

    override fun clear(): Unit = synchronized(lock) {
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
