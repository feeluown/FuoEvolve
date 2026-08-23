package org.feeluown.mobile

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LowRiskOwnershipTest {
    @Test
    fun appUiStateKeepsBootstrapStateAppScoped() {
        val loading = AppUiState()
        assertFalse(loading.isInitialized)
        assertFalse(loading.onboardingCompleted)

        val ready = AppUiState(
            isInitialized = true,
            onboardingCompleted = true,
            themeMode = ThemeMode.Dark,
        )
        assertTrue(ready.isInitialized)
        assertTrue(ready.onboardingCompleted)
        assertEquals(ThemeMode.Dark, ready.themeMode)
    }

    @Test
    fun playlistFeedbackBridgePublishesLegacyStateWritesToAppPortFlow() {
        val state = PlaylistControllerState()

        assertNull(state.playlistOperationFeedbackFlow.value)

        state.playlistOperationFeedback = "歌单已新建"
        assertEquals("歌单已新建", state.playlistOperationFeedbackFlow.value)

        state.playlistOperationFeedback = "已从本地歌单移除"
        assertEquals("已从本地歌单移除", state.playlistOperationFeedbackFlow.value)

        state.playlistOperationFeedback = null
        assertNull(state.playlistOperationFeedbackFlow.value)
    }

    @Test
    fun debugLogOwnerKeepsLoadingFilteringAndFeedbackFeatureLocal() = runTest {
        val repository = FakeDebugLogRepository(
            lines = listOf("info", "warning"),
            exportResult = "日志已导出",
        )
        val controller = createDebugLogFeatureController(repository, this)

        controller.refresh()
        advanceUntilIdle()

        assertEquals(listOf("info", "warning"), controller.uiState.value.lines)
        assertFalse(controller.uiState.value.isLoading)
        assertNull(controller.uiState.value.errorMessage)
        assertEquals(
            setOf(DebugLogLevel.Info, DebugLogLevel.Warning, DebugLogLevel.Error),
            controller.uiState.value.levelFilters,
        )

        controller.onLevelFilterChange(DebugLogLevel.Debug, true)
        assertTrue(DebugLogLevel.Debug in controller.uiState.value.levelFilters)

        controller.export(listOf("info"))
        advanceUntilIdle()
        assertEquals("日志已导出", controller.uiState.value.feedback)
        controller.dismissFeedback("日志已导出")
        assertNull(controller.uiState.value.feedback)
    }

    @Test
    fun debugLogOwnerPublishesRepositoryFailureWithoutGlobalMessageState() = runTest {
        val controller = createDebugLogFeatureController(
            repository = FakeDebugLogRepository(loadFailure = IllegalStateException("read failed")),
            scope = this,
        )

        controller.refresh()
        advanceUntilIdle()

        assertFalse(controller.uiState.value.isLoading)
        assertEquals("read failed", controller.uiState.value.errorMessage)
        assertTrue(controller.uiState.value.lines.isEmpty())
    }

    @Test
    fun sleepTimerFeedbackIsOwnedByPlaybackPortAndDismissible() = runTest {
        val engine = FakePlaybackEngine()
        val compatibilityFeedback = mutableListOf<String>()
        val controller = PlaybackSleepTimerController(
            playbackEngine = engine,
            scope = this,
            currentTrackId = { null },
            nowMillis = { 0L },
            onFeedback = compatibilityFeedback::add,
        )

        controller.setSleepTimerDurationMinutes(30)

        assertEquals("请先播放一首歌曲", controller.feedback.value)
        assertEquals(listOf("请先播放一首歌曲"), compatibilityFeedback)
        controller.dismissFeedback("请先播放一首歌曲")
        assertNull(controller.feedback.value)
    }

    private class FakeDebugLogRepository(
        private val lines: List<String> = emptyList(),
        private val exportResult: String = "",
        private val loadFailure: Throwable? = null,
    ) : DebugLogRepository {
        override val isAvailable: Boolean = true

        override suspend fun logLines(): List<String> {
            loadFailure?.let { throw it }
            return lines
        }

        override suspend fun exportLogFile(lines: List<String>): String = exportResult
    }

    private class FakePlaybackEngine : PlaybackEngine {
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())

        override fun play(track: MusicTrack, payload: PlaybackPayload) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
        override fun seekTo(positionMs: Long) = Unit
    }
}
