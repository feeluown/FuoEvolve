package org.feeluown.mobile.nucleus

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.feeluown.mobile.playback.api.PlaybackSession
import org.feeluown.mobile.playback.api.PlaybackSessionState
import org.feeluown.mobile.playback.api.PlaybackSessionStatus

/** Keeps tray and launcher playback actions bound to the current app-scoped PlaybackSession. */
internal class NucleusTrayPlaybackController {
    private val sessionRef = AtomicReference<PlaybackSession?>()
    private val scopeRef = AtomicReference<CoroutineScope?>()
    private val pendingId = AtomicLong(0L)
    private val pendingAction = AtomicReference<PendingMediaAction?>()
    private val mutableState = MutableStateFlow(PlaybackSessionState())
    val state: StateFlow<PlaybackSessionState> = mutableState.asStateFlow()

    fun bind(session: PlaybackSession): AutoCloseable {
        sessionRef.set(session)
        mutableState.value = session.state.value
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scopeRef.set(scope)
        pendingAction.get()?.let { scheduleExpiry(scope, it) }
        scope.launch {
            session.state.collect { state ->
                mutableState.value = state
                tryExecutePending(session, state)
            }
        }
        return AutoCloseable {
            if (scopeRef.compareAndSet(scope, null)) scope.cancel()
            if (sessionRef.compareAndSet(session, null)) {
                pendingAction.set(null)
                mutableState.value = PlaybackSessionState()
            }
        }
    }

    fun handle(action: NucleusDesktopMediaAction) {
        val session = sessionRef.get()
        if (session == null) {
            queuePending(action)
            return
        }
        val state = session.state.value
        if (canExecute(action, state)) {
            execute(session, action)
        } else if (state.isPossiblyRestoring()) {
            queuePending(action)
        }
    }

    fun toggle() {
        sessionRef.get()?.let { session ->
            if (trayPlaybackCanToggle(session.state.value)) session.toggle()
        }
    }

    fun previous() {
        sessionRef.get()?.let { session ->
            if (session.state.value.canGoPrevious) session.previous()
        }
    }

    fun next() {
        sessionRef.get()?.let { session ->
            if (session.state.value.canGoNext) session.next()
        }
    }

    private fun queuePending(action: NucleusDesktopMediaAction) {
        val pending = PendingMediaAction(pendingId.incrementAndGet(), action)
        pendingAction.set(pending)
        scopeRef.get()?.let { scheduleExpiry(it, pending) }
    }

    private fun scheduleExpiry(scope: CoroutineScope, pending: PendingMediaAction) {
        scope.launch {
            delay(PENDING_MEDIA_ACTION_TIMEOUT_MS)
            pendingAction.compareAndSet(pending, null)
        }
    }

    private fun tryExecutePending(session: PlaybackSession, state: PlaybackSessionState) {
        val pending = pendingAction.get() ?: return
        if (!canExecute(pending.action, state)) return
        if (pendingAction.compareAndSet(pending, null)) {
            execute(session, pending.action)
        }
    }

    private fun execute(session: PlaybackSession, action: NucleusDesktopMediaAction) {
        when (action) {
            NucleusDesktopMediaAction.PlayPause -> session.toggle()
            NucleusDesktopMediaAction.Previous -> session.previous()
            NucleusDesktopMediaAction.Next -> session.next()
        }
    }

    private fun canExecute(action: NucleusDesktopMediaAction, state: PlaybackSessionState): Boolean =
        when (action) {
            NucleusDesktopMediaAction.PlayPause -> trayPlaybackCanToggle(state)
            NucleusDesktopMediaAction.Previous -> state.canGoPrevious
            NucleusDesktopMediaAction.Next -> state.canGoNext
        }

    private fun PlaybackSessionState.isPossiblyRestoring(): Boolean =
        status == PlaybackSessionStatus.Idle &&
            currentTrack == null &&
            queueTrackIds.isEmpty() &&
            canonicalQueueTracks.isEmpty()

    private data class PendingMediaAction(
        val id: Long,
        val action: NucleusDesktopMediaAction,
    )
}

internal fun trayPlaybackCanToggle(state: PlaybackSessionState): Boolean =
    state.currentTrack != null && state.status != PlaybackSessionStatus.Loading

internal fun trayPlaybackToggleLabel(state: PlaybackSessionState): String =
    if (state.status == PlaybackSessionStatus.Playing) "暂停" else "播放"

internal fun trayPlaybackTrackLabel(state: PlaybackSessionState): String {
    val track = state.currentTrack ?: return "当前曲目：暂无播放"
    val title = track.title.ifBlank { "未知曲目" }
    val artists = track.artists.trim()
    return if (artists.isBlank()) "当前曲目：$title" else "当前曲目：$title · $artists"
}

private const val PENDING_MEDIA_ACTION_TIMEOUT_MS = 10_000L
