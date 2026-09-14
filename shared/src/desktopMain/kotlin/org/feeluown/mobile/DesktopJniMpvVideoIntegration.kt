package org.feeluown.mobile

/** Installs the GraalVM-friendly libmpv JNI video controller for the Native/Nucleus desktop host. */
fun installDesktopJniMpvVideoControllerFactory(
    videoDecodeMode: () -> DesktopVideoDecodeMode = { DesktopVideoDecodeMode.HardwareCompatible },
    openGlRenderContextParameters: DesktopOpenGlRenderContextParameters? = null,
) {
    installDesktopPlatformVideoControllerFactory {
        DesktopJniMpvVideoController(videoDecodeMode(), openGlRenderContextParameters)
    }
}
