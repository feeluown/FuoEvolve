package org.feeluown.mobile

import kotlinx.coroutines.flow.StateFlow

data class ReplacementCandidate(
    val track: MusicTrack,
    val score: Double,
)

data class ReplacementCandidateState(
    val trackId: String? = null,
    val candidates: List<ReplacementCandidate> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * A logical request for the platform playback runtime. The runtime, rather
 * than the UI controller, resolves provider media into a playable URL.
 */
data class PlaybackRequest(
    val track: MusicTrack,
    val resolveTrack: MusicTrack = track,
    val requestedPartIndex: Int? = null,
    val unavailablePolicy: UnavailablePlaybackPolicy = DEFAULT_UNAVAILABLE_PLAYBACK_POLICY,
    val smartReplacementProviderIds: Set<String> = emptySet(),
    val smartReplacementMinScore: Double = DEFAULT_SMART_REPLACEMENT_MIN_SCORE,
    val smartReplacementUseOriginalMetadata: Boolean = true,
    val smartReplacementUseOriginalLyrics: Boolean = true,
    val resolveOnlySelectedReplacement: Boolean = false,
)

/**
 * The current item plus its ordered look-ahead window. The first entry is
 * always the item that must start immediately.
 */
data class PlaybackPlan(
    val generation: Long,
    val requests: List<PlaybackRequest>,
)

data class PlaybackState(
    val status: PlayerStatus = PlayerStatus.Idle,
    val currentTrack: MusicTrack? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val queue: List<MusicTrack> = emptyList(),
    val queueIndex: Int = -1,
    val playMode: PlayMode = PlayMode.ListLoop,
    val lyrics: String? = null,
    val lyricsAlignmentOffsetMs: Long = 0L,
    val audioQuality: String? = null,
    val audioFormatInfo: AudioFormatInfo? = null,
    val audioDecoderInfo: AudioDecoderInfo? = null,
    val playbackParts: List<PlaybackPart> = emptyList(),
    val currentPartIndex: Int = -1,
    val playbackGeneration: Long = 0,
    val errorMessage: String? = null,
)

data class PlaybackQueueSnapshot(
    val mainQueue: List<MusicTrack> = emptyList(),
    val originalMainQueue: List<MusicTrack> = emptyList(),
    val upNextQueue: List<MusicTrack> = emptyList(),
    val queueIndex: Int = -1,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.QUEUE,
    val isFmQueue: Boolean = false,
    val shuffleBeforeFm: Boolean? = null,
)

interface PlaybackQueueStore {
    suspend fun load(): PlaybackQueueSnapshot
    suspend fun save(snapshot: PlaybackQueueSnapshot)
}

object NoOpPlaybackQueueStore : PlaybackQueueStore {
    override suspend fun load(): PlaybackQueueSnapshot = PlaybackQueueSnapshot()

    override suspend fun save(snapshot: PlaybackQueueSnapshot) = Unit
}

object PlaybackQueueCodec {
    private const val CURRENT_VERSION = "v2"

    fun encode(snapshot: PlaybackQueueSnapshot): String {
        return buildList {
            add(CURRENT_VERSION)
            add(
                listOf(
                    snapshot.queueIndex.toString(),
                    snapshot.shuffleEnabled.toString(),
                    snapshot.repeatMode.name,
                    snapshot.isFmQueue.toString(),
                    snapshot.shuffleBeforeFm?.toString().orEmpty(),
                ).joinToString("\t")
            )
            addTracks("main", snapshot.mainQueue)
            addTracks("original", snapshot.originalMainQueue)
            addTracks("upNext", snapshot.upNextQueue)
        }.joinToString("\n")
    }

    fun decode(raw: String): PlaybackQueueSnapshot {
        val lines = raw.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return PlaybackQueueSnapshot()

        val version = lines.first()
        val flags = lines.getOrNull(1)?.split("\t") ?: return PlaybackQueueSnapshot()

        val repeatMode = when (version) {
            "v1" -> {
                val boolValue = flags.getOrNull(2)?.toBooleanStrictOrNull() ?: true
                if (boolValue) RepeatMode.QUEUE else RepeatMode.OFF
            }
            CURRENT_VERSION -> {
                runCatching { RepeatMode.valueOf(flags.getOrNull(2) ?: "QUEUE") }
                    .getOrDefault(RepeatMode.QUEUE)
            }
            else -> RepeatMode.QUEUE
        }

        val tracksBySection = lines.drop(2)
            .mapNotNull { line ->
                val fields = line.split("\t")
                if (fields.size < 2 || fields[0] != "track") return@mapNotNull null
                decodeTrack(fields.drop(2))?.let { track -> fields[1] to track }
            }
            .groupBy({ it.first }, { it.second })
        return PlaybackQueueSnapshot(
            mainQueue = tracksBySection["main"].orEmpty(),
            originalMainQueue = tracksBySection["original"].orEmpty(),
            upNextQueue = tracksBySection["upNext"].orEmpty(),
            queueIndex = flags.getOrNull(0)?.toIntOrNull() ?: -1,
            shuffleEnabled = flags.getOrNull(1)?.toBooleanStrictOrNull() ?: false,
            repeatMode = repeatMode,
            isFmQueue = flags.getOrNull(3)?.toBooleanStrictOrNull() ?: false,
            shuffleBeforeFm = flags.getOrNull(4)?.takeIf { it.isNotBlank() }?.toBooleanStrictOrNull(),
        )
    }

    private fun MutableList<String>.addTracks(section: String, tracks: List<MusicTrack>) {
        tracks.forEach { track ->
            add((listOf("track", section) + encodeTrack(track)).joinToString("\t"))
        }
    }

    private fun encodeTrack(track: MusicTrack): List<String> = listOf(
        track.id,
        track.title,
        track.artists,
        track.album,
        track.source,
        track.sourceType.name,
        track.coverUrl.orEmpty(),
        track.durationMs?.toString().orEmpty(),
        track.localUri.orEmpty(),
        track.lyrics.orEmpty(),
        track.providerId.orEmpty(),
        track.providerName.orEmpty(),
        track.isSmartReplacement.toString(),
        track.originalTitle.orEmpty(),
        track.originalProviderName.orEmpty(),
        track.isUnavailable.toString(),
        track.artistItemId.orEmpty(),
        track.albumItemId.orEmpty(),
        track.originalCoverUrl.orEmpty(),
        track.replacementTitle.orEmpty(),
        track.replacementArtists.orEmpty(),
        track.replacementSource.orEmpty(),
        track.replacementProviderName.orEmpty(),
        track.replacementCoverUrl.orEmpty(),
        track.replacementStrategy.orEmpty(),
        track.replacementScore?.toString().orEmpty(),
        track.localDirectoryId.orEmpty(),
        track.originalId.orEmpty(),
        track.originalArtists.orEmpty(),
        track.originalAlbum.orEmpty(),
        track.originalSource.orEmpty(),
        track.replacementId.orEmpty(),
        track.replacementAlbum.orEmpty(),
    ).map(::escape)

    private fun decodeTrack(fields: List<String>): MusicTrack? {
        if (fields.size < 18) return null
        return runCatching {
            MusicTrack(
                id = unescape(fields[0]),
                title = unescape(fields[1]),
                artists = unescape(fields[2]),
                album = unescape(fields[3]),
                source = unescape(fields[4]),
                sourceType = TrackSourceType.valueOf(unescape(fields[5])),
                coverUrl = unescape(fields[6]).ifBlank { null },
                durationMs = unescape(fields[7]).toLongOrNull(),
                localUri = unescape(fields[8]).ifBlank { null },
                lyrics = unescape(fields[9]).ifBlank { null },
                providerId = unescape(fields[10]).ifBlank { null },
                providerName = unescape(fields[11]).ifBlank { null },
                isSmartReplacement = unescape(fields[12]).toBooleanStrictOrNull() ?: false,
                originalTitle = unescape(fields[13]).ifBlank { null },
                originalProviderName = unescape(fields[14]).ifBlank { null },
                originalCoverUrl = fields.unescapedOrNull(18),
                originalId = fields.unescapedOrNull(27),
                originalArtists = fields.unescapedOrNull(28),
                originalAlbum = fields.unescapedOrNull(29),
                originalSource = fields.unescapedOrNull(30),
                replacementId = fields.unescapedOrNull(31),
                replacementTitle = fields.unescapedOrNull(19),
                replacementArtists = fields.unescapedOrNull(20),
                replacementAlbum = fields.unescapedOrNull(32),
                replacementSource = fields.unescapedOrNull(21),
                replacementProviderName = fields.unescapedOrNull(22),
                replacementCoverUrl = fields.unescapedOrNull(23),
                replacementStrategy = fields.unescapedOrNull(24),
                replacementScore = fields.unescapedOrNull(25)?.toDoubleOrNull(),
                localDirectoryId = fields.unescapedOrNull(26),
                isUnavailable = unescape(fields[15]).toBooleanStrictOrNull() ?: false,
                artistItemId = unescape(fields[16]).ifBlank { null },
                albumItemId = unescape(fields[17]).ifBlank { null },
            )
        }.getOrNull()
    }

    private fun List<String>.unescapedOrNull(index: Int): String? {
        return getOrNull(index)?.let(::unescape)?.ifBlank { null }
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
                when (value.substring(index + 1, index + 3)) {
                    "25" -> {
                        result.append('%')
                        index += 3
                    }
                    "09" -> {
                        result.append('\t')
                        index += 3
                    }
                    "0A" -> {
                        result.append('\n')
                        index += 3
                    }
                    "0D" -> {
                        result.append('\r')
                        index += 3
                    }
                    else -> {
                        result.append(value[index])
                        index += 1
                    }
                }
            } else {
                result.append(value[index])
                index += 1
            }
        }
        return result.toString()
    }
}

interface PlaybackEngine {
    val state: StateFlow<PlaybackState>
    val resolvesResourcesInternally: Boolean
        get() = false
    fun prepareLoading(track: MusicTrack) = Unit
    fun play(track: MusicTrack, payload: PlaybackPayload)
    fun play(plan: PlaybackPlan) = Unit
    fun pause()
    fun resume()
    fun stop()
    fun seekTo(positionMs: Long)
    fun setStopAfterCurrentTrack(enabled: Boolean) = Unit
}
