package org.feeluown.mobile

import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.feeluown.mobile.playback.api.PlaybackSession

@Volatile
private var desktopPlaybackSessionIntegrationFactory: ((PlaybackSession) -> AutoCloseable)? = null

/** Installs desktop system-media integration without leaking platform APIs into shared code. */
fun installDesktopPlaybackSessionIntegrationFactory(factory: (PlaybackSession) -> AutoCloseable) {
    desktopPlaybackSessionIntegrationFactory = factory
}

internal fun createDesktopPlaybackSessionIntegration(playbackSession: PlaybackSession): AutoCloseable =
    desktopPlaybackSessionIntegrationFactory?.invoke(playbackSession) ?: AutoCloseable { }

@Volatile
private var desktopListeningHistorySinkFactory: ((Path) -> ListeningHistorySink)? = null

/** Installs the JVM SQLDelight history backend while shared playback sees only its sink contract. */
fun installDesktopListeningHistorySinkFactory(factory: (Path) -> ListeningHistorySink) {
    desktopListeningHistorySinkFactory = factory
}

internal fun createDesktopListeningHistorySink(): ListeningHistorySink =
    desktopListeningHistorySinkFactory?.invoke(DesktopAppDirectories.data().resolve("listening_history.db"))
        ?: NoOpListeningHistorySink

@Volatile
private var desktopLocalMusicRepositoryFactory: (() -> LocalMusicRepository)? = null

/** Installs the JVM filesystem-backed local music repository from `desktopApp`. */
fun installDesktopLocalMusicRepositoryFactory(factory: () -> LocalMusicRepository) {
    desktopLocalMusicRepositoryFactory = factory
}

/**
 * Compatibility name retained at the desktop composition edge. The real implementation is supplied
 * by `desktopApp`; common/feature code continues to depend only on [LocalMusicRepository].
 */
internal object DesktopUnsupportedLocalMusicRepository : LocalMusicRepository {
    private val delegate: LocalMusicRepository by lazy {
        desktopLocalMusicRepositoryFactory?.invoke() ?: MissingDesktopLocalMusicRepository
    }

    override val mediaChangeEvents: Flow<Unit>
        get() = delegate.mediaChangeEvents

    override suspend fun updateScanSettings(settings: LocalMusicScanSettings) = delegate.updateScanSettings(settings)
    override suspend fun isDatabaseReady(): Boolean = delegate.isDatabaseReady()
    override suspend fun isDatabaseStale(): Boolean = delegate.isDatabaseStale()
    override suspend fun directories(): List<LocalMusicDirectory> = delegate.directories()
    override suspend fun tracks(): List<MusicTrack> = delegate.tracks()
    override suspend fun refreshDatabase(): List<MusicTrack> = delegate.refreshDatabase()
    override suspend fun search(keyword: String): List<MusicTrack> = delegate.search(keyword)
    override suspend fun updateMetadata(track: MusicTrack, metadata: LocalTrackMetadata) =
        delegate.updateMetadata(track, metadata)
    override suspend fun saveLyrics(track: MusicTrack, lyrics: String) = delegate.saveLyrics(track, lyrics)
}

private object MissingDesktopLocalMusicRepository : LocalMusicRepository {
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
    PlaybackStartReasonAwareEngine,
    ResolvedPlaybackSourceAwareEngine,
    AutoCloseable {
    private val delegate: PlaybackEngine = desktopPlaybackEngineFactory?.invoke()
        ?: MissingDesktopPlaybackEngine()

    override val state: StateFlow<PlaybackState>
        get() = delegate.state

    override val resolvesResourcesInternally: Boolean
        get() = delegate.resolvesResourcesInternally

    override fun prepareLoading(track: MusicTrack) = delegate.prepareLoading(track)

    override fun prepareLoading(track: MusicTrack, reason: PlaybackStartReason) {
        val reasonAware = delegate as? PlaybackStartReasonAwareEngine
        if (reasonAware != null) {
            reasonAware.prepareLoading(track, reason)
        } else {
            delegate.prepareLoading(track)
        }
    }

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
