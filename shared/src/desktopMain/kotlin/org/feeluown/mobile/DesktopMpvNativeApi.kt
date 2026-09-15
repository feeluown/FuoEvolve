package org.feeluown.mobile

import java.util.ServiceLoader

/**
 * Low-level libmpv/native rendering boundary for the desktop host.
 *
 * The shared desktop code stays on the JVM 17 bytecode baseline; the concrete implementation is
 * supplied by the JDK 25 desktop runtime and uses the Foreign Function & Memory API.
 */
interface DesktopMpvNativeApi {
    fun create(): Long
    fun initialize(handle: Long): Int
    fun setOption(handle: Long, name: String, value: String): Int
    fun setProperty(handle: Long, name: String, value: String): Int
    fun getProperty(handle: Long, name: String): String?
    fun command(handle: Long, args: Array<out String>): Int
    fun observeProperty(handle: Long, replyUserdata: Long, name: String): Int
    fun waitObservedEvent(handle: Long, timeoutSeconds: Double): String?
    fun wakeup(handle: Long)
    fun destroy(handle: Long)
    fun errorString(error: Int): String?

    fun createSoftwareRenderContext(handle: Long): Long
    fun createOpenGlRenderContext(
        handle: Long,
        directHardware: Boolean,
        nativeDisplayKind: Int,
        nativeDisplay: Long,
        taoGetProcAddress: Long,
    ): Long
    fun openGlRenderContextDisplayKind(renderContext: Long): Int
    fun updateRenderContext(renderContext: Long): Long
    fun createOpenGlRenderTarget(width: Int, height: Int): Long
    fun openGlRenderTargetFramebuffer(renderTarget: Long): Int
    fun renderOpenGl(renderContext: Long, renderTarget: Long)
    fun reportSwap(renderContext: Long)
    fun destroyOpenGlRenderTarget(renderTarget: Long)
    fun createD3D11RenderTarget(renderContext: Long, width: Int, height: Int): Long
    fun d3D11RenderTargetSharedHandle(renderTarget: Long): Long
    fun renderD3D11(renderContext: Long, renderTarget: Long): Boolean
    fun destroyD3D11RenderTarget(renderTarget: Long)
    fun freeOpenGlRenderContext(renderContext: Long)
    fun createIoSurfaceRenderContext(handle: Long): Long
    fun createIoSurfaceRenderTarget(renderContext: Long, width: Int, height: Int): Long
    fun ioSurfaceRenderTargetPointer(renderTarget: Long): Long
    fun renderIoSurface(renderContext: Long, renderTarget: Long): Boolean
    fun destroyIoSurfaceRenderTarget(renderContext: Long, renderTarget: Long)
    fun freeIoSurfaceRenderContext(renderContext: Long)
    fun renderSoftware(
        renderContext: Long,
        width: Int,
        height: Int,
        stride: Int,
        pixels: ByteArray,
    ): Int
    fun freeRenderContext(renderContext: Long)

    fun createWindowsD3D11Texture(width: Int, height: Int): Long
    fun windowsD3D11TextureSharedHandle(target: Long): Long
    fun uploadWindowsD3D11Texture(target: Long, pixels: IntArray): Boolean
    fun destroyWindowsD3D11Texture(target: Long)
}

@Volatile
private var installedDesktopMpvNativeApi: DesktopMpvNativeApi? = null

fun installDesktopMpvNativeApi(api: DesktopMpvNativeApi) {
    installedDesktopMpvNativeApi = api
}

fun desktopMpvNativeApi(): DesktopMpvNativeApi {
    installedDesktopMpvNativeApi?.let { return it }
    return synchronized(DesktopMpvNativeApi::class.java) {
        installedDesktopMpvNativeApi?.let { return@synchronized it }
        ServiceLoader.load(DesktopMpvNativeApi::class.java)
            .firstOrNull()
            ?.also { installedDesktopMpvNativeApi = it }
            ?: error("Desktop libmpv native API provider was not found on the desktop runtime classpath")
    }
}
