package org.feeluown.mobile

/**
 * Platform-neutral desktop system-output capture boundary.
 *
 * The shared desktop target stays on JVM 17; JDK 25 FFM details live in desktopRuntime.
 */
interface DesktopAudioCaptureApi {
    fun open(): Long
    fun read(handle: Long, target: FloatArray, offset: Int, length: Int): Int
    fun cancel(handle: Long)
    fun close(handle: Long)
    fun lastError(handle: Long): String?
}
