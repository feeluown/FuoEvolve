package org.feeluown.mobile.provider.qqmusic

import kotlin.experimental.or

/*
 * QRC uses QQ Music's DES-compatible key schedule rather than JCE DESede.
 * Adapted for Kotlin Multiplatform from the GPL-3.0 implementation in
 * cy745/lddc-android, itself based on WXRIW/QQMusicDecoder.
 * SPDX-License-Identifier: GPL-3.0-only
 */
@OptIn(ExperimentalUnsignedTypes::class)
internal object QQMusicQrcDes {
    const val ENCRYPT: UInt = 1u
    const val DECRYPT: UInt = 0u

    private fun bitNum(a: ByteArray, b: Int, c: Int): UInt =
        ((((a[(b / 32) * 4 + 3 - (b % 32) / 8].toInt() ushr (7 - (b % 8)))) and 1) shl c).toUInt()

    private fun bitNumIntr(a: UInt, b: Int, c: Int): Byte =
        ((((a shr (31 - b)) and 1u).toInt()) shl c).toByte()

    private fun bitNumIntl(a: UInt, b: Int, c: Int): UInt =
        ((a shl b) and 0x80000000u) shr c

    private fun sboxBit(a: Byte): UInt =
        ((a.toInt() and 0x20) or ((a.toInt() and 0x1f) ushr 1) or ((a.toInt() and 0x01) shl 4)).toUInt()

    private val sbox1 = byteArrayOf(
        14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
        0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
        4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
        15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
    )
    private val sbox2 = byteArrayOf(
        15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
        3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
        0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
        13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
    )
    private val sbox3 = byteArrayOf(
        10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
        13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
        13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
        1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
    )
    private val sbox4 = byteArrayOf(
        7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
        13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
        10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
        3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
    )
    private val sbox5 = byteArrayOf(
        2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
        14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
        4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
        11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
    )
    private val sbox6 = byteArrayOf(
        12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
        10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
        9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
        4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
    )
    private val sbox7 = byteArrayOf(
        4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
        13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
        1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
        6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
    )
    private val sbox8 = byteArrayOf(
        13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
        1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
        7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
        2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
    )

    private fun keySchedule(key: ByteArray, schedule: Array<UByteArray>, mode: UInt) {
        val keyRndShift = uintArrayOf(1u, 1u, 2u, 2u, 2u, 2u, 2u, 2u, 1u, 2u, 2u, 2u, 2u, 2u, 2u, 1u)
        val keyPermC = uintArrayOf(
            56u, 48u, 40u, 32u, 24u, 16u, 8u, 0u, 57u, 49u, 41u, 33u, 25u, 17u,
            9u, 1u, 58u, 50u, 42u, 34u, 26u, 18u, 10u, 2u, 59u, 51u, 43u, 35u,
        )
        val keyPermD = uintArrayOf(
            62u, 54u, 46u, 38u, 30u, 22u, 14u, 6u, 61u, 53u, 45u, 37u, 29u, 21u,
            13u, 5u, 60u, 52u, 44u, 36u, 28u, 20u, 12u, 4u, 27u, 19u, 11u, 3u,
        )
        val keyComp = uintArrayOf(
            13u, 16u, 10u, 23u, 0u, 4u, 2u, 27u, 14u, 5u, 20u, 9u,
            22u, 18u, 11u, 3u, 25u, 7u, 15u, 6u, 26u, 19u, 12u, 1u,
            40u, 51u, 30u, 36u, 46u, 54u, 29u, 39u, 50u, 44u, 32u, 47u,
            43u, 48u, 38u, 55u, 33u, 52u, 45u, 41u, 49u, 35u, 28u, 31u,
        )

        var c = 0u
        var d = 0u
        for (i in 0 until 28) c = c or bitNum(key, keyPermC[i].toInt(), 31 - i)
        for (i in 0 until 28) d = d or bitNum(key, keyPermD[i].toInt(), 31 - i)

        for (i in 0 until 16) {
            c = ((c shl keyRndShift[i].toInt()) or (c shr (28 - keyRndShift[i].toInt()))) and 0xfffffff0u
            d = ((d shl keyRndShift[i].toInt()) or (d shr (28 - keyRndShift[i].toInt()))) and 0xfffffff0u

            val toGen = if (mode == DECRYPT) 15 - i else i
            schedule[toGen].fill(0u)
            for (j in 0 until 24) {
                schedule[toGen][j / 8] = (
                    schedule[toGen][j / 8].toInt() or bitNumIntr(c, keyComp[j].toInt(), 7 - (j % 8)).toInt()
                    ).toUByte()
            }
            for (j in 24 until 48) {
                schedule[toGen][j / 8] = (
                    schedule[toGen][j / 8].toInt() or bitNumIntr(d, keyComp[j].toInt() - 27, 7 - (j % 8)).toInt()
                    ).toUByte()
            }
        }
    }

    private fun ip(state: UIntArray, block: ByteArray) {
        state[0] = bitNum(block, 57, 31) or bitNum(block, 49, 30) or
            bitNum(block, 41, 29) or bitNum(block, 33, 28) or
            bitNum(block, 25, 27) or bitNum(block, 17, 26) or
            bitNum(block, 9, 25) or bitNum(block, 1, 24) or
            bitNum(block, 59, 23) or bitNum(block, 51, 22) or
            bitNum(block, 43, 21) or bitNum(block, 35, 20) or
            bitNum(block, 27, 19) or bitNum(block, 19, 18) or
            bitNum(block, 11, 17) or bitNum(block, 3, 16) or
            bitNum(block, 61, 15) or bitNum(block, 53, 14) or
            bitNum(block, 45, 13) or bitNum(block, 37, 12) or
            bitNum(block, 29, 11) or bitNum(block, 21, 10) or
            bitNum(block, 13, 9) or bitNum(block, 5, 8) or
            bitNum(block, 63, 7) or bitNum(block, 55, 6) or
            bitNum(block, 47, 5) or bitNum(block, 39, 4) or
            bitNum(block, 31, 3) or bitNum(block, 23, 2) or
            bitNum(block, 15, 1) or bitNum(block, 7, 0)
        state[1] = bitNum(block, 56, 31) or bitNum(block, 48, 30) or
            bitNum(block, 40, 29) or bitNum(block, 32, 28) or
            bitNum(block, 24, 27) or bitNum(block, 16, 26) or
            bitNum(block, 8, 25) or bitNum(block, 0, 24) or
            bitNum(block, 58, 23) or bitNum(block, 50, 22) or
            bitNum(block, 42, 21) or bitNum(block, 34, 20) or
            bitNum(block, 26, 19) or bitNum(block, 18, 18) or
            bitNum(block, 10, 17) or bitNum(block, 2, 16) or
            bitNum(block, 60, 15) or bitNum(block, 52, 14) or
            bitNum(block, 44, 13) or bitNum(block, 36, 12) or
            bitNum(block, 28, 11) or bitNum(block, 20, 10) or
            bitNum(block, 12, 9) or bitNum(block, 4, 8) or
            bitNum(block, 62, 7) or bitNum(block, 54, 6) or
            bitNum(block, 46, 5) or bitNum(block, 38, 4) or
            bitNum(block, 30, 3) or bitNum(block, 22, 2) or
            bitNum(block, 14, 1) or bitNum(block, 6, 0)
    }

    private fun invIp(state: UIntArray, block: ByteArray) {
        block[3] = bitNumIntr(state[1], 7, 7) or bitNumIntr(state[0], 7, 6) or
            bitNumIntr(state[1], 15, 5) or bitNumIntr(state[0], 15, 4) or
            bitNumIntr(state[1], 23, 3) or bitNumIntr(state[0], 23, 2) or
            bitNumIntr(state[1], 31, 1) or bitNumIntr(state[0], 31, 0)
        block[2] = bitNumIntr(state[1], 6, 7) or bitNumIntr(state[0], 6, 6) or
            bitNumIntr(state[1], 14, 5) or bitNumIntr(state[0], 14, 4) or
            bitNumIntr(state[1], 22, 3) or bitNumIntr(state[0], 22, 2) or
            bitNumIntr(state[1], 30, 1) or bitNumIntr(state[0], 30, 0)
        block[1] = bitNumIntr(state[1], 5, 7) or bitNumIntr(state[0], 5, 6) or
            bitNumIntr(state[1], 13, 5) or bitNumIntr(state[0], 13, 4) or
            bitNumIntr(state[1], 21, 3) or bitNumIntr(state[0], 21, 2) or
            bitNumIntr(state[1], 29, 1) or bitNumIntr(state[0], 29, 0)
        block[0] = bitNumIntr(state[1], 4, 7) or bitNumIntr(state[0], 4, 6) or
            bitNumIntr(state[1], 12, 5) or bitNumIntr(state[0], 12, 4) or
            bitNumIntr(state[1], 20, 3) or bitNumIntr(state[0], 20, 2) or
            bitNumIntr(state[1], 28, 1) or bitNumIntr(state[0], 28, 0)
        block[7] = bitNumIntr(state[1], 3, 7) or bitNumIntr(state[0], 3, 6) or
            bitNumIntr(state[1], 11, 5) or bitNumIntr(state[0], 11, 4) or
            bitNumIntr(state[1], 19, 3) or bitNumIntr(state[0], 19, 2) or
            bitNumIntr(state[1], 27, 1) or bitNumIntr(state[0], 27, 0)
        block[6] = bitNumIntr(state[1], 2, 7) or bitNumIntr(state[0], 2, 6) or
            bitNumIntr(state[1], 10, 5) or bitNumIntr(state[0], 10, 4) or
            bitNumIntr(state[1], 18, 3) or bitNumIntr(state[0], 18, 2) or
            bitNumIntr(state[1], 26, 1) or bitNumIntr(state[0], 26, 0)
        block[5] = bitNumIntr(state[1], 1, 7) or bitNumIntr(state[0], 1, 6) or
            bitNumIntr(state[1], 9, 5) or bitNumIntr(state[0], 9, 4) or
            bitNumIntr(state[1], 17, 3) or bitNumIntr(state[0], 17, 2) or
            bitNumIntr(state[1], 25, 1) or bitNumIntr(state[0], 25, 0)
        block[4] = bitNumIntr(state[1], 0, 7) or bitNumIntr(state[0], 0, 6) or
            bitNumIntr(state[1], 8, 5) or bitNumIntr(state[0], 8, 4) or
            bitNumIntr(state[1], 16, 3) or bitNumIntr(state[0], 16, 2) or
            bitNumIntr(state[1], 24, 1) or bitNumIntr(state[0], 24, 0)
    }

    private fun f(state: UInt, keyBytes: UByteArray): UInt {
        val lrgState = UByteArray(6)
        val t1 = bitNumIntl(state, 31, 0) or ((state and 0xf0000000u) shr 1) or
            bitNumIntl(state, 4, 5) or bitNumIntl(state, 3, 6) or
            ((state and 0x0f000000u) shr 3) or bitNumIntl(state, 8, 11) or
            bitNumIntl(state, 7, 12) or ((state and 0x00f00000u) shr 5) or
            bitNumIntl(state, 12, 17) or bitNumIntl(state, 11, 18) or
            ((state and 0x000f0000u) shr 7) or bitNumIntl(state, 16, 23)
        val t2 = bitNumIntl(state, 15, 0) or ((state and 0x0000f000u) shl 15) or
            bitNumIntl(state, 20, 5) or bitNumIntl(state, 19, 6) or
            ((state and 0x00000f00u) shl 13) or bitNumIntl(state, 24, 11) or
            bitNumIntl(state, 23, 12) or ((state and 0x000000f0u) shl 11) or
            bitNumIntl(state, 28, 17) or bitNumIntl(state, 27, 18) or
            ((state and 0x0000000fu) shl 9) or bitNumIntl(state, 0, 23)

        lrgState[0] = ((t1 shr 24) and 0xffu).toUByte()
        lrgState[1] = ((t1 shr 16) and 0xffu).toUByte()
        lrgState[2] = ((t1 shr 8) and 0xffu).toUByte()
        lrgState[3] = ((t2 shr 24) and 0xffu).toUByte()
        lrgState[4] = ((t2 shr 16) and 0xffu).toUByte()
        lrgState[5] = ((t2 shr 8) and 0xffu).toUByte()
        for (i in 0..5) lrgState[i] = (lrgState[i].toInt() xor keyBytes[i].toInt()).toUByte()

        var res = (sbox1[sboxBit((lrgState[0].toInt() ushr 2).toByte()).toInt()].toUInt() shl 28) or
            (sbox2[sboxBit((((lrgState[0].toInt() and 0x03) shl 4) or (lrgState[1].toInt() ushr 4)).toByte()).toInt()].toUInt() shl 24) or
            (sbox3[sboxBit((((lrgState[1].toInt() and 0x0f) shl 2) or (lrgState[2].toInt() ushr 6)).toByte()).toInt()].toUInt() shl 20) or
            (sbox4[sboxBit((lrgState[2].toInt() and 0x3f).toByte()).toInt()].toUInt() shl 16) or
            (sbox5[sboxBit((lrgState[3].toInt() ushr 2).toByte()).toInt()].toUInt() shl 12) or
            (sbox6[sboxBit((((lrgState[3].toInt() and 0x03) shl 4) or (lrgState[4].toInt() ushr 4)).toByte()).toInt()].toUInt() shl 8) or
            (sbox7[sboxBit((((lrgState[4].toInt() and 0x0f) shl 2) or (lrgState[5].toInt() ushr 6)).toByte()).toInt()].toUInt() shl 4) or
            sbox8[sboxBit((lrgState[5].toInt() and 0x3f).toByte()).toInt()].toUInt()

        res = bitNumIntl(res, 15, 0) or bitNumIntl(res, 6, 1) or bitNumIntl(res, 19, 2) or
            bitNumIntl(res, 20, 3) or bitNumIntl(res, 28, 4) or bitNumIntl(res, 11, 5) or
            bitNumIntl(res, 27, 6) or bitNumIntl(res, 16, 7) or bitNumIntl(res, 0, 8) or
            bitNumIntl(res, 14, 9) or bitNumIntl(res, 22, 10) or bitNumIntl(res, 25, 11) or
            bitNumIntl(res, 4, 12) or bitNumIntl(res, 17, 13) or bitNumIntl(res, 30, 14) or
            bitNumIntl(res, 9, 15) or bitNumIntl(res, 1, 16) or bitNumIntl(res, 7, 17) or
            bitNumIntl(res, 23, 18) or bitNumIntl(res, 13, 19) or bitNumIntl(res, 31, 20) or
            bitNumIntl(res, 26, 21) or bitNumIntl(res, 2, 22) or bitNumIntl(res, 8, 23) or
            bitNumIntl(res, 18, 24) or bitNumIntl(res, 12, 25) or bitNumIntl(res, 29, 26) or
            bitNumIntl(res, 5, 27) or bitNumIntl(res, 21, 28) or bitNumIntl(res, 10, 29) or
            bitNumIntl(res, 3, 30) or bitNumIntl(res, 24, 31)
        return res
    }

    private fun crypt(input: ByteArray, output: ByteArray, roundKeys: Array<UByteArray>) {
        val state = UIntArray(2)
        ip(state, input)
        for (index in 0 until 15) {
            val next = state[1]
            state[1] = f(state[1], roundKeys[index]) xor state[0]
            state[0] = next
        }
        state[0] = f(state[1], roundKeys[15]) xor state[0]
        invIp(state, output)
    }

    fun tripleDESKeySetup(key: ByteArray, schedule: Array<Array<UByteArray>>, mode: UInt) {
        if (mode == ENCRYPT) {
            keySchedule(key.copyOfRange(0, 8), schedule[0], mode)
            keySchedule(key.copyOfRange(8, 16), schedule[1], DECRYPT)
            keySchedule(key.copyOfRange(16, 24), schedule[2], mode)
        } else {
            keySchedule(key.copyOfRange(16, 24), schedule[0], mode)
            keySchedule(key.copyOfRange(8, 16), schedule[1], ENCRYPT)
            keySchedule(key.copyOfRange(0, 8), schedule[2], mode)
        }
    }

    fun tripleDESCrypt(input: ByteArray, output: ByteArray, schedule: Array<Array<UByteArray>>) {
        crypt(input, output, schedule[0])
        crypt(output, output, schedule[1])
        crypt(output, output, schedule[2])
    }
}
