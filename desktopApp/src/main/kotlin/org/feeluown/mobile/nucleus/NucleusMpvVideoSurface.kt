package org.feeluown.mobile.nucleus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import dev.nucleusframework.window.tao.TaoMetalRenderContext
import dev.nucleusframework.window.tao.TaoOpenGlRenderContext
import dev.nucleusframework.window.tao.TextureView
import dev.nucleusframework.window.tao.nucleusD3D11SharedTextureSource
import dev.nucleusframework.window.tao.nucleusIOSurfaceTextureSource
import dev.nucleusframework.window.tao.rememberTaoGpuRenderContext
import dev.nucleusframework.window.tao.rememberTextureViewController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.DesktopIoSurfaceVideoController
import org.feeluown.mobile.DesktopMpvNativeApi
import org.feeluown.mobile.DesktopOpenGlVideoController
import org.feeluown.mobile.DesktopPlatformVideoController
import org.feeluown.mobile.DesktopPlatformVideoSurface
import org.feeluown.mobile.DesktopWindowsD3D11VideoController
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
 * Windows renders libmpv directly into an ANGLE-owned D3D11 shared texture and lets TextureView
 * sample the same resource without CPU readback/upload. The previous software-frame upload path is
 * retained as a setup/runtime fallback. Linux keeps the direct Tao OpenGL path. macOS keeps the
 * IOSurface/TextureView path.
 */
internal class NucleusMpvVideoSurface(
    private val nativeApi: DesktopMpvNativeApi,
) : DesktopPlatformVideoSurface {
    @Composable
    override fun Content(
        controller: DesktopPlatformVideoController,
        payload: VideoPlaybackPayload?,
        modifier: Modifier,
    ) {
        val openGlController = controller as? DesktopOpenGlVideoController
        val windowsD3D11Controller = controller as? DesktopWindowsD3D11VideoController
        val ioSurfaceController = controller as? DesktopIoSurfaceVideoController
        val taoContext = rememberTaoGpuRenderContext()
        val openGlContext = taoContext as? TaoOpenGlRenderContext
        val metalContext = taoContext as? TaoMetalRenderContext

        if (isWindowsDesktop() && openGlController != null) {
            var directDisabled by remember(controller, taoContext) { mutableStateOf(false) }
            if (windowsD3D11Controller != null && openGlContext != null && !directDisabled) {
                val rendererResult = remember(windowsD3D11Controller, openGlContext) {
                    runCatching {
                        NucleusWindowsD3D11MpvVideoRenderer(windowsD3D11Controller, openGlContext)
                    }.onFailure { throwable ->
                        AppLogger.w(
                            "DesktopVideo",
                            "Windows direct D3D11 video setup failed; using CPU fallback: ${throwable.message}",
                        )
                    }
                }
                val renderer = rendererResult.getOrNull()
                if (renderer != null) {
                    DisposableEffect(renderer) {
                        onDispose(renderer::close)
                    }
                    NucleusWindowsD3D11VideoContent(
                        renderer = renderer,
                        modifier = modifier,
                        onFailure = { throwable ->
                            AppLogger.e(
                                "DesktopVideo",
                                "Windows direct D3D11 video rendering failed; using CPU fallback",
                                throwable,
                            )
                            renderer.close()
                            directDisabled = true
                        },
                    )
                    return
                }
            }

            NucleusWindowsTextureVideoContent(
                nativeApi = nativeApi,
                controller = controller,
                softwareController = openGlController,
                modifier = modifier,
                onFailure = { throwable ->
                    AppLogger.e("DesktopVideo", "Windows TextureView fallback failed", throwable)
                },
            )
            return
        }

        var gpuDisabled by remember(controller, taoContext) { mutableStateOf(false) }
        if (openGlController != null && openGlContext != null && !gpuDisabled) {
            val rendererResult = remember(openGlController, openGlContext) {
                runCatching { NucleusOpenGlMpvVideoRenderer(openGlController, openGlContext) }
                    .onFailure { throwable ->
                        AppLogger.w(
                            "DesktopVideo",
                            "Tao OpenGL video setup failed; hardware video unavailable: ${throwable.message}",
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
                            "macOS IOSurface video setup failed; hardware video unavailable: ${throwable.message}",
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

        AppLogger.e("DesktopVideo", "no hardware video surface is available")
    }
}

@Composable
private fun NucleusWindowsD3D11VideoContent(
    renderer: NucleusWindowsD3D11MpvVideoRenderer,
    modifier: Modifier,
    onFailure: (Throwable) -> Unit,
) {
    val textureController = rememberTextureViewController()
    var width by remember(renderer) { mutableStateOf(0) }
    var height by remember(renderer) { mutableStateOf(0) }
    var target by remember(renderer) { mutableStateOf<WindowsD3D11MpvRenderTarget?>(null) }
    val retirement = remember(renderer) {
        WindowsVideoTargetRetirement<WindowsD3D11MpvRenderTarget>()
    }

    LaunchedEffect(renderer, width, height) {
        if (width <= 0 || height <= 0) return@LaunchedEffect
        var prepared: WindowsD3D11MpvRenderTarget? = null
        try {
            if (target?.width == width && target?.height == height) return@LaunchedEffect
            // Keep the last usable texture visible while a resize is still in progress.
            if (target != null) delay(WINDOWS_VIDEO_RESIZE_SETTLE_MS)

            // Both allocation and the initial libmpv render need the active Tao GPU frame.
            val firstFrameReady = withFrameNanos {
                val next = renderer.createTarget(width = width, height = height)
                prepared = next
                renderer.render(next)
            }
            check(firstFrameReady) { "Initial Windows D3D11 video frame was not rendered" }

            val previous = target
            target = prepared
            prepared = null
            if (previous != null) retirement.retire(previous)
            textureController.markFrameAvailable()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            onFailure(throwable)
        } finally {
            // A resize can cancel the effect after allocation but before publication.
            prepared?.close()
        }
    }

    val activeTarget = target
    LaunchedEffect(renderer, activeTarget, textureController) {
        val renderTarget = activeTarget ?: return@LaunchedEffect
        try {
            while (isActive) {
                val rendered = withFrameNanos {
                    val updated = renderer.render(renderTarget)
                    // Release retired shared handles on the GPU thread, after the source swap
                    // has had multiple draw frames to detach the old TextureView import.
                    retirement.onFrame()
                    updated
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
            retirement.close()
        }
    }

    val source = remember(activeTarget) {
        activeTarget?.let { renderTarget ->
            nucleusD3D11SharedTextureSource(
                sharedHandle = renderTarget.sharedHandle,
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
private fun NucleusWindowsTextureVideoContent(
    nativeApi: DesktopMpvNativeApi,
    controller: DesktopPlatformVideoController,
    softwareController: DesktopOpenGlVideoController,
    modifier: Modifier,
    onFailure: (Throwable) -> Unit,
) {
    val textureController = rememberTextureViewController()
    val softwareSetup = remember(controller, softwareController) {
        runCatching { softwareController.enableSoftwareRendering() }
    }
    val setupError = softwareSetup.exceptionOrNull()
    if (setupError != null) {
        LaunchedEffect(setupError) { onFailure(setupError) }
        return
    }

    var target by remember(controller) { mutableStateOf<WindowsD3D11VideoTarget?>(null) }

    LaunchedEffect(controller, textureController) {
        try {
            controller.frame.collect { frame ->
                if (frame == null) {
                    val previous = target
                    if (previous != null) {
                        target = null
                        withFrameNanos { }
                        withContext(Dispatchers.Default) { previous.close() }
                    }
                    return@collect
                }

                val current = target
                val next = if (
                    current != null &&
                    current.width == frame.width &&
                    current.height == frame.height
                ) {
                    current
                } else {
                    withContext(Dispatchers.Default) {
                        WindowsD3D11VideoTarget.create(nativeApi, frame.width, frame.height)
                            ?: error("D3D11 shared video texture creation failed")
                    }
                }

                val uploaded = withContext(Dispatchers.Default) { next.upload(frame) }
                if (!uploaded) {
                    if (next !== current) withContext(Dispatchers.Default) { next.close() }
                    return@collect
                }

                if (next !== current) {
                    target = next
                    textureController.markFrameAvailable()
                    withFrameNanos { }
                    if (current != null) withContext(Dispatchers.Default) { current.close() }
                } else {
                    textureController.markFrameAvailable()
                }
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            onFailure(throwable)
        }
    }

    DisposableEffect(controller) {
        onDispose {
            target?.close()
            target = null
        }
    }

    val source = remember(target) {
        target?.let { active ->
            nucleusD3D11SharedTextureSource(
                sharedHandle = active.sharedHandle,
                widthPx = active.width,
                heightPx = active.height,
            )
        }
    }
    TextureView(
        source = source,
        controller = textureController,
        modifier = modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.Low,
    )
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

private class WindowsD3D11VideoTarget private constructor(
    private val nativeApi: DesktopMpvNativeApi,
    private val handle: Long,
    val sharedHandle: Long,
    val width: Int,
    val height: Int,
) : AutoCloseable {
    private val lock = Any()
    private val pixels = IntArray(width * height)
    private var closed = false

    fun upload(frame: ImageBitmap): Boolean = synchronized(lock) {
        if (closed || frame.width != width || frame.height != height) return@synchronized false
        frame.readPixels(
            buffer = pixels,
            startX = 0,
            startY = 0,
            width = width,
            height = height,
            bufferOffset = 0,
            stride = width,
        )
        nativeApi.uploadWindowsD3D11Texture(handle, pixels)
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        nativeApi.destroyWindowsD3D11Texture(handle)
    }

    companion object {
        fun create(
            nativeApi: DesktopMpvNativeApi,
            width: Int,
            height: Int,
        ): WindowsD3D11VideoTarget? {
            if (width <= 0 || height <= 0) return null
            val handle = nativeApi.createWindowsD3D11Texture(width, height)
            if (handle == 0L) return null
            val sharedHandle = nativeApi.windowsD3D11TextureSharedHandle(handle)
            if (sharedHandle == 0L) {
                nativeApi.destroyWindowsD3D11Texture(handle)
                return null
            }
            return WindowsD3D11VideoTarget(nativeApi, handle, sharedHandle, width, height)
        }
    }
}

private class NucleusWindowsD3D11MpvVideoRenderer(
    private val controller: DesktopWindowsD3D11VideoController,
    private val renderContext: TaoOpenGlRenderContext,
) : AutoCloseable {
    private val lock = Any()
    private val mpvRenderContext: Long = renderContext.withContextCurrent {
        controller.createOpenGlRenderContext()
    } ?: error("Tao OpenGL context is not available")
    private val targets = mutableSetOf<Long>()
    private var closed = false

    fun createTarget(width: Int, height: Int): WindowsD3D11MpvRenderTarget {
        check(width > 0 && height > 0)
        return renderContext.withContextCurrent {
            synchronized(lock) {
                check(!closed) { "Windows D3D11 video renderer is closed" }
                val handle = controller.createD3D11RenderTarget(mpvRenderContext, width, height)
                try {
                    val sharedHandle = controller.d3D11RenderTargetSharedHandle(handle)
                    targets += handle
                    WindowsD3D11MpvRenderTarget(
                        owner = this,
                        handle = handle,
                        sharedHandle = sharedHandle,
                        width = width,
                        height = height,
                    ).also {
                        AppLogger.i(
                            "DesktopVideo",
                            "zero-copy D3D11 video target ${width}x$height attached to TextureView",
                        )
                    }
                } catch (throwable: Throwable) {
                    controller.destroyD3D11RenderTarget(handle)
                    throw throwable
                }
            }
        } ?: error("Tao OpenGL context is not available")
    }

    fun render(target: WindowsD3D11MpvRenderTarget): Boolean {
        return renderContext.withContextCurrent {
            synchronized(lock) {
                if (closed || target.handle !in targets) return@synchronized false
                val shouldRender = target.needsInitialFrame ||
                    controller.updateOpenGlRenderContext(mpvRenderContext)
                if (!shouldRender) return@synchronized false

                renderContext.skiaContext.resetGLAll()
                val rendered = controller.renderD3D11(mpvRenderContext, target.handle)
                renderContext.skiaContext.resetGLAll()
                if (rendered) {
                    controller.reportOpenGlSwap(mpvRenderContext)
                    target.needsInitialFrame = false
                }
                rendered
            }
        } ?: false
    }

    fun release(handle: Long) {
        renderContext.withContextCurrent {
            synchronized(lock) {
                if (handle !in targets) return@synchronized
                targets.remove(handle)
                if (!closed) controller.destroyD3D11RenderTarget(handle)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        renderContext.withContextCurrent {
            synchronized(lock) {
                targets.toList().forEach(controller::destroyD3D11RenderTarget)
                targets.clear()
                controller.destroyOpenGlRenderContext(mpvRenderContext)
            }
        }
    }
}

private class WindowsD3D11MpvRenderTarget(
    private val owner: NucleusWindowsD3D11MpvVideoRenderer,
    val handle: Long,
    val sharedHandle: Long,
    val width: Int,
    val height: Int,
) : AutoCloseable {
    var needsInitialFrame: Boolean = true
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        owner.release(handle)
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

            currentSurface.notifyContentWillChange(ContentChangeMode.DISCARD)
            renderContext.skiaContext.resetGLAll()
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
        if (closed) {
            image.close()
        } else {
            retired.addLast(image)
        }
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

private fun isWindowsDesktop(): Boolean =
    System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)

private const val WINDOWS_VIDEO_RESIZE_SETTLE_MS = 180L
private const val SNAPSHOT_RETIRE_DELAY_FRAMES = 2
