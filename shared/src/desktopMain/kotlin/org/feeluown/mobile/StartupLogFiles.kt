package org.feeluown.mobile

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** One log per application launch. Only our timestamped log names are eligible for export. */
internal object StartupLogFiles {
    private const val MAX_STORED_STARTUPS = 10
    const val EXPORT_STARTUPS = 3
    private val namePattern = Regex("application-\\d{8}-\\d{6}-\\d{3}-[0-9a-f]{8}\\.log")
    private var lastStartMillis = 0L

    fun latest(directory: Path, limit: Int = EXPORT_STARTUPS): List<Path> {
        require(limit >= 0)
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) && namePattern.matches(it.fileName.toString()) }
                .sorted { first, second -> second.fileName.toString().compareTo(first.fileName.toString()) }
                .limit(limit.toLong())
                .toList()
        }
    }

    @Synchronized
    fun start(directory: Path): Path {
        Files.createDirectories(directory)
        // A monotonic millisecond timestamp also preserves ordering when a launch or
        // repeated logger initialization happens within the same wall-clock millisecond.
        lastStartMillis = maxOf(System.currentTimeMillis(), lastStartMillis + 1)
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(lastStartMillis))
        val active = Files.createFile(
            directory.resolve("application-$timestamp-${UUID.randomUUID().toString().take(8)}.log"),
        )
        latest(directory, Int.MAX_VALUE).drop(MAX_STORED_STARTUPS).forEach { Files.deleteIfExists(it) }
        return active
    }
}

/** Keep the newest log lines within a single startup file without creating extra segments. */
internal class StartupLogOutputStream(
    private val activeFile: Path,
    private val maxBytes: Long,
) : OutputStream() {
    private var output: OutputStream = Files.newOutputStream(activeFile, StandardOpenOption.APPEND)
    private var written = Files.size(activeFile)
    private var closed = false

    init {
        require(maxBytes > 0L && maxBytes <= Int.MAX_VALUE) { "invalid startup log size limit" }
    }

    @Synchronized
    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

    @Synchronized
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        java.util.Objects.checkFromIndexSize(offset, length, buffer.size)
        check(!closed) { "startup log is closed" }
        if (length == 0) return
        if (length.toLong() >= maxBytes) {
            replaceContents(buffer.copyOfRange(offset + length - maxBytes.toInt(), offset + length))
            return
        }
        if (written + length > maxBytes) compactFor(length)
        output.write(buffer, offset, length)
        written += length
    }

    private fun compactFor(incoming: Int) {
        output.flush()
        output.close()
        val previous = Files.readAllBytes(activeFile)
        val keep = minOf(maxBytes - incoming, maxBytes / 2).toInt()
        var start = (previous.size - keep).coerceAtLeast(0)
        // Drop any partial line at the beginning of the retained tail.
        while (start > 0 && start < previous.size && previous[start - 1] != '\n'.code.toByte()) start++
        reopen(previous.copyOfRange(start, previous.size))
    }

    private fun replaceContents(bytes: ByteArray) {
        output.flush()
        output.close()
        reopen(bytes)
    }

    private fun reopen(bytes: ByteArray) {
        Files.write(activeFile, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        written = bytes.size.toLong()
        output = Files.newOutputStream(activeFile, StandardOpenOption.APPEND)
    }

    @Synchronized
    override fun flush() {
        if (!closed) output.flush()
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            output.close()
        }
    }
}
