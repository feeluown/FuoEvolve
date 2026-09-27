package org.feeluown.mobile

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RecognizedSong(
    val neteaseSongId: String?,
    val title: String,
    val artists: List<String>,
    val album: String,
    val coverUrl: String? = null,
    val matchStartTimeMs: Long? = null,
)

sealed interface AudioRecognitionEvent {
    data class Capturing(
        val attempt: Int,
        val capturedMs: Long,
        val windowDurationMs: Long = AUDIO_RECOGNITION_WINDOW_MS,
    ) : AudioRecognitionEvent

    data class Matching(val attempt: Int) : AudioRecognitionEvent

    data class NoMatch(val attempt: Int) : AudioRecognitionEvent

    data class Success(val songs: List<RecognizedSong>) : AudioRecognitionEvent

    data class Error(val message: String) : AudioRecognitionEvent

    data object Cancelled : AudioRecognitionEvent
}

sealed interface RecognitionUiState {
    data object Idle : RecognitionUiState

    data class Capturing(
        val capturedMs: Long,
        val windowDurationMs: Long,
    ) : RecognitionUiState

    data object Matching : RecognitionUiState

    data class Success(val songs: List<RecognizedSong>) : RecognitionUiState

    data object NoResult : RecognitionUiState

    data class Error(val message: String) : RecognitionUiState

    data object Cancelled : RecognitionUiState
}

interface AudioRecognitionRepository {
    suspend fun recognize(onEvent: (AudioRecognitionEvent) -> Unit): List<RecognizedSong>

    fun cancel()
}

object UnsupportedAudioRecognitionRepository : AudioRecognitionRepository {
    override suspend fun recognize(onEvent: (AudioRecognitionEvent) -> Unit): List<RecognizedSong> {
        throw UnsupportedOperationException("当前平台不支持听歌识曲")
    }

    override fun cancel() = Unit
}

const val AUDIO_RECOGNITION_WINDOW_MS = 6_000L
const val AUDIO_RECOGNITION_WINDOW_STRIDE_MS = 2_000L
const val AUDIO_RECOGNITION_MAX_ATTEMPTS = 16
const val AUDIO_RECOGNITION_SAMPLE_RATE = 48_000
const val AUDIO_RECOGNITION_FINGERPRINT_SAMPLE_RATE = 8_000
const val AUDIO_RECOGNITION_WINDOW_SAMPLES =
    AUDIO_RECOGNITION_SAMPLE_RATE * AUDIO_RECOGNITION_WINDOW_MS.toInt() / 1_000
const val AUDIO_RECOGNITION_WINDOW_STRIDE_SAMPLES =
    AUDIO_RECOGNITION_SAMPLE_RATE * AUDIO_RECOGNITION_WINDOW_STRIDE_MS.toInt() / 1_000
const val AUDIO_RECOGNITION_FINGERPRINT_SAMPLES =
    AUDIO_RECOGNITION_FINGERPRINT_SAMPLE_RATE * AUDIO_RECOGNITION_WINDOW_MS.toInt() / 1_000

internal data class RecognitionSignalQuality(
    val rms: Float,
    val peak: Float,
    val clippingRatio: Float,
    val dcOffset: Float,
) {
    val usable: Boolean
        get() = rms >= MIN_RECOGNITION_RMS &&
            peak >= MIN_RECOGNITION_PEAK &&
            clippingRatio <= MAX_RECOGNITION_CLIPPING_RATIO
}

internal fun analyzeRecognitionSignal(samples: FloatArray): RecognitionSignalQuality {
    require(samples.isNotEmpty()) { "录音数据为空" }
    var sum = 0.0
    var peak = 0f
    var clipped = 0
    for (sample in samples) {
        sum += sample
        peak = maxOf(peak, abs(sample))
        if (abs(sample) >= RECOGNITION_CLIPPING_LEVEL) clipped += 1
    }
    val dcOffset = (sum / samples.size).toFloat()
    var squareSum = 0.0
    for (sample in samples) {
        val centered = sample - dcOffset
        squareSum += centered * centered
    }
    return RecognitionSignalQuality(
        rms = sqrt(squareSum / samples.size).toFloat(),
        peak = peak,
        clippingRatio = clipped.toFloat() / samples.size,
        dcOffset = dcOffset,
    )
}

/**
 * Converts one 48 kHz recognition window to the 8 kHz input required by the NetEase
 * fingerprint runtime. The old implementation kept every sixth sample directly, which aliased
 * energy above 4 kHz into the fingerprint band. Apply DC removal and a windowed-sinc low-pass
 * filter before decimation so noisy microphone captures retain stable spectral landmarks.
 */
fun downsampleRecognitionWindow(samples: FloatArray): FloatArray {
    require(samples.size >= AUDIO_RECOGNITION_WINDOW_SAMPLES) {
        "录音不足 6 秒"
    }
    val dcOffset = samples.takeRecognitionWindowAverage()
    val kernel = recognitionLowPassKernel
    val radius = kernel.size / 2
    val decimation = AUDIO_RECOGNITION_SAMPLE_RATE / AUDIO_RECOGNITION_FINGERPRINT_SAMPLE_RATE
    val lastSourceIndex = AUDIO_RECOGNITION_WINDOW_SAMPLES - 1

    return FloatArray(AUDIO_RECOGNITION_FINGERPRINT_SAMPLES) { outputIndex ->
        val center = outputIndex * decimation
        var filtered = 0.0
        for (tap in kernel.indices) {
            val sourceIndex = (center + tap - radius).coerceIn(0, lastSourceIndex)
            filtered += (samples[sourceIndex] - dcOffset) * kernel[tap]
        }
        filtered.toFloat()
    }
}

private fun FloatArray.takeRecognitionWindowAverage(): Float {
    var sum = 0.0
    for (index in 0 until AUDIO_RECOGNITION_WINDOW_SAMPLES) {
        sum += this[index]
    }
    return (sum / AUDIO_RECOGNITION_WINDOW_SAMPLES).toFloat()
}

private val recognitionLowPassKernel: FloatArray by lazy {
    val taps = RECOGNITION_RESAMPLER_TAPS
    val center = (taps - 1) / 2
    val cutoff = RECOGNITION_LOW_PASS_HZ / AUDIO_RECOGNITION_SAMPLE_RATE.toDouble()
    val coefficients = DoubleArray(taps) { tap ->
        val offset = tap - center
        val sinc = if (offset == 0) {
            2.0 * cutoff
        } else {
            sin(2.0 * PI * cutoff * offset) / (PI * offset)
        }
        val hann = 0.5 - 0.5 * cos(2.0 * PI * tap / (taps - 1))
        sinc * hann
    }
    val gain = coefficients.sum()
    FloatArray(taps) { index -> (coefficients[index] / gain).toFloat() }
}

private const val RECOGNITION_LOW_PASS_HZ = 3_500.0
private const val RECOGNITION_RESAMPLER_TAPS = 95
private const val MIN_RECOGNITION_RMS = 0.0005f
private const val MIN_RECOGNITION_PEAK = 0.002f
private const val RECOGNITION_CLIPPING_LEVEL = 0.995f
private const val MAX_RECOGNITION_CLIPPING_RATIO = 0.25f
