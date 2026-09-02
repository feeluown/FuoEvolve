package org.feeluown.mobile.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.feeluown.mobile.DesktopPlatformVideoController
import org.feeluown.mobile.PlatformVideoPlaybackState
import org.feeluown.mobile.VideoPlaybackPayload
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

internal class DesktopMpvVideoController(
    private val library: MpvVideoNative = loadMpvVideoLibrary(),
) : DesktopPlatformVideoController {
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(PlatformVideoPlaybackState())
    override val state: StateFlow<PlatformVideoPlaybackState> = mutableState.asStateFlow()
    private val mutableFrame = MutableStateFlow<ImageBitmap?>(null)
    override val frame: StateFlow<ImageBitmap?> = mutableFrame.asStateFlow()

    private val handle: Pointer
    private val renderContext: Pointer
    private val eventThread: Thread
    private val renderThread: Thread

    @Volatile private var viewportWidth = 0
    @Volatile private var viewportHeight = 0
    @Volatile private var playbackActive = false

    init {
        handle = library.mpv_create()
            ?: throw IllegalStateException("libmpv mpv_create() returned null for video")
        var createdRenderContext: Pointer? = null
        try {
            setOption("config", "no")
            setOption("terminal", "no")
            setOption("input-default-bindings", "no")
            setOption("ytdl", "no")
            setOption("vo", "libmpv")
            setOption("hwdec", "no")
            setOption("audio-display", "no")
            checkMpv(library.mpv_initialize(handle), "mpv_initialize video")
            createdRenderContext = createSoftwareRenderContext()
            VIDEO_OBSERVED_PROPERTIES.forEach { property ->
                checkMpv(
                    library.mpv_observe_property(handle, 0L, property, MPV_FORMAT_STRING),
                    "observe video $property",
                )
            }
        } catch (throwable: Throwable) {
            createdRenderContext?.let(library::mpv_render_context_free)
            library.mpv_terminate_destroy(handle)
            throw throwable
        }
        renderContext = checkNotNull(createdRenderContext)
        eventThread = thread(
            start = true,
            isDaemon = true,
            name = "fuoevolve-libmpv-video-events",
            block = ::eventLoop,
        )
        renderThread = thread(
            start = true,
            isDaemon = true,
            name = "fuoevolve-libmpv-video-render",
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
        val options = encodeVideoLoadfileOptions(payload.headers, externalAudio)
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
        library.mpv_wakeup(handle)
        if (Thread.currentThread() !== eventThread) runCatching { eventThread.join(2_000) }
        if (Thread.currentThread() !== renderThread) runCatching { renderThread.join(2_000) }
        library.mpv_render_context_free(renderContext)
        library.mpv_terminate_destroy(handle)
        mutableFrame.value = null
    }

    private fun createSoftwareRenderContext(): Pointer {
        val apiType = Memory(3).apply { setString(0, MPV_RENDER_API_TYPE_SW) }
        val params = renderParams(2)
        params[0].type = MPV_RENDER_PARAM_API_TYPE
        params[0].data = apiType
        params[1].type = MPV_RENDER_PARAM_INVALID
        writeParams(params)
        val result = PointerByReference()
        checkMpv(
            library.mpv_render_context_create(result, handle, params.first().pointer),
            "create software video render context",
        )
        return result.value ?: throw IllegalStateException("libmpv returned null video render context")
    }

    private fun eventLoop() {
        try {
            while (!closed.get()) {
                val pointer = library.mpv_wait_event(handle, -1.0)
                val event = VideoMpvEvent(pointer)
                when (event.eventId) {
                    MPV_EVENT_NONE -> Unit
                    MPV_EVENT_SHUTDOWN -> break
                    MPV_EVENT_PLAYBACK_RESTART -> {
                        playbackActive = true
                        mutableState.value = mutableState.value.copy(isPlaying = true, errorMessage = null)
                    }
                    MPV_EVENT_PROPERTY_CHANGE -> {
                        val data = event.data ?: continue
                        val property = VideoMpvEventProperty(data)
                        handleProperty(
                            property.name?.getString(0, StandardCharsets.UTF_8.name()).orEmpty(),
                            property.stringValue(),
                        )
                    }
                    MPV_EVENT_END_FILE -> {
                        val data = event.data ?: continue
                        val endFile = VideoMpvEndFile(data)
                        playbackActive = false
                        if (endFile.reason == MPV_END_FILE_REASON_ERROR && endFile.error < 0) {
                            mutableState.value = mutableState.value.copy(
                                isPlaying = false,
                                errorMessage = library.mpv_error_string(endFile.error)
                                    ?.let { "视频播放失败：$it" }
                                    ?: "视频播放失败",
                            )
                        } else {
                            mutableState.value = mutableState.value.copy(isPlaying = false)
                        }
                    }
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

    private fun handleProperty(name: String, value: String?) {
        when (name) {
            "pause" -> mutableState.value = mutableState.value.copy(
                isPlaying = playbackActive && value != "yes" && value != "true",
            )
            "time-pos" -> value.secondsToMsOrNull()?.let { position ->
                mutableState.value = mutableState.value.copy(positionMs = position.coerceAtLeast(0L))
            }
            "duration" -> value.secondsToMsOrNull()?.let { duration ->
                mutableState.value = mutableState.value.copy(durationMs = duration.coerceAtLeast(0L))
            }
            "demuxer-cache-time" -> value.secondsToMsOrNull()?.let { buffered ->
                val duration = mutableState.value.durationMs
                mutableState.value = mutableState.value.copy(
                    bufferedMs = if (duration > 0) buffered.coerceIn(0, duration) else buffered.coerceAtLeast(0),
                )
            }
            "video-params/w" -> value?.toIntOrNull()?.let { width ->
                mutableState.value = mutableState.value.copy(videoWidth = width.coerceAtLeast(0))
            }
            "video-params/h" -> value?.toIntOrNull()?.let { height ->
                mutableState.value = mutableState.value.copy(videoHeight = height.coerceAtLeast(0))
            }
        }
    }

    private fun renderLoop() {
        var surface: SoftwareRenderSurface? = null
        while (!closed.get()) {
            val target = boundedVideoRenderSize(viewportWidth, viewportHeight)
            if (!playbackActive || target.first <= 0 || target.second <= 0) {
                Thread.sleep(if (playbackActive) 30L else 80L)
                continue
            }
            try {
                if (surface == null || surface.width != target.first || surface.height != target.second) {
                    surface = SoftwareRenderSurface(target.first, target.second)
                }
                val currentSurface = surface
                renderTo(currentSurface)
                val bytes = currentSurface.pixels.getByteArray(0, currentSurface.byteSize)
                val imageInfo = ImageInfo.makeN32(
                    currentSurface.width,
                    currentSurface.height,
                    ColorAlphaType.OPAQUE,
                )
                mutableFrame.value = Image.makeRaster(
                    imageInfo,
                    bytes,
                    currentSurface.stride,
                ).toComposeImageBitmap()
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

    private fun renderTo(surface: SoftwareRenderSurface) {
        val size = Memory(8).apply {
            setInt(0, surface.width)
            setInt(4, surface.height)
        }
        val format = Memory(5).apply { setString(0, MPV_SW_PIXEL_FORMAT) }
        val stride = Memory(Native.SIZE_T_SIZE.toLong()).apply {
            if (Native.SIZE_T_SIZE == Long.SIZE_BYTES) setLong(0, surface.stride.toLong())
            else setInt(0, surface.stride)
        }
        val params = renderParams(5)
        params[0].type = MPV_RENDER_PARAM_SW_SIZE
        params[0].data = size
        params[1].type = MPV_RENDER_PARAM_SW_FORMAT
        params[1].data = format
        params[2].type = MPV_RENDER_PARAM_SW_STRIDE
        params[2].data = stride
        params[3].type = MPV_RENDER_PARAM_SW_POINTER
        params[3].data = surface.pixels
        params[4].type = MPV_RENDER_PARAM_INVALID
        writeParams(params)
        checkMpv(
            library.mpv_render_context_render(renderContext, params.first().pointer),
            "render software video frame",
        )
    }

    private fun setOption(name: String, value: String) {
        checkMpv(library.mpv_set_option_string(handle, name, value), "set video option $name")
    }

    private fun setProperty(name: String, value: String) {
        checkMpv(library.mpv_set_property_string(handle, name, value), "set video property $name")
    }

    private fun command(vararg args: String) {
        checkMpv(library.mpv_command(handle, StringArray(args)), "video command ${args.firstOrNull().orEmpty()}")
    }

    private fun checkMpv(result: Int, operation: String) {
        if (result >= 0) return
        val detail = library.mpv_error_string(result) ?: "error $result"
        throw IllegalStateException("libmpv $operation failed: $detail")
    }

    private fun ensureOpen() {
        check(!closed.get()) { "libmpv video controller is closed" }
    }
}

internal fun boundedVideoRenderSize(width: Int, height: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 0 to 0
    val pixels = width.toLong() * height.toLong()
    if (pixels <= MAX_SOFTWARE_RENDER_PIXELS) return width to height
    val scale = sqrt(MAX_SOFTWARE_RENDER_PIXELS.toDouble() / pixels.toDouble())
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

private class SoftwareRenderSurface(
    val width: Int,
    val height: Int,
) {
    val stride: Int = alignTo64(width * BYTES_PER_PIXEL)
    val byteSize: Long = stride.toLong() * height
    val pixels = Memory(byteSize)
}

internal fun encodeVideoLoadfileOptions(
    headers: Map<String, String>,
    externalAudioUrl: String?,
): String = buildList {
    encodeMpvLoadfileOptions(headers).takeIf(String::isNotBlank)?.let(::add)
    externalAudioUrl?.takeIf(String::isNotBlank)?.let { url ->
        add("audio-files-append=${mpvVideoFixedLength(url)}")
    }
}.joinToString(",")

private fun mpvVideoFixedLength(value: String): String {
    val byteLength = value.toByteArray(StandardCharsets.UTF_8).size
    return "%$byteLength%$value"
}

private fun alignTo64(value: Int): Int = ((value + 63) / 64) * 64

private fun renderParams(count: Int): List<MpvRenderParam> =
    MpvRenderParam().toArray(count).map { it as MpvRenderParam }

private fun writeParams(params: List<MpvRenderParam>) = params.forEach(Structure::write)

internal interface MpvVideoNative : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_command(ctx: Pointer, args: Pointer): Int
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_error_string(error: Int): String?
    fun mpv_render_context_create(result: PointerByReference, mpv: Pointer, params: Pointer): Int
    fun mpv_render_context_render(context: Pointer, params: Pointer): Int
    fun mpv_render_context_free(context: Pointer)
}

private class MpvRenderParam : Structure() {
    @JvmField var type: Int = 0
    @JvmField var data: Pointer? = null
    override fun getFieldOrder(): List<String> = listOf("type", "data")
}

private class VideoMpvEvent(pointer: Pointer) : Structure(pointer) {
    @JvmField var eventId: Int = 0
    @JvmField var error: Int = 0
    @JvmField var replyUserdata: Long = 0L
    @JvmField var data: Pointer? = null
    override fun getFieldOrder(): List<String> = listOf("eventId", "error", "replyUserdata", "data")
    init { read() }
}

private class VideoMpvEventProperty(pointer: Pointer) : Structure(pointer) {
    @JvmField var name: Pointer? = null
    @JvmField var format: Int = 0
    @JvmField var data: Pointer? = null
    override fun getFieldOrder(): List<String> = listOf("name", "format", "data")
    init { read() }
    fun stringValue(): String? {
        if (format != MPV_FORMAT_STRING) return null
        val stringPointer = data?.getPointer(0L) ?: return null
        return stringPointer.getString(0L, StandardCharsets.UTF_8.name())
    }
}

private class VideoMpvEndFile(pointer: Pointer) : Structure(pointer) {
    @JvmField var reason: Int = 0
    @JvmField var error: Int = 0
    @JvmField var playlistEntryId: Long = 0L
    @JvmField var playlistInsertId: Long = 0L
    @JvmField var playlistInsertNumEntries: Int = 0
    override fun getFieldOrder(): List<String> = listOf(
        "reason", "error", "playlistEntryId", "playlistInsertId", "playlistInsertNumEntries",
    )
    init { read() }
}

private fun loadMpvVideoLibrary(): MpvVideoNative {
    val explicit = System.getProperty("fuoevolve.libmpv.path")
        ?.takeIf(String::isNotBlank)
        ?: System.getenv("FUOEVOLVE_LIBMPV_PATH")?.takeIf(String::isNotBlank)
    val candidates = buildList {
        explicit?.let(::add)
        when {
            Platform.isWindows() -> addAll(listOf("mpv-2", "mpv"))
            Platform.isMac() -> addAll(listOf("mpv", "libmpv.dylib"))
            else -> addAll(listOf("mpv", "libmpv.so.2", "libmpv.so"))
        }
    }.distinct()
    var lastFailure: Throwable? = null
    candidates.forEach { candidate ->
        try {
            return Native.load(candidate, MpvVideoNative::class.java)
        } catch (throwable: Throwable) {
            lastFailure = throwable
        }
    }
    throw IllegalStateException(
        "Unable to load libmpv video renderer from ${candidates.joinToString()}",
        lastFailure,
    )
}

private fun String?.secondsToMsOrNull(): Long? = this
    ?.toDoubleOrNull()
    ?.takeIf(Double::isFinite)
    ?.let { seconds -> (seconds * 1000.0).toLong() }

private const val MPV_FORMAT_STRING = 1
private const val MPV_EVENT_NONE = 0
private const val MPV_EVENT_SHUTDOWN = 1
private const val MPV_EVENT_END_FILE = 7
private const val MPV_EVENT_PLAYBACK_RESTART = 21
private const val MPV_EVENT_PROPERTY_CHANGE = 22
private const val MPV_END_FILE_REASON_ERROR = 4
private const val MPV_RENDER_PARAM_INVALID = 0
private const val MPV_RENDER_PARAM_API_TYPE = 1
private const val MPV_RENDER_PARAM_SW_SIZE = 17
private const val MPV_RENDER_PARAM_SW_FORMAT = 18
private const val MPV_RENDER_PARAM_SW_STRIDE = 19
private const val MPV_RENDER_PARAM_SW_POINTER = 20
private const val MPV_RENDER_API_TYPE_SW = "sw"
private const val MPV_SW_PIXEL_FORMAT = "bgr0"
private const val BYTES_PER_PIXEL = 4
private const val VIDEO_RENDER_INTERVAL_MS = 33L
private const val MAX_SOFTWARE_RENDER_PIXELS = 1920L * 1080L

private val VIDEO_OBSERVED_PROPERTIES = listOf(
    "pause",
    "time-pos",
    "duration",
    "demuxer-cache-time",
    "video-params/w",
    "video-params/h",
)
