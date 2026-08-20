package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class PlaybackSleepTimerController(
    private val playbackEngine: PlaybackEngine,
    private val scope: CoroutineScope,
    private val currentTrackId: () -> String? = { null },
    private val nowMillis: () -> Long,
    private val onFeedback: (String) -> Unit,
) : PlaybackSleepTimerPort, PlaybackEndSleepTimer {
    var state by mutableStateOf(SleepTimerState())

    override val sleepTimerState: SleepTimerState
        get() = state

    private var timerJob: Job? = null
    private var timerSerial: Long = 0L

    override fun setSleepTimerDurationMinutes(minutes: Int) {
        setDurationMinutes(minutes, currentTrackId())
    }

    override fun setSleepTimerToEndOfTrack() {
        setToEndOfTrack(currentTrackId())
    }

    override fun clearSleepTimer() = clear()

    fun setDurationMinutes(minutes: Int, currentTrackId: String?) {
        if (currentTrackId == null) {
            onFeedback("请先播放一首歌曲")
            return
        }
        if (minutes !in SLEEP_TIMER_MIN_MINUTES..SLEEP_TIMER_MAX_MINUTES) {
            onFeedback("请输入 $SLEEP_TIMER_MIN_MINUTES–$SLEEP_TIMER_MAX_MINUTES 分钟")
            return
        }
        val durationMs = minutes.toLong() * 60_000L
        replace(
            SleepTimerState(
                mode = SleepTimerMode.Duration,
                deadlineMs = nowMillis() + durationMs,
                remainingMs = durationMs,
            ),
        )
    }

    fun setToEndOfTrack(currentTrackId: String?) {
        if (currentTrackId == null) {
            onFeedback("请先播放一首歌曲")
            return
        }
        replace(
            SleepTimerState(
                mode = SleepTimerMode.EndOfTrack,
                targetTrackId = currentTrackId,
            ),
        )
    }

    fun clear() {
        timerSerial += 1L
        timerJob?.cancel()
        timerJob = null
        state = SleepTimerState()
        playbackEngine.setStopAfterCurrentTrack(false)
    }

    override fun onTrackChanged(trackId: String?) {
        if (
            state.mode == SleepTimerMode.EndOfTrack &&
            state.targetTrackId != trackId
        ) {
            clear()
        }
    }

    fun prepareForTrack(trackId: String) {
        onTrackChanged(trackId)
        if (
            state.mode == SleepTimerMode.EndOfTrack &&
            state.targetTrackId == trackId
        ) {
            playbackEngine.setStopAfterCurrentTrack(true)
        }
    }

    override fun shouldCompleteEndOfTrack(trackId: String, isFinalPlaybackPart: Boolean): Boolean {
        return state.mode == SleepTimerMode.EndOfTrack &&
            state.targetTrackId == trackId &&
            isFinalPlaybackPart
    }

    override fun completeEndOfTrack() {
        clear()
        onFeedback("当前曲目已播放完，播放已暂停")
    }

    private fun replace(nextState: SleepTimerState) {
        clear()
        state = nextState
        playbackEngine.setStopAfterCurrentTrack(nextState.mode == SleepTimerMode.EndOfTrack)
        if (nextState.mode != SleepTimerMode.Duration) return
        val deadlineMs = nextState.deadlineMs ?: return
        val serial = timerSerial
        timerJob = scope.launch {
            while (serial == timerSerial) {
                val remainingMs = deadlineMs - nowMillis()
                if (remainingMs <= 0L) {
                    clear()
                    playbackEngine.pause()
                    onFeedback("睡眠定时已结束，播放已暂停")
                    break
                }
                state = state.copy(remainingMs = remainingMs)
                delay(minOf(remainingMs, 1_000L))
            }
        }
    }
}
