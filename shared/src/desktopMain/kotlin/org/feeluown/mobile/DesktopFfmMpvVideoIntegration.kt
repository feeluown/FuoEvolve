package org.feeluown.mobile

/** Installs the GraalVM-friendly libmpv FFM video controller for the Native/Nucleus desktop host. */
fun installDesktopFfmMpvVideoControllerFactory(
    nativeApi: DesktopMpvNativeApi,
    videoDecodeMode: () -> DesktopVideoDecodeMode = { DesktopVideoDecodeMode.HardwareCompatible },
    openGlRenderContextParameters: DesktopOpenGlRenderContextParameters? = null,
) {
    installDesktopPlatformVideoControllerFactory {
        DesktopFfmMpvVideoController(nativeApi, videoDecodeMode(), openGlRenderContextParameters)
    }
}
