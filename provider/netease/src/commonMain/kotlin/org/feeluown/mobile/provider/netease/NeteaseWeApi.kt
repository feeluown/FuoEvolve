package org.feeluown.mobile.provider.netease

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class NeteaseWeApiPayload(
    val params: String,
    val encSecKey: String,
)

/**
 * Minimal WeAPI implementation used by the NetEase account endpoints.
 *
 * NetEase uses AES-CBC twice and then RSA-encrypts the second AES key. Keeping
 * this implementation in commonMain makes account validation behave the same
 * on Android and iOS without introducing platform-specific crypto APIs.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object NeteaseWeApi {
    private const val FIRST_AES_KEY = "0CoJUm6Qyw8W8jud"
    private const val AES_IV = "0102030405060708"
    private const val RSA_PUBLIC_EXPONENT = 0x10001
    private const val RSA_MODULUS =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615" +
            "bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf" +
            "695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46" +
            "bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b" +
            "8e289dc6935b3ece0462db0a22b8e7"

    fun encrypt(json: String, secretKey: String = createSecretKey()): NeteaseWeApiPayload {
        require(secretKey.length == 16) { "WeAPI AES key must contain 16 characters" }
        val firstPass = aesCbcEncrypt(
            plainText = json.encodeToByteArray(),
            key = FIRST_AES_KEY.encodeToByteArray(),
            iv = AES_IV.encodeToByteArray(),
        )
        val secondPass = aesCbcEncrypt(
            plainText = Base64.encode(firstPass).encodeToByteArray(),
            key = secretKey.encodeToByteArray(),
            iv = AES_IV.encodeToByteArray(),
        )
        return NeteaseWeApiPayload(
            params = Base64.encode(secondPass),
            encSecKey = rsaEncrypt(secretKey.encodeToByteArray()),
        )
    }

    private fun createSecretKey(): String = buildString(16) {
        neteaseSecureRandomBytes(16).forEach { byte ->
            append(SECRET_KEY_ALPHABET[byte.toInt() and 0x0f])
        }
    }

    private fun aesCbcEncrypt(plainText: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == 16) { "WeAPI AES key must contain 16 bytes" }
        require(iv.size == 16) { "WeAPI AES IV must contain 16 bytes" }
        val padding = 16 - plainText.size % 16
        val padded = ByteArray(plainText.size + padding)
        plainText.copyInto(padded)
        padded.fill(padding.toByte(), plainText.size)

        val expandedKey = expandKey(key)
        val encrypted = ByteArray(padded.size)
        var previous = iv.copyOf()
        var offset = 0
        while (offset < padded.size) {
            val block = ByteArray(16) { index ->
                (padded[offset + index].toInt() xor previous[index].toInt()).toByte()
            }
            encryptBlock(block, expandedKey)
            block.copyInto(encrypted, offset)
            previous = block
            offset += 16
        }
        return encrypted
    }

    private fun encryptBlock(block: ByteArray, expandedKey: ByteArray) {
        addRoundKey(block, expandedKey, 0)
        for (round in 1 until 10) {
            subBytes(block)
            shiftRows(block)
            mixColumns(block)
            addRoundKey(block, expandedKey, round)
        }
        subBytes(block)
        shiftRows(block)
        addRoundKey(block, expandedKey, 10)
    }

    private fun expandKey(key: ByteArray): ByteArray {
        val expanded = ByteArray(176)
        key.copyInto(expanded)
        var generated = 16
        var roundConstant = 1
        while (generated < expanded.size) {
            val temporary = IntArray(4) { index -> expanded[generated - 4 + index].unsigned() }
            if (generated % 16 == 0) {
                val first = temporary[0]
                temporary[0] = S_BOX[temporary[1]]
                temporary[1] = S_BOX[temporary[2]]
                temporary[2] = S_BOX[temporary[3]]
                temporary[3] = S_BOX[first]
                temporary[0] = temporary[0] xor RCON[roundConstant]
                roundConstant += 1
            }
            repeat(4) { index ->
                expanded[generated] = (
                    expanded[generated - 16].unsigned() xor temporary[index]
                    ).toByte()
                generated += 1
            }
        }
        return expanded
    }

    private fun addRoundKey(state: ByteArray, expandedKey: ByteArray, round: Int) {
        val offset = round * 16
        repeat(16) { index ->
            state[index] = (state[index].unsigned() xor expandedKey[offset + index].unsigned()).toByte()
        }
    }

    private fun subBytes(state: ByteArray) {
        repeat(16) { index -> state[index] = S_BOX[state[index].unsigned()].toByte() }
    }

    private fun shiftRows(state: ByteArray) {
        val original = state.copyOf()
        repeat(4) { row ->
            repeat(4) { column ->
                state[column * 4 + row] = original[((column + row) % 4) * 4 + row]
            }
        }
    }

    private fun mixColumns(state: ByteArray) {
        repeat(4) { column ->
            val offset = column * 4
            val a0 = state[offset].unsigned()
            val a1 = state[offset + 1].unsigned()
            val a2 = state[offset + 2].unsigned()
            val a3 = state[offset + 3].unsigned()
            state[offset] = (multiplyByTwo(a0) xor multiplyByTwo(a1) xor a1 xor a2 xor a3).toByte()
            state[offset + 1] = (a0 xor multiplyByTwo(a1) xor multiplyByTwo(a2) xor a2 xor a3).toByte()
            state[offset + 2] = (a0 xor a1 xor multiplyByTwo(a2) xor multiplyByTwo(a3) xor a3).toByte()
            state[offset + 3] = (multiplyByTwo(a0) xor a0 xor a1 xor a2 xor multiplyByTwo(a3)).toByte()
        }
    }

    private fun multiplyByTwo(value: Int): Int =
        ((value shl 1) xor if (value and 0x80 != 0) 0x1b else 0) and 0xff

    private fun rsaEncrypt(secretKey: ByteArray): String {
        val modulus = hexToBytes(RSA_MODULUS).dropLeadingZero()
        val message = ByteArray(modulus.size)
        secretKey.reversedArray().copyInto(message, modulus.size - secretKey.size)
        val encrypted = modPow(message, RSA_PUBLIC_EXPONENT, modulus)
        return encrypted.toHexString()
    }

    private fun modPow(base: ByteArray, exponent: Int, modulus: ByteArray): ByteArray {
        var result = ByteArray(modulus.size).apply { this[lastIndex] = 1 }
        var current = base
        var remaining = exponent
        while (remaining > 0) {
            if (remaining and 1 == 1) {
                result = multiplyModulo(result, current, modulus)
            }
            remaining = remaining ushr 1
            if (remaining > 0) {
                current = multiplyModulo(current, current, modulus)
            }
        }
        return result
    }

    /** Multiplies two 2048-bit values using binary double-and-add. */
    private fun multiplyModulo(left: ByteArray, right: ByteArray, modulus: ByteArray): ByteArray {
        var result = ByteArray(modulus.size)
        var addend = left
        for (index in right.indices.reversed()) {
            var bits = right[index].unsigned()
            repeat(8) {
                if (bits and 1 == 1) result = addModulo(result, addend, modulus)
                addend = addModulo(addend, addend, modulus)
                bits = bits ushr 1
            }
        }
        return result
    }

    private fun addModulo(left: ByteArray, right: ByteArray, modulus: ByteArray): ByteArray {
        val sum = ByteArray(modulus.size)
        var carry = 0
        for (index in sum.indices.reversed()) {
            val value = left[index].unsigned() + right[index].unsigned() + carry
            sum[index] = value.toByte()
            carry = value ushr 8
        }
        return if (carry != 0 || compareUnsigned(sum, modulus) >= 0) {
            subtractUnsigned(sum, modulus)
        } else {
            sum
        }
    }

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        for (index in left.indices) {
            val difference = left[index].unsigned() - right[index].unsigned()
            if (difference != 0) return difference
        }
        return 0
    }

    private fun subtractUnsigned(left: ByteArray, right: ByteArray): ByteArray {
        val result = ByteArray(left.size)
        var borrow = 0
        for (index in left.indices.reversed()) {
            var value = left[index].unsigned() - right[index].unsigned() - borrow
            if (value < 0) {
                value += 256
                borrow = 1
            } else {
                borrow = 0
            }
            result[index] = value.toByte()
        }
        return result
    }

    private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        (value.substring(index * 2, index * 2 + 2).toInt(16)).toByte()
    }

    private fun ByteArray.dropLeadingZero(): ByteArray =
        if (firstOrNull()?.unsigned() == 0) copyOfRange(1, size) else this

    private fun ByteArray.toHexString(): String = buildString(size * 2) {
        for (byte in this@toHexString) {
            val value = byte.unsigned()
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }

    private fun Byte.unsigned(): Int = toInt() and 0xff

    private const val SECRET_KEY_ALPHABET = "0123456789abcdef"
    private const val HEX_DIGITS = "0123456789abcdef"

    private val RCON = intArrayOf(
        0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36,
    )

    private val S_BOX = intArrayOf(
        0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
        0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
        0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
        0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
        0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
        0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
        0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
        0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
        0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
        0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
        0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
        0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
        0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
        0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
        0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
        0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
    )
}
