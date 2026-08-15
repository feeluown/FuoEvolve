package org.feeluown.mobile

import android.content.Context
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import io.github.proify.lyricon.lyric.model.LyricWord as LyriconLyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine as LyriconRichLyricLine
import io.github.proify.lyricon.lyric.model.Song as LyriconSong
import io.github.proify.lyricon.provider.ConnectionListener
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal const val LYRICON_PACKAGE_NAME = "io.github.proify.lyricon"

@Suppress("DEPRECATION")
internal fun isLyriconInstalled(context: Context): Boolean = runCatching {
    context.packageManager.getPackageInfo(LYRICON_PACKAGE_NAME, 0)
}.isSuccess

internal class LyriconLyricsPublisher(
    context: Context,
    private val controller: FuoPlayerController,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private var collectJob: Job? = null
    private var provider: LyriconProvider? = null
    private var lastTrackKey: String? = null
    private var lastPayload: StatusBarLyricsPayload? = null

    private val connectionListener = object : ConnectionListener {
        override fun onConnected(provider: LyriconProvider) {
            Log.d(TAG, "Lyricon connected")
        }

        override fun onReconnected(provider: LyriconProvider) {
            Log.d(TAG, "Lyricon reconnected")
        }

        override fun onDisconnected(provider: LyriconProvider) {
            Log.w(TAG, "Lyricon disconnected")
        }

        override fun onConnectTimeout(provider: LyriconProvider) {
            Log.w(TAG, "Lyricon connection timed out; playback continues normally")
        }
    }

    fun start() {
        if (collectJob != null) return
        collectJob = scope.launch {
            snapshotFlow {
                val state = controller.playbackState
                Snapshot(
                    enabled = controller.statusBarLyricsEnabled,
                    status = state.status,
                    track = state.currentTrack,
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    lyrics = state.lyrics,
                )
            }
                .distinctUntilChanged()
                .collect(::publish)
        }
    }

    fun close() {
        collectJob?.cancel()
        collectJob = null
        deactivate()
    }

    private fun publish(snapshot: Snapshot) {
        val track = snapshot.track
        if (
            !snapshot.enabled ||
            track == null ||
            snapshot.status == PlayerStatus.Idle ||
            snapshot.status == PlayerStatus.Error ||
            snapshot.status == PlayerStatus.Ended
        ) {
            deactivate()
            return
        }

        val payload = buildStatusBarLyricsPayload(
            rawLyrics = snapshot.lyrics,
            durationMs = snapshot.durationMs.takeIf { it > 0L } ?: track.durationMs,
        )
        if (payload == StatusBarLyricsPayload.Empty) {
            deactivate()
            return
        }

        val activeProvider = ensureProvider() ?: return
        val trackKey = listOf(track.source, track.id, track.title, track.artists).joinToString("\u0000")
        runCatching {
            if (trackKey != lastTrackKey || payload != lastPayload) {
                when (payload) {
                    StatusBarLyricsPayload.Empty -> Unit
                    is StatusBarLyricsPayload.Text -> activeProvider.player.sendText(payload.text)
                    is StatusBarLyricsPayload.Timed -> activeProvider.player.setSong(
                        LyriconSong(
                            id = "${track.source}:${track.id}",
                            name = track.title,
                            artist = track.artists,
                            duration = snapshot.durationMs.takeIf { it > 0L } ?: track.durationMs ?: 0L,
                            lyrics = payload.lines.map { line ->
                                LyriconRichLyricLine(
                                    begin = line.beginMs,
                                    end = line.endMs,
                                    text = line.text,
                                    words = line.words?.map { word ->
                                        LyriconLyricWord(
                                            begin = word.beginMs,
                                            end = word.endMs,
                                            text = word.text,
                                        )
                                    },
                                    translation = line.translation,
                                    roma = line.romanization,
                                )
                            },
                        ),
                    )
                }
                lastTrackKey = trackKey
                lastPayload = payload
            }
            activeProvider.player.setPosition(snapshot.positionMs.coerceAtLeast(0L))
            activeProvider.player.setPlaybackState(snapshot.status == PlayerStatus.Playing)
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to publish Lyricon state; playback is unaffected", throwable)
        }
    }

    private fun ensureProvider(): LyriconProvider? {
        provider?.let { return it }
        if (!isLyriconInstalled(appContext)) {
            Log.d(TAG, "Lyricon is not installed; skip status bar lyrics")
            return null
        }
        return runCatching {
            LyriconFactory.createProvider(appContext).also { created ->
                created.autoSync = true
                created.player.setDisplayTranslation(true)
                created.player.setDisplayRoma(true)
                created.service.addConnectionListener(connectionListener)
                if (!created.register()) {
                    Log.w(TAG, "Lyricon provider registration was not started; playback is unaffected")
                }
                provider = created
            }
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to initialize Lyricon; playback is unaffected", throwable)
        }.getOrNull()
    }

    private fun deactivate() {
        val activeProvider = provider ?: return
        runCatching {
            activeProvider.player.setPlaybackState(false)
            activeProvider.player.setSong(null)
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to clear Lyricon song", throwable)
        }
        runCatching { activeProvider.unregister() }
            .onFailure { throwable -> Log.w(TAG, "Unable to unregister Lyricon", throwable) }
        runCatching {
            activeProvider.service.removeConnectionListener(connectionListener)
            activeProvider.destroy()
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to destroy Lyricon provider", throwable)
        }
        provider = null
        lastTrackKey = null
        lastPayload = null
    }

    private data class Snapshot(
        val enabled: Boolean,
        val status: PlayerStatus,
        val track: MusicTrack?,
        val positionMs: Long,
        val durationMs: Long,
        val lyrics: String?,
    )

    private companion object {
        const val TAG = "LyriconLyricsPublisher"
    }
}
