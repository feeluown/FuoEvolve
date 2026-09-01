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
 * logical queue metadata for the active track. Platform engines may still expose legacy decorated
 * tracks during the migration; physical source identity is projected separately through
 * [resolvedSource]. A presentation-only compatibility decoration keeps existing player UI working
 * without allowing replacement metadata back into playback business state.
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
        )?.withResolvedPresentation(resolvedSource)
    override val resolvedSource: ResolvedPlaybackSource?
        get() = playbackState.resolvedSource
            ?: playbackState.currentTrack?.toLegacyResolvedPlaybackSource()
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
 * Resolve the logical track shown by player UI without allowing physical replacement metadata from
 * the engine to become business identity. The queue wins for the active logical identity because it
 * carries restored/local edits; engine-only states are normalized back to logical identity.
 */
internal fun resolvePlaybackPresentationTrack(
    engineTrack: MusicTrack?,
    queueTrack: MusicTrack?,
): MusicTrack? {
    val logicalEngineTrack = engineTrack?.logicalPlaybackTrack()
    val logicalQueueTrack = queueTrack?.logicalPlaybackTrack()
    return when {
        logicalEngineTrack == null -> logicalQueueTrack
        logicalQueueTrack == null -> logicalEngineTrack
        logicalEngineTrack.id == logicalQueueTrack.id -> logicalQueueTrack
        else -> logicalEngineTrack
    }
}

private fun MusicTrack.withResolvedPresentation(source: ResolvedPlaybackSource?): MusicTrack {
    val resolved = source?.takeIf { it.isReplacement } ?: return this
    return copy(
        isSmartReplacement = true,
        originalId = id,
        originalTitle = title,
        originalArtists = artists,
        originalAlbum = album,
        originalSource = this.source,
        originalProviderName = providerName,
        originalCoverUrl = coverUrl,
        replacementId = resolved.trackId,
        replacementTitle = resolved.title,
        replacementArtists = resolved.artists,
        replacementAlbum = resolved.album,
        replacementSource = resolved.source,
        replacementProviderName = resolved.providerName,
        replacementCoverUrl = resolved.coverUrl,
        replacementStrategy = resolved.replacementStrategy,
        replacementScore = resolved.replacementScore,
    )
}
