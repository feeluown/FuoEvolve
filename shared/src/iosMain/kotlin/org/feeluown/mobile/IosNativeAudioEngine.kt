package org.feeluown.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class IosNativeAudioEngine(
    private val scope: CoroutineScope,
    private val output: IosAudioOutput,
    private val providerRepository: ProviderMusicRepository,
) : PlaybackEngine {
    private val mutableState = MutableStateFlow(PlaybackState())
    private val pendingRequests = ArrayDeque<PlaybackRequest>()
    private val preparedItems = mutableMapOf<String, PreparedPlayback>()
    private var activeToken: String? = null
    private var activeGeneration = 0L
    private var requestSerial = 0L
    private var itemSerial = 0L
    private var preloading = false
    private var pendingPreloadError: String? = null

    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    override val resolvesResourcesInternally: Boolean = true

    init {
        scope.launch {
            while (true) {
                updateFromOutput()
                delay(50)
            }
        }
    }

    override fun prepareLoading(track: MusicTrack) {
        mutableState.value = PlaybackState(
            status = PlayerStatus.Loading,
            currentTrack = track,
            durationMs = track.durationMs ?: 0,
            lyrics = track.lyrics,
        )
    }

    override fun play(track: MusicTrack, payload: PlaybackPayload) = error("iOS playback resolves resources in IosNativeAudioEngine")

    override fun play(plan: PlaybackPlan) {
        val first = plan.requests.firstOrNull() ?: return
        val serial = ++requestSerial
        activeGeneration = plan.generation
        activeToken = null
        preloading = false
        pendingPreloadError = null
        pendingRequests.clear()
        pendingRequests.addAll(plan.requests.drop(1))
        preparedItems.clear()
        mutableState.value = PlaybackState(
            status = PlayerStatus.Loading,
            currentTrack = first.track,
            durationMs = first.track.durationMs ?: 0,
            lyrics = first.track.lyrics,
            playbackGeneration = plan.generation,
        )
        scope.launch {
            try {
                val prepared = resolvePlayback(first)
                if (serial != requestSerial) return@launch
                installCurrent(prepared)
                enqueueRemainingParts(prepared)
                preloadNext(serial)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                if (serial != requestSerial) return@launch
                mutableState.value = mutableState.value.copy(
                    status = PlayerStatus.Error,
                    errorMessage = throwable.message ?: "音频资源加载失败",
                )
            }
        }
    }

    override fun pause() {
        output.pause()
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Paused)
    }

    override fun resume() {
        output.resume()
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Playing)
    }

    override fun stop() {
        requestSerial += 1
        activeToken = null
        pendingRequests.clear()
        preparedItems.clear()
        preloading = false
        pendingPreloadError = null
        output.stop()
        mutableState.value = PlaybackState(status = PlayerStatus.Idle)
    }

    override fun seekTo(positionMs: Long) {
        output.seekTo(positionMs)
        mutableState.value = mutableState.value.copy(positionMs = positionMs.coerceAtLeast(0))
    }

    private fun preloadNext(serial: Long) {
        if (preloading || pendingRequests.isEmpty()) return
        val request = pendingRequests.removeFirst()
        preloading = true
        scope.launch {
            var retryNext = false
            var stopMessage: String? = null
            try {
                val prepared = resolvePlayback(request)
                if (serial != requestSerial) return@launch
                preparedItems[prepared.token] = prepared
                output.enqueue(
                    prepared.payload.url,
                    prepared.payload.headers,
                    prepared.track.title,
                    prepared.track.artists,
                    prepared.track.album,
                    prepared.token,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (serial == requestSerial) {
                    if (request.unavailablePolicy == UnavailablePlaybackPolicy.Skip ||
                        request.unavailablePolicy == UnavailablePlaybackPolicy.SmartReplace
                    ) {
                        // 与 Android 保持一致：跳过策略不打断当前歌曲，继续尝试后续条目。
                        retryNext = true
                    } else {
                        stopMessage = "下一首资源加载失败：${request.track.title}"
                    }
                }
            } finally {
                if (serial == requestSerial) {
                    preloading = false
                }
            }
            if (retryNext) preloadNext(serial)
            if (stopMessage != null && serial == requestSerial) pendingPreloadError = stopMessage
        }
    }

    private suspend fun resolvePlayback(request: PlaybackRequest): PreparedPlayback {
        val payload = withTimeout(PLAYBACK_RESOLVE_TIMEOUT_MS) {
            request.resolveTrack.localUri?.let { uri -> request.resolveTrack.toLocalPayload(uri) }
                ?: providerRepository.resolve(
                    request.resolveTrack,
                    request.unavailablePolicy,
                    request.smartReplacementProviderIds,
                    request.smartReplacementMinScore,
                    request.smartReplacementUseOriginalMetadata,
                    request.smartReplacementUseOriginalLyrics,
                )
        }
        val parts = payload.parts
        val currentPartIndex = when {
            parts.isEmpty() -> -1
            payload.currentPartIndex in parts.indices -> payload.currentPartIndex
            request.requestedPartIndex?.let { it in parts.indices } == true -> request.requestedPartIndex ?: -1
            else -> -1
        }
        val track = request.track.copy(
            title = if (parts.isEmpty()) payload.title.ifBlank { request.track.title } else request.track.title,
            artists = payload.artists.ifBlank { request.track.artists },
            album = payload.album.ifBlank { request.track.album },
            source = payload.source.ifBlank { request.track.source },
            coverUrl = payload.coverUrl ?: request.track.coverUrl,
            durationMs = if (parts.isEmpty()) payload.durationMs ?: request.track.durationMs else request.track.durationMs,
            providerName = payload.providerName ?: request.track.providerName,
            isSmartReplacement = payload.isSmartReplacement,
            originalTitle = payload.originalTitle,
            originalProviderName = payload.originalProviderName,
            originalCoverUrl = payload.originalCoverUrl,
            replacementTitle = payload.replacementTitle,
            replacementArtists = payload.replacementArtists,
            replacementSource = payload.replacementSource,
            replacementProviderName = payload.replacementProviderName,
            replacementCoverUrl = payload.replacementCoverUrl,
            replacementStrategy = payload.replacementStrategy,
            replacementScore = payload.replacementScore,
            isUnavailable = false,
        )
        return PreparedPlayback(
            token = "$activeGeneration:${++itemSerial}:${track.id}",
            request = request,
            track = track,
            payload = payload.copy(currentPartIndex = currentPartIndex),
        )
    }

    private fun installCurrent(prepared: PreparedPlayback) {
        activeToken = prepared.token
        preparedItems[prepared.token] = prepared
        output.play(
            prepared.payload.url,
            prepared.payload.headers,
            prepared.track.title,
            prepared.track.artists,
            prepared.track.album,
            prepared.token,
        )
        mutableState.value = prepared.toPlaybackState(PlayerStatus.Loading)
    }

    private fun enqueueRemainingParts(prepared: PreparedPlayback) {
        val currentPartIndex = prepared.payload.currentPartIndex
        if (currentPartIndex !in 0 until prepared.payload.parts.lastIndex) return
        prepared.payload.parts.drop(currentPartIndex + 1)
            .asReversed()
            .forEachIndexed { reverseIndex, part ->
                val partIndex = prepared.payload.parts.lastIndex - reverseIndex
                pendingRequests.addFirst(
                    prepared.request.copy(
                        resolveTrack = part.toTrack(prepared.request.track),
                        requestedPartIndex = partIndex,
                    ),
                )
            }
    }

    private fun updateFromOutput() {
        val token = activeToken ?: return
        val outputToken = output.currentItemToken()
        if (outputToken != null && outputToken != token) {
            preparedItems[outputToken]?.let { prepared ->
                activeToken = outputToken
                installCurrentState(prepared)
                enqueueRemainingParts(prepared)
                preloadNext(requestSerial)
            }
        }
        val status = when (output.playbackStatus()) {
            "Loading" -> PlayerStatus.Loading
            "Playing" -> PlayerStatus.Playing
            "Paused" -> PlayerStatus.Paused
            "Ended" -> when {
                pendingPreloadError != null -> PlayerStatus.Error
                preloading || pendingRequests.isNotEmpty() -> PlayerStatus.Loading
                else -> PlayerStatus.Ended
            }
            "Error" -> PlayerStatus.Error
            else -> PlayerStatus.Idle
        }
        mutableState.value = mutableState.value.copy(
            status = status,
            positionMs = output.positionMs().coerceAtLeast(0),
            durationMs = output.durationMs().takeIf { it > 0 } ?: mutableState.value.durationMs,
            bufferedMs = output.bufferedMs().coerceAtLeast(0),
            audioFormatInfo = output.audioFormatInfo(),
            spectrumLevels = if (status == PlayerStatus.Playing) output.spectrumLevels() else emptyList(),
            errorMessage = if (status == PlayerStatus.Error) pendingPreloadError ?: output.errorMessage() else output.errorMessage(),
        )
    }

    private fun installCurrentState(prepared: PreparedPlayback) {
        mutableState.value = prepared.toPlaybackState(PlayerStatus.Loading)
    }

    private fun PreparedPlayback.toPlaybackState(status: PlayerStatus): PlaybackState = PlaybackState(
        status = status,
        currentTrack = track,
        durationMs = payload.durationMs ?: track.durationMs ?: 0,
        lyrics = payload.lyrics,
        audioQuality = payload.audioQuality,
        playbackParts = payload.parts,
        currentPartIndex = payload.currentPartIndex,
        playbackGeneration = activeGeneration,
    )

    private fun PlaybackPart.toTrack(parent: MusicTrack): MusicTrack = parent.copy(
        id = id,
        title = title.ifBlank { parent.title },
        durationMs = durationMs ?: parent.durationMs,
        providerId = id,
    )

    private fun MusicTrack.toLocalPayload(uri: String): PlaybackPayload = PlaybackPayload(
        url = uri,
        title = title,
        artists = artists,
        album = album,
        source = source,
        coverUrl = coverUrl,
        durationMs = durationMs,
        lyrics = lyrics,
        providerName = providerName,
    )

    private data class PreparedPlayback(
        val token: String,
        val request: PlaybackRequest,
        val track: MusicTrack,
        val payload: PlaybackPayload,
    )

    private companion object {
        private const val PLAYBACK_RESOLVE_TIMEOUT_MS = 30_000L
    }
}
