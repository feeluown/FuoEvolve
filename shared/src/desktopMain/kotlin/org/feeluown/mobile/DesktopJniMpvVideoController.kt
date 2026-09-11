package org.feeluown.mobile

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * GraalVM-friendly desktop video controller backed by the same libmpv JNI bridge packaged by the
 * Nucleus desktop runtime. The legacy JVM host installs its JNA controller explicitly, so this
 * controller is only used when no host-specific factory overrides the desktop default.
 */
internal class DesktopJniMpvVideoController : DesktopPlatformVideoController {
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(PlatformVideoPlaybackState())
    override val state: StateFlow<PlatformVideoPlaybackState> = mutableState.asStateFlow()
    private val mutableFrame = MutableStateFlow<ImageBitmap?>(null)
    override val frame: StateFlow<ImageBitmap?> = mutableFrame.asStateFlow()

    private val handle: Long
    private val renderContext: Long
    private val eventThread: Thread
    private val renderThread: Thread

    @Volatile private var viewportWidth = 0
    @Volatile private var viewportHeight = 0
    @Volatile private var playbackActive = false

    init {
        DesktopJniMpvVideoBridgeLoader.ensureLoaded()
        handle = DesktopJniMpvVideoApi.nativeCreate()
        check(handle != 0L) { "libmpv mpv_create() returned null for video" }
        var createdRenderContext = 0L
        try {
            setOption("config", "no")
            setOption("terminal", "no")
            setOption("input-default-bindings", "no")
            setOption("ytdl", "no")
            setOption("vo", "libmpv")
            setOption("hwdec", "no")
            setOption("audio-display", "no")
            checkMpv(DesktopJniMpvVideoApi.nativeInitialize(handle), "mpv_initialize video")
            createdRenderContext = DesktopJniMpvVideoApi.nativeCreateSoftwareRenderContext(handle)
            check(createdRenderContext != 0L) { "libmpv software video render context creation failed" }
        } catch (throwable: Throwable) {
            if (createdRenderContext != 0L) {
                DesktopJniMpvVideoApi.nativeFreeRenderContext(createdRenderContext)
            }
            DesktopJniMpvVideoApi.nativeDestroy(handle)
            throw throwable
        }
        renderContext = createdRenderContext
        eventThread = thread(
            start = true,
            isDaemon = true,
            name = "fuoevolve-jni-libmpv-video-events",
            block = ::eventLoop,
        )
        renderThread = thread(
            start = true,
            isDaemon = true,
            name = "fuoevolve-jni-libmpv-video-render",
            block = ::renderLoop,
        )
    }

    override fun setPayload(payload: VideoPlaybackPayload?) {
        ensureOpen()
        mutableFrame.value = null
        if (payload == null) {
            playbackActive = false
            command("stop")
            mutableState.value = PlatformVideoPlaybackState()
            return
        }

        val mainUrl = payload.url.takeIf(String::isNotBlank)
            ?: payload.videoUrl.takeIf(String::isNotBlank)
        if (mainUrl == null) {
            playbackActive = false
            mutableState.value = PlatformVideoPlaybackState(errorMessage = "当前视频没有可播放地址")
            return
        }

        val externalAudio = if (payload.url.isBlank()) payload.audioUrl.takeIf(String::isNotBlank) else null
        val options = encodeDesktopJniVideoLoadfileOptions(payload.headers, externalAudio)
        mutableState.value = PlatformVideoPlaybackState()
        playbackActive = true
        if (options.isBlank()) {
            command("loadfile", mainUrl, "replace")
        } else {
            command("loadfile", mainUrl, "replace", "-1", options)
        }
    }

    override fun setViewportSize(width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(0)
        viewportHeight = height.coerceAtLeast(0)
    }

    override fun play() {
        ensureOpen()
        setProperty("pause", "no")
    }

    override fun pause() {
        ensureOpen()
        setProperty("pause", "yes")
    }

    override fun seekTo(positionMs: Long) {
        ensureOpen()
        command("seek", (positionMs.coerceAtLeast(0L) / 1000.0).toString(), "absolute")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        playbackActive = false
        DesktopJniMpvVideoApi.nativeWakeup(handle)
        if (Thread.currentThread() !== eventThread) runCatching { eventThread.join(2_000) }
        if (Thread.currentThread() !== renderThread) runCatching { renderThread.join(2_000) }
        DesktopJniMpvVideoApi.nativeFreeRenderContext(renderContext)
        DesktopJniMpvVideoApi.nativeDestroy(handle)
        mutableFrame.value = null
    }

    private fun eventLoop() {
        try {
            var nextPollAtNanos = System.nanoTime()
            while (!closed.get()) {
                DesktopJniMpvVideoApi.nativeWaitEvent(handle, EVENT_WAIT_SECONDS)?.let { event ->
                    when {
                        event == "shutdown" -> return
                        event == "loaded" || event == "restart" -> {
                            playbackActive = true
                            mutableState.value = mutableState.value.copy(
                                isPlaying = getProperty("pause") != "yes",
                                errorMessage = null,
                            )
                        }
                        event.startsWith("end:") -> {
                            playbackActive = false
                            val endEvent = parseDesktopJniVideoEndEvent(event)
                            mutableState.value = mutableState.value.copy(
                                isPlaying = false,
                                errorMessage = if (
                                    endEvent?.reason == MPV_END_FILE_REASON_ERROR && endEvent.error < 0
                                ) {
                                    DesktopJniMpvVideoApi.nativeErrorString(endEvent.error)
                                        ?.let { "视频播放失败：$it" }
                                        ?: "视频播放失败"
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }

                val now = System.nanoTime()
                if (playbackActive && now >= nextPollAtNanos) {
                    publishPolledState()
                    nextPollAtNanos = now + STATE_POLL_INTERVAL_NANOS
                }
            }
        } catch (throwable: Throwable) {
            if (!closed.get()) {
                mutableState.value = mutableState.value.copy(
                    isPlaying = false,
                    errorMessage = throwable.message ?: "libmpv 视频事件处理失败",
                )
            }
        }
    }

    private fun publishPolledState() {
        val current = mutableState.value
        val duration = getProperty("duration").secondsToMsOrNull() ?: current.durationMs
        val buffered = getProperty("demuxer-cache-time").secondsToMsOrNull() ?: current.bufferedMs
        mutableState.value = current.copy(
            isPlaying = getProperty("pause")?.let { it != "yes" && it != "true" } ?: current.isPlaying,
            positionMs = getProperty("time-pos").secondsToMsOrNull()?.coerceAtLeast(0L) ?: current.positionMs,
            durationMs = duration.coerceAtLeast(0L),
            bufferedMs = if (duration > 0L) buffered.coerceIn(0L, duration) else buffered.coerceAtLeast(0L),
            videoWidth = getProperty("video-params/w")?.toIntOrNull()?.coerceAtLeast(0) ?: current.videoWidth,
            videoHeight = getProperty("video-params/h")?.toIntOrNull()?.coerceAtLeast(0) ?: current.videoHeight,
        )
    }

    private fun renderLoop() {
        while (!closed.get()) {
            val (width, height) = boundedDesktopJniVideoRenderSize(viewportWidth, viewportHeight)
            if (!playbackActive || width <= 0 || height <= 0) {
                Thread.sleep(if (playbackActive) 30L else 80L)
                continue
            }

            try {
                val stride = alignTo64(width * BYTES_PER_PIXEL)
                val pixels = ByteArray(stride * height)
                checkMpv(
                    DesktopJniMpvVideoApi.nativeRenderSoftware(
                        renderContext = renderContext,
                        width = width,
                        height = height,
                        stride = stride,
                        pixels = pixels,
                    ),
                    "render software video frame",
                )
                val imageInfo = ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE)
                mutableFrame.value = Image.makeRaster(imageInfo, pixels, stride).toComposeImageBitmap()
            } catch (throwable: Throwable) {
                if (!closed.get()) {
                    mutableState.value = mutableState.value.copy(
                        errorMessage = throwable.message ?: "视频画面渲染失败",
                    )
                }
                Thread.sleep(100L)
            }
            Thread.sleep(VIDEO_RENDER_INTERVAL_MS)
        }
    }

    private fun setOption(name: String, value: String) {
        checkMpv(DesktopJniMpvVideoApi.nativeSetOption(handle, name, value), "set video option $name")
    }

    private fun setProperty(name: String, value: String) {
        checkMpv(DesktopJniMpvVideoApi.nativeSetProperty(handle, name, value), "set video property $name")
    }

    private fun getProperty(name: String): String? = DesktopJniMpvVideoApi.nativeGetProperty(handle, name)

    private fun command(vararg args: String) {
        checkMpv(
            DesktopJniMpvVideoApi.nativeCommand(handle, args),
            "video command ${args.firstOrNull().orEmpty()}",
        )
    }

    private fun checkMpv(result: Int, operation: String) {
        if (result >= 0) return
        val detail = DesktopJniMpvVideoApi.nativeErrorString(result) ?: "error $result"
        throw IllegalStateException("libmpv $operation failed: $detail")
    }

    private fun ensureOpen() {
        check(!closed.get()) { "libmpv video controller is closed" }
    }
}

internal data class DesktopJniVideoEndEvent(
    val reason: Int,
    val error: Int,
)

internal fun parseDesktopJniVideoEndEvent(encoded: String): DesktopJniVideoEndEvent? {
    val fields = encoded.split(':', limit = 4)
    if (fields.size != 4 || fields[0] != "end") return null
    if (fields[1].toLongOrNull() == null) return null
    return DesktopJniVideoEndEvent(
        reason = fields[2].toIntOrNull() ?: return null,
        error = fields[3].toIntOrNull() ?: return null,
    )
}

internal fun boundedDesktopJniVideoRenderSize(width: Int, height: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 0 to 0
    val pixels = width.toLong() * height.toLong()
    if (pixels <= MAX_SOFTWARE_RENDER_PIXELS) return width to height
    val scale = sqrt(MAX_SOFTWARE_RENDER_PIXELS.toDouble() / pixels.toDouble())
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

internal fun encodeDesktopJniVideoLoadfileOptions(
    headers: Map<String, String>,
    externalAudioUrl: String?,
): String = buildList {
    encodeDesktopJniHttpOptions(headers).takeIf(String::isNotBlank)?.let(::add)
    externalAudioUrl?.takeIf(String::isNotBlank)?.let { url ->
        add("audio-files-append=${desktopJniMpvFixedLength(url)}")
    }
}.joinToString(",")

private fun encodeDesktopJniHttpOptions(headers: Map<String, String>): String {
    val sanitized = headers.mapNotNull { (name, value) ->
        if (name.isBlank() || name.any(::isHeaderLineBreak) || value.any(::isHeaderLineBreak)) null
        else name to value
    }
    if (sanitized.isEmpty()) return ""

    val userAgent = sanitized
        .firstOrNull { (name, _) -> name.equals("User-Agent", ignoreCase = true) }
        ?.second
    val headerFields = sanitized
        .filterNot { (name, _) -> name.equals("User-Agent", ignoreCase = true) }
        .map { (name, value) -> escapeMpvStringListItem("$name: $value") }
        .joinToString(",")

    return buildList {
        userAgent?.let { add("user-agent=${desktopJniMpvFixedLength(it)}") }
        if (headerFields.isNotEmpty()) add("http-header-fields=${desktopJniMpvFixedLength(headerFields)}")
    }.joinToString(",")
}

private fun escapeMpvStringListItem(value: String): String = buildString(value.length) {
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            ',' -> append("\\,")
            else -> append(char)
        }
    }
}

private fun desktopJniMpvFixedLength(value: String): String =
    "%${value.toByteArray(StandardCharsets.UTF_8).size}%$value"

private fun isHeaderLineBreak(char: Char): Boolean = char == '\r' || char == '\n'

private fun String?.secondsToMsOrNull(): Long? =
    this?.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { (it * 1000.0).toLong() }

private fun alignTo64(value: Int): Int = ((value + 63) / 64) * 64

private object DesktopJniMpvVideoApi {
    external fun nativeCreate(): Long
    external fun nativeInitialize(handle: Long): Int
    external fun nativeSetOption(handle: Long, name: String, value: String): Int
    external fun nativeSetProperty(handle: Long, name: String, value: String): Int
    external fun nativeGetProperty(handle: Long, name: String): String?
    external fun nativeCommand(handle: Long, args: Array<out String>): Int
    external fun nativeWaitEvent(handle: Long, timeoutSeconds: Double): String?
    external fun nativeWakeup(handle: Long)
    external fun nativeDestroy(handle: Long)
    external fun nativeErrorString(error: Int): String?
    external fun nativeCreateSoftwareRenderContext(handle: Long): Long
    external fun nativeRenderSoftware(
        renderContext: Long,
        width: Int,
        height: Int,
        stride: Int,
        pixels: ByteArray,
    ): Int
    external fun nativeFreeRenderContext(renderContext: Long)
}

private object DesktopJniMpvVideoBridgeLoader {
    @Volatile private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val bridge = resolveDesktopJniMpvVideoBridge()
                ?: throw UnsatisfiedLinkError(
                    "Nucleus libmpv JNI bridge not found in packaged resources or development build output",
                )
            System.load(bridge.absolutePath)
            loaded = true
            AppLogger.i("DesktopVideo", "loaded JNI video bridge ${bridge.absolutePath}")
        }
    }
}

private fun resolveDesktopJniMpvVideoBridge(): File? {
    val osName = System.getProperty("os.name").orEmpty()
    val libraryName = when {
        osName.contains("windows", ignoreCase = true) -> "fuoevolve_mpv_jni.dll"
        osName.contains("mac", ignoreCase = true) || osName.contains("darwin", ignoreCase = true) ->
            "libfuoevolve_mpv_jni.dylib"
        else -> "libfuoevolve_mpv_jni.so"
    }
    val resourcesDir = System.getProperty("compose.application.resources.dir")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
    val userDir = File(System.getProperty("user.dir").orEmpty().ifBlank { "." })
    return buildList {
        resourcesDir?.let { add(File(it, "native/lib/$libraryName")) }
        add(File(userDir, "desktopNucleusPoc/build/native/mpv-jni/$libraryName"))
        add(File(userDir, "build/native/mpv-jni/$libraryName"))
    }.firstOrNull(File::isFile)
}

private const val EVENT_WAIT_SECONDS = 0.05
private const val STATE_POLL_INTERVAL_NANOS = 100_000_000L
private const val MPV_END_FILE_REASON_ERROR = 4
private const val BYTES_PER_PIXEL = 4
private const val VIDEO_RENDER_INTERVAL_MS = 33L
private const val MAX_SOFTWARE_RENDER_PIXELS = 1920L * 1080L
