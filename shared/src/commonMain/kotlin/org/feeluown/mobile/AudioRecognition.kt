package org.feeluown.mobile

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
        val attempt: Int,
        val capturedMs: Long,
        val windowDurationMs: Long,
    ) : RecognitionUiState

    data class Matching(val attempt: Int) : RecognitionUiState

    data class Success(val songs: List<RecognizedSong>) : RecognitionUiState

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
const val AUDIO_RECOGNITION_SAMPLE_RATE = 48_000
const val AUDIO_RECOGNITION_FINGERPRINT_SAMPLE_RATE = 8_000
const val AUDIO_RECOGNITION_WINDOW_SAMPLES =
    AUDIO_RECOGNITION_SAMPLE_RATE * AUDIO_RECOGNITION_WINDOW_MS.toInt() / 1_000
const val AUDIO_RECOGNITION_FINGERPRINT_SAMPLES =
    AUDIO_RECOGNITION_FINGERPRINT_SAMPLE_RATE * AUDIO_RECOGNITION_WINDOW_MS.toInt() / 1_000

fun downsampleRecognitionWindow(samples: FloatArray): FloatArray {
    require(samples.size >= AUDIO_RECOGNITION_WINDOW_SAMPLES) {
        "录音不足 6 秒"
    }
    return FloatArray(AUDIO_RECOGNITION_FINGERPRINT_SAMPLES) { index ->
        samples[index * (AUDIO_RECOGNITION_SAMPLE_RATE / AUDIO_RECOGNITION_FINGERPRINT_SAMPLE_RATE)]
    }
}
