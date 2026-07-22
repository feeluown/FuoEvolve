package org.feeluown.mobile.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackEngine
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.PlaybackState
import org.feeluown.mobile.PlayerStatus
import java.io.File
import java.net.URI

internal class DesktopNativeAudioEngine(
    private val scope: CoroutineScope,
    private val clientFactory: () -> MpvClient = { JnaMpvClient() },
) : PlaybackEngine {
    private val mutableState = MutableStateFlow(PlaybackState())
    private val clientLifecycleLock = Any()
    @Volatile
    private var client: MpvClient? = null
    private var monitorJob: Job? = null
    private var currentPayload: PlaybackPayload? = null
    private var desiredVolume = 1.0

    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    override fun prepareLoading(track: MusicTrack) {
        mutableState.value = mutableState.value.copy(
            status = PlayerStatus.Loading,
            currentTrack = track,
            positionMs = 0,
            durationMs = track.durationMs ?: 0,
            bufferedMs = 0,
            lyrics = track.lyrics,
            audioQuality = null,
            errorMessage = null,
        )
    }

    override fun play(track: MusicTrack, payload: PlaybackPayload) {
        stopClient()
        currentPayload = payload
        mutableState.value = PlaybackState(
            status = PlayerStatus.Loading,
            currentTrack = track,
            durationMs = payload.durationMs ?: track.durationMs ?: 0,
            volume = desiredVolume,
            lyrics = payload.lyrics,
            audioQuality = payload.audioQuality,
            playbackParts = payload.parts,
            currentPartIndex = payload.currentPartIndex,
        )

        val target = playbackTarget(track, payload)
        if (target.isBlank()) {
            mutableState.value = mutableState.value.copy(
                status = PlayerStatus.Error,
                errorMessage = "播放地址为空",
            )
            return
        }

        var createdClient: MpvClient? = null
        val nextClient = runCatching {
            val candidate = clientFactory()
            createdClient = candidate
            candidate.apply {
                setOption("vid", "no")
                setOption("audio-display", "no")
                initialize()
                setProperty("volume", (desiredVolume * MPV_VOLUME_SCALE).toString())
                payload.headers.forEach { (key, value) ->
                    if (key.isNotBlank() && value.isNotBlank()) {
                        command("change-list", "http-header-fields", "append", "$key: $value")
                    }
                }
                command("loadfile", target)
            }
        }.getOrElse { throwable ->
            runCatching { createdClient?.close() }
            currentPayload = null
            mutableState.value = mutableState.value.copy(
                status = PlayerStatus.Error,
                errorMessage = "无法启动 libmpv：${throwable.message ?: "请确认已安装 libmpv"}",
            )
            return
        }
        synchronized(clientLifecycleLock) {
            client = nextClient
        }
        monitorJob = scope.launch {
            monitorPlayback(nextClient)
        }
    }

    override fun pause() {
        val currentClient = client ?: return
        runCatching { currentClient.setProperty("pause", "yes") }
            .onSuccess { mutableState.value = mutableState.value.copy(status = PlayerStatus.Paused) }
    }

    override fun resume() {
        val currentClient = client ?: return
        runCatching { currentClient.setProperty("pause", "no") }
            .onSuccess { mutableState.value = mutableState.value.copy(status = PlayerStatus.Playing) }
    }

    override fun stop() {
        stopClient()
        mutableState.value = PlaybackState(status = PlayerStatus.Idle, volume = desiredVolume)
    }

    override fun seekTo(positionMs: Long) {
        val currentClient = client ?: return
        val targetSeconds = positionMs.coerceAtLeast(0) / 1000.0
        runCatching { currentClient.command("seek", targetSeconds.toString(), "absolute+exact") }
            .onSuccess { mutableState.value = mutableState.value.copy(positionMs = positionMs.coerceAtLeast(0)) }
    }

    override fun setVolume(volume: Double) {
        desiredVolume = volume.coerceIn(0.0, 1.0)
        runCatching { client?.setProperty("volume", (desiredVolume * MPV_VOLUME_SCALE).toString()) }
        mutableState.value = mutableState.value.copy(volume = desiredVolume)
    }

    internal fun currentPositionMs(): Long {
        val fallbackState = mutableState.value
        if (fallbackState.status != PlayerStatus.Playing && fallbackState.status != PlayerStatus.Paused) {
            return fallbackState.positionMs.coerceAtLeast(0)
        }
        val position = synchronized(clientLifecycleLock) {
            val currentClient = client ?: return@synchronized null
            runCatching {
                currentClient.getProperty("time-pos")
                    ?.toDoubleOrNull()
                    ?.times(1_000)
                    ?.toLong()
            }.getOrNull()
        } ?: fallbackState.positionMs
        val nonNegativePosition = position.coerceAtLeast(0)
        return if (fallbackState.durationMs > 0) {
            nonNegativePosition.coerceAtMost(fallbackState.durationMs)
        } else {
            nonNegativePosition
        }
    }

    private suspend fun monitorPlayback(monitoredClient: MpvClient) {
        var finished = false
        var loaded = false
        while (client === monitoredClient && !finished) {
            while (true) {
                val event = runCatching { monitoredClient.pollEvent() }.getOrElse { throwable ->
                    mutableState.value = mutableState.value.copy(
                        status = PlayerStatus.Error,
                        errorMessage = "libmpv 事件读取失败：${throwable.message.orEmpty()}",
                    )
                    return
                }
                when (event.type) {
                    MpvEventType.None -> break
                    MpvEventType.FileLoaded -> {
                        loaded = true
                        mutableState.value = mutableState.value.copy(status = PlayerStatus.Playing)
                    }
                    MpvEventType.EndFile -> {
                        mutableState.value = if (event.errorCode < 0) {
                            mutableState.value.copy(
                                status = PlayerStatus.Error,
                                errorMessage = "libmpv 播放失败：${monitoredClient.errorDescription(event.errorCode)}",
                            )
                        } else {
                            mutableState.value.copy(status = PlayerStatus.Ended)
                        }
                        finished = true
                    }
                    MpvEventType.Shutdown -> {
                        mutableState.value = mutableState.value.copy(status = PlayerStatus.Ended)
                        finished = true
                    }
                    MpvEventType.Other -> Unit
                }
            }
            if (!finished) {
                runCatching { updateStateFromMpv(monitoredClient, loaded) }.onFailure { throwable ->
                    mutableState.value = mutableState.value.copy(
                        status = PlayerStatus.Error,
                        errorMessage = "libmpv 状态读取失败：${throwable.message.orEmpty()}",
                    )
                    finished = true
                }
                if (!finished) delay(1_000)
            }
        }
    }

    private fun updateStateFromMpv(currentClient: MpvClient, loaded: Boolean) {
        val position = currentClient.getProperty("time-pos")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        val duration = currentClient.getProperty("duration")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        val volume = currentClient.getProperty("volume")
            ?.toDoubleOrNull()
            ?.div(MPV_VOLUME_SCALE)
            ?.coerceIn(0.0, 1.0)
        val paused = when (currentClient.getProperty("pause")) {
            "yes" -> true
            "no" -> false
            else -> null
        }
        val nextStatus = if (loaded) {
            when (paused) {
                true -> PlayerStatus.Paused
                false -> PlayerStatus.Playing
                null -> mutableState.value.status
            }
        } else {
            mutableState.value.status
        }
        mutableState.value = mutableState.value.copy(
            status = nextStatus,
            positionMs = position ?: mutableState.value.positionMs,
            durationMs = duration ?: mutableState.value.durationMs,
            volume = volume ?: desiredVolume,
            bufferedMs = duration ?: mutableState.value.bufferedMs,
            lyrics = mutableState.value.lyrics ?: currentPayload?.lyrics,
            audioQuality = mutableState.value.audioQuality ?: currentPayload?.audioQuality,
        )
    }

    private fun playbackTarget(track: MusicTrack, payload: PlaybackPayload): String {
        if (payload.url.isNotBlank()) return payload.url
        val localUri = track.localUri.orEmpty()
        if (localUri.startsWith("file:")) return File(URI(localUri)).absolutePath
        return localUri
    }

    private fun stopClient() {
        monitorJob?.cancel()
        monitorJob = null
        val stoppedClient = synchronized(clientLifecycleLock) {
            val currentClient = client
            client = null
            currentClient
        }
        stoppedClient?.close()
        currentPayload = null
    }

    private companion object {
        const val MPV_VOLUME_SCALE = 100.0
    }
}
