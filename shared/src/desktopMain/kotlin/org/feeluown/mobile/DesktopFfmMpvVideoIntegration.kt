package org.feeluown.mobile

/** Installs the GraalVM-friendly libmpv FFM video controller for the Native/Nucleus desktop host. */
fun installDesktopFfmMpvVideoControllerFactory(
    nativeApi: DesktopMpvNativeApi,
    videoDecodeMode: () -> DesktopVideoDecodeMode = { DesktopVideoDecodeMode.HardwareCompatible },
    openGlRenderContextParameters: DesktopOpenGlRenderContextParameters? = null,
) {
    installDesktopPlatformVideoControllerFactory {
        val controller = DesktopFfmMpvVideoController(
            nativeApi,
            videoDecodeMode(),
            openGlRenderContextParameters,
        )
        val windowsHostHandle = nativeApi.claimWindowsNativeVideoHostHandle()
        if (windowsHostHandle == 0L) {
            controller
        } else {
            WindowsNativeMpvVideoController(controller, windowsHostHandle)
        }
    }
}

private class WindowsNativeMpvVideoController(
    private val delegate: DesktopPlatformVideoController,
    override val windowsNativeVideoHostHandle: Long,
) :
    DesktopPlatformVideoController by delegate,
    DesktopWindowsNativeVideoController
