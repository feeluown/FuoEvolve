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
    return lyric.lineSequence().joinToString("\n") { line ->
        if (qrcLineHeaderRegex.containsMatchIn(line.trim())) {
            qrcWordTimestampRegex.replace(line) { match ->
                "(${match.groupValues[1]},${match.groupValues[2]},0)"
            }
        } else {
            line
        }
    }.trim()
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
private val qrcWordTimestampRegex = Regex("""\((\d+),(\d+)\)""")
private val qrcXmlLyricRegex = Regex("LyricContent=\"([^\"]*)\"")
