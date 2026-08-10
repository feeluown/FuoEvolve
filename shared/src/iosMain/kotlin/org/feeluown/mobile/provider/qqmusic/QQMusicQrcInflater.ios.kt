package org.feeluown.mobile.provider.qqmusic

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDataCompressionAlgorithmZlib
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun qrcInflate(data: ByteArray): ByteArray? = runCatching {
    if (data.isEmpty()) return@runCatching null
    val compressed = data.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = data.size.toULong())
    }
    val decompressed = compressed.decompressedDataUsingAlgorithm(
        NSDataCompressionAlgorithmZlib,
        error = null,
    ) ?: return@runCatching null
    val size = decompressed.length.toInt()
    if (size <= 0) return@runCatching null
    ByteArray(size).also { output ->
        output.usePinned { pinned ->
            memcpy(pinned.addressOf(0), decompressed.bytes, decompressed.length)
        }
    }
}.getOrNull()
