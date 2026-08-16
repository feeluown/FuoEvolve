package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class AudioRecognitionController(
    private val repository: AudioRecognitionRepository,
    private val scope: CoroutineScope,
    private val isPlaybackActive: () -> Boolean,
    private val pausePlayback: () -> Unit,
) {
    var uiState by mutableStateOf<RecognitionUiState>(RecognitionUiState.Idle)
        internal set

    private var recognitionJob: Job? = null
    private var recognitionSerial: Long = 0

    fun reset() {
        uiState = RecognitionUiState.Idle
    }

    fun start() {
        if (recognitionJob?.isActive == true) return
        if (isPlaybackActive()) {
            pausePlayback()
        }
        uiState = RecognitionUiState.Capturing(
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
                    uiState = resultState(songs)
                }
            }.onFailure { throwable ->
                if (
                    serial == recognitionSerial &&
                    throwable !is CancellationException &&
                    uiState != RecognitionUiState.Cancelled &&
                    uiState != RecognitionUiState.NoResult
                ) {
                    uiState = RecognitionUiState.Error(
                        throwable.message ?: "听歌识曲失败",
                    )
                }
            }
            if (serial == recognitionSerial) {
                recognitionJob = null
            }
        }
    }

    fun cancel() {
        recognitionSerial += 1
        repository.cancel()
        recognitionJob?.cancel()
        recognitionJob = null
        uiState = RecognitionUiState.Cancelled
    }

    fun retry() {
        cancel()
        uiState = RecognitionUiState.Idle
        start()
    }

    fun close() {
        recognitionSerial += 1
        repository.cancel()
        recognitionJob?.cancel()
        recognitionJob = null
        uiState = RecognitionUiState.Idle
    }

    fun cancelIfInProgress() {
        if (isInProgress()) {
            cancel()
        }
    }

    private fun isInProgress(): Boolean =
        uiState is RecognitionUiState.Capturing || uiState == RecognitionUiState.Matching

    private fun handleEvent(event: AudioRecognitionEvent) {
        uiState = when (event) {
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
