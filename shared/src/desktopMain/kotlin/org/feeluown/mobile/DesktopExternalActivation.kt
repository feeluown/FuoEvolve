package org.feeluown.mobile

import java.awt.Desktop
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val MAX_DESKTOP_ACTIVATION_INPUTS = 32

/**
 * Desktop-only activation broker. It keeps one process owning persistence/native media integration
 * and forwards file/protocol activations from later launcher processes to that primary instance.
 */
class DesktopExternalActivationSession private constructor(
    private val lockChannel: FileChannel,
    private val processLock: FileLock,
    private val server: ServerSocket,
    private val endpointFile: Path,
    initialInputs: List<String>,
) : AutoCloseable {
    // Initial launcher arguments are emitted before Compose collectors attach, so replay must retain
    // the same maximum batch that the secondary-instance relay accepts.
    private val mutableInputs = MutableSharedFlow<String>(
        replay = MAX_DESKTOP_ACTIVATION_INPUTS,
        extraBufferCapacity = MAX_DESKTOP_ACTIVATION_INPUTS,
    )
    val inputs: SharedFlow<String> = mutableInputs.asSharedFlow()
    private val token = UUID.randomUUID().toString()
    @Volatile
    private var closed = false

    private val serverThread = thread(
        start = false,
        isDaemon = true,
        name = "fuoevolve-desktop-activation",
    ) { acceptLoop() }

    init {
        Files.writeString(
            endpointFile,
            "${server.localPort}\n$token",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        initialInputs
            .asSequence()
            .filter(String::isNotBlank)
            .take(MAX_DESKTOP_ACTIVATION_INPUTS)
            .forEach(mutableInputs::tryEmit)
        serverThread.start()
        installAwtOpenHandlers()
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        runCatching { serverThread.join(500) }
        runCatching { Files.deleteIfExists(endpointFile) }
        runCatching { processLock.release() }
        runCatching { lockChannel.close() }
    }

    private fun acceptLoop() {
        while (!closed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            socket.use { incoming ->
                runCatching {
                    val input = DataInputStream(incoming.getInputStream().buffered())
                    if (input.readUTF() != token) return@runCatching
                    repeat(input.readInt().coerceIn(0, MAX_DESKTOP_ACTIVATION_INPUTS)) {
                        input.readUTF().takeIf(String::isNotBlank)?.let(mutableInputs::tryEmit)
                    }
                }
            }
        }
    }

    private fun installAwtOpenHandlers() {
        if (!Desktop.isDesktopSupported()) return
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull() ?: return
        if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) {
            runCatching {
                desktop.setOpenFileHandler { event ->
                    event.files.forEach { file -> mutableInputs.tryEmit(file.absolutePath) }
                }
            }
        }
        if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) {
            runCatching {
                desktop.setOpenURIHandler { event -> mutableInputs.tryEmit(event.uri.toString()) }
            }
        }
    }

    companion object {
        /** Returns null in a secondary process after its activation has been relayed. */
        fun open(initialInputs: List<String>): DesktopExternalActivationSession? {
            val directory = DesktopAppDirectories.state().resolve("activation")
            Files.createDirectories(directory)
            val lockFile = directory.resolve("instance.lock")
            val endpointFile = directory.resolve("endpoint")
            val channel = FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = runCatching { channel.tryLock() }.getOrNull()
            if (lock == null) {
                channel.close()
                relayToPrimary(endpointFile, initialInputs)
                return null
            }
            return runCatching {
                DesktopExternalActivationSession(
                    lockChannel = channel,
                    processLock = lock,
                    server = ServerSocket(0, 16, InetAddress.getLoopbackAddress()),
                    endpointFile = endpointFile,
                    initialInputs = initialInputs,
                )
            }.getOrElse { throwable ->
                runCatching { lock.release() }
                runCatching { channel.close() }
                throw throwable
            }
        }

        private fun relayToPrimary(endpointFile: Path, inputs: List<String>) {
            val requested = inputs.filter(String::isNotBlank).take(MAX_DESKTOP_ACTIVATION_INPUTS)
            val payload = requested.ifEmpty { listOf(DESKTOP_ACTIVATION_FOCUS) }
            repeat(RELAY_ATTEMPTS) { attempt ->
                val endpoint = runCatching {
                    Files.readAllLines(endpointFile, StandardCharsets.UTF_8)
                }.getOrNull()
                val port = endpoint?.getOrNull(0)?.toIntOrNull()
                val token = endpoint?.getOrNull(1)
                if (port != null && !token.isNullOrBlank()) {
                    val sent = runCatching {
                        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                            DataOutputStream(socket.getOutputStream().buffered()).use { output ->
                                output.writeUTF(token)
                                output.writeInt(payload.size)
                                payload.forEach(output::writeUTF)
                                output.flush()
                            }
                        }
                    }.isSuccess
                    if (sent) return
                }
                if (attempt + 1 < RELAY_ATTEMPTS) Thread.sleep(RELAY_RETRY_MS)
            }
        }

        private const val RELAY_ATTEMPTS = 20
        private const val RELAY_RETRY_MS = 50L
    }
}
