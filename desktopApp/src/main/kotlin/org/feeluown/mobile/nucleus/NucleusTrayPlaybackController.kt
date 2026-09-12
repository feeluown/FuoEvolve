package org.feeluown.mobile.nucleus

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val pendingAction = AtomicReference<NucleusDesktopMediaAction?>()
    private val mutableState = MutableStateFlow(PlaybackSessionState())
    val state: StateFlow<PlaybackSessionState> = mutableState.asStateFlow()

    fun bind(session: PlaybackSession): AutoCloseable {
        sessionRef.set(session)
        mutableState.value = session.state.value
        pendingAction.getAndSet(null)?.let { execute(session, it) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            session.state.collect { mutableState.value = it }
        }
        return AutoCloseable {
            scope.cancel()
            if (sessionRef.compareAndSet(session, null)) {
                mutableState.value = PlaybackSessionState()
            }
        }
    }

    fun handle(action: NucleusDesktopMediaAction) {
        val session = sessionRef.get()
        if (session == null) {
            pendingAction.set(action)
        } else {
            execute(session, action)
        }
    }

    fun toggle() {
        sessionRef.get()?.let { execute(it, NucleusDesktopMediaAction.PlayPause) }
    }

    fun previous() {
        sessionRef.get()?.let { execute(it, NucleusDesktopMediaAction.Previous) }
    }

    fun next() {
        sessionRef.get()?.let { execute(it, NucleusDesktopMediaAction.Next) }
    }

    private fun execute(session: PlaybackSession, action: NucleusDesktopMediaAction) {
        when (action) {
            NucleusDesktopMediaAction.PlayPause -> {
                if (trayPlaybackCanToggle(session.state.value)) session.toggle()
            }
            NucleusDesktopMediaAction.Previous -> {
                if (session.state.value.canGoPrevious) session.previous()
            }
            NucleusDesktopMediaAction.Next -> {
                if (session.state.value.canGoNext) session.next()
            }
        }
    }
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
