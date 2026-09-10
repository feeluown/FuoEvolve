package org.feeluown.mobile.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.feeluown.mobile.AudioDecoderInfo
import org.feeluown.mobile.AudioDecoderType
import org.feeluown.mobile.AudioFormatInfo
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackEngine
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlaybackState
import org.feeluown.mobile.PlayerStatus
import org.feeluown.mobile.ResolvedPlaybackSource
import org.feeluown.mobile.ResolvedPlaybackSourceAwareEngine
import org.feeluown.mobile.logicalPlaybackTrack
import org.feeluown.mobile.toResolvedPlaybackSource

/**
 * Shared desktop playback state machine.
 *
 * Host modules provide only the native libmpv transport through [DesktopMpvBackend]. This keeps
 * queue identity, stale-event filtering, loading/playing transitions, seeking and format reporting
 * identical between the JVM/JNA host and the Nucleus/GraalVM host.
 */
class DesktopMpvPlaybackEngine(
    private val backendFactory: ((DesktopMpvBackendEvent) -> Unit) -> DesktopMpvBackend,
) : PlaybackEngine, ResolvedPlaybackSourceAwareEngine, AutoCloseable {
    private val mutableState = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private var backend: DesktopMpvBackend? = null
    private var backendFailure: Throwable? = null

    @Volatile
    private var paused = false

    @Volatile
    private var volume = 1.0

    @Volatile
    private var activeRequestedPath: String? = null

    @Volatile
    private var activePlaylistEntryId: Long? = null

    @Volatile
    private var activeFileLoaded = false

    @Volatile
    private var activePlaybackConfirmed = false

    @Volatile
    private var pendingPlaybackRestart = false

    @Volatile
    private var lastLoadingPositionMs: Long? = null

    override fun prepareLoading(track: MusicTrack) {
        activeRequestedPath = null
        activePlaylistEntryId = null
        activeFileLoaded = false
        activePlaybackConfirmed = false
        pendingPlaybackRestart = false
        lastLoadingPositionMs = null
        backend?.runCatching { stop() }
        val logicalTrack = track.logicalPlaybackTrack()
        mutableState.value = PlaybackState(
            status = PlayerStatus.Loading,
            currentTrack = logicalTrack,
            durationMs = logicalTrack.durationMs ?: 0L,
            volume = volume,
        )
    }

    override fun play(track: MusicTrack, payload: PlaybackPayload) {
        val logicalTrack = track.logicalPlaybackTrack()
        startPlayback(
            logicalTrack = logicalTrack,
            payload = payload,
            resolvedSource = payload.toResolvedPlaybackSource(logicalTrack),
        )
    }

    override fun playResolved(
        logicalTrack: MusicTrack,
        resolveTrack: MusicTrack,
        payload: PlaybackPayload,
    ) {
        val logical = logicalTrack.logicalPlaybackTrack()
        startPlayback(
            logicalTrack = logical,
            payload = payload,
            resolvedSource = payload.toResolvedPlaybackSource(
                logicalTrack = logical,
                resolveTrack = resolveTrack,
            ),
        )
    }

    override fun pause() {
        val activeBackend = backend ?: return
        runCatching { activeBackend.setPaused(true) }
            .onSuccess {
                paused = true
                val current = mutableState.value
                if (current.status == PlayerStatus.Playing || current.status == PlayerStatus.Loading) {
                    mutableState.value = current.copy(status = PlayerStatus.Paused)
                }
            }
            .onFailure(::publishBackendFailure)
    }

    override fun resume() {
        val activeBackend = backend ?: return
        runCatching { activeBackend.setPaused(false) }
            .onSuccess {
                paused = false
                val current = mutableState.value
                if (current.status == PlayerStatus.Paused) {
                    mutableState.value = current.copy(
                        status = if (activePlaybackConfirmed) PlayerStatus.Playing else PlayerStatus.Loading,
                    )
                }
            }
            .onFailure(::publishBackendFailure)
    }

    override fun stop() {
        activeRequestedPath = null
        activePlaylistEntryId = null
        activeFileLoaded = false
        activePlaybackConfirmed = false
        pendingPlaybackRestart = false
        lastLoadingPositionMs = null
        backend?.runCatching { stop() }?.onFailure(::publishBackendFailure)
        paused = false
        mutableState.value = PlaybackState(volume = volume)
    }

    override fun seekTo(positionMs: Long) {
        val current = mutableState.value
        if (current.currentTrack == null || !hasActiveNativeFile()) return
        val upperBound = current.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = positionMs.coerceIn(0L, upperBound)
        backend?.runCatching { seekTo(target) }?.onFailure(::publishBackendFailure)
        mutableState.value = mutableState.value.copy(positionMs = target)
    }

    override fun setVolume(volume: Double) {
        if (!volume.isFinite()) return
        val normalized = volume.coerceIn(0.0, 1.0)
        val activeBackend = ensureBackend() ?: return
        runCatching { activeBackend.setVolume(normalized) }
            .onSuccess {
                this.volume = normalized
                mutableState.value = mutableState.value.copy(volume = normalized)
            }
            .onFailure(::publishBackendFailure)
    }

    override fun close() {
        activeRequestedPath = null
        activePlaylistEntryId = null
        activeFileLoaded = false
        activePlaybackConfirmed = false
        pendingPlaybackRestart = false
        lastLoadingPositionMs = null
        val activeBackend = backend
        backend = null
        runCatching { activeBackend?.close() }
    }

    private fun startPlayback(
        logicalTrack: MusicTrack,
        payload: PlaybackPayload,
        resolvedSource: ResolvedPlaybackSource,
    ) {
        paused = false
        activeRequestedPath = payload.url
        activePlaylistEntryId = null
        activeFileLoaded = false
        activePlaybackConfirmed = false
        pendingPlaybackRestart = false
        lastLoadingPositionMs = null
        mutableState.value = PlaybackState(
            status = PlayerStatus.Loading,
            currentTrack = logicalTrack,
            resolvedSource = resolvedSource,
            durationMs = payload.durationMs ?: logicalTrack.durationMs ?: 0L,
            volume = volume,
            lyrics = payload.lyrics ?: logicalTrack.lyrics,
            audioQuality = payload.audioQuality,
            playbackParts = payload.parts,
            currentPartIndex = payload.currentPartIndex,
        )
        AppLogger.i(
            DESKTOP_MPV_LOG_TAG,
            "playback requested sourceKind=${desktopMpvSourceKind(payload.url)} " +
                "headers=${payload.headers.size} hasAudioQuality=${!payload.audioQuality.isNullOrBlank()}",
        )
        val activeBackend = ensureBackend() ?: run {
            publishBackendFailure(backendFailure ?: IllegalStateException("libmpv unavailable"))
            return
        }
        runCatching {
            activeBackend.load(payload.url, payload.headers)
        }.onFailure(::publishBackendFailure)
    }

    private fun ensureBackend(): DesktopMpvBackend? {
        backend?.let { return it }
        if (backendFailure != null) return null
        return runCatching { backendFactory(::handleBackendEvent) }
            .onSuccess {
                backend = it
                AppLogger.i(DESKTOP_MPV_LOG_TAG, "libmpv backend created")
            }
            .onFailure {
                backendFailure = it
                AppLogger.e(DESKTOP_MPV_LOG_TAG, "libmpv backend creation failed", it)
            }
            .getOrNull()
    }

    private fun handleBackendEvent(event: DesktopMpvBackendEvent) {
        when (event) {
            is DesktopMpvBackendEvent.StartFile -> {
                val current = mutableState.value
                if (
                    current.currentTrack != null &&
                    (current.status == PlayerStatus.Loading || current.status == PlayerStatus.Paused)
                ) {
                    activePlaylistEntryId = event.playlistEntryId
                    activeFileLoaded = false
                    activePlaybackConfirmed = false
                    lastLoadingPositionMs = null
                }
            }
            is DesktopMpvBackendEvent.FileLoaded -> {
                val current = mutableState.value
                if (
                    event.path == activeRequestedPath &&
                    current.currentTrack != null &&
                    current.status != PlayerStatus.Idle &&
                    current.status != PlayerStatus.Error &&
                    current.status != PlayerStatus.Ended
                ) {
                    event.playlistEntryId?.let { activePlaylistEntryId = it }
                    activeFileLoaded = true
                    AppLogger.i(
                        DESKTOP_MPV_LOG_TAG,
                        "event FILE_LOADED entry=${event.playlistEntryId ?: "unknown"}",
                    )
                    if (pendingPlaybackRestart) {
                        pendingPlaybackRestart = false
                        confirmPlaybackRestart()
                    }
                }
            }
            DesktopMpvBackendEvent.PlaybackRestart -> {
                if (!hasActiveNativeFile()) {
                    val current = mutableState.value
                    if (
                        activeRequestedPath != null &&
                        current.currentTrack != null &&
                        (current.status == PlayerStatus.Loading || current.status == PlayerStatus.Paused)
                    ) {
                        pendingPlaybackRestart = true
                        AppLogger.d(DESKTOP_MPV_LOG_TAG, "event PLAYBACK_RESTART deferred until FILE_LOADED")
                    }
                    return
                }
                pendingPlaybackRestart = false
                confirmPlaybackRestart()
            }
            is DesktopMpvBackendEvent.Property -> {
                if (!hasActiveNativeFile()) return
                handleProperty(event.name, event.value)
            }
            is DesktopMpvBackendEvent.EndFile -> {
                val activeEntryId = activePlaylistEntryId
                val matchesActiveEntry = when {
                    activeEntryId != null -> event.playlistEntryId == activeEntryId
                    else -> activeFileLoaded
                }
                if (!matchesActiveEntry) return
                when (event.reason) {
                    MPV_END_FILE_REASON_EOF -> {
                        clearActiveNativeFile()
                        val current = mutableState.value
                        if (current.currentTrack != null) {
                            mutableState.value = current.copy(
                                status = PlayerStatus.Ended,
                                positionMs = current.durationMs.takeIf { it > 0L } ?: current.positionMs,
                            )
                        }
                    }
                    MPV_END_FILE_REASON_ERROR -> {
                        clearActiveNativeFile()
                        publishBackendFailure(
                            IllegalStateException(event.errorMessage ?: "libmpv playback failed"),
                        )
                    }
                    MPV_END_FILE_REASON_STOP,
                    MPV_END_FILE_REASON_QUIT,
                    MPV_END_FILE_REASON_REDIRECT,
                    -> clearActiveNativeFile()
                }
            }
            is DesktopMpvBackendEvent.Failure -> {
                val failedBackend = backend
                backend = null
                try {
                    failedBackend?.close()
                } catch (closeFailure: Exception) {
                    AppLogger.e(DESKTOP_MPV_LOG_TAG, "backend cleanup failed", closeFailure)
                }
                AppLogger.e(DESKTOP_MPV_LOG_TAG, "backend failure", event.throwable)
                publishBackendFailure(event.throwable)
            }
        }
    }

    private fun confirmPlaybackRestart() {
        val current = mutableState.value
        if (current.currentTrack != null && current.status != PlayerStatus.Error) {
            activePlaybackConfirmed = true
            lastLoadingPositionMs = null
            val nextStatus = if (paused) PlayerStatus.Paused else PlayerStatus.Playing
            mutableState.value = current.copy(
                status = nextStatus,
                errorMessage = null,
            )
            AppLogger.i(DESKTOP_MPV_LOG_TAG, "playback confirmed status=$nextStatus")
        }
    }

    private fun hasActiveNativeFile(): Boolean = activePlaylistEntryId != null || activeFileLoaded

    private fun clearActiveNativeFile() {
        activeRequestedPath = null
        activePlaylistEntryId = null
        activeFileLoaded = false
        activePlaybackConfirmed = false
        pendingPlaybackRestart = false
        lastLoadingPositionMs = null
    }

    private fun handleProperty(name: String, value: String?) {
        when (name) {
            "pause" -> {
                val observedPaused = when (value) {
                    "yes", "true" -> true
                    "no", "false" -> false
                    else -> return
                }
                paused = observedPaused
                val current = mutableState.value
                val nextStatus = when {
                    observedPaused && (
                        current.status == PlayerStatus.Loading ||
                            current.status == PlayerStatus.Playing ||
                            current.status == PlayerStatus.Paused
                    ) -> PlayerStatus.Paused
                    !observedPaused && activePlaybackConfirmed && current.status == PlayerStatus.Paused ->
                        PlayerStatus.Playing
                    else -> current.status
                }
                if (nextStatus != current.status) {
                    mutableState.value = current.copy(status = nextStatus)
                }
            }
            "time-pos" -> value.secondsToMsOrNull()?.let { positionMs ->
                val current = mutableState.value
                val normalizedPositionMs = positionMs.coerceAtLeast(0L)
                val previousLoadingPositionMs = lastLoadingPositionMs
                val confirmsPlayback = current.status == PlayerStatus.Loading &&
                    !paused &&
                    previousLoadingPositionMs != null &&
                    normalizedPositionMs > previousLoadingPositionMs
                if (current.status == PlayerStatus.Loading) {
                    lastLoadingPositionMs = normalizedPositionMs
                } else {
                    lastLoadingPositionMs = null
                }
                if (confirmsPlayback) activePlaybackConfirmed = true
                mutableState.value = current.copy(
                    status = if (confirmsPlayback) PlayerStatus.Playing else current.status,
                    positionMs = normalizedPositionMs,
                )
            }
            "duration" -> value.secondsToMsOrNull()?.let { durationMs ->
                mutableState.value = mutableState.value.copy(durationMs = durationMs.coerceAtLeast(0L))
            }
            "demuxer-cache-time" -> value.secondsToMsOrNull()?.let { bufferedMs ->
                val duration = mutableState.value.durationMs
                mutableState.value = mutableState.value.copy(
                    bufferedMs = if (duration > 0L) bufferedMs.coerceIn(0L, duration) else bufferedMs.coerceAtLeast(0L),
                )
            }
            "volume" -> value?.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { mpvVolume ->
                val normalized = (mpvVolume / MPV_VOLUME_SCALE).coerceIn(0.0, 1.0)
                volume = normalized
                mutableState.value = mutableState.value.copy(volume = normalized)
            }
            "file-format" -> updateAudioFormat { it.copy(format = value?.takeIf(String::isNotBlank)) }
            "audio-codec-name" -> {
                val codec = value?.takeIf(String::isNotBlank)
                updateAudioFormat { it.copy(codec = codec) }
                mutableState.value = mutableState.value.copy(
                    audioDecoderInfo = codec?.let {
                        AudioDecoderInfo(
                            type = AudioDecoderType.Software,
                            name = "libmpv / $it",
                        )
                    },
                )
            }
            "audio-bitrate" -> {
                val bitrate = value?.toDoubleOrNull()?.toLong()?.takeIf { it > 0L }
                updateAudioFormat { it.copy(averageBitrate = bitrate) }
            }
        }
    }

    private fun updateAudioFormat(transform: (AudioFormatInfo) -> AudioFormatInfo) {
        val current = mutableState.value
        mutableState.value = current.copy(
            audioFormatInfo = transform(current.audioFormatInfo ?: AudioFormatInfo()),
        )
    }

    private fun publishBackendFailure(throwable: Throwable) {
        activePlaybackConfirmed = false
        pendingPlaybackRestart = false
        lastLoadingPositionMs = null
        val current = mutableState.value
        AppLogger.e(
            DESKTOP_MPV_LOG_TAG,
            "playback failed previousStatus=${current.status}",
            throwable,
        )
        mutableState.value = current.copy(
            status = PlayerStatus.Error,
            errorMessage = desktopMpvFailureMessage(throwable),
        )
    }
}

sealed interface DesktopMpvBackendEvent {
    data class StartFile(val playlistEntryId: Long) : DesktopMpvBackendEvent

    data class FileLoaded(
        val path: String,
        val playlistEntryId: Long? = null,
    ) : DesktopMpvBackendEvent

    data object PlaybackRestart : DesktopMpvBackendEvent

    data class Property(val name: String, val value: String?) : DesktopMpvBackendEvent

    data class EndFile(
        val playlistEntryId: Long,
        val reason: Int,
        val errorMessage: String? = null,
    ) : DesktopMpvBackendEvent

    data class Failure(val throwable: Throwable) : DesktopMpvBackendEvent
}

interface DesktopMpvBackend : AutoCloseable {
    fun load(url: String, headers: Map<String, String>)
    fun setPaused(paused: Boolean)
    fun setVolume(volume: Double) = Unit
    fun stop()
    fun seekTo(positionMs: Long)
}

private fun desktopMpvSourceKind(url: String): String = when {
    url.startsWith("file:", ignoreCase = true) -> "file"
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> "http"
    else -> "other"
}

private fun String?.secondsToMsOrNull(): Long? = this
    ?.toDoubleOrNull()
    ?.takeIf(Double::isFinite)
    ?.let { seconds -> (seconds * 1000.0).toLong() }

private fun desktopMpvFailureMessage(throwable: Throwable): String {
    val detail = throwable.message?.takeIf(String::isNotBlank)
    val isLoadFailure = generateSequence<Throwable>(throwable) { it.cause }
        .any {
            it is UnsatisfiedLinkError ||
                it.message?.contains("Unable to load libmpv", ignoreCase = true) == true ||
                it.message?.contains("mpv helper", ignoreCase = true) == true
        }
    return if (isLoadFailure) {
        "无法加载 libmpv。请确认桌面音频运行时完整，或通过 FUOEVOLVE_LIBMPV_PATH 指定动态库路径。" +
            detail?.let { "（$it）" }.orEmpty()
    } else {
        detail?.let { "libmpv 播放失败：$it" } ?: "libmpv 播放失败"
    }
}

private const val MPV_END_FILE_REASON_EOF = 0
private const val MPV_END_FILE_REASON_STOP = 2
private const val MPV_END_FILE_REASON_QUIT = 3
private const val MPV_END_FILE_REASON_ERROR = 4
private const val MPV_END_FILE_REASON_REDIRECT = 5
private const val MPV_VOLUME_SCALE = 100.0
private const val DESKTOP_MPV_LOG_TAG = "DesktopMpv"
