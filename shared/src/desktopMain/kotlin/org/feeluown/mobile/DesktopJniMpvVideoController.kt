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
 * GPU-facing extension used by the Nucleus/Tao host. The host owns the GL context lifetime and
 * calls these methods only while Tao has made that context current.
 */
interface DesktopOpenGlVideoController {
    fun createOpenGlRenderContext(): Long
    fun updateOpenGlRenderContext(renderContext: Long): Boolean
    fun createOpenGlRenderTarget(width: Int, height: Int): Long
    fun openGlRenderTargetFramebuffer(renderTarget: Long): Int
    fun renderOpenGl(renderContext: Long, renderTarget: Long)
    fun reportOpenGlSwap(renderContext: Long)
    fun destroyOpenGlRenderTarget(renderTarget: Long)
    fun destroyOpenGlRenderContext(renderContext: Long)
    fun enableSoftwareRendering()
}

/**
 * GraalVM-friendly desktop video controller backed by the same libmpv JNI bridge packaged by the
 * Nucleus desktop runtime. The legacy JVM host installs its JNA controller explicitly, so this
 * controller is only used by the Native/Nucleus host.
 *
 * A render context is intentionally created lazily. Windows/Linux attach libmpv to Tao's active
 * OpenGL/ANGLE context. macOS and any failed GPU setup explicitly enable the software fallback.
 */
internal class DesktopJniMpvVideoController :
    DesktopPlatformVideoController,
    DesktopOpenGlVideoController {
    private val closed = AtomicBoolean(false)
    private val renderContextLock = Any()
    private val mutableState = MutableStateFlow(PlatformVideoPlaybackState())
    override val state: StateFlow<PlatformVideoPlaybackState> = mutableState.asStateFlow()
    private val mutableFrame = MutableStateFlow<ImageBitmap?>(null)
    override val frame: StateFlow<ImageBitmap?> = mutableFrame.asStateFlow()

    private val handle: Long
    private val eventThread: Thread
    private val renderThread: Thread

    @Volatile private var viewportWidth = 0
    @Volatile private var viewportHeight = 0
    @Volatile private var playbackActive = false
    @Volatile private var softwareRenderContext = 0L
    @Volatile private var openGlRenderContext = 0L
    @Volatile private var lastPipelineDescription: String? = null

    init {
        DesktopJniMpvVideoBridgeLoader.ensureLoaded()
        handle = DesktopJniMpvVideoApi.nativeCreate()
        check(handle != 0L) { "libmpv mpv_create() returned null for video" }
        try {
            setOption("config", "no")
            setOption("terminal", "no")
            setOption("input-default-bindings", "no")
            setOption("ytdl", "no")
            setOption("vo", "libmpv")
            // Prefer the OS hardware decoder. libmpv automatically falls back to software when
            // the codec, device, driver, or GPU interop path cannot satisfy the request.
            setOption("hwdec", "auto")
            setOption("audio-display", "no")
            checkMpv(DesktopJniMpvVideoApi.nativeInitialize(handle), "mpv_initialize video")
        } catch (throwable: Throwable) {
            DesktopJniMpvVideoApi.nativeDestroy(handle)
            throw throwable
        }
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
        lastPipelineDescription = null
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

    override fun createOpenGlRenderContext(): Long = synchronized(renderContextLock) {
        ensureOpen()
        check(softwareRenderContext == 0L) {
            "software libmpv video renderer is already active"
        }
        if (openGlRenderContext != 0L) return@synchronized openGlRenderContext
        val context = DesktopJniMpvVideoApi.nativeCreateOpenGlRenderContext(handle)
        check(context != 0L) { "libmpv OpenGL video render context creation failed" }
        openGlRenderContext = context
        AppLogger.i("DesktopVideo", "attached libmpv OpenGL renderer with hwdec=auto")
        context
    }

    override fun updateOpenGlRenderContext(renderContext: Long): Boolean {
        ensureOpenGlContext(renderContext)
        return DesktopJniMpvVideoApi.nativeUpdateRenderContext(renderContext) and
            MPV_RENDER_UPDATE_FRAME != 0L
    }

    override fun createOpenGlRenderTarget(width: Int, height: Int): Long {
        ensureOpen()
        require(width > 0 && height > 0) { "OpenGL video render target must have positive dimensions" }
        val target = DesktopJniMpvVideoApi.nativeCreateOpenGlRenderTarget(width, height)
        check(target != 0L) { "OpenGL video render target creation failed" }
        return target
    }

    override fun openGlRenderTargetFramebuffer(renderTarget: Long): Int {
        ensureOpen()
        val framebuffer = DesktopJniMpvVideoApi.nativeOpenGlRenderTargetFramebuffer(renderTarget)
        check(framebuffer > 0) { "OpenGL video render target has no framebuffer" }
        return framebuffer
    }

    override fun renderOpenGl(renderContext: Long, renderTarget: Long) {
        ensureOpenGlContext(renderContext)
        check(renderTarget != 0L) { "OpenGL video render target is closed" }
        DesktopJniMpvVideoApi.nativeRenderOpenGl(renderContext, renderTarget)
    }

    override fun reportOpenGlSwap(renderContext: Long) {
        ensureOpenGlContext(renderContext)
        DesktopJniMpvVideoApi.nativeReportSwap(renderContext)
    }

    override fun destroyOpenGlRenderTarget(renderTarget: Long) {
        if (renderTarget != 0L) DesktopJniMpvVideoApi.nativeDestroyOpenGlRenderTarget(renderTarget)
    }

    override fun destroyOpenGlRenderContext(renderContext: Long) {
        if (renderContext == 0L) return
        synchronized(renderContextLock) {
            if (openGlRenderContext != renderContext) return
            DesktopJniMpvVideoApi.nativeFreeRenderContext(renderContext)
            openGlRenderContext = 0L
            lastPipelineDescription = null
        }
    }

    override fun enableSoftwareRendering() {
        synchronized(renderContextLock) {
            ensureOpen()
            if (softwareRenderContext != 0L) return
            check(openGlRenderContext == 0L) {
                "OpenGL libmpv video renderer is already active"
            }
            val context = DesktopJniMpvVideoApi.nativeCreateSoftwareRenderContext(handle)
            check(context != 0L) { "libmpv software video render context creation failed" }
            softwareRenderContext = context
            AppLogger.w("DesktopVideo", "using software video rendering fallback")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        playbackActive = false
        DesktopJniMpvVideoApi.nativeWakeup(handle)
        if (Thread.currentThread() !== eventThread) runCatching { eventThread.join(2_000) }
        if (Thread.currentThread() !== renderThread) runCatching { renderThread.join(2_000) }

        val gpuContextStillAttached = synchronized(renderContextLock) {
            softwareRenderContext.takeIf { it != 0L }?.let { context ->
                DesktopJniMpvVideoApi.nativeFreeRenderContext(context)
                softwareRenderContext = 0L
            }
            openGlRenderContext != 0L
        }
        if (gpuContextStillAttached) {
            // The normal Compose lifecycle disposes the host GPU surface first, while its GL
            // context can still be made current. Do not attempt OpenGL teardown without that
            // context here; leaking only on an abnormal disposal order is safer than a driver
            // crash. The process will reclaim it on exit.
            AppLogger.w(
                "DesktopVideo",
                "OpenGL video surface still attached during controller close; deferring native teardown",
            )
            mutableFrame.value = null
            return
        }

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
                            publishPipelineIfChanged()
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
        publishPipelineIfChanged()
    }

    private fun publishPipelineIfChanged() {
        val renderer = when {
            openGlRenderContext != 0L -> "opengl-gpu"
            softwareRenderContext != 0L -> "software"
            else -> "pending"
        }
        val hwdec = getProperty("hwdec-current")
            ?.takeIf { it.isNotBlank() && !it.equals("no", ignoreCase = true) }
            ?: "software"
        val codec = getProperty("video-codec")?.takeIf(String::isNotBlank) ?: "unknown"
        val description = "decoder=$hwdec renderer=$renderer codec=$codec"
        if (description == lastPipelineDescription) return
        lastPipelineDescription = description
        AppLogger.i("DesktopVideo", description)
    }

    private fun renderLoop() {
        while (!closed.get()) {
            val renderContext = softwareRenderContext
            if (renderContext == 0L) {
                Thread.sleep(80L)
                continue
            }
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

    private fun ensureOpenGlContext(renderContext: Long) {
        ensureOpen()
        check(renderContext != 0L && renderContext == openGlRenderContext) {
            "OpenGL libmpv video render context is not active"
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
    external fun nativeCreateOpenGlRenderContext(handle: Long): Long
    external fun nativeUpdateRenderContext(renderContext: Long): Long
    external fun nativeCreateOpenGlRenderTarget(width: Int, height: Int): Long
    external fun nativeOpenGlRenderTargetFramebuffer(renderTarget: Long): Int
    external fun nativeRenderOpenGl(renderContext: Long, renderTarget: Long)
    external fun nativeReportSwap(renderContext: Long)
    external fun nativeDestroyOpenGlRenderTarget(renderTarget: Long)
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
private const val MPV_RENDER_UPDATE_FRAME = 1L
private const val BYTES_PER_PIXEL = 4
private const val VIDEO_RENDER_INTERVAL_MS = 33L
private const val MAX_SOFTWARE_RENDER_PIXELS = 1920L * 1080L
