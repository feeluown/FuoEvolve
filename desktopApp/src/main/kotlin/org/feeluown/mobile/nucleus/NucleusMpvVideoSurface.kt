package org.feeluown.mobile.nucleus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import dev.nucleusframework.window.tao.TaoMetalRenderContext
import dev.nucleusframework.window.tao.TaoOpenGlRenderContext
import dev.nucleusframework.window.tao.TextureView
import dev.nucleusframework.window.tao.nucleusIOSurfaceTextureSource
import dev.nucleusframework.window.tao.rememberTaoGpuRenderContext
import dev.nucleusframework.window.tao.rememberTextureViewController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.DesktopIoSurfaceVideoController
import org.feeluown.mobile.DesktopOpenGlVideoController
import org.feeluown.mobile.DesktopPlatformVideoController
import org.feeluown.mobile.DesktopPlatformVideoSurface
import org.feeluown.mobile.VideoPlaybackPayload
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin

/**
 * Native/Nucleus video presentation surface.
 *
 * Windows/Linux render libmpv directly into an FBO on Tao's ANGLE/EGL context and let the same
 * Skia DirectContext sample it. macOS renders libmpv into an IOSurface-backed CGL FBO which
 * Nucleus imports into its Metal scene through TextureView. Neither GPU path performs a CPU frame
 * readback; the software ImageBitmap path remains the last-resort fallback.
 */
internal object NucleusMpvVideoSurface : DesktopPlatformVideoSurface {
    @Composable
    override fun Content(
        controller: DesktopPlatformVideoController,
        payload: VideoPlaybackPayload?,
        modifier: Modifier,
    ) {
        val openGlController = controller as? DesktopOpenGlVideoController
        val ioSurfaceController = controller as? DesktopIoSurfaceVideoController
        val taoContext = rememberTaoGpuRenderContext()
        val openGlContext = taoContext as? TaoOpenGlRenderContext
        val metalContext = taoContext as? TaoMetalRenderContext
        var gpuDisabled by remember(controller, taoContext) { mutableStateOf(false) }

        if (openGlController != null && openGlContext != null && !gpuDisabled) {
            val rendererResult = remember(openGlController, openGlContext) {
                runCatching { NucleusOpenGlMpvVideoRenderer(openGlController, openGlContext) }
                    .onFailure { throwable ->
                        AppLogger.w(
                            "DesktopVideo",
                            "Tao OpenGL video setup failed; falling back to software: ${throwable.message}",
                        )
                    }
            }
            val renderer = rendererResult.getOrNull()
            if (renderer != null) {
                DisposableEffect(renderer) {
                    onDispose(renderer::close)
                }
                NucleusGpuVideoContent(
                    renderer = renderer,
                    contentDescription = payload?.video?.title,
                    modifier = modifier,
                    onFailure = { throwable ->
                        AppLogger.e("DesktopVideo", "Tao OpenGL video rendering failed", throwable)
                        gpuDisabled = true
                    },
                )
                return
            }
        }

        if (ioSurfaceController != null && metalContext != null && !gpuDisabled) {
            val rendererResult = remember(ioSurfaceController, metalContext) {
                runCatching { NucleusIoSurfaceMpvVideoRenderer(ioSurfaceController) }
                    .onFailure { throwable ->
                        AppLogger.w(
                            "DesktopVideo",
                            "macOS IOSurface video setup failed; falling back to software: ${throwable.message}",
                        )
                    }
            }
            val renderer = rendererResult.getOrNull()
            if (renderer != null) {
                DisposableEffect(renderer) {
                    onDispose(renderer::close)
                }
                NucleusIoSurfaceVideoContent(
                    renderer = renderer,
                    modifier = modifier,
                    onFailure = { throwable ->
                        AppLogger.e("DesktopVideo", "macOS IOSurface video rendering failed", throwable)
                        gpuDisabled = true
                    },
                )
                return
            }
        }

        NucleusSoftwareVideoContent(
            controller = controller,
            gpuController = openGlController,
            contentDescription = payload?.video?.title,
            modifier = modifier,
        )
    }
}

@Composable
private fun NucleusGpuVideoContent(
    renderer: NucleusOpenGlMpvVideoRenderer,
    contentDescription: String?,
    modifier: Modifier,
    onFailure: (Throwable) -> Unit,
) {
    var width by remember(renderer) { mutableStateOf(0) }
    var height by remember(renderer) { mutableStateOf(0) }
    var frame by remember(renderer) { mutableStateOf<SkiaImage?>(null) }

    LaunchedEffect(renderer, width, height) {
        if (width <= 0 || height <= 0) return@LaunchedEffect
        try {
            while (isActive) {
                val next = withFrameNanos {
                    renderer.renderFrame(width = width, height = height)
                }
                if (next != null) {
                    frame?.let(renderer::retire)
                    frame = next
                }
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            onFailure(throwable)
        }
    }

    DisposableEffect(renderer) {
        onDispose {
            frame?.let(renderer::retire)
            frame = null
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                width = size.width
                height = size.height
            },
    ) {
        val image = frame ?: return@Canvas
        drawIntoCanvas { canvas ->
            val sourceWidth = image.width.toFloat()
            val sourceHeight = image.height.toFloat()
            if (sourceWidth <= 0f || sourceHeight <= 0f || size.width <= 0f || size.height <= 0f) {
                return@drawIntoCanvas
            }
            val scale = minOf(size.width / sourceWidth, size.height / sourceHeight)
            val drawWidth = sourceWidth * scale
            val drawHeight = sourceHeight * scale
            val left = (size.width - drawWidth) / 2f
            val top = (size.height - drawHeight) / 2f
            canvas.nativeCanvas.drawImageRect(
                image,
                Rect.makeXYWH(left, top, drawWidth, drawHeight),
            )
        }
    }
}

@Composable
private fun NucleusIoSurfaceVideoContent(
    renderer: NucleusIoSurfaceMpvVideoRenderer,
    modifier: Modifier,
    onFailure: (Throwable) -> Unit,
) {
    val textureController = rememberTextureViewController()
    var width by remember(renderer) { mutableStateOf(0) }
    var height by remember(renderer) { mutableStateOf(0) }
    var target by remember(renderer) { mutableStateOf<IoSurfaceVideoTarget?>(null) }

    LaunchedEffect(renderer, width, height) {
        if (width <= 0 || height <= 0) return@LaunchedEffect
        try {
            val next = withContext(Dispatchers.Default) {
                renderer.createTarget(width = width, height = height)
            }
            val previous = target
            target = next
            if (previous != null) {
                // Let Compose publish/import the new source before retiring the old IOSurface.
                withFrameNanos { }
                withContext(Dispatchers.Default) { previous.close() }
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            onFailure(throwable)
        }
    }

    val activeTarget = target
    LaunchedEffect(renderer, activeTarget, textureController) {
        val renderTarget = activeTarget ?: return@LaunchedEffect
        try {
            while (isActive) {
                withFrameNanos { }
                val rendered = withContext(Dispatchers.Default) {
                    renderer.render(renderTarget)
                }
                if (rendered) textureController.markFrameAvailable()
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            onFailure(throwable)
        }
    }

    DisposableEffect(renderer) {
        onDispose {
            target?.close()
            target = null
        }
    }

    val source = remember(activeTarget) {
        activeTarget?.let { renderTarget ->
            nucleusIOSurfaceTextureSource(
                ioSurface = renderTarget.ioSurface,
                widthPx = renderTarget.width,
                heightPx = renderTarget.height,
            )
        }
    }
    TextureView(
        source = source,
        controller = textureController,
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                width = size.width
                height = size.height
            },
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.Low,
    )
}

@Composable
private fun NucleusSoftwareVideoContent(
    controller: DesktopPlatformVideoController,
    gpuController: DesktopOpenGlVideoController?,
    contentDescription: String?,
    modifier: Modifier,
) {
    DisposableEffect(controller, gpuController) {
        runCatching { gpuController?.enableSoftwareRendering() }
            .onFailure { throwable ->
                AppLogger.e("DesktopVideo", "software video fallback setup failed", throwable)
            }
        onDispose { }
    }
    val frame by controller.frame.collectAsState()
    frame?.let { bitmap ->
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}

private class NucleusOpenGlMpvVideoRenderer(
    private val controller: DesktopOpenGlVideoController,
    private val renderContext: TaoOpenGlRenderContext,
) : AutoCloseable {
    private val mpvRenderContext: Long = renderContext.withContextCurrent {
        controller.createOpenGlRenderContext()
    } ?: error("Tao OpenGL context is not available")

    private var target: Long = 0L
    private var backendTarget: BackendRenderTarget? = null
    private var surface: Surface? = null
    private var targetWidth = 0
    private var targetHeight = 0
    private var targetNeedsInitialFrame = true
    private val retired = ArrayDeque<SkiaImage>()
    private var closed = false

    fun renderFrame(width: Int, height: Int): SkiaImage? {
        if (closed || width <= 0 || height <= 0) return null
        return renderContext.withContextCurrent {
            ensureTarget(width, height)
            val currentSurface = checkNotNull(surface)
            val shouldRender = targetNeedsInitialFrame || controller.updateOpenGlRenderContext(mpvRenderContext)
            if (!shouldRender) {
                retireOldSnapshots()
                return@withContextCurrent null
            }

            // Tell Skia an external producer is about to overwrite the wrapped FBO. This preserves
            // snapshot immutability with a GPU-side copy-on-write when a previous frame is in flight.
            currentSurface.notifyContentWillChange(ContentChangeMode.DISCARD)
            controller.renderOpenGl(mpvRenderContext, target)
            renderContext.skiaContext.resetGLAll()
            val next = currentSurface.makeImageSnapshot()
            controller.reportOpenGlSwap(mpvRenderContext)
            targetNeedsInitialFrame = false
            retireOldSnapshots()
            next
        }
    }

    fun retire(image: SkiaImage) {
        retired.addLast(image)
    }

    private fun ensureTarget(width: Int, height: Int) {
        if (target != 0L && targetWidth == width && targetHeight == height) return
        destroyTarget()

        val nextTarget = controller.createOpenGlRenderTarget(width, height)
        var nextBackendTarget: BackendRenderTarget? = null
        var nextSurface: Surface? = null
        try {
            val framebuffer = controller.openGlRenderTargetFramebuffer(nextTarget)
            nextBackendTarget = BackendRenderTarget.makeGL(
                width = width,
                height = height,
                sampleCnt = 0,
                stencilBits = 0,
                fbId = framebuffer,
                fbFormat = FramebufferFormat.GR_GL_RGBA8,
            )
            nextSurface = Surface.makeFromBackendRenderTarget(
                context = renderContext.skiaContext,
                rt = nextBackendTarget,
                origin = SurfaceOrigin.BOTTOM_LEFT,
                colorFormat = SurfaceColorFormat.RGBA_8888,
                colorSpace = ColorSpace.sRGB,
            ) ?: error("Skia could not wrap the libmpv OpenGL framebuffer")
        } catch (throwable: Throwable) {
            nextSurface?.close()
            nextBackendTarget?.close()
            controller.destroyOpenGlRenderTarget(nextTarget)
            throw throwable
        }

        target = nextTarget
        backendTarget = nextBackendTarget
        surface = nextSurface
        targetWidth = width
        targetHeight = height
        targetNeedsInitialFrame = true
        AppLogger.i("DesktopVideo", "GPU video target ${width}x$height attached to Tao/Skia")
    }

    private fun retireOldSnapshots() {
        while (retired.size > SNAPSHOT_RETIRE_DELAY_FRAMES) {
            retired.removeFirst().close()
        }
    }

    private fun destroyTarget() {
        surface?.close()
        surface = null
        backendTarget?.close()
        backendTarget = null
        if (target != 0L) {
            controller.destroyOpenGlRenderTarget(target)
            target = 0L
        }
        targetWidth = 0
        targetHeight = 0
        targetNeedsInitialFrame = true
    }

    override fun close() {
        if (closed) return
        closed = true
        renderContext.withContextCurrent {
            while (retired.isNotEmpty()) retired.removeFirst().close()
            destroyTarget()
            controller.destroyOpenGlRenderContext(mpvRenderContext)
        }
    }
}

private class NucleusIoSurfaceMpvVideoRenderer(
    private val controller: DesktopIoSurfaceVideoController,
) : AutoCloseable {
    private val lock = Any()
    private val renderContext = controller.createIoSurfaceRenderContext()
    private val targets = mutableSetOf<Long>()
    private var closed = false

    fun createTarget(width: Int, height: Int): IoSurfaceVideoTarget = synchronized(lock) {
        check(!closed) { "IOSurface video renderer is closed" }
        val handle = controller.createIoSurfaceRenderTarget(renderContext, width, height)
        targets += handle
        IoSurfaceVideoTarget(
            owner = this,
            handle = handle,
            ioSurface = controller.ioSurfaceRenderTargetPointer(handle),
            width = width,
            height = height,
        ).also {
            AppLogger.i("DesktopVideo", "IOSurface video target ${width}x$height attached to Tao/Metal")
        }
    }

    fun render(target: IoSurfaceVideoTarget): Boolean = synchronized(lock) {
        if (closed || target.handle !in targets) return@synchronized false
        controller.renderIoSurface(renderContext, target.handle)
    }

    fun release(handle: Long) = synchronized(lock) {
        if (handle !in targets) return@synchronized
        targets.remove(handle)
        if (!closed) controller.destroyIoSurfaceRenderTarget(renderContext, handle)
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        targets.toList().forEach { handle ->
            controller.destroyIoSurfaceRenderTarget(renderContext, handle)
        }
        targets.clear()
        controller.destroyIoSurfaceRenderContext(renderContext)
        closed = true
    }
}

private class IoSurfaceVideoTarget(
    private val owner: NucleusIoSurfaceMpvVideoRenderer,
    val handle: Long,
    val ioSurface: Long,
    val width: Int,
    val height: Int,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        owner.release(handle)
    }
}

private const val SNAPSHOT_RETIRE_DELAY_FRAMES = 2
