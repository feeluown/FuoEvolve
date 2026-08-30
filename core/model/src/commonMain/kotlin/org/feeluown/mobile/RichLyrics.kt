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
        else -> {
            val alignedTracks = parsedTracks.map { track ->
                alignSecondaryRichTrack(primaryLines, track)
            }
            buildString {
                primaryLines.forEachIndexed { index, line ->
                    val values = alignedTracks.mapNotNull { track -> track[index] }
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

private fun alignSecondaryRichTrack(
    primary: List<TimedRichText>,
    secondary: List<TimedRichText>,
): Map<Int, String> {
    if (primary.isEmpty() || secondary.isEmpty()) return emptyMap()
    val aligned = mutableMapOf<Int, String>()
    var minimumPrimaryIndex = 0

    secondary.forEachIndexed { secondaryIndex, secondaryLine ->
        val secondaryIsCredit = isLikelyLyricCreditText(secondaryLine.text)
        val nearestIndex = (minimumPrimaryIndex until primary.size)
            .asSequence()
            .filter { primaryIndex ->
                abs(primary[primaryIndex].timeMs - secondaryLine.timeMs) <= RICH_LYRIC_ALIGNMENT_TOLERANCE_MS
            }
            .minWithOrNull(
                compareBy<Int> { primaryIndex ->
                    if (isLikelyLyricCreditText(primary[primaryIndex].text) == secondaryIsCredit) 0 else 1
                }.thenBy { primaryIndex ->
                    abs(primary[primaryIndex].timeMs - secondaryLine.timeMs)
                }.thenBy { it },
            )
        val fallbackIndex = if (
            nearestIndex == null &&
            primary.size == secondary.size &&
            secondaryIndex >= minimumPrimaryIndex &&
            abs(primary[secondaryIndex].timeMs - secondaryLine.timeMs) <= RICH_LYRIC_INDEX_TOLERANCE_MS
        ) {
            secondaryIndex
        } else {
            null
        }
        val primaryIndex = nearestIndex ?: fallbackIndex ?: return@forEachIndexed
        aligned[primaryIndex] = secondaryLine.text
        minimumPrimaryIndex = primaryIndex + 1
    }

    return aligned
}

private fun isLikelyLyricCreditText(text: String): Boolean =
    lyricCreditPrefixRegex.containsMatchIn(text.trim())

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
private val lyricCreditPrefixRegex = Regex(
    """^\s*(?:(?:作词|作詞|作曲|编曲|編曲|填词|填詞|混音|制作人|製作人|监制|監製|原唱|演唱|歌手|和声|和聲|录音|錄音|母带|母帶|吉他|贝斯|貝斯|鼓|弦乐|弦樂|词|詞|曲)|(?:lyrics?|lyricist|composer|arranger|producer|vocals?|singer|mix(?:ing)?|master(?:ing)?|written\s+by|music\s+by))\s*(?:[:：/]|-\s+)""",
    RegexOption.IGNORE_CASE,
)
private const val RICH_LYRIC_ALIGNMENT_TOLERANCE_MS = 350L
private const val RICH_LYRIC_INDEX_TOLERANCE_MS = 1_500L
