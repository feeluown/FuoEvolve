package org.feeluown.mobile

import kotlin.math.abs

/**
 * Re-ranks candidates that already passed the configured replacement score threshold.
 *
 * Recording identity is treated as a structural signal before the legacy score:
 * 1. Prefer the same recording/version (studio, live, remix, cover, etc.).
 * 2. For multi-artist originals, prefer candidates that preserve the full artist lineup.
 * 3. Within the same recording profile, keep the legacy score and metadata confidence order.
 *
 * This keeps uploader/official provenance useful without allowing it to override a clearly
 * different recording, such as an official live or solo version replacing the studio duet.
 */
internal fun sortReplacementScoreTies(
    origin: MusicTrack,
    ranked: List<ReplacementCandidate>,
): List<ReplacementCandidate> {
    if (ranked.size < 2) return ranked
    return ranked
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<ReplacementCandidate>> {
                replacementRecordingVersionRank(origin, it.value.track)
            }
                .thenByDescending {
                    replacementCollaborationCoverage(origin, it.value.track)
                }
                .thenByDescending { it.value.score }
                .thenByDescending { replacementTieBreakConfidence(origin, it.value.track) }
                .thenBy { it.index },
        )
        .map { it.value }
}

internal fun replacementTieBreakConfidence(origin: MusicTrack, candidate: MusicTrack): Double {
    val titleScore = orderedTextSimilarity(origin.title, candidate.title)
    val artistScore = artistSetSimilarity(origin.artists, candidate.artists)
    val albumScore = exactMetadataMatch(origin.album, candidate.album)
    val durationScore = durationCloseness(origin.durationMs, candidate.durationMs)
    return titleScore * 0.45 + artistScore * 0.30 + albumScore * 0.15 + durationScore * 0.10
}

private fun replacementRecordingVersionRank(origin: MusicTrack, candidate: MusicTrack): Int {
    val originVersions = recordingVersionKinds("${origin.title} ${origin.album}")
    val candidateVersions = recordingVersionKinds(
        buildString {
            append(candidate.title)
            append(' ')
            append(candidate.album)
            if (candidate.providerTags.isNotEmpty()) {
                append(' ')
                append(candidate.providerTags.joinToString(" "))
            }
        },
    )
    return when {
        originVersions == candidateVersions -> 3
        originVersions.isEmpty() && candidateVersions.isNotEmpty() -> 0
        originVersions.isNotEmpty() && candidateVersions.isEmpty() -> 1
        originVersions.intersect(candidateVersions).isNotEmpty() -> 1
        else -> 0
    }
}

private fun replacementCollaborationCoverage(origin: MusicTrack, candidate: MusicTrack): Double {
    val originArtists = splitArtists(origin.artists)
    if (originArtists.size <= 1) return 1.0
    val evidence = normalizeTieBreakText("${candidate.title} ${candidate.artists}")
    if (evidence.isBlank()) return 0.0
    val matched = originArtists.count { artist ->
        artist.length >= 2 && artist in evidence
    }
    return matched.toDouble() / originArtists.size.toDouble()
}

private enum class RecordingVersionKind {
    COVER,
    REMIX,
    LIVE,
    INSTRUMENTAL,
    ACOUSTIC,
    DEMO,
    SOLO,
}

private fun recordingVersionKinds(value: String): Set<RecordingVersionKind> = buildSet {
    if (RECORDING_COVER_PATTERNS.any { it.containsMatchIn(value) }) add(RecordingVersionKind.COVER)
    if (RECORDING_REMIX_PATTERNS.any { it.containsMatchIn(value) }) add(RecordingVersionKind.REMIX)
    if (RECORDING_LIVE_PATTERNS.any { it.containsMatchIn(value) }) add(RecordingVersionKind.LIVE)
    if (RECORDING_INSTRUMENTAL_PATTERNS.any { it.containsMatchIn(value) }) add(RecordingVersionKind.INSTRUMENTAL)
    if (RECORDING_ACOUSTIC_PATTERNS.any { it.containsMatchIn(value) }) add(RecordingVersionKind.ACOUSTIC)
    if (RECORDING_DEMO_PATTERNS.any { it.containsMatchIn(value) }) add(RecordingVersionKind.DEMO)
    if (RECORDING_SOLO_PATTERNS.any { it.containsMatchIn(value) }) add(RecordingVersionKind.SOLO)
}

private fun orderedTextSimilarity(leftValue: String, rightValue: String): Double {
    val left = normalizeTieBreakText(leftValue)
    val right = normalizeTieBreakText(rightValue)
    if (left.isBlank() || right.isBlank()) return 0.0
    if (left == right) return 1.0
    val longest = maxOf(left.length, right.length)
    val distance = levenshteinDistance(left, right)
    return (1.0 - distance.toDouble() / longest.toDouble()).coerceIn(0.0, 1.0)
}

private fun artistSetSimilarity(leftValue: String, rightValue: String): Double {
    val left = splitArtists(leftValue)
    val right = splitArtists(rightValue)
    if (left.isEmpty() || right.isEmpty()) return 0.0
    if (left == right) return 1.0
    val intersection = left.intersect(right).size.toDouble()
    val union = left.union(right).size.toDouble()
    return if (union == 0.0) 0.0 else intersection / union
}

private fun exactMetadataMatch(leftValue: String, rightValue: String): Double {
    val left = normalizeTieBreakText(leftValue)
    val right = normalizeTieBreakText(rightValue)
    if (left.isBlank() || right.isBlank()) return 0.0
    return if (left == right) 1.0 else 0.0
}

private fun durationCloseness(left: Long?, right: Long?): Double {
    val leftDuration = left?.takeIf { it > 0 } ?: return 0.0
    val rightDuration = right?.takeIf { it > 0 } ?: return 0.0
    val difference = abs(leftDuration - rightDuration)
    return when {
        difference <= 3_000L -> 1.0
        difference <= 10_000L -> 0.5
        else -> 0.0
    }
}

private fun splitArtists(value: String): Set<String> {
    val expanded = Regex("(?i)\\b(feat|featuring|ft|with)\\b\\.?").replace(value, "/")
    return Regex("\\s*(?:/|、|,|，|;|；|&|\\+|＋|×)\\s*")
        .split(expanded)
        .map { normalizeTieBreakText(it) }
        .filter { it.isNotBlank() }
        .toSet()
}

private fun levenshteinDistance(left: String, right: String): Int {
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length
    var previous = IntArray(right.length + 1) { it }
    var current = IntArray(right.length + 1)
    left.forEachIndexed { leftIndex, leftChar ->
        current[0] = leftIndex + 1
        right.forEachIndexed { rightIndex, rightChar ->
            val insertion = current[rightIndex] + 1
            val deletion = previous[rightIndex + 1] + 1
            val substitution = previous[rightIndex] + if (leftChar == rightChar) 0 else 1
            current[rightIndex + 1] = minOf(insertion, deletion, substitution)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[right.length]
}

private fun normalizeTieBreakText(value: String): String =
    value.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

private val RECORDING_COVER_PATTERNS = listOf(
    Regex("(?i)(?:^|[^a-z0-9])cover(?:$|[^a-z0-9])"),
    Regex("翻唱"),
    Regex("歌ってみた"),
    Regex("弾いてみた"),
)
private val RECORDING_REMIX_PATTERNS = listOf(
    Regex("(?i)(?:^|[^a-z0-9])remix(?:$|[^a-z0-9])"),
    Regex("重混"),
)
private val RECORDING_LIVE_PATTERNS = listOf(
    Regex("(?i)(?:^|[^a-z0-9])live(?:$|[^a-z0-9])"),
    Regex("现场"),
    Regex("現場"),
    Regex("ライブ"),
)
private val RECORDING_INSTRUMENTAL_PATTERNS = listOf(
    Regex("(?i)(?:^|[^a-z0-9])instrumental(?:$|[^a-z0-9])"),
    Regex("(?i)(?:^|[^a-z0-9])off[ -]?vocal(?:$|[^a-z0-9])"),
    Regex("(?i)(?:^|[^a-z0-9])karaoke(?:$|[^a-z0-9])"),
    Regex("伴奏"),
    Regex("纯音乐"),
    Regex("純音樂"),
)
private val RECORDING_ACOUSTIC_PATTERNS = listOf(
    Regex("(?i)(?:^|[^a-z0-9])acoustic(?:$|[^a-z0-9])"),
    Regex("(?i)(?:^|[^a-z0-9])unplugged(?:$|[^a-z0-9])"),
    Regex("不插电"),
    Regex("不插電"),
)
private val RECORDING_DEMO_PATTERNS = listOf(
    Regex("(?i)(?:^|[^a-z0-9])demo(?:$|[^a-z0-9])"),
    Regex("小样"),
    Regex("小樣"),
)
private val RECORDING_SOLO_PATTERNS = listOf(
    Regex("(?i)(?:^|[^a-z0-9])solo(?:$|[^a-z0-9])"),
    Regex("独唱"),
    Regex("獨唱"),
)
