package org.feeluown.mobile

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
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
    private val activeLine = AtomicReference<TargetDataLine?>()

    override suspend fun capture(onSamples: (FloatArray) -> Unit) = withContext(Dispatchers.IO) {
        val format = AudioFormat(
            AUDIO_RECOGNITION_SAMPLE_RATE.toFloat(),
            PCM_BITS_PER_SAMPLE,
            PCM_CHANNELS,
            true,
            false,
        )
        val lineInfo = DataLine.Info(TargetDataLine::class.java, format)
        val line = runCatching { AudioSystem.getLine(lineInfo) as TargetDataLine }
            .getOrElse { throw IllegalStateException("系统没有可用的麦克风输入设备", it) }
        try {
            line.open(format, DESKTOP_AUDIO_BUFFER_BYTES)
        } catch (throwable: Throwable) {
            runCatching { line.close() }
            throw IllegalStateException("麦克风不支持 48 kHz 单声道 PCM 录音", throwable)
        }
        check(activeLine.compareAndSet(null, line)) { "麦克风录音已经在进行中" }

        val bytes = ByteArray(DESKTOP_AUDIO_READ_BYTES)
        try {
            line.start()
            while (activeLine.get() === line) {
                val read = line.read(bytes, 0, bytes.size)
                if (read < 0) {
                    if (activeLine.get() !== line) break
                    throw IllegalStateException("麦克风读取失败")
                }
                if (read >= PCM_BYTES_PER_SAMPLE) {
                    onSamples(decodePcm16Le(bytes, read))
                }
            }
        } finally {
            releaseLine(line)
        }
    }

    override fun cancel() {
        activeLine.getAndSet(null)?.let(::stopAndClose)
    }

    private fun releaseLine(line: TargetDataLine) {
        activeLine.compareAndSet(line, null)
        stopAndClose(line)
    }

    private fun stopAndClose(line: TargetDataLine) {
        runCatching { line.stop() }
        runCatching { line.flush() }
        runCatching { line.close() }
    }
}

internal fun decodePcm16Le(bytes: ByteArray, length: Int): FloatArray {
    val sampleCount = length.coerceAtMost(bytes.size) / PCM_BYTES_PER_SAMPLE
    return FloatArray(sampleCount) { index ->
        val byteOffset = index * PCM_BYTES_PER_SAMPLE
        val low = bytes[byteOffset].toInt() and 0xff
        val high = bytes[byteOffset + 1].toInt()
        val sample = ((high shl 8) or low).toShort()
        sample / 32768f
    }
}

private class DesktopAudioFingerprintRuntime : AudioFingerprintRuntime {
    private val json = Json { ignoreUnknownKeys = true }
    private val activeProcess = AtomicReference<Process?>()

    override suspend fun generate(samples: FloatArray): String = withContext(Dispatchers.IO) {
        val helper = resolveDesktopWebViewHelper()
            ?: throw IllegalStateException("桌面音频指纹组件未找到，请重新安装应用")
        val process = ProcessBuilder(helper.absolutePath).start()
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

private const val PCM_BITS_PER_SAMPLE = 16
private const val PCM_CHANNELS = 1
private const val PCM_BYTES_PER_SAMPLE = PCM_BITS_PER_SAMPLE / 8
private const val DESKTOP_AUDIO_READ_BYTES = 4_096
private const val DESKTOP_AUDIO_BUFFER_BYTES = 16_384
