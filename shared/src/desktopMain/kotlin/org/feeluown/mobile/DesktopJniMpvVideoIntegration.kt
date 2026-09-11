package org.feeluown.mobile

/** Installs the GraalVM-friendly libmpv JNI video controller for the Native/Nucleus desktop host. */
fun installDesktopJniMpvVideoControllerFactory() {
    installDesktopPlatformVideoControllerFactory(::DesktopJniMpvVideoController)
}
