package org.feeluown.mobile

import java.io.File

internal object DesktopAudioCaptureNative {
    external fun nativeOpen(): Long
    external fun nativeRead(handle: Long, target: FloatArray, offset: Int, length: Int): Int
    external fun nativeCancel(handle: Long)
    external fun nativeClose(handle: Long)
    external fun nativeLastError(handle: Long): String?
}

internal object DesktopAudioCaptureNativeLoader {
    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val library = resolveDesktopAudioCaptureLibrary()
                ?: throw UnsatisfiedLinkError(
                    "Nucleus system audio capture library was not found in packaged resources " +
                        "or development build output",
                )
            System.load(library.absolutePath)
            loaded = true
            AppLogger.i(LOG_TAG, "loaded system audio capture library ${library.absolutePath}")
        }
    }
}

internal fun resolveDesktopAudioCaptureLibrary(): File? {
    val libraryName = when {
        isDesktopWindows() -> "fuoevolve_audio_capture.dll"
        isDesktopMac() -> "libfuoevolve_audio_capture.dylib"
        else -> "libfuoevolve_audio_capture.so"
    }
    val resourcesDir = System.getProperty("compose.application.resources.dir")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
    val userDir = File(System.getProperty("user.dir").orEmpty().ifBlank { "." })
    return buildList {
        resourcesDir?.let { add(File(it, "native/audio/$libraryName")) }
        add(File(userDir, "desktopNucleusPoc/native/audio-capture/target/release/$libraryName"))
        add(File(userDir, "desktopNucleusPoc/build/native/audio-capture/$libraryName"))
        add(File(userDir, "build/native/audio-capture/$libraryName"))
    }.firstOrNull(File::isFile)
}

internal fun isDesktopMac(): Boolean =
    System.getProperty("os.name").orEmpty().let { name ->
        name.contains("mac", ignoreCase = true) || name.contains("darwin", ignoreCase = true)
    }

private const val LOG_TAG = "DesktopAudioCapture"
