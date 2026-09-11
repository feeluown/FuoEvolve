package org.feeluown.mobile

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class DesktopAudioRecognitionRepository : AudioRecognitionRepository {
    private val delegate = DefaultAudioRecognitionRepository(
        captureDevice = DesktopAudioRecognitionCaptureDevice(),
        fingerprintRuntime = DesktopAudioFingerprintRuntime(),
        matcher = NeteaseAudioRecognitionMatcher(),
    )

    override suspend fun recognize(onEvent: (AudioRecognitionEvent) -> Unit): List<RecognizedSong> =
        delegate.recognize(onEvent)

    override fun cancel() = delegate.cancel()
}

internal class DesktopAudioRecognitionCaptureDevice : AudioRecognitionCaptureDevice {
    private val activeHandle = AtomicLong(0L)

    override suspend fun capture(onSamples: (FloatArray) -> Unit) = withContext(Dispatchers.IO) {
        DesktopAudioCaptureNativeLoader.ensureLoaded()
        val handle = DesktopAudioCaptureNative.nativeOpen()
        if (handle == 0L) {
            throw IllegalStateException(
                DesktopAudioCaptureNative.nativeLastError(0L)
                    ?: "系统音频采集不可用，请确认默认输出设备和系统音频权限",
            )
        }
        check(activeHandle.compareAndSet(0L, handle)) {
            DesktopAudioCaptureNative.nativeCancel(handle)
            DesktopAudioCaptureNative.nativeClose(handle)
            "系统音频采集已经在进行中"
        }

        val samples = FloatArray(DESKTOP_AUDIO_READ_SAMPLES)
        try {
            while (activeHandle.get() == handle) {
                when (val read = DesktopAudioCaptureNative.nativeRead(handle, samples, 0, samples.size)) {
                    READ_CANCELLED -> break
                    READ_FAILED -> throw IllegalStateException(
                        DesktopAudioCaptureNative.nativeLastError(handle)
                            ?: "系统音频采集失败，请确认默认输出设备和系统音频权限",
                    )
                    0 -> Unit
                    else -> onSamples(samples.copyOf(read))
                }
            }
        } finally {
            activeHandle.compareAndSet(handle, 0L)
            DesktopAudioCaptureNative.nativeClose(handle)
        }
    }

    override fun cancel() {
        activeHandle.get().takeIf { it != 0L }?.let(DesktopAudioCaptureNative::nativeCancel)
    }
}

private class DesktopAudioFingerprintRuntime : AudioFingerprintRuntime {
    private val json = Json { ignoreUnknownKeys = true }
    private val activeProcess = AtomicReference<Process?>()

    override suspend fun generate(samples: FloatArray): String = withContext(Dispatchers.IO) {
        val helper = resolveDesktopWebViewHelper()
            ?: throw IllegalStateException("桌面音频指纹组件未找到，请重新安装应用")
        val processBuilder = ProcessBuilder(helper.absolutePath)
        configureDesktopWebLoginProcessEnvironment(processBuilder.environment())
        val process = processBuilder.start()
        check(activeProcess.compareAndSet(null, process)) {
            process.destroyForcibly()
            "音频指纹任务已经在进行中"
        }
        try {
            coroutineScope {
                val diagnosticsDeferred = async(Dispatchers.IO) {
                    runCatching {
                        process.errorStream.bufferedReader(Charsets.UTF_8)
                            .use(::readDesktopWebViewHelperDiagnosticTail)
                    }.getOrDefault("")
                }
                try {
                    val request = DesktopFingerprintRequest(samples.toBase64FloatBytes())
                    process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write(json.encodeToString(request))
                        writer.newLine()
                    }
                    val responseLine = process.inputStream.bufferedReader(Charsets.UTF_8).readLine().orEmpty()
                    val exitCode = process.waitFor()
                    val diagnostics = diagnosticsDeferred.await().trim()
                    if (responseLine.isBlank()) {
                        val detail = diagnostics.takeLast(240).ifBlank { "退出码 $exitCode" }
                        throw IllegalStateException("音频指纹组件启动失败：$detail")
                    }
                    val response = json.decodeFromString<DesktopFingerprintResponse>(responseLine)
                    if (response.status != "success" || response.fingerprint.isBlank()) {
                        throw IllegalStateException(
                            response.message?.takeIf(String::isNotBlank) ?: "音频指纹生成失败",
                        )
                    }
                    response.fingerprint
                } finally {
                    diagnosticsDeferred.cancel()
                }
            }
        } finally {
            activeProcess.compareAndSet(process, null)
            if (process.isAlive) process.destroyForcibly()
        }
    }

    override fun cancel() {
        activeProcess.getAndSet(null)?.let { process ->
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun FloatArray.toBase64FloatBytes(): String {
        val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        forEach(buffer::putFloat)
        return Base64.getEncoder().encodeToString(buffer.array())
    }
}

@Serializable
private data class DesktopFingerprintRequest(
    val samplesBase64: String,
)

@Serializable
private data class DesktopFingerprintResponse(
    val status: String,
    val fingerprint: String = "",
    val message: String? = null,
)

private const val DESKTOP_AUDIO_READ_SAMPLES = 4_096
private const val READ_CANCELLED = -1
private const val READ_FAILED = -2
