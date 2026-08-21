package org.feeluown.mobile

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AudioRecognitionControllerTest {
    @Test
    fun startOwnsStateAndPausesActivePlayback() = runTest {
        val song = RecognizedSong(
            neteaseSongId = "123",
            title = "Song",
            artists = listOf("Artist"),
            album = "Album",
        )
        val repository = object : AudioRecognitionRepository {
            override suspend fun recognize(onEvent: (AudioRecognitionEvent) -> Unit): List<RecognizedSong> {
                onEvent(AudioRecognitionEvent.Capturing(attempt = 1, capturedMs = 1_000))
                onEvent(AudioRecognitionEvent.Matching(attempt = 1))
                return listOf(song, song)
            }

            override fun cancel() = Unit
        }
        var pauseCount = 0
        val controller = createRecognitionFeatureController(
            repository = repository,
            scope = this,
            isPlaybackActive = { true },
            pausePlayback = { pauseCount += 1 },
        )

        controller.dispatch(RecognitionAction.Start)
        advanceUntilIdle()

        assertEquals(1, pauseCount)
        assertEquals(RecognitionUiState.Success(listOf(song)), controller.uiState.value)
    }

    @Test
    fun cancelIfInProgressCancelsRepositoryAndPublishesCancelled() = runTest {
        var cancelCount = 0
        val repository = object : AudioRecognitionRepository {
            override suspend fun recognize(onEvent: (AudioRecognitionEvent) -> Unit): List<RecognizedSong> {
                onEvent(AudioRecognitionEvent.Capturing(attempt = 1, capturedMs = 500))
                awaitCancellation()
            }

            override fun cancel() {
                cancelCount += 1
            }
        }
        val controller = createRecognitionFeatureController(
            repository = repository,
            scope = this,
            isPlaybackActive = { false },
            pausePlayback = {},
        )

        controller.dispatch(RecognitionAction.Start)
        runCurrent()
        assertIs<RecognitionUiState.Capturing>(controller.uiState.value)

        controller.dispatch(RecognitionAction.CancelIfInProgress)
        runCurrent()

        assertEquals(1, cancelCount)
        assertEquals(RecognitionUiState.Cancelled, controller.uiState.value)
    }

    @Test
    fun closeReturnsFeatureToIdle() = runTest {
        val repository = object : AudioRecognitionRepository {
            override suspend fun recognize(onEvent: (AudioRecognitionEvent) -> Unit): List<RecognizedSong> = emptyList()

            override fun cancel() = Unit
        }
        val controller = createRecognitionFeatureController(
            repository = repository,
            scope = this,
            isPlaybackActive = { false },
            pausePlayback = {},
            initialState = RecognitionUiState.Cancelled,
        )

        controller.dispatch(RecognitionAction.Close)

        assertEquals(RecognitionUiState.Idle, controller.uiState.value)
    }
}
