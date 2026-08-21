package org.feeluown.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface RecognitionAction {
    data object Reset : RecognitionAction
    data object Start : RecognitionAction
    data object Cancel : RecognitionAction
    data object Retry : RecognitionAction
    data object Close : RecognitionAction
    data object CancelIfInProgress : RecognitionAction
}

interface RecognitionFeatureController {
    val uiState: StateFlow<RecognitionUiState>

    fun dispatch(action: RecognitionAction)
}

fun createRecognitionFeatureController(
    repository: AudioRecognitionRepository,
    scope: CoroutineScope,
    isPlaybackActive: () -> Boolean,
    pausePlayback: () -> Unit,
    initialState: RecognitionUiState = RecognitionUiState.Idle,
): RecognitionFeatureController = AudioRecognitionController(
    repository = repository,
    scope = scope,
    isPlaybackActive = isPlaybackActive,
    pausePlayback = pausePlayback,
    initialState = initialState,
)

internal class AudioRecognitionController(
    private val repository: AudioRecognitionRepository,
    private val scope: CoroutineScope,
    private val isPlaybackActive: () -> Boolean,
    private val pausePlayback: () -> Unit,
    initialState: RecognitionUiState = RecognitionUiState.Idle,
) : RecognitionFeatureController {
    private val mutableUiState = MutableStateFlow(initialState)
    override val uiState: StateFlow<RecognitionUiState> = mutableUiState.asStateFlow()

    private var recognitionJob: Job? = null
    private var recognitionSerial: Long = 0

    override fun dispatch(action: RecognitionAction) {
        when (action) {
            RecognitionAction.Reset -> reset()
            RecognitionAction.Start -> start()
            RecognitionAction.Cancel -> cancel()
            RecognitionAction.Retry -> retry()
            RecognitionAction.Close -> close()
            RecognitionAction.CancelIfInProgress -> cancelIfInProgress()
        }
    }

    private fun reset() {
        mutableUiState.value = RecognitionUiState.Idle
    }

    private fun start() {
        if (recognitionJob?.isActive == true) return
        if (isPlaybackActive()) {
            pausePlayback()
        }
        mutableUiState.value = RecognitionUiState.Capturing(
            capturedMs = 0,
            windowDurationMs = AUDIO_RECOGNITION_WINDOW_MS,
        )
        val serial = ++recognitionSerial
        recognitionJob = scope.launch {
            runCatching {
                repository.recognize { event ->
                    if (serial == recognitionSerial) {
                        handleEvent(event)
                    }
                }
            }.onSuccess { songs ->
                if (serial == recognitionSerial) {
                    mutableUiState.value = resultState(songs)
                }
            }.onFailure { throwable ->
                if (
                    serial == recognitionSerial &&
                    throwable !is CancellationException &&
                    mutableUiState.value != RecognitionUiState.Cancelled &&
                    mutableUiState.value != RecognitionUiState.NoResult
                ) {
                    mutableUiState.value = RecognitionUiState.Error(
                        throwable.message ?: "听歌识曲失败",
                    )
                }
            }
            if (serial == recognitionSerial) {
                recognitionJob = null
            }
        }
    }

    private fun cancel() {
        recognitionSerial += 1
        repository.cancel()
        recognitionJob?.cancel()
        recognitionJob = null
        mutableUiState.value = RecognitionUiState.Cancelled
    }

    private fun retry() {
        cancel()
        mutableUiState.value = RecognitionUiState.Idle
        start()
    }

    private fun close() {
        recognitionSerial += 1
        repository.cancel()
        recognitionJob?.cancel()
        recognitionJob = null
        mutableUiState.value = RecognitionUiState.Idle
    }

    private fun cancelIfInProgress() {
        if (isInProgress()) {
            cancel()
        }
    }

    private fun isInProgress(): Boolean =
        mutableUiState.value is RecognitionUiState.Capturing ||
            mutableUiState.value == RecognitionUiState.Matching

    private fun handleEvent(event: AudioRecognitionEvent) {
        mutableUiState.value = when (event) {
            is AudioRecognitionEvent.Capturing -> RecognitionUiState.Capturing(
                capturedMs = event.capturedMs,
                windowDurationMs = event.windowDurationMs,
            )
            is AudioRecognitionEvent.Matching -> RecognitionUiState.Matching
            is AudioRecognitionEvent.NoMatch -> {
                if (event.attempt >= AUDIO_RECOGNITION_MAX_ATTEMPTS) {
                    stopWithoutResult()
                    RecognitionUiState.NoResult
                } else {
                    RecognitionUiState.Capturing(
                        capturedMs = 0,
                        windowDurationMs = AUDIO_RECOGNITION_WINDOW_MS,
                    )
                }
            }
            is AudioRecognitionEvent.Success -> resultState(event.songs)
            is AudioRecognitionEvent.Error -> RecognitionUiState.Error(event.message)
            AudioRecognitionEvent.Cancelled -> RecognitionUiState.Cancelled
        }
    }

    private fun stopWithoutResult() {
        recognitionSerial += 1
        recognitionJob?.cancel()
        repository.cancel()
        recognitionJob = null
    }

    private fun resultState(songs: List<RecognizedSong>): RecognitionUiState {
        val distinctSongs = songs.distinctBy {
            it.neteaseSongId ?: "${it.title}\u0000${it.artists.joinToString()}"
        }
        return if (distinctSongs.isEmpty()) {
            RecognitionUiState.NoResult
        } else {
            RecognitionUiState.Success(distinctSongs)
        }
    }
}
