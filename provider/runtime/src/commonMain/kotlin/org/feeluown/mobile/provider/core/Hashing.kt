package org.feeluown.mobile.provider.core

/**
 * MD5 is used only for third-party request signatures (for example Bilibili's
 * WBI and QQ Music's RPC signature). It is not used for password or credential
 * protection.
 */
fun md5Hex(value: String): String {
    val input = value.encodeToByteArray()
    val paddedLength = ((input.size + 9 + 63) / 64) * 64
    val padded = ByteArray(paddedLength)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    val bitLength = input.size.toLong() * 8
    repeat(8) { index ->
        padded[padded.size - 8 + index] = (bitLength ushr (index * 8)).toByte()
    }

    var a = 0x67452301
    var b = 0xefcdab89.toInt()
    var c = 0x98badcfe.toInt()
    var d = 0x10325476

    var offset = 0
    while (offset < padded.size) {
        val words = IntArray(16) { wordIndex ->
            val base = offset + wordIndex * 4
            (padded[base].toInt() and 0xff) or
                ((padded[base + 1].toInt() and 0xff) shl 8) or
                ((padded[base + 2].toInt() and 0xff) shl 16) or
                ((padded[base + 3].toInt() and 0xff) shl 24)
        }
        val originalA = a
        val originalB = b
        val originalC = c
        val originalD = d
        repeat(64) { index ->
            val (function, wordIndex) = when (index) {
                in 0..15 -> ((b and c) or (b.inv() and d)) to index
                in 16..31 -> ((d and b) or (d.inv() and c)) to ((5 * index + 1) % 16)
                in 32..47 -> (b xor c xor d) to ((3 * index + 5) % 16)
                else -> (c xor (b or d.inv())) to ((7 * index) % 16)
            }
            val next = d
            d = c
            c = b
            val rotated = rotateLeft(
                a + function + K[index] + words[wordIndex],
                SHIFT[index],
            )
            b += rotated
            a = next
        }
        a += originalA
        b += originalB
        c += originalC
        d += originalD
        offset += 64
    }

    return buildString(32) {
        appendLittleEndian(a)
        appendLittleEndian(b)
        appendLittleEndian(c)
        appendLittleEndian(d)
    }
}

fun base64DecodeToString(value: String): String =
    base64Decode(value).decodeToString()

private fun base64Decode(value: String): ByteArray {
    val clean = value.filterNot { it == '\r' || it == '\n' || it == ' ' || it == '\t' }
    if (clean.isEmpty()) return ByteArray(0)
    val output = ArrayList<Byte>((clean.length * 3) / 4)
    var buffer = 0
    var bits = 0
    clean.forEach { character ->
        if (character == '=') return@forEach
        val digit = BASE64_ALPHABET.indexOf(character)
        if (digit < 0) return@forEach
        buffer = (buffer shl 6) or digit
        bits += 6
        if (bits >= 8) {
            bits -= 8
            output += ((buffer ushr bits) and 0xff).toByte()
        }
    }
    return output.toByteArray()
}

private fun rotateLeft(value: Int, distance: Int): Int =
    (value shl distance) or (value ushr (32 - distance))

private val SHIFT = intArrayOf(
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
)

private val K = intArrayOf(
    0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db, 0xc1bdceee.toInt(),
    0xf57c0faf.toInt(), 0x4787c62a, 0xa8304613.toInt(), 0xfd469501.toInt(),
    0x698098d8, 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
    0x6b901122, 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821,
    0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51, 0xe9b6c7aa.toInt(),
    0xd62f105d.toInt(), 0x02441453, 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
    0x21e1cde6, 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed,
    0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9, 0x8d2a4c8a.toInt(),
    0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122, 0xfde5380c.toInt(),
    0xa4beea44.toInt(), 0x4bdecfa9, 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
    0x289b7ec6, 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05,
    0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8, 0xc4ac5665.toInt(),
    0xf4292244.toInt(), 0x432aff97, 0xab9423a7.toInt(), 0xfc93a039.toInt(),
    0x655b59c3, 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
    0x6fa87e4f, 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1,
    0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb, 0xeb86d391.toInt(),
)

private fun StringBuilder.appendLittleEndian(value: Int) {
    repeat(4) { index ->
        val byte = (value ushr (index * 8)) and 0xff
        append(HEX[byte ushr 4])
        append(HEX[byte and 0x0f])
    }
}

private const val HEX = "0123456789abcdef"
private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
