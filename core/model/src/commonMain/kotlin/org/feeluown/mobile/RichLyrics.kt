package org.feeluown.mobile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs

/**
 * Composes provider lyric tracks into the app's lyric transport format.
 * The primary LRC/YRC/QRC-derived track keeps its timing. Romanization is kept
 * as an independent rich track so word timing can be rendered above the primary
 * lyric, while translation and annotation remain lower secondary lines.
 */
fun composeRichLyrics(
    main: String,
    translation: String? = null,
    romanization: String? = null,
    annotation: String? = null,
): String {
    val primary = stripStructuredYrcMetadata(main).trimEnd()
    if (primary.isBlank()) return primary

    val romanizationTrack = romanization
        ?.let(::stripStructuredYrcMetadata)
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val secondaryTracks = listOf(translation, annotation)
        .mapNotNull { raw ->
            raw?.let(::stripStructuredYrcMetadata)
                ?.trim()
                ?.takeIf(String::isNotBlank)
        }

    if (romanizationTrack == null && secondaryTracks.isEmpty()) return primary
    if (romanizationTrack == null && secondaryTracks.size == 1) {
        return composeRichLyricsTransport(primary, secondaryTracks.first(), null)
    }

    val primaryLines = parseTimedRichTrack(primary)
    val parsedTracks = secondaryTracks.map(::parseTimedRichTrack).filter { it.isNotEmpty() }
    val secondary = when {
        secondaryTracks.isEmpty() -> null
        primaryLines.isEmpty() || parsedTracks.isEmpty() -> secondaryTracks.first()
        else -> buildString {
            primaryLines.forEachIndexed { index, line ->
                val values = parsedTracks.mapNotNull { track ->
                    alignedSecondaryText(track, line.timeMs, index, primaryLines.size)
                }
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .filter { it != line.text.trim() }
                    .distinct()
                values.forEach { value ->
                    append(formatRichLrcTimestamp(line.timeMs))
                    append(value)
                    append('\n')
                }
            }
        }.trimEnd().takeIf(String::isNotBlank)
    }

    return composeRichLyricsTransport(
        main = primary,
        translation = secondary,
        romanization = romanizationTrack,
    )
}

private fun composeRichLyricsTransport(
    main: String,
    translation: String?,
    romanization: String?,
): String {
    val trimmedMain = main.trimEnd()
    val trimmedRomanization = romanization?.trim()?.takeIf(String::isNotBlank)
    val trimmedTranslation = translation?.trim()?.takeIf(String::isNotBlank)
    if (trimmedRomanization == null && trimmedTranslation == null) return trimmedMain
    return buildString {
        append(trimmedMain)
        trimmedRomanization?.let {
            append("\n__FUO_LYRIC_ROMANIZATION__\n")
            append(it)
        }
        trimmedTranslation?.let {
            append("\n__FUO_LYRIC_TRANSLATION__\n")
            append(it)
        }
    }
}

private data class TimedRichText(val timeMs: Long, val text: String)

/**
 * NetEase YRC may prepend JSON metadata lines such as composer/arranger credits.
 * They are not lyric text and the YRC parser intentionally does not render them.
 * Remove them before falling back to the LRC parser as well, otherwise an
 * instrumental track with no word-timed YRC lines exposes the raw JSON in UI.
 */
private fun stripStructuredYrcMetadata(raw: String): String {
    val lines = raw.lines()
    if (lines.none(::isStructuredYrcMetadataLine)) return raw
    return lines.filterNot(::isStructuredYrcMetadataLine).joinToString("\n")
}

private fun isStructuredYrcMetadataLine(rawLine: String): Boolean {
    val line = rawLine.trim()
    if (!line.startsWith('{') || !line.endsWith('}')) return false
    val root = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return false
    if (root["t"]?.jsonPrimitive?.longOrNull == null) return false
    val content = runCatching { root["c"]?.jsonArray }.getOrNull() ?: return false
    return content.any { entry ->
        val value = runCatching { entry.jsonObject["tx"]?.jsonPrimitive?.content }.getOrNull()
        !value.isNullOrBlank()
    }
}

private fun parseTimedRichTrack(raw: String): List<TimedRichText> = buildList {
    raw.lineSequence().forEach { originalLine ->
        val line = originalLine.trim()
        if (line.isBlank()) return@forEach

        richWordLineRegex.matchEntire(line)?.let { match ->
            val time = match.groupValues[1].toLongOrNull() ?: return@let
            val text = match.groupValues[3].replace(richWordTimestampRegex, "").trim()
            if (text.isNotBlank()) add(TimedRichText(time, text))
            return@forEach
        }

        val timestamps = richLrcTimestampRegex.findAll(line).toList()
        if (timestamps.isEmpty()) return@forEach
        val text = line.replace(richLrcTimestampRegex, "").trim()
        if (text.isBlank()) return@forEach
        timestamps.forEach { timestamp ->
            parseRichLrcTime(timestamp)?.let { time -> add(TimedRichText(time, text)) }
        }
    }
}.sortedBy(TimedRichText::timeMs)

private fun alignedSecondaryText(
    track: List<TimedRichText>,
    primaryTimeMs: Long,
    primaryIndex: Int,
    primarySize: Int,
): String? {
    val nearest = track.minByOrNull { abs(it.timeMs - primaryTimeMs) }
    if (nearest != null && abs(nearest.timeMs - primaryTimeMs) <= RICH_LYRIC_ALIGNMENT_TOLERANCE_MS) {
        return nearest.text
    }
    return track.getOrNull(primaryIndex)
        ?.takeIf { track.size == primarySize && abs(it.timeMs - primaryTimeMs) <= RICH_LYRIC_INDEX_TOLERANCE_MS }
        ?.text
}

private fun parseRichLrcTime(match: MatchResult): Long? {
    val minutes = match.groupValues[1].toLongOrNull() ?: return null
    val seconds = match.groupValues[2].toLongOrNull() ?: return null
    val fraction = match.groupValues[3]
        .takeIf(String::isNotBlank)
        ?.padEnd(3, '0')
        ?.take(3)
        ?.toLongOrNull()
        ?: 0L
    return minutes * 60_000L + seconds * 1_000L + fraction
}

private fun formatRichLrcTimestamp(timeMs: Long): String {
    val normalized = timeMs.coerceAtLeast(0L)
    val minutes = normalized / 60_000L
    val seconds = (normalized % 60_000L) / 1_000L
    val millis = normalized % 1_000L
    return "[${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}.${millis.toString().padStart(3, '0')}]"
}

private val richLrcTimestampRegex = Regex("""\[(\d{1,3}):(\d{1,2})(?:\.(\d{1,3}))?]""")
private val richWordLineRegex = Regex("""^\[(\d+),(\d+)](.*)$""")
private val richWordTimestampRegex = Regex("""\(\d+,\d+(?:,\d+)?\)""")
private const val RICH_LYRIC_ALIGNMENT_TOLERANCE_MS = 350L
private const val RICH_LYRIC_INDEX_TOLERANCE_MS = 1_500L
