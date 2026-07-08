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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel

internal class DesktopNativeAudioEngine(
    private val scope: CoroutineScope,
    private val mpvExecutable: String = System.getenv("FUO_MPV") ?: "mpv",
) : PlaybackEngine {
    private val mutableState = MutableStateFlow(PlaybackState())
    private var process: Process? = null
    private var socketFile: File? = null
    private var pollJob: Job? = null
    private var currentPayload: PlaybackPayload? = null

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
        stopProcess()
        currentPayload = payload
        mutableState.value = PlaybackState(
            status = PlayerStatus.Loading,
            currentTrack = track,
            durationMs = payload.durationMs ?: track.durationMs ?: 0,
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

        val nextSocket = File.createTempFile("fuo-mpv-", ".sock").apply { delete() }
        socketFile = nextSocket
        val command = buildList {
            add(mpvExecutable)
            add("--no-terminal")
            add("--force-window=no")
            add("--input-ipc-server=${nextSocket.absolutePath}")
            add("--really-quiet")
            payload.headers.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank()) {
                    add("--http-header-fields=$key: $value")
                }
            }
            add(target)
        }
        runCatching {
            process = ProcessBuilder(command).redirectErrorStream(true).start()
        }.onFailure { throwable ->
            mutableState.value = mutableState.value.copy(
                status = PlayerStatus.Error,
                errorMessage = "无法启动 mpv：${throwable.message ?: "请确认已安装 mpv"}",
            )
            return
        }
        pollJob = scope.launch {
            waitForSocket(nextSocket)
            mutableState.value = mutableState.value.copy(status = PlayerStatus.Playing)
            while (process?.isAlive == true) {
                updateStateFromMpv()
                delay(1_000)
            }
            if (mutableState.value.status != PlayerStatus.Idle && mutableState.value.status != PlayerStatus.Error) {
                mutableState.value = mutableState.value.copy(status = PlayerStatus.Ended)
            }
        }
    }

    override fun pause() {
        sendCommand("set_property", "pause", true)
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Paused)
    }

    override fun resume() {
        sendCommand("set_property", "pause", false)
        mutableState.value = mutableState.value.copy(status = PlayerStatus.Playing)
    }

    override fun stop() {
        stopProcess()
        mutableState.value = PlaybackState(status = PlayerStatus.Idle)
    }

    override fun seekTo(positionMs: Long) {
        sendCommand("set_property", "time-pos", positionMs.coerceAtLeast(0) / 1000.0)
        mutableState.value = mutableState.value.copy(positionMs = positionMs.coerceAtLeast(0))
    }

    private suspend fun waitForSocket(socket: File) {
        repeat(30) {
            if (socket.exists()) return
            delay(100)
        }
    }

    private fun updateStateFromMpv() {
        val position = getPropertyDouble("time-pos")?.let { (it * 1000).toLong() }
        val duration = getPropertyDouble("duration")?.let { (it * 1000).toLong() }
        val paused = getPropertyBoolean("pause")
        val nextStatus = when (paused) {
            true -> PlayerStatus.Paused
            false -> PlayerStatus.Playing
            null -> mutableState.value.status
        }
        mutableState.value = mutableState.value.copy(
            status = nextStatus,
            positionMs = position ?: mutableState.value.positionMs,
            durationMs = duration ?: mutableState.value.durationMs,
            bufferedMs = duration ?: mutableState.value.bufferedMs,
            lyrics = mutableState.value.lyrics ?: currentPayload?.lyrics,
            audioQuality = mutableState.value.audioQuality ?: currentPayload?.audioQuality,
        )
    }

    private fun getPropertyDouble(name: String): Double? {
        val response = sendCommand("get_property", name) ?: return null
        return response.optDouble("data").takeIf { !it.isNaN() && it >= 0.0 }
    }

    private fun getPropertyBoolean(name: String): Boolean? {
        val response = sendCommand("get_property", name) ?: return null
        return if (response.has("data")) response.optBoolean("data") else null
    }

    private fun sendCommand(vararg args: Any): JSONObject? {
        val socket = socketFile?.takeIf { it.exists() } ?: return null
        return runCatching {
            SocketChannel.open(UnixDomainSocketAddress.of(socket.absolutePath)).use { channel ->
                val writer = Channels.newWriter(channel, Charsets.UTF_8)
                val reader = Channels.newReader(channel, Charsets.UTF_8).buffered()
                writer.write(JSONObject().put("command", JSONArray(args.toList())).toString())
                writer.write("\n")
                writer.flush()
                JSONObject(reader.readLine())
            }
        }.getOrNull()
    }

    private fun playbackTarget(track: MusicTrack, payload: PlaybackPayload): String {
        if (payload.url.isNotBlank()) return payload.url
        val localUri = track.localUri.orEmpty()
        if (localUri.startsWith("file:")) return File(URI(localUri)).absolutePath
        return localUri
    }

    private fun stopProcess() {
        pollJob?.cancel()
        pollJob = null
        runCatching { sendCommand("quit") }
        process?.destroy()
        process = null
        socketFile?.delete()
        socketFile = null
        currentPayload = null
    }
}
