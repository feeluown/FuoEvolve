package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Explicit placeholders for desktop capabilities that are intentionally outside the current phase.
 * Keeping them at the platform composition edge lets feature/common code stay identical on desktop.
 */
internal object DesktopUnsupportedLocalMusicRepository : LocalMusicRepository {
    override suspend fun updateScanSettings(settings: LocalMusicScanSettings) = Unit

    override suspend fun directories(): List<LocalMusicDirectory> = emptyList()

    override suspend fun tracks(): List<MusicTrack> = emptyList()

    override suspend fun refreshDatabase(): List<MusicTrack> = emptyList()

    override suspend fun search(keyword: String): List<MusicTrack> = emptyList()
}

@Volatile
private var desktopPlaybackEngineFactory: (() -> PlaybackEngine)? = null

/**
 * Installs the concrete desktop playback runtime from the JVM host module.
 *
 * Native dependencies such as JNA/libmpv intentionally stay in `desktopApp`; shared desktop code
 * only sees the existing [PlaybackEngine] contract. Call this before composing [DesktopAppHost].
 */
fun installDesktopPlaybackEngineFactory(factory: () -> PlaybackEngine) {
    desktopPlaybackEngineFactory = factory
}

/**
 * Compatibility name kept so the existing desktop composition root does not own a native runtime.
 * The real implementation is supplied by `desktopApp` before [DesktopAppHost] is created.
 */
internal class DesktopUnsupportedPlaybackEngine :
    PlaybackEngine,
    ResolvedPlaybackSourceAwareEngine,
    AutoCloseable {
    private val delegate: PlaybackEngine = desktopPlaybackEngineFactory?.invoke()
        ?: MissingDesktopPlaybackEngine()

    override val state: StateFlow<PlaybackState>
        get() = delegate.state

    override val resolvesResourcesInternally: Boolean
        get() = delegate.resolvesResourcesInternally

    override fun prepareLoading(track: MusicTrack) = delegate.prepareLoading(track)

    override fun play(track: MusicTrack, payload: PlaybackPayload) = delegate.play(track, payload)

    override fun play(plan: PlaybackPlan) = delegate.play(plan)

    override fun playResolved(
        logicalTrack: MusicTrack,
        resolveTrack: MusicTrack,
        payload: PlaybackPayload,
    ) {
        val sourceAware = delegate as? ResolvedPlaybackSourceAwareEngine
        if (sourceAware != null) {
            sourceAware.playResolved(logicalTrack, resolveTrack, payload)
        } else {
            delegate.play(logicalTrack, payload)
        }
    }

    override fun pause() = delegate.pause()

    override fun resume() = delegate.resume()

    override fun stop() = delegate.stop()

    override fun seekTo(positionMs: Long) = delegate.seekTo(positionMs)

    override fun setStopAfterCurrentTrack(enabled: Boolean) = delegate.setStopAfterCurrentTrack(enabled)

    override fun close() {
        (delegate as? AutoCloseable)?.close()
    }
}

private class MissingDesktopPlaybackEngine : PlaybackEngine {
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    override fun prepareLoading(track: MusicTrack) {
        mutableState.value = mutableState.value.copy(
            status = PlayerStatus.Loading,
            currentTrack = track,
            positionMs = 0L,
            durationMs = track.durationMs ?: 0L,
            errorMessage = null,
        )
    }

    override fun play(track: MusicTrack, payload: PlaybackPayload) {
        mutableState.value = mutableState.value.copy(
            status = PlayerStatus.Error,
            currentTrack = track,
            positionMs = 0L,
            durationMs = payload.durationMs ?: track.durationMs ?: 0L,
            errorMessage = "桌面播放引擎未由 desktopApp 注入",
        )
    }

    override fun pause() = Unit

    override fun resume() = Unit

    override fun stop() {
        mutableState.value = PlaybackState()
    }

    override fun seekTo(positionMs: Long) {
        mutableState.value = mutableState.value.copy(positionMs = positionMs.coerceAtLeast(0L))
    }
}
