package org.feeluown.mobile.desktop

import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackEngine
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlaybackPlan
import org.feeluown.mobile.PlaybackResumeSnapshot
import org.feeluown.mobile.PlaybackResumeStore
import org.feeluown.mobile.PlaybackStartReason
import org.feeluown.mobile.PlaybackStartReasonAwareEngine
import org.feeluown.mobile.PlaybackState
import org.feeluown.mobile.PlayerStatus
import org.feeluown.mobile.ResolvedPlaybackSourceAwareEngine
import org.feeluown.mobile.clearsDurablePlaybackResume
import org.feeluown.mobile.isDurablePlaybackResumeStatus
import org.feeluown.mobile.logicalPlaybackTrack

/**
 * Adds durable session semantics around the native libmpv adapter.
 *
 * The wrapper persists only logical track/session state. URLs and provider credentials are resolved
 * again by the shared playback transaction after restart, keeping the native engine free of stale
 * network resources and keeping persistence policy outside the libmpv adapter itself.
 */
internal class PersistentDesktopPlaybackEngine(
    private val delegate: PlaybackEngine,
    private val resumeStore: PlaybackResumeStore,
) : PlaybackEngine, PlaybackStartReasonAwareEngine, ResolvedPlaybackSourceAwareEngine, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var restoredSession: PlaybackResumeSnapshot? = resumeStore.load()
    private var exposeRestoredState = restoredSession != null
    private val mutableState = MutableStateFlow(restoredSession?.toPlaybackState() ?: delegate.state.value)

    @Volatile
    private var pendingResumePositionMs: Long? = null
    @Volatile
    private var pendingPauseAfterStart = false
    @Volatile
    private var pendingStartCancelled = false
    private var activePlaybackPositionCandidateMs: Long? = null
    private var lastPersistedIdentity: String? = null
    private var lastPersistedPositionMs: Long = restoredSession?.positionMs ?: 0L

    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    override val resolvesResourcesInternally: Boolean
        get() = delegate.resolvesResourcesInternally

    init {
        scope.launch {
            delegate.state.collect { next ->
                if (exposeRestoredState && next.currentTrack == null && next.status == PlayerStatus.Idle) {
                    return@collect
                }
                exposeRestoredState = false
                val verified = verifyActivePlayback(next)
                mutableState.value = verified
                if (applyPendingResumeSeek(verified)) return@collect
                persist(verified)
            }
        }
    }

    override fun prepareLoading(track: MusicTrack) {
        prepareLoading(track, PlaybackStartReason.RESTORE_SESSION)
    }

    override fun prepareLoading(track: MusicTrack, reason: PlaybackStartReason) {
        pendingPauseAfterStart = false
        pendingStartCancelled = false
        activePlaybackPositionCandidateMs = null
        val restored = if (reason.mayResumePausedSession) {
            resumeStore.load()?.also { restoredSession = it }
        } else {
            null
        }
        pendingResumePositionMs = restored
            ?.takeIf { session -> session.currentTrack.id == track.id }
            ?.positionMs
            ?.coerceAtLeast(0L)

        if (reason.clearsDurablePlaybackResume) {
            restoredSession = null
            pendingResumePositionMs = null
            lastPersistedIdentity = null
            lastPersistedPositionMs = 0L
            resumeStore.clear()
        }
        exposeRestoredState = false
        val reasonAwareDelegate = delegate as? PlaybackStartReasonAwareEngine
        if (reasonAwareDelegate != null) {
            reasonAwareDelegate.prepareLoading(track, reason)
        } else {
            delegate.prepareLoading(track)
        }
    }

    override fun play(track: MusicTrack, payload: PlaybackPayload) {
        if (pendingStartCancelled) {
            publishCancelledStart(track, payload)
            return
        }
        startDelegatePreservingPendingPause {
            delegate.play(track, payload)
        }
    }

    override fun play(plan: PlaybackPlan) {
        if (pendingStartCancelled) return
        startDelegatePreservingPendingPause {
            delegate.play(plan)
        }
    }

    override fun playResolved(
        logicalTrack: MusicTrack,
        resolveTrack: MusicTrack,
        payload: PlaybackPayload,
    ) {
        if (pendingStartCancelled) {
            publishCancelledStart(logicalTrack, payload)
            return
        }
        startDelegatePreservingPendingPause {
            val sourceAware = delegate as? ResolvedPlaybackSourceAwareEngine
            if (sourceAware != null) {
                sourceAware.playResolved(logicalTrack, resolveTrack, payload)
            } else {
                delegate.play(logicalTrack, payload)
            }
        }
    }

    override fun pause() {
        if (
            delegate.state.value.status == PlayerStatus.Loading ||
            mutableState.value.status == PlayerStatus.Loading
        ) {
            pendingPauseAfterStart = true
        }
        delegate.pause()
        delegate.state.value.takeIf { it.status.isDurablePlaybackResumeStatus() }?.let {
            resumeStore.saveSession(it)
            rememberPersisted(it)
        }
    }

    override fun resume() {
        pendingPauseAfterStart = false
        delegate.resume()
    }

    override fun stop() {
        if (
            delegate.state.value.status == PlayerStatus.Loading ||
            mutableState.value.status == PlayerStatus.Loading
        ) {
            pendingStartCancelled = true
        }
        pendingPauseAfterStart = false
        activePlaybackPositionCandidateMs = null
        pendingResumePositionMs = null
        restoredSession = null
        exposeRestoredState = false
        lastPersistedIdentity = null
        lastPersistedPositionMs = 0L
        resumeStore.clear()
        delegate.stop()
    }

    override fun seekTo(positionMs: Long) {
        pendingResumePositionMs = null
        delegate.seekTo(positionMs)
        val current = delegate.state.value
        if (current.currentTrack != null && current.status.isDurablePlaybackResumeStatus()) {
            resumeStore.savePosition(current.positionMs, current.durationMs)
            lastPersistedPositionMs = current.positionMs
        }
    }

    override fun setVolume(volume: Double) {
        delegate.setVolume(volume)
    }

    override fun setStopAfterCurrentTrack(enabled: Boolean) {
        delegate.setStopAfterCurrentTrack(enabled)
    }

    override fun close() {
        val current = delegate.state.value
        if (current.currentTrack != null && current.status.isDurablePlaybackResumeStatus()) {
            resumeStore.saveSession(current)
        }
        resumeStore.flush()
        scope.cancel()
        (delegate as? AutoCloseable)?.close()
    }

    private inline fun startDelegatePreservingPendingPause(block: () -> Unit) {
        if (pendingPauseAfterStart) delegate.pause()
        block()
        if (pendingPauseAfterStart) delegate.pause()
    }

    private fun publishCancelledStart(track: MusicTrack, payload: PlaybackPayload) {
        val logicalTrack = track.logicalPlaybackTrack()
        val cancelledState = PlaybackState(
            status = PlayerStatus.Idle,
            currentTrack = logicalTrack,
            durationMs = payload.durationMs ?: logicalTrack.durationMs ?: 0L,
            volume = delegate.state.value.volume,
            lyrics = payload.lyrics ?: logicalTrack.lyrics,
            audioQuality = payload.audioQuality,
            playbackParts = payload.parts,
            currentPartIndex = payload.currentPartIndex,
        )
        scope.launch {
            // PlaybackStartCoordinator publishes its resolved Loading overlay immediately after the
            // engine call returns. Republish the cancelled idle state on the next turn so that stale
            // resolution cannot leave app/session state stuck in Loading after Stop.
            yield()
            if (pendingStartCancelled) mutableState.value = cancelledState
        }
    }

    private fun verifyActivePlayback(next: PlaybackState): PlaybackState {
        val current = mutableState.value
        val sameLoadingTrack = current.status == PlayerStatus.Loading &&
            current.currentTrack?.id == next.currentTrack?.id
        if (!sameLoadingTrack) {
            activePlaybackPositionCandidateMs = null
            return next
        }

        if (next.status != PlayerStatus.Playing) {
            if (next.status != PlayerStatus.Loading) activePlaybackPositionCandidateMs = null
            return next
        }

        val positionMs = next.positionMs
        if (positionMs <= 0L) {
            return next.copy(status = PlayerStatus.Loading)
        }

        val previousCandidate = activePlaybackPositionCandidateMs
        if (previousCandidate == null || positionMs <= previousCandidate) {
            activePlaybackPositionCandidateMs = positionMs
            return next.copy(status = PlayerStatus.Loading)
        }

        activePlaybackPositionCandidateMs = null
        return next
    }

    private fun applyPendingResumeSeek(state: PlaybackState): Boolean {
        val position = pendingResumePositionMs ?: return false
        if (state.currentTrack == null || state.status != PlayerStatus.Playing) return false
        pendingResumePositionMs = null
        delegate.seekTo(position)
        return true
    }

    private fun persist(state: PlaybackState) {
        val track = state.currentTrack ?: return
        if (!state.status.isDurablePlaybackResumeStatus()) return

        val identity = buildString {
            append(track.id)
            append('|')
            append(state.currentPartIndex)
            append('|')
            state.playbackParts.forEach { part ->
                append(part.id)
                append(',')
            }
        }
        if (identity != lastPersistedIdentity) {
            resumeStore.saveSession(state)
            rememberPersisted(state)
            restoredSession = PlaybackResumeSnapshot(
                currentTrack = track,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                playbackParts = state.playbackParts,
                currentPartIndex = state.currentPartIndex,
            )
            return
        }

        if (abs(state.positionMs - lastPersistedPositionMs) >= POSITION_PERSIST_INTERVAL_MS) {
            resumeStore.savePosition(state.positionMs, state.durationMs)
            lastPersistedPositionMs = state.positionMs
        }
    }

    private fun rememberPersisted(state: PlaybackState) {
        val track = state.currentTrack ?: return
        lastPersistedIdentity = buildString {
            append(track.id)
            append('|')
            append(state.currentPartIndex)
            append('|')
            state.playbackParts.forEach { part ->
                append(part.id)
                append(',')
            }
        }
        lastPersistedPositionMs = state.positionMs
    }

    private companion object {
        const val POSITION_PERSIST_INTERVAL_MS = 5_000L
    }
}
