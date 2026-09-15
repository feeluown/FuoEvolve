package org.feeluown.mobile

/** Installs the GraalVM-friendly libmpv FFM video controller for the Native/Nucleus desktop host. */
fun installDesktopJniMpvVideoControllerFactory(
    nativeApi: DesktopMpvNativeApi,
    videoDecodeMode: () -> DesktopVideoDecodeMode = { DesktopVideoDecodeMode.HardwareCompatible },
    openGlRenderContextParameters: DesktopOpenGlRenderContextParameters? = null,
) {
    installDesktopPlatformVideoControllerFactory {
        DesktopJniMpvVideoController(nativeApi, videoDecodeMode(), openGlRenderContextParameters)
    }
}
