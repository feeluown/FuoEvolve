package org.feeluown.mobile.desktop

import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.DesktopAudioCaptureApi

fun createDesktopFfmAudioCaptureApi(): DesktopAudioCaptureApi = FfmDesktopAudioCaptureApi

private object FfmDesktopAudioCaptureApi : DesktopAudioCaptureApi {
    private val bindings by lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::Bindings)
    private val samplesScratch = ThreadLocal.withInitial { ReusableAudioNativeBuffer(1L) }
    private val errorScratch = ThreadLocal.withInitial { ReusableAudioNativeBuffer(DEFAULT_ERROR_BUFFER_BYTES) }

    override fun open(): Long = bindings.openHandle.invokeExact() as Long

    override fun read(handle: Long, target: FloatArray, offset: Int, length: Int): Int {
        if (offset < 0 || length <= 0 || offset > target.size - length) {
            throw IllegalArgumentException(
                "Invalid desktop audio capture buffer range offset=$offset length=$length size=${target.size}",
            )
        }
        val byteCount = length.toLong() * Float.SIZE_BYTES
        val nativeSamples = samplesScratch.get().ensure(byteCount)
        val result = bindings.readHandle.invokeExact(handle, nativeSamples, length.toLong()) as Int
        if (result > 0) {
            check(result <= length) {
                "Desktop audio capture returned $result samples for a $length-sample buffer"
            }
            MemorySegment.copy(
                nativeSamples,
                0L,
                MemorySegment.ofArray(target),
                offset.toLong() * Float.SIZE_BYTES,
                result.toLong() * Float.SIZE_BYTES,
            )
        }
        return result
    }

    override fun cancel(handle: Long) {
        bindings.cancelHandle.invokeExact(handle)
    }

    override fun close(handle: Long) {
        bindings.closeHandle.invokeExact(handle)
    }

    override fun lastError(handle: Long): String? {
        val scratch = errorScratch.get()
        var buffer = scratch.ensure(DEFAULT_ERROR_BUFFER_BYTES)
        var result = bindings.lastErrorHandle.invokeExact(handle, buffer, buffer.byteSize()) as Int
        if (result < 0 && result != Int.MIN_VALUE) {
            buffer = scratch.ensure((-result).toLong() + 1L)
            result = bindings.lastErrorHandle.invokeExact(handle, buffer, buffer.byteSize()) as Int
        }
        return if (result > 0) buffer.getString(0) else null
    }

    private class Bindings {
        val openHandle: MethodHandle
        val readHandle: MethodHandle
        val cancelHandle: MethodHandle
        val closeHandle: MethodHandle
        val lastErrorHandle: MethodHandle

        init {
            val library = resolveDesktopAudioCaptureLibrary()
                ?: throw UnsatisfiedLinkError(
                    "Nucleus system audio capture library was not found in packaged resources " +
                        "or development build output",
                )
            System.load(library.absolutePath)
            val linker = Linker.nativeLinker()
            val lookup = SymbolLookup.loaderLookup()

            fun bind(name: String, descriptor: FunctionDescriptor): MethodHandle {
                val symbol = lookup.find(name).orElseThrow {
                    UnsatisfiedLinkError("Missing FFM symbol $name in the system audio capture library")
                }
                return linker.downcallHandle(symbol, descriptor)
            }

            openHandle = bind(
                "fuo_audio_capture_open",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG),
            )
            readHandle = bind(
                "fuo_audio_capture_read",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                ),
            )
            cancelHandle = bind(
                "fuo_audio_capture_cancel",
                FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG),
            )
            closeHandle = bind(
                "fuo_audio_capture_close",
                FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG),
            )
            lastErrorHandle = bind(
                "fuo_audio_capture_last_error",
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                ),
            )
            AppLogger.i(LOG_TAG, "loaded FFM system audio capture library ${library.absolutePath}")
        }
    }
}

private class ReusableAudioNativeBuffer(initialCapacity: Long) {
    private var arena: Arena = Arena.ofConfined()
    private var segment: MemorySegment = arena.allocate(initialCapacity.coerceAtLeast(1L), 8L)

    fun ensure(requiredBytes: Long): MemorySegment {
        if (segment.byteSize() >= requiredBytes) return segment
        arena.close()
        arena = Arena.ofConfined()
        segment = arena.allocate(nextAudioBufferCapacity(requiredBytes), 8L)
        return segment
    }
}

private fun nextAudioBufferCapacity(value: Long): Long {
    var result = 1L
    while (result < value && result < Long.MAX_VALUE / 2L) result = result shl 1
    return result.coerceAtLeast(value)
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
        add(File(userDir, "desktopApp/native/audio-capture/target/release/$libraryName"))
        add(File(userDir, "desktopApp/build/native/audio-capture/$libraryName"))
        add(File(userDir, "build/native/audio-capture/$libraryName"))
    }.firstOrNull(File::isFile)
}

internal fun isDesktopWindows(): Boolean =
    System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)

internal fun isDesktopMac(): Boolean =
    System.getProperty("os.name").orEmpty().let { name ->
        name.contains("mac", ignoreCase = true) || name.contains("darwin", ignoreCase = true)
    }

private const val DEFAULT_ERROR_BUFFER_BYTES = 1_024L
private const val LOG_TAG = "DesktopAudioCapture"
