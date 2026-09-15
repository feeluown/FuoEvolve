package org.feeluown.mobile

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.skia.Bitmap
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
 * Windows GPU extension. The render target is a plain shared D3D11 texture owned by ANGLE;
 * libmpv renders directly into its EGL pbuffer and Nucleus TextureView imports the shared handle.
 */
interface DesktopWindowsD3D11VideoController : DesktopOpenGlVideoController {
    fun createD3D11RenderTarget(renderContext: Long, width: Int, height: Int): Long
    fun d3D11RenderTargetSharedHandle(renderTarget: Long): Long
    fun renderD3D11(renderContext: Long, renderTarget: Long): Boolean
    fun destroyD3D11RenderTarget(renderTarget: Long)
}

/** Native handles owned by the active Tao OpenGL context. */
data class DesktopOpenGlRenderContextParameters(
    val nativeDisplayKind: Int,
    val nativeDisplay: Long,
    val taoGetProcAddress: Long,
)

/**
 * macOS GPU extension. libmpv renders on a private accelerated CGL context into an
 * IOSurface-backed FBO; Nucleus imports that IOSurface into its Metal scene via TextureView.
 */
interface DesktopIoSurfaceVideoController {
    fun createIoSurfaceRenderContext(): Long
    fun createIoSurfaceRenderTarget(renderContext: Long, width: Int, height: Int): Long
    fun ioSurfaceRenderTargetPointer(renderTarget: Long): Long
    fun renderIoSurface(renderContext: Long, renderTarget: Long): Boolean
    fun destroyIoSurfaceRenderTarget(renderContext: Long, renderTarget: Long)
    fun destroyIoSurfaceRenderContext(renderContext: Long)
}

internal data class DesktopJniVideoSourceCandidate(
    val url: String = "",
    val videoUrl: String = "",
    val audioUrl: String = "",
) {
    val mainUrl: String
        get() = url.ifBlank { videoUrl }

    val externalAudioUrl: String?
        get() = if (url.isBlank()) audioUrl.takeIf(String::isNotBlank) else null

    fun debugDescription(): String = if (url.isNotBlank()) "combined" else "split-dash"
}

/**
 * GraalVM-friendly desktop video controller backed by the desktop libmpv FFM bridge.
 *
 * A render context is intentionally created lazily. Windows/Linux attach libmpv to Tao's active
 * OpenGL/ANGLE context. macOS renders into IOSurface on a private CGL context and lets Tao/Metal
 * import the surface. GPU setup failures are reported instead of silently switching to software
 * video in the Native/Nucleus surface.
 */
internal class DesktopJniMpvVideoController(
    nativeApi: DesktopMpvNativeApi,
    private val videoDecodeMode: DesktopVideoDecodeMode,
    private val openGlRenderContextParameters: DesktopOpenGlRenderContextParameters? = null,
) :
    DesktopPlatformVideoController,
    DesktopWindowsD3D11VideoController,
    DesktopIoSurfaceVideoController {
    private val DesktopJniMpvVideoApi = DesktopMpvVideoApiAdapter(nativeApi)
    private val closed = AtomicBoolean(false)
    private val renderContextLock = Any()
    private val mutableState = MutableStateFlow(PlatformVideoPlaybackState())
    override val state: StateFlow<PlatformVideoPlaybackState> = mutableState.asStateFlow()
    private val mutableFrame = MutableStateFlow<ImageBitmap?>(null)
    override val frame: StateFlow<ImageBitmap?> = mutableFrame.asStateFlow()
    private val softwareFrameLock = Any()
    private var currentSoftwareFrame: DesktopSoftwareFrame? = null
    private val retiredSoftwareFrames = ArrayDeque<DesktopSoftwareFrame>()

    private val handle: Long
    private val nativeDestroyed = AtomicBoolean(false)
    private val eventThread: Thread
    private val renderThread: Thread

    @Volatile private var viewportWidth = 0
    @Volatile private var viewportHeight = 0
    @Volatile private var playbackActive = false
    @Volatile private var playWhenReady = false
    @Volatile private var reachedEof = false
    @Volatile private var softwareFrameDirty = false
    @Volatile private var activePayload: VideoPlaybackPayload? = null
    @Volatile private var activeCandidates: List<DesktopJniVideoSourceCandidate> = emptyList()
    @Volatile private var activeCandidateIndex = -1
    @Volatile private var hardwareDecoderRejected = false
    @Volatile private var activePlaylistEntryId: Long? = null
    @Volatile private var softwareRenderContext = 0L
    @Volatile private var openGlRenderContext = 0L
    @Volatile private var ioSurfaceRenderContext = 0L
    @Volatile private var d3D11DirectTargetCount = 0
    @Volatile private var lastPipelineDescription: String? = null

    init {
        handle = DesktopJniMpvVideoApi.nativeCreate()
        check(handle != 0L) { "libmpv mpv_create() returned null for video" }
        try {
            setOption("config", "no")
            setOption("terminal", "no")
            setOption("input-default-bindings", "no")
            setOption("ytdl", "no")
            setOption("vo", "libmpv")
            setOption("hwdec", desktopVideoHwdecOption(videoDecodeMode))
            if (videoDecodeMode != DesktopVideoDecodeMode.Software) {
                setOption("hwdec-software-fallback", "no")
            }
            desktopVideoHwdecInteropOption(videoDecodeMode)?.let { interop ->
                setOption("gpu-hwdec-interop", interop)
            }
            setOption("audio-display", "no")
            checkMpv(DesktopJniMpvVideoApi.nativeInitialize(handle), "mpv_initialize video")
            VIDEO_OBSERVED_PROPERTIES.forEachIndexed { index, property ->
                checkMpv(
                    DesktopJniMpvVideoApi.nativeObserveProperty(handle, index.toLong() + 1L, property),
                    "observe video property $property",
                )
            }
        } catch (throwable: Throwable) {
            DesktopJniMpvVideoApi.nativeDestroy(handle)
            throw throwable
        }
        eventThread = thread(
            start = true,
            isDaemon = true,
            name = "fuoevolve-ffm-libmpv-video-events",
            block = ::eventLoop,
        )
        renderThread = thread(
            start = true,
            isDaemon = true,
            name = "fuoevolve-ffm-libmpv-video-render",
            block = ::renderLoop,
        )
    }

    override fun setPayload(payload: VideoPlaybackPayload?) {
        ensureOpen()
        mutableFrame.value = null
        lastPipelineDescription = null
        activePlaylistEntryId = null
        reachedEof = false
        softwareFrameDirty = true

        if (payload == null) {
            activePayload = null
            activeCandidates = emptyList()
            activeCandidateIndex = -1
            playbackActive = false
            playWhenReady = false
            command("stop")
            closeSoftwareFrames()
            mutableState.value = PlatformVideoPlaybackState()
            return
        }

        val candidates = desktopJniVideoSourceCandidates(payload)
        if (candidates.isEmpty()) {
            activePayload = null
            activeCandidates = emptyList()
            activeCandidateIndex = -1
            playbackActive = false
            playWhenReady = false
            closeSoftwareFrames()
            mutableState.value = PlatformVideoPlaybackState(errorMessage = "当前视频没有可播放地址")
            return
        }

        activePayload = payload
        activeCandidates = candidates
        activeCandidateIndex = 0
        hardwareDecoderRejected = false
        mutableState.value = PlatformVideoPlaybackState()
        prepareCandidate(index = 0, positionMs = 0L, shouldPlay = true)
    }

    override fun setViewportSize(width: Int, height: Int) {
        val newWidth = width.coerceAtLeast(0)
        val newHeight = height.coerceAtLeast(0)
        if (newWidth != viewportWidth || newHeight != viewportHeight) {
            softwareFrameDirty = true
        }
        viewportWidth = newWidth
        viewportHeight = newHeight
    }

    override fun play() {
        ensureOpen()
        playWhenReady = true
        softwareFrameDirty = true
        val current = mutableState.value
        if (
            activeCandidateIndex in activeCandidates.indices &&
            shouldRestartDesktopJniVideoPlayback(
                reachedEof = reachedEof,
                playbackActive = playbackActive,
                positionMs = current.positionMs,
                durationMs = current.durationMs,
            )
        ) {
            val restartPosition = if (reachedEof) 0L else current.positionMs.coerceAtLeast(0L)
            prepareCandidate(activeCandidateIndex, restartPosition, shouldPlay = true)
            return
        }
        setProperty("pause", "no")
    }

    override fun pause() {
        ensureOpen()
        playWhenReady = false
        setProperty("pause", "yes")
    }

    override fun seekTo(positionMs: Long) {
        ensureOpen()
        val duration = mutableState.value.durationMs
        val target = if (duration > 0L) {
            positionMs.coerceIn(0L, duration)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        softwareFrameDirty = true
        if (!playbackActive && activeCandidateIndex in activeCandidates.indices) {
            prepareCandidate(activeCandidateIndex, target, shouldPlay = playWhenReady)
        } else {
            command("seek", (target / 1000.0).toString(), "absolute")
        }
    }

    override fun createOpenGlRenderContext(): Long = synchronized(renderContextLock) {
        ensureOpen()
        check(softwareRenderContext == 0L && ioSurfaceRenderContext == 0L) {
            "another libmpv video renderer is already active"
        }
        if (openGlRenderContext != 0L) return@synchronized openGlRenderContext
        val context = DesktopJniMpvVideoApi.nativeCreateOpenGlRenderContext(
            handle = handle,
            directHardware = videoDecodeMode == DesktopVideoDecodeMode.HardwareDirect,
            nativeDisplayKind = openGlRenderContextParameters?.nativeDisplayKind ?: 0,
            nativeDisplay = openGlRenderContextParameters?.nativeDisplay ?: 0L,
            taoGetProcAddress = openGlRenderContextParameters?.taoGetProcAddress ?: 0L,
        )
        check(context != 0L) { "libmpv OpenGL video render context creation failed" }
        openGlRenderContext = context
        val displayKind = DesktopJniMpvVideoApi.nativeOpenGlRenderContextDisplayKind(context)
        AppLogger.i(
            "DesktopVideo",
            "attached libmpv OpenGL renderer with " +
                "hwdec=${desktopVideoHwdecOption(videoDecodeMode)} " +
                "interop=${desktopVideoHwdecInteropOption(videoDecodeMode) ?: "auto"} " +
                "display=${desktopVideoNativeDisplayDescription(displayKind)}",
        )
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

    override fun createD3D11RenderTarget(renderContext: Long, width: Int, height: Int): Long {
        ensureOpenGlContext(renderContext)
        require(width > 0 && height > 0) { "D3D11 video render target must have positive dimensions" }
        val target = DesktopJniMpvVideoApi.nativeCreateD3D11RenderTarget(renderContext, width, height)
        check(target != 0L) { "D3D11 shared libmpv render target creation failed" }
        d3D11DirectTargetCount += 1
        lastPipelineDescription = null
        return target
    }

    override fun d3D11RenderTargetSharedHandle(renderTarget: Long): Long {
        ensureOpen()
        val sharedHandle = DesktopJniMpvVideoApi.nativeD3D11RenderTargetSharedHandle(renderTarget)
        check(sharedHandle != 0L) { "D3D11 libmpv render target has no shared handle" }
        return sharedHandle
    }

    override fun renderD3D11(renderContext: Long, renderTarget: Long): Boolean {
        ensureOpenGlContext(renderContext)
        check(renderTarget != 0L) { "D3D11 libmpv render target is closed" }
        return DesktopJniMpvVideoApi.nativeRenderD3D11(renderContext, renderTarget)
    }

    override fun destroyD3D11RenderTarget(renderTarget: Long) {
        if (renderTarget == 0L) return
        DesktopJniMpvVideoApi.nativeDestroyD3D11RenderTarget(renderTarget)
        d3D11DirectTargetCount = (d3D11DirectTargetCount - 1).coerceAtLeast(0)
        lastPipelineDescription = null
    }

    override fun destroyOpenGlRenderContext(renderContext: Long) {
        if (renderContext == 0L) return
        synchronized(renderContextLock) {
            if (openGlRenderContext != renderContext) return@synchronized
            DesktopJniMpvVideoApi.nativeFreeOpenGlRenderContext(renderContext)
            openGlRenderContext = 0L
            d3D11DirectTargetCount = 0
            lastPipelineDescription = null
        }
        destroyNativeHandleIfReady()
    }

    override fun createIoSurfaceRenderContext(): Long = synchronized(renderContextLock) {
        ensureOpen()
        check(softwareRenderContext == 0L && openGlRenderContext == 0L) {
            "another libmpv video renderer is already active"
        }
        if (ioSurfaceRenderContext != 0L) return@synchronized ioSurfaceRenderContext
        val context = DesktopJniMpvVideoApi.nativeCreateIoSurfaceRenderContext(handle)
        check(context != 0L) { "libmpv macOS IOSurface render context creation failed" }
        ioSurfaceRenderContext = context
        AppLogger.i(
            "DesktopVideo",
            "attached libmpv IOSurface renderer with hwdec=${desktopVideoHwdecOption(videoDecodeMode)}",
        )
        context
    }

    override fun createIoSurfaceRenderTarget(renderContext: Long, width: Int, height: Int): Long {
        ensureIoSurfaceContext(renderContext)
        require(width > 0 && height > 0) { "IOSurface video target must have positive dimensions" }
        val target = DesktopJniMpvVideoApi.nativeCreateIoSurfaceRenderTarget(renderContext, width, height)
        check(target != 0L) { "macOS IOSurface video target creation failed" }
        return target
    }

    override fun ioSurfaceRenderTargetPointer(renderTarget: Long): Long {
        ensureOpen()
        val surface = DesktopJniMpvVideoApi.nativeIoSurfaceRenderTargetPointer(renderTarget)
        check(surface != 0L) { "macOS video target has no IOSurface" }
        return surface
    }

    override fun renderIoSurface(renderContext: Long, renderTarget: Long): Boolean {
        ensureIoSurfaceContext(renderContext)
        check(renderTarget != 0L) { "IOSurface video render target is closed" }
        return DesktopJniMpvVideoApi.nativeRenderIoSurface(renderContext, renderTarget)
    }

    override fun destroyIoSurfaceRenderTarget(renderContext: Long, renderTarget: Long) {
        if (renderTarget == 0L) return
        synchronized(renderContextLock) {
            if (ioSurfaceRenderContext != renderContext) return@synchronized
            DesktopJniMpvVideoApi.nativeDestroyIoSurfaceRenderTarget(renderContext, renderTarget)
        }
    }

    override fun destroyIoSurfaceRenderContext(renderContext: Long) {
        if (renderContext == 0L) return
        synchronized(renderContextLock) {
            if (ioSurfaceRenderContext != renderContext) return@synchronized
            DesktopJniMpvVideoApi.nativeFreeIoSurfaceRenderContext(renderContext)
            ioSurfaceRenderContext = 0L
            lastPipelineDescription = null
        }
        destroyNativeHandleIfReady()
    }

    override fun enableSoftwareRendering() {
        synchronized(renderContextLock) {
            ensureOpen()
            if (softwareRenderContext != 0L) return
            check(openGlRenderContext == 0L && ioSurfaceRenderContext == 0L) {
                "GPU libmpv video renderer is already active"
            }
            val context = DesktopJniMpvVideoApi.nativeCreateSoftwareRenderContext(handle)
            check(context != 0L) { "libmpv software video render context creation failed" }
            softwareRenderContext = context
            softwareFrameDirty = true
            AppLogger.w("DesktopVideo", "using software video rendering fallback")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        playbackActive = false
        playWhenReady = false
        activePayload = null
        activeCandidates = emptyList()
        activeCandidateIndex = -1
        activePlaylistEntryId = null
        DesktopJniMpvVideoApi.nativeWakeup(handle)
        if (Thread.currentThread() !== eventThread) runCatching { eventThread.join(2_000) }
        if (Thread.currentThread() !== renderThread) runCatching { renderThread.join(2_000) }

        val gpuContextStillAttached = synchronized(renderContextLock) {
            softwareRenderContext.takeIf { it != 0L }?.let { context ->
                DesktopJniMpvVideoApi.nativeFreeRenderContext(context)
                softwareRenderContext = 0L
            }
            openGlRenderContext != 0L || ioSurfaceRenderContext != 0L
        }
        mutableFrame.value = null
        closeSoftwareFrames()
        destroyNativeHandleIfReady()
        if (gpuContextStillAttached) {
            AppLogger.w(
                "DesktopVideo",
                "GPU video surface still attached during controller close; deferring native teardown",
            )
        }
    }

    private fun eventLoop() {
        try {
            while (!closed.get()) {
                val event = DesktopJniMpvVideoApi.nativeWaitObservedEvent(handle, EVENT_WAIT_SECONDS)
                if (event == "shutdown") return
                if (event != null) handleEvent(event)
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

    private fun handleEvent(event: String) {
        when {
            event == "queue-overflow" -> refreshObservedState()
            event.startsWith("property:") -> handleObservedProperty(event)
            event.startsWith("start:") -> {
                activePlaylistEntryId = parseDesktopJniVideoStartEvent(event)
                softwareFrameDirty = true
            }
            event == "loaded" || event == "restart" -> {
                if (!ensureHardwareDecoder()) return
                playbackActive = true
                reachedEof = false
                softwareFrameDirty = true
                mutableState.value = mutableState.value.copy(errorMessage = null)
                refreshObservedState()
                publishPipelineIfChanged()
            }
            event.startsWith("end:") -> handleEndEvent(event)
        }
    }

    private fun handleObservedProperty(encoded: String) {
        val idSeparator = encoded.indexOf(':')
        val valueSeparator = encoded.indexOf(':', startIndex = idSeparator + 1)
        if (idSeparator < 0 || valueSeparator < 0) return
        val id = encoded.substring(idSeparator + 1, valueSeparator).toIntOrNull() ?: return
        val property = VIDEO_OBSERVED_PROPERTIES.getOrNull(id - 1) ?: return
        val value = encoded.substring(valueSeparator + 1)
        applyObservedProperty(property, value)
    }

    private fun applyObservedProperty(property: String, value: String) {
        val current = mutableState.value
        val updated = when (property) {
            "pause" -> current.copy(
                isPlaying = value != "yes" && value != "true" && value != "1",
            )
            "time-pos" -> current.copy(
                positionMs = value.secondsToMsOrNull()?.coerceAtLeast(0L) ?: current.positionMs,
            )
            "duration" -> {
                val duration = value.secondsToMsOrNull()?.coerceAtLeast(0L) ?: current.durationMs
                current.copy(
                    durationMs = duration,
                    bufferedMs = if (duration > 0L) {
                        current.bufferedMs.coerceIn(0L, duration)
                    } else {
                        current.bufferedMs.coerceAtLeast(0L)
                    },
                )
            }
            "demuxer-cache-time" -> {
                val buffered = value.secondsToMsOrNull()?.coerceAtLeast(0L) ?: current.bufferedMs
                current.copy(
                    bufferedMs = if (current.durationMs > 0L) {
                        buffered.coerceIn(0L, current.durationMs)
                    } else {
                        buffered
                    },
                )
            }
            "video-params/w" -> current.copy(
                videoWidth = value.toIntOrNull()?.coerceAtLeast(0) ?: current.videoWidth,
            )
            "video-params/h" -> current.copy(
                videoHeight = value.toIntOrNull()?.coerceAtLeast(0) ?: current.videoHeight,
            )
            else -> current
        }
        if (updated != current) mutableState.value = updated

        if (property == "hwdec-current") {
            if (!ensureHardwareDecoder()) return
        }
        if (property in VIDEO_PIPELINE_PROPERTIES) publishPipelineIfChanged()
    }

    private fun refreshObservedState() {
        VIDEO_OBSERVED_PROPERTIES.forEach { property ->
            getProperty(property)?.let { value -> applyObservedProperty(property, value) }
        }
    }

    private fun handleEndEvent(encoded: String) {
        val endEvent = parseDesktopJniVideoEndEvent(encoded) ?: return
        if (activePlaylistEntryId == null || endEvent.playlistEntryId != activePlaylistEntryId) {
            return
        }
        activePlaylistEntryId = null
        if (hardwareDecoderRejected) return

        if (
            endEvent.reason == MPV_END_FILE_REASON_ERROR &&
            endEvent.error < 0 &&
            retryNextCandidate()
        ) {
            return
        }

        playbackActive = false
        reachedEof = endEvent.reason == MPV_END_FILE_REASON_EOF
        playWhenReady = false
        softwareFrameDirty = reachedEof
        val errorMessage = if (
            endEvent.reason == MPV_END_FILE_REASON_ERROR && endEvent.error < 0
        ) {
            DesktopJniMpvVideoApi.nativeErrorString(endEvent.error)
                ?.let { "视频播放失败：$it" }
                ?: "视频播放失败"
        } else {
            null
        }
        val current = mutableState.value
        mutableState.value = current.copy(
            isPlaying = false,
            positionMs = if (reachedEof && current.durationMs > 0L) current.durationMs else current.positionMs,
            errorMessage = errorMessage,
        )
    }

    private fun retryNextCandidate(): Boolean {
        val nextIndex = activeCandidateIndex + 1
        if (nextIndex !in activeCandidates.indices) return false
        val current = mutableState.value
        val shouldPlay = playWhenReady
        AppLogger.w(
            "DesktopVideo",
            "retrying source ${nextIndex + 1}/${activeCandidates.size} " +
                "(${activeCandidates[nextIndex].debugDescription()}) after playback error",
        )
        return prepareCandidate(
            index = nextIndex,
            positionMs = current.positionMs.coerceAtLeast(0L),
            shouldPlay = shouldPlay,
        )
    }

    private fun prepareCandidate(index: Int, positionMs: Long, shouldPlay: Boolean): Boolean {
        if (hardwareDecoderRejected) return false
        val payload = activePayload ?: return false
        val candidate = activeCandidates.getOrNull(index) ?: return false
        if (candidate.mainUrl.isBlank()) return false

        activeCandidateIndex = index
        activePlaylistEntryId = null
        reachedEof = false
        playbackActive = true
        playWhenReady = shouldPlay
        softwareFrameDirty = true
        lastPipelineDescription = null
        mutableState.value = mutableState.value.copy(
            isPlaying = false,
            positionMs = positionMs.coerceAtLeast(0L),
            errorMessage = null,
        )

        setProperty("pause", if (shouldPlay) "no" else "yes")
        val options = encodeDesktopJniVideoLoadfileOptions(payload.headers, candidate.externalAudioUrl)
        if (options.isBlank()) {
            command("loadfile", candidate.mainUrl, "replace")
        } else {
            command("loadfile", candidate.mainUrl, "replace", "-1", options)
        }
        if (positionMs > 0L) {
            command("seek", (positionMs / 1000.0).toString(), "absolute")
        }
        return true
    }

    private fun publishPipelineIfChanged() {
        val renderer = when {
            d3D11DirectTargetCount > 0 -> "d3d11-shared-zero-copy"
            ioSurfaceRenderContext != 0L -> "iosurface-metal"
            openGlRenderContext != 0L -> "opengl-gpu"
            softwareRenderContext != 0L -> "software"
            else -> "pending"
        }
        val hwdec = getProperty("hwdec-current")
            ?.takeIf { it.isNotBlank() && !it.equals("no", ignoreCase = true) }
            ?: "software"
        val interop = getProperty("hwdec-interop")?.takeIf(String::isNotBlank) ?: "none"
        val codec = getProperty("video-codec")?.takeIf(String::isNotBlank) ?: "unknown"
        val description = "decoder=$hwdec interop=$interop renderer=$renderer codec=$codec"
        if (description == lastPipelineDescription) return
        lastPipelineDescription = description
        AppLogger.i("DesktopVideo", description)
    }

    private fun ensureHardwareDecoder(): Boolean {
        if (videoDecodeMode == DesktopVideoDecodeMode.Software) return true
        if (hardwareDecoderRejected) return false
        val decoder = getProperty("hwdec-current")?.trim().orEmpty()
        if (decoder.isBlank() || !decoder.equals("no", ignoreCase = true)) return true

        hardwareDecoderRejected = true
        playbackActive = false
        playWhenReady = false
        runCatching { setProperty("pause", "yes") }
        mutableState.value = mutableState.value.copy(
            isPlaying = false,
            errorMessage = "当前设备无法使用硬件解码",
        )
        AppLogger.e("DesktopVideo", "hardware decoder unavailable; software decoding disabled")
        return false
    }

    private fun renderLoop() {
        var pixels = ByteArray(0)
        var bufferWidth = 0
        var bufferHeight = 0
        var bufferStride = 0

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

            val currentlyPlaying = mutableState.value.isPlaying
            if (!currentlyPlaying && !softwareFrameDirty) {
                Thread.sleep(PAUSED_RENDER_IDLE_MS)
                continue
            }

            try {
                val stride = alignTo64(width * BYTES_PER_PIXEL)
                val requiredBytes = stride * height
                if (
                    pixels.size != requiredBytes ||
                    bufferWidth != width ||
                    bufferHeight != height ||
                    bufferStride != stride
                ) {
                    pixels = ByteArray(requiredBytes)
                    bufferWidth = width
                    bufferHeight = height
                    bufferStride = stride
                }
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
                val imageBitmap = Image.makeRaster(imageInfo, pixels, stride).use {
                    it.toComposeImageBitmap()
                }
                publishSoftwareFrame(
                    DesktopSoftwareFrame(
                        imageBitmap = imageBitmap,
                        bitmap = imageBitmap.asSkiaBitmap(),
                    ),
                )
                softwareFrameDirty = false
            } catch (throwable: Throwable) {
                if (!closed.get()) {
                    mutableState.value = mutableState.value.copy(
                        errorMessage = throwable.message ?: "视频画面渲染失败",
                    )
                }
                Thread.sleep(100L)
            }
            Thread.sleep(if (currentlyPlaying) VIDEO_RENDER_INTERVAL_MS else PAUSED_RENDER_IDLE_MS)
        }
    }

    private fun publishSoftwareFrame(next: DesktopSoftwareFrame) {
        synchronized(softwareFrameLock) {
            currentSoftwareFrame?.let(retiredSoftwareFrames::addLast)
            currentSoftwareFrame = next
            mutableFrame.value = next.imageBitmap
            while (retiredSoftwareFrames.size > SOFTWARE_FRAME_RETIRE_DELAY_FRAMES) {
                retiredSoftwareFrames.removeFirst().close()
            }
        }
    }

    private fun closeSoftwareFrames() {
        synchronized(softwareFrameLock) {
            currentSoftwareFrame?.close()
            currentSoftwareFrame = null
            while (retiredSoftwareFrames.isNotEmpty()) {
                retiredSoftwareFrames.removeFirst().close()
            }
        }
    }

    private fun ensureOpenGlContext(renderContext: Long) {
        ensureOpen()
        check(renderContext != 0L && renderContext == openGlRenderContext) {
            "OpenGL libmpv video render context is not active"
        }
    }

    private fun ensureIoSurfaceContext(renderContext: Long) {
        ensureOpen()
        check(renderContext != 0L && renderContext == ioSurfaceRenderContext) {
            "IOSurface libmpv video render context is not active"
        }
    }

    private fun destroyNativeHandleIfReady() {
        synchronized(renderContextLock) {
            if (!closed.get()) return
            if (
                softwareRenderContext != 0L ||
                openGlRenderContext != 0L ||
                ioSurfaceRenderContext != 0L
            ) {
                return
            }
            if (nativeDestroyed.compareAndSet(false, true)) {
                DesktopJniMpvVideoApi.nativeDestroy(handle)
            }
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

private class DesktopSoftwareFrame(
    val imageBitmap: ImageBitmap,
    private val bitmap: Bitmap,
) : AutoCloseable {
    override fun close() {
        bitmap.close()
    }
}

internal data class DesktopJniVideoEndEvent(
    val playlistEntryId: Long,
    val reason: Int,
    val error: Int,
)

internal fun parseDesktopJniVideoStartEvent(encoded: String): Long? {
    if (!encoded.startsWith("start:")) return null
    return encoded.substringAfter(':').toLongOrNull()
}

internal fun parseDesktopJniVideoEndEvent(encoded: String): DesktopJniVideoEndEvent? {
    val fields = encoded.split(':', limit = 4)
    if (fields.size != 4 || fields[0] != "end") return null
    return DesktopJniVideoEndEvent(
        playlistEntryId = fields[1].toLongOrNull() ?: return null,
        reason = fields[2].toIntOrNull() ?: return null,
        error = fields[3].toIntOrNull() ?: return null,
    )
}

internal fun desktopJniVideoSourceCandidates(
    payload: VideoPlaybackPayload,
): List<DesktopJniVideoSourceCandidate> {
    val candidates = mutableListOf<DesktopJniVideoSourceCandidate>()
    (listOf(payload.url) + payload.fallbackUrls)
        .filter(String::isNotBlank)
        .distinct()
        .forEach { mediaUrl -> candidates += DesktopJniVideoSourceCandidate(url = mediaUrl) }

    if (payload.videoUrl.isNotBlank() && payload.audioUrl.isNotBlank()) {
        val videos = (listOf(payload.videoUrl) + payload.fallbackVideoUrls)
            .filter(String::isNotBlank)
            .distinct()
        val audios = (listOf(payload.audioUrl) + payload.fallbackAudioUrls)
            .filter(String::isNotBlank)
            .distinct()
        if (videos.isNotEmpty() && audios.isNotEmpty()) {
            candidates += DesktopJniVideoSourceCandidate(
                videoUrl = videos.first(),
                audioUrl = audios.first(),
            )
            videos.drop(1).forEach { fallbackVideo ->
                candidates += DesktopJniVideoSourceCandidate(
                    videoUrl = fallbackVideo,
                    audioUrl = audios.first(),
                )
            }
            audios.drop(1).forEach { fallbackAudio ->
                candidates += DesktopJniVideoSourceCandidate(
                    videoUrl = videos.first(),
                    audioUrl = fallbackAudio,
                )
            }
            videos.drop(1).forEach { fallbackVideo ->
                audios.drop(1).forEach { fallbackAudio ->
                    candidates += DesktopJniVideoSourceCandidate(
                        videoUrl = fallbackVideo,
                        audioUrl = fallbackAudio,
                    )
                }
            }
        }
    }

    return candidates.distinct().take(MAX_VIDEO_SOURCE_CANDIDATES)
}

internal fun shouldRestartDesktopJniVideoPlayback(
    reachedEof: Boolean,
    playbackActive: Boolean,
    positionMs: Long,
    durationMs: Long,
): Boolean = reachedEof ||
    !playbackActive ||
    (durationMs > 0L && positionMs >= (durationMs - RESTART_NEAR_END_THRESHOLD_MS).coerceAtLeast(0L))

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

private class DesktopMpvVideoApiAdapter(
    private val api: DesktopMpvNativeApi,
) {
    fun nativeCreate(): Long = api.create()
    fun nativeInitialize(handle: Long): Int = api.initialize(handle)
    fun nativeSetOption(handle: Long, name: String, value: String): Int = api.setOption(handle, name, value)
    fun nativeSetProperty(handle: Long, name: String, value: String): Int = api.setProperty(handle, name, value)
    fun nativeGetProperty(handle: Long, name: String): String? = api.getProperty(handle, name)
    fun nativeCommand(handle: Long, args: Array<out String>): Int = api.command(handle, args)
    fun nativeObserveProperty(handle: Long, replyUserdata: Long, name: String): Int =
        api.observeProperty(handle, replyUserdata, name)
    fun nativeWaitObservedEvent(handle: Long, timeoutSeconds: Double): String? =
        api.waitObservedEvent(handle, timeoutSeconds)
    fun nativeWakeup(handle: Long) = api.wakeup(handle)
    fun nativeDestroy(handle: Long) = api.destroy(handle)
    fun nativeErrorString(error: Int): String? = api.errorString(error)
    fun nativeCreateSoftwareRenderContext(handle: Long): Long = api.createSoftwareRenderContext(handle)
    fun nativeCreateOpenGlRenderContext(
        handle: Long,
        directHardware: Boolean,
        nativeDisplayKind: Int,
        nativeDisplay: Long,
        taoGetProcAddress: Long,
    ): Long = api.createOpenGlRenderContext(
        handle,
        directHardware,
        nativeDisplayKind,
        nativeDisplay,
        taoGetProcAddress,
    )
    fun nativeOpenGlRenderContextDisplayKind(renderContext: Long): Int =
        api.openGlRenderContextDisplayKind(renderContext)
    fun nativeUpdateRenderContext(renderContext: Long): Long = api.updateRenderContext(renderContext)
    fun nativeCreateOpenGlRenderTarget(width: Int, height: Int): Long = api.createOpenGlRenderTarget(width, height)
    fun nativeOpenGlRenderTargetFramebuffer(renderTarget: Long): Int =
        api.openGlRenderTargetFramebuffer(renderTarget)
    fun nativeRenderOpenGl(renderContext: Long, renderTarget: Long) = api.renderOpenGl(renderContext, renderTarget)
    fun nativeReportSwap(renderContext: Long) = api.reportSwap(renderContext)
    fun nativeDestroyOpenGlRenderTarget(renderTarget: Long) = api.destroyOpenGlRenderTarget(renderTarget)
    fun nativeCreateD3D11RenderTarget(renderContext: Long, width: Int, height: Int): Long =
        api.createD3D11RenderTarget(renderContext, width, height)
    fun nativeD3D11RenderTargetSharedHandle(renderTarget: Long): Long =
        api.d3D11RenderTargetSharedHandle(renderTarget)
    fun nativeRenderD3D11(renderContext: Long, renderTarget: Long): Boolean =
        api.renderD3D11(renderContext, renderTarget)
    fun nativeDestroyD3D11RenderTarget(renderTarget: Long) = api.destroyD3D11RenderTarget(renderTarget)
    fun nativeFreeOpenGlRenderContext(renderContext: Long) = api.freeOpenGlRenderContext(renderContext)
    fun nativeCreateIoSurfaceRenderContext(handle: Long): Long = api.createIoSurfaceRenderContext(handle)
    fun nativeCreateIoSurfaceRenderTarget(renderContext: Long, width: Int, height: Int): Long =
        api.createIoSurfaceRenderTarget(renderContext, width, height)
    fun nativeIoSurfaceRenderTargetPointer(renderTarget: Long): Long = api.ioSurfaceRenderTargetPointer(renderTarget)
    fun nativeRenderIoSurface(renderContext: Long, renderTarget: Long): Boolean =
        api.renderIoSurface(renderContext, renderTarget)
    fun nativeDestroyIoSurfaceRenderTarget(renderContext: Long, renderTarget: Long) =
        api.destroyIoSurfaceRenderTarget(renderContext, renderTarget)
    fun nativeFreeIoSurfaceRenderContext(renderContext: Long) = api.freeIoSurfaceRenderContext(renderContext)
    fun nativeRenderSoftware(
        renderContext: Long,
        width: Int,
        height: Int,
        stride: Int,
        pixels: ByteArray,
    ): Int = api.renderSoftware(renderContext, width, height, stride, pixels)
    fun nativeFreeRenderContext(renderContext: Long) = api.freeRenderContext(renderContext)
}

internal fun desktopVideoHwdecOption(mode: DesktopVideoDecodeMode): String = when (mode) {
    DesktopVideoDecodeMode.HardwareCompatible -> "auto-copy"
    DesktopVideoDecodeMode.HardwareDirect -> "auto"
    DesktopVideoDecodeMode.Software -> "no"
}

internal fun desktopVideoHwdecInteropOption(
    mode: DesktopVideoDecodeMode,
    osName: String = System.getProperty("os.name").orEmpty(),
): String? = when {
    mode != DesktopVideoDecodeMode.HardwareDirect -> null
    osName.contains("windows", ignoreCase = true) -> "d3d11-egl"
    else -> null
}

internal fun desktopVideoNativeDisplayDescription(kind: Int): String {
    val protocol = when (kind and 0x0F) {
        1 -> "x11"
        2 -> "wayland"
        else -> "none"
    }
    return if (kind and 0x10 != 0) "$protocol-exact" else protocol
}

private const val EVENT_WAIT_SECONDS = 0.05
private const val MPV_END_FILE_REASON_EOF = 0
private const val MPV_END_FILE_REASON_ERROR = 4
private const val MPV_RENDER_UPDATE_FRAME = 1L
private const val BYTES_PER_PIXEL = 4
private const val VIDEO_RENDER_INTERVAL_MS = 33L
private const val PAUSED_RENDER_IDLE_MS = 100L
private const val RESTART_NEAR_END_THRESHOLD_MS = 500L
private const val MAX_VIDEO_SOURCE_CANDIDATES = 24
private const val MAX_SOFTWARE_RENDER_PIXELS = 1920L * 1080L
private const val SOFTWARE_FRAME_RETIRE_DELAY_FRAMES = 4

private val VIDEO_OBSERVED_PROPERTIES = listOf(
    "pause",
    "time-pos",
    "duration",
    "demuxer-cache-time",
    "video-params/w",
    "video-params/h",
    "hwdec-current",
    "hwdec-interop",
    "video-codec",
)

private val VIDEO_PIPELINE_PROPERTIES = setOf(
    "hwdec-current",
    "hwdec-interop",
    "video-codec",
)
