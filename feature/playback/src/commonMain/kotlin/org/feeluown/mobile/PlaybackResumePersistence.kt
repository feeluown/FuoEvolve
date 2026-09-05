package org.feeluown.mobile

/** Only established playback can become a durable resume point. */
fun PlayerStatus.isDurablePlaybackResumeStatus(): Boolean =
    this == PlayerStatus.Playing || this == PlayerStatus.Paused

/** Only a new logical selection invalidates the previous durable resume point. */
val PlaybackStartReason.clearsDurablePlaybackResume: Boolean
    get() = isActiveSelection

/**
 * Platform-neutral durable playback resume state.
 *
 * Physical media URLs are deliberately excluded. A restored session re-enters the normal provider
 * resolution transaction, so expired URLs/tokens are never persisted across application launches.
 */
data class PlaybackResumeSnapshot(
    val currentTrack: MusicTrack,
    val positionMs: Long,
    val durationMs: Long,
    val playbackParts: List<PlaybackPart> = emptyList(),
    val currentPartIndex: Int = -1,
    val currentQueueEntryId: Long? = null,
) {
    fun toPlaybackState(): PlaybackState {
        val normalizedDuration = durationMs.takeIf { it > 0L } ?: currentTrack.durationMs ?: 0L
        val normalizedPosition = positionMs.coerceAtLeast(0L).let { position ->
            normalizedDuration.takeIf { it > 0L }?.let(position::coerceAtMost) ?: position
        }
        return PlaybackState(
            status = PlayerStatus.Paused,
            currentTrack = currentTrack,
            positionMs = normalizedPosition,
            durationMs = normalizedDuration,
            playbackParts = playbackParts,
            currentPartIndex = currentPartIndex.takeIf { it in playbackParts.indices } ?: -1,
            playbackQueueEntryId = currentQueueEntryId?.takeIf { it > 0L },
            lyrics = currentTrack.lyrics,
        )
    }
}

/** Blocking store: platform implementations own their IO/threading policy. */
interface PlaybackResumeStore {
    fun load(): PlaybackResumeSnapshot?
    fun saveSession(state: PlaybackState)
    fun savePosition(positionMs: Long, durationMs: Long)
    fun flush()
    fun clear()
}

object NoOpPlaybackResumeStore : PlaybackResumeStore {
    override fun load(): PlaybackResumeSnapshot? = null
    override fun saveSession(state: PlaybackState) = Unit
    override fun savePosition(positionMs: Long, durationMs: Long) = Unit
    override fun flush() = Unit
    override fun clear() = Unit
}

/** Text codec shared by desktop stores and future platform persistence adapters. */
object PlaybackResumeCodec {
    private const val VERSION = "v1"
    private const val QUEUE_MARKER = "--track--"

    fun encode(snapshot: PlaybackResumeSnapshot): String = buildString {
        appendLine(VERSION)
        appendLine(
            listOf(
                snapshot.positionMs.coerceAtLeast(0L).toString(),
                snapshot.durationMs.coerceAtLeast(0L).toString(),
                snapshot.currentPartIndex.toString(),
                snapshot.currentQueueEntryId?.takeIf { it > 0L }?.toString().orEmpty(),
            ).joinToString("\t")
        )
        snapshot.playbackParts.forEach { part ->
            append("part\t")
            append(escape(part.id))
            append('\t')
            append(escape(part.title))
            append('\t')
            append(part.durationMs?.toString().orEmpty())
            appendLine()
        }
        appendLine(QUEUE_MARKER)
        append(
            PlaybackQueueCodec.encode(
                PlaybackQueueSnapshot(
                    mainQueue = listOf(snapshot.currentTrack),
                    queueIndex = 0,
                )
            )
        )
    }

    fun decode(raw: String): PlaybackResumeSnapshot? {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val marker = normalized.indexOf("\n$QUEUE_MARKER\n")
        if (marker < 0) return null
        val headerLines = normalized.substring(0, marker).lineSequence().filter(String::isNotBlank).toList()
        if (headerLines.firstOrNull() != VERSION) return null
        val state = headerLines.getOrNull(1)?.split('\t') ?: return null
        val queueRaw = normalized.substring(marker + QUEUE_MARKER.length + 2)
        val track = runCatching { PlaybackQueueCodec.decode(queueRaw).mainQueue.firstOrNull() }.getOrNull() ?: return null
        val parts = headerLines.drop(2).mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.firstOrNull() != "part" || fields.size < 3) return@mapNotNull null
            PlaybackPart(
                id = unescape(fields[1]),
                title = unescape(fields[2]),
                durationMs = fields.getOrNull(3)?.toLongOrNull()?.takeIf { it > 0L },
            )
        }
        return PlaybackResumeSnapshot(
            currentTrack = track,
            positionMs = state.getOrNull(0)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            durationMs = state.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            playbackParts = parts,
            currentPartIndex = state.getOrNull(2)?.toIntOrNull() ?: -1,
            currentQueueEntryId = state.getOrNull(3)?.toLongOrNull()?.takeIf { it > 0L },
        )
    }

    private fun escape(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '%' -> append("%25")
                '\t' -> append("%09")
                '\n' -> append("%0A")
                '\r' -> append("%0D")
                else -> append(char)
            }
        }
    }

    private fun unescape(value: String): String {
        val result = StringBuilder()
        var index = 0
        while (index < value.length) {
            if (value[index] == '%' && index + 2 < value.length) {
                val decoded = when (value.substring(index + 1, index + 3)) {
                    "25" -> '%'
                    "09" -> '\t'
                    "0A" -> '\n'
                    "0D" -> '\r'
                    else -> null
                }
                if (decoded != null) {
                    result.append(decoded)
                    index += 3
                    continue
                }
            }
            result.append(value[index])
            index += 1
        }
        return result.toString()
    }
}