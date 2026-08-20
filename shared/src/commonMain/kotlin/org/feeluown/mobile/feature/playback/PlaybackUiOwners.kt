package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Owns FullPlayer and queue-overlay navigation without mirroring controller state. */
class DefaultPlaybackNavigationPort : PlaybackNavigationPort {
    override var isFullPlayerOpen by mutableStateOf(false)
        private set
    override var isQueueOpen by mutableStateOf(false)
        private set

    override fun openFullPlayer() {
        isFullPlayerOpen = true
    }

    override fun closeFullPlayer() {
        isQueueOpen = false
        isFullPlayerOpen = false
    }

    override fun toggleQueue() {
        if (!isFullPlayerOpen) return
        isQueueOpen = !isQueueOpen
    }
}

/**
 * Reads rich presentation directly from the playback engine/settings owners and overlays durable
 * queue metadata for the active track. The queue remains the source of restored/local metadata,
 * while the engine remains the runtime identity authority during a real transition.
 */
class DefaultPlaybackPresentationPort(
    private val playbackEngine: PlaybackEngine,
    private val queuePort: PlaybackQueueUiPort,
    settingsRepository: AppSettingsRepository,
    scope: CoroutineScope,
) : PlaybackPresentationPort {
    private var playbackState by mutableStateOf(playbackEngine.state.value)
    private var settings by mutableStateOf(settingsRepository.state.value.settings)

    init {
        scope.launch {
            playbackEngine.state.collect { playbackState = it }
        }
        scope.launch {
            settingsRepository.state.collect { settings = it.settings }
        }
    }

    override val currentTrack: MusicTrack?
        get() = resolvePlaybackPresentationTrack(
            engineTrack = playbackState.currentTrack,
            queueTrack = queuePort.currentQueueTrack,
        )
    override val playbackParts: List<PlaybackPart>
        get() = playbackState.playbackParts
    override val currentPartIndex: Int
        get() = playbackState.currentPartIndex
    override val lyricFontSize: LyricFontSize
        get() = settings.lyricFontSize
    override val themeMode: ThemeMode
        get() = settings.themeMode
    override val dynamicCoverColorEnabled: Boolean
        get() = settings.dynamicCoverColorEnabled
    override val audioQuality: String?
        get() = playbackState.audioQuality
    override val audioFormatInfo: AudioFormatInfo?
        get() = playbackState.audioFormatInfo
    override val audioDecoderInfo: AudioDecoderInfo?
        get() = playbackState.audioDecoderInfo

    override fun seekTo(positionMs: Long) {
        val normalizedPosition = positionMs.coerceAtLeast(0L).let { position ->
            playbackState.durationMs.takeIf { it > 0L }?.let(position::coerceAtMost) ?: position
        }
        playbackEngine.seekTo(normalizedPosition)
    }
}

/**
 * Resolve the rich track shown by player UI without letting stale engine metadata hide durable
 * queue state. During a real track transition where the identities differ, the engine remains the
 * runtime authority; for the same identity, the queue copy wins because it carries restored or
 * locally edited metadata.
 */
internal fun resolvePlaybackPresentationTrack(
    engineTrack: MusicTrack?,
    queueTrack: MusicTrack?,
): MusicTrack? = when {
    engineTrack == null -> queueTrack
    queueTrack == null -> engineTrack
    engineTrack.id == queueTrack.id -> queueTrack
    else -> engineTrack
}
