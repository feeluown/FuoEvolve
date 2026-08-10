package org.feeluown.mobile.provider.qqmusic

@OptIn(ExperimentalUnsignedTypes::class)
internal fun decodeQqLyricPayload(raw: String?): String? {
    val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
    val decoded = if (qqEncryptedHexRegex.matches(value) && value.length % 16 == 0) {
        decryptQqQrc(value) ?: return null
    } else {
        value
    }
    return normalizeQqLyricText(decoded).takeIf(String::isNotBlank)
}

@OptIn(ExperimentalUnsignedTypes::class)
internal fun decryptQqQrc(encrypted: String): String? = runCatching {
    val cipherBytes = encrypted.hexToByteArrayOrNull() ?: return@runCatching null
    if (cipherBytes.isEmpty() || cipherBytes.size % 8 != 0) return@runCatching null

    val key = QQ_QRC_KEY.encodeToByteArray()
    val schedule = Array(3) { Array(16) { UByteArray(6) } }
    QQMusicQrcDes.tripleDESKeySetup(key, schedule, QQMusicQrcDes.DECRYPT)
    val plain = ByteArray(cipherBytes.size)
    val output = ByteArray(8)
    var offset = 0
    while (offset < cipherBytes.size) {
        QQMusicQrcDes.tripleDESCrypt(cipherBytes.copyOfRange(offset, offset + 8), output, schedule)
        output.copyInto(plain, destinationOffset = offset)
        offset += 8
    }
    qrcInflate(plain)?.decodeToString()
}.getOrNull()

internal fun normalizeQqLyricText(raw: String): String {
    val lyric = extractQrcXmlLyric(raw) ?: raw
    return lyric.lineSequence().joinToString("\n", transform = ::normalizeQrcLine).trim()
}

private fun normalizeQrcLine(originalLine: String): String {
    val line = originalLine.trim()
    val headerMatch = qrcLineHeaderRegex.find(line) ?: return originalLine
    val header = headerMatch.value
    val body = line.substring(headerMatch.range.last + 1)
    val timestamps = qrcWordTimestampRegex.findAll(body).toList()
    if (timestamps.isEmpty()) return line

    // QQ QRC puts each word timestamp after the corresponding text, e.g.
    // [1000,1000]你(1000,200)好(1200,300). The app's YRC parser expects
    // the timestamp before the word, so move each timing token in front of its
    // text instead of only adding the third YRC field. Otherwise the text before
    // the first timestamp (the first word of every line) is dropped and all word
    // timings are shifted by one word.
    if (timestamps.first().range.first > 0) {
        return buildString {
            append(header)
            var textStart = 0
            timestamps.forEach { timestamp ->
                val text = body.substring(textStart, timestamp.range.first)
                appendNormalizedWordTimestamp(timestamp)
                append(text)
                textStart = timestamp.range.last + 1
            }
            if (textStart < body.length) {
                append(body.substring(textStart))
            }
        }
    }

    // Already prefix-timed input (YRC-like) only needs the QQ two-field timing
    // normalized to the parser's three-field form.
    return header + qrcWordTimestampRegex.replace(body) { match ->
        "(${match.groupValues[1]},${match.groupValues[2]},0)"
    }
}

private fun StringBuilder.appendNormalizedWordTimestamp(match: MatchResult) {
    append('(')
    append(match.groupValues[1])
    append(',')
    append(match.groupValues[2])
    append(",0)")
}

private fun extractQrcXmlLyric(raw: String): String? {
    val match = qrcXmlLyricRegex.find(raw) ?: return null
    return decodeXmlEntities(match.groupValues[1])
}

private fun decodeXmlEntities(value: String): String = value
    .replace("&#13;", "\r")
    .replace("&#10;", "\n")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")

private fun String.hexToByteArrayOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val result = ByteArray(length / 2)
    var index = 0
    while (index < length) {
        val high = this[index].digitToIntOrNull(16) ?: return null
        val low = this[index + 1].digitToIntOrNull(16) ?: return null
        result[index / 2] = ((high shl 4) or low).toByte()
        index += 2
    }
    return result
}

internal expect fun qrcInflate(data: ByteArray): ByteArray?

private const val QQ_QRC_KEY = "!@#)(*$%123ZXC!@!@#)(NHL"
private val qqEncryptedHexRegex = Regex("""^[0-9A-Fa-f]+$""")
private val qrcLineHeaderRegex = Regex("""^\[\d+,\d+]""")
private val qrcWordTimestampRegex = Regex("""\((\d+),(\d+)(?:,\d+)?\)""")
private val qrcXmlLyricRegex = Regex("LyricContent=\"([^\"]*)\"")
