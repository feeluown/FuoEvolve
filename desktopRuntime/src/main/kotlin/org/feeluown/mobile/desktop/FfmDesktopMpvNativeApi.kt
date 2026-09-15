package org.feeluown.mobile.desktop

import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.DesktopMpvNativeApi

fun createDesktopFfmMpvNativeApi(): DesktopMpvNativeApi = FfmDesktopMpvNativeApi

private object FfmDesktopMpvNativeApi : DesktopMpvNativeApi {
    private val linker: Linker
    private val lookup: SymbolLookup

    private val createHandle: MethodHandle
    private val initializeHandle: MethodHandle
    private val setOptionHandle: MethodHandle
    private val setPropertyHandle: MethodHandle
    private val getPropertyHandle: MethodHandle
    private val commandHandle: MethodHandle
    private val observePropertyHandle: MethodHandle
    private val waitObservedEventHandle: MethodHandle
    private val wakeupHandle: MethodHandle
    private val destroyHandle: MethodHandle
    private val errorStringHandle: MethodHandle

    private val createSoftwareRenderContextHandle: MethodHandle
    private val createOpenGlRenderContextHandle: MethodHandle
    private val openGlRenderContextDisplayKindHandle: MethodHandle
    private val updateRenderContextHandle: MethodHandle
    private val createOpenGlRenderTargetHandle: MethodHandle
    private val openGlRenderTargetFramebufferHandle: MethodHandle
    private val renderOpenGlHandle: MethodHandle
    private val reportSwapHandle: MethodHandle
    private val destroyOpenGlRenderTargetHandle: MethodHandle
    private val createD3D11RenderTargetHandle: MethodHandle
    private val d3D11RenderTargetSharedHandleHandle: MethodHandle
    private val renderD3D11Handle: MethodHandle
    private val destroyD3D11RenderTargetHandle: MethodHandle
    private val freeOpenGlRenderContextHandle: MethodHandle
    private val createIoSurfaceRenderContextHandle: MethodHandle
    private val createIoSurfaceRenderTargetHandle: MethodHandle
    private val ioSurfaceRenderTargetPointerHandle: MethodHandle
    private val renderIoSurfaceHandle: MethodHandle
    private val destroyIoSurfaceRenderTargetHandle: MethodHandle
    private val freeIoSurfaceRenderContextHandle: MethodHandle
    private val renderSoftwareHandle: MethodHandle
    private val freeRenderContextHandle: MethodHandle

    private val createWindowsD3D11TextureHandle: MethodHandle
    private val windowsD3D11TextureSharedHandleHandle: MethodHandle
    private val uploadWindowsD3D11TextureHandle: MethodHandle
    private val destroyWindowsD3D11TextureHandle: MethodHandle

    private val utf8Scratch = ThreadLocal.withInitial { ReusableNativeBuffer(DEFAULT_TEXT_BUFFER_BYTES) }
    private val softwareScratch = ThreadLocal.withInitial { ReusableNativeBuffer(1) }
    private val pixelsScratch = ThreadLocal.withInitial { ReusableNativeBuffer(1) }

    init {
        val bridge = resolveDesktopMpvBridge()
            ?: throw UnsatisfiedLinkError(
                "Nucleus libmpv native bridge was not found in packaged resources or development build output",
            )
        if (isWindows()) preloadPackagedWindowsMpvRuntime(bridge)
        System.load(bridge.absolutePath)
        linker = Linker.nativeLinker()
        lookup = SymbolLookup.loaderLookup()

        createHandle = bind(DesktopMpvFfmDowncalls.mpvCreate)
        initializeHandle = bind(DesktopMpvFfmDowncalls.mpvInitialize)
        setOptionHandle = bind(DesktopMpvFfmDowncalls.mpvSetOption)
        setPropertyHandle = bind(DesktopMpvFfmDowncalls.mpvSetProperty)
        getPropertyHandle = bind(DesktopMpvFfmDowncalls.mpvGetProperty)
        commandHandle = bind(DesktopMpvFfmDowncalls.mpvCommand)
        observePropertyHandle = bind(DesktopMpvFfmDowncalls.mpvObserveProperty)
        waitObservedEventHandle = bind(DesktopMpvFfmDowncalls.mpvWaitObservedEvent)
        wakeupHandle = bind(DesktopMpvFfmDowncalls.mpvWakeup)
        destroyHandle = bind(DesktopMpvFfmDowncalls.mpvDestroy)
        errorStringHandle = bind(DesktopMpvFfmDowncalls.mpvErrorString)

        createSoftwareRenderContextHandle = bind(DesktopMpvFfmDowncalls.mpvCreateSoftwareRenderContext)
        createOpenGlRenderContextHandle = bind(DesktopMpvFfmDowncalls.mpvCreateOpenGlRenderContext)
        openGlRenderContextDisplayKindHandle = bind(DesktopMpvFfmDowncalls.mpvOpenGlRenderContextDisplayKind)
        updateRenderContextHandle = bind(DesktopMpvFfmDowncalls.mpvUpdateRenderContext)
        createOpenGlRenderTargetHandle = bind(DesktopMpvFfmDowncalls.mpvCreateOpenGlRenderTarget)
        openGlRenderTargetFramebufferHandle = bind(DesktopMpvFfmDowncalls.mpvOpenGlRenderTargetFramebuffer)
        renderOpenGlHandle = bind(DesktopMpvFfmDowncalls.mpvRenderOpenGl)
        reportSwapHandle = bind(DesktopMpvFfmDowncalls.mpvReportSwap)
        destroyOpenGlRenderTargetHandle = bind(DesktopMpvFfmDowncalls.mpvDestroyOpenGlRenderTarget)
        createD3D11RenderTargetHandle = bind(DesktopMpvFfmDowncalls.mpvCreateD3D11RenderTarget)
        d3D11RenderTargetSharedHandleHandle = bind(DesktopMpvFfmDowncalls.mpvD3D11RenderTargetSharedHandle)
        renderD3D11Handle = bind(DesktopMpvFfmDowncalls.mpvRenderD3D11)
        destroyD3D11RenderTargetHandle = bind(DesktopMpvFfmDowncalls.mpvDestroyD3D11RenderTarget)
        freeOpenGlRenderContextHandle = bind(DesktopMpvFfmDowncalls.mpvFreeOpenGlRenderContext)
        createIoSurfaceRenderContextHandle = bind(DesktopMpvFfmDowncalls.mpvCreateIoSurfaceRenderContext)
        createIoSurfaceRenderTargetHandle = bind(DesktopMpvFfmDowncalls.mpvCreateIoSurfaceRenderTarget)
        ioSurfaceRenderTargetPointerHandle = bind(DesktopMpvFfmDowncalls.mpvIoSurfaceRenderTargetPointer)
        renderIoSurfaceHandle = bind(DesktopMpvFfmDowncalls.mpvRenderIoSurface)
        destroyIoSurfaceRenderTargetHandle = bind(DesktopMpvFfmDowncalls.mpvDestroyIoSurfaceRenderTarget)
        freeIoSurfaceRenderContextHandle = bind(DesktopMpvFfmDowncalls.mpvFreeIoSurfaceRenderContext)
        renderSoftwareHandle = bind(DesktopMpvFfmDowncalls.mpvRenderSoftware)
        freeRenderContextHandle = bind(DesktopMpvFfmDowncalls.mpvFreeRenderContext)

        createWindowsD3D11TextureHandle = bind(DesktopMpvFfmDowncalls.d3D11TextureCreate)
        windowsD3D11TextureSharedHandleHandle = bind(DesktopMpvFfmDowncalls.d3D11TextureSharedHandle)
        uploadWindowsD3D11TextureHandle = bind(DesktopMpvFfmDowncalls.d3D11TextureUpload)
        destroyWindowsD3D11TextureHandle = bind(DesktopMpvFfmDowncalls.d3D11TextureDestroy)
        AppLogger.i(LOG_TAG, "loaded FFM libmpv bridge ${bridge.absolutePath}")
    }

    override fun create(): Long = createHandle.invokeExact() as Long

    override fun initialize(handle: Long): Int = initializeHandle.invokeExact(handle) as Int

    override fun setOption(handle: Long, name: String, value: String): Int =
        withUtf8Pair(name, value) { nativeName, nativeValue ->
            setOptionHandle.invokeExact(handle, nativeName, nativeValue) as Int
        }

    override fun setProperty(handle: Long, name: String, value: String): Int =
        withUtf8Pair(name, value) { nativeName, nativeValue ->
            setPropertyHandle.invokeExact(handle, nativeName, nativeValue) as Int
        }

    override fun getProperty(handle: Long, name: String): String? =
        withUtf8(name) { nativeName ->
            readUtf8 { buffer, capacity ->
                getPropertyHandle.invokeExact(handle, nativeName, buffer, capacity) as Int
            }
        }

    override fun command(handle: Long, args: Array<out String>): Int = Arena.ofConfined().use { arena ->
        val pointerSize = ValueLayout.ADDRESS.byteSize()
        val pointers = arena.allocate(pointerSize * (args.size + 1L), ValueLayout.ADDRESS.byteAlignment())
        args.forEachIndexed { index, argument ->
            pointers.setAtIndex(ValueLayout.ADDRESS, index.toLong(), arena.allocateFrom(argument))
        }
        pointers.setAtIndex(ValueLayout.ADDRESS, args.size.toLong(), MemorySegment.NULL)
        commandHandle.invokeExact(handle, pointers) as Int
    }

    override fun observeProperty(handle: Long, replyUserdata: Long, name: String): Int =
        withUtf8(name) { nativeName ->
            observePropertyHandle.invokeExact(handle, replyUserdata, nativeName) as Int
        }

    override fun waitObservedEvent(handle: Long, timeoutSeconds: Double): String? {
        val scratch = utf8Scratch.get()
        val buffer = scratch.ensure(EVENT_BUFFER_BYTES)
        val result = waitObservedEventHandle.invokeExact(
            handle,
            timeoutSeconds,
            buffer,
            buffer.byteSize(),
        ) as Int
        return if (result > 0) buffer.getString(0) else null
    }

    override fun wakeup(handle: Long) {
        wakeupHandle.invokeExact(handle)
    }

    override fun destroy(handle: Long) {
        destroyHandle.invokeExact(handle)
    }

    override fun errorString(error: Int): String? = readUtf8 { buffer, capacity ->
        errorStringHandle.invokeExact(error, buffer, capacity) as Int
    }

    override fun createSoftwareRenderContext(handle: Long): Long =
        createSoftwareRenderContextHandle.invokeExact(handle) as Long

    override fun createOpenGlRenderContext(
        handle: Long,
        directHardware: Boolean,
        nativeDisplayKind: Int,
        nativeDisplay: Long,
        taoGetProcAddress: Long,
    ): Long = createOpenGlRenderContextHandle.invokeExact(
        handle,
        if (directHardware) 1 else 0,
        nativeDisplayKind,
        nativeDisplay,
        taoGetProcAddress,
    ) as Long

    override fun openGlRenderContextDisplayKind(renderContext: Long): Int =
        openGlRenderContextDisplayKindHandle.invokeExact(renderContext) as Int

    override fun updateRenderContext(renderContext: Long): Long =
        updateRenderContextHandle.invokeExact(renderContext) as Long

    override fun createOpenGlRenderTarget(width: Int, height: Int): Long =
        createOpenGlRenderTargetHandle.invokeExact(width, height) as Long

    override fun openGlRenderTargetFramebuffer(renderTarget: Long): Int =
        openGlRenderTargetFramebufferHandle.invokeExact(renderTarget) as Int

    override fun renderOpenGl(renderContext: Long, renderTarget: Long) {
        renderOpenGlHandle.invokeExact(renderContext, renderTarget)
    }

    override fun reportSwap(renderContext: Long) {
        reportSwapHandle.invokeExact(renderContext)
    }

    override fun destroyOpenGlRenderTarget(renderTarget: Long) {
        destroyOpenGlRenderTargetHandle.invokeExact(renderTarget)
    }

    override fun createD3D11RenderTarget(renderContext: Long, width: Int, height: Int): Long =
        createD3D11RenderTargetHandle.invokeExact(renderContext, width, height) as Long

    override fun d3D11RenderTargetSharedHandle(renderTarget: Long): Long =
        d3D11RenderTargetSharedHandleHandle.invokeExact(renderTarget) as Long

    override fun renderD3D11(renderContext: Long, renderTarget: Long): Boolean =
        (renderD3D11Handle.invokeExact(renderContext, renderTarget) as Int) != 0

    override fun destroyD3D11RenderTarget(renderTarget: Long) {
        destroyD3D11RenderTargetHandle.invokeExact(renderTarget)
    }

    override fun freeOpenGlRenderContext(renderContext: Long) {
        freeOpenGlRenderContextHandle.invokeExact(renderContext)
    }

    override fun createIoSurfaceRenderContext(handle: Long): Long =
        createIoSurfaceRenderContextHandle.invokeExact(handle) as Long

    override fun createIoSurfaceRenderTarget(renderContext: Long, width: Int, height: Int): Long =
        createIoSurfaceRenderTargetHandle.invokeExact(renderContext, width, height) as Long

    override fun ioSurfaceRenderTargetPointer(renderTarget: Long): Long =
        ioSurfaceRenderTargetPointerHandle.invokeExact(renderTarget) as Long

    override fun renderIoSurface(renderContext: Long, renderTarget: Long): Boolean =
        (renderIoSurfaceHandle.invokeExact(renderContext, renderTarget) as Int) != 0

    override fun destroyIoSurfaceRenderTarget(renderContext: Long, renderTarget: Long) {
        destroyIoSurfaceRenderTargetHandle.invokeExact(renderContext, renderTarget)
    }

    override fun freeIoSurfaceRenderContext(renderContext: Long) {
        freeIoSurfaceRenderContextHandle.invokeExact(renderContext)
    }

    override fun renderSoftware(
        renderContext: Long,
        width: Int,
        height: Int,
        stride: Int,
        pixels: ByteArray,
    ): Int {
        val nativePixels = softwareScratch.get().ensure(pixels.size.toLong())
        val heapPixels = MemorySegment.ofArray(pixels)
        MemorySegment.copy(heapPixels, 0, nativePixels, 0, pixels.size.toLong())
        val result = renderSoftwareHandle.invokeExact(
            renderContext,
            width,
            height,
            stride,
            nativePixels,
            pixels.size.toLong(),
        ) as Int
        if (result >= 0) {
            MemorySegment.copy(nativePixels, 0, heapPixels, 0, pixels.size.toLong())
        }
        return result
    }

    override fun freeRenderContext(renderContext: Long) {
        freeRenderContextHandle.invokeExact(renderContext)
    }

    override fun createWindowsD3D11Texture(width: Int, height: Int): Long =
        createWindowsD3D11TextureHandle.invokeExact(width, height) as Long

    override fun windowsD3D11TextureSharedHandle(target: Long): Long =
        windowsD3D11TextureSharedHandleHandle.invokeExact(target) as Long

    override fun uploadWindowsD3D11Texture(target: Long, pixels: IntArray): Boolean {
        val bytes = pixels.size.toLong() * Int.SIZE_BYTES
        val nativePixels = pixelsScratch.get().ensure(bytes)
        MemorySegment.copy(MemorySegment.ofArray(pixels), 0, nativePixels, 0, bytes)
        return (uploadWindowsD3D11TextureHandle.invokeExact(
            target,
            nativePixels,
            pixels.size.toLong(),
        ) as Int) != 0
    }

    override fun destroyWindowsD3D11Texture(target: Long) {
        destroyWindowsD3D11TextureHandle.invokeExact(target)
    }

    private fun bind(downcall: DesktopMpvFfmDowncall): MethodHandle {
        val symbol = lookup.find(downcall.symbol).orElseThrow {
            UnsatisfiedLinkError("Missing FFM symbol ${downcall.symbol} in the packaged libmpv bridge")
        }
        return linker.downcallHandle(symbol, downcall.descriptor)
    }

    private inline fun <T> withUtf8(value: String, block: (MemorySegment) -> T): T =
        Arena.ofConfined().use { arena -> block(arena.allocateFrom(value)) }

    private inline fun <T> withUtf8Pair(
        first: String,
        second: String,
        block: (MemorySegment, MemorySegment) -> T,
    ): T = Arena.ofConfined().use { arena ->
        block(arena.allocateFrom(first), arena.allocateFrom(second))
    }

    private inline fun readUtf8(call: (MemorySegment, Long) -> Int): String? {
        val scratch = utf8Scratch.get()
        var buffer = scratch.ensure(DEFAULT_TEXT_BUFFER_BYTES)
        var result = call(buffer, buffer.byteSize())
        if (result == 0) return null
        if (result < 0 && result != Int.MIN_VALUE) {
            val required = (-result).toLong() + 1L
            buffer = scratch.ensure(required)
            result = call(buffer, buffer.byteSize())
        }
        return if (result > 0) buffer.getString(0) else null
    }
}

private class ReusableNativeBuffer(initialCapacity: Long) {
    private var arena: Arena = Arena.ofConfined()
    private var segment: MemorySegment = arena.allocate(initialCapacity.coerceAtLeast(1L), 8L)

    fun ensure(requiredBytes: Long): MemorySegment {
        if (segment.byteSize() >= requiredBytes) return segment
        arena.close()
        arena = Arena.ofConfined()
        segment = arena.allocate(nextPowerOfTwo(requiredBytes), 8L)
        return segment
    }
}

private fun nextPowerOfTwo(value: Long): Long {
    var result = 1L
    while (result < value && result < Long.MAX_VALUE / 2L) result = result shl 1
    return result.coerceAtLeast(value)
}

private fun resolveDesktopMpvBridge(): File? {
    val libraryName = when {
        isWindows() -> WINDOWS_MPV_BRIDGE_NAME
        isMac() -> "libfuoevolve_mpv_jni.dylib"
        else -> "libfuoevolve_mpv_jni.so"
    }
    val resourcesDir = System.getProperty("compose.application.resources.dir")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
    val userDir = File(System.getProperty("user.dir").orEmpty().ifBlank { "." })
    return buildList {
        resourcesDir?.let { add(File(it, "native/lib/$libraryName")) }
        add(File(userDir, "desktopApp/build/native/mpv-jni/$libraryName"))
        add(File(userDir, "desktopNucleusPoc/build/native/mpv-jni/$libraryName"))
        add(File(userDir, "build/native/mpv-jni/$libraryName"))
    }.firstOrNull(File::isFile)
}

private fun preloadPackagedWindowsMpvRuntime(bridge: File) {
    val runtimeDir = bridge.parentFile ?: return
    val runtimeFiles = runtimeDir.listFiles().orEmpty()
        .filter { file -> file.isFile && file.extension.equals("dll", ignoreCase = true) }
    val loadPlan = windowsMpvRuntimeLoadPlan(runtimeFiles.map(File::getName))
    if (loadPlan.isEmpty()) return

    val filesByName = runtimeFiles.associateBy { file -> file.name.lowercase() }
    val mpvRuntimeName = loadPlan.last()
    val pendingSupport = loadPlan.dropLast(1).toMutableList()
    var madeProgress: Boolean
    do {
        madeProgress = false
        val iterator = pendingSupport.iterator()
        while (iterator.hasNext()) {
            val name = iterator.next()
            val file = filesByName[name.lowercase()] ?: run {
                iterator.remove()
                continue
            }
            if (runCatching { System.load(file.absolutePath) }.isSuccess) {
                iterator.remove()
                madeProgress = true
                AppLogger.i(LOG_TAG, "preloaded Windows runtime dependency ${file.name}")
            }
        }
    } while (madeProgress && pendingSupport.isNotEmpty())

    val mpvRuntime = filesByName[mpvRuntimeName.lowercase()] ?: return
    try {
        System.load(mpvRuntime.absolutePath)
    } catch (error: UnsatisfiedLinkError) {
        val unresolved = pendingSupport.takeIf(List<String>::isNotEmpty)?.joinToString()
        val detail = buildString {
            append("Failed to load packaged Windows libmpv runtime: ${mpvRuntime.absolutePath}")
            if (unresolved != null) append("; unresolved sibling DLLs: $unresolved")
        }
        throw UnsatisfiedLinkError(detail).also { it.initCause(error) }
    }
    AppLogger.i(LOG_TAG, "preloaded Windows libmpv runtime ${mpvRuntime.absolutePath}")
}

fun windowsMpvRuntimeLoadPlan(libraryNames: List<String>): List<String> {
    val dllNames = libraryNames.filter { name -> name.endsWith(".dll", ignoreCase = true) }
    val mpvRuntime = WINDOWS_MPV_RUNTIME_NAMES.firstNotNullOfOrNull { expected ->
        dllNames.firstOrNull { name -> name.equals(expected, ignoreCase = true) }
    } ?: return emptyList()
    val support = dllNames
        .filterNot { name ->
            name.equals(WINDOWS_MPV_BRIDGE_NAME, ignoreCase = true) ||
                name.equals(mpvRuntime, ignoreCase = true)
        }
        .sortedBy(String::lowercase)
    return support + mpvRuntime
}

private fun isWindows(): Boolean =
    System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)

private fun isMac(): Boolean =
    System.getProperty("os.name").orEmpty().let { name ->
        name.contains("mac", ignoreCase = true) || name.contains("darwin", ignoreCase = true)
    }

private const val LOG_TAG = "DesktopMpvFfm"
private const val WINDOWS_MPV_BRIDGE_NAME = "fuoevolve_mpv_jni.dll"
private const val DEFAULT_TEXT_BUFFER_BYTES = 4_096L
private const val EVENT_BUFFER_BYTES = 16_384L
private val WINDOWS_MPV_RUNTIME_NAMES = listOf("libmpv-2.dll", "mpv-2.dll", "mpv.dll")
