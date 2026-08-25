package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class PlaybackSleepTimerController(
    private val playbackEngine: PlaybackEngine,
    private val scope: CoroutineScope,
    private val currentTrackId: () -> String? = { null },
    private val nowMillis: () -> Long,
    private val onFeedback: (String) -> Unit,
) : PlaybackSleepTimerPort, PlaybackEndSleepTimer {
    private val mutableState = MutableStateFlow(SleepTimerState())
    override val sleepTimerStateFlow: StateFlow<SleepTimerState> = mutableState.asStateFlow()
    override val sleepTimerState: SleepTimerState
        get() = mutableState.value
    val state: SleepTimerState
        get() = mutableState.value

    private val mutableFeedback = MutableStateFlow<String?>(null)
    override val feedback: StateFlow<String?> = mutableFeedback.asStateFlow()

    private var timerJob: Job? = null
    private var timerSerial: Long = 0L

    override fun setSleepTimerDurationMinutes(minutes: Int) {
        setDurationMinutes(minutes, currentTrackId())
    }

    override fun setSleepTimerToEndOfTrack() {
        setToEndOfTrack(currentTrackId())
    }

    override fun clearSleepTimer() = clear()

    override fun dismissFeedback(feedback: String) {
        if (mutableFeedback.value == feedback) {
            mutableFeedback.value = null
        }
    }

    fun setDurationMinutes(minutes: Int, currentTrackId: String?) {
        if (currentTrackId == null) {
            publishFeedback("请先播放一首歌曲")
            return
        }
        if (minutes !in SLEEP_TIMER_MIN_MINUTES..SLEEP_TIMER_MAX_MINUTES) {
            publishFeedback("请输入 $SLEEP_TIMER_MIN_MINUTES–$SLEEP_TIMER_MAX_MINUTES 分钟")
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
            publishFeedback("请先播放一首歌曲")
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
        mutableState.value = SleepTimerState()
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
        publishFeedback("当前曲目已播放完，播放已暂停")
    }

    private fun replace(nextState: SleepTimerState) {
        clear()
        mutableState.value = nextState
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
                    publishFeedback("睡眠定时已结束，播放已暂停")
                    break
                }
                mutableState.value = state.copy(remainingMs = remainingMs)
                delay(minOf(remainingMs, 1_000L))
            }
        }
    }

    private fun publishFeedback(message: String) {
        mutableFeedback.value = message
        onFeedback(message)
    }
}
