package org.feeluown.mobile

import kotlin.math.abs

/**
 * Refines only candidates that have exactly the same legacy replacement score.
 * It never changes the score itself or which candidates pass the configured threshold.
 */
internal fun sortReplacementScoreTies(
    origin: MusicTrack,
    ranked: List<ReplacementCandidate>,
): List<ReplacementCandidate> {
    if (ranked.size < 2) return ranked
    return ranked
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<ReplacementCandidate>> { it.value.score }
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
